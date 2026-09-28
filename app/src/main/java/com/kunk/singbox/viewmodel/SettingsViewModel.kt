package com.kunk.singbox.viewmodel

import com.kunk.singbox.R
import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.manager.VpnServiceManager
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.CustomRule
import com.kunk.singbox.model.DefaultRule
import com.kunk.singbox.model.DnsStrategy
import com.kunk.singbox.model.AppThemeMode
import com.kunk.singbox.model.AppThemeStyle
import com.kunk.singbox.model.AppLanguage
import com.kunk.singbox.model.ExportData
import com.kunk.singbox.model.ExportDataSummary
import com.kunk.singbox.model.ImportOptions
import com.kunk.singbox.model.ImportResult
import com.kunk.singbox.model.IpVersionMode
import com.kunk.singbox.model.RoutingMode
import com.kunk.singbox.model.AppGroup
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetType
import com.kunk.singbox.model.TunStack
import com.kunk.singbox.model.TrafficCaptureMode
import com.kunk.singbox.model.LatencyTestMethod
import com.kunk.singbox.model.VpnAppMode
import com.kunk.singbox.model.VpnRouteMode
import com.kunk.singbox.model.GhProxyMirror
import com.kunk.singbox.model.BackgroundPowerSavingDelay
import com.kunk.singbox.repository.DataExportRepository
import com.kunk.singbox.repository.RuleSetRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.repository.PerAppPolicyUpdateResult
import com.kunk.singbox.service.RuleSetAutoUpdateWorker
import com.kunk.singbox.service.root.RootCapabilityReport
import com.kunk.singbox.service.root.RootServiceConnection
import com.kunk.singbox.ui.components.AppNotificationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DefaultRuleSetDownloadState(
    val isActive: Boolean = false,
    val total: Int = 0,
    val completed: Int = 0,
    val currentTag: String? = null,
    val cancelled: Boolean = false
)

sealed interface PerAppPolicyApplyState {
    data object Idle : PerAppPolicyApplyState
    data class Applying(val revision: Long) : PerAppPolicyApplyState
    data class Failed(val revision: Long, val message: String) : PerAppPolicyApplyState
}

