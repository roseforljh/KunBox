package com.kunk.singbox.service.manager

import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.core.SelectorManager as CoreSelectorManager
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.service.VpnKeepaliveWorker
import com.kunk.singbox.service.VpnTileService
import com.kunk.singbox.service.notification.VpnNotificationManager
import com.kunk.singbox.service.network.NetworkManager
import com.kunk.singbox.service.network.TrafficMonitor
import com.kunk.singbox.utils.NetworkClient
import io.nekohasekai.libbox.InterfaceUpdateListener
import kotlinx.coroutines.*

/**
 *
 */
class ShutdownManager(
    private val context: Context,
    private val cleanupScope: CoroutineScope
) {
    companion object {
        private const val TAG = "ShutdownManager"
        private const val FAST_PORT_RELEASE_WAIT_MS = 1500L
    }

    /**
     */
    interface Callbacks {
        fun updateServiceState(state: ServiceState)
        fun updateTileState()
        fun stopForegroundService()
        fun stopSelf()

        fun cancelStartVpnJob(): Job?
        fun cancelVpnHealthJob()
        fun cancelRemoteStateUpdateJob()
        fun cancelRouteGroupAutoSelectJob()
        fun cancelAutoFailoverJob()

        fun stopForeignVpnMonitor()
        fun tryClearRunningServiceForLibbox()
        fun unregisterScreenStateReceiver()
        fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?)

        fun isServiceRunning(): Boolean
        fun getVpnInterface(): ParcelFileDescriptor?
        fun getCurrentInterfaceListener(): InterfaceUpdateListener?
        fun getConnectivityManager(): ConnectivityManager?

        fun setVpnInterface(fd: ParcelFileDescriptor?)
        fun setIsRunning(running: Boolean)
        fun setRealTimeNodeName(name: String?)
        fun setVpnLinkValidated(validated: Boolean)
        fun setNoPhysicalNetworkWarningLogged(logged: Boolean)
        fun setDefaultInterfaceName(name: String)
        fun setNetworkCallbackReady(ready: Boolean)
        fun setLastKnownNetwork(network: android.net.Network?)
        fun clearUnderlyingNetworks()

        fun getPendingStartConfigPath(): String?
        fun clearPendingStartConfigPath()
        fun startVpn(configPath: String)

        fun hasExistingTunInterface(): Boolean
    }

    /**
     */
    data class ShutdownOptions(
        val stopService: Boolean,
        val preserveTunInterface: Boolean = !stopService,
        val proxyPort: Int = 0,
        val strictPortRelease: Boolean = false
    )

    /**
     */
    @Suppress("LongParameterList", "LongMethod", "CognitiveComplexMethod", "CyclomaticComplexMethod")
    fun stopVpn(
        options: ShutdownOptions,
        coreManager: CoreManager,
        commandManager: CommandManager,
        trafficMonitor: TrafficMonitor,
        networkManager: NetworkManager?,
        notificationManager: VpnNotificationManager,
        selectorManager: SelectorManager,
        platformInterfaceImpl: PlatformInterfaceImpl,
        callbacks: Callbacks
    ): Job {
        val stopService = options.stopService
        val proxyPort = options.proxyPort

        val jobToJoin = callbacks.cancelStartVpnJob()
        callbacks.cancelVpnHealthJob()
        callbacks.cancelRemoteStateUpdateJob()
        callbacks.cancelRouteGroupAutoSelectJob()
        callbacks.cancelAutoFailoverJob()

        if (stopService) {
            VpnKeepaliveWorker.cancel(context)
            Log.i(TAG, "VPN keepalive worker cancelled")
        }

        notificationManager.resetState()

        trafficMonitor.stop()

        networkManager?.reset()

        callbacks.stopForeignVpnMonitor()

        callbacks.setVpnLinkValidated(false)
        callbacks.setNoPhysicalNetworkWarningLogged(false)
        callbacks.setDefaultInterfaceName("")

        if (stopService) {
            callbacks.setNetworkCallbackReady(false)
            callbacks.setLastKnownNetwork(null)
            callbacks.clearUnderlyingNetworks()
        } else {
            callbacks.setNetworkCallbackReady(false)
        }

        callbacks.tryClearRunningServiceForLibbox()

        CoreSelectorManager.clear()
        selectorManager.clear()

        Log.i(TAG, "stopVpn(stopService=$stopService, proxyPort=$proxyPort)")

        callbacks.setRealTimeNodeName(null)
        callbacks.setIsRunning(false)
        NetworkClient.onVpnStateChanged(false)

        val listener = callbacks.getCurrentInterfaceListener()

        val interfaceToClose: ParcelFileDescriptor?
        if (stopService) {
            interfaceToClose = callbacks.getVpnInterface()
            callbacks.setVpnInterface(null)
            coreManager.setVpnInterface(null)
        } else {
            interfaceToClose = null
            Log.i(TAG, "Keeping vpnInterface for reuse")
        }

        if (stopService) {
            coreManager.releaseLocks()
            callbacks.unregisterScreenStateReceiver()
        }

        return cleanupScope.launch(NonCancellable) {
            try {
                jobToJoin?.join()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to wait for startup job before shutdown", e)
            }

            if (stopService) {
                withContext(Dispatchers.Main) {
                    callbacks.stopForegroundService()
                    runCatching {
                        val manager = context.getSystemService(NotificationManager::class.java)
                        manager.cancel(VpnNotificationManager.NOTIFICATION_ID)
                    }
                    VpnTileService.persistVpnState(context, false)
                    VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
                    VpnTileService.persistVpnPending(context, "")
                    callbacks.updateServiceState(ServiceState.STOPPED)
                    callbacks.updateTileState()
                }
            }

            val serviceCloseStart = SystemClock.elapsedRealtime()
            runCatching { coreManager.stopService() }
                .onFailure { e -> Log.w(TAG, "CoreManager.stopService failed: ${e.message}") }
            Log.i(TAG, "CoreManager service stopped in ${SystemClock.elapsedRealtime() - serviceCloseStart}ms")

            commandManager.stopAndWaitPortRelease(
                proxyPort = proxyPort,
                waitTimeoutMs = FAST_PORT_RELEASE_WAIT_MS,
                forceKillOnTimeout = stopService,
                enforceReleaseOnTimeout = false
            ).onFailure { e ->
                Log.w(TAG, "Error closing command server/client", e)
            }

            if (stopService) {
                try {
                    platformInterfaceImpl.closeDefaultInterfaceMonitor(listener)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to close default interface monitor", e)
                }
            }

            try {
                withTimeout(2000L) {
                    if (interfaceToClose != null) {
                        try {
                            interfaceToClose.close()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to close VPN interface", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Graceful close failed or timed out", e)
            }

            withContext(Dispatchers.Main) {
                if (stopService) {
                    callbacks.stopSelf()
                    Log.i(TAG, "VPN stopped")
                } else {
                    Log.i(TAG, "Config reload: boxService closed, keeping TUN and foreground")
                }
            }

            val startAfterStop = callbacks.getPendingStartConfigPath()
            callbacks.clearPendingStartConfigPath()

            if (!startAfterStop.isNullOrBlank()) {

                val hasExistingTun = callbacks.hasExistingTunInterface()
                if (!hasExistingTun) {
                    waitForSystemVpnDown(callbacks.getConnectivityManager(), 1500L)
                } else {
                    Log.i(TAG, "Skipping waitForSystemVpnDown: TUN interface preserved")
                }
                withContext(Dispatchers.Main) {
                    callbacks.startVpn(startAfterStop)
                }
            }
        }
    }

    private suspend fun waitForSystemVpnDown(cm: ConnectivityManager?, timeoutMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || cm == null) return

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
