package com.kunk.singbox.service.manager

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 *
 */
class NetworkSwitchManager(
    private val scope: CoroutineScope,
    private val mainHandler: Handler
) {
    internal data class HealthCheckRecoveryDecision(
        val reason: String,
        val force: Boolean
    )

    companion object {
        private const val TAG = "NetworkSwitchManager"
        private const val NETWORK_TYPE_CHANGED_REASON = "network_type_changed"
        private const val NETWORK_VALIDATED_REASON = "network_validated"

        private const val STARTUP_WINDOW_MS = 1000L
        private const val EVENT_AGGREGATION_MS = 300L
        private const val MIN_SWITCH_INTERVAL_MS = 500L

        internal fun resolveHealthCheckRecovery(
            validated: Boolean,
            connected: Boolean
        ): HealthCheckRecoveryDecision {
            return if (validated || connected) {
                HealthCheckRecoveryDecision(
                    reason = NETWORK_VALIDATED_REASON,
                    force = false
                )
            } else {
                HealthCheckRecoveryDecision(
                    reason = NETWORK_TYPE_CHANGED_REASON,
                    force = true
                )
            }
        }
    }

    interface Callbacks {
        fun getConnectivityManager(): ConnectivityManager?
        fun setUnderlyingNetworks(networks: Array<Network>?)
        fun setLastKnownNetwork(network: Network?)
        fun getLastKnownNetwork(): Network?
        fun updateInterfaceListener(name: String, index: Int, isExpensive: Boolean, isConstrained: Boolean)
        fun isRunning(): Boolean
        fun requestCoreNetworkRecovery(reason: String, force: Boolean = false)
    }

    private var callbacks: Callbacks? = null

    private val vpnStartedAtMs = AtomicLong(0L)
    private val lastSwitchAtMs = AtomicLong(0L)
    private val lastNetworkType = AtomicReference(NetworkType.OTHER)
    private val pendingNetworkUpdate = AtomicReference<Network?>(null)
    private var aggregationJob: Job? = null
    private var healthCheckJob: Job? = null

    private val switchCount = AtomicLong(0)
    private val failedSwitchCount = AtomicLong(0)
    private val typeChangeCount = AtomicLong(0)

    enum class NetworkType {
        WIFI, CELLULAR, ETHERNET, OTHER
    }

    fun init(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    fun markVpnStarted() {
        vpnStartedAtMs.set(SystemClock.elapsedRealtime())
    }

    /**
     */
    fun handleNetworkUpdate(network: Network) {
        val now = SystemClock.elapsedRealtime()

        val vpnStarted = vpnStartedAtMs.get()
        val timeSinceStart = now - vpnStarted
        val inStartupWindow = vpnStarted > 0 && timeSinceStart < STARTUP_WINDOW_MS

        if (inStartupWindow) {
            Log.d(TAG, "Network update during startup window, deferring...")
            deferNetworkUpdate(network, STARTUP_WINDOW_MS - timeSinceStart + 100)
            return
        }
        val lastSwitch = lastSwitchAtMs.get()
        val timeSinceLastSwitch = now - lastSwitch
        if (timeSinceLastSwitch < MIN_SWITCH_INTERVAL_MS) {
            Log.d(TAG, "Network update too fast, aggregating...")
            aggregateNetworkUpdate(network)
            return
        }

        processNetworkUpdate(network)
    }

    /**
     */
    private fun deferNetworkUpdate(network: Network, delayMs: Long) {
        pendingNetworkUpdate.set(network)
        mainHandler.postDelayed({
            val pending = pendingNetworkUpdate.getAndSet(null)
            if (pending == network) {
                processNetworkUpdate(pending)
            }
        }, delayMs)
    }

    /**
     */
    private fun aggregateNetworkUpdate(network: Network) {
        pendingNetworkUpdate.set(network)
        aggregationJob?.cancel()
        aggregationJob = scope.launch {
            delay(EVENT_AGGREGATION_MS)
            val pending = pendingNetworkUpdate.getAndSet(null)
            if (pending != null) {
                withContext(Dispatchers.Main) {
                    processNetworkUpdate(pending)
                }
            }
        }
    }

    /**
     */
    @Suppress("CyclomaticComplexMethod")
    private fun processNetworkUpdate(network: Network) {
        val cb = callbacks ?: return
        val cm = cb.getConnectivityManager() ?: return

        val caps = cm.getNetworkCapabilities(network)
        val isValidPhysical = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true

        if (!isValidPhysical) {
            Log.d(TAG, "Network $network is not a valid physical network")
            return
        }

        val now = SystemClock.elapsedRealtime()
        lastSwitchAtMs.set(now)
        switchCount.incrementAndGet()

        val currentType = detectNetworkType(caps)
        val previousType = lastNetworkType.getAndSet(currentType)
        val typeChanged = currentType != previousType && previousType != NetworkType.OTHER

        if (typeChanged) {
            typeChangeCount.incrementAndGet()
            Log.i(TAG, "Network type changed: $previousType -> $currentType")
        }

        val linkProps = cm.getLinkProperties(network)
        val interfaceName = linkProps?.interfaceName ?: ""
        val isExpensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false

        val lastKnown = cb.getLastKnownNetwork()
        val networkChanged = network != lastKnown

        if (networkChanged && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            cb.setUnderlyingNetworks(arrayOf(network))
            cb.setLastKnownNetwork(network)
            Log.i(TAG, "Switched underlying network to $network (interface=$interfaceName)")
        }

        if (interfaceName.isNotEmpty()) {
            val index = try {
                java.net.NetworkInterface.getByName(interfaceName)?.index ?: 0
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get network interface index: ${e.message}")
                0
            }
            cb.updateInterfaceListener(interfaceName, index, isExpensive, false)
        }

        if (networkChanged && typeChanged) {
            Log.i(TAG, "Network type changed, requesting recovery")
            cb.requestCoreNetworkRecovery(reason = NETWORK_TYPE_CHANGED_REASON, force = true)
        } else if (networkChanged) {
            performHealthCheck(network)
        }
    }

    /**
     */
    private fun detectNetworkType(caps: NetworkCapabilities?): NetworkType {
        if (caps == null) return NetworkType.OTHER
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.OTHER
        }
    }

    @Suppress("CognitiveComplexMethod")
    private fun performHealthCheck(network: Network) {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch(Dispatchers.IO) {
            val cb = callbacks ?: return@launch
            val cm = cb.getConnectivityManager() ?: return@launch

            var validated = false
            var connected = false
            for (attempt in 0 until 10) {
                val caps = cm.getNetworkCapabilities(network)
                if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) {
                    validated = true
                    Log.i(TAG, "Network validated after ${attempt * 200}ms")
                    break
                }
                delay(200)
            }

            if (!validated) {
                val testTargets = listOf(
                    "1.1.1.1" to 53,
                    "8.8.8.8" to 53,
                    "223.5.5.5" to 53
                )

                for ((host, port) in testTargets) {
                    try {
                        network.socketFactory.createSocket().use { socket ->
                            socket.connect(InetSocketAddress(host, port), 2000)
                        }
                        connected = true
                        Log.i(TAG, "Connectivity verified via $host:$port")
                        break
                    } catch (e: Exception) {
                        Log.d(TAG, "Connectivity test to $host failed: ${e.message}")
                    }
                }
            }

            val recoveryDecision = resolveHealthCheckRecovery(validated = validated, connected = connected)
            if (recoveryDecision.force) {
                failedSwitchCount.incrementAndGet()
                Log.w(
                    TAG,
                    "Network health check failed for $network, escalating recovery via ${recoveryDecision.reason}"
                )
            } else {
                Log.i(TAG, "Network health check passed, requesting recovery via ${recoveryDecision.reason}")
            }

            cb.requestCoreNetworkRecovery(
                reason = recoveryDecision.reason,
                force = recoveryDecision.force
            )
        }
    }

    /**
     */
    fun cancelPendingUpdates() {
        pendingNetworkUpdate.set(null)
        aggregationJob?.cancel()
        aggregationJob = null
    }

    /**
     */
    fun getMetrics(): Map<String, Long> {
        return mapOf(
            "switch_count" to switchCount.get(),
            "failed_switch_count" to failedSwitchCount.get(),
            "type_change_count" to typeChangeCount.get(),
            "last_switch_at_ms" to lastSwitchAtMs.get()
        )
    }

    /**
     */
    fun cleanup() {
        cancelPendingUpdates()
        callbacks = null
    }
}
