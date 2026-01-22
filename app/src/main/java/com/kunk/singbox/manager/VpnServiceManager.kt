package com.kunk.singbox.manager

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.service.SingBoxService

/**
 * VPN 服务管理器
 *
 * 统一管理 SingBoxService 和 ProxyOnlyService 的启停操作
 * 提供智能缓存机制,优化快捷方式/Widget/QS Tile 的响应速度
 *
 * 参考 v2rayNG 的 V2RayServiceManager 实现
 */
object VpnServiceManager {
    private const val TAG = "VpnServiceManager"

    // TUN 设置缓存,避免每次都读取 SharedPreferences
    @Volatile
    private var cachedTunEnabled: Boolean? = null

    @Volatile
    private var lastTunCheckTime: Long = 0L

    // 缓存有效期: 5 秒 (足够应对快速连续切换,又不会太久导致设置变更不生效)
    private const val CACHE_VALIDITY_MS = 5_000L

    /**
     * 判断 VPN 是否正在运行
     *
     * 使用 SharedPreferences 读取状态（与 VpnTileService.persistVpnState 保持一致）
     */
    fun isRunning(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(
            PREFS_VPN_STATE,
            Context.MODE_PRIVATE
        )
        val persistedActive = prefs.getBoolean(KEY_VPN_ACTIVE, false)
        val pending = prefs.getString(KEY_VPN_PENDING, "") ?: ""

        if (pending.isNotEmpty()) {
            return persistedActive || pending == "starting"
        }

        if (persistedActive) {
            return true
        }

        return SingBoxRemote.isRunning.value
    }

    private const val PREFS_VPN_STATE = "vpn_state"
    private const val KEY_VPN_ACTIVE = "vpn_active"
    private const val KEY_VPN_PENDING = "vpn_pending"

    /**
     * 判断 VPN 是否正在启动中
     */
    fun isStarting(): Boolean {
        return SingBoxRemote.isStarting.value
    }

    /**
     * 获取当前运行的服务类型
     *
     * @return "tun" | "proxy" | null
     */
    fun getActiveService(context: Context): String? {
        if (!isRunning(context)) return null
        // 通过 activeLabel 判断,如果包含特定标识则返回对应类型
        // 这里简化处理,实际可以根据服务状态更精确判断
        return if (isTunEnabled()) "tun" else "proxy"
    }

    /**
     * 切换 VPN 状态
     *
     * 如果正在运行则停止,否则启动
     * 这是快捷方式/Widget 的核心逻辑
     */
    fun toggleVpn(context: Context) {
        if (isRunning(context)) {
            stopVpn(context)
        } else {
            startVpn(context)
        }
    }

    /**
     * 启动 VPN
     *
     * 根据当前 TUN 设置自动选择启动 SingBoxService 或 ProxyOnlyService
     */
    fun startVpn(context: Context) {
        val tunEnabled = isTunEnabled(context)
        startVpn(context, tunEnabled)
    }

    /**
     * 启动 VPN (显式指定模式)
     *
     * @param tunMode true = TUN 模式, false = Proxy-Only 模式
     */
    fun startVpn(context: Context, tunMode: Boolean) {
        Log.d(TAG, "startVpn: tunMode=$tunMode")

        val serviceClass = if (tunMode) {
            SingBoxService::class.java
        } else {
            ProxyOnlyService::class.java
        }

        val intent = Intent(context, serviceClass).apply {
            action = if (tunMode) {
                SingBoxService.ACTION_START
            } else {
                ProxyOnlyService.ACTION_START
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN service", e)
        }
    }

    /**
     * 停止 VPN
     *
     * 同时停止 SingBoxService 和 ProxyOnlyService,确保完全停止
     */
    fun stopVpn(context: Context) {
        Log.d(TAG, "stopVpn")

        try {
            // 停止 TUN 服务
            val tunIntent = Intent(context, SingBoxService::class.java).apply {
                action = SingBoxService.ACTION_STOP
            }
            context.startService(tunIntent)

            // 停止 Proxy-Only 服务
            val proxyIntent = Intent(context, ProxyOnlyService::class.java).apply {
                action = ProxyOnlyService.ACTION_STOP
            }
            context.startService(proxyIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop VPN service", e)
        }
    }

    /**
     * 重启 VPN
     *
     * 保持当前模式,先停止再启动
     */
    fun restartVpn(context: Context) {
        Log.d(TAG, "restartVpn")

        val currentTunMode = isTunEnabled(context)
        stopVpn(context)

        // 延迟 500ms 后启动,确保服务完全停止
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startVpn(context, currentTunMode)
        }, 500)
    }

    /**
     * 获取当前 TUN 设置 (带智能缓存)
     *
     * 优先从缓存读取,缓存过期则从 SharedPreferences 读取并更新缓存
     */
    private fun isTunEnabled(context: Context? = null): Boolean {
        val now = System.currentTimeMillis()
        val cached = cachedTunEnabled

        // 缓存有效
        if (cached != null && (now - lastTunCheckTime) < CACHE_VALIDITY_MS) {
            return cached
        }

        // 缓存过期或未初始化,从 SharedPreferences 读取
        if (context != null) {
            val prefs = context.applicationContext.getSharedPreferences(
                "com.kunk.singbox_preferences",
                Context.MODE_PRIVATE
            )
            val tunEnabled = prefs.getBoolean("tun_enabled", true)

            cachedTunEnabled = tunEnabled
            lastTunCheckTime = now

            return tunEnabled
        }

        // 没有 Context 且缓存为空,返回默认值
        return cached ?: true
    }

    /**
     * 刷新 TUN 设置缓存
     *
     * 在设置页面修改 TUN 设置后调用,立即更新缓存
     */
    fun refreshTunSetting(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(
            "com.kunk.singbox_preferences",
            Context.MODE_PRIVATE
        )
        val tunEnabled = prefs.getBoolean("tun_enabled", true)

        cachedTunEnabled = tunEnabled
        lastTunCheckTime = System.currentTimeMillis()

        Log.d(TAG, "refreshTunSetting: tunEnabled=$tunEnabled")
    }

    /**
     * 获取当前配置信息 (调试用)
     */
    fun getCurrentConfig(context: Context): String {
        return buildString {
            append("isRunning: ${isRunning(context)}\n")
            append("isStarting: ${isStarting()}\n")
            append("activeService: ${getActiveService(context)}\n")
            append("cachedTunEnabled: $cachedTunEnabled\n")
            append("activeLabel: ${SingBoxRemote.activeLabel.value}\n")
        }
    }
}
