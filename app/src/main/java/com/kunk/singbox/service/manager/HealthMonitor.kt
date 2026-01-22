package com.kunk.singbox.service.manager

import android.util.Log
import com.kunk.singbox.service.health.VpnHealthMonitor
import kotlinx.coroutines.CoroutineScope

/**
 * 健康监控管理器 (协调者)
 * 封装 VpnHealthMonitor，提供统一的健康检查接口
 * 使用 Result<T> 返回值模式
 */
class HealthMonitor(
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "HealthMonitor"
    }

    private var delegate: VpnHealthMonitor? = null
    private var contextProvider: (() -> HealthContext)? = null

    /**
     * 健康检查上下文
     */
    interface HealthContext {
        val isRunning: Boolean
        val isStopping: Boolean
        fun isBoxServiceValid(): Boolean
        fun isVpnInterfaceValid(): Boolean
        suspend fun wakeBoxService()
        fun restartVpnService(reason: String)
        fun addLog(message: String)
    }

    /**
     * 初始化管理器
     */
    fun init(contextProvider: () -> HealthContext): Result<Unit> {
        return runCatching {
            this.contextProvider = contextProvider

            // 创建适配器
            val adapter = object : VpnHealthMonitor.HealthCheckContext {
                override val isRunning: Boolean
                    get() = contextProvider().isRunning
                override val isStopping: Boolean
                    get() = contextProvider().isStopping

                override fun isBoxServiceValid(): Boolean =
                    contextProvider().isBoxServiceValid()

                override fun isVpnInterfaceValid(): Boolean =
                    contextProvider().isVpnInterfaceValid()

                override suspend fun wakeBoxService() =
                    contextProvider().wakeBoxService()

                override fun restartVpnService(reason: String) =
                    contextProvider().restartVpnService(reason)

                override fun addLog(message: String) =
                    contextProvider().addLog(message)
            }

            delegate = VpnHealthMonitor(adapter, serviceScope)
            Log.i(TAG, "HealthMonitor initialized")
        }
    }

    /**
     * 启动健康检查
     */
    fun start(): Result<Unit> {
        return runCatching {
            delegate?.start() ?: throw IllegalStateException("HealthMonitor not initialized")
            Log.i(TAG, "Health check started")
        }
    }

    /**
     * 停止健康检查
     */
    fun stop(): Result<Unit> {
        return runCatching {
            delegate?.stop()
            Log.i(TAG, "Health check stopped")
        }
    }

    /**
     * 执行屏幕唤醒健康检查
     */
    suspend fun performScreenOnCheck(): Result<Unit> {
        return runCatching {
            delegate?.performScreenOnHealthCheck()
                ?: throw IllegalStateException("HealthMonitor not initialized")
        }
    }

    /**
     * 执行应用前台健康检查
     */
    suspend fun performAppForegroundCheck(): Result<Unit> {
        return runCatching {
            delegate?.performAppForegroundHealthCheck()
                ?: throw IllegalStateException("HealthMonitor not initialized")
        }
    }

    /**
     * 执行轻量级健康检查
     */
    suspend fun performLightweightCheck(): Result<Unit> {
        return runCatching {
            delegate?.performLightweightHealthCheck()
                ?: throw IllegalStateException("HealthMonitor not initialized")
        }
    }

    /**
     * 处理健康检查失败
     */
    fun handleFailure(reason: String): Result<Unit> {
        return runCatching {
            delegate?.handleFailure(reason)
                ?: throw IllegalStateException("HealthMonitor not initialized")
        }
    }

    /**
     * 清理资源
     */
    fun cleanup(): Result<Unit> {
        return runCatching {
            delegate?.cleanup()
            delegate = null
            contextProvider = null
            Log.i(TAG, "HealthMonitor cleaned up")
        }
    }

    /**
     * 是否已初始化
     */
    fun isInitialized(): Boolean = delegate != null

    fun enterPowerSavingMode(): Result<Unit> {
        return runCatching {
            delegate?.enterPowerSavingMode()
                ?: throw IllegalStateException("HealthMonitor not initialized")
        }
    }

    fun exitPowerSavingMode(): Result<Unit> {
        return runCatching {
            delegate?.exitPowerSavingMode()
                ?: throw IllegalStateException("HealthMonitor not initialized")
        }
    }

    val isInPowerSavingMode: Boolean
        get() = delegate?.isInPowerSavingMode ?: false
}
