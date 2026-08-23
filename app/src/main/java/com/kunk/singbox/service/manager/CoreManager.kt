package com.kunk.singbox.service.manager

import android.content.Context
import android.net.Network
import android.net.VpnService
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class CoreManager(
    private val context: Context,
    private val vpnService: VpnService,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "CoreManager"

        internal fun shouldAcquireServiceLock(isHeld: Boolean, locksSuppressed: Boolean): Boolean {
            return !locksSuppressed && !isHeld
        }

        internal fun isStartTokenCurrent(
            startToken: Long,
            currentGeneration: Long,
            stopping: Boolean
        ): Boolean {
            return !stopping && startToken == currentGeneration
        }
    }

    private val tunManager = VpnTunManager(context, vpnService)
    private val settingsRepository by lazy { SettingsRepository.getInstance(context) }

    @Volatile var commandServer: CommandServer? = null
        private set

    @Volatile var vpnInterface: ParcelFileDescriptor? = null
        private set

    @Volatile var currentSettings: AppSettings? = null
        private set

    private val starting = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    val isStarting: Boolean get() = starting.get()
    val isStopping: Boolean get() = stopping.get()

    @Volatile var currentConfigContent: String? = null
        private set

    // ===== Command Client =====
    var commandClient: CommandClient? = null
        private set

    // ===== Locks =====
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    private var locksSuppressed: Boolean = false

    private var platformInterface: PlatformInterface? = null
    private val lifecycleMutex = Mutex()
    private val tunLifecycleLock = Any()
    private val stopGeneration = AtomicLong(0L)
    private val tunRebuildGeneration = AtomicLong(0L)
    private val tunRebuildRequested = AtomicBoolean(false)
    private val runtimeAccess = Any()
    private val runtimeGeneration = AtomicLong(0L)
    @Volatile private var runtimeHandle: CoreRuntimeHandle? = null

    internal data class CoreRuntimeHandle(
        val generation: Long,
        val commandServer: CommandServer
    )

    fun captureStartToken(): Long? {
        val generation = stopGeneration.get()
        return generation.takeIf {
            isStartTokenCurrent(
                startToken = it,
                currentGeneration = stopGeneration.get(),
                stopping = stopping.get()
            )
        }
    }

    fun isStartTokenCurrent(startToken: Long): Boolean {
        return isStartTokenCurrent(
            startToken = startToken,
            currentGeneration = stopGeneration.get(),
            stopping = stopping.get()
        )
    }

    fun beginStop(): Long {
        if (stopping.compareAndSet(false, true)) {
            stopGeneration.incrementAndGet()
        }
        return stopGeneration.get()
    }

    fun completeStop() {
        stopping.set(false)
    }

    fun requestTunRebuild(): Long = synchronized(tunLifecycleLock) {
        tunRebuildRequested.set(true)
        tunRebuildGeneration.incrementAndGet()
    }

    fun clearTunRebuildRequest() = synchronized(tunLifecycleLock) {
        tunRebuildRequested.set(false)
    }

    fun currentTunRebuildGeneration(): Long = tunRebuildGeneration.get()

    fun isTunRebuildRequested(): Boolean = tunRebuildRequested.get()

    internal fun currentRuntimeGeneration(): Long = synchronized(runtimeAccess) {
        runtimeHandle?.generation ?: 0L
    }

    sealed class StartResult {
        data class Success(val durationMs: Long, val configContent: String) : StartResult()
        data class Failed(val error: String, val exception: Exception? = null) : StartResult()
        object Cancelled : StartResult()
    }

    sealed class StopResult {
        object Success : StopResult()
        data class Failed(val error: String) : StopResult()
    }

    fun init(platformInterface: PlatformInterface): Result<Unit> {
        return runCatching {
            this.platformInterface = platformInterface
            Log.i(TAG, "CoreManager initialized")
        }
    }

    fun preallocateTunBuilder(): Result<Unit> {
        return runCatching {
            tunManager.preallocateBuilder()
            Log.d(TAG, "TUN builder preallocated")
        }
    }

    suspend fun loadSettings(): Result<AppSettings> {
        return runCatching {
            PerfTracer.begin(PerfTracer.Phases.SETTINGS_LOAD)
            val settings = settingsRepository.settings.first()
            currentSettings = settings
            PerfTracer.end(PerfTracer.Phases.SETTINGS_LOAD)
            settings
        }.onFailure {
            PerfTracer.end(PerfTracer.Phases.SETTINGS_LOAD, "error")
        }
    }

    fun setCurrentSettings(settings: AppSettings) {
        currentSettings = settings
    }

    fun acquireLocks(): Result<Unit> {
        return runCatching {
            acquireWakeLock()
            acquireWifiLockIfAllowed()
            Log.i(TAG, "WakeLock and WifiLock acquired")
        }
    }

    private fun acquireWakeLock() {
        if (!shouldAcquireServiceLock(wakeLock?.isHeld == true, locksSuppressed)) {
            if (locksSuppressed) Log.i(TAG, "WakeLock suppressed (power saving), skip acquire")
            return
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KunBox:VpnService")
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire()
    }

    private fun acquireWifiLockIfAllowed() {
        if (!shouldAcquireServiceLock(wifiLock?.isHeld == true, locksSuppressed)) {
            if (locksSuppressed) Log.i(TAG, "WifiLock suppressed (power saving), skip acquire")
            return
        }

        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "KunBox:VpnService")
        wifiLock?.setReferenceCounted(false)
        wifiLock?.acquire()
    }

    fun releaseLocks(): Result<Unit> {
        return runCatching {
            releaseWakeLockInternal()
            releaseWifiLockInternal()
            Log.i(TAG, "WakeLock and WifiLock released")
        }
    }

    /**
     * 省电模式释放 CPU 与 Wi-Fi 锁，网络数据到达时仍可由系统唤醒 VPN 进程。
     */
    fun enterPowerSavingMode(): Result<Unit> {
        return runCatching {
            locksSuppressed = true
            releaseWakeLockInternal()
            releaseWifiLockInternal()
            Log.i(TAG, "Entered power saving mode: WakeLock and WifiLock released")
        }
    }

    /**
     * 返回前台后仅在内核仍运行时恢复锁，避免停止过程中重新持锁。
     */
    fun exitPowerSavingMode(): Result<Unit> {
        return runCatching {
            locksSuppressed = false
            if (isServiceRunning()) {
                acquireWakeLock()
                acquireWifiLockIfAllowed()
            }
            Log.i(TAG, "Exited power saving mode: locks restored=${isServiceRunning()}")
        }
    }

    private fun releaseWakeLockInternal() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun releaseWifiLockInternal() {
        if (wifiLock?.isHeld == true) wifiLock?.release()
        wifiLock = null
    }

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

    fun setCommandServer(server: CommandServer?) {
        commandServer = server
    }

    @Suppress("LongMethod", "CognitiveComplexMethod")
    suspend fun startLibbox(configContent: String, startToken: Long): StartResult {
        if (!isStartTokenCurrent(startToken)) {
            return StartResult.Cancelled
        }
        if (!starting.compareAndSet(false, true)) {
            return StartResult.Failed("Already starting")
        }

        val logRepo = com.kunk.singbox.repository.LogRepository.getInstance()

        return try {
            lifecycleMutex.withLock {
                if (!isStartTokenCurrent(startToken)) {
                    Log.i(TAG, "Libbox start cancelled by a newer stop request")
                    return@withLock StartResult.Cancelled
                }

                PerfTracer.begin(PerfTracer.Phases.LIBBOX_START)
                try {
                    val server = commandServer
                        ?: throw IllegalStateException("CommandServer not initialized")
                    checkNotNull(platformInterface) { "PlatformInterface not initialized" }

                    logRepo.addLog("INFO [Startup] [STEP] startLibbox: ensureLibboxSetup...")
                    SingBoxCore.ensureLibboxSetup(context)

                    if (!isStartTokenCurrent(startToken)) {
                        Log.i(TAG, "Libbox start cancelled before native start")
                        PerfTracer.end(PerfTracer.Phases.LIBBOX_START, "cancelled")
                        return@withLock StartResult.Cancelled
                    }

                    logRepo.addLog("INFO [Startup] [STEP] startLibbox: creating BoxService...")
                    val serviceStartTime = android.os.SystemClock.elapsedRealtime()

                    withContext(Dispatchers.IO) {
                        val overrideOptions = OverrideOptions().apply {
                            autoRedirect = false
                        }
                        server.startOrReloadService(configContent, overrideOptions)
                    }

                    if (!isStartTokenCurrent(startToken)) {
                        Log.i(TAG, "Libbox start invalidated while native start was running")
                        PerfTracer.end(PerfTracer.Phases.LIBBOX_START, "cancelled")
                        return@withLock StartResult.Cancelled
                    }

                    val serviceStartDuration = android.os.SystemClock.elapsedRealtime() - serviceStartTime
                    logRepo.addLog(
                        "INFO [Startup] [STEP] startLibbox: BoxService started in ${serviceStartDuration}ms"
                    )

                    currentConfigContent = configContent
                    synchronized(runtimeAccess) {
                        runtimeHandle = CoreRuntimeHandle(runtimeGeneration.incrementAndGet(), server)
                    }

                    val durationMs = PerfTracer.end(PerfTracer.Phases.LIBBOX_START)
                    Log.i(TAG, "Libbox started in ${durationMs}ms")

                    StartResult.Success(durationMs, configContent)
                } catch (e: CancellationException) {
                    PerfTracer.end(PerfTracer.Phases.LIBBOX_START, "cancelled")
                    Log.i(TAG, "Libbox start cancelled")
                    throw e
                } catch (e: Exception) {
                    PerfTracer.end(PerfTracer.Phases.LIBBOX_START, "error")
                    Log.e(TAG, "Libbox start failed: ${e.message}", e)
                    logRepo.addLog("ERR [Startup] startLibbox failed: ${e.message}")
                    StartResult.Failed(e.message ?: "Unknown error", e)
                }
            }
        } finally {
            starting.set(false)
        }
    }

    suspend fun stopCorePreservingTun(expectedRuntimeGeneration: Long? = null): Result<Unit> {
        beginStop()
        return try {
            runCatching {
                lifecycleMutex.withLock { stopServiceLocked(expectedRuntimeGeneration) }
            }
        } finally {
            completeStop()
        }
    }

    suspend fun stopService(): Result<Unit> = stopCorePreservingTun()

    suspend fun prepareTunReplacement(expectedRuntimeGeneration: Long? = null): Result<Unit> {
        requestTunRebuild()
        return stopCorePreservingTun(expectedRuntimeGeneration)
    }

    @Suppress("CognitiveComplexMethod")
    private suspend fun stopServiceLocked(expectedRuntimeGeneration: Long? = null) {
        withContext(Dispatchers.IO) {
            val capturedHandle = synchronized(runtimeAccess) { runtimeHandle }
            if (expectedRuntimeGeneration != null && expectedRuntimeGeneration > 0L &&
                capturedHandle?.generation != expectedRuntimeGeneration
            ) {
                Log.w(
                    TAG,
                    "Skip stale core cleanup expected=$expectedRuntimeGeneration current=${capturedHandle?.generation}"
                )
                return@withContext
            }
            val capturedServer = capturedHandle?.commandServer ?: commandServer
            capturedServer?.closeService()
            val ownsCurrentRuntime = synchronized(runtimeAccess) {
                if (capturedHandle == null || runtimeHandle === capturedHandle) {
                    runtimeHandle = null
                    if (commandServer === capturedServer) commandServer = null
                    true
                } else {
                    false
                }
            }
            if (ownsCurrentRuntime) {
                BoxWrapperManager.release()
                SelectorManager.clear()
                currentConfigContent = null
            }
            Log.i(TAG, "Service stopped")
        }
    }

    suspend fun stopFully(
        completeLifecycle: Boolean = true,
        expectedRuntimeGeneration: Long? = null
    ): Result<Unit> {
        beginStop()

        return try {
            runCatching {
                lifecycleMutex.withLock {
                    val stopResult = runCatching { stopServiceLocked(expectedRuntimeGeneration) }

                    withContext(Dispatchers.IO) {
                        synchronized(tunLifecycleLock) {
                            tunRebuildRequested.set(false)
                            tunRebuildGeneration.incrementAndGet()
                            vpnInterface?.let { pfd ->
                                runCatching { pfd.close() }
                                vpnInterface = null
                            }
                        }

                        tunManager.cleanup()

                        releaseLocks()

                        currentSettings = null
                        Log.i(TAG, "VPN fully stopped")
                    }

                    stopResult.getOrThrow()
                }
            }
        } finally {
            if (completeLifecycle) {
                completeStop()
            }
        }
    }

    suspend fun stop(): Result<Unit> = stopFully()

    private fun applyUnderlyingNetworkIfPossible(underlyingNetwork: Network?, reason: String) {
        if (underlyingNetwork == null) return

        runCatching {
            vpnService.setUnderlyingNetworks(arrayOf(underlyingNetwork))
            Log.i(TAG, "Underlying network set ($reason): $underlyingNetwork")
        }.onFailure { e ->
            Log.w(TAG, "Failed to set underlying network ($reason)", e)
        }
    }

    @Suppress("CognitiveComplexMethod")
    fun openTun(
        options: TunOptions?,
        underlyingNetwork: Network? = null,
        reuseExisting: Boolean = true
    ): Result<Int> {
        if (options == null) {
            return Result.failure(IllegalArgumentException("TunOptions cannot be null"))
        }

        return synchronized(tunLifecycleLock) {
            var tunTraceStarted = false
            val replaceExisting = tunRebuildRequested.get() || !reuseExisting
            val previousInterface = vpnInterface
            runCatching {
                if (!replaceExisting) {
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
                tunTraceStarted = true

                val builder = tunManager.consumePreallocatedBuilder()
                    ?: vpnService.Builder()

                tunManager.configureBuilder(builder, options, currentSettings)

                val pfd = tunManager.establishWithRetry(builder) { isStopping }
                    ?: throw IllegalStateException("Failed to establish TUN interface")

                vpnInterface = pfd
                val fd = pfd.fd

                if (previousInterface != null && previousInterface !== pfd) {
                    runCatching { previousInterface.close() }
                        .onFailure { Log.w(TAG, "Failed to close replaced TUN interface", it) }
                }
                tunRebuildRequested.set(false)

                applyUnderlyingNetworkIfPossible(underlyingNetwork, reason = "new_tun")

                PerfTracer.end(PerfTracer.Phases.TUN_CREATE)
                Log.i(TAG, "TUN interface opened, fd=$fd")

                fd
            }.onFailure {
                if (tunTraceStarted) {
                    PerfTracer.end(PerfTracer.Phases.TUN_CREATE, "error")
                }
            }
        }
    }

    fun closeTunInterface(): Result<Unit> {
        return synchronized(tunLifecycleLock) {
            runCatching {
                tunRebuildRequested.set(false)
                tunRebuildGeneration.incrementAndGet()
                vpnInterface?.let { pfd ->
                    runCatching { pfd.close() }
                    vpnInterface = null
                    Log.i(TAG, "TUN interface closed")
                }
                Unit
            }
        }
    }

    fun isServiceRunning(): Boolean = currentConfigContent != null

    fun isVpnInterfaceValid(): Boolean = vpnInterface?.fileDescriptor?.valid() == true

    internal fun appliedPerAppVpnPlan(): VpnTunManager.Companion.AppliedPerAppVpnPlan =
        tunManager.appliedPerAppVpnPlan

    fun alwaysOnVpnStatus(): Pair<String?, Boolean> = tunManager.checkAlwaysOnVpn()

    suspend fun wakeService(): Result<Unit> {
        return runCatching {
            withContext(Dispatchers.IO) {
                BoxWrapperManager.wake()
            }
        }
    }

    /**
     * Hot reload config without destroying VPN service
     * Returns true if hot reload succeeded, false if fallback to full restart is needed
     */
    @Suppress("CognitiveComplexMethod")
    suspend fun hotReloadConfig(configContent: String, startToken: Long): Result<Boolean> {
        return try {
            Result.success(
                lifecycleMutex.withLock {
                    if (!isStartTokenCurrent(startToken) || currentConfigContent == null) {
                        Log.w(TAG, "Hot reload cancelled because the service is stopping or already stopped")
                        return@withLock false
                    }

                    withContext(Dispatchers.IO) {
                        val server = commandServer ?: return@withContext false
                        if (platformInterface == null || !isStartTokenCurrent(startToken)) {
                            return@withContext false
                        }

                        Log.i(TAG, "Attempting hot reload...")

                        val overrideOptions = OverrideOptions().apply {
                            autoRedirect = false
                        }
                        server.startOrReloadService(configContent, overrideOptions)

                        if (!isStartTokenCurrent(startToken)) {
                            Log.i(TAG, "Hot reload invalidated while native reload was running")
                            return@withContext false
                        }

                        currentConfigContent = configContent

                        Log.i(TAG, "Hot reload completed successfully")
                        true
                    }
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
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
