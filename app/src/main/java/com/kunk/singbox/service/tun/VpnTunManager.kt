package com.kunk.singbox.service.tun

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.IpVersionMode
import com.kunk.singbox.model.TunStack
import com.kunk.singbox.model.VpnAppMode
import com.kunk.singbox.model.VpnRouteMode
import com.kunk.singbox.repository.LogRepository
import io.nekohasekai.libbox.TunOptions
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 */
class VpnTunManager(
    private val context: Context,
    private val vpnService: VpnService
) {
    companion object {
        private const val TAG = "VpnTunManager"

        internal fun resolveVpnDnsServersForTest(
            settings: AppSettings?,
            dnsServerAddress: String? = null,
            tunPlan: VpnTunAddressPlan = VpnTunAddressPlanner.build(settings?.ipVersionMode ?: IpVersionMode.DUAL_STACK)
        ): List<String> {
            val explicitDns = dnsServerAddress?.trim().orEmpty()
            if (explicitDns.isNotEmpty() && !isTunLocalAddress(explicitDns, tunPlan)) {
                return listOf(explicitDns)
            }
            return tunPlan.defaultDnsServers
        }

        private fun isTunLocalAddress(address: String, tunPlan: VpnTunAddressPlan): Boolean {
            return tunPlan.addresses.any { it.first == address }
        }

        internal fun resolveVpnRoutesForTest(
            settings: AppSettings?,
            tunPlan: VpnTunAddressPlan = VpnTunAddressPlanner.build(settings?.ipVersionMode ?: IpVersionMode.DUAL_STACK)
        ): List<Pair<String, Int>> {
            return resolveVpnRoutes(settings, tunPlan)
        }

        private fun resolveVpnRoutes(settings: AppSettings?, tunPlan: VpnTunAddressPlan): List<Pair<String, Int>> {
            val routeMode = settings?.vpnRouteMode ?: VpnRouteMode.GLOBAL
            val customRoutes = settings?.vpnRouteIncludeCidrs.orEmpty()
                .split("\n", "\r", ",", ";", " ", "\t")
                .mapNotNull { parseCidrRoute(it) }

            val baseRoutes = if (routeMode == VpnRouteMode.CUSTOM && customRoutes.isNotEmpty()) {
                customRoutes + resolveDnsServerRoutes(tunPlan)
            } else {
                tunPlan.globalRoutes
            }
            return baseRoutes + resolveFakeIpRoutes(settings)
        }

        private fun parseCidrRoute(cidr: String): Pair<String, Int>? {
            val parts = cidr.trim().split("/")
            val ip = parts.getOrNull(0)?.trim().orEmpty()
            val prefix = parts.getOrNull(1)?.trim()?.toIntOrNull()
            return if (parts.size == 2 && ip.isNotEmpty() && prefix != null) ip to prefix else null
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

        internal fun resolvePerAppVpnPlanForTest(settings: AppSettings?, selfPackage: String): PerAppVpnPlan {
            return resolvePerAppVpnPlan(settings, selfPackage)
        }

        private fun resolvePerAppVpnPlan(settings: AppSettings?, selfPackage: String): PerAppVpnPlan {
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
    }

    @Volatile
    private var preallocatedBuilder: VpnService.Builder? = null

    val isConnecting = AtomicBoolean(false)

    // Avoid spamming logs if Builder is recreated multiple times.
    private val lastMtuLogAtMs = AtomicLong(0L)
    @Volatile private var lastLoggedMtu: Int = -1
    private val mtuLogDebounceMs: Long = 10_000L

    /**
     */
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

    /**
     */
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
    fun configureBuilder(
        builder: VpnService.Builder,
        options: TunOptions?,
        settings: AppSettings?
    ) {
        val effectiveMtu = resolveEffectiveMtu(options, settings)
        logEffectiveMtuIfNeeded(options, settings, effectiveMtu)
        val tunPlan = VpnTunAddressPlanner.build(settings?.ipVersionMode ?: IpVersionMode.DUAL_STACK)

        builder.setSession("KunBox VPN")
            .setMtu(effectiveMtu)

        tunPlan.addresses.forEach { (address, prefix) ->
            builder.addAddress(address, prefix)
        }

        configureRoutes(builder, settings, tunPlan)

        configureDns(builder, settings, options)

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
        val physicalCaps = cm?.allNetworks
            ?.asSequence()
            ?.mapNotNull { cm.getNetworkCapabilities(it) }
            ?.firstOrNull {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        val caps = physicalCaps ?: cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val networkType = when {
            caps == null -> "unknown"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }

        val msg = "INFO [VPN] Effective MTU=$effectiveMtu " +
            "(auto=$autoEnabled, configured=$configuredMtu) network=$networkType"
        Log.i(TAG, msg)
        runCatching { LogRepository.getInstance().addLog(msg) }
    }

    private fun resolveEffectiveMtu(options: TunOptions?, settings: AppSettings?): Int {
        val configuredMtu = if (options != null && options.mtu > 0) options.mtu else (settings?.tunMtu ?: 1500)
        if (settings?.tunMtuAuto != true) return configuredMtu

        val caps = getNetworkCapabilities() ?: return configuredMtu

        // Throughput-first for Wi-Fi/Ethernet; conservative for cellular.
        // QUIC-based proxies (Hysteria2/TUIC) + YouTube QUIC = double encapsulation,
        // requiring higher MTU to avoid fragmentation blackholes.
        val recommendedMtu = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1480
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1480
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1400
            else -> configuredMtu
        }

        // Auto MTU should never be more aggressive than user-configured MTU.
        return minOf(configuredMtu, recommendedMtu)
    }

    private fun getNetworkCapabilities(): NetworkCapabilities? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null

        val physicalCaps = cm.allNetworks
            .asSequence()
            .mapNotNull { cm.getNetworkCapabilities(it) }
            .firstOrNull {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        return physicalCaps ?: cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    }

    private fun configureRoutes(
        builder: VpnService.Builder,
        settings: AppSettings?,
        tunPlan: VpnTunAddressPlan
    ) {
        resolveVpnRoutes(settings, tunPlan).forEach { (route, prefix) ->
            addRoute(builder, route, prefix)
        }
    }

    private fun addRoute(builder: VpnService.Builder, route: String, prefix: Int): Boolean {
        return try {
            builder.addRoute(InetAddress.getByName(route), prefix)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun configureDns(
        builder: VpnService.Builder,
        settings: AppSettings?,
        options: TunOptions?
    ) {
        val dnsServerAddress = runCatching { options?.getDNSServerAddress()?.getValue() }.getOrNull()
        val dnsServers = resolveVpnDnsServersForTest(settings, dnsServerAddress)

        dnsServers.distinct().forEach { dns ->
            try {
                builder.addDnsServer(dns)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add DNS server: $dns", e)
            }
        }
    }

    private fun configurePerAppVpn(builder: VpnService.Builder, settings: AppSettings?) {
        val plan = resolvePerAppVpnPlan(settings, context.packageName)

        try {
            var addedAllowedCount = 0
            plan.allowedPackages.forEach { pkg ->
                try {
                    builder.addAllowedApplication(pkg)
                    addedAllowedCount++
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Allowed app not found: $pkg")
                }
            }

            if (settings?.vpnAppMode == VpnAppMode.ALLOWLIST && addedAllowedCount == 0) {
                Log.w(TAG, "No valid apps in allowlist, falling back to ALL mode")
                builder.addDisallowedApplication(context.packageName)
                return
            }

            plan.disallowedPackages.forEach { pkg ->
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Disallowed app not found: $pkg")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply per-app VPN settings", e)
        }
    }

    private fun configureSecuritySettings(builder: VpnService.Builder) {
        // Kill Switch: NOT calling allowBypass() means bypass disabled by default
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Log.i(TAG, "Kill switch enabled: NOT calling allowBypass()")
        }

        // Blocking mode: blocks network until VPN established
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                builder.setBlocking(true)
                Log.i(TAG, "Blocking mode enabled: setBlocking(true)")
            } catch (e: Exception) {
                Log.w(TAG, "setBlocking not supported on this device", e)
            }
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
        val alwaysOnPkg = runCatching {
            Settings.Secure.getString(context.contentResolver, "always_on_vpn_app")
        }.getOrNull() ?: runCatching {
            Settings.Global.getString(context.contentResolver, "always_on_vpn_app")
        }.getOrNull()

        val lockdownValueSecure = runCatching {
            Settings.Secure.getInt(context.contentResolver, "always_on_vpn_lockdown", 0)
        }.getOrDefault(0)
        val lockdownValueGlobal = runCatching {
            Settings.Global.getInt(context.contentResolver, "always_on_vpn_lockdown", 0)
        }.getOrDefault(0)
        val lockdown = lockdownValueSecure != 0 || lockdownValueGlobal != 0

        if (!alwaysOnPkg.isNullOrBlank() || lockdown) {
            Log.i(TAG, "Always-on VPN status: pkg=$alwaysOnPkg lockdown=$lockdown")
        }

        return Pair(alwaysOnPkg, lockdown)
    }

    fun isOtherVpnActive(connectivityManager: ConnectivityManager?): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && connectivityManager != null) {
            return runCatching {
                @Suppress("DEPRECATION")
                connectivityManager.allNetworks.any { network ->
                    val caps = connectivityManager.getNetworkCapabilities(network) ?: return@any false
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }
            }.getOrDefault(false)
        }
        return false
    }

    /**
     */
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

            try { vpnInterface?.close() } catch (_: Exception) {}
        }

        return null
    }

    /**
     */
    fun cleanup() {
        preallocatedBuilder = null
        isConnecting.set(false)
    }
}
