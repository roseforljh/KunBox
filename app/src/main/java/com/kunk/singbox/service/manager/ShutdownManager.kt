package com.kunk.singbox.service.manager

import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.core.SelectorManager
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.repository.TrafficRepository
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.service.VpnKeepaliveWorker
import com.kunk.singbox.service.VpnTileService
import com.kunk.singbox.service.notification.VpnNotificationManager
import com.kunk.singbox.utils.NetworkClient
import kotlinx.coroutines.*

internal suspend fun stopTrafficProducerThenFlush(
    stopProducer: suspend () -> Unit,
    stopUpdatesAndWait: () -> Unit,
    flush: () -> Unit,
    stopTransport: suspend () -> Unit
) {
    try {
        stopProducer()
        stopUpdatesAndWait()
        withContext(Dispatchers.IO) { flush() }
    } finally {
        stopTransport()
    }
}

class ShutdownManager(
    private val context: Context,
    private val cleanupScope: CoroutineScope
) {
    companion object {
        private const val TAG = "ShutdownManager"
        private const val FAST_PORT_RELEASE_WAIT_MS = 1500L

        internal fun resolveStopCompletion(
            initialStopService: Boolean,
            hardStopRequested: Boolean,
            cleanupRecoveryIntentLease: RecoveryIntentLease?,
            hardStopRecoveryIntentLease: RecoveryIntentLease?,
            pendingStartConfigPath: String?,
            pendingRecoveryIntentLease: RecoveryIntentLease? = null
        ): StopCompletion {
            val hardStopLease = hardStopRecoveryIntentLease
                ?.takeIf { hardStopRequested && ServiceStateHolder.isRecoveryIntentCurrent(it) }
                ?: cleanupRecoveryIntentLease?.takeIf {
                    initialStopService && ServiceStateHolder.isRecoveryIntentCurrent(it)
                }
            val restartLease = pendingRecoveryIntentLease?.takeIf {
                hardStopLease == null && ServiceStateHolder.isRecoveryIntentCurrent(it)
            }
            val restartConfigPath = pendingStartConfigPath?.takeIf {
                it.isNotBlank() && restartLease != null
            }
            return StopCompletion(
                stopService = hardStopLease != null,
                restartConfigPath = restartConfigPath,
                recoveryIntentLease = hardStopLease ?: restartLease.takeIf { restartConfigPath != null },
                resourceRecoveryAttemptId = (hardStopLease ?: restartLease)?.attemptId
            )
        }
    }

    data class StopCompletion(
        val stopService: Boolean,
        val restartConfigPath: String?,
        val recoveryIntentLease: RecoveryIntentLease?,
        val resourceRecoveryAttemptId: Long?
    )

    interface Callbacks {
        fun updateServiceState(state: ServiceState)
        fun updateTileState()
        fun stopForegroundService()
        fun stopSelf()

        fun cancelStartVpnJob(): Job?
        fun cancelPostStartJob(): Job?
        fun cancelHotReloadJob(): Job?
        fun cancelRemoteStateUpdateJob()
        fun cancelAutoFailoverJob()

        fun stopForeignVpnMonitor()
        fun tryClearRunningServiceForLibbox()
        fun unregisterScreenStateReceiver()
        fun closeDefaultInterfaceMonitor()

        fun getConnectivityManager(): ConnectivityManager?

        fun setVpnInterface(fd: android.os.ParcelFileDescriptor?)
        fun setIsRunning(running: Boolean)
        fun setRealTimeNodeName(name: String?)
        fun setNetworkCallbackReady(ready: Boolean)
        fun setLastKnownNetwork(network: android.net.Network?)
        fun clearUnderlyingNetworks()

        fun hasPendingStartConfigPath(): Boolean
        fun completeStop(
            initialStopService: Boolean,
            recoveryIntentLease: RecoveryIntentLease
        ): StopCompletion
        fun startVpn(configPath: String, recoveryIntentLease: RecoveryIntentLease?)
    }

    data class ShutdownOptions(
        val stopService: Boolean,
        val proxyPort: Int = 0,
        val recoveryIntentLease: RecoveryIntentLease,
        val resourceRecoveryAttemptId: Long? = null
    )

    @Suppress("LongParameterList", "LongMethod", "CognitiveComplexMethod", "CyclomaticComplexMethod")
    fun stopVpn(
        options: ShutdownOptions,
        coreManager: CoreManager,
        commandManager: CommandManager,
        notificationManager: VpnNotificationManager,
        callbacks: Callbacks
    ): Job {
        val stopService = options.stopService
        val proxyPort = options.proxyPort
        val recoveryIntentLease = options.recoveryIntentLease
        val resourceRecoveryAttemptId = options.resourceRecoveryAttemptId

        val jobsToJoin = listOfNotNull(
            callbacks.cancelStartVpnJob(),
            callbacks.cancelPostStartJob(),
            callbacks.cancelHotReloadJob()
        )
        callbacks.cancelRemoteStateUpdateJob()
        callbacks.cancelAutoFailoverJob()

        VpnKeepaliveWorker.cancel(context)
        Log.i(TAG, "VPN keepalive worker cancelled")

        notificationManager.resetState()

        callbacks.stopForeignVpnMonitor()

        callbacks.setNetworkCallbackReady(false)
        callbacks.setLastKnownNetwork(null)
        callbacks.clearUnderlyingNetworks()

        callbacks.tryClearRunningServiceForLibbox()

        SelectorManager.clear()

        Log.i(TAG, "stopVpn(stopService=$stopService, proxyPort=$proxyPort)")

        callbacks.setRealTimeNodeName(null)
        callbacks.setIsRunning(false)
        NetworkClient.onVpnStateChanged(false)

        callbacks.setVpnInterface(null)
        callbacks.unregisterScreenStateReceiver()

        val cleanupJob = cleanupScope.launch {
            withContext(NonCancellable) {
                try {
                    jobsToJoin.forEach { it.join() }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to wait for startup tasks before shutdown", e)
                }

                stopTrafficProducerThenFlush(
                    stopProducer = {
                        val serviceCloseStart = SystemClock.elapsedRealtime()
                        coreManager.stopFully(completeLifecycle = false)
                            .onFailure { e -> Log.w(TAG, "CoreManager.stopFully failed: ${e.message}") }
                        Log.i(
                            TAG,
                            "CoreManager fully stopped in ${SystemClock.elapsedRealtime() - serviceCloseStart}ms"
                        )
                    },
                    stopUpdatesAndWait = commandManager::stopTrafficUpdatesAndWait,
                    flush = {
                        runCatching { TrafficRepository.getInstance(context).saveStats() }
                            .onFailure { error -> Log.w(TAG, "Failed to persist final traffic", error) }
                    },
                    stopTransport = {
                        commandManager.stopAndWaitPortRelease(
                            proxyPort = proxyPort,
                            waitTimeoutMs = FAST_PORT_RELEASE_WAIT_MS,
                            forceKillOnTimeout = stopService,
                            enforceReleaseOnTimeout = false
                        ).onFailure { e ->
                            Log.w(TAG, "Error closing command server/client", e)
                        }
                    }
                )

                try {
                    callbacks.closeDefaultInterfaceMonitor()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to close default interface monitor", e)
                }

                if (!stopService && callbacks.hasPendingStartConfigPath()) {
                    waitForSystemVpnDown(callbacks.getConnectivityManager(), 1500L)
                }

                withContext(Dispatchers.Main) {
                    val completion = callbacks.completeStop(stopService, recoveryIntentLease)
                    val restartConfigPath = completion.restartConfigPath
                    when {
                        restartConfigPath != null -> {
                            Log.i(TAG, "Cleanup complete, restarting VPN")
                            callbacks.startVpn(restartConfigPath, completion.recoveryIntentLease)
                        }
                        completion.stopService -> {
                            val completionLease = completion.recoveryIntentLease
                            val recoveryIntent = completionLease?.let(
                                ServiceStateHolder::consumeRecoveryIntentOnFailure
                            )
                            if (recoveryIntent == null) {
                                Log.w(
                                    TAG,
                                    "Stale shutdown completion ignored: recovery intent ownership changed " +
                                        "attempt=${completion.resourceRecoveryAttemptId ?: resourceRecoveryAttemptId}"
                                )
                            } else {
                                callbacks.stopForegroundService()
                                runCatching {
                                    val manager = context.getSystemService(NotificationManager::class.java)
                                    manager.cancel(VpnNotificationManager.NOTIFICATION_ID)
                                }
                                VpnTileService.persistVpnState(false)
                                val preserveMode = RecoveryPolicy.shouldPreserveModeOnStartFailure(recoveryIntent)
                                if (preserveMode) {
                                    // 恢复路径启动失败：保留 mode，只清 runtime + claim
                                    VpnStateStore.clearRuntimeState(preserveLastError = true)
                                    VpnStateStore.clearRecoveryClaim()
                                    Log.w(TAG, "Recovery start failed, mode preserved for next issuer")
                                } else {
                                    VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
                                }
                                VpnTileService.persistVpnPending("")
                                callbacks.updateServiceState(ServiceState.STOPPED)
                                callbacks.updateTileState()
                                callbacks.stopSelf()
                                Log.i(TAG, "VPN stopped")
                            }
                        }
                        else -> Log.i(TAG, "Cleanup complete without restart")
                    }
                }
            }
        }
        return cleanupJob
    }

    private suspend fun waitForSystemVpnDown(cm: ConnectivityManager?, timeoutMs: Long) {
        if (cm == null) return

        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            val hasVpn = runCatching {
                @Suppress("DEPRECATION")
                cm.allNetworks.any { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@any false
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }
            }.getOrDefault(false)

            if (!hasVpn) return
            delay(50)
        }
    }
}