@Suppress("LargeClass")
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository.getInstance(application)
    private val ruleSetRepository = RuleSetRepository.getInstance(application)
    private val dataExportRepository = DataExportRepository.getInstance(application)

    private val _downloadingRuleSets = MutableStateFlow<Set<String>>(emptySet())
    val downloadingRuleSets: StateFlow<Set<String>> = _downloadingRuleSets.asStateFlow()

    private val _defaultRuleSetDownloadState = MutableStateFlow(DefaultRuleSetDownloadState())
    val defaultRuleSetDownloadState: StateFlow<DefaultRuleSetDownloadState> = _defaultRuleSetDownloadState.asStateFlow()

    private var defaultRuleSetDownloadJob: Job? = null
    private val defaultRuleSetDownloadTags = mutableSetOf<String>()

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    val settings: StateFlow<AppSettings> = repository.settings
    private val _perAppPolicyApplyState = MutableStateFlow<PerAppPolicyApplyState>(PerAppPolicyApplyState.Idle)
    val perAppPolicyApplyState: StateFlow<PerAppPolicyApplyState> = _perAppPolicyApplyState.asStateFlow()
    private val _rootCapabilityError = MutableStateFlow("")
    val rootCapabilityError: StateFlow<String> = _rootCapabilityError.asStateFlow()

    fun ensureDefaultRuleSetsReady() {
        viewModelScope.launch {
            if (defaultRuleSetDownloadJob?.isActive == true) return@launch
            val currentRuleSets = repository.getRuleSets()
            if (currentRuleSets.isNotEmpty()) return@launch

            val defaultRuleSets = repository.getDefaultRuleSets()
            repository.setRuleSets(defaultRuleSets)
            startDefaultRuleSetDownload(defaultRuleSets)
        }
    }

    fun cancelDefaultRuleSetDownload() {
        defaultRuleSetDownloadJob?.cancel()
        defaultRuleSetDownloadJob = null
        clearDefaultRuleSetDownloadTags()
        _defaultRuleSetDownloadState.value = _defaultRuleSetDownloadState.value.copy(
            isActive = false,
            currentTag = null,
            cancelled = true
        )
    }

    private fun startDefaultRuleSetDownload(ruleSets: List<RuleSet>) {
        defaultRuleSetDownloadJob?.cancel()
        defaultRuleSetDownloadTags.clear()

        defaultRuleSetDownloadJob = viewModelScope.launch {
            val remoteRuleSets = ruleSets.filter { it.type == RuleSetType.REMOTE }
            if (remoteRuleSets.isEmpty()) {
                _defaultRuleSetDownloadState.value = DefaultRuleSetDownloadState()
                return@launch
            }

            var completedCount = 0
            _defaultRuleSetDownloadState.value = DefaultRuleSetDownloadState(
                isActive = true,
                total = remoteRuleSets.size,
                completed = 0
            )

            try {
                for (ruleSet in remoteRuleSets) {
                    ensureActive()
                    _defaultRuleSetDownloadState.value = _defaultRuleSetDownloadState.value.copy(
                        currentTag = ruleSet.tag
                    )

                    defaultRuleSetDownloadTags.add(ruleSet.tag)
                    markRuleSetDownloading(ruleSet.tag)
                    try {
                        ruleSetRepository.prefetchRuleSet(ruleSet, forceUpdate = false, allowNetwork = true)
                    } finally {
                        markRuleSetDownloadFinished(ruleSet.tag)
                        defaultRuleSetDownloadTags.remove(ruleSet.tag)
                    }

                    completedCount += 1
                    _defaultRuleSetDownloadState.value = _defaultRuleSetDownloadState.value.copy(
                        completed = completedCount
                    )
                }

                _defaultRuleSetDownloadState.value = _defaultRuleSetDownloadState.value.copy(
                    isActive = false,
                    currentTag = null,
                    cancelled = false
                )
            } catch (e: CancellationException) {
                clearDefaultRuleSetDownloadTags()
                _defaultRuleSetDownloadState.value = _defaultRuleSetDownloadState.value.copy(
                    isActive = false,
                    currentTag = null,
                    cancelled = true
                )
            }
        }
    }

    private fun markRuleSetDownloading(tag: String) {
        _downloadingRuleSets.update { it + tag }
    }

    private fun markRuleSetDownloadFinished(tag: String) {
        _downloadingRuleSets.update { it - tag }
    }

    private fun tryMarkRuleSetDownloading(tag: String): Boolean {
        var added = false
        _downloadingRuleSets.update { current ->
            if (current.contains(tag)) {
                current
            } else {
                added = true
                current + tag
            }
        }
        return added
    }

    private fun clearDefaultRuleSetDownloadTags() {
        defaultRuleSetDownloadTags.forEach { tag ->
            markRuleSetDownloadFinished(tag)
        }
        defaultRuleSetDownloadTags.clear()
    }

    fun setAutoConnect(value: Boolean) {
        viewModelScope.launch { repository.setAutoConnect(value) }
    }

    fun setNetworkAutoSwitchEnabled(value: Boolean) {
        viewModelScope.launch { repository.setNetworkAutoSwitchEnabled(value) }
    }

    fun setTrustedWifiSsids(value: String) {
        viewModelScope.launch { repository.setTrustedWifiSsids(value) }
    }

    fun setExcludeFromRecent(value: Boolean) {
        viewModelScope.launch { repository.setExcludeFromRecent(value) }
    }

    fun setAppTheme(value: AppThemeMode) {
        viewModelScope.launch { repository.setAppTheme(value) }
    }

    fun setAppThemeStyle(value: AppThemeStyle) {
        viewModelScope.launch { repository.setAppThemeStyle(value) }
    }

    fun setAppLanguage(value: AppLanguage) {
        viewModelScope.launch { repository.setAppLanguage(value) }
    }

    fun setAutoCheckUpdate(value: Boolean) {
        viewModelScope.launch { repository.setAutoCheckUpdate(value) }
    }

    fun setBackgroundPowerSavingDelay(value: BackgroundPowerSavingDelay) {
        viewModelScope.launch {
            repository.setBackgroundPowerSavingDelay(value)

            com.kunk.singbox.lifecycle.AppLifecycleObserver.setBackgroundTimeout(value.delayMs)
        }
    }

    fun setShowNotificationSpeed(value: Boolean) {
        viewModelScope.launch {
            repository.setShowNotificationSpeed(value)

            if (com.kunk.singbox.ipc.SingBoxRemote.isRunning.value) {
                try {
                    val intent = android.content.Intent(getApplication(), com.kunk.singbox.service.SingBoxService::class.java).apply {
                        action = com.kunk.singbox.service.SingBoxService.ACTION_UPDATE_SETTING
                        putExtra(com.kunk.singbox.service.SingBoxService.EXTRA_SETTING_KEY, "show_notification_speed")
                        putExtra(com.kunk.singbox.service.SingBoxService.EXTRA_SETTING_VALUE_BOOL, value)
                    }
                    getApplication<Application>().startService(intent)
                } catch (e: Exception) {
                    android.util.Log.e("SettingsViewModel", "Failed to update service setting", e)
                }
            }
        }
    }

    fun setTunEnabled(value: Boolean) {
        viewModelScope.launch { repository.setTunEnabled(value) }
    }

    fun setTrafficCaptureMode(value: TrafficCaptureMode) {
        viewModelScope.launch {
            if (value == TrafficCaptureMode.ROOT_TRANSPARENT) {
                val error = checkRootCapability()
                if (error != null) {
                    _rootCapabilityError.value = error
                    return@launch
                }
            }
            _rootCapabilityError.value = ""
            repository.setTrafficCaptureMode(value)
            VpnServiceManager.refreshTunSetting(getApplication())
        }
    }

    private suspend fun checkRootCapability(): String? {
        val connection = RootServiceConnection(getApplication()) { }
        return try {
            val report = RootCapabilityReport.fromBundle(connection.bind().capabilityReport)
            when {
                !report.supported -> report.error.ifBlank { "Root TPROXY is unavailable" }
                settings.value.ipVersionMode == IpVersionMode.IPV6_ONLY &&
                    (!report.tproxyIpv6 || !report.redirectIpv6) ->
                    "Complete IPv6 transparent proxy support is unavailable on this device"
                else -> null
            }
        } catch (error: Exception) {
            error.message ?: getApplication<Application>().getString(R.string.settings_root_check_failed)
        } finally {
            connection.unbind()
        }
    }

    fun setTunStack(value: TunStack) {
        viewModelScope.launch { repository.setTunStack(value) }
    }

    fun setIpVersionMode(value: IpVersionMode) {
        viewModelScope.launch { repository.setIpVersionMode(value) }
    }

    fun setTunMtu(value: Int) {
        viewModelScope.launch { repository.setTunMtu(value) }
    }

    fun setTunMtuAuto(value: Boolean) {
        viewModelScope.launch { repository.setTunMtuAuto(value) }
    }

    fun setAutoRoute(value: Boolean) {
        viewModelScope.launch { repository.setAutoRoute(value) }
    }

    fun setStrictRoute(value: Boolean) {
        viewModelScope.launch { repository.setStrictRoute(value) }
    }

    fun setVpnRouteMode(value: VpnRouteMode) {
        viewModelScope.launch { repository.setVpnRouteMode(value) }
    }

    fun setVpnRouteIncludeCidrs(value: String) {
        viewModelScope.launch { repository.setVpnRouteIncludeCidrs(value) }
    }

    fun setVpnAppMode(value: VpnAppMode) {
        viewModelScope.launch { applyPerAppPolicyUpdate(repository.setVpnAppMode(value)) }
    }

    fun setVpnAllowlist(value: String) {
        viewModelScope.launch { applyPerAppPolicyUpdate(repository.setVpnAllowlist(value)) }
    }

    fun setVpnBlocklist(value: String) {
        viewModelScope.launch { applyPerAppPolicyUpdate(repository.setVpnBlocklist(value)) }
    }

    fun setAutoIncludeNewAppsInPerAppRules(value: Boolean) {
        viewModelScope.launch { repository.setAutoIncludeNewAppsInPerAppRules(value) }
    }

    fun retryPerAppPolicyApply() {
        viewModelScope.launch {
            val revision = settings.value.perAppPolicyRevision
            _perAppPolicyApplyState.value = PerAppPolicyApplyState.Applying(revision)
            VpnServiceManager.applyPerAppRuleChangeIfRunning(getApplication(), revision)
                .onSuccess { _perAppPolicyApplyState.value = PerAppPolicyApplyState.Idle }
                .onFailure { error ->
                    _perAppPolicyApplyState.value = PerAppPolicyApplyState.Failed(
                        revision,
                        error.message ?: getApplication<Application>().getString(R.string.settings_per_app_apply_failed)
                    )
                }
        }
    }

    private suspend fun applyPerAppPolicyUpdate(result: Result<PerAppPolicyUpdateResult>) {
        val update = result.getOrElse { error ->
            _perAppPolicyApplyState.value = PerAppPolicyApplyState.Failed(
                settings.value.perAppPolicyRevision,
                error.message ?: getApplication<Application>().getString(R.string.settings_per_app_persist_failed)
            )
            return
        }
        if (!update.runtimeChanged) {
            _perAppPolicyApplyState.value = PerAppPolicyApplyState.Idle
            return
        }
        _perAppPolicyApplyState.value = PerAppPolicyApplyState.Applying(update.revision)
        VpnServiceManager.applyPerAppRuleChangeIfRunning(getApplication(), update.revision)
            .onSuccess { _perAppPolicyApplyState.value = PerAppPolicyApplyState.Idle }
            .onFailure { error ->
                _perAppPolicyApplyState.value = PerAppPolicyApplyState.Failed(
                    update.revision,
                    error.message ?: getApplication<Application>().getString(R.string.settings_per_app_apply_failed)
                )
            }
    }

    fun setLocalDns(value: String) {
        viewModelScope.launch { repository.setLocalDns(value) }
    }

    fun setRemoteDns(value: String) {
        viewModelScope.launch { repository.setRemoteDns(value) }
    }

    fun setFakeDnsEnabled(value: Boolean) {
        viewModelScope.launch { repository.setFakeDnsEnabled(value) }
    }

    fun setFakeIpRange(value: String) {
        viewModelScope.launch { repository.setFakeIpRange(value) }
    }

    fun setDnsStrategy(value: DnsStrategy) {
        viewModelScope.launch { repository.setDnsStrategy(value) }
    }

    fun setRemoteDnsStrategy(value: DnsStrategy) {
        viewModelScope.launch { repository.setRemoteDnsStrategy(value) }
    }

    fun setDirectDnsStrategy(value: DnsStrategy) {
        viewModelScope.launch { repository.setDirectDnsStrategy(value) }
    }

    fun setServerAddressStrategy(value: DnsStrategy) {
        viewModelScope.launch { repository.setServerAddressStrategy(value) }
    }

    fun setDnsCacheEnabled(value: Boolean) {
        viewModelScope.launch { repository.setDnsCacheEnabled(value) }
    }

    fun setRoutingMode(value: RoutingMode, notifyRestartRequired: Boolean = true) {
        viewModelScope.launch { repository.setRoutingMode(value, notifyRestartRequired) }
    }

    suspend fun setRoutingModeAndWait(value: RoutingMode, notifyRestartRequired: Boolean = true) {
        repository.setRoutingMode(value, notifyRestartRequired)
    }

    fun setDefaultRule(value: DefaultRule) {
        viewModelScope.launch { repository.setDefaultRule(value) }
    }

    fun setBlockQuic(value: Boolean) {
        viewModelScope.launch { repository.setBlockQuic(value) }
    }

    fun setDebugLoggingEnabled(value: Boolean) {
        viewModelScope.launch { repository.setDebugLoggingEnabled(value) }
    }

    fun setLatencyTestMethod(value: LatencyTestMethod) {
        viewModelScope.launch { repository.setLatencyTestMethod(value) }
    }

    fun setLatencyTestUrl(value: String) {
        viewModelScope.launch { repository.setLatencyTestUrl(value) }
    }

    fun setLatencyTestTimeout(value: Int) {
        viewModelScope.launch { repository.setLatencyTestTimeout(value) }
    }

    fun setBypassLan(value: Boolean) {
        viewModelScope.launch { repository.setBypassLan(value) }
    }

    fun setIcmpEchoRoutingEnabled(value: Boolean) {
        viewModelScope.launch { repository.setIcmpEchoRoutingEnabled(value) }
    }

    fun updateLatencyTestConcurrency(value: Int) {
        viewModelScope.launch { repository.setLatencyTestConcurrency(value) }
    }

    fun updateLatencyTestTimeout(value: Int) {
        viewModelScope.launch { repository.setLatencyTestTimeout(value) }
    }

    fun setGhProxyMirror(value: GhProxyMirror) {
        viewModelScope.launch { repository.setGhProxyMirror(value) }
    }

    fun setSubscriptionUpdateTimeout(value: Int) {
        viewModelScope.launch { repository.setSubscriptionUpdateTimeout(value) }
    }

    fun updateProxyPort(value: Int) {
        viewModelScope.launch { repository.setProxyPort(value) }
    }

    fun updateAllowLan(value: Boolean) {
        viewModelScope.launch { repository.setAllowLan(value) }
    }

    fun updateAppendHttpProxy(value: Boolean) {
        viewModelScope.launch { repository.setAppendHttpProxy(value) }
    }

    fun addCustomRule(rule: CustomRule) {
        viewModelScope.launch {
            val currentRules = settings.value.customRules.toMutableList()
            currentRules.add(rule)
            repository.setCustomRules(currentRules)
        }
    }

    fun updateCustomRule(rule: CustomRule) {
        viewModelScope.launch {
            val currentRules = settings.value.customRules.toMutableList()
            val index = currentRules.indexOfFirst { it.id == rule.id }
            if (index != -1) {
                currentRules[index] = rule
                repository.setCustomRules(currentRules)
            }
        }
    }

    fun deleteCustomRule(ruleId: String) {
        viewModelScope.launch {
            val currentRules = settings.value.customRules.toMutableList()
            currentRules.removeAll { it.id == ruleId }
            repository.setCustomRules(currentRules)
        }
    }

    @Suppress("CognitiveComplexMethod")
    fun addRuleSet(ruleSet: RuleSet, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val normalizedRuleSet = RuleSetRepository.normalizeRuleSetForSave(
                ruleSet = ruleSet,
                mirrorUrl = settings.value.ghProxyMirror.url
            )

            val exists = repository.getRuleSets().any { it.tag == normalizedRuleSet.tag }
            if (exists) {
                onResult(false, getApplication<Application>().getString(R.string.rulesets_exists, normalizedRuleSet.tag))
            } else {
                if (normalizedRuleSet.type == RuleSetType.REMOTE) {
                    markRuleSetDownloading(normalizedRuleSet.tag)
                }

                val downloadOk = try {
                    // ponytail: 按 tag 命名的旧缓存无法证明 URL 来源；新建远程规则集总要实际下载一次。
                    ruleSetRepository.prefetchRuleSet(
                        normalizedRuleSet,
                        forceUpdate = normalizedRuleSet.type == RuleSetType.REMOTE,
                        allowNetwork = true
                    )
                } finally {
                    if (normalizedRuleSet.type == RuleSetType.REMOTE) {
                        markRuleSetDownloadFinished(normalizedRuleSet.tag)
                    }
                }

                val latestSets = repository.getRuleSets().toMutableList()
                if (latestSets.any { it.tag == normalizedRuleSet.tag }) {
                    onResult(false, getApplication<Application>().getString(R.string.rulesets_exists, normalizedRuleSet.tag))
                    return@launch
                }
                latestSets.add(normalizedRuleSet.copy(enabled = normalizedRuleSet.enabled && downloadOk))
                repository.setRuleSets(latestSets)

                if (downloadOk) {
                    onResult(true, getApplication<Application>().getString(R.string.rulesets_added_downloaded, normalizedRuleSet.tag))
                } else {
                    onResult(true, getApplication<Application>().getString(R.string.rulesets_added_failed, normalizedRuleSet.tag))
                }
            }
        }
    }

    fun addRuleSets(ruleSets: List<RuleSet>, onResult: (Int) -> Unit = { _ -> }) {
        viewModelScope.launch {
            val addedRuleSets = mutableListOf<RuleSet>()
            val seenTags = repository.getRuleSets().mapTo(mutableSetOf()) { it.tag }

            ruleSets.forEach { ruleSet ->
                val normalized = RuleSetRepository.normalizeRuleSetForSave(
                    ruleSet = ruleSet,
                    mirrorUrl = settings.value.ghProxyMirror.url
                )
                if (!seenTags.add(normalized.tag)) return@forEach

                if (normalized.type == RuleSetType.REMOTE) {
                    markRuleSetDownloading(normalized.tag)
                }
                val ready = try {
                    ruleSetRepository.prefetchRuleSet(
                        normalized,
                        forceUpdate = normalized.type == RuleSetType.REMOTE,
                        allowNetwork = normalized.type == RuleSetType.REMOTE
                    )
                } finally {
                    if (normalized.type == RuleSetType.REMOTE) {
                        markRuleSetDownloadFinished(normalized.tag)
                    }
                }

                addedRuleSets.add(normalized.copy(enabled = normalized.enabled && ready))
            }

            val latestSets = repository.getRuleSets()
            val latestTags = latestSets.mapTo(mutableSetOf()) { it.tag }
            val newSets = addedRuleSets.filter { latestTags.add(it.tag) }
            if (newSets.isNotEmpty()) repository.setRuleSets(latestSets + newSets)
            onResult(newSets.size)
        }
    }

    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod")
    fun updateRuleSet(ruleSet: RuleSet) {
        viewModelScope.launch {
            val normalizedRuleSet = RuleSetRepository.normalizeRuleSetForSave(
                ruleSet = ruleSet,
                mirrorUrl = settings.value.ghProxyMirror.url
            )
            val currentSets = repository.getRuleSets().toMutableList()
            val index = currentSets.indexOfFirst { it.id == normalizedRuleSet.id }
            if (index == -1) return@launch

            val previous = currentSets[index]
            val sourceChanged = previous.type != normalizedRuleSet.type ||
                previous.tag != normalizedRuleSet.tag ||
                previous.format != normalizedRuleSet.format ||
                previous.url != normalizedRuleSet.url ||
                previous.path != normalizedRuleSet.path
            val needsReadyRuleSet = normalizedRuleSet.enabled
            val isRemote = normalizedRuleSet.type == RuleSetType.REMOTE
            if (needsReadyRuleSet && isRemote && !tryMarkRuleSetDownloading(normalizedRuleSet.tag)) {
                return@launch
            }

            val ready = if (needsReadyRuleSet) {
                try {
                    ruleSetRepository.prefetchRuleSet(
                        normalizedRuleSet,
                        forceUpdate = isRemote && sourceChanged,
                        allowNetwork = isRemote,
                        requireSource = isRemote && !previous.enabled
                    )
                } finally {
                    if (isRemote) {
                        markRuleSetDownloadFinished(normalizedRuleSet.tag)
                    }
                }
            } else {
                true
            }
            if (!ready) {
                Log.w("SettingsViewModel", "Rule set ${normalizedRuleSet.tag} was not enabled: file not ready")
                AppNotificationManager.showMessage(
                    getApplication(),
                    getApplication<Application>().getString(R.string.rulesets_enable_failed, normalizedRuleSet.tag)
                )
                return@launch
            }

            // ponytail: 下载可耗时；重新读取列表，避免用旧快照覆盖期间的设置变更。
            val latestSets = repository.getRuleSets().toMutableList()
            val latestIndex = latestSets.indexOfFirst { it.id == normalizedRuleSet.id }
            if (latestIndex == -1 || latestSets[latestIndex] != currentSets[index]) return@launch
            latestSets[latestIndex] = normalizedRuleSet
            repository.setRuleSets(latestSets)
        }
    }

    fun deleteRuleSet(ruleSetId: String) {
        viewModelScope.launch {
            val currentSets = settings.value.ruleSets.toMutableList()
            currentSets.removeAll { it.id == ruleSetId }
            repository.setRuleSets(currentSets)
        }
    }

    fun deleteRuleSets(ruleSetIds: List<String>) {
        viewModelScope.launch {
            val idsToDelete = ruleSetIds.toSet()
            val currentSets = settings.value.ruleSets.toMutableList()
            currentSets.removeAll { it.id in idsToDelete }
            repository.setRuleSets(currentSets)
        }
    }

    fun setRuleSetAutoUpdateEnabled(value: Boolean) {
        viewModelScope.launch {
            val currentSettings = repository.settings.first()
            repository.setRuleSetAutoUpdateEnabled(value)

            if (value && currentSettings.ruleSetAutoUpdateInterval > 0) {
                RuleSetAutoUpdateWorker.schedule(
                    getApplication(),
                    currentSettings.ruleSetAutoUpdateInterval
                )
            } else {
                RuleSetAutoUpdateWorker.cancel(getApplication())
            }
        }
    }

    fun setRuleSetAutoUpdateInterval(value: Int) {
        viewModelScope.launch {
            val currentSettings = repository.settings.first()
            repository.setRuleSetAutoUpdateInterval(value)

            if (currentSettings.ruleSetAutoUpdateEnabled && value > 0) {
                RuleSetAutoUpdateWorker.schedule(getApplication(), value)
            }
        }
    }

    fun reorderRuleSets(newOrder: List<RuleSet>) {
        viewModelScope.launch {
            // ponytail: 拖拽快照可能过时；只取排序，启用状态等字段始终取最新值。
            val latestSets = repository.getRuleSets()
            val byId = latestSets.associateBy { it.id }
            val orderedIds = newOrder.map { it.id }.toSet()
            repository.setRuleSets(newOrder.mapNotNull { byId[it.id] } + latestSets.filter { it.id !in orderedIds })
        }
    }

    fun addAppGroup(group: AppGroup) {
        viewModelScope.launch {
            applyAppRoutingChange { repository.upsertAppGroup(group) }
        }
    }

    fun updateAppGroup(group: AppGroup) {
        viewModelScope.launch {
            applyAppRoutingChange { repository.upsertAppGroup(group) }
        }
    }

    fun deleteAppGroup(groupId: String) {
        viewModelScope.launch {
            val currentGroups = settings.value.appGroups.toMutableList()
            currentGroups.removeAll { it.id == groupId }
            applyAppRoutingChange { repository.setAppGroups(currentGroups) }
        }
    }

    fun toggleAppGroupEnabled(groupId: String) {
        viewModelScope.launch {
            val currentGroups = settings.value.appGroups.toMutableList()
            val index = currentGroups.indexOfFirst { it.id == groupId }
            if (index != -1) {
                val group = currentGroups[index]
                currentGroups[index] = group.copy(enabled = !group.enabled)
                applyAppRoutingChange { repository.setAppGroups(currentGroups) }
            }
        }
    }

    private suspend fun applyAppRoutingChange(update: suspend () -> Boolean) {
        if (!update()) return
        val revision = settings.value.perAppPolicyRevision
        _perAppPolicyApplyState.value = PerAppPolicyApplyState.Applying(revision)
        VpnServiceManager.applyPerAppRuleChangeIfRunning(getApplication())
            .onSuccess { _perAppPolicyApplyState.value = PerAppPolicyApplyState.Idle }
            .onFailure { error ->
                Log.e("SettingsViewModel", "自动应用应用分流配置失败", error)
                _perAppPolicyApplyState.value = PerAppPolicyApplyState.Failed(
                    revision,
                    error.message ?: getApplication<Application>().getString(R.string.settings_per_app_apply_failed)
                )
            }
    }

    fun exportData(uri: Uri) {
        viewModelScope.launch {
            _exportState.value = ExportState.Exporting

            val result = dataExportRepository.exportToFile(uri)

            _exportState.value = if (result.isSuccess) {
                ExportState.Success
            } else {
                ExportState.Error(result.exceptionOrNull()?.message ?: getApplication<Application>().getString(R.string.export_failed))
            }
        }
    }

    fun validateImportFile(uri: Uri) {
        viewModelScope.launch {
            _importState.value = ImportState.Validating

            val result = dataExportRepository.validateFromFile(uri)

            _importState.value = if (result.isSuccess) {
                val exportData = result.getOrThrow()
                val summary = dataExportRepository.getExportDataSummary(exportData)
                ImportState.Preview(uri, exportData, summary)
            } else {
                ImportState.Error(result.exceptionOrNull()?.message ?: getApplication<Application>().getString(R.string.data_validation_failed))
            }
        }
    }

    fun confirmImport(uri: Uri, options: ImportOptions = ImportOptions()) {
        viewModelScope.launch {
            _importState.value = ImportState.Importing

            val result = dataExportRepository.importFromFile(uri, options)

            _importState.value = if (result.isSuccess) {
                when (val importResult = result.getOrThrow()) {
                    is ImportResult.Success -> ImportState.Success(
                        profilesImported = importResult.profilesImported,
                        nodesImported = importResult.nodesImported,
                        settingsImported = importResult.settingsImported
                    )
                    is ImportResult.Failed -> ImportState.Error(importResult.error)
                }
            } else {
                ImportState.Error(result.exceptionOrNull()?.message ?: getApplication<Application>().getString(R.string.import_failed))
            }
        }
    }

    fun resetExportState() {
        _exportState.value = ExportState.Idle
    }

    fun resetImportState() {
        _importState.value = ImportState.Idle
    }
}

sealed class ExportState {
    object Idle : ExportState()
    object Exporting : ExportState()
    object Success : ExportState()
    data class Error(val message: String) : ExportState()
}

sealed class ImportState {
    object Idle : ImportState()
    object Validating : ImportState()
    data class Preview(
        val uri: Uri,
        val data: ExportData,
        val summary: ExportDataSummary
    ) : ImportState()
    object Importing : ImportState()
    data class Success(
        val profilesImported: Int,
        val nodesImported: Int,
        val settingsImported: Boolean
    ) : ImportState()
    data class Error(val message: String) : ImportState()
}
