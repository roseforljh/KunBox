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
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.VpnAppMode
import com.kunk.singbox.model.VpnRouteMode
import com.kunk.singbox.repository.LogRepository
import io.nekohasekai.libbox.TunOptions
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VPN TUN 接口管理器
 * 负责 TUN 接口的配置、创建和生命周期管理
 */
class VpnTunManager(
    private val context: Context,
    private val vpnService: VpnService
) {
    companion object {
        private const val TAG = "VpnTunManager"
    }

    @Volatile
    private var preallocatedBuilder: VpnService.Builder? = null

    val isConnecting = AtomicBoolean(false)

    /**
     * 预分配 TUN Builder
     * 在收到 ACTION_START 时调用，减少 openTun 时的延迟
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
     * 获取预分配的 Builder（如果有）
     */
    fun consumePreallocatedBuilder(): VpnService.Builder? {
        return preallocatedBuilder?.also {
            preallocatedBuilder = null
            Log.d(TAG, "Using preallocated TUN builder")
        }
    }

    /**
     * 配置 TUN Builder
     * @param builder VpnService.Builder
     * @param options TunOptions from libbox
     * @param settings 应用设置
     */
    fun configureBuilder(
        builder: VpnService.Builder,
        options: TunOptions?,
        settings: AppSettings?
    ) {
        builder.setSession("KunBox VPN")
            .setMtu(if (options != null && options.mtu > 0) options.mtu else (settings?.tunMtu ?: 1500))

        // 添加地址
        builder.addAddress("172.19.0.1", 30)
        builder.addAddress("fd00::1", 126)

        // 添加路由
        configureRoutes(builder, settings)

        // 添加 DNS
        configureDns(builder, settings)

        // 分应用配置
        configurePerAppVpn(builder, settings)

        // 安全设置
        configureSecuritySettings(builder)

        // Android Q+ 设置
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            configureHttpProxy(builder, settings)
        }
    }

    private fun configureRoutes(builder: VpnService.Builder, settings: AppSettings?) {
        val routeMode = settings?.vpnRouteMode ?: VpnRouteMode.GLOBAL
        val cidrText = settings?.vpnRouteIncludeCidrs.orEmpty()
        val cidrs = cidrText
            .split("\n", "\r", ",", ";", " ", "\t")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val usedCustomRoutes = if (routeMode == VpnRouteMode.CUSTOM) {
            var okCount = 0
            cidrs.forEach { cidr ->
                if (addCidrRoute(builder, cidr)) okCount++
            }
            okCount > 0
        } else {
            false
        }

        if (!usedCustomRoutes) {
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
        }
    }

    private fun addCidrRoute(builder: VpnService.Builder, cidr: String): Boolean {
        val parts = cidr.split("/")
        if (parts.size != 2) return false
        val ip = parts[0].trim()
        val prefix = parts[1].trim().toIntOrNull() ?: return false
        return try {
            val addr = InetAddress.getByName(ip)
            builder.addRoute(addr, prefix)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun configureDns(builder: VpnService.Builder, settings: AppSettings?) {
        val dnsServers = mutableListOf<String>()
        if (settings != null) {
            if (isNumericAddress(settings.remoteDns)) dnsServers.add(settings.remoteDns)
            if (isNumericAddress(settings.localDns)) dnsServers.add(settings.localDns)
        }

        if (dnsServers.isEmpty()) {
            dnsServers.add("223.5.5.5")
            dnsServers.add("119.29.29.29")
            dnsServers.add("1.1.1.1")
        }

        dnsServers.distinct().forEach { dns ->
            try {
                builder.addDnsServer(dns)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add DNS server: $dns", e)
            }
        }
    }

    private fun configurePerAppVpn(builder: VpnService.Builder, settings: AppSettings?) {
        val appMode = settings?.vpnAppMode ?: VpnAppMode.ALL
        val allowPkgs = parsePackageList(settings?.vpnAllowlist.orEmpty())
        val blockPkgs = parsePackageList(settings?.vpnBlocklist.orEmpty())
        val selfPackage = context.packageName

        try {
            when (appMode) {
                VpnAppMode.ALL -> {
                    builder.addDisallowedApplication(selfPackage)
                }
                VpnAppMode.ALLOWLIST -> {
                    if (allowPkgs.isEmpty()) {
                        Log.w(TAG, "Allowlist is empty, falling back to ALL mode")
                        builder.addDisallowedApplication(selfPackage)
                    } else {
                        var addedCount = 0
                        allowPkgs.forEach { pkg ->
                            if (pkg == selfPackage) return@forEach
                            try {
                                builder.addAllowedApplication(pkg)
                                addedCount++
                            } catch (e: PackageManager.NameNotFoundException) {
                                Log.w(TAG, "Allowed app not found: $pkg")
                            }
                        }
                        if (addedCount == 0) {
                            Log.w(TAG, "No valid apps in allowlist, falling back to ALL mode")
                            builder.addDisallowedApplication(selfPackage)
                        }
                    }
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
            if (settings?.appendHttpProxy == true && settings.proxyPort > 0) {
                try {
                    builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", settings.proxyPort))
                    Log.i(TAG, "HTTP Proxy appended to VPN: 127.0.0.1:${settings.proxyPort}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set HTTP proxy for VPN", e)
                }
            }
        }
    }

    /**
     * 检查 Always-On VPN 状态
     * @return Pair<packageName, isLockdown>
     */
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

    /**
     * 检查是否有其他 VPN 活跃
     */
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
     * 使用重试建立 TUN 接口
     * @return ParcelFileDescriptor 或 null
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
     * 清理预分配的 Builder
     */
    fun cleanup() {
        preallocatedBuilder = null
        isConnecting.set(false)
    }

    private fun parsePackageList(raw: String): List<String> {
        return raw
            .split("\n", "\r", ",", ";", " ", "\t")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun isNumericAddress(address: String): Boolean {
        if (address.isBlank()) return false
        return try {
            val addr = InetAddress.getByName(address)
            addr.hostAddress == address
        } catch (_: Exception) {
            false
        }
    }
}
