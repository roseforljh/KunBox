package com.kunk.singbox.viewmodel

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.util.Log
import com.kunk.singbox.R
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.ConnectionState
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.service.SingBoxService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 *
 *
 */
class VpnConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val configRepository: ConfigRepository,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "VpnConnectionManager"
    }

    /**
     */
    interface PermissionCallback {
        fun onVpnPermissionNeeded()
        fun onStatusMessage(message: String, durationMs: Long = 2000)
        fun onConnectionStateChange(state: ConnectionState)
    }

    private var callback: PermissionCallback? = null

    private val _vpnPermissionNeeded = MutableStateFlow(false)
    val vpnPermissionNeeded: StateFlow<Boolean> = _vpnPermissionNeeded.asStateFlow()

    private var startMonitorJob: kotlinx.coroutines.Job? = null

    fun setCallback(callback: PermissionCallback) {
        this.callback = callback
    }

    /**
     *
     *
     *
     */
    suspend fun toggleConnection(): Boolean {
        return when {
            SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value -> {
                stopVpn()
                false
            }
            checkSystemVpn() -> {
                callback?.onStatusMessage(
                    context.getString(R.string.dashboard_system_vpn_running),
                    3000
                )
                false
            }
            else -> {
                startCore()
                _vpnPermissionNeeded.value
            }
        }
    }

    /**
     * 閲嶅惎 VPN
     */
    suspend fun restartVpn() {
        if (!SingBoxRemote.isRunning.value && !SingBoxRemote.isStarting.value) {
            return
        }

        callback?.onConnectionStateChange(ConnectionState.Connecting)
        stopVpnInternal()

        try {
            withTimeout(5000L) {
                SingBoxRemote.state.drop(1)
                    .first { it == ServiceState.STOPPED }
            }
        } catch (@Suppress("SwallowedException") e: TimeoutCancellationException) {
            Log.w(TAG, "Timeout waiting for VPN to stop during restart")
        }
        delay(300)

        startCore()
    }

    /**
     *
     */
    fun stopVpn() {
        startMonitorJob?.cancel()
        startMonitorJob = null
        callback?.onConnectionStateChange(ConnectionState.Idle)
        stopVpnInternal()
    }

    private fun stopVpnInternal() {
        val mode = VpnStateStore.getMode()
        val intent = when (mode) {
            VpnStateStore.CoreMode.PROXY -> Intent(context, ProxyOnlyService::class.java).apply {
                action = ProxyOnlyService.ACTION_STOP
            }
            else -> Intent(context, SingBoxService::class.java).apply {
                action = SingBoxService.ACTION_STOP
            }
        }
        context.startService(intent)
    }

    /**
     *
     */
    private suspend fun startCore() {
        val settings = runCatching {
            settingsRepository.settings.first()
        }.getOrNull()

        val desiredMode = if (settings?.tunEnabled == true) {
            VpnStateStore.CoreMode.VPN
        } else {
            VpnStateStore.CoreMode.PROXY
        }

        if (settings?.tunEnabled == true) {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                _vpnPermissionNeeded.value = true
                callback?.onVpnPermissionNeeded()
                return
            }
        }

        callback?.onConnectionStateChange(ConnectionState.Connecting)
        stopOppositeService(desiredMode)

        try {
            val configResult = withContext(Dispatchers.IO) {
                settingsRepository.checkAndMigrateRuleSets()
                configRepository.generateConfigFile()
            }
            if (configResult == null) {
                callback?.onConnectionStateChange(ConnectionState.Error)
                callback?.onStatusMessage(
                    context.getString(R.string.dashboard_config_generation_failed)
                )
                return
            }

            startService(desiredMode, configResult.path)
            startConnectionMonitor()
        } catch (e: Exception) {
            callback?.onConnectionStateChange(ConnectionState.Error)
            callback?.onStatusMessage(
                context.getString(R.string.node_start_failed, e.message ?: "")
            )
        }
    }

    private suspend fun stopOppositeService(desiredMode: VpnStateStore.CoreMode) {
        when (desiredMode) {
            VpnStateStore.CoreMode.VPN -> {
                runCatching {
                    context.startService(Intent(context, ProxyOnlyService::class.java).apply {
                        action = ProxyOnlyService.ACTION_STOP
                    })
                }
            }
            VpnStateStore.CoreMode.PROXY -> {
                runCatching {
                    context.startService(Intent(context, SingBoxService::class.java).apply {
                        action = SingBoxService.ACTION_STOP
                    })
                }
            }
            else -> return
        }

        if (SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value) {
            try {
                withTimeout(3000L) {
                    SingBoxRemote.state.drop(1)
                        .first { it == ServiceState.STOPPED }
                }
            } catch (@Suppress("SwallowedException") e: TimeoutCancellationException) {
                Log.w(TAG, "Timeout waiting for opposite service to stop")
            }
            delay(200)
        }
    }

    private fun startService(mode: VpnStateStore.CoreMode, configPath: String) {
        val useTun = mode == VpnStateStore.CoreMode.VPN
        val intent = if (useTun) {
            Intent(context, SingBoxService::class.java).apply {
                action = SingBoxService.ACTION_START
                putExtra(SingBoxService.EXTRA_CONFIG_PATH, configPath)
                putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
            }
        } else {
            Intent(context, ProxyOnlyService::class.java).apply {
                action = ProxyOnlyService.ACTION_START
                putExtra(ProxyOnlyService.EXTRA_CONFIG_PATH, configPath)
                putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    @Suppress("CognitiveComplexMethod")
    private fun startConnectionMonitor() {
        startMonitorJob?.cancel()
        startMonitorJob = scope.launch {
            val startTime = System.currentTimeMillis()
            val quickFeedbackMs = 1000L
            var showedStartingHint = false

            while (true) {
                if (SingBoxRemote.isRunning.value) {
                    callback?.onConnectionStateChange(ConnectionState.Connected)
                    return@launch
                }

                val err = SingBoxRemote.lastError.value
                if (!err.isNullOrBlank()) {
                    callback?.onConnectionStateChange(ConnectionState.Error)
                    callback?.onStatusMessage(err, 3000)
                    return@launch
                }

                val elapsed = System.currentTimeMillis() - startTime
                if (!showedStartingHint && elapsed >= quickFeedbackMs) {
                    showedStartingHint = true
                    callback?.onStatusMessage(
                        context.getString(R.string.connection_connecting),
                        1200
                    )
                }

                val intervalMs = when {
                    elapsed < 10_000L -> 200L
                    elapsed < 60_000L -> 1000L
                    else -> 5000L
                }
                delay(intervalMs)
            }
        }
    }

    private fun checkSystemVpn(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /**
     */
    fun onVpnPermissionResult(granted: Boolean) {
        _vpnPermissionNeeded.value = false
        if (granted) {
            scope.launch {
                startCore()
            }
        }
    }

    fun cleanup() {
        startMonitorJob?.cancel()
        startMonitorJob = null
        callback = null
    }
}
