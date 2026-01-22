package com.kunk.singbox.service.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.core.LibboxCompat
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.RuleSetRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.utils.DefaultNetworkListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * 网络辅助工具类
 * 提取自 SingBoxService，负责网络相关的辅助操作
 */
class NetworkHelper(
    private val context: Context,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "NetworkHelper"
    }

    private val connectivityManager: ConnectivityManager? by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    /**
     * 并行启动初始化
     * 同时执行: 网络检测、规则集检查、设置加载
     */
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
                networkCallbackReady, lastKnownNetwork,
                findBestPhysicalNetwork, updateNetworkState, 1500L
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
     * 等待网络回调就绪
     */
    suspend fun ensureNetworkCallbackReady(
        networkCallbackReady: Boolean,
        lastKnownNetwork: Network?,
        findBestPhysicalNetwork: () -> Network?,
        updateNetworkState: (Network?, Boolean) -> Unit,
        timeoutMs: Long = 2000L
    ) {
        if (networkCallbackReady && lastKnownNetwork != null) {
            return
        }

        val cm = connectivityManager ?: return

        // 先尝试主动采样
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

        // 等待回调就绪
        val startTime = System.currentTimeMillis()
        while (!networkCallbackReady && System.currentTimeMillis() - startTime < timeoutMs) {
            delay(100)
        }

        if (!networkCallbackReady) {
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
     * 等待可用的物理网络
     */
    suspend fun waitForUsablePhysicalNetwork(
        lastKnownNetwork: Network?,
        networkManager: com.kunk.singbox.service.network.NetworkManager?,
        findBestPhysicalNetwork: () -> Network?,
        timeoutMs: Long
    ): Network? {
        val cm = connectivityManager ?: return null

        // 1. 检查 DefaultNetworkListener 缓存
        DefaultNetworkListener.underlyingNetwork?.let { cached ->
            if (isValidPhysicalNetwork(cm, cached)) {
                Log.i(TAG, "Using DefaultNetworkListener cache: $cached")
                return cached
            }
        }

        // 2. 检查 NetworkManager 缓存
        networkManager?.lastKnownNetwork?.let { cached ->
            if (isValidPhysicalNetwork(cm, cached)) {
                Log.i(TAG, "Using NetworkManager cache: $cached")
                return cached
            }
        }

        // 3. 检查 lastKnownNetwork
        lastKnownNetwork?.let { cached ->
            if (isValidPhysicalNetwork(cm, cached)) {
                Log.i(TAG, "Using lastKnownNetwork cache: $cached")
                return cached
            }
        }

        // 4. 轮询查找
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
     * DNS 预热
     */
    fun warmupDnsCache() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val startTime = System.currentTimeMillis()
                val domains = listOf(
                    "www.google.com",
                    "github.com",
                    "api.github.com",
                    "www.youtube.com",
                    "twitter.com",
                    "facebook.com"
                )

                withTimeoutOrNull(1500L) {
                    domains.map { domain ->
                        async {
                            runCatching { InetAddress.getByName(domain) }
                        }
                    }.awaitAll()
                }

                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "DNS warmup completed in ${elapsed}ms")
            } catch (e: Exception) {
                Log.w(TAG, "DNS warmup failed", e)
            }
        }
    }

    /**
     * 连通性检查
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
     * 重置连接 (优化版)
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
            // 1. 尝试原生 API
            if (LibboxCompat.hasResetAllConnections) {
                if (LibboxCompat.resetAllConnections(true)) {
                    Log.i(TAG, "[$reason] Used native resetAllConnections")
                    LogRepository.getInstance().addLog("INFO [$reason] resetAllConnections via native")
                    return@withContext
                }
            }

            // 2. 尝试 CommandClient
            if (commandManager.closeConnections()) {
                Log.i(TAG, "[$reason] Used CommandClient.closeConnections()")
                return@withContext
            }

            // 3. 回退到逐个关闭
            Log.w(TAG, "[$reason] Falling back to closeRecent")
            closeRecentFn(reason)
        }
    }

    /**
     * 检查是否有活跃的 VPN
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
     * 更新默认接口
     */
    fun updateDefaultInterface(
        network: Network,
        vpnStartedAtMs: Long,
        startupWindowMs: Long,
        defaultInterfaceName: String,
        lastKnownNetwork: Network?,
        lastSetUnderlyingAtMs: Long,
        debounceMs: Long,
        isRunning: Boolean,
        settings: AppSettings?,
        setUnderlyingNetworks: (Array<Network>) -> Unit,
        updateInterfaceListener: (String, Int, Boolean, Boolean) -> Unit,
        updateState: (Network, String, Long) -> Unit,
        requestCoreReset: (String, Boolean) -> Unit,
        resetConnections: (String, Boolean) -> Unit
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 &&
                (network != lastKnownNetwork || upstreamChanged)) {
                val timeSinceLastSet = now - lastSetUnderlyingAtMs
                val shouldSetNetwork = timeSinceLastSet >= debounceMs || network != lastKnownNetwork

                if (shouldSetNetwork) {
                    setUnderlyingNetworks(arrayOf(network))
                    updateState(network, interfaceName, now)
                    Log.i(TAG, "Switched underlying network to $network")
                    requestCoreReset("underlyingNetworkChanged", true)
                }
            }

            if (interfaceName.isNotEmpty() && interfaceName != defaultInterfaceName) {
                val oldInterfaceName = defaultInterfaceName
                val index = try {
                    NetworkInterface.getByName(interfaceName)?.index ?: 0
                } catch (_: Exception) { 0 }
                val networkCaps = cm.getNetworkCapabilities(network)
                val isExpensive = networkCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false

                updateInterfaceListener(interfaceName, index, isExpensive, false)

                if (oldInterfaceName.isNotEmpty() && isRunning) {
                    if (settings?.networkChangeResetConnections == true) {
                        serviceScope.launch {
                            resetConnections("interface_change", true)
                        }
                    }
                }
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
     * 当 NetworkManager 为 null 时的回退逻辑（服务重启期间）
     * 遍历所有网络，查找有效的物理网络（非 VPN）
     */
    fun findBestPhysicalNetworkFallback(): Network? {
        val cm = connectivityManager ?: return null

        // 1. 先检查 activeNetwork 是否是有效的物理网络
        val activeNetwork = cm.activeNetwork
        if (activeNetwork != null && isValidPhysicalNetwork(cm, activeNetwork)) {
            return activeNetwork
        }

        // 2. 遍历所有网络，查找最佳的物理网络
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
