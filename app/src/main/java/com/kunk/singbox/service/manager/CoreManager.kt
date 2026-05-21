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
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.TunOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

/**
 *
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

    @Volatile var commandServer: CommandServer? = null
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

    // ===== Command Client =====
    var commandClient: CommandClient? = null
        private set

    // ===== Locks =====
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    private var wifiLockSuppressed: Boolean = false

    private var platformInterface: PlatformInterface? = null

    /**
     */
    sealed class StartResult {
        data class Success(val durationMs: Long, val configContent: String) : StartResult()
        data class Failed(val error: String, val exception: Exception? = null) : StartResult()
        object Cancelled : StartResult()
    }

    /**
     */
    sealed class StopResult {
        object Success : StopResult()
        data class Failed(val error: String) : StopResult()
    }

    /**
     */
    fun init(platformInterface: PlatformInterface): Result<Unit> {
        return runCatching {
            this.platformInterface = platformInterface
            Log.i(TAG, "CoreManager initialized")
        }
    }

    /**
     */
    fun preallocateTunBuilder(): Result<Unit> {
        return runCatching {
            tunManager.preallocateBuilder()
            Log.d(TAG, "TUN builder preallocated")
        }
    }

    /**
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
     */
    fun setCurrentSettings(settings: AppSettings) {
        currentSettings = settings
    }

    /**
     */
    fun acquireLocks(): Result<Unit> {
        return runCatching {
            acquireWakeLock()
            acquireWifiLockIfAllowed()
            Log.i(TAG, "WakeLock and WifiLock acquired")
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KunBox:VpnService")
        wakeLock?.setReferenceCounted(false)
        // Keep a long timeout as a safety net. We rely on explicit release in stopFully().
        wakeLock?.acquire(24 * 60 * 60 * 1000L)
    }

    private fun acquireWifiLockIfAllowed() {
        if (wifiLockSuppressed) {
            Log.i(TAG, "WifiLock suppressed (power saving), skip acquire")
            return
        }
        if (wifiLock?.isHeld == true) return

        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "KunBox:VpnService")
        wifiLock?.setReferenceCounted(false)
        wifiLock?.acquire()
    }

    /**
     */
    fun releaseLocks(): Result<Unit> {
        return runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
            releaseWifiLockInternal()
            Log.i(TAG, "WakeLock and WifiLock released")
        }
    }

    /**
     * Reduce battery usage in background power-saving mode.
     * Stability-first: we only suppress WifiLock here (WakeLock kept as before).
     */
    fun enterPowerSavingMode(): Result<Unit> {
        return runCatching {
            wifiLockSuppressed = true
            releaseWifiLockInternal()
            Log.i(TAG, "Entered power saving mode: WifiLock suppressed")
        }
    }

    /**
     * Resume normal mode. WifiLock will be re-acquired when VPN is running.
     */
    fun exitPowerSavingMode(): Result<Unit> {
        return runCatching {
            wifiLockSuppressed = false
            acquireWifiLockIfAllowed()
            Log.i(TAG, "Exited power saving mode: WifiLock allowed")
        }
    }

    private fun releaseWifiLockInternal() {
        if (wifiLock?.isHeld == true) wifiLock?.release()
        wifiLock = null
    }

    /**
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
     */
    fun setCommandServer(server: CommandServer?) {
        commandServer = server
    }

    /**
     */
    suspend fun startLibbox(configContent: String): StartResult {
        if (isStarting) {
            return StartResult.Failed("Already starting")
        }

        isStarting = true
        PerfTracer.begin(PerfTracer.Phases.LIBBOX_START)

        val logRepo = com.kunk.singbox.repository.LogRepository.getInstance()

        return try {
            val server = commandServer
                ?: throw IllegalStateException("CommandServer not initialized")
            val pi = platformInterface
                ?: throw IllegalStateException("PlatformInterface not initialized")

            logRepo.addLog("INFO [Startup] [STEP] startLibbox: ensureLibboxSetup...")
            SingBoxCore.ensureLibboxSetup(context)

            logRepo.addLog("INFO [Startup] [STEP] startLibbox: creating BoxService...")
            val serviceStartTime = android.os.SystemClock.elapsedRealtime()

            withContext(Dispatchers.IO) {
                val overrideOptions = OverrideOptions().apply {
                    autoRedirect = false
                }
                server.startOrReloadService(configContent, overrideOptions)
            }

            val serviceStartDuration = android.os.SystemClock.elapsedRealtime() - serviceStartTime
            logRepo.addLog(
                "INFO [Startup] [STEP] startLibbox: BoxService started in ${serviceStartDuration}ms"
            )

            currentConfigContent = configContent

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
            logRepo.addLog("ERR [Startup] startLibbox failed: ${e.message}")
            StartResult.Failed(e.message ?: "Unknown error", e)
        } finally {
            isStarting = false
        }
    }

    /**
     */
    suspend fun stopService(): Result<Unit> {
        return runCatching {
            withContext(Dispatchers.IO) {
                BoxWrapperManager.release()

                SelectorManager.clear()

                commandServer?.closeService()

                currentConfigContent = null
                Log.i(TAG, "Service stopped")
                Unit
            }
        }
    }

    /**
     */
    suspend fun stopFully(): Result<Unit> {
        if (isStopping) {
            return Result.failure(IllegalStateException("Already stopping"))
        }

        isStopping = true

        return runCatching {
            withContext(Dispatchers.IO) {

                stopService()

                vpnInterface?.let { pfd ->
                    runCatching { pfd.close() }
                    vpnInterface = null
                }

                tunManager.cleanup()

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
     */
    suspend fun stop(): Result<Unit> = stopFully()

    /**
     */
    private fun applyUnderlyingNetworkIfPossible(underlyingNetwork: Network?, reason: String) {
        if (underlyingNetwork == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return

        runCatching {
            vpnService.setUnderlyingNetworks(arrayOf(underlyingNetwork))
            Log.i(TAG, "Underlying network set ($reason): $underlyingNetwork")
        }.onFailure { e ->
            Log.w(TAG, "Failed to set underlying network ($reason)", e)
        }
    }

    /**
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
            if (reuseExisting) {
                vpnInterface?.let { existing ->
                    val existingFd = existing.fd
                    if (existingFd >= 0) {

                        applyUnderlyingNetworkIfPossible(underlyingNetwork, reason = "reuse_tun")

                        Log.i(TAG, "Reusing existing TUN interface (fd=$existingFd)")
                        return@runCatching existingFd
                    }
                    Log.w(TAG, "Existing TUN interface has invalid fd, recreating")
                    runCatching { existing.close() }
                    vpnInterface = null
                }
            }

            PerfTracer.begin(PerfTracer.Phases.TUN_CREATE)

            val builder = tunManager.consumePreallocatedBuilder()
                ?: vpnService.Builder()

            tunManager.configureBuilder(builder, options, currentSettings)

            val pfd = tunManager.establishWithRetry(builder) { isStopping }
                ?: throw IllegalStateException("Failed to establish TUN interface")

            vpnInterface = pfd
            val fd = pfd.fd

            applyUnderlyingNetworkIfPossible(underlyingNetwork, reason = "new_tun")

            PerfTracer.end(PerfTracer.Phases.TUN_CREATE)
            Log.i(TAG, "TUN interface opened, fd=$fd")

            fd
        }
    }

    /**
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
     */
    fun preserveTunInterface(): ParcelFileDescriptor? = vpnInterface

    fun setVpnInterface(pfd: ParcelFileDescriptor?) { vpnInterface = pfd }
    fun isServiceRunning(): Boolean = currentConfigContent != null

    fun isVpnInterfaceValid(): Boolean = vpnInterface?.fileDescriptor?.valid() == true

    suspend fun wakeService(): Result<Unit> {
        return runCatching {
            withContext(Dispatchers.IO) {
                BoxWrapperManager.resume()
                Unit
            }
        }
    }

    suspend fun resetNetwork(): Result<Unit> {
        return runCatching {
            withContext(Dispatchers.IO) {
                BoxWrapperManager.resetNetwork()
                Unit
            }
        }
    }

    /**
     * Hot reload config without destroying VPN service
     * Returns true if hot reload succeeded, false if fallback to full restart is needed
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun hotReloadConfig(configContent: String, preserveSelector: Boolean = true): Result<Boolean> {
        return runCatching {
            withContext(Dispatchers.IO) {
                val server = commandServer ?: return@withContext false
                val pi = platformInterface ?: return@withContext false

                Log.i(TAG, "Attempting hot reload...")

                val overrideOptions = OverrideOptions().apply {
                    autoRedirect = false
                }
                server.startOrReloadService(configContent, overrideOptions)

                // Update current config content
                currentConfigContent = configContent

                Log.i(TAG, "Hot reload completed successfully")
                true
            }
        }
    }

    fun cleanup(): Result<Unit> {
        return runCatching {
            serviceScope.launch { stopFully() }
            platformInterface = null
            Log.i(TAG, "CoreManager cleaned up")
        }
    }
}
