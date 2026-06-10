package com.kunk.singbox.repository

import android.content.Context
import android.util.Log
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.CustomRule
import com.kunk.singbox.model.DefaultRule
import com.kunk.singbox.model.DnsStrategy
import com.kunk.singbox.model.RoutingMode
import com.kunk.singbox.model.AppRule
import com.kunk.singbox.model.AppGroup
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.model.RuleSetType
import com.kunk.singbox.model.TunStack
import com.kunk.singbox.model.LatencyTestMethod
import com.kunk.singbox.model.VpnAppMode
import com.kunk.singbox.model.VpnRouteMode
import com.kunk.singbox.model.GhProxyMirror
import com.kunk.singbox.model.IpVersionMode
import com.kunk.singbox.model.AppThemeMode
import com.kunk.singbox.model.AppLanguage
import com.kunk.singbox.model.NodeSortType
import com.kunk.singbox.model.NodeFilter
import com.kunk.singbox.model.BackgroundPowerSavingDelay
import com.kunk.singbox.repository.store.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 *
 */
class SettingsRepository(private val context: Context) {

    private val settingsStore = SettingsStore.getInstance(context)

    fun getDefaultRuleSets(): List<RuleSet> {
        val geositeBase = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set"
        val geoipBase = "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set"
        return listOf(
            RuleSet(
                tag = "geosite-cn",
                type = RuleSetType.REMOTE,
                url = "$geositeBase/geosite-cn.srs",
                enabled = false,
                outboundMode = RuleSetOutboundMode.DIRECT
            ),
            RuleSet(
                tag = "geoip-cn",
                type = RuleSetType.REMOTE,
                url = "$geoipBase/geoip-cn.srs",
                enabled = false,
                outboundMode = RuleSetOutboundMode.DIRECT
            ),
            RuleSet(
                tag = "geosite-geolocation-!cn",
                type = RuleSetType.REMOTE,
                url = "$geositeBase/geosite-geolocation-!cn.srs",
                enabled = false,
                outboundMode = RuleSetOutboundMode.PROXY
            ),
            RuleSet(
                tag = "geosite-category-ads-all",
                type = RuleSetType.REMOTE,
                url = "$geositeBase/geosite-category-ads-all.srs",
                enabled = false,
                outboundMode = RuleSetOutboundMode.BLOCK
            ),
            RuleSet(
                tag = "geosite-private",
                type = RuleSetType.REMOTE,
                url = "$geositeBase/geosite-private.srs",
                enabled = false,
                outboundMode = RuleSetOutboundMode.DIRECT
            )
        )
    }

    /**
     */
    val settings: StateFlow<AppSettings> = settingsStore.settings

    /**
     */
    suspend fun reloadFromStorage() {
        settingsStore.reload()
    }

    suspend fun replaceImportedSettings(imported: AppSettings, importRules: Boolean) {
        val persisted = settingsStore.updateSettingsAndWait { current ->
            buildImportedSettings(current = current, imported = imported, importRules = importRules)
        }
        if (!persisted) {
            throw IllegalStateException("Failed to persist imported settings")
        }
        notifyRestartRequired()
    }

    private suspend fun updateSettingsAndNotifyRestart(update: (AppSettings) -> AppSettings): Boolean {
        val persisted = settingsStore.updateSettingsAndWait(update)
        if (persisted) notifyRestartRequired()
        return persisted
    }

