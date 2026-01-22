package com.kunk.singbox.service.health

import android.util.Log
import kotlinx.coroutines.*

/**
 * VPN 健康检查监控器
 * 负责周期性健康检查、自适应间隔调整和故障恢复
 */
class VpnHealthMonitor(
    private val context: HealthCheckContext,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "VpnHealthMonitor"

        private const val DEFAULT_INTERVAL_MS = 15_000L
        private const val MIN_INTERVAL_MS = 5_000L
        private const val MAX_INTERVAL_MS = 60_000L
        private const val POWER_SAVING_INTERVAL_MS = 120_000L
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val HEALTHY_COUNT_THRESHOLD = 5
    }

    /**
     * 健康检查上下文接口
     * 由 SingBoxService 实现，提供检查所需的状态访问
     */
    interface HealthCheckContext {
        val isRunning: Boolean
        val isStopping: Boolean
        fun isBoxServiceValid(): Boolean
        fun isVpnInterfaceValid(): Boolean
        suspend fun wakeBoxService()
        fun restartVpnService(reason: String)
        fun addLog(message: String)
    }

    private var periodicHealthCheckJob: Job? = null

    @Volatile
    private var healthCheckIntervalMs: Long = DEFAULT_INTERVAL_MS

    @Volatile
    private var consecutiveHealthyChecks: Int = 0

    @Volatile
    private var consecutiveHealthCheckFailures: Int = 0

    @Volatile
    private var isPowerSavingMode: Boolean = false

    /**
     * 启动周期性健康检查
     * 定期检查 boxService 是否仍在正常运行，防止 native 崩溃导致僵尸状态
     */
    fun start() {
        stop()
        resetCounters()
        healthCheckIntervalMs = if (isPowerSavingMode) POWER_SAVING_INTERVAL_MS else DEFAULT_INTERVAL_MS

        periodicHealthCheckJob = serviceScope.launch {
            while (isActive && context.isRunning) {
                val intervalToUse = if (isPowerSavingMode) POWER_SAVING_INTERVAL_MS else healthCheckIntervalMs
                delay(intervalToUse)

                if (!context.isRunning || context.isStopping) {
                    break
                }

                try {
                    // 检查 1: boxService 对象是否仍然存在
                    if (!context.isBoxServiceValid()) {
                        Log.e(TAG, "Health check failed: boxService is null but isRunning=true")
                        handleFailure("boxService became null")
                        continue
                    }

                    // 检查 2: 验证 VPN 接口仍然有效
                    if (!context.isVpnInterfaceValid()) {
                        Log.e(TAG, "Health check failed: vpnInterface is null but isRunning=true")
                        handleFailure("vpnInterface became null")
                        continue
                    }

                    // 检查 3: 尝试调用 boxService 方法验证其响应性
                    withContext(Dispatchers.IO) {
                        try {
                            context.wakeBoxService()

                            if (consecutiveHealthCheckFailures > 0) {
                                Log.i(TAG, "Health check recovered, failures reset to 0")
                                consecutiveHealthCheckFailures = 0
                            }
                            onSuccess()
                        } catch (e: Exception) {
                            Log.e(TAG, "Health check failed: boxService method call threw exception", e)
                            handleFailure("boxService exception: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Periodic health check encountered exception", e)
                    handleFailure("health check exception: ${e.message}")
                }
            }
            Log.i(TAG, "Periodic health check stopped (isRunning=${context.isRunning})")
        }
    }

    /**
     * 停止周期性健康检查
     */
    fun stop() {
        periodicHealthCheckJob?.cancel()
        periodicHealthCheckJob = null
    }

    /**
     * 重置计数器
     */
    fun resetCounters() {
        consecutiveHealthCheckFailures = 0
        consecutiveHealthyChecks = 0
    }

    /**
     * 执行屏幕唤醒健康检查
     * 2025-fix-v4: NekoBox 风格 - 只调用 wake()，不做网络重置
     */
    suspend fun performScreenOnHealthCheck() {
        if (!context.isRunning) return

        try {
            Log.i(TAG, "[ScreenOn] Performing health check...")

            if (!context.isVpnInterfaceValid()) {
                Log.e(TAG, "[ScreenOn] VPN interface invalid, triggering recovery")
                handleFailure("VPN interface invalid after screen on")
                return
            }

            if (!context.isBoxServiceValid()) {
                Log.e(TAG, "[ScreenOn] boxService is null")
                handleFailure("boxService is null after screen on")
                return
            }

            withContext(Dispatchers.IO) {
                try {
                    context.wakeBoxService()
                    Log.i(TAG, "[ScreenOn] Called boxService.wake() - no network reset")
                } catch (e: Exception) {
                    Log.w(TAG, "[ScreenOn] wake() failed: ${e.message}")
                }
            }

            Log.i(TAG, "[ScreenOn] Health check passed (NekoBox-style)")
            consecutiveHealthCheckFailures = 0
        } catch (e: Exception) {
            Log.e(TAG, "[ScreenOn] Health check failed", e)
            handleFailure("Screen-on check exception: ${e.message}")
        }
    }

    /**
     * 应用返回前台时的健康检查
     * 2025-fix-v4: NekoBox 风格 - 只调用 wake()，不做网络重置
     */
    suspend fun performAppForegroundHealthCheck() {
        if (!context.isRunning) return

        try {
            Log.i(TAG, "[AppForeground] Performing health check...")

            if (!context.isVpnInterfaceValid()) {
                Log.e(TAG, "[AppForeground] VPN interface invalid, triggering recovery")
                handleFailure("VPN interface invalid after app foreground")
                return
            }

            if (!context.isBoxServiceValid()) {
                Log.e(TAG, "[AppForeground] boxService is null")
                handleFailure("boxService is null after app foreground")
                return
            }

            withContext(Dispatchers.IO) {
                try {
                    context.wakeBoxService()
                    Log.i(TAG, "[AppForeground] Called wake() - no connection reset")
                } catch (e: Exception) {
                    Log.w(TAG, "[AppForeground] wake() failed: ${e.message}")
                }
            }

            Log.i(TAG, "[AppForeground] Health check passed (NekoBox-style)")
            consecutiveHealthCheckFailures = 0
        } catch (e: Exception) {
            Log.e(TAG, "[AppForeground] Health check failed", e)
            handleFailure("App foreground check exception: ${e.message}")
        }
    }

    /**
     * 轻量级健康检查
     * 用于网络恢复等场景，只做基本验证而不触发完整的重启流程
     */
    suspend fun performLightweightHealthCheck() {
        if (!context.isRunning) return

        try {
            Log.i(TAG, "[Lightweight Check] Performing health check...")

            val vpnInterfaceValid = context.isVpnInterfaceValid()
            val boxServiceValid = context.isBoxServiceValid()

            if (!vpnInterfaceValid || !boxServiceValid) {
                Log.w(TAG, "[Lightweight Check] Issues found (vpnInterface=$vpnInterfaceValid, boxService=$boxServiceValid)")
                return
            }

            Log.i(TAG, "[Lightweight Check] Health check passed")
        } catch (e: Exception) {
            Log.w(TAG, "Lightweight health check failed", e)
        }
    }

    /**
     * 健康检查成功时调整间隔
     * 连续健康 5 次后增加间隔 (x1.5)，最大 60 秒
     */
    private fun onSuccess() {
        consecutiveHealthyChecks++
        if (consecutiveHealthyChecks >= HEALTHY_COUNT_THRESHOLD) {
            val newInterval = (healthCheckIntervalMs * 1.5).toLong()
                .coerceAtMost(MAX_INTERVAL_MS)
            if (newInterval != healthCheckIntervalMs) {
                Log.d(TAG, "Health check interval increased: ${healthCheckIntervalMs}ms -> ${newInterval}ms")
                healthCheckIntervalMs = newInterval
            }
            consecutiveHealthyChecks = 0
        }
    }

    /**
     * 处理健康检查失败
     * 失败时缩短间隔 (x0.5)，最小 5 秒
     */
    fun handleFailure(reason: String) {
        consecutiveHealthCheckFailures++
        consecutiveHealthyChecks = 0
        Log.w(TAG, "Health check failure #$consecutiveHealthCheckFailures: $reason")

        // 自适应间隔: 失败时缩短间隔
        val newInterval = (healthCheckIntervalMs * 0.5).toLong()
            .coerceAtLeast(MIN_INTERVAL_MS)
        if (newInterval != healthCheckIntervalMs) {
            Log.d(TAG, "Health check interval decreased: ${healthCheckIntervalMs}ms -> ${newInterval}ms")
            healthCheckIntervalMs = newInterval
        }

        if (consecutiveHealthCheckFailures >= MAX_CONSECUTIVE_FAILURES) {
            Log.e(TAG, "Max consecutive health check failures reached, restarting VPN service")
            context.addLog(
                "ERROR: VPN service became unresponsive ($reason), automatically restarting..."
            )

            context.restartVpnService("Health check failures: $reason")
        }
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        stop()
        resetCounters()
        healthCheckIntervalMs = DEFAULT_INTERVAL_MS
        isPowerSavingMode = false
    }

    fun enterPowerSavingMode() {
        if (isPowerSavingMode) return
        isPowerSavingMode = true
        healthCheckIntervalMs = POWER_SAVING_INTERVAL_MS
        Log.i(TAG, "Entered power saving mode (interval=${POWER_SAVING_INTERVAL_MS / 1000}s)")
    }

    fun exitPowerSavingMode() {
        if (!isPowerSavingMode) return
        isPowerSavingMode = false
        healthCheckIntervalMs = DEFAULT_INTERVAL_MS
        Log.i(TAG, "Exited power saving mode (interval=${DEFAULT_INTERVAL_MS / 1000}s)")
    }

    val isInPowerSavingMode: Boolean
        get() = isPowerSavingMode
}
