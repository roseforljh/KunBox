package com.kunk.singbox.viewmodel

import com.kunk.singbox.R
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.ProfileType
import com.kunk.singbox.model.SubscriptionUpdateResult
import com.kunk.singbox.repository.ConfigRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProfilesViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val MAX_IMPORT_CONTENT_BYTES = 1024 * 1024
    }

    private val configRepository = ConfigRepository.getInstance(application)

    private var importJob: Job? = null

    val profiles: StateFlow<List<ProfileUi>> = configRepository.profiles
    val allNodes: StateFlow<List<NodeUi>> = configRepository.allNodes
    val activeProfileId: StateFlow<String?> = configRepository.activeProfileId

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    private fun emitToast(message: String) {
        _toastEvents.tryEmit(message)
    }

    private fun emitDnsOverrideCompatibilityWarning(dnsOverride: String?) {
        ConfigRepository.buildDnsOverrideCompatibilityWarning(dnsOverride)?.let { emitToast(it) }
    }

    fun setActiveProfile(profileId: String) {
        configRepository.setActiveProfile(profileId)

        // Only show toast when VPN is running
        val isVpnRunning = SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value
        if (isVpnRunning) {
            val name = profiles.value.find { it.id == profileId }?.name
            if (!name.isNullOrBlank()) {
                emitToast(getApplication<Application>().getString(R.string.profiles_updated) + ": $name")
            }

            viewModelScope.launch {
                configRepository.setActiveProfileAndWait(profileId)
                val currentNodeId = configRepository.activeNodeId.value
                if (currentNodeId != null) {
                    Log.i("ProfilesViewModel", "Profile switched while VPN running, triggering node switch for: $currentNodeId")
                    configRepository.setActiveNodeWithResult(currentNodeId)
                }
            }
        }
    }

    fun toggleProfileEnabled(profileId: String) {
        val before = profiles.value.find { it.id == profileId }
        configRepository.toggleProfileEnabled(profileId)

        val name = before?.name
        if (!name.isNullOrBlank()) {
            val enabledAfter = !before.enabled
            val msg = if (enabledAfter) getApplication<Application>().getString(R.string.common_enable) else getApplication<Application>().getString(R.string.common_disable)
            emitToast("$msg: $name")
        }
    }

    fun updateProfileMetadata(
        profileId: String,
        newName: String,
        newUrl: String?,
        autoUpdateInterval: Int = 0,
        dnsOverride: String? = null
    ) {
        configRepository.updateProfileMetadata(
            profileId,
            newName,
            newUrl,
            autoUpdateInterval,
            dnsOverride
        )
        emitToast(getApplication<Application>().getString(R.string.profiles_updated))
        emitDnsOverrideCompatibilityWarning(dnsOverride)
    }

    @Suppress("CognitiveComplexMethod")
    fun updateProfile(profileId: String) {
        viewModelScope.launch {
            emitToast(getApplication<Application>().getString(R.string.common_loading))
            val result = configRepository.updateProfile(profileId)

            val message = when (result) {
                is SubscriptionUpdateResult.SuccessWithChanges -> {
                    val changes = mutableListOf<String>()
                    if (result.addedCount > 0) changes.add("+${result.addedCount}")
                    if (result.removedCount > 0) changes.add("-${result.removedCount}")
                    val message = getApplication<Application>().getString(
                        R.string.subscription_update_success_with_changes,
                        changes.joinToString("/"),
                        result.totalCount
                    )
                    message
                }
                is SubscriptionUpdateResult.SuccessNoChanges -> {
                    val message = getApplication<Application>().getString(
                        R.string.subscription_update_success_no_changes,
                        result.totalCount
                    )
                    message
                }
                is SubscriptionUpdateResult.Failed -> {
                    getApplication<Application>().getString(R.string.settings_update_failed) + ": ${result.error}"
                }
            }

            emitToast(message)
        }
    }

    fun deleteProfile(profileId: String) {
        val name = profiles.value.find { it.id == profileId }?.name
        viewModelScope.launch {
            configRepository.deleteProfile(profileId)
            if (!name.isNullOrBlank()) {
                emitToast(getApplication<Application>().getString(R.string.profiles_deleted) + ": $name")
            } else {
                emitToast(getApplication<Application>().getString(R.string.profiles_deleted))
            }
        }
    }

    fun reorderProfiles(newProfiles: List<ProfileUi>) {
        configRepository.reorderProfiles(newProfiles)
    }

    fun importSubscription(
        name: String,
        url: String,
        autoUpdateInterval: Int = 0,
        dnsOverride: String? = null
    ): Boolean {

        if (_importState.value is ImportState.Loading) {
            return false
        }

        importJob = viewModelScope.launch {
            _importState.value = ImportState.Loading(getApplication<Application>().getString(R.string.common_loading))

            val result = configRepository.importFromSubscription(
                name = name,
                url = url,
                autoUpdateInterval = autoUpdateInterval,
                dnsOverride = dnsOverride,
                onProgress = { progress ->
                    _importState.value = ImportState.Loading(progress)
                }
            )

            coroutineContext.ensureActive()

            result.fold(
                onSuccess = { profile ->
                    _importState.value = ImportState.Success(profile)
                    emitDnsOverrideCompatibilityWarning(profile.dnsOverride)
                },
                onFailure = { error ->

                    if (error is kotlinx.coroutines.CancellationException) {
                        _importState.value = ImportState.Idle
                    } else {
                        _importState.value = ImportState.Error(error.message ?: getApplication<Application>().getString(R.string.import_failed))
                    }
                }
            )
        }

        return true
    }

    fun createCustomConfig(name: String, selectedNodeIds: List<String>) {
        if (_importState.value is ImportState.Loading) {
            return
        }
        if (name.isBlank() || selectedNodeIds.isEmpty()) {
            _importState.value = ImportState.Error("名称不能为空，且至少选择一个节点")
            return
        }

        importJob = viewModelScope.launch {
            _importState.value = ImportState.Loading("创建自定义配置中...")

            val result = configRepository.createCustomProfile(
                name = name,
                selectedNodeIds = selectedNodeIds
            )

            coroutineContext.ensureActive()

            result.fold(
                onSuccess = { profile ->
                    _importState.value = ImportState.Success(profile)
                },
                onFailure = { error ->
                    if (error is kotlinx.coroutines.CancellationException) {
                        _importState.value = ImportState.Idle
                    } else {
                        _importState.value = ImportState.Error(error.message ?: "创建失败")
                    }
                }
            )
        }
    }

    fun setAllNodesUiActive(active: Boolean) {
        configRepository.setAllNodesUiActive(active)
    }

    fun importFromContent(
        name: String,
        content: String,
        profileType: ProfileType = ProfileType.Imported
    ) {
        if (_importState.value is ImportState.Loading) {
            return
        }
        if (content.isBlank()) {
            _importState.value = ImportState.Error(getApplication<Application>().getString(R.string.profiles_content_empty))
            return
        }
        if (content.toByteArray(Charsets.UTF_8).size > MAX_IMPORT_CONTENT_BYTES) {
            _importState.value = ImportState.Error(
                getApplication<Application>().getString(R.string.profiles_import_content_too_large)
            )
            return
        }

        importJob = viewModelScope.launch {
            _importState.value = ImportState.Loading(getApplication<Application>().getString(R.string.common_loading))

            val result = configRepository.importFromContent(
                name = name,
                content = content,
                profileType = profileType,
                onProgress = { progress ->
                    _importState.value = ImportState.Loading(progress)
                }
            )

            coroutineContext.ensureActive()

            result.fold(
                onSuccess = { profile ->
                    _importState.value = ImportState.Success(profile)
                },
                onFailure = { error ->

                    if (error is kotlinx.coroutines.CancellationException) {
                        _importState.value = ImportState.Idle
                    } else {
                        _importState.value = ImportState.Error(error.message ?: getApplication<Application>().getString(R.string.import_failed))
                    }
                }
            )
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        _importState.value = ImportState.Idle
    }

    fun resetImportState() {
        importJob = null
        _importState.value = ImportState.Idle
    }

    sealed class ImportState {
        data object Idle : ImportState()
        data class Loading(val message: String) : ImportState()
        data class Success(val profile: ProfileUi) : ImportState()
        data class Error(val message: String) : ImportState()
    }
}
