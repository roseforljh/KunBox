package com.kunk.singbox.service.tun

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.IpVersionMode
import com.kunk.singbox.model.TunStack
import com.kunk.singbox.model.VpnAppMode
import com.kunk.singbox.model.VpnRouteMode
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.utils.DefaultNetworkListener
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.TunOptions
import java.net.InetAddress
import java.net.Inet6Address
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class VpnTunManager(
    private val context: Context,
    private val vpnService: VpnService
) {
    companion object {
        private const val TAG = "VpnTunManager"
        private const val MIN_IPV4_TUN_MTU = 1200
        private const val MIN_IPV6_TUN_MTU = 1280
        private const val MAX_TUN_MTU = 1500

        internal fun resolveAutoMtu(
            configuredMtu: Int,
            physicalMtu: Int?,
            includesIpv6: Boolean
        ): Int {
            val minimumMtu = if (includesIpv6) MIN_IPV6_TUN_MTU else MIN_IPV4_TUN_MTU
            val configuredLimit = configuredMtu.coerceIn(minimumMtu, MAX_TUN_MTU)
            val linkLimit = physicalMtu
                ?.takeIf { it > 0 }
                ?.coerceIn(minimumMtu, MAX_TUN_MTU)
                ?: return configuredLimit
            return minOf(configuredLimit, linkLimit)
        }

        internal fun resolveVpnDnsServers(
            settings: AppSettings?,
            dnsServerAddress: String? = null,
            tunPlan: VpnTunAddressPlan = VpnTunAddressPlanner.build(settings?.ipVersionMode ?: IpVersionMode.DUAL_STACK)
        ): List<String> {
            val explicitDns = dnsServerAddress?.trim().orEmpty()
            if (explicitDns in tunPlan.defaultDnsServers) {
                return listOf(explicitDns)
            }
            if (explicitDns.isNotEmpty()) {
                Log.w(TAG, "Ignoring DNS server outside the active TUN prefix: $explicitDns")
            }
            return tunPlan.defaultDnsServers
        }

        internal fun addVpnRoutesFailClosed(
            routes: List<Pair<String, Int>>,
            requiredRoutes: Set<Pair<String, Int>>,
            addRoute: (String, Int) -> Boolean
        ) {
            routes.forEach { route ->
                if (!addRoute(route.first, route.second) && route in requiredRoutes) {
                    throw IllegalStateException("Failed to add required VPN route: ${route.first}/${route.second}")
                }
            }
        }

        internal fun addVpnDnsServersFailClosed(
            dnsServers: List<String>,
            internalDnsServers: Set<String>,
            addDnsServer: (String) -> Boolean
        ) {
            var addedInternalDnsCount = 0
            dnsServers.distinct().forEach { dns ->
                if (addDnsServer(dns) && dns in internalDnsServers) addedInternalDnsCount++
            }
            if (addedInternalDnsCount == 0) {
                throw IllegalStateException("Failed to add an internal VPN DNS server")
            }
        }

        internal fun validateKernelTunAddresses(
            kernelAddresses: List<Pair<String, Int>>
        ): List<Pair<String, Int>> {
            require(kernelAddresses.isNotEmpty()) { "libbox returned no TUN addresses" }
            kernelAddresses.forEach { (address, prefix) ->
                val parsedAddress = parseNumericAddress(address)
                require(parsedAddress != null) { "libbox returned invalid TUN address: $address/$prefix" }
                require(prefix in 0..(parsedAddress.address.size * Byte.SIZE_BITS)) {
                    "libbox returned invalid TUN prefix: $address/$prefix"
                }
            }
            return kernelAddresses
        }

        private fun readKernelTunAddresses(options: TunOptions): List<Pair<String, Int>> {
            return buildList {
                appendRoutePrefixes(this, options.getInet4Address())
                appendRoutePrefixes(this, options.getInet6Address())
            }
        }

        private fun appendRoutePrefixes(
            destination: MutableList<Pair<String, Int>>,
            iterator: RoutePrefixIterator?
        ) {
            if (iterator == null) return
            while (iterator.hasNext()) {
                val prefix = iterator.next() ?: continue
                destination.add(prefix.address() to prefix.prefix())
            }
        }

        internal fun resolveVpnRoutes(
            settings: AppSettings?,
            tunPlan: VpnTunAddressPlan = VpnTunAddressPlanner.build(settings?.ipVersionMode ?: IpVersionMode.DUAL_STACK)
        ): List<Pair<String, Int>> {
            val routeMode = settings?.vpnRouteMode ?: VpnRouteMode.GLOBAL
            val customRouteInputs = settings?.vpnRouteIncludeCidrs.orEmpty()
                .split("\n", "\r", ",", ";", " ", "\t")
                .map(String::trim)
                .filter(String::isNotEmpty)
            val customRoutes = customRouteInputs
                .mapNotNull { parseCidrRoute(it) }

            require(routeMode != VpnRouteMode.CUSTOM || customRouteInputs.isEmpty() || customRoutes.isNotEmpty()) {
                "Custom VPN routes contain no valid CIDR"
            }

            val baseRoutes = if (routeMode == VpnRouteMode.CUSTOM && customRoutes.isNotEmpty()) {
                customRoutes + resolveDnsServerRoutes(tunPlan)
            } else {
                tunPlan.globalRoutes
            }
            return baseRoutes + resolveFakeIpRoutes(settings)
        }

        internal fun resolveRequiredVpnRoutes(
            settings: AppSettings?,
            tunPlan: VpnTunAddressPlan,
            routes: List<Pair<String, Int>>
        ): Set<Pair<String, Int>> {
            return if (settings?.vpnRouteMode == VpnRouteMode.CUSTOM) {
                routes.toSet()
            } else {
                tunPlan.globalRoutes.toSet()
            }
        }

        private fun parseCidrRoute(cidr: String): Pair<String, Int>? {
            val parts = cidr.trim().split("/")
            val ip = parts.getOrNull(0)?.trim().orEmpty()
            val prefix = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (parts.size != 2 || prefix == null) return null
            val address = parseNumericAddress(ip) ?: return null
            return if (prefix in 0..(address.address.size * Byte.SIZE_BITS)) ip to prefix else null
        }

        private fun parseNumericAddress(address: String): InetAddress? {
            return if (address.contains(':')) parseNumericIpv6Address(address) else parseNumericIpv4Address(address)
        }

        private fun parseNumericIpv6Address(address: String): InetAddress? {
            val containsOnlyAddressCharacters = address.all { it in "0123456789abcdefABCDEF:." }
            return if (containsOnlyAddressCharacters) {
                runCatching { InetAddress.getByName(address) }.getOrNull()?.takeIf { it is Inet6Address }
            } else {
                null
            }
        }

        private fun parseNumericIpv4Address(address: String): InetAddress? {
            val octets = address.split('.')
            val values = octets
                .takeIf { it.size == 4 }
                ?.mapNotNull(::parseCanonicalIpv4Octet)
                ?.takeIf { it.size == 4 }
                ?: return null
            return InetAddress.getByAddress(values.map(Int::toByte).toByteArray())
        }

        private fun parseCanonicalIpv4Octet(octet: String): Int? {
            val value = octet.toIntOrNull()?.takeIf { it in 0..255 }
            return value?.takeIf { octet == it.toString() }
        }

        private fun resolveFakeIpRoutes(settings: AppSettings?): List<Pair<String, Int>> {
            if (settings?.fakeDnsEnabled != true) return emptyList()
            val fakeIpRoutes = resolveConfiguredFakeIpRoutes(settings.fakeIpRange)
            return when (settings.ipVersionMode) {
                IpVersionMode.IPV4_ONLY -> listOf(fakeIpRoutes.ipv4)
                IpVersionMode.IPV6_ONLY -> listOf(fakeIpRoutes.ipv6)
                IpVersionMode.DUAL_STACK, IpVersionMode.PREFER_IPV6 -> listOf(fakeIpRoutes.ipv4, fakeIpRoutes.ipv6)
            }
        }

        private data class FakeIpRoutePlan(
            val ipv4: Pair<String, Int>,
            val ipv6: Pair<String, Int>
        )

        private fun resolveConfiguredFakeIpRoutes(fakeIpRange: String?): FakeIpRoutePlan {
            val routes = fakeIpRange.orEmpty()
                .split(",")
                .mapNotNull { parseCidrRoute(it) }
            return FakeIpRoutePlan(
                ipv4 = routes.firstOrNull { !it.first.contains(":") } ?: ("198.18.0.0" to 15),
                ipv6 = routes.firstOrNull { it.first.contains(":") } ?: ("fc00::" to 18)
            )
        }

        private fun resolveDnsServerRoutes(tunPlan: VpnTunAddressPlan): List<Pair<String, Int>> {
            return tunPlan.defaultDnsServers.map { dns ->
                val prefix = if (dns.contains(":")) 128 else 32
                dns to prefix
            }
        }

        internal data class PerAppVpnPlan(
            val allowedPackages: List<String>,
            val disallowedPackages: List<String>
        )

        internal fun resolvePerAppVpnPlan(settings: AppSettings?, selfPackage: String): PerAppVpnPlan {
            val appMode = settings?.vpnAppMode ?: VpnAppMode.ALL
            val allowPkgs = parsePackageList(settings?.vpnAllowlist.orEmpty()).filterNot { it == selfPackage }
            val blockPkgs = parsePackageList(settings?.vpnBlocklist.orEmpty()).filterNot { it == selfPackage }
            return when (appMode) {
                VpnAppMode.ALL -> PerAppVpnPlan(
                    allowedPackages = emptyList(),
                    disallowedPackages = listOf(selfPackage)
                )
                VpnAppMode.ALLOWLIST -> PerAppVpnPlan(
                    allowedPackages = allowPkgs,
                    disallowedPackages = emptyList()
                )
                VpnAppMode.BLOCKLIST -> PerAppVpnPlan(
                    allowedPackages = emptyList(),
                    disallowedPackages = listOf(selfPackage) + blockPkgs
                )
            }
        }

        private fun parsePackageList(raw: String): List<String> {
            return raw
                .split("\n", "\r", ",", ";", " ", "\t")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        }

        internal fun shouldAppendHttpProxy(settings: AppSettings?): Boolean {
            return settings?.appendHttpProxy == true && settings.proxyPort > 0 && !settings.tunEnabled
        }

        internal fun hasUsablePerAppAllowlist(settings: AppSettings?, addedAllowedCount: Int): Boolean {
            return settings?.vpnAppMode != VpnAppMode.ALLOWLIST || addedAllowedCount > 0
        }

        internal data class AppliedPerAppVpnPlan(
            val mode: String,
            val requestedAllowedPackages: List<String>,
            val requestedDisallowedPackages: List<String>,
            val appliedAllowedPackages: List<String>,
            val appliedDisallowedPackages: List<String>,
            val skippedPackages: List<String>,
            val tunAddresses: List<String> = emptyList(),
            val routes: List<String> = emptyList(),
            val dnsServers: List<String> = emptyList(),
            val mtu: Int = 0,
            val defaultBrowserPackage: String? = null,
            val browserCoverage: String = "unknown"
        )

        internal fun addAllowedApplicationsFailClosed(
            packages: List<String>,
            addAllowedApplication: (String) -> Unit
        ): Int {
            var addedCount = 0
            packages.forEach { packageName ->
                try {
                    addAllowedApplication(packageName)
                    addedCount++
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Allowed app not found, skipping: $packageName", e)
                } catch (e: Exception) {
                    throw IllegalStateException("Failed to add allowed application: $packageName", e)
                }
            }
            return addedCount
        }
    }

    @Volatile
    private var preallocatedBuilder: VpnService.Builder? = null

    val isConnecting = AtomicBoolean(false)

    // Avoid spamming logs if Builder is recreated multiple times.
    private val lastMtuLogAtMs = AtomicLong(0L)
    @Volatile private var lastLoggedMtu: Int = -1
    private val mtuLogDebounceMs: Long = 10_000L

    @Volatile
    internal var appliedPerAppVpnPlan = AppliedPerAppVpnPlan(
        mode = VpnAppMode.ALL.name,
        requestedAllowedPackages = emptyList(),
        requestedDisallowedPackages = emptyList(),
        appliedAllowedPackages = emptyList(),
        appliedDisallowedPackages = emptyList(),
        skippedPackages = emptyList()
    )
        private set

    fun preallocateBuilder() {
        if (preallocatedBuilder != null) return
        try {
            preallocatedBuilder = vpnService.Builder()
                .setSession(context.packageName)
                .setMtu(9000)
            Log.d(TAG, "TUN builder preallocated")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to preallocate TUN builder", e)
            preallocatedBuilder = null
        }
    }

    fun consumePreallocatedBuilder(): VpnService.Builder? {
        return preallocatedBuilder?.also {
            preallocatedBuilder = null
            Log.d(TAG, "Using preallocated TUN builder")
        }
    }

    /**
     * @param builder VpnService.Builder
     * @param options TunOptions from libbox
     */
    @Suppress("CyclomaticComplexMethod")
    fun configureBuilder(
        builder: VpnService.Builder,
        options: TunOptions?,
        settings: AppSettings?
    ) {
        val effectiveMtu = resolveEffectiveMtu(options, settings)
        logEffectiveMtuIfNeeded(options, settings, effectiveMtu)
        val tunPlan = VpnTunAddressPlanner.build(settings?.ipVersionMode ?: IpVersionMode.DUAL_STACK)
        val tunAddresses = options?.let {
            validateKernelTunAddresses(readKernelTunAddresses(it))
        } ?: tunPlan.addresses

        builder.setSession("KunBox VPN")
            .setMtu(effectiveMtu)

        tunAddresses.forEach { (address, prefix) ->
            builder.addAddress(address, prefix)
        }

        configureRoutes(builder, settings, tunPlan)

        configureDns(builder, settings, options, tunPlan)

        configurePerAppVpn(builder, settings)

        val appModeName = (settings?.vpnAppMode ?: VpnAppMode.ALL).name
        val allowlist = settings?.vpnAllowlist
        val blocklist = settings?.vpnBlocklist
        Log.d(
            TAG,
            "Saving per-app settings: mode=$appModeName, " +
                "allowHash=${allowlist?.hashCode() ?: 0}, blockHash=${blocklist?.hashCode() ?: 0}"
        )
        VpnStateStore.savePerAppVpnSettings(
            appMode = appModeName,
            allowlist = allowlist,
            blocklist = blocklist
        )

        VpnStateStore.saveRoutingMode(
            mode = (settings?.routingMode ?: com.kunk.singbox.model.RoutingMode.RULE).name
        )
        VpnStateStore.saveTunSettings(
            tunStack = (settings?.tunStack ?: TunStack.MIXED).name,
            tunMtu = settings?.tunMtu ?: 1500,
            autoRoute = settings?.autoRoute ?: false,
            strictRoute = settings?.strictRoute ?: true,
            proxyPort = settings?.proxyPort ?: 2080
        )

        configureSecuritySettings(builder)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            configureHttpProxy(builder, settings)
        }
        val dnsAddress = runCatching { options?.getDNSServerAddress()?.getValue() }.getOrNull()
        val browserPackage = resolveDefaultBrowserPackage()
        appliedPerAppVpnPlan = appliedPerAppVpnPlan.copy(
            tunAddresses = tunAddresses.map { (address, prefix) -> "$address/$prefix" },
            routes = resolveVpnRoutes(settings, tunPlan).map { (address, prefix) -> "$address/$prefix" },
            dnsServers = resolveVpnDnsServers(settings, dnsAddress, tunPlan),
            mtu = effectiveMtu,
            defaultBrowserPackage = browserPackage,
            browserCoverage = resolveBrowserCoverage(browserPackage, appliedPerAppVpnPlan)
        )
        persistAppliedVpnPlan()
    }

    private fun logEffectiveMtuIfNeeded(options: TunOptions?, settings: AppSettings?, effectiveMtu: Int) {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastMtuLogAtMs.get()
        if (effectiveMtu == lastLoggedMtu && elapsed < mtuLogDebounceMs) return
        lastMtuLogAtMs.set(now)
        lastLoggedMtu = effectiveMtu

        val configuredMtu = if (options != null && options.mtu > 0) options.mtu else (settings?.tunMtu ?: 1500)
        val autoEnabled = settings?.tunMtuAuto == true

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val physicalNetwork = cm?.let { DefaultNetworkListener.selectBestPhysicalNetwork(it) }
        val caps = physicalNetwork?.let { cm.getNetworkCapabilities(it) }
        val physicalMtu = physicalNetwork?.let { readLinkMtu(cm, it) }
        val networkType = when {
            caps == null -> "unknown"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }

        val msg = "INFO [VPN] Effective MTU=$effectiveMtu " +
            "(auto=$autoEnabled, configured=$configuredMtu, physical=${physicalMtu ?: "unknown"}) " +
            "network=$networkType"
        Log.i(TAG, msg)
        runCatching { LogRepository.getInstance().addLog(msg) }
    }

    private fun resolveEffectiveMtu(options: TunOptions?, settings: AppSettings?): Int {
        options?.mtu?.takeIf { it > 0 }?.let { return it }
        val configuredMtu = settings?.tunMtu ?: 1500
        if (settings?.tunMtuAuto != true) return configuredMtu

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val physicalMtu = cm
            ?.let { DefaultNetworkListener.selectBestPhysicalNetwork(it) }
            ?.let { readLinkMtu(cm, it) }
        return resolveAutoMtu(
            configuredMtu = configuredMtu,
            physicalMtu = physicalMtu,
            includesIpv6 = settings.ipVersionMode != IpVersionMode.IPV4_ONLY
        )
    }

    private fun readLinkMtu(
        connectivityManager: ConnectivityManager,
        network: Network
    ): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return connectivityManager.getLinkProperties(network)?.mtu?.takeIf { it > 0 }
    }

    private fun configureRoutes(
        builder: VpnService.Builder,
        settings: AppSettings?,
        tunPlan: VpnTunAddressPlan
    ) {
        val routes = resolveVpnRoutes(settings, tunPlan)
        addVpnRoutesFailClosed(
            routes = routes,
            requiredRoutes = resolveRequiredVpnRoutes(settings, tunPlan, routes)
        ) { route, prefix ->
            addRoute(builder, route, prefix)
        }
    }

    private fun addRoute(builder: VpnService.Builder, route: String, prefix: Int): Boolean {
        val address = parseNumericAddress(route)
        if (address == null || prefix !in 0..(address.address.size * Byte.SIZE_BITS)) {
            Log.w(TAG, "Ignoring invalid VPN route: $route/$prefix")
            return false
        }
        return try {
            builder.addRoute(address, prefix)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add VPN route: $route/$prefix", e)
            false
        }
    }

    private fun configureDns(
        builder: VpnService.Builder,
        settings: AppSettings?,
        options: TunOptions?,
        tunPlan: VpnTunAddressPlan
    ) {
        val dnsServerAddress = runCatching { options?.getDNSServerAddress()?.getValue() }.getOrNull()
        val dnsServers = resolveVpnDnsServers(settings, dnsServerAddress, tunPlan)

        addVpnDnsServersFailClosed(
            dnsServers = dnsServers,
            internalDnsServers = tunPlan.defaultDnsServers.toSet()
        ) { dns ->
            try {
                builder.addDnsServer(dns)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add DNS server: $dns", e)
                false
            }
        }
    }

    private fun configurePerAppVpn(builder: VpnService.Builder, settings: AppSettings?) {
        val plan = resolvePerAppVpnPlan(settings, context.packageName)
        val appliedAllowed = mutableListOf<String>()
        val appliedDisallowed = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        val addedAllowedCount = try {
            addAllowedApplicationsFailClosed(plan.allowedPackages) { packageName ->
                builder.addAllowedApplication(packageName)
                appliedAllowed += packageName
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Failed to apply VPN allowlist", e)
            throw e
        }
        if (!hasUsablePerAppAllowlist(settings, addedAllowedCount)) {
            throw IllegalStateException("VPN allowlist contains no installed applications")
        }

        plan.disallowedPackages.forEach { pkg ->
            try {
                builder.addDisallowedApplication(pkg)
                appliedDisallowed += pkg
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Disallowed app not found: $pkg")
                skipped += pkg
            } catch (e: Exception) {
                Log.w(TAG, "Failed to disallow app: $pkg", e)
                skipped += pkg
            }
        }
        skipped += plan.allowedPackages.filterNot(appliedAllowed::contains)
        appliedPerAppVpnPlan = AppliedPerAppVpnPlan(
            mode = (settings?.vpnAppMode ?: VpnAppMode.ALL).name,
            requestedAllowedPackages = plan.allowedPackages,
            requestedDisallowedPackages = plan.disallowedPackages,
            appliedAllowedPackages = appliedAllowed,
            appliedDisallowedPackages = appliedDisallowed,
            skippedPackages = skipped.distinct()
        )
    }

    private fun resolveDefaultBrowserPackage(): String? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        return context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
            ?.takeUnless { it == "android" }
    }

    private fun resolveBrowserCoverage(browserPackage: String?, plan: AppliedPerAppVpnPlan): String {
        browserPackage ?: return "unknown"
        val covered = when (plan.mode) {
            VpnAppMode.ALL.name -> browserPackage !in plan.appliedDisallowedPackages
            VpnAppMode.ALLOWLIST.name -> browserPackage in plan.appliedAllowedPackages
            VpnAppMode.BLOCKLIST.name -> browserPackage !in plan.appliedDisallowedPackages
            else -> return "unknown"
        }
        return if (covered) "covered" else "excluded"
    }

    private fun persistAppliedVpnPlan() {
        runCatching {
            val file = java.io.File(context.filesDir, "diagnostics/per_app_vpn_plan.json")
            check(file.parentFile?.let { it.exists() || it.mkdirs() } == true)
            file.writeText(com.google.gson.Gson().toJson(appliedPerAppVpnPlan), Charsets.UTF_8)
        }.onFailure { error ->
            Log.w(TAG, "Failed to persist applied per-app VPN plan", error)
        }
    }

    private fun configureSecuritySettings(builder: VpnService.Builder) {
        // 不调用 allowBypass()，避免应用主动绕过当前 VPN。
        Log.i(TAG, "VPN bypass disabled")

        // setBlocking 控制 TUN 文件描述符的读写模式，不代表系统 Always-on lockdown。
        try {
            builder.setBlocking(true)
            Log.i(TAG, "TUN file descriptor configured for blocking I/O")
        } catch (e: Exception) {
            Log.w(TAG, "setBlocking not supported on this device", e)
        }
    }

    private fun configureHttpProxy(builder: VpnService.Builder, settings: AppSettings?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val currentSettings = settings ?: return
            if (currentSettings.appendHttpProxy && currentSettings.tunEnabled) {
                Log.w(TAG, "HTTP proxy not appended in TUN VPN mode")
            }
            if (shouldAppendHttpProxy(currentSettings)) {
                try {
                    builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", currentSettings.proxyPort))
                    Log.i(TAG, "HTTP Proxy appended to VPN: 127.0.0.1:${currentSettings.proxyPort}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set HTTP proxy for VPN", e)
                }
            }
        }
    }

    fun checkAlwaysOnVpn(): Pair<String?, Boolean> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null to false
        val alwaysOn = runCatching { vpnService.isAlwaysOn }.getOrNull() ?: return null to false
        val lockdown = runCatching { vpnService.isLockdownEnabled }.getOrDefault(false)
        return (context.packageName.takeIf { alwaysOn }) to lockdown
    }

    fun isOtherVpnActive(connectivityManager: ConnectivityManager?): Boolean {
        connectivityManager ?: return false
        return runCatching {
            @Suppress("DEPRECATION")
            connectivityManager.allNetworks.any { network ->
                val caps = connectivityManager.getNetworkCapabilities(network) ?: return@any false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        }.getOrDefault(false)
    }

    fun establishWithRetry(
        builder: VpnService.Builder,
        isStopping: () -> Boolean
    ): ParcelFileDescriptor? {
        val backoffMs = longArrayOf(0L, 250L, 250L, 500L, 500L, 1000L, 1000L, 2000L, 2000L, 2000L)

        for (sleepMs in backoffMs) {
            if (isStopping()) {
                return null
            }
            if (sleepMs > 0) {
                SystemClock.sleep(sleepMs)
            }

            val vpnInterface = builder.establish()
            val fd = vpnInterface?.fd ?: -1
            if (vpnInterface != null && fd >= 0) {
                return vpnInterface
            }

            try {
                vpnInterface?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close invalid VPN interface", e)
            }
        }

        return null
    }

    fun cleanup() {
        preallocatedBuilder = null
        isConnecting.set(false)
    }
}
