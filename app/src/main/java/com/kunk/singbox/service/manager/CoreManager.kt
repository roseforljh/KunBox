package com.kunk.singbox.service.manager

import android.content.Context
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.util.Log
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.core.SelectorManager
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.service.tun.VpnTunManager
import com.kunk.singbox.utils.perf.PerfTracer
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.TunOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * 核心管理器 (重构版)
 * 负责完整的 VPN 生命周期管理
 * 使用 Result<T> 返回值模式
 */
class CoreManager(
    private val context: Context,
    private val vpnService: VpnService,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "CoreManager"
    }

    private val tunManager = VpnTunManager(context, vpnService)
    private val settingsRepository by lazy { SettingsRepository.getInstance(context) }

    // ===== 核心状态 =====
    @Volatile var boxService: BoxService? = null
        private set

    @Volatile var vpnInterface: ParcelFileDescriptor? = null
        private set

    @Volatile var currentSettings: AppSettings? = null
        private set

    @Volatile var isStarting = false
        private set

    @Volatile var isStopping = false
        private set

    @Volatile var currentConfigContent: String? = null
        private set

    // ===== Command Server/Client =====
    var commandServer: CommandServer? = null
        private set
    var commandClient: CommandClient? = null
        private set

    // ===== Locks =====
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // 回调接口
    private var platformInterface: PlatformInterface? = null

    /**
     * 启动结果
     */
    sealed class StartResult {
        data class Success(val durationMs: Long, val configContent: String) : StartResult()
        data class Failed(val error: String, val exception: Exception? = null) : StartResult()
        object Cancelled : StartResult()
    }

    /**
     * 停止结果
     */
    sealed class StopResult {
        object Success : StopResult()
        data class Failed(val error: String) : StopResult()
    }

    /**
     * 初始化管理器
     */
    fun init(platformInterface: PlatformInterface): Result<Unit> {
        return runCatching {
            this.platformInterface = platformInterface
            Log.i(TAG, "CoreManager initialized")
        }
    }

    /**
     * 预分配 TUN Builder
     */
    fun preallocateTunBuilder(): Result<Unit> {
        return runCatching {
            tunManager.preallocateBuilder()
            Log.d(TAG, "TUN builder preallocated")
        }
    }

    /**
     * 加载设置
     */
    suspend fun loadSettings(): Result<AppSettings> {
        return runCatching {
            PerfTracer.begin(PerfTracer.Phases.SETTINGS_LOAD)
            val settings = settingsRepository.settings.first()
            currentSettings = settings
            PerfTracer.end(PerfTracer.Phases.SETTINGS_LOAD)
            settings
        }
    }

    /**
     * 设置当前设置 (用于外部已加载的设置)
     */
    fun setCurrentSettings(settings: AppSettings) {
        currentSettings = settings
    }

    /**
     * 获取 WakeLock 和 WifiLock
     */
    fun acquireLocks(): Result<Unit> {
        return runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KunBox:VpnService")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(24 * 60 * 60 * 1000L)

            val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "KunBox:VpnService")
            wifiLock?.setReferenceCounted(false)
            wifiLock?.acquire()
            Log.i(TAG, "WakeLock and WifiLock acquired")
        }
    }

    /**
     * 释放 WakeLock 和 WifiLock
     */
    fun releaseLocks(): Result<Unit> {
        return runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
            if (wifiLock?.isHeld == true) wifiLock?.release()
            wifiLock = null
            Log.i(TAG, "WakeLock and WifiLock released")
        }
    }

    /**
     * 清理 cache.db (跨配置切换)
     */
    fun cleanCacheDb(): Result<Boolean> {
        return runCatching {
            val cacheDir = File(context.filesDir, "singbox_data")
            val cacheDb = File(cacheDir, "cache.db")
            if (cacheDb.exists()) {
                val deleted = cacheDb.delete()
                Log.i(TAG, "Deleted cache.db: $deleted")
                deleted
            } else {
                false
            }
        }
    }

    /**
     * 启动 Libbox 服务
     */
    suspend fun startLibbox(configContent: String): StartResult {
        if (isStarting) {
            return StartResult.Failed("Already starting")
        }

        isStarting = true
        PerfTracer.begin(PerfTracer.Phases.LIBBOX_START)

        return try {
            val pInterface = platformInterface
                ?: throw IllegalStateException("PlatformInterface not initialized")

            SingBoxCore.ensureLibboxSetup(context)

            // Register platform interface for hot reload before creating service
            runCatching { Libbox.setReloadablePlatformInterface(pInterface) }

            val service = withContext(Dispatchers.IO) {
                Libbox.newService(configContent, pInterface)
            }
            service.start()
            boxService = service
            currentConfigContent = configContent

            // Register BoxService for hot reload after start
            runCatching { Libbox.setReloadableBoxService(service) }

            // 初始化 BoxWrapperManager
            if (BoxWrapperManager.init(service)) {
                Log.i(TAG, "BoxWrapperManager initialized")
            }

            val durationMs = PerfTracer.end(PerfTracer.Phases.LIBBOX_START)
            Log.i(TAG, "Libbox started in ${durationMs}ms")

            StartResult.Success(durationMs, configContent)
        } catch (e: CancellationException) {
            PerfTracer.end(PerfTracer.Phases.LIBBOX_START)
            Log.i(TAG, "Libbox start cancelled")
            StartResult.Cancelled
        } catch (e: Exception) {
            PerfTracer.end(PerfTracer.Phases.LIBBOX_START)
            Log.e(TAG, "Libbox start failed: ${e.message}", e)
            StartResult.Failed(e.message ?: "Unknown error", e)
        } finally {
            isStarting = false
        }
    }

    /**
     * 停止 BoxService (保留 TUN 用于跨配置切换)
     */
    suspend fun stopBoxService(): Result<Unit> {
        return runCatching {
            withContext(Dispatchers.IO) {
                // Clear hot reload state
                runCatching { Libbox.clearReloadableService() }

                // 释放 BoxWrapperManager
                BoxWrapperManager.release()

                // 清除 SelectorManager 状态
                SelectorManager.clear()

                boxService?.let { service ->
                    runCatching { service.close() }
                    boxService = null
                }
                currentConfigContent = null
                Log.i(TAG, "BoxService stopped")
                Unit
            }
        }
    }

    /**
     * 完全停止 VPN (关闭 TUN)
     */
    suspend fun stopFully(): Result<Unit> {
        if (isStopping) {
            return Result.failure(IllegalStateException("Already stopping"))
        }

        isStopping = true

        return runCatching {
            withContext(Dispatchers.IO) {
                // 1. 停止 BoxService
                stopBoxService()

                // 2. 关闭 TUN 接口
                vpnInterface?.let { pfd ->
                    runCatching { pfd.close() }
                    vpnInterface = null
                }

                // 3. 清理 TUN 管理器
                tunManager.cleanup()

                // 4. 释放锁
                releaseLocks()

                currentSettings = null
                Log.i(TAG, "VPN fully stopped")
                Unit
            }
        }.also {
            isStopping = false
        }
    }

    /**
     * 停止 (兼容旧 API)
     */
    suspend fun stop(): Result<Unit> = stopFully()

    /**
     * 打开 TUN 接口
     */
    fun openTun(
        options: TunOptions?,
        underlyingNetwork: Network? = null,
        reuseExisting: Boolean = true
    ): Result<Int> {
        if (options == null) {
            return Result.failure(IllegalArgumentException("TunOptions cannot be null"))
        }

        return runCatching {
            // 1. 尝试复用现有 TUN 接口
            if (reuseExisting) {
                vpnInterface?.let { existing ->
                    val existingFd = existing.fd
                    if (existingFd >= 0) {
                        Log.i(TAG, "Reusing existing TUN interface (fd=$existingFd)")
                        return@runCatching existingFd
                    }
                    Log.w(TAG, "Existing TUN interface has invalid fd, recreating")
                    runCatching { existing.close() }
                    vpnInterface = null
                }
            }

            // 2. 创建新 TUN 接口
            PerfTracer.begin(PerfTracer.Phases.TUN_CREATE)

            val builder = tunManager.consumePreallocatedBuilder()
                ?: vpnService.Builder()

            tunManager.configureBuilder(builder, options, currentSettings)

            // 3. 建立 TUN 接口 (带重试)
            val pfd = tunManager.establishWithRetry(builder) { isStopping }
                ?: throw IllegalStateException("Failed to establish TUN interface")

            vpnInterface = pfd
            val fd = pfd.fd

            // 4. 设置底层网络
            if (underlyingNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                runCatching {
                    vpnService.setUnderlyingNetworks(arrayOf(underlyingNetwork))
                    Log.i(TAG, "Underlying network set: $underlyingNetwork")
                }
            }

            PerfTracer.end(PerfTracer.Phases.TUN_CREATE)
            Log.i(TAG, "TUN interface opened, fd=$fd")

            fd
        }
    }

    /**
     * 关闭 TUN 接口
     */
    fun closeTunInterface(): Result<Unit> {
        return runCatching {
            vpnInterface?.let { pfd ->
                runCatching { pfd.close() }
                vpnInterface = null
                Log.i(TAG, "TUN interface closed")
            }
            Unit
        }
    }

    /**
     * 保留 TUN 接口
     */
    fun preserveTunInterface(): ParcelFileDescriptor? = vpnInterface

    fun setVpnInterface(pfd: ParcelFileDescriptor?) { vpnInterface = pfd }
    fun setBoxService(service: BoxService?) { boxService = service }
    fun isBoxServiceValid(): Boolean = boxService != null
    fun isVpnInterfaceValid(): Boolean = vpnInterface?.fileDescriptor?.valid() == true

    suspend fun wakeBoxService(): Result<Unit> {
        return runCatching {
            withContext(Dispatchers.IO) {
                boxService?.wake()
                Unit
            }
        }
    }

    suspend fun resetNetwork(): Result<Unit> {
        return runCatching {
            withContext(Dispatchers.IO) {
                boxService?.resetNetwork()
                Unit
            }
        }
    }

    /**
     * Hot reload config without destroying VPN service
     * Returns true if hot reload succeeded, false if fallback to full restart is needed
     */
    suspend fun hotReloadConfig(configContent: String, preserveSelector: Boolean = true): Result<Boolean> {
        return runCatching {
            withContext(Dispatchers.IO) {
                if (!Libbox.canReload()) {
                    Log.w(TAG, "Hot reload not available, fallback to full restart")
                    return@withContext false
                }

                Log.i(TAG, "Attempting hot reload...")
                Libbox.reloadConfig(configContent, preserveSelector)

                // Update current config content
                currentConfigContent = configContent

                // Re-register BoxService after reload (the internal instance changed)
                boxService?.let { Libbox.setReloadableBoxService(it) }

                // Re-init BoxWrapperManager with the reloaded service
                boxService?.let { BoxWrapperManager.init(it) }

                Log.i(TAG, "Hot reload completed successfully (count: ${Libbox.getReloadCount()})")
                true
            }
        }
    }

    /**
     * Check if hot reload is available
     */
    fun canHotReload(): Boolean {
        return runCatching { Libbox.canReload() }.getOrDefault(false)
    }

    fun cleanup(): Result<Unit> {
        return runCatching {
            serviceScope.launch { stopFully() }
            platformInterface = null
            Log.i(TAG, "CoreManager cleaned up")
        }
    }
}
