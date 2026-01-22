package com.kunk.singbox.service.manager

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.system.OsConstants
import android.util.Log
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicLong

/**
 * PlatformInterface 实现，从 SingBoxService 中提取
 * 处理 libbox 平台回调：TUN 设备、网络接口、连接所有者查询等
 */
class PlatformInterfaceImpl(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val mainHandler: Handler,
    private val callbacks: Callbacks
) : PlatformInterface {

    companion object {
        private const val TAG = "PlatformInterfaceImpl"
    }

    /**
     * 回调接口，由 SingBoxService 实现
     */
    interface Callbacks {
        // VPN 操作
        fun protect(fd: Int): Boolean
        fun openTun(options: TunOptions): Result<Int>

        // 网络状态
        fun getConnectivityManager(): ConnectivityManager?
        fun getCurrentNetwork(): Network?
        fun getLastKnownNetwork(): Network?
        fun setLastKnownNetwork(network: Network?)
        fun markVpnStarted()

        // 网络重置
        fun requestCoreNetworkReset(reason: String, force: Boolean)
        fun resetConnectionsOptimal(reason: String, skipDebounce: Boolean)
        fun setUnderlyingNetworks(networks: Array<Network>?)

        // 状态查询
        fun isRunning(): Boolean
        fun isStarting(): Boolean
        fun isManuallyStopped(): Boolean
        fun getLastConfigPath(): String?
        fun getCurrentSettings(): com.kunk.singbox.model.AppSettings?

        // 统计
        fun incrementConnectionOwnerCalls()
        fun incrementConnectionOwnerInvalidArgs()
        fun incrementConnectionOwnerUidResolved()
        fun incrementConnectionOwnerSecurityDenied()
        fun incrementConnectionOwnerOtherException()
        fun setConnectionOwnerLastEvent(event: String)
        fun setConnectionOwnerLastUid(uid: Int)
        fun isConnectionOwnerPermissionDeniedLogged(): Boolean
        fun setConnectionOwnerPermissionDeniedLogged(logged: Boolean)

        // 缓存
        fun cacheUidToPackage(uid: Int, packageName: String)
        fun getUidFromCache(uid: Int): String?

        // 接口监听
        fun findBestPhysicalNetwork(): Network?
    }

    // 网络监听状态
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var vpnNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentInterfaceListener: InterfaceUpdateListener? = null
    private var networkCallbackReady = false
    private var defaultInterfaceName = ""

    // VPN 健康检查
    private var vpnHealthJob: Job? = null
    private var postTunRebindJob: Job? = null
    private var vpnLinkValidated = false

    // 时间戳
    private val vpnStartedAtMs = AtomicLong(0L)
    private val lastSetUnderlyingNetworksAtMs = AtomicLong(0L)
    private val vpnStartupWindowMs: Long = 3000L
    private val setUnderlyingNetworksDebounceMs: Long = 500L

    // 日志控制
    private var noPhysicalNetworkWarningLogged = false

    override fun localDNSTransport(): io.nekohasekai.libbox.LocalDNSTransport {
        return com.kunk.singbox.core.LocalResolverImpl
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        val result = callbacks.protect(fd)
        if (!result) {
            Log.e(TAG, "autoDetectInterfaceControl: protect($fd) failed")
            runCatching {
                com.kunk.singbox.repository.LogRepository.getInstance()
                    .addLog("ERROR: protect($fd) failed")
            }
        }
    }

    override fun openTun(options: TunOptions?): Int {
        if (options == null) return -1

        try {
            // 检查 VPN lockdown
            val alwaysOnPkg = runCatching {
                Settings.Secure.getString(context.contentResolver, "always_on_vpn_app")
            }.getOrNull() ?: runCatching {
                Settings.Global.getString(context.contentResolver, "always_on_vpn_app")
            }.getOrNull()

            val lockdownSecure = runCatching {
                Settings.Secure.getInt(context.contentResolver, "always_on_vpn_lockdown", 0)
            }.getOrDefault(0)
            val lockdownGlobal = runCatching {
                Settings.Global.getInt(context.contentResolver, "always_on_vpn_lockdown", 0)
            }.getOrDefault(0)
            val lockdown = lockdownSecure != 0 || lockdownGlobal != 0

            if (lockdown && !alwaysOnPkg.isNullOrBlank() && alwaysOnPkg != context.packageName) {
                throw IllegalStateException("VPN lockdown enabled by $alwaysOnPkg")
            }

            // 委托给 Callbacks
            val result = callbacks.openTun(options)

            return result.getOrElse { e ->
                Log.e(TAG, "openTun failed: ${e.message}", e)
                throw e
            }.also { fd ->
                val network = callbacks.getCurrentNetwork()
                if (network != null) {
                    callbacks.setLastKnownNetwork(network)
                    vpnStartedAtMs.set(SystemClock.elapsedRealtime())
                    callbacks.markVpnStarted()
                }
                Log.i(TAG, "TUN interface established with fd: $fd")
            }
        } catch (e: Exception) {
            Log.e(TAG, "openTun exception: ${e.message}", e)
            throw e
        }
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun useProcFS(): Boolean {
        val procPaths = listOf(
            "/proc/net/tcp",
            "/proc/net/tcp6",
            "/proc/net/udp",
            "/proc/net/udp6"
        )

        fun hasUidHeader(path: String): Boolean {
            return try {
                val file = File(path)
                if (!file.exists() || !file.canRead()) return false
                val header = file.bufferedReader().use { it.readLine() } ?: return false
                header.contains("uid")
            } catch (_: Exception) {
                false
            }
        }

        val readable = procPaths.all { path -> hasUidHeader(path) }

        if (!readable) {
            callbacks.setConnectionOwnerLastEvent("procfs_unreadable_or_no_uid -> force findConnectionOwner")
        }

        return readable
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int
    ): Int {
        callbacks.incrementConnectionOwnerCalls()

        fun findUidFromProcFsBySourcePort(protocol: Int, srcPort: Int): Int {
            if (srcPort <= 0) return 0

            val procFiles = when (protocol) {
                OsConstants.IPPROTO_TCP -> listOf("/proc/net/tcp", "/proc/net/tcp6")
                OsConstants.IPPROTO_UDP -> listOf("/proc/net/udp", "/proc/net/udp6")
                else -> emptyList()
            }
            if (procFiles.isEmpty()) return 0

            val targetPortHex = srcPort.toString(16).uppercase().padStart(4, '0')

            fun parseUidFromLine(parts: List<String>): Int {
                if (parts.size < 9) return 0
                val uidStr = parts.getOrNull(7) ?: return 0
                return uidStr.toIntOrNull() ?: 0
            }

            for (path in procFiles) {
                try {
                    val file = File(path)
                    if (!file.exists() || !file.canRead()) continue
                    var resultUid = 0
                    file.bufferedReader().useLines { lines ->
                        for (line in lines.drop(1)) {
                            val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                            val local = parts.getOrNull(1) ?: continue
                            val portHex = local.substringAfter(':', "").uppercase()
                            if (portHex == targetPortHex) {
                                val uid = parseUidFromLine(parts)
                                if (uid > 0) {
                                    resultUid = uid
                                    break
                                }
                            }
                        }
                    }
                    if (resultUid > 0) return resultUid
                } catch (e: Exception) {
                    // ignore
                }
            }
            return 0
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            callbacks.incrementConnectionOwnerInvalidArgs()
            callbacks.setConnectionOwnerLastEvent("api<29")
            return 0
        }

        fun parseAddress(value: String?): InetAddress? {
            if (value.isNullOrBlank()) return null
            val cleaned = value.trim().replace("[", "").replace("]", "").substringBefore("%")
            return try {
                InetAddress.getByName(cleaned)
            } catch (_: Exception) {
                null
            }
        }

        val sourceIp = parseAddress(sourceAddress)
        val destinationIp = parseAddress(destinationAddress)

        val protocol = when (ipProtocol) {
            OsConstants.IPPROTO_TCP -> OsConstants.IPPROTO_TCP
            OsConstants.IPPROTO_UDP -> OsConstants.IPPROTO_UDP
            else -> ipProtocol
        }

        if (sourceIp == null || sourcePort <= 0 || destinationIp == null || destinationPort <= 0) {
            val uid = findUidFromProcFsBySourcePort(protocol, sourcePort)
            if (uid > 0) {
                callbacks.incrementConnectionOwnerUidResolved()
                callbacks.setConnectionOwnerLastUid(uid)
                callbacks.setConnectionOwnerLastEvent(
                    "procfs_fallback uid=$uid proto=$protocol src=$sourceAddress:$sourcePort dst=$destinationAddress:$destinationPort"
                )
                return uid
            }

            callbacks.incrementConnectionOwnerInvalidArgs()
            callbacks.setConnectionOwnerLastEvent(
                "invalid_args src=$sourceAddress:$sourcePort dst=$destinationAddress:$destinationPort proto=$ipProtocol"
            )
            return 0
        }

        return try {
            val cm = callbacks.getConnectivityManager() ?: return 0
            val uid = cm.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(sourceIp, sourcePort),
                InetSocketAddress(destinationIp, destinationPort)
            )
            if (uid > 0) {
                callbacks.incrementConnectionOwnerUidResolved()
                callbacks.setConnectionOwnerLastUid(uid)
                callbacks.setConnectionOwnerLastEvent(
                    "resolved uid=$uid proto=$protocol $sourceIp:$sourcePort->$destinationIp:$destinationPort"
                )
                uid
            } else {
                callbacks.setConnectionOwnerLastEvent(
                    "unresolved uid=$uid proto=$protocol $sourceIp:$sourcePort->$destinationIp:$destinationPort"
                )
                0
            }
        } catch (e: SecurityException) {
            callbacks.incrementConnectionOwnerSecurityDenied()
            callbacks.setConnectionOwnerLastEvent(
                "SecurityException findConnectionOwner proto=$protocol $sourceIp:$sourcePort->$destinationIp:$destinationPort"
            )
            if (!callbacks.isConnectionOwnerPermissionDeniedLogged()) {
                callbacks.setConnectionOwnerPermissionDeniedLogged(true)
                Log.w(TAG, "findConnectionOwner permission denied; app routing may not work on this ROM", e)
                com.kunk.singbox.repository.LogRepository.getInstance()
                    .addLog("WARN: findConnectionOwner permission denied; per-app routing disabled on this ROM")
            }
            val uid = findUidFromProcFsBySourcePort(protocol, sourcePort)
            if (uid > 0) {
                callbacks.incrementConnectionOwnerUidResolved()
                callbacks.setConnectionOwnerLastUid(uid)
                callbacks.setConnectionOwnerLastEvent("procfs_fallback_after_security uid=$uid")
                uid
            } else {
                0
            }
        } catch (e: Exception) {
            callbacks.incrementConnectionOwnerOtherException()
            callbacks.setConnectionOwnerLastEvent("Exception ${e.javaClass.simpleName}: ${e.message}")
            val uid = findUidFromProcFsBySourcePort(protocol, sourcePort)
            if (uid > 0) {
                callbacks.incrementConnectionOwnerUidResolved()
                callbacks.setConnectionOwnerLastUid(uid)
                callbacks.setConnectionOwnerLastEvent("procfs_fallback_after_exception uid=$uid")
                uid
            } else {
                0
            }
        }
    }

    override fun packageNameByUid(uid: Int): String {
        if (uid <= 0) return ""
        return try {
            val pkgs = context.packageManager.getPackagesForUid(uid)
            if (!pkgs.isNullOrEmpty()) {
                pkgs[0]
            } else {
                val name = runCatching { context.packageManager.getNameForUid(uid) }.getOrNull().orEmpty()
                if (name.isNotBlank()) {
                    callbacks.cacheUidToPackage(uid, name)
                    name
                } else {
                    callbacks.getUidFromCache(uid) ?: ""
                }
            }
        } catch (_: Exception) {
            val name = runCatching { context.packageManager.getNameForUid(uid) }.getOrNull().orEmpty()
            if (name.isNotBlank()) {
                callbacks.cacheUidToPackage(uid, name)
                name
            } else {
                callbacks.getUidFromCache(uid) ?: ""
            }
        }
    }

    override fun uidByPackageName(packageName: String?): Int {
        if (packageName.isNullOrBlank()) return 0
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val uid = appInfo.uid
            if (uid > 0) uid else 0
        } catch (_: Exception) {
            0
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        currentInterfaceListener = listener

        // 提前设置启动窗口期时间戳
        vpnStartedAtMs.set(SystemClock.elapsedRealtime())
        lastSetUnderlyingNetworksAtMs.set(SystemClock.elapsedRealtime())

        connectivityManager = callbacks.getConnectivityManager()

        // 使用 Application 层预缓存的网络
        var initialNetwork: Network? = com.kunk.singbox.utils.DefaultNetworkListener.underlyingNetwork

        // 如果预缓存不可用，尝试使用之前保存的 lastKnownNetwork
        if (initialNetwork == null) {
            val lastKnown = callbacks.getLastKnownNetwork()
            if (lastKnown != null) {
                val caps = connectivityManager?.getNetworkCapabilities(lastKnown)
                val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                if (!isVpn && hasInternet) {
                    initialNetwork = lastKnown
                    Log.i(TAG, "startDefaultInterfaceMonitor: using preserved lastKnownNetwork: $lastKnown")
                }
            }
        }

        // 最后尝试 activeNetwork
        if (initialNetwork == null) {
            val activeNet = connectivityManager?.activeNetwork
            if (activeNet != null) {
                val caps = connectivityManager?.getNetworkCapabilities(activeNet)
                val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                if (!isVpn && caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                    initialNetwork = activeNet
                }
            }
        }

        if (initialNetwork != null) {
            networkCallbackReady = true
            callbacks.setLastKnownNetwork(initialNetwork)

            val linkProperties = connectivityManager?.getLinkProperties(initialNetwork)
            val interfaceName = linkProperties?.interfaceName ?: ""
            if (interfaceName.isNotEmpty()) {
                defaultInterfaceName = interfaceName
                val index = try {
                    NetworkInterface.getByName(interfaceName)?.index ?: 0
                } catch (e: Exception) { 0 }
                val caps = connectivityManager?.getNetworkCapabilities(initialNetwork)
                val isExpensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                currentInterfaceListener?.updateDefaultInterface(interfaceName, index, isExpensive, false)
            }

            Log.i(TAG, "startDefaultInterfaceMonitor: initialized with network=$initialNetwork, interface=$defaultInterfaceName")
        } else {
            Log.w(TAG, "startDefaultInterfaceMonitor: no usable physical network found at startup")
        }

        // 将 NetworkCallback 注册延迟到 cgo callback 返回之后
        mainHandler.post {
            registerNetworkCallbacksDeferred()
        }
    }

    private fun registerNetworkCallbacksDeferred() {
        if (networkCallback != null) return

        val cm = connectivityManager ?: return

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                if (isVpn) return

                val isActiveDefault = cm.activeNetwork == network
                if (isActiveDefault) {
                    networkCallbackReady = true
                    Log.i(TAG, "Network available: $network (active default)")
                    updateDefaultInterface(network)
                }
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "Network lost: $network")
                if (network == callbacks.getLastKnownNetwork()) {
                    val newActive = cm.activeNetwork
                    if (newActive != null) {
                        Log.i(TAG, "Switching to new active network: $newActive")
                        updateDefaultInterface(newActive)
                    } else {
                        callbacks.setLastKnownNetwork(null)
                        currentInterfaceListener?.updateDefaultInterface("", 0, false, false)
                    }
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                if (isVpn) return

                if (cm.activeNetwork == network) {
                    networkCallbackReady = true
                    updateDefaultInterface(network)
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        try {
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback", e)
        }

        vpnNetworkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!callbacks.isRunning()) return
                val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (isValidated) {
                    vpnLinkValidated = true
                    if (vpnHealthJob?.isActive == true) {
                        Log.i(TAG, "VPN link validated, cancelling recovery job")
                        vpnHealthJob?.cancel()
                    }
                } else {
                    if (vpnHealthJob?.isActive != true) {
                        Log.w(TAG, "VPN link not validated, scheduling recovery in 5s")
                        vpnHealthJob = serviceScope.launch {
                            delay(5000)
                            if (callbacks.isRunning() && !callbacks.isStarting() &&
                                !callbacks.isManuallyStopped() && callbacks.getLastConfigPath() != null) {
                                Log.w(TAG, "VPN link still not validated after 5s, attempting rebind and reset")
                                try {
                                    val bestNetwork = callbacks.findBestPhysicalNetwork()
                                    if (bestNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                                        callbacks.setUnderlyingNetworks(arrayOf(bestNetwork))
                                        callbacks.setLastKnownNetwork(bestNetwork)
                                        Log.i(TAG, "Rebound underlying network to $bestNetwork during health recovery")
                                        com.kunk.singbox.repository.LogRepository.getInstance()
                                            .addLog("INFO VPN health recovery: rebound to $bestNetwork")
                                    }
                                    callbacks.requestCoreNetworkReset(reason = "vpnHealthRecovery", force = false)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to reset network stack during health recovery", e)
                                }
                            }
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                vpnHealthJob?.cancel()
            }
        }

        val vpnRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        try {
            cm.registerNetworkCallback(vpnRequest, vpnNetworkCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register VPN network callback", e)
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        vpnNetworkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        vpnHealthJob?.cancel()
        postTunRebindJob?.cancel()
        postTunRebindJob = null
        vpnNetworkCallback = null
        networkCallback = null
        currentInterfaceListener = null
        networkCallbackReady = false
    }

    override fun getInterfaces(): NetworkInterfaceIterator? {
        return try {
            val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            object : NetworkInterfaceIterator {
                private val iterator = interfaces.filter { !it.isLoopback }.iterator()

                override fun hasNext(): Boolean = iterator.hasNext()

                override fun next(): io.nekohasekai.libbox.NetworkInterface {
                    val iface = iterator.next()
                    return io.nekohasekai.libbox.NetworkInterface().apply {
                        name = iface.name
                        index = iface.index
                        mtu = iface.mtu

                        var flagsStr = 0
                        if (iface.isUp) flagsStr = flagsStr or 1
                        if (iface.isLoopback) flagsStr = flagsStr or 4
                        if (iface.isPointToPoint) flagsStr = flagsStr or 8
                        if (iface.supportsMulticast()) flagsStr = flagsStr or 16
                        flags = flagsStr

                        val addrList = ArrayList<String>()
                        for (addr in iface.interfaceAddresses) {
                            val ip = addr.address.hostAddress
                            val cleanIp = if (ip != null && ip.contains("%")) ip.substring(0, ip.indexOf("%")) else ip
                            if (cleanIp != null) {
                                addrList.add("$cleanIp/${addr.networkPrefixLength}")
                            }
                        }
                        addresses = StringIteratorImpl(addrList)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get interfaces", e)
            null
        }
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState? = null

    override fun clearDNSCache() {}

    override fun sendNotification(notification: io.nekohasekai.libbox.Notification?) {}

    override fun systemCertificates(): StringIterator? = null

    override fun writeLog(message: String?) {
        if (message.isNullOrBlank()) return
        com.kunk.singbox.repository.LogRepository.getInstance().addLog(message)
    }

    // ========== 内部方法 ==========

    private fun updateDefaultInterface(network: Network) {
        try {
            val now = SystemClock.elapsedRealtime()
            val vpnStartedAt = vpnStartedAtMs.get()
            val timeSinceVpnStart = now - vpnStartedAt
            val inStartupWindow = vpnStartedAt > 0 && timeSinceVpnStart < vpnStartupWindowMs

            if (inStartupWindow) {
                Log.d(TAG, "updateDefaultInterface: skipped during startup window (${timeSinceVpnStart}ms < ${vpnStartupWindowMs}ms)")
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

            val lastKnownNetwork = callbacks.getLastKnownNetwork()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && (network != lastKnownNetwork || upstreamChanged)) {
                val lastSet = lastSetUnderlyingNetworksAtMs.get()
                val timeSinceLastSet = now - lastSet
                val shouldSetNetwork = timeSinceLastSet >= setUnderlyingNetworksDebounceMs || network != lastKnownNetwork

                if (shouldSetNetwork) {
                    callbacks.setUnderlyingNetworks(arrayOf(network))
                    lastSetUnderlyingNetworksAtMs.set(now)
                    callbacks.setLastKnownNetwork(network)
                    noPhysicalNetworkWarningLogged = false
                    postTunRebindJob?.cancel()
                    postTunRebindJob = null
                    Log.i(TAG, "Switched underlying network to $network (upstream=$interfaceName, debounce=${timeSinceLastSet}ms)")

                    callbacks.requestCoreNetworkReset(reason = "underlyingNetworkChanged", force = true)
                }
            }

            if (interfaceName.isNotEmpty() && interfaceName != defaultInterfaceName) {
                val oldInterfaceName = defaultInterfaceName
                defaultInterfaceName = interfaceName
                val index = try {
                    NetworkInterface.getByName(interfaceName)?.index ?: 0
                } catch (e: Exception) { 0 }
                val networkCaps = cm.getNetworkCapabilities(network)
                val isExpensive = networkCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                Log.i(TAG, "Default interface updated: $oldInterfaceName -> $interfaceName (index: $index)")
                currentInterfaceListener?.updateDefaultInterface(interfaceName, index, isExpensive, false)

                if (oldInterfaceName.isNotEmpty() && callbacks.isRunning()) {
                    val settings = callbacks.getCurrentSettings()
                    if (settings?.networkChangeResetConnections == true) {
                        serviceScope.launch {
                            try {
                                callbacks.resetConnectionsOptimal(reason = "interface_change", skipDebounce = true)
                                Log.i(TAG, "Closed all connections after interface change: $oldInterfaceName -> $interfaceName")
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to close connections after interface change", e)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update default interface", e)
        }
    }

    private class StringIteratorImpl(private val list: List<String>) : StringIterator {
        private var index = 0
        override fun hasNext(): Boolean = index < list.size
        override fun next(): String = list[index++]
        override fun len(): Int = list.size
    }
}
