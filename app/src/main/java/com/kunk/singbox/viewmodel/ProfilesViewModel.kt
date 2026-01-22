package com.kunk.singbox.viewmodel

import com.kunk.singbox.R
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.model.ProfileType
import com.kunk.singbox.model.SubscriptionUpdateResult
import com.kunk.singbox.repository.ConfigRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfilesViewModel(application: Application) : AndroidViewModel(application) {

    private val configRepository = ConfigRepository.getInstance(application)

    // 用于跟踪当前的导入任务，以便可以取消
    private var importJob: Job? = null

    val profiles: StateFlow<List<ProfileUi>> = configRepository.profiles
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val activeProfileId: StateFlow<String?> = configRepository.activeProfileId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    // 导入状态
    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    // 单个配置更新状态
    private val _updateStatus = MutableStateFlow<String?>(null)
    val updateStatus: StateFlow<String?> = _updateStatus.asStateFlow()

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    private fun emitToast(message: String) {
        _toastEvents.tryEmit(message)
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

            // 2025-fix: 切换配置后自动触发节点切换，确保VPN加载新配置
            viewModelScope.launch {
                delay(100)
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
            val enabledAfter = !(before?.enabled ?: true)
            val msg = if (enabledAfter) getApplication<Application>().getString(R.string.common_enable) else getApplication<Application>().getString(R.string.common_disable)
            emitToast("$msg: $name")
        }
    }

    fun updateProfileMetadata(profileId: String, newName: String, newUrl: String?, autoUpdateInterval: Int = 0) {
        configRepository.updateProfileMetadata(profileId, newName, newUrl, autoUpdateInterval)
        emitToast(getApplication<Application>().getString(R.string.profiles_updated))
    }

    fun updateProfile(profileId: String) {
        viewModelScope.launch {
            _updateStatus.value = getApplication<Application>().getString(R.string.common_loading)

            val result = configRepository.updateProfile(profileId)

            // 根据结果生成提示消息
            _updateStatus.value = when (result) {
                is SubscriptionUpdateResult.SuccessWithChanges -> {
                    val changes = mutableListOf<String>()
                    if (result.addedCount > 0) changes.add("+${result.addedCount}")
                    if (result.removedCount > 0) changes.add("-${result.removedCount}")
                    getApplication<Application>().getString(R.string.subscription_update_success_with_changes, changes.joinToString("/"), result.totalCount)
                }
                is SubscriptionUpdateResult.SuccessNoChanges -> {
                    getApplication<Application>().getString(R.string.subscription_update_success_no_changes, result.totalCount)
                }
                is SubscriptionUpdateResult.Failed -> {
                    getApplication<Application>().getString(R.string.settings_update_failed) + ": ${result.error}"
                }
            }

            delay(2500)
            _updateStatus.value = null
        }
    }

    fun deleteProfile(profileId: String) {
        val name = profiles.value.find { it.id == profileId }?.name
        configRepository.deleteProfile(profileId)
        if (!name.isNullOrBlank()) {
            emitToast(getApplication<Application>().getString(R.string.profiles_deleted) + ": $name")
        } else {
            emitToast(getApplication<Application>().getString(R.string.profiles_deleted))
        }
    }

    /**
     * 导入订阅配置
     */
    fun importSubscription(name: String, url: String, autoUpdateInterval: Int = 0) {
        // 防止重复导入
        if (_importState.value is ImportState.Loading) {
            return
        }

        importJob = viewModelScope.launch {
            _importState.value = ImportState.Loading(getApplication<Application>().getString(R.string.common_loading))

            val result = configRepository.importFromSubscription(
                name = name,
                url = url,
                autoUpdateInterval = autoUpdateInterval,
                onProgress = { progress ->
                    _importState.value = ImportState.Loading(progress)
                }
            )

            // 防止取消后仍然更新状态
            coroutineContext.ensureActive()

            result.fold(
                onSuccess = { profile ->
                    _importState.value = ImportState.Success(profile)
                },
                onFailure = { error ->
                    // 检查是否是由于取消导致的
                    if (error is kotlinx.coroutines.CancellationException) {
                        _importState.value = ImportState.Idle
                    } else {
                        _importState.value = ImportState.Error(error.message ?: getApplication<Application>().getString(R.string.import_failed))
                    }
                }
            )
        }
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

            // 防止取消后仍然更新状态
            coroutineContext.ensureActive()

            result.fold(
                onSuccess = { profile ->
                    _importState.value = ImportState.Success(profile)
                },
                onFailure = { error ->
                    // 检查是否是由于取消导致的
                    if (error is kotlinx.coroutines.CancellationException) {
                        _importState.value = ImportState.Idle
                    } else {
                        _importState.value = ImportState.Error(error.message ?: getApplication<Application>().getString(R.string.import_failed))
                    }
                }
            )
        }
    }

    /**
     * 取消当前的导入操作
     */
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
