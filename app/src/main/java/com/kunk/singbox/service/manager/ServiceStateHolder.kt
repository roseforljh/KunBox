package com.kunk.singbox.service.manager

import com.kunk.singbox.repository.LogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

object ServiceStateHolder {

    const val ACTION_START = "com.kunk.singbox.START"
    const val ACTION_STOP = "com.kunk.singbox.STOP"
    const val ACTION_SWITCH_NODE = "com.kunk.singbox.SWITCH_NODE"
    const val ACTION_SERVICE = "com.kunk.singbox.SERVICE"
    const val ACTION_UPDATE_SETTING = "com.kunk.singbox.UPDATE_SETTING"
    
    // Pre-cleanup: close connections before VPN restart to avoid app timeout on old connections
    const val ACTION_PREPARE_RESTART = "com.kunk.singbox.PREPARE_RESTART"
    // Hot-reload: reload config preserving TUN interface, no VPN service restart
    const val ACTION_HOT_RELOAD = "com.kunk.singbox.HOT_RELOAD"
    
    const val EXTRA_CONFIG_PATH = "config_path"
    const val EXTRA_CONFIG_CONTENT = "config_content"
    const val EXTRA_CLEAN_CACHE = "clean_cache"
    const val EXTRA_SETTING_KEY = "setting_key"
    const val EXTRA_SETTING_VALUE_BOOL = "setting_value_bool"

    @Volatile
    var instance: com.kunk.singbox.service.SingBoxService? = null
        internal set

    private val _isRunningFlow = MutableStateFlow(false)
    val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

    private val _isStartingFlow = MutableStateFlow(false)
    val isStartingFlow: StateFlow<Boolean> = _isStartingFlow.asStateFlow()

    private val _lastErrorFlow = MutableStateFlow<String?>(null)
    val lastErrorFlow: StateFlow<String?> = _lastErrorFlow.asStateFlow()

    @Volatile
    var isRunning: Boolean = false
        internal set(value) {
            field = value
            _isRunningFlow.value = value
        }

    @Volatile
    var isStarting: Boolean = false
        internal set(value) {
            field = value
            _isStartingFlow.value = value
        }

    @Volatile
    var isManuallyStopped: Boolean = false
        internal set

    @Volatile
    var lastConfigPath: String? = null
        internal set

    fun setLastError(message: String?) {
        _lastErrorFlow.value = message
        if (!message.isNullOrBlank()) {
            try {
                LogRepository.getInstance()
                    .addLog("ERROR SingBoxService: $message")
            } catch (_: Exception) {
            }
        }
    }

    fun clearLastError() {
        _lastErrorFlow.value = null
    }

    private val connectionOwnerCalls = AtomicLong(0)
    private val connectionOwnerInvalidArgs = AtomicLong(0)
    private val connectionOwnerUidResolved = AtomicLong(0)
    private val connectionOwnerSecurityDenied = AtomicLong(0)
    private val connectionOwnerOtherException = AtomicLong(0)

    @Volatile
    private var connectionOwnerLastUid: Int = 0
    
    @Volatile
    private var connectionOwnerLastEvent: String = ""

    @Volatile
    var connectionOwnerPermissionDeniedLogged: Boolean = false
        internal set

    fun incrementConnectionOwnerCalls() {
        connectionOwnerCalls.incrementAndGet()
    }

    fun incrementConnectionOwnerInvalidArgs() {
        connectionOwnerInvalidArgs.incrementAndGet()
    }

    fun incrementConnectionOwnerUidResolved() {
        connectionOwnerUidResolved.incrementAndGet()
    }

    fun incrementConnectionOwnerSecurityDenied() {
        connectionOwnerSecurityDenied.incrementAndGet()
    }

    fun incrementConnectionOwnerOtherException() {
        connectionOwnerOtherException.incrementAndGet()
    }

    fun setConnectionOwnerLastEvent(event: String) {
        connectionOwnerLastEvent = event
    }

    fun setConnectionOwnerLastUid(uid: Int) {
        connectionOwnerLastUid = uid
    }

    fun getConnectionOwnerStatsSnapshot(): ConnectionOwnerStatsSnapshot {
        return ConnectionOwnerStatsSnapshot(
            calls = connectionOwnerCalls.get(),
            invalidArgs = connectionOwnerInvalidArgs.get(),
            uidResolved = connectionOwnerUidResolved.get(),
            securityDenied = connectionOwnerSecurityDenied.get(),
            otherException = connectionOwnerOtherException.get(),
            lastUid = connectionOwnerLastUid,
            lastEvent = connectionOwnerLastEvent
        )
    }

    fun resetConnectionOwnerStats() {
        connectionOwnerCalls.set(0)
        connectionOwnerInvalidArgs.set(0)
        connectionOwnerUidResolved.set(0)
        connectionOwnerSecurityDenied.set(0)
        connectionOwnerOtherException.set(0)
        connectionOwnerLastUid = 0
        connectionOwnerLastEvent = ""
        connectionOwnerPermissionDeniedLogged = false
    }

    fun onServiceDestroyed() {
        instance = null
        isRunning = false
        isStarting = false
    }
}

data class ConnectionOwnerStatsSnapshot(
    val calls: Long,
    val invalidArgs: Long,
    val uidResolved: Long,
    val securityDenied: Long,
    val otherException: Long,
    val lastUid: Int,
    val lastEvent: String
)
