package com.kunk.singbox.service.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.kunk.singbox.core.LibboxCompat
import com.kunk.singbox.core.StringIteratorImpl
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.PerAppVpnPolicy
import com.kunk.singbox.model.RoutingMode
import com.kunk.singbox.utils.DefaultNetworkListener
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

class PlatformInterfaceImpl(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val mainHandler: Handler,
    private val callbacks: Callbacks
) : PlatformInterface {

    internal enum class ProcFsUidLookupStatus {
        RESOLVED,
        NOT_FOUND,
        AMBIGUOUS,
        UNAVAILABLE
    }

    internal data class ProcFsUidLookupResult(
        val status: ProcFsUidLookupStatus,
        val uid: Int = 0
    )

    private data class ProcFsOwnerCandidate(
        val uid: Int?,
        val matchesFullTuple: Boolean,
        val isWildcardRemote: Boolean
    )

    companion object {
        private const val TAG = "PlatformInterfaceImpl"
        private const val PROC_FS_LOCAL_ENDPOINT_INDEX = 1
        private const val PROC_FS_REMOTE_ENDPOINT_INDEX = 2
        private const val PROC_FS_UID_INDEX = 7
        private const val IP_PROTOCOL_TCP = 6
        private const val IP_PROTOCOL_UDP = 17
        private val PROC_FS_WHITESPACE = Regex("\\s+")
        internal const val UID_PACKAGE_CACHE_TTL_MS = 5 * 60 * 1000L
        internal const val UNKNOWN_CONNECTION_OWNER_UID = -1

        internal fun ensureSocketProtected(protected: Boolean, fd: Int) {
            if (!protected) throw IOException("VpnService.protect($fd) failed")
        }

        internal fun unknownConnectionOwner(): ConnectionOwner = ConnectionOwner().apply {
            userId = UNKNOWN_CONNECTION_OWNER_UID
        }

        internal fun isUidPackageCacheFresh(cachedAtMs: Long, nowMs: Long): Boolean {
            return nowMs - cachedAtMs in 0 until UID_PACKAGE_CACHE_TTL_MS
        }

        internal fun shouldForceConnectionOwnerRouting(settings: AppSettings?): Boolean {
            if (settings?.routingMode != RoutingMode.RULE) return false
            val policy = PerAppVpnPolicy.from(settings)
            return settings.appRules.any { it.enabled && policy.captures(it.packageName, "") } ||
                settings.appGroups.any { group ->
                    group.enabled && group.apps.any { policy.captures(it.packageName, "") }
                }
        }

        internal fun resolveForceConnectionOwnerRouting(
            override: Boolean?,
            settings: AppSettings?
        ): Boolean = override ?: shouldForceConnectionOwnerRouting(settings)

        internal fun shouldExposeProcFsToLibbox(procFsReadable: Boolean, settings: AppSettings?): Boolean {
            if (!procFsReadable) return false
            return !shouldForceConnectionOwnerRouting(settings)
        }

        internal fun resolvePackageNames(
            uid: Int,
            cachedPackageName: String?,
            lookup: () -> Collection<String>
        ): List<String> {
            if (uid <= 0) return emptyList()
            val resolved = runCatching { lookup() }
                .getOrDefault(emptyList())
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
            return resolved.ifEmpty { listOfNotNull(cachedPackageName?.takeIf(String::isNotBlank)) }
        }

        internal fun startListenerIfCurrent(
            isCurrent: () -> Boolean,
            start: () -> Unit,
            stop: () -> Unit
        ) {
            if (!isCurrent()) return
            var completed = false
            try {
                start()
                completed = true
            } finally {
                if (!completed || !isCurrent()) stop()
            }
        }

        internal fun encodeProcFsEndpoint(address: InetAddress, port: Int): String? {
            if (port !in 1..65535) return null
            val addressBytes = address.address
            if (addressBytes.size != 4 && addressBytes.size != 16) return null

            val procFsBytes = addressBytes.copyOf()
            if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                for (offset in procFsBytes.indices step 4) {
                    var left = offset
                    var right = offset + 3
                    while (left < right) {
                        val value = procFsBytes[left]
                        procFsBytes[left] = procFsBytes[right]
                        procFsBytes[right] = value
                        left++
                        right--
                    }
                }
            }

            val hex = "0123456789ABCDEF"
            return buildString(procFsBytes.size * 2 + 5) {
                procFsBytes.forEach { byte ->
                    val value = byte.toInt() and 0xFF
                    append(hex[value ushr 4])
                    append(hex[value and 0x0F])
                }
                append(':')
                append(port.toString(16).uppercase().padStart(4, '0'))
            }
        }

        internal fun procFsTablePath(ipProtocol: Int, addressLength: Int): String? {
            val basePath = when (ipProtocol) {
                IP_PROTOCOL_TCP -> "/proc/net/tcp"
                IP_PROTOCOL_UDP -> "/proc/net/udp"
                else -> return null
            }
            return when (addressLength) {
                4 -> basePath
                16 -> "${basePath}6"
                else -> null
            }
        }

        private fun isProcFsWildcardEndpoint(endpoint: String): Boolean {
            val separator = endpoint.lastIndexOf(':')
            if (separator <= 0 || endpoint.substring(separator + 1) != "0000") return false
            val address = endpoint.substring(0, separator)
            return (address.length == 8 || address.length == 32) && address.all { it == '0' }
        }

        private fun parseProcFsOwnerCandidate(
            line: String,
            sourceEndpoint: String,
            destinationEndpoint: String
        ): ProcFsOwnerCandidate? {
            val parts = line.trim().split(PROC_FS_WHITESPACE)
            val localEndpoint = parts.getOrNull(PROC_FS_LOCAL_ENDPOINT_INDEX) ?: return null
            if (!localEndpoint.equals(sourceEndpoint, ignoreCase = true)) return null
            val remoteEndpoint = parts.getOrNull(PROC_FS_REMOTE_ENDPOINT_INDEX)
            return ProcFsOwnerCandidate(
                uid = parts.getOrNull(PROC_FS_UID_INDEX)?.toIntOrNull()?.takeIf { it > 0 },
                matchesFullTuple = remoteEndpoint?.equals(destinationEndpoint, ignoreCase = true) == true,
                isWildcardRemote = remoteEndpoint?.let(::isProcFsWildcardEndpoint) == true
            )
        }

        private fun resolveProcFsUidCandidates(
            candidates: List<ProcFsOwnerCandidate>,
            allowUdpWildcardFallback: Boolean,
            allowSourceEndpointFallback: Boolean
        ): ProcFsUidLookupResult {
            val fullTupleUids = candidates.filter(ProcFsOwnerCandidate::matchesFullTuple)
                .mapNotNull(ProcFsOwnerCandidate::uid)
                .toSet()
            val sourceEndpointUids = candidates.mapNotNull(ProcFsOwnerCandidate::uid).toSet()
            val hasInvalidFullTupleOwner = candidates.any { it.matchesFullTuple && it.uid == null }
            val hasInvalidSourceOwner = candidates.any { it.uid == null }

            return when {
                fullTupleUids.size > 1 || (fullTupleUids.isNotEmpty() && hasInvalidFullTupleOwner) -> {
                    ProcFsUidLookupResult(ProcFsUidLookupStatus.AMBIGUOUS)
                }
                fullTupleUids.size == 1 -> {
                    ProcFsUidLookupResult(ProcFsUidLookupStatus.RESOLVED, fullTupleUids.first())
                }
                !allowSourceEndpointFallback &&
                    (!allowUdpWildcardFallback || candidates.none(ProcFsOwnerCandidate::isWildcardRemote)) -> {
                    ProcFsUidLookupResult(ProcFsUidLookupStatus.NOT_FOUND)
                }
                sourceEndpointUids.size > 1 || (sourceEndpointUids.isNotEmpty() && hasInvalidSourceOwner) -> {
                    ProcFsUidLookupResult(ProcFsUidLookupStatus.AMBIGUOUS)
                }
                sourceEndpointUids.size == 1 -> {
                    ProcFsUidLookupResult(ProcFsUidLookupStatus.RESOLVED, sourceEndpointUids.first())
                }
                else -> ProcFsUidLookupResult(ProcFsUidLookupStatus.NOT_FOUND)
            }
        }

        internal fun resolveProcFsUidFromLines(
            lines: Sequence<String>,
            sourceEndpoint: String,
            destinationEndpoint: String,
            allowUdpWildcardFallback: Boolean = false,
            allowSourceEndpointFallback: Boolean = false
        ): ProcFsUidLookupResult {
            val iterator = lines.iterator()
            val headerIsValid = iterator.hasNext() && iterator.next().let { header ->
                header.contains("local_address") && header.contains("rem_address") && header.contains("uid")
            }
            if (!headerIsValid) {
                return ProcFsUidLookupResult(ProcFsUidLookupStatus.UNAVAILABLE)
            }

            val candidates = iterator.asSequence()
                .mapNotNull { line -> parseProcFsOwnerCandidate(line, sourceEndpoint, destinationEndpoint) }
                .toList()
            return resolveProcFsUidCandidates(
                candidates,
                allowUdpWildcardFallback,
                allowSourceEndpointFallback
            )
        }

        internal fun networkInterfaceTypeForName(name: String): Int {
            return when {
                isCellularInterfaceName(name) -> 1
                name.startsWith("wlan", ignoreCase = true) -> 0
                name.startsWith("eth", ignoreCase = true) -> 2
                else -> 3
            }
        }

        internal fun isCellularInterfaceName(name: String): Boolean {
            val lower = name.lowercase()
            return lower.startsWith("rmnet") ||
                lower.startsWith("ccmni") ||
                lower.startsWith("ccemni") ||
                lower.startsWith("pdp_ip") ||
                lower.startsWith("wwan") ||
                lower.startsWith("usb")
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

        override fun resetCoreNetwork() {
            callbacks.onDefaultNetworkChanged()
        }
    }

    interface Callbacks {
        fun protect(fd: Int): Boolean
        fun openTun(options: TunOptions): Result<Int>

        fun getConnectivityManager(): ConnectivityManager?
        fun getCurrentNetwork(): Network?
        fun getLastKnownNetwork(): Network?
        fun setLastKnownNetwork(network: Network?)
        fun markVpnStarted()

        fun onDefaultNetworkChanged()
        fun setUnderlyingNetworks(networks: Array<Network>?)

        fun getCurrentSettings(): com.kunk.singbox.model.AppSettings?

        fun forceConnectionOwnerRouting(): Boolean? = null

        fun preferConnectivityOwnerRouting(): Boolean = false

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
    private val defaultNetworkMonitorLock = Any()
    private val defaultNetworkMonitorGeneration = AtomicLong(0L)
    @Volatile private var defaultNetworkListenerKey: Any? = null
    @Volatile private var currentInterfaceListener: InterfaceUpdateListener? = null
    private var defaultInterfaceName = ""

    // ProcFS readability cache (avoid repeated /proc reads)
    private val lastProcFsCheckAtMs = AtomicLong(0L)
    private val lastOwnerTraceAtMs = AtomicLong(0L)
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
        }
        ensureSocketProtected(protected, fd)
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
        val forceOwnerRouting = resolveForceConnectionOwnerRouting(
            callbacks.forceConnectionOwnerRouting(),
            settings
        )
        val exposeProcFs = procFsReadable && !forceOwnerRouting
        if (!exposeProcFs && procFsReadable && forceOwnerRouting) {
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
        val forceConnectionOwnerRouting = resolveForceConnectionOwnerRouting(
            callbacks.forceConnectionOwnerRouting(),
            callbacks.getCurrentSettings()
        )

        fun toConnectionOwner(uid: Int): ConnectionOwner {
            if (uid <= 0) return unknownConnectionOwner()
            val cachedPackageName = callbacks.getUidFromCache(uid)
            val packageNames = PlatformInterfaceImpl.resolvePackageNames(uid, cachedPackageName) {
                context.packageManager.getPackagesForUid(uid)?.toList()
                    ?: listOfNotNull(context.packageManager.getNameForUid(uid))
            }
            val primaryPackageName = packageNames.firstOrNull().orEmpty()
            if (primaryPackageName.isNotBlank() && primaryPackageName != cachedPackageName) {
                callbacks.cacheUidToPackage(uid, primaryPackageName)
            }
            val telegramUid = runCatching {
                context.packageManager.getApplicationInfo("org.telegram.messenger", 0).uid
            }.getOrNull()
            if (uid == telegramUid || "org.telegram.messenger" in packageNames) {
                Log.i(
                    TAG,
                    "[APP_ROUTE_TRACE] owner_resolved uid=$uid packages=${packageNames.joinToString("|")}"
                )
            }
            return ConnectionOwner().apply {
                userId = uid
                LibboxCompat.setConnectionOwnerPackageNames(this, packageNames)
                userName = primaryPackageName
            }
        }

        fun parseAddress(value: String?): InetAddress? {
            if (value.isNullOrBlank()) return null
            val cleaned = value.trim().replace("[", "").replace("]", "").substringBefore("%")
            val looksNumeric = cleaned.any { it == '.' || it == ':' } &&
                cleaned.all { it in "0123456789abcdefABCDEF:." }
            if (!looksNumeric) return null
            return try {
                InetAddress.getByName(cleaned)
            } catch (_: Exception) {
                null
            }
        }

        val sourceIp = parseAddress(sourceAddress)?.takeIf { sourcePort in 1..65535 }
        val destinationIp = parseAddress(destinationAddress)?.takeIf { destinationPort in 1..65535 }

        val protocol = ipProtocol
        val protocolSupported = protocol == IP_PROTOCOL_TCP || protocol == IP_PROTOCOL_UDP

        if (!protocolSupported || sourceIp == null || destinationIp == null) {
            callbacks.incrementConnectionOwnerInvalidArgs()
            callbacks.setConnectionOwnerLastEvent(
                "invalid_args src=$sourceAddress:$sourcePort dst=$destinationAddress:$destinationPort proto=$ipProtocol"
            )
            return unknownConnectionOwner()
        }

        val ownerSourceIp = sourceIp
        val ownerDestinationIp = destinationIp

        fun findUidFromProcFsByConnection(): ProcFsUidLookupResult {
            if (!procFsUsable) return ProcFsUidLookupResult(ProcFsUidLookupStatus.UNAVAILABLE)
            if (sourceIp.address.size != destinationIp.address.size) {
                return ProcFsUidLookupResult(ProcFsUidLookupStatus.NOT_FOUND)
            }

            val path = procFsTablePath(protocol, sourceIp.address.size)
                ?: return ProcFsUidLookupResult(ProcFsUidLookupStatus.NOT_FOUND)
            val sourceEndpoint = encodeProcFsEndpoint(sourceIp, sourcePort)
                ?: return ProcFsUidLookupResult(ProcFsUidLookupStatus.NOT_FOUND)
            val destinationEndpoint = encodeProcFsEndpoint(destinationIp, destinationPort)
                ?: return ProcFsUidLookupResult(ProcFsUidLookupStatus.NOT_FOUND)
            val file = File(path)
            if (!file.exists() || !file.canRead()) {
                return ProcFsUidLookupResult(ProcFsUidLookupStatus.UNAVAILABLE)
            }

            return try {
                file.bufferedReader().useLines { lines ->
                    resolveProcFsUidFromLines(
                        lines = lines,
                        sourceEndpoint = sourceEndpoint,
                        destinationEndpoint = destinationEndpoint,
                        allowUdpWildcardFallback = protocol == IP_PROTOCOL_UDP,
                        allowSourceEndpointFallback = forceConnectionOwnerRouting && protocol == IP_PROTOCOL_TCP
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read $path for connection owner", e)
                ProcFsUidLookupResult(ProcFsUidLookupStatus.UNAVAILABLE)
            }
        }

        fun resolveFromProcFs(eventPrefix: String): ConnectionOwner? {
            val lookup = findUidFromProcFsByConnection()
            val uid = lookup.uid.takeIf { lookup.status == ProcFsUidLookupStatus.RESOLVED && it > 0 }
            if (lookup.status != ProcFsUidLookupStatus.RESOLVED || uid == null) {
                callbacks.setConnectionOwnerLastEvent(
                    "${eventPrefix}_procfs_${lookup.status.name.lowercase()} " +
                        "proto=$protocol src=$sourceAddress:$sourcePort dst=$destinationAddress:$destinationPort"
                )
                if (lookup.status == ProcFsUidLookupStatus.AMBIGUOUS) {
                    Log.w(
                        TAG,
                        "Ambiguous ProcFS owner for " +
                            "$protocol $sourceAddress:$sourcePort->$destinationAddress:$destinationPort"
                    )
                }
                return null
            }

            callbacks.incrementConnectionOwnerUidResolved()
            callbacks.setConnectionOwnerLastUid(uid)
            callbacks.setConnectionOwnerLastEvent(
                "${eventPrefix}_procfs_resolved uid=$uid proto=$protocol " +
                    "src=$sourceAddress:$sourcePort dst=$destinationAddress:$destinationPort"
            )
            return toConnectionOwner(uid)
        }

        fun resolveFromConnectivityManager(): ConnectionOwner? {
            val cm = callbacks.getConnectivityManager() ?: return null
            return try {
                val uid = cm.getConnectionOwnerUid(
                    protocol,
                    InetSocketAddress(ownerSourceIp, sourcePort),
                    InetSocketAddress(ownerDestinationIp, destinationPort)
                )
                if (uid > 0) {
                    callbacks.incrementConnectionOwnerUidResolved()
                    callbacks.setConnectionOwnerLastUid(uid)
                    callbacks.setConnectionOwnerLastEvent(
                        "resolved uid=$uid proto=$protocol $sourceIp:$sourcePort->$destinationIp:$destinationPort"
                    )
                    toConnectionOwner(uid)
                } else {
                    traceConnectionOwner("owner_unresolved uid=$uid proto=$protocol")
                    callbacks.setConnectionOwnerLastEvent(
                        "unresolved uid=$uid proto=$protocol $sourceIp:$sourcePort->$destinationIp:$destinationPort"
                    )
                    null
                }
            } catch (e: SecurityException) {
                callbacks.incrementConnectionOwnerSecurityDenied()
                traceConnectionOwner("owner_security_denied proto=$protocol error=${e.javaClass.simpleName}")
                callbacks.setConnectionOwnerLastEvent(
                    "SecurityException findConnectionOwner proto=$protocol " +
                        "$sourceIp:$sourcePort->$destinationIp:$destinationPort"
                )
                if (!forceConnectionOwnerRouting && !callbacks.isConnectionOwnerPermissionDeniedLogged()) {
                    callbacks.setConnectionOwnerPermissionDeniedLogged(true)
                    Log.w(TAG, "findConnectionOwner permission denied; app routing may not work on this ROM", e)
                    com.kunk.singbox.repository.LogRepository.getInstance()
                        .addLog("WARN: findConnectionOwner permission denied; per-app routing disabled on this ROM")
                }
                null
            } catch (e: Exception) {
                callbacks.incrementConnectionOwnerOtherException()
                traceConnectionOwner("owner_exception proto=$protocol error=${e.javaClass.simpleName}")
                callbacks.setConnectionOwnerLastEvent("Exception ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }

        if (forceConnectionOwnerRouting) {
            val owner = if (callbacks.preferConnectivityOwnerRouting()) {
                // Root TProxy 的重定向 socket 可能在 ProcFS 中显示为 Root UID，优先查原始五元组。
                resolveFromConnectivityManager() ?: resolveFromProcFs("forced_fallback")
            } else {
                // VPN/TUN 下 ProcFS 保留应用真实 socket，优先使用它；系统接口常看不到 TUN 五元组。
                resolveFromProcFs("forced") ?: resolveFromConnectivityManager()
            }
            return owner ?: unknownConnectionOwner().also {
                traceConnectionOwner(
                    "owner_unknown_after_fallback src=$sourceAddress:$sourcePort " +
                        "dst=$destinationAddress:$destinationPort proto=$protocol"
                )
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return resolveFromProcFs("legacy_api_${Build.VERSION.SDK_INT}") ?: unknownConnectionOwner()
        }

        return resolveFromConnectivityManager()
            ?: resolveFromProcFs("connection_owner_fallback")
            ?: unknownConnectionOwner()
    }

    private fun traceConnectionOwner(message: String) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastOwnerTraceAtMs.get()
        if (now - previous >= 1_000L && lastOwnerTraceAtMs.compareAndSet(previous, now)) {
            Log.i(TAG, "[APP_ROUTE_TRACE] $message")
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        val listenerKey = Any()
        val monitorState = synchronized(defaultNetworkMonitorLock) {
            val generation = defaultNetworkMonitorGeneration.incrementAndGet()
            val previousListenerKey = defaultNetworkListenerKey
            defaultNetworkListenerKey = listenerKey
            currentInterfaceListener = listener
            networkSwitchManager.init(networkSwitchCallbacks)
            networkSwitchManager.markVpnStarted()
            connectivityManager = callbacks.getConnectivityManager()
            Triple(generation, previousListenerKey, connectivityManager)
        }
        val monitorGeneration = monitorState.first
        monitorState.second?.let(DefaultNetworkListener::stop)
        val manager = monitorState.third
        if (manager == null) {
            synchronized(defaultNetworkMonitorLock) {
                if (defaultNetworkListenerKey === listenerKey) {
                    defaultNetworkListenerKey = null
                    currentInterfaceListener = null
                    networkSwitchManager.cleanup()
                }
            }
            return
        }

        fun isCurrentMonitor(): Boolean {
            return defaultNetworkMonitorGeneration.get() == monitorGeneration &&
                defaultNetworkListenerKey === listenerKey
        }

        initializeDefaultInterface(manager, ::isCurrentMonitor)

        startListenerIfCurrent(
            isCurrent = ::isCurrentMonitor,
            start = {
                DefaultNetworkListener.start(
                    connectivityManager = manager,
                    key = listenerKey
                ) { network ->
                    if (network != null && isCurrentMonitor()) {
                        updateDefaultInterface(network)
                    }
                }
            },
            stop = {
                DefaultNetworkListener.stop(listenerKey)
            }
        )
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        val listenerKey = synchronized(defaultNetworkMonitorLock) {
            defaultNetworkMonitorGeneration.incrementAndGet()
            defaultNetworkListenerKey.also {
                defaultNetworkListenerKey = null
                currentInterfaceListener = null
                defaultInterfaceName = ""
                connectivityManager = null
                networkSwitchManager.cleanup()
            }
        }
        listenerKey?.let(DefaultNetworkListener::stop)
    }

    private fun initializeDefaultInterface(
        manager: ConnectivityManager,
        isCurrentMonitor: () -> Boolean
    ) {
        val initialNetwork = DefaultNetworkListener.selectBestPhysicalNetwork(manager)
        if (initialNetwork == null) {
            if (isCurrentMonitor()) {
                Log.w(TAG, "startDefaultInterfaceMonitor: no usable physical network found at startup")
            }
            return
        }

        val interfaceName = manager.getLinkProperties(initialNetwork)?.interfaceName.orEmpty()
        val interfaceIndex = runCatching { NetworkInterface.getByName(interfaceName)?.index ?: 0 }.getOrDefault(0)
        val capabilities = manager.getNetworkCapabilities(initialNetwork)
        val isExpensive = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        if (!isCurrentMonitor()) return

        callbacks.setLastKnownNetwork(initialNetwork)
        if (interfaceName.isNotEmpty()) {
            defaultInterfaceName = interfaceName
            currentInterfaceListener?.updateDefaultInterface(interfaceName, interfaceIndex, isExpensive, false)
        }
        Log.i(
            TAG,
            "startDefaultInterfaceMonitor: initialized with " +
                "network=$initialNetwork, interface=$defaultInterfaceName"
        )
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
                        type = networkInterfaceTypeForName(iface.name)

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
}
