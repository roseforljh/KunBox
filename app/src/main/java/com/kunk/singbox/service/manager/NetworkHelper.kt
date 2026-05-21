package com.kunk.singbox.service.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.core.LibboxCompat
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.RuleSetRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.utils.DefaultNetworkListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 */
class NetworkHelper(
    private val context: Context,
    @Suppress("UnusedPrivateProperty") private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "NetworkHelper"
    }

    private val connectivityManager: ConnectivityManager? by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    suspend fun parallelStartupInit(
        networkCallbackReady: Boolean,
        lastKnownNetwork: Network?,
        networkManager: com.kunk.singbox.service.network.NetworkManager?,
        findBestPhysicalNetwork: () -> Network?,
        updateNetworkState: (Network?, Boolean) -> Unit,
        lastRuleSetCheckMs: Long,
        ruleSetCheckIntervalMs: Long,
        onRuleSetChecked: (Long) -> Unit
    ): Triple<Network?, Boolean, AppSettings> = coroutineScope {
        val networkDeferred = async(Dispatchers.IO) {
            ensureNetworkCallbackReady(
                isCallbackReady = { networkCallbackReady },
                lastKnownNetwork = { lastKnownNetwork },
                findBestPhysicalNetwork = findBestPhysicalNetwork,
                updateNetworkState = updateNetworkState,
                timeoutMs = 1500L
            )
            waitForUsablePhysicalNetwork(
                lastKnownNetwork, networkManager,
                findBestPhysicalNetwork, 3000L
            )
        }

        val ruleSetDeferred = async(Dispatchers.IO) {
            runCatching {
                val ruleSetRepo = RuleSetRepository.getInstance(context)
                val now = System.currentTimeMillis()
                if (now - lastRuleSetCheckMs >= ruleSetCheckIntervalMs) {
                    onRuleSetChecked(now)
                }
                ruleSetRepo.ensureRuleSetsReady(
                    forceUpdate = false,
                    allowNetwork = false
                ) { }
            }.getOrDefault(false)
        }

        val settingsDeferred = async(Dispatchers.IO) {
            SettingsRepository.getInstance(context).settings.first()
        }

        Triple(
            networkDeferred.await(),
            ruleSetDeferred.await(),
            settingsDeferred.await()
        )
    }

    /**
     */
    suspend fun ensureNetworkCallbackReady(
        isCallbackReady: () -> Boolean,
        lastKnownNetwork: () -> Network?,
        findBestPhysicalNetwork: () -> Network?,
        updateNetworkState: (Network?, Boolean) -> Unit,
        timeoutMs: Long = 2000L
    ) {
        if (isCallbackReady() && lastKnownNetwork() != null) {
            return
        }

        val cm = connectivityManager ?: return

        val activeNet = cm.activeNetwork
        if (activeNet != null) {
            val caps = cm.getNetworkCapabilities(activeNet)
            val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val notVpn = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true

            if (!isVpn && hasInternet && notVpn) {
                updateNetworkState(activeNet, true)
                Log.i(TAG, "Pre-sampled physical network: $activeNet")
                return
            }
        }

        val startTime = System.currentTimeMillis()
        while (!isCallbackReady() && System.currentTimeMillis() - startTime < timeoutMs) {
            delay(100)
        }

        if (!isCallbackReady()) {
            val bestNetwork = findBestPhysicalNetwork()
            if (bestNetwork != null) {
                updateNetworkState(bestNetwork, true)
                Log.i(TAG, "Found physical network after timeout: $bestNetwork")
            } else {
                Log.w(TAG, "Network callback not ready after ${timeoutMs}ms")
            }
        }
    }

    /**
     */
    suspend fun waitForUsablePhysicalNetwork(
        lastKnownNetwork: Network?,
        networkManager: com.kunk.singbox.service.network.NetworkManager?,
        findBestPhysicalNetwork: () -> Network?,
        timeoutMs: Long
    ): Network? {
        val cm = connectivityManager ?: return null
        DefaultNetworkListener.underlyingNetwork?.let { cached ->
            if (isValidPhysicalNetwork(cm, cached)) {
                Log.i(TAG, "Using DefaultNetworkListener cache: $cached")
                return cached
            }
        }
        networkManager?.lastKnownNetwork?.let { cached ->
            if (isValidPhysicalNetwork(cm, cached)) {
                Log.i(TAG, "Using NetworkManager cache: $cached")
                return cached
            }
        }
        lastKnownNetwork?.let { cached ->
            if (isValidPhysicalNetwork(cm, cached)) {
                Log.i(TAG, "Using lastKnownNetwork cache: $cached")
                return cached
            }
        }

        val start = SystemClock.elapsedRealtime()
        var best: Network? = null
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            val candidate = findBestPhysicalNetwork()
            if (candidate != null) {
                val caps = cm.getNetworkCapabilities(candidate)
                val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                val notVpn = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true
                if (hasInternet && notVpn) {
                    best = candidate
                    val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                    if (validated) return candidate
                }
            }
            delay(100)
        }
        return best
    }

    /**
     */
    fun warmupDnsCache() {
        Log.d(TAG, "DNS warmup skipped")
    }

    /**
     */
    suspend fun performConnectivityCheck(): Boolean = withContext(Dispatchers.IO) {
        val testTargets = listOf(
            "1.1.1.1" to 53,
            "8.8.8.8" to 53,
            "223.5.5.5" to 53
        )

        Log.i(TAG, "Starting connectivity check...")

        for ((host, port) in testTargets) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 2000)
                socket.close()
                Log.i(TAG, "Connectivity check passed: $host:$port")
                return@withContext true
            } catch (_: Exception) {
            }
        }

        Log.w(TAG, "Connectivity check failed")
        return@withContext false
    }

    /**
     */
    suspend fun resetConnectionsOptimal(
        reason: String,
        skipDebounce: Boolean,
        lastResetAtMs: Long,
        debounceMs: Long,
        commandManager: CommandManager,
        closeRecentFn: (String) -> Unit,
        updateLastReset: (Long) -> Unit
    ) {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastResetAtMs
        if (!skipDebounce && elapsed < debounceMs) {
            Log.d(TAG, "resetConnectionsOptimal skipped: debounce")
            return
        }
        updateLastReset(now)

        withContext(Dispatchers.IO) {
            if (BoxWrapperManager.isAvailable()) {
                val ok = runCatching { BoxWrapperManager.recoverNetworkAuto() }.getOrDefault(false)
                Log.i(TAG, "[$reason] Used recoverNetworkAuto (ok=$ok)")
                LogRepository.getInstance().addLog("INFO [$reason] recoverNetworkAuto ok=$ok")
                return@withContext
            }

            if (LibboxCompat.hasResetAllConnections) {
                if (LibboxCompat.resetAllConnections(true)) {
                    Log.i(TAG, "[$reason] Used native resetAllConnections")
                    LogRepository.getInstance().addLog("INFO [$reason] resetAllConnections via native")
                    return@withContext
                }
            }

            if (commandManager.closeConnections()) {
                Log.i(TAG, "[$reason] Used CommandClient.closeConnections()")
                return@withContext
            }

            Log.w(TAG, "[$reason] Falling back to closeRecent")
            closeRecentFn(reason)
        }
    }

    /**
     */
    fun isAnyVpnActive(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val cm = connectivityManager ?: return false

        return runCatching {
            @Suppress("DEPRECATION")
            cm.allNetworks.any { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@any false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        }.getOrDefault(false)
    }

    /**
     */
    @Suppress(
        "UnusedParameter", "LongParameterList",
        "CyclomaticComplexMethod", "CognitiveComplexMethod", "NestedBlockDepth"
    )
    fun updateDefaultInterface(
        network: Network,
        vpnStartedAtMs: Long,
        startupWindowMs: Long,
        defaultInterfaceName: String,
        lastKnownNetwork: Network?,
        lastSetUnderlyingAtMs: Long,
        debounceMs: Long,
        isRunning: Boolean,
        setUnderlyingNetworks: (Array<Network>) -> Unit,
        updateInterfaceListener: (String, Int, Boolean, Boolean) -> Unit,
        updateState: (Network, String, Long) -> Unit
    ) {
        try {
            val now = SystemClock.elapsedRealtime()
            val timeSinceVpnStart = now - vpnStartedAtMs
            val inStartupWindow = vpnStartedAtMs > 0 && timeSinceVpnStart < startupWindowMs

            if (inStartupWindow) {
                Log.d(TAG, "updateDefaultInterface: skipped during startup window")
                return
            }

            val cm = connectivityManager ?: return
            val caps = cm.getNetworkCapabilities(network)
            val isValidPhysical = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true

            if (!isValidPhysical) return

            val linkProperties = cm.getLinkProperties(network)
            val interfaceName = linkProperties?.interfaceName ?: ""
            val upstreamChanged = interfaceName.isNotEmpty() && interfaceName != defaultInterfaceName

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                // 2025-fix: Aggressive update without debounce
                // val timeSinceLastSet = now - lastSetUnderlyingAtMs
                // val shouldSetNetwork = timeSinceLastSet >= debounceMs || network != lastKnownNetwork

                // Always update to ensure system and kernel are in sync
                val shouldSetNetwork = true

                if (shouldSetNetwork) {
                    setUnderlyingNetworks(arrayOf(network))
                    updateState(network, interfaceName, now)

                    if (network != lastKnownNetwork || upstreamChanged) {
                        Log.i(TAG, "Switched underlying network to $network")
                    }
                }
            }

            if (interfaceName.isNotEmpty() && interfaceName != defaultInterfaceName) {
                val index = try {
                    NetworkInterface.getByName(interfaceName)?.index ?: 0
                } catch (_: Exception) { 0 }
                val networkCaps = cm.getNetworkCapabilities(network)
                val isExpensive = networkCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false

                updateInterfaceListener(interfaceName, index, isExpensive, false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update default interface", e)
        }
    }

    private fun isValidPhysicalNetwork(cm: ConnectivityManager, network: Network): Boolean {
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    /**
     */
    fun findBestPhysicalNetworkFallback(): Network? {
        val cm = connectivityManager ?: return null

        val activeNetwork = cm.activeNetwork
        if (activeNetwork != null && isValidPhysicalNetwork(cm, activeNetwork)) {
            return activeNetwork
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            @Suppress("DEPRECATION")
            val allNetworks = cm.allNetworks
            var bestNetwork: Network? = null
            var bestScore = -1

            for (net in allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val notVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)

                if (hasInternet && notVpn) {
                    val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    val isEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

                    val score = if (validated) {
                        when {
                            isEthernet -> 5
                            isWifi -> 4
                            isCellular -> 3
                            else -> 1
                        }
                    } else {
                        when {
                            isEthernet -> 2
                            isWifi -> 2
                            isCellular -> 1
                            else -> 0
                        }
                    }

                    if (score > bestScore) {
                        bestScore = score
                        bestNetwork = net
                    }
                }
            }

            if (bestNetwork != null) {
                return bestNetwork
            }
        }

        return null
    }
}
