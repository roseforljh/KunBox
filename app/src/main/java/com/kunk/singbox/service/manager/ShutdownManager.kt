package com.kunk.singbox.service.manager

import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.repository.TrafficRepository
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.service.VpnKeepaliveWorker
import com.kunk.singbox.service.VpnTileService
import com.kunk.singbox.service.notification.VpnNotificationManager
import com.kunk.singbox.utils.NetworkClient
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

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
    private val operationGeneration = AtomicLong(0L)

    enum class ShutdownKind {
        CORE_RECOVERY,
        TUN_REBUILD,
        FINAL_STOP
    }

    companion object {
        private const val TAG = "ShutdownManager"
        private const val FAST_PORT_RELEASE_WAIT_MS = 1500L
        private const val STOP_WATCHDOG_TIMEOUT_MS = 6_000L

        internal fun shouldRetainTun(kind: ShutdownKind): Boolean = kind != ShutdownKind.FINAL_STOP

        internal fun shouldForceStopProcessOnTimeout(kind: ShutdownKind): Boolean =
            kind == ShutdownKind.FINAL_STOP

        internal fun requiresEscalatedFinalCleanup(kind: ShutdownKind, completionStop: Boolean): Boolean =
            completionStop && kind != ShutdownKind.FINAL_STOP

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
        fun forceStopProcess(reason: String)
        fun onRecoveryTimeout(kind: ShutdownKind, reason: String)

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
        val kind: ShutdownKind,
        val proxyPort: Int = 0,
        val recoveryIntentLease: RecoveryIntentLease,
        val resourceRecoveryAttemptId: Long? = null
    ) {
        val stopService: Boolean get() = kind == ShutdownKind.FINAL_STOP
    }

    @Suppress("LongParameterList", "LongMethod", "CognitiveComplexMethod", "CyclomaticComplexMethod")
    fun stopVpn(
        options: ShutdownOptions,
        coreManager: CoreManager,
        commandManager: CommandManager,
        notificationManager: VpnNotificationManager,
        callbacks: Callbacks
    ): Job {
        val stopService = options.stopService
        val shutdownKind = options.kind
        val proxyPort = options.proxyPort
        val recoveryIntentLease = options.recoveryIntentLease
        val resourceRecoveryAttemptId = options.resourceRecoveryAttemptId
        val operationToken = operationGeneration.incrementAndGet()
        val commandRuntimeGeneration = commandManager.currentRuntimeGeneration()
        val coreRuntimeGeneration = coreManager.currentRuntimeGeneration()

        callbacks.cancelStartVpnJob()
        callbacks.cancelPostStartJob()
        callbacks.cancelHotReloadJob()
        callbacks.cancelRemoteStateUpdateJob()
        callbacks.cancelAutoFailoverJob()

        if (shutdownKind == ShutdownKind.FINAL_STOP) {
            VpnKeepaliveWorker.cancel(context)
            Log.i(TAG, "VPN keepalive worker cancelled")
            notificationManager.resetState()
            callbacks.stopForeignVpnMonitor()
            callbacks.setNetworkCallbackReady(false)
            callbacks.setLastKnownNetwork(null)
            callbacks.clearUnderlyingNetworks()
        }

        callbacks.tryClearRunningServiceForLibbox()

        Log.i(TAG, "stopVpn(kind=$shutdownKind, stopService=$stopService, proxyPort=$proxyPort)")

        if (shutdownKind == ShutdownKind.FINAL_STOP) {
            callbacks.setRealTimeNodeName(null)
        }
        callbacks.setIsRunning(false)
        if (shutdownKind == ShutdownKind.FINAL_STOP) {
            NetworkClient.onVpnStateChanged(false)
            callbacks.setVpnInterface(null)
            callbacks.unregisterScreenStateReceiver()
        }

        val cleanupJob = cleanupScope.launch {
            withContext(NonCancellable) {
                Log.i(TAG, "stop phase=core")
                stopTrafficProducerThenFlush(
                    stopProducer = {
                        val serviceCloseStart = SystemClock.elapsedRealtime()
                        val stopResult = when (shutdownKind) {
                            ShutdownKind.CORE_RECOVERY ->
                                coreManager.stopCorePreservingTun(coreRuntimeGeneration)
                            ShutdownKind.TUN_REBUILD ->
                                coreManager.prepareTunReplacement(coreRuntimeGeneration)
                            ShutdownKind.FINAL_STOP -> coreManager.stopFully(completeLifecycle = false)
                        }
                        stopResult.onFailure { e ->
                            Log.w(TAG, "CoreManager stop failed kind=$shutdownKind: ${e.message}")
                        }
                        Log.i(
                            TAG,
                            "CoreManager stopped kind=$shutdownKind in " +
                                "${SystemClock.elapsedRealtime() - serviceCloseStart}ms"
                        )
                    },
                    stopUpdatesAndWait = {
                        if (operationGeneration.get() == operationToken) {
                            commandManager.stopTrafficUpdatesAndWait()
                        }
                    },
                    flush = {
                        runCatching { TrafficRepository.getInstance(context).saveStats() }
                            .onFailure { error -> Log.w(TAG, "Failed to persist final traffic", error) }
                    },
                    stopTransport = {
                        Log.i(TAG, "stop phase=transport")
                        commandManager.stopAndWaitPortRelease(
                            proxyPort = proxyPort,
                            waitTimeoutMs = FAST_PORT_RELEASE_WAIT_MS,
                            forceKillOnTimeout = stopService,
                            enforceReleaseOnTimeout = false,
                            preserveNotifications = shutdownKind != ShutdownKind.FINAL_STOP,
                            expectedRuntimeGeneration = commandRuntimeGeneration
                        ).onFailure { e ->
                            Log.w(TAG, "Error closing command server/client", e)
                        }
                    }
                )

                if (shouldForceStopProcessOnTimeout(shutdownKind)) {
                    try {
                        Log.i(TAG, "stop phase=network_monitor")
                        callbacks.closeDefaultInterfaceMonitor()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to close default interface monitor", e)
                    }
                }

                if (shutdownKind == ShutdownKind.FINAL_STOP) {
                    waitForSystemVpnDown(callbacks.getConnectivityManager(), 1500L)
                }

                withContext(Dispatchers.Main) {
                    if (operationGeneration.get() != operationToken) {
                        Log.w(TAG, "Ignoring stale shutdown completion token=$operationToken")
                        return@withContext
                    }
                    Log.i(TAG, "stop phase=complete")
                    val completion = callbacks.completeStop(stopService, recoveryIntentLease)
                    if (requiresEscalatedFinalCleanup(shutdownKind, completion.stopService)) {
                        withContext(Dispatchers.IO) {
                            coreManager.stopFully(completeLifecycle = false)
                        }
                        completeEscalatedFinalResources(notificationManager, callbacks)
                        waitForSystemVpnDown(callbacks.getConnectivityManager(), 1500L)
                    }
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
                                    if (VpnStateStore.getStopOwnerMode() == VpnStateStore.CoreMode.VPN) {
                                        VpnStateStore.clearStopOwnerMode()
                                    }
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
        cleanupScope.launch {
            try {
                withTimeout(STOP_WATCHDOG_TIMEOUT_MS) { cleanupJob.join() }
            } catch (_: TimeoutCancellationException) {
                Log.e(TAG, "Stop watchdog fired kind=$shutdownKind after ${STOP_WATCHDOG_TIMEOUT_MS}ms")
                if (shouldForceStopProcessOnTimeout(shutdownKind)) {
                    callbacks.forceStopProcess("shutdown_timeout")
                } else if (operationGeneration.compareAndSet(operationToken, operationToken + 1L)) {
                    callbacks.onRecoveryTimeout(shutdownKind, "shutdown_timeout")
                }
            }
        }
        return cleanupJob
    }

    private fun completeEscalatedFinalResources(
        notificationManager: VpnNotificationManager,
        callbacks: Callbacks
    ) {
        VpnKeepaliveWorker.cancel(context)
        notificationManager.resetState()
        callbacks.stopForeignVpnMonitor()
        callbacks.setNetworkCallbackReady(false)
        callbacks.setLastKnownNetwork(null)
        callbacks.clearUnderlyingNetworks()
        callbacks.setVpnInterface(null)
        callbacks.unregisterScreenStateReceiver()
        runCatching { callbacks.closeDefaultInterfaceMonitor() }
            .onFailure { Log.w(TAG, "Failed to close monitor during escalated stop", it) }
        NetworkClient.onVpnStateChanged(false)
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
