package com.kunk.singbox.service.manager

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.utils.perf.PerfTracer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class NetworkSwitchManager(
    private val scope: CoroutineScope,
    private val mainHandler: Handler
) {
    companion object {
        private const val TAG = "NetworkSwitchManager"

        private const val STARTUP_WINDOW_MS = 1000L
        private const val EVENT_AGGREGATION_MS = 300L
        private const val MIN_SWITCH_INTERVAL_MS = 500L
    }

    interface Callbacks {
        fun getConnectivityManager(): ConnectivityManager?
        fun setUnderlyingNetworks(networks: Array<Network>?)
        fun setLastKnownNetwork(network: Network?)
        fun getLastKnownNetwork(): Network?
        fun updateInterfaceListener(name: String, index: Int, isExpensive: Boolean, isConstrained: Boolean)
        fun resetCoreNetwork()
    }

    private var callbacks: Callbacks? = null

    private val vpnStartedAtMs = AtomicLong(0L)
    private val lastSwitchAtMs = AtomicLong(0L)
    private val lastNetworkType = AtomicReference(NetworkType.OTHER)
    private val pendingNetworkUpdate = AtomicReference<Network?>(null)
    private val updateGeneration = AtomicLong(0L)
    private val pendingUpdateLock = Any()
    private val processingLock = Any()
    private var aggregationJob: Job? = null

    enum class NetworkType {
        WIFI, CELLULAR, ETHERNET, OTHER
    }

    fun init(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    fun markVpnStarted() {
        vpnStartedAtMs.set(SystemClock.elapsedRealtime())
    }

    fun handleNetworkUpdate(network: Network) {
        val generation = updateGeneration.incrementAndGet()
        val now = SystemClock.elapsedRealtime()

        val vpnStarted = vpnStartedAtMs.get()
        val timeSinceStart = now - vpnStarted
        val inStartupWindow = vpnStarted > 0 && timeSinceStart < STARTUP_WINDOW_MS

        if (inStartupWindow) {
            Log.d(TAG, "Network update during startup window, deferring...")
            deferNetworkUpdate(network, STARTUP_WINDOW_MS - timeSinceStart + 100, generation)
            return
        }
        val lastSwitch = lastSwitchAtMs.get()
        val timeSinceLastSwitch = now - lastSwitch
        if (timeSinceLastSwitch < MIN_SWITCH_INTERVAL_MS) {
            Log.d(TAG, "Network update too fast, aggregating...")
            aggregateNetworkUpdate(network, generation)
            return
        }

        processNetworkUpdate(network, generation)
    }

    private fun deferNetworkUpdate(network: Network, delayMs: Long, generation: Long) {
        if (!setPendingNetworkIfCurrent(network, generation)) return
        mainHandler.postDelayed({
            val pending = consumePendingNetwork(generation) ?: return@postDelayed
            processNetworkUpdate(pending, generation)
        }, delayMs)
    }

    private fun aggregateNetworkUpdate(network: Network, generation: Long) {
        if (!setPendingNetworkIfCurrent(network, generation)) return
        aggregationJob?.cancel()
        aggregationJob = scope.launch {
            delay(EVENT_AGGREGATION_MS)
            val pending = consumePendingNetwork(generation)
            if (pending != null) {
                withContext(Dispatchers.Main) {
                    processNetworkUpdate(pending, generation)
                }
            }
        }
    }

    private fun setPendingNetworkIfCurrent(network: Network, generation: Long): Boolean {
        return synchronized(pendingUpdateLock) {
            if (generation != updateGeneration.get()) {
                false
            } else {
                pendingNetworkUpdate.set(network)
                true
            }
        }
    }

    private fun consumePendingNetwork(generation: Long): Network? {
        return synchronized(pendingUpdateLock) {
            if (generation != updateGeneration.get()) {
                null
            } else {
                pendingNetworkUpdate.getAndSet(null)
            }
        }
    }

    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod")
    private fun processNetworkUpdate(network: Network, generation: Long) {
        synchronized(processingLock) {
            if (generation != updateGeneration.get()) return@synchronized
            val cb = callbacks ?: return@synchronized
            val cm = cb.getConnectivityManager() ?: return@synchronized

            val caps = cm.getNetworkCapabilities(network)
            if (caps == null ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) {
                Log.d(TAG, "Network $network is not a valid physical network")
                return@synchronized
            }

            val now = SystemClock.elapsedRealtime()
            lastSwitchAtMs.set(now)

            val currentType = detectNetworkType(caps)
            val previousType = lastNetworkType.getAndSet(currentType)
            val typeChanged = currentType != previousType && previousType != NetworkType.OTHER

            if (typeChanged) {
                Log.i(TAG, "Network type changed: $previousType -> $currentType")
            }

            val linkProps = cm.getLinkProperties(network)
            val interfaceName = linkProps?.interfaceName ?: ""
            val isExpensive = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

            val lastKnown = cb.getLastKnownNetwork()
            val networkChanged = network != lastKnown

            if (networkChanged) {
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

            if (networkChanged) {
                Log.i(TAG, "Default physical network changed, resetting core network once")
                resetCoreNetwork(cb)
            }
        }
    }

    private fun resetCoreNetwork(callbacks: Callbacks) {
        val startedAtMs = SystemClock.elapsedRealtime()
        try {
            callbacks.resetCoreNetwork()
            recordNetworkSwitchMetric(startedAtMs, "success")
        } catch (e: Exception) {
            recordNetworkSwitchMetric(startedAtMs, "error")
            throw e
        }
    }

    private fun recordNetworkSwitchMetric(startedAtMs: Long, outcome: String) {
        PerfTracer.recordDuration(
            name = PerfTracer.Phases.NETWORK_SWITCH,
            durationMs = SystemClock.elapsedRealtime() - startedAtMs,
            outcome = outcome
        )
    }

    private fun detectNetworkType(caps: NetworkCapabilities): NetworkType {
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.OTHER
        }
    }

    fun cancelPendingUpdates() {
        synchronized(pendingUpdateLock) {
            updateGeneration.incrementAndGet()
            pendingNetworkUpdate.set(null)
        }
        aggregationJob?.cancel()
        aggregationJob = null
    }

    fun cleanup() {
        cancelPendingUpdates()
        callbacks = null
    }
}
