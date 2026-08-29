package com.kunk.singbox.viewmodel

import com.kunk.singbox.R
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.ProfileType
import com.kunk.singbox.model.SubscriptionUpdateResult
import com.kunk.singbox.repository.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Suppress("TooManyFunctions")
class ProfilesViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val MAX_IMPORT_CONTENT_BYTES = 1024 * 1024
    }

    private val configRepository = ConfigRepository.getInstance(application)

    private var importJob: Job? = null

    val profiles: StateFlow<List<ProfileUi>> = configRepository.profiles
    val allNodes: StateFlow<List<NodeUi>> = configRepository.allNodes
    val activeProfileId: StateFlow<String?> = configRepository.activeProfileId

    private val _switchingProfileId = MutableStateFlow<String?>(null)
    val switchingProfileId: StateFlow<String?> = _switchingProfileId.asStateFlow()

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    private val _customDraftOutbounds = MutableStateFlow<List<Outbound>>(emptyList())
    val customDraftOutbounds: StateFlow<List<Outbound>> = _customDraftOutbounds.asStateFlow()

    private fun emitToast(message: String) {
        _toastEvents.tryEmit(message)
    }

    private fun emitDnsOverrideCompatibilityWarning(dnsOverride: String?) {
        ConfigRepository.buildDnsOverrideCompatibilityWarning(dnsOverride)?.let { emitToast(it) }
    }

    fun setActiveProfile(profileId: String) {
        if (_switchingProfileId.value != null) return
        _switchingProfileId.value = profileId
        val isVpnRunning = SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value
        viewModelScope.launch {
            try {
                val result = configRepository.setActiveProfileWithResult(profileId)
                val name = profiles.value.find { it.id == profileId }?.name
                if (result is ConfigRepository.NodeSwitchResult.Failed) {
                    emitToast(result.reason)
                } else if (isVpnRunning && !name.isNullOrBlank()) {
                    emitToast(getApplication<Application>().getString(R.string.profiles_updated) + ": $name")
                }
            } finally {
                _switchingProfileId.value = null
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
        dnsPreResolve: Boolean = false,
        dnsServer: String? = null,
        dnsOverride: String? = null
    ) {
        configRepository.updateProfileMetadata(
            profileId = profileId,
            newName = newName,
            newUrl = newUrl,
            autoUpdateInterval = autoUpdateInterval,
            dnsPreResolve = dnsPreResolve,
            dnsServer = dnsServer,
            dnsOverride = dnsOverride
        )
        emitToast(getApplication<Application>().getString(R.string.profiles_updated))
        emitDnsOverrideCompatibilityWarning(dnsOverride)
    }

    @Suppress("CognitiveComplexMethod")
    fun updateProfile(profileId: String) {
        if (profiles.value.find { it.id == profileId }?.type == ProfileType.Custom) {
            emitToast(getApplication<Application>().getString(R.string.profiles_custom_no_update))
            return
        }
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
        dnsPreResolve: Boolean = false,
        dnsServer: String? = null,
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
                dnsPreResolve = dnsPreResolve,
                dnsServer = dnsServer,
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

    fun addCustomDraftOutbound(outbound: Outbound) {
        _customDraftOutbounds.update { it + outbound }
    }

    fun addCustomDraftNodeLink(content: String): Boolean {
        return configRepository.parseNodeLinkForCustomProfile(content).fold(
            onSuccess = { outbound ->
                addCustomDraftOutbound(outbound)
                emitToast(getApplication<Application>().getString(R.string.common_add) + ": ${outbound.tag}")
                true
            },
            onFailure = { error ->
                emitToast(error.message ?: getApplication<Application>().getString(R.string.nodes_add_failed))
                false
            }
        )
    }

    fun removeCustomDraftOutbound(index: Int) {
        _customDraftOutbounds.update { outbounds ->
            outbounds.filterIndexed { currentIndex, _ -> currentIndex != index }
        }
    }

    fun clearCustomDraftNodes() {
        _customDraftOutbounds.value = emptyList()
    }

    fun createCustomConfig(
        name: String,
        selectedNodeIds: List<String>,
        onResult: (Boolean) -> Unit
    ) {
        if (_importState.value is ImportState.Loading) {
            return
        }
        if (name.isBlank()) {
            _importState.value = ImportState.Error(
                getApplication<Application>().getString(R.string.custom_profile_name_required)
            )
            onResult(false)
            return
        }
        val additionalOutbounds = customDraftOutbounds.value
        if (selectedNodeIds.isEmpty() && additionalOutbounds.isEmpty()) {
            _importState.value = ImportState.Error(
                getApplication<Application>().getString(R.string.custom_profile_nodes_required)
            )
            onResult(false)
            return
        }

        importJob = viewModelScope.launch {
            _importState.value = ImportState.Loading(
                getApplication<Application>().getString(R.string.custom_profile_creating)
            )

            val result = configRepository.createCustomProfile(
                name = name,
                selectedNodeIds = selectedNodeIds,
                additionalOutbounds = additionalOutbounds
            )

            coroutineContext.ensureActive()

            result.fold(
                onSuccess = { profile ->
                    clearCustomDraftNodes()
                    _importState.value = ImportState.Success(profile)
                    onResult(true)
                },
                onFailure = { error ->
                    if (error is kotlinx.coroutines.CancellationException) {
                        _importState.value = ImportState.Idle
                    } else {
                        _importState.value = ImportState.Error(
                            error.message
                                ?: getApplication<Application>().getString(R.string.custom_profile_create_failed)
                        )
                    }
                    onResult(false)
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
