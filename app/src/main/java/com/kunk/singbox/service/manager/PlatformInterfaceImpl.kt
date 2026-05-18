package com.kunk.singbox.service.manager

import android.content.Context
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
import com.kunk.singbox.core.LibboxCompat
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.RoutingMode
import io.nekohasekai.libbox.ConnectionOwner
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
 */
class PlatformInterfaceImpl(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val mainHandler: Handler,
    private val callbacks: Callbacks
) : PlatformInterface {

    companion object {
        private const val TAG = "PlatformInterfaceImpl"
        private const val NETWORK_SWITCH_DELAY_MS = 2000L

        internal fun shouldHandoverToActiveDefaultNetwork(
            isActiveDefault: Boolean,
            isVpn: Boolean,
            isValidPhysical: Boolean
        ): Boolean {
            return isActiveDefault && !isVpn && isValidPhysical
        }

        internal fun shouldHandoverToRequestedDefaultNetwork(
            isVpn: Boolean,
            isValidPhysical: Boolean
        ): Boolean {
            return !isVpn && isValidPhysical
        }

        internal fun shouldForceConnectionOwnerRouting(settings: AppSettings?): Boolean {
            if (settings?.routingMode != RoutingMode.RULE) return false
            return settings.appRules.any { it.enabled } || settings.appGroups.any { it.enabled }
        }

        internal fun shouldExposeProcFsToLibbox(procFsReadable: Boolean, settings: AppSettings?): Boolean {
            if (!procFsReadable) return false
            return !shouldForceConnectionOwnerRouting(settings)
        }

        internal fun shouldBindProtectedSocketToPhysicalNetwork(
            protected: Boolean,
            physicalNetworkAvailable: Boolean
        ): Boolean {
            return protected && physicalNetworkAvailable
        }
    }

    private val networkSwitchManager: NetworkSwitchManager by lazy {
        NetworkSwitchManager(serviceScope, mainHandler).apply {
            init(networkSwitchCallbacks)
        }
    }

    private val networkSwitchCallbacks = object : NetworkSwitchManager.Callbacks {
        override fun getConnectivityManager(): ConnectivityManager? = callbacks.getConnectivityManager()

        override fun setUnderlyingNetworks(networks: Array<Network>?) {
            callbacks.setUnderlyingNetworks(networks)
        }

        override fun setLastKnownNetwork(network: Network?) {
            callbacks.setLastKnownNetwork(network)
        }

        override fun getLastKnownNetwork(): Network? = callbacks.getLastKnownNetwork()

        override fun updateInterfaceListener(name: String, index: Int, isExpensive: Boolean, isConstrained: Boolean) {
            currentInterfaceListener?.updateDefaultInterface(name, index, isExpensive, isConstrained)
        }

        override fun isRunning(): Boolean = callbacks.isRunning()

        override fun requestCoreNetworkRecovery(reason: String, force: Boolean) {
            callbacks.requestCoreNetworkReset(reason, force)
        }
    }

    /**
     */
    interface Callbacks {
        fun protect(fd: Int): Boolean
        fun openTun(options: TunOptions): Result<Int>

        fun getConnectivityManager(): ConnectivityManager?
        fun getCurrentNetwork(): Network?
        fun getLastKnownNetwork(): Network?
        fun setLastKnownNetwork(network: Network?)
        fun markVpnStarted()

        fun requestCoreNetworkReset(reason: String, force: Boolean)
        fun resetConnectionsOptimal(reason: String, skipDebounce: Boolean)
        fun setUnderlyingNetworks(networks: Array<Network>?)

        fun isRunning(): Boolean
        fun isStarting(): Boolean
        fun isManuallyStopped(): Boolean
        fun getLastConfigPath(): String?
        fun getCurrentSettings(): com.kunk.singbox.model.AppSettings?

        fun incrementConnectionOwnerCalls()
        fun incrementConnectionOwnerInvalidArgs()
        fun incrementConnectionOwnerUidResolved()
        fun incrementConnectionOwnerSecurityDenied()
        fun incrementConnectionOwnerOtherException()
        fun setConnectionOwnerLastEvent(event: String)
        fun setConnectionOwnerLastUid(uid: Int)
        fun isConnectionOwnerPermissionDeniedLogged(): Boolean
        fun setConnectionOwnerPermissionDeniedLogged(logged: Boolean)
        fun cacheUidToPackage(uid: Int, packageName: String)
        fun getUidFromCache(uid: Int): String?

        fun findBestPhysicalNetwork(): Network?
    }

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var vpnNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentInterfaceListener: InterfaceUpdateListener? = null
    private var networkCallbackReady = false
    private var defaultInterfaceName = ""
    private var vpnHealthJob: Job? = null
    private var postTunRebindJob: Job? = null
    private var vpnLinkValidated = false

    private val lastVpnHealthRecoveryAtMs = AtomicLong(0L)
    private val vpnHealthRecoveryMinIntervalMs: Long = 30_000L

    private val vpnStartedAtMs = AtomicLong(0L)
    private val lastSetUnderlyingNetworksAtMs = AtomicLong(0L)

    // ProcFS readability cache (avoid repeated /proc reads)
    private val lastProcFsCheckAtMs = AtomicLong(0L)
    @Volatile private var cachedProcFsReadable: Boolean? = null
    private val procFsCheckIntervalMs: Long = 5 * 60_000L

    override fun localDNSTransport(): io.nekohasekai.libbox.LocalDNSTransport {
        return com.kunk.singbox.core.LocalResolverImpl
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        val protected = callbacks.protect(fd)
        if (!protected) {
            Log.e(TAG, "autoDetectInterfaceControl: protect($fd) failed")
            runCatching {
                com.kunk.singbox.repository.LogRepository.getInstance()
                    .addLog("ERROR: protect($fd) failed")
            }
            return
        }

        val physicalNetwork = callbacks.findBestPhysicalNetwork()
        if (shouldBindProtectedSocketToPhysicalNetwork(protected, physicalNetwork != null)) {
            runCatching {
                val pfd = android.os.ParcelFileDescriptor.adoptFd(fd)
                try {
                    physicalNetwork?.bindSocket(pfd.fileDescriptor)
                    Log.d(TAG, "autoDetectInterfaceControl: protected and bound fd=$fd to $physicalNetwork")
                } finally {
                    pfd.detachFd()
                }
            }.onFailure { e ->
                Log.w(TAG, "autoDetectInterfaceControl: bind physical network failed for fd=$fd: ${e.message}")
            }
        }
    }

    override fun openTun(options: TunOptions?): Int {
        if (options == null) return -1

        try {
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

    private fun getProcFsReadable(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val cached = cachedProcFsReadable
        val last = lastProcFsCheckAtMs.get()
        if (cached != null && now - last < procFsCheckIntervalMs) {
            return cached
        }

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
        cachedProcFsReadable = readable
        lastProcFsCheckAtMs.set(now)

        if (!readable) {
            callbacks.setConnectionOwnerLastEvent("procfs_unreadable_or_no_uid -> force findConnectionOwner")
        }

        return readable
    }

    override fun useProcFS(): Boolean {
        val procFsReadable = getProcFsReadable()
        val settings = callbacks.getCurrentSettings()
        val exposeProcFs = shouldExposeProcFsToLibbox(procFsReadable, settings)
        if (!exposeProcFs && procFsReadable && shouldForceConnectionOwnerRouting(settings)) {
            callbacks.setConnectionOwnerLastEvent("app_routing_force_findConnectionOwner")
        }
        return exposeProcFs
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod", "ReturnCount")
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int
    ): ConnectionOwner {
        callbacks.incrementConnectionOwnerCalls()

        // Avoid expensive /proc scanning when it's known to be unreadable.
        val procFsUsable = runCatching { getProcFsReadable() }.getOrDefault(false)

        fun toConnectionOwner(uid: Int): ConnectionOwner {
            if (uid <= 0) return ConnectionOwner()
            val packageName = runCatching {
                val pkgs = context.packageManager.getPackagesForUid(uid)
                if (!pkgs.isNullOrEmpty()) pkgs[0] else context.packageManager.getNameForUid(uid).orEmpty()
            }.getOrDefault("")
            if (packageName.isNotBlank()) {
                callbacks.cacheUidToPackage(uid, packageName)
            }
            return ConnectionOwner().apply {
                userId = uid
                LibboxCompat.setConnectionOwnerPackageName(this, packageName)
                userName = packageName
            }
        }

        fun findUidFromProcFsBySourcePort(protocol: Int, srcPort: Int): Int {
            if (srcPort <= 0) return 0
            if (!procFsUsable) return 0

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
            return ConnectionOwner()
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
                return toConnectionOwner(uid)
            }

            callbacks.incrementConnectionOwnerInvalidArgs()
            callbacks.setConnectionOwnerLastEvent(
                "invalid_args src=$sourceAddress:$sourcePort dst=$destinationAddress:$destinationPort proto=$ipProtocol"
            )
            return ConnectionOwner()
        }

        return try {
            val cm = callbacks.getConnectivityManager() ?: return ConnectionOwner()
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
                toConnectionOwner(uid)
            } else {
                callbacks.setConnectionOwnerLastEvent(
                    "unresolved uid=$uid proto=$protocol $sourceIp:$sourcePort->$destinationIp:$destinationPort"
                )
                ConnectionOwner()
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
                toConnectionOwner(uid)
            } else {
                ConnectionOwner()
            }
        } catch (e: Exception) {
            callbacks.incrementConnectionOwnerOtherException()
            callbacks.setConnectionOwnerLastEvent("Exception ${e.javaClass.simpleName}: ${e.message}")
            val uid = findUidFromProcFsBySourcePort(protocol, sourcePort)
            if (uid > 0) {
                callbacks.incrementConnectionOwnerUidResolved()
                callbacks.setConnectionOwnerLastUid(uid)
                callbacks.setConnectionOwnerLastEvent("procfs_fallback_after_exception uid=$uid")
                toConnectionOwner(uid)
            } else {
                ConnectionOwner()
            }
        }
    }

    private fun isUsableInitialNetwork(network: Network?): Boolean {
        if (network == null) return false
        val caps = connectivityManager?.getNetworkCapabilities(network)
        val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        return !isVpn && hasInternet
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        currentInterfaceListener = listener

        vpnStartedAtMs.set(SystemClock.elapsedRealtime())
        lastSetUnderlyingNetworksAtMs.set(SystemClock.elapsedRealtime())
        networkSwitchManager.markVpnStarted()

        connectivityManager = callbacks.getConnectivityManager()

        var initialNetwork: Network? = com.kunk.singbox.utils.DefaultNetworkListener.underlyingNetwork
        if (initialNetwork != null && !isUsableInitialNetwork(initialNetwork)) {
            Log.w(TAG, "startDefaultInterfaceMonitor: cached underlyingNetwork is invalid, ignoring")
            initialNetwork = null
        }

        if (initialNetwork == null) {
            val lastKnown = callbacks.getLastKnownNetwork()
            if (isUsableInitialNetwork(lastKnown)) {
                initialNetwork = lastKnown
                Log.i(TAG, "startDefaultInterfaceMonitor: using preserved lastKnownNetwork: $lastKnown")
            }
        }

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
                val isActiveDefault = cm.activeNetwork == network
                val isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                val isValidPhysical = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                val shouldHandover = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    shouldHandoverToRequestedDefaultNetwork(isVpn, isValidPhysical)
                } else {
                    shouldHandoverToActiveDefaultNetwork(isActiveDefault, isVpn, isValidPhysical)
                }
                if (!shouldHandover) {
                    return
                }

                networkCallbackReady = true
                if (!isValidated) {
                    Log.d(
                        TAG,
                        "Active default network $network not yet validated, handing over underlying network first"
                    )
                }
                Log.i(TAG, "Network available: $network (active default, validated=$isValidated)")
                updateDefaultInterface(network)
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "Network lost: $network")
                if (network != callbacks.getLastKnownNetwork()) {
                    return
                }

                val newActive = cm.activeNetwork
                if (newActive != null && newActive != network) {
                    Log.i(TAG, "Switching to new active network: $newActive")
                    updateDefaultInterface(newActive)
                    return
                }

                mainHandler.postDelayed({
                    if (callbacks.getLastKnownNetwork() != network) {
                        return@postDelayed
                    }
                    val delayedActive = cm.activeNetwork
                    if (delayedActive != null && delayedActive != network) {
                        Log.i(TAG, "Delayed switch to new active network: $delayedActive")
                        updateDefaultInterface(delayedActive)
                    } else {
                        Log.i(TAG, "No replacement network after delay, waiting for onAvailable")
                    }
                }, NETWORK_SWITCH_DELAY_MS)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                val isActiveDefault = cm.activeNetwork == network
                val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val isValidPhysical = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                val shouldHandover = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    shouldHandoverToRequestedDefaultNetwork(isVpn, isValidPhysical)
                } else {
                    shouldHandoverToActiveDefaultNetwork(isActiveDefault, isVpn, isValidPhysical)
                }
                if (shouldHandover) {
                    networkCallbackReady = true
                    if (!isValidated) {
                        Log.d(TAG, "Active network $network not yet validated, updating underlying network first")
                    }
                    updateDefaultInterface(network)
                }
            }
        }

        val requestBuilder = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requestBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        } else {
            requestBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
        val request = requestBuilder.build()

        val defaultCallback = networkCallback ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cm.requestNetwork(request, defaultCallback)
            } else {
                cm.registerNetworkCallback(request, defaultCallback)
            }
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
                    vpnLinkValidated = false

                    // Captive portal / no internet: do not spam recovery; user action is required.
                    val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val isCaptivePortal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
                    if (!hasInternet || isCaptivePortal) {
                        Log.w(
                            TAG,
                            "VPN link not validated (hasInternet=$hasInternet, " +
                                "captivePortal=$isCaptivePortal), skip recovery"
                        )
                        return
                    }

                    if (vpnHealthJob?.isActive != true) {
                        val now = SystemClock.elapsedRealtime()
                        val last = lastVpnHealthRecoveryAtMs.get()
                        val elapsed = now - last
                        if (elapsed in 0 until vpnHealthRecoveryMinIntervalMs) {
                            Log.w(
                                TAG,
                                "VPN link not validated, skip recovery (throttled, elapsed=${elapsed}ms)"
                            )
                            return
                        }

                        Log.w(TAG, "VPN link not validated, scheduling recovery in 5s")
                        lastVpnHealthRecoveryAtMs.set(now)
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

        val vpnCallback = vpnNetworkCallback ?: return
        try {
            cm.registerNetworkCallback(vpnRequest, vpnCallback)
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
        networkSwitchManager.cleanup()
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

    private fun updateDefaultInterface(network: Network) {

        networkSwitchManager.handleNetworkUpdate(network)
    }

    private class StringIteratorImpl(private val list: List<String>) : StringIterator {
        private var index = 0
        override fun hasNext(): Boolean = index < list.size
        override fun next(): String = list[index++]
        override fun len(): Int = list.size
    }
}
