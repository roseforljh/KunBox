package com.kunk.singbox.service.manager

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Network
import android.net.VpnService
import android.util.Log
import com.google.gson.Gson
import com.kunk.singbox.R
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.repository.RuleSetRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.service.notification.VpnNotificationManager
import com.kunk.singbox.utils.perf.PerfTracer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * VPN 启动管理器
 * 负责完整的 VPN 启动流程，包括：
 * - 前台通知
 * - 权限检查
 * - 并行初始化
 * - 配置加载和修补
 * - Libbox 启动
 */
class StartupManager(
    private val context: Context,
    private val vpnService: VpnService,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "StartupManager"
    }

    private val gson = Gson()

    /**
     * 启动回调接口
     */
    interface Callbacks {
        // 状态回调
        fun onStarting()
        fun onStarted(configContent: String)
        fun onFailed(error: String)
        fun onCancelled()

        // 通知管理
        fun createNotification(): Notification
        fun markForegroundStarted()

        // 生命周期管理
        fun registerScreenStateReceiver()
        fun startForeignVpnMonitor()
        fun stopForeignVpnMonitor()

        // 组件初始化
        fun initSelectorManager(configContent: String)
        fun startCommandServerAndClient(boxService: io.nekohasekai.libbox.BoxService)
        fun startRouteGroupAutoSelect(configContent: String)
        fun scheduleAsyncRuleSetUpdate()
        fun startHealthMonitor()
        fun scheduleKeepaliveWorker()
        fun startTrafficMonitor()

        // 状态管理
        fun updateTileState()
        fun setIsRunning(running: Boolean)
        fun setIsStarting(starting: Boolean)
        fun setLastError(error: String?)
        fun persistVpnState(isRunning: Boolean)
        fun persistVpnPending(pending: String)

        // 网络管理
        suspend fun waitForUsablePhysicalNetwork(timeoutMs: Long): Network?
        suspend fun ensureNetworkCallbackReady(timeoutMs: Long)
        fun setLastKnownNetwork(network: Network?)
        fun setNetworkCallbackReady(ready: Boolean)

        // 清理
        suspend fun waitForCleanupJob()
        fun stopSelf()
    }

    /**
     * 启动结果
     */
    sealed class StartResult {
        data class Success(val configContent: String, val durationMs: Long) : StartResult()
        data class Failed(val error: String, val exception: Exception? = null) : StartResult()
        data object Cancelled : StartResult()
        data object NeedPermission : StartResult()
    }

    /**
     * 执行完整的 VPN 启动流程
     */
    suspend fun startVpn(
        configPath: String,
        cleanCache: Boolean,
        coreManager: CoreManager,
        connectManager: ConnectManager,
        callbacks: Callbacks
    ): StartResult = withContext(Dispatchers.IO) {
        PerfTracer.begin(PerfTracer.Phases.VPN_STARTUP)

        try {
            // 等待前一个清理任务完成
            callbacks.waitForCleanupJob()

            callbacks.onStarting()

            // 1. 获取锁和注册监听器
            coreManager.acquireLocks()
            callbacks.registerScreenStateReceiver()

            // 2. 检查 VPN 权限
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                handlePermissionRequired(prepareIntent, callbacks)
                return@withContext StartResult.NeedPermission
            }

            callbacks.startForeignVpnMonitor()

            // 3. 并行初始化
            PerfTracer.begin(PerfTracer.Phases.PARALLEL_INIT)
            val (physicalNetwork, ruleSetReady, settings) = parallelInit(callbacks)
            PerfTracer.end(PerfTracer.Phases.PARALLEL_INIT)

            if (physicalNetwork == null) {
                throw IllegalStateException("No usable physical network before VPN start")
            }

            // 更新网络状态
            callbacks.setLastKnownNetwork(physicalNetwork)
            callbacks.setNetworkCallbackReady(true)

            // 设置 CoreManager 的当前设置 (用于 TUN 配置中的分应用代理等)
            coreManager.setCurrentSettings(settings)

            // 4. 读取和修补配置
            val configContent = loadAndPatchConfig(configPath, settings)

            // 5. 清理缓存（如果需要）
            if (cleanCache) {
                coreManager.cleanCacheDb()
            }

            // 6. 启动 Libbox
            when (val result = coreManager.startLibbox(configContent)) {
                is CoreManager.StartResult.Success -> {
                    Log.i(TAG, "Libbox started in ${result.durationMs}ms")
                }
                is CoreManager.StartResult.Failed -> {
                    throw Exception("Libbox start failed: ${result.error}", result.exception)
                }
                is CoreManager.StartResult.Cancelled -> {
                    return@withContext StartResult.Cancelled
                }
            }

            // 7. 初始化后续组件
            val boxService = coreManager.boxService
                ?: throw IllegalStateException("BoxService is null after successful start")

            callbacks.startCommandServerAndClient(boxService)
            callbacks.initSelectorManager(configContent)

            // 8. 标记运行状态
            callbacks.setIsRunning(true)
            callbacks.setLastError(null)
            callbacks.persistVpnState(true)
            callbacks.stopForeignVpnMonitor()

            // 9. 启动监控和辅助组件
            callbacks.startTrafficMonitor()
            callbacks.startHealthMonitor()
            callbacks.scheduleKeepaliveWorker()
            callbacks.startRouteGroupAutoSelect(configContent)
            callbacks.scheduleAsyncRuleSetUpdate()

            // 10. 更新 UI 状态
            callbacks.persistVpnPending("")
            callbacks.updateTileState()

            callbacks.onStarted(configContent)

            val totalMs = PerfTracer.end(PerfTracer.Phases.VPN_STARTUP)
            Log.i(TAG, "VPN startup completed in ${totalMs}ms")

            StartResult.Success(configContent, totalMs)
        } catch (e: CancellationException) {
            PerfTracer.end(PerfTracer.Phases.VPN_STARTUP)
            callbacks.onCancelled()
            StartResult.Cancelled
        } catch (e: Exception) {
            PerfTracer.end(PerfTracer.Phases.VPN_STARTUP)
            val error = parseStartError(e)
            callbacks.onFailed(error)
            StartResult.Failed(error, e)
        } finally {
            callbacks.setIsStarting(false)
        }
    }

    private suspend fun parallelInit(callbacks: Callbacks): Triple<Network?, Boolean, AppSettings> = coroutineScope {
        val networkDeferred = async {
            callbacks.ensureNetworkCallbackReady(1500L)
            callbacks.waitForUsablePhysicalNetwork(3000L)
        }

        val ruleSetDeferred = async {
            runCatching {
                RuleSetRepository.getInstance(context).ensureRuleSetsReady(
                    forceUpdate = false,
                    allowNetwork = false
                ) { }
            }.getOrDefault(false)
        }

        val settingsDeferred = async {
            SettingsRepository.getInstance(context).settings.first()
        }

        Triple(
            networkDeferred.await(),
            ruleSetDeferred.await(),
            settingsDeferred.await()
        )
    }

    private fun loadAndPatchConfig(configPath: String, settings: AppSettings): String {
        val configFile = File(configPath)
        if (!configFile.exists()) {
            throw IllegalStateException("Config file not found: $configPath")
        }

        var configContent = configFile.readText()
        val logLevel = if (settings.debugLoggingEnabled) "debug" else "info"

        try {
            val configObj = gson.fromJson(configContent, SingBoxConfig::class.java)

            val logConfig = configObj.log?.copy(level = logLevel)
                ?: com.kunk.singbox.model.LogConfig(level = logLevel, timestamp = true, output = "box.log")

            var newConfig = configObj.copy(log = logConfig)

            if (newConfig.inbounds != null) {
                val newInbounds = newConfig.inbounds.orEmpty().map { inbound ->
                    if (inbound.type == "tun") {
                        inbound.copy(autoRoute = settings.autoRoute)
                    } else {
                        inbound
                    }
                }
                newConfig = newConfig.copy(inbounds = newInbounds)
            }

            configContent = gson.toJson(newConfig)
            Log.i(TAG, "Patched config: auto_route=${settings.autoRoute}, log_level=$logLevel")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to patch config: ${e.message}")
        }

        return configContent
    }

    private fun handlePermissionRequired(prepareIntent: Intent, callbacks: Callbacks) {
        Log.w(TAG, "VPN permission required")
        callbacks.persistVpnState(false)
        callbacks.persistVpnPending("")

        runCatching {
            prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(prepareIntent)
        }.onFailure {
            runCatching {
                val manager = context.getSystemService(NotificationManager::class.java)
                val pi = PendingIntent.getActivity(
                    context, 2002,
                    prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val notification = Notification.Builder(context, VpnNotificationManager.CHANNEL_ID)
                    .setContentTitle("VPN Permission Required")
                    .setContentText("Tap to grant VPN permission")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
                manager.notify(VpnNotificationManager.NOTIFICATION_ID + 3, notification)
            }
        }
    }

    private fun parseStartError(e: Exception): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("VPN lockdown enabled by", ignoreCase = true) -> {
                val lockedBy = msg.substringAfter("VPN lockdown enabled by ").trim().ifBlank { "unknown" }
                "Start failed: system lockdown VPN enabled ($lockedBy)"
            }
            msg.contains("VPN interface establish failed", ignoreCase = true) ||
                msg.contains("configure tun interface", ignoreCase = true) ||
                msg.contains("fd=-1", ignoreCase = true) -> {
                "Start failed: could not establish VPN interface"
            }
            else -> "Failed to start VPN: ${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