    suspend fun setAutoConnect(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(autoConnect = value) }
    }

    suspend fun setExcludeFromRecent(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(excludeFromRecent = value) }
    }

    suspend fun setAppTheme(value: AppThemeMode) {
        settingsStore.updateSettingsAndWait { it.copy(appTheme = value) }
    }

    suspend fun setAppLanguage(value: AppLanguage) {
        settingsStore.updateSettingsAndWait { it.copy(appLanguage = value) }
    }

    suspend fun setShowNotificationSpeed(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(showNotificationSpeed = value) }
    }

    suspend fun setTunEnabled(value: Boolean) {
        updateSettingsAndNotifyRestart { it.copy(tunEnabled = value) }
    }

    suspend fun setTunStack(value: TunStack) {
        updateSettingsAndNotifyRestart { it.copy(tunStack = value) }
    }

    suspend fun setIpVersionMode(value: IpVersionMode) {
        updateSettingsAndNotifyRestart { it.copy(ipVersionMode = value) }
    }

    suspend fun setTunMtu(value: Int) {
        updateSettingsAndNotifyRestart { it.copy(tunMtu = value) }
    }

    suspend fun setTunMtuAuto(value: Boolean) {
        updateSettingsAndNotifyRestart { it.copy(tunMtuAuto = value) }
    }

    suspend fun setTunInterfaceName(value: String) {
        updateSettingsAndNotifyRestart { it.copy(tunInterfaceName = value) }
    }

    suspend fun setAutoRoute(value: Boolean) {
        updateSettingsAndNotifyRestart { it.copy(autoRoute = value) }
    }

    suspend fun setStrictRoute(value: Boolean) {
        updateSettingsAndNotifyRestart { it.copy(strictRoute = value) }
    }

    suspend fun setEndpointIndependentNat(value: Boolean) {
        updateSettingsAndNotifyRestart { it.copy(endpointIndependentNat = value) }
    }

    suspend fun setVpnRouteMode(value: VpnRouteMode) {
        updateSettingsAndNotifyRestart { it.copy(vpnRouteMode = value) }
    }

    suspend fun setVpnRouteIncludeCidrs(value: String) {
        updateSettingsAndNotifyRestart { it.copy(vpnRouteIncludeCidrs = value) }
    }

    suspend fun setVpnAppMode(value: VpnAppMode) {
        updateSettingsAndNotifyRestart { it.copy(vpnAppMode = value) }
    }

    suspend fun setVpnAllowlist(value: String) {
        updateSettingsAndNotifyRestart { it.copy(vpnAllowlist = value) }
    }

    suspend fun setVpnBlocklist(value: String) {
        updateSettingsAndNotifyRestart { it.copy(vpnBlocklist = value) }
    }

    suspend fun removePackageFromPerAppSettings(packageName: String) {
        updateSettingsAndNotifyRestart { settings ->
            removePackageFromPerAppSettings(settings, packageName)
        }
    }

    suspend fun setLocalDns(value: String) {
        updateSettingsAndNotifyRestart { it.copy(localDns = value) }
    }

    suspend fun setRemoteDns(value: String) {
        updateSettingsAndNotifyRestart { it.copy(remoteDns = value) }
    }

    suspend fun setFakeDnsEnabled(value: Boolean) {
        updateSettingsAndNotifyRestart { it.copy(fakeDnsEnabled = value) }
    }

    suspend fun setFakeIpRange(value: String?) {
        val normalized = value?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_FAKE_IP_RANGE
        updateSettingsAndNotifyRestart { it.copy(fakeIpRange = normalized) }
    }

    suspend fun setDnsStrategy(value: DnsStrategy) {
        updateSettingsAndNotifyRestart { it.copy(dnsStrategy = value) }
    }

    suspend fun setRemoteDnsStrategy(value: DnsStrategy) {
        updateSettingsAndNotifyRestart { it.copy(remoteDnsStrategy = value) }
    }

    suspend fun setDirectDnsStrategy(value: DnsStrategy) {
        updateSettingsAndNotifyRestart { it.copy(directDnsStrategy = value) }
    }

    suspend fun setServerAddressStrategy(value: DnsStrategy) {
        updateSettingsAndNotifyRestart { it.copy(serverAddressStrategy = value) }
    }

    suspend fun setDnsCacheEnabled(value: Boolean) {
        updateSettingsAndNotifyRestart { it.copy(dnsCacheEnabled = value) }
    }

    suspend fun setRoutingMode(value: RoutingMode, notifyRestartRequired: Boolean = true) {
        val persisted = settingsStore.updateSettingsAndWait { it.copy(routingMode = value) }
        if (persisted && notifyRestartRequired) {
            notifyRestartRequired()
        }
    }

    suspend fun setDefaultRule(value: DefaultRule) {
        updateSettingsAndNotifyRestart { it.copy(defaultRule = value) }
    }

    suspend fun setBlockQuic(value: Boolean) {
        updateSettingsAndNotifyRestart { it.copy(blockQuic = value) }
    }

    suspend fun setDebugLoggingEnabled(value: Boolean) {
        updateSettingsAndNotifyRestart { it.copy(debugLoggingEnabled = value) }
    }

    suspend fun setLatencyTestMethod(value: LatencyTestMethod) {
        settingsStore.updateSettingsAndWait { it.copy(latencyTestMethod = value) }
    }

    suspend fun setLatencyTestUrl(value: String) {
        settingsStore.updateSettingsAndWait { it.copy(latencyTestUrl = value) }
    }

    suspend fun setLatencyTestTimeout(value: Int) {
        settingsStore.updateSettingsAndWait { it.copy(latencyTestTimeout = value) }
    }

    suspend fun setLatencyTestConcurrency(value: Int) {
        settingsStore.updateSettingsAndWait { it.copy(latencyTestConcurrency = sanitizeLatencyTestConcurrency(value)) }
    }

    suspend fun setBypassLan(value: Boolean) {
        updateSettingsAndNotifyRestart { it.copy(bypassLan = value) }
    }

    suspend fun setIcmpEchoRoutingEnabled(value: Boolean) {
        updateSettingsAndNotifyRestart { it.copy(icmpEchoRoutingEnabled = value) }
    }

    suspend fun setWakeResetConnections(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(wakeResetConnections = value) }
    }

    suspend fun setGhProxyMirror(value: GhProxyMirror) {
        updateSettingsAndNotifyRestart { it.copy(ghProxyMirror = value) }
    }

    suspend fun setProxyPort(value: Int) {
        updateSettingsAndNotifyRestart { it.copy(proxyPort = sanitizeProxyPort(value)) }
    }

    suspend fun setAllowLan(value: Boolean) {
        updateSettingsAndNotifyRestart { it.copy(allowLan = value) }
    }

    suspend fun setAppendHttpProxy(value: Boolean) {
        updateSettingsAndNotifyRestart { it.copy(appendHttpProxy = value) }
    }

    suspend fun setCustomRules(value: List<CustomRule>) {
        updateSettingsAndNotifyRestart { it.copy(customRules = value) }
    }

    suspend fun setRuleSets(value: List<RuleSet>, notify: Boolean = true) {
        val persisted = settingsStore.updateSettingsAndWait { it.copy(ruleSets = value) }
        if (persisted && notify) {
            notifyRestartRequired()
        }
    }

    suspend fun getRuleSets(): List<RuleSet> {
        return settings.value.ruleSets
    }

    suspend fun setAppRules(value: List<AppRule>) {
        updateSettingsAndNotifyRestart { it.copy(appRules = value) }
    }

    suspend fun setAppGroups(value: List<AppGroup>) {
        updateSettingsAndNotifyRestart { it.copy(appGroups = value) }
    }

    suspend fun setRuleSetAutoUpdateEnabled(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(ruleSetAutoUpdateEnabled = value) }
    }

    suspend fun setRuleSetAutoUpdateInterval(value: Int) {
        val normalized = com.kunk.singbox.service.RuleSetAutoUpdateWorker.Companion.normalizeIntervalMinutes(value)
        settingsStore.updateSettingsAndWait { it.copy(ruleSetAutoUpdateInterval = normalized) }
    }

    suspend fun setSubscriptionUpdateTimeout(value: Int) {
        settingsStore.updateSettingsAndWait { it.copy(subscriptionUpdateTimeout = value) }
    }

    suspend fun setAutoCheckUpdate(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(autoCheckUpdate = value) }
    }

    suspend fun setBackgroundPowerSavingDelay(value: BackgroundPowerSavingDelay) {
        settingsStore.updateSettingsAndWait { it.copy(backgroundPowerSavingDelay = value) }
    }

    suspend fun setNodeFilter(value: NodeFilter) {
        settingsStore.updateSettingsAndWait { it.copy(nodeFilter = value) }
    }

    suspend fun getNodeFilter(): NodeFilter {
        return settings.value.nodeFilter
    }

    fun getNodeFilterFlow(): Flow<NodeFilter> {
        return settings.map { it.nodeFilter }
    }

    suspend fun setNodeSortType(sortType: NodeSortType) {
        settingsStore.updateSettingsAndWait { it.copy(nodeSortType = sortType) }
    }

    fun getNodeSortType(): Flow<NodeSortType> {
        return settings.map { it.nodeSortType }
    }

    suspend fun setCustomNodeOrder(nodeIds: List<String>) {
        settingsStore.updateSettingsAndWait { it.copy(customNodeOrder = nodeIds) }
    }

    fun getCustomNodeOrder(): Flow<List<String>> {
        return settings.map { it.customNodeOrder }
    }

    suspend fun checkAndMigrateRuleSets() {
        try {
            val currentSettings = settings.value

            if (currentSettings.ruleSets.isEmpty()) {
                Log.i("SettingsRepository", "Initializing default rule sets")
                setRuleSets(getDefaultRuleSets(), notify = false)
                return
            }

            val currentMirrorUrl = currentSettings.ghProxyMirror.url
            val rawPrefix = "https://raw.githubusercontent.com/"
            val cdnPrefix = "https://cdn.jsdelivr.net/gh/"

            val migratedRuleSets = currentSettings.ruleSets.map { ruleSet ->
                var updatedUrl = ruleSet.url
                var updatedTag = ruleSet.tag

                if (updatedTag.equals("geosite-ads", ignoreCase = true)) {
                    updatedTag = "geosite-category-ads-all"
                }

                if (updatedUrl.contains("geosite-ads.srs")) {
                    updatedUrl = updatedUrl.replace("geosite-ads.srs", "geosite-category-ads-all.srs")
                }

                var rawUrl = updatedUrl

                // Fix corrupted URLs: https://ghp.ci/https://raw.githubusercontent.com/...
                val proxyPrefixes = listOf(
                    "https://ghp.ci/",
                    "https://mirror.ghproxy.com/",
                    "https://ghproxy.com/",
                    "https://ghproxy.net/",
                    "https://ghfast.top/",
                    "https://gh-proxy.com/"
                )

                for (proxy in proxyPrefixes) {
                    if (rawUrl.startsWith(proxy)) {
                        val afterProxy = rawUrl.removePrefix(proxy)
                        if (afterProxy.startsWith("http://") || afterProxy.startsWith("https://")) {
                            // Extract clean path from corrupted URL
                            val withoutProtocol = afterProxy
                                .removePrefix("https://")
                                .removePrefix("http://")
                            val firstSlash = withoutProtocol.indexOf('/')
                            if (firstSlash > 0) {
                                rawUrl = "/" + withoutProtocol.substring(firstSlash)
                            } else {
                                rawUrl = "/" + withoutProtocol
                            }
                        } else if (afterProxy.startsWith("/")) {
                            rawUrl = afterProxy
                        } else {
                            rawUrl = afterProxy
                        }
                        break
                    }
                }

                // Handle CDN format
                if (rawUrl.startsWith(cdnPrefix)) {
                    val path = rawUrl.removePrefix(cdnPrefix)
                    val parts = path.split("@", limit = 2)
                    if (parts.size == 2) {
                        val userRepo = parts[0]
                        val branchPath = parts[1]
                        rawUrl = "$rawPrefix$userRepo/$branchPath"
                    }
                }

                // Handle remaining raw.githubusercontent.com URLs
                if (rawUrl.contains("raw.githubusercontent.com") && !rawUrl.startsWith(rawPrefix)) {
                    val path = rawUrl.substringAfter("raw.githubusercontent.com/")
                    if (path.startsWith("http://") || path.startsWith("https://")) {
                        val cleanPath = path
                            .removePrefix("https://")
                            .removePrefix("http://")
                        rawUrl = rawPrefix + cleanPath
                    } else if (path.contains("raw.githubusercontent.com/")) {
                        rawUrl = rawPrefix + path.substringAfter("raw.githubusercontent.com/")
                    } else {
                        rawUrl = rawPrefix + path
                    }
                }

                if (currentMirrorUrl.contains("cdn.jsdelivr.net")) {
                    if (rawUrl.startsWith(rawPrefix)) {
                        val path = rawUrl.removePrefix(rawPrefix)
                        val parts = path.split("/", limit = 4)
                        if (parts.size >= 4) {
                            val user = parts[0]
                            val repo = parts[1]
                            val branch = parts[2]
                            val filePath = parts[3]
                            updatedUrl = "$cdnPrefix$user/$repo@$branch/$filePath"
                        }
                    }
                } else if (currentMirrorUrl != rawPrefix) {
                    if (rawUrl.startsWith(rawPrefix)) {
                        updatedUrl = rawUrl.replace(rawPrefix, currentMirrorUrl)
                    }
                } else {
                    updatedUrl = rawUrl
                }

                if (updatedUrl != ruleSet.url || updatedTag != ruleSet.tag) {
                    ruleSet.copy(tag = updatedTag, url = updatedUrl)
                } else {
                    ruleSet
                }
            }.distinctBy { it.tag }

            if (migratedRuleSets != currentSettings.ruleSets) {
                Log.i("SettingsRepository", "Saving migrated rule sets")
                setRuleSets(migratedRuleSets, notify = false)
            }
        } catch (e: Exception) {
            Log.e("SettingsRepository", "Error during migration", e)
        }
    }

    private fun notifyRestartRequired() {
        _restartRequiredEvents.tryEmit(Unit)
    }

    companion object {
        private const val MIN_PROXY_PORT = 1
        private const val MAX_PROXY_PORT = 65535
        private const val MIN_LATENCY_TEST_CONCURRENCY = 1
        private const val MAX_LATENCY_TEST_CONCURRENCY = 20

        private val _restartRequiredEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val restartRequiredEvents: SharedFlow<Unit> = _restartRequiredEvents.asSharedFlow()

        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }

        internal fun sanitizeProxyPortForTest(value: Int): Int {
            return sanitizeProxyPort(value)
        }

        internal fun sanitizeLatencyTestConcurrencyForTest(value: Int): Int {
            return sanitizeLatencyTestConcurrency(value)
        }

        internal fun buildImportedSettingsForTest(
            current: AppSettings,
            imported: AppSettings,
            importRules: Boolean
        ): AppSettings {
            return buildImportedSettings(current = current, imported = imported, importRules = importRules)
        }

        private fun buildImportedSettings(
            current: AppSettings,
            imported: AppSettings,
            importRules: Boolean
        ): AppSettings {
            val normalizedRuleSetAutoUpdateInterval =
                com.kunk.singbox.service.RuleSetAutoUpdateWorker.normalizeIntervalMinutes(
                    imported.ruleSetAutoUpdateInterval
                )
            val normalized = imported.copy(
                fakeIpRange = normalizeFakeIpRange(imported),
                proxyPort = sanitizeProxyPort(imported.proxyPort),
                latencyTestConcurrency = sanitizeLatencyTestConcurrency(imported.latencyTestConcurrency),
                ruleSetAutoUpdateInterval = normalizedRuleSetAutoUpdateInterval
            )

            return if (importRules) {
                normalized
            } else {
                normalized.copy(
                    customRules = current.customRules,
                    ruleSets = current.ruleSets,
                    appRules = current.appRules,
                    appGroups = current.appGroups,
                    ruleSetAutoUpdateEnabled = current.ruleSetAutoUpdateEnabled,
                    ruleSetAutoUpdateInterval = current.ruleSetAutoUpdateInterval
                )
            }
        }

        private fun normalizeFakeIpRange(settings: AppSettings): String {
            val fakeIpRange: String? = runCatching { settings.fakeIpRange }.getOrNull()
            return fakeIpRange?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_FAKE_IP_RANGE
        }

        private fun sanitizeProxyPort(value: Int): Int {
            return value.coerceIn(MIN_PROXY_PORT, MAX_PROXY_PORT)
        }

        private fun sanitizeLatencyTestConcurrency(value: Int): Int {
            return value.coerceIn(MIN_LATENCY_TEST_CONCURRENCY, MAX_LATENCY_TEST_CONCURRENCY)
        }
    }
}
