package com.kunk.singbox.manager

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.PerAppVpnPolicy
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.manager.VpnStopInitiator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

object VpnServiceManager {
    private const val TAG = "VpnServiceManager"

    data class StartCommand(
        val serviceClass: Class<*>,
        val action: String,
        val configPath: String? = null,
        val cleanCache: Boolean = false
    )

    @Volatile
    private var cachedTunEnabled: Boolean? = null

    @Volatile
    private var lastTunCheckTime: Long = 0L

    private const val CACHE_VALIDITY_MS = 5_000L
    private const val PER_APP_APPLY_TIMEOUT_MS = 10_000L
    private const val PER_APP_APPLY_POLL_MS = 100L
    private val restartHandler = Handler(Looper.getMainLooper())
    private val perAppApplyMutex = Mutex()
    private val perAppApplyGeneration = AtomicLong(0L)

    @Volatile
    private var pendingRestartTask: Runnable? = null

    @Volatile
    private var pendingRestartVersion: Long = 0L

    fun isRunning(): Boolean {
        val persistedActive = VpnStateStore.getActive()
        val pending = VpnStateStore.getPending()

        if (pending.isNotEmpty()) {
            return persistedActive || pending == "starting" || pending == "stopping"
        }

        if (persistedActive) {
            return true
        }

        return SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value
    }

    fun isStarting(): Boolean {
        return SingBoxRemote.isStarting.value
    }

    /**
     *
     * @return "tun" | "proxy" | null
     */
    fun getActiveService(context: Context): String? {
        if (!isRunning()) return null

        return if (isTunEnabled(context)) "tun" else "proxy"
    }

    fun toggleVpn(context: Context): Result<Unit> {
        return if (isRunning()) {
            stopVpn(context, VpnStopInitiator.USER_UI)
        } else {
            startVpn(context)
        }
    }

    fun startVpn(context: Context): Result<Unit> {
        val tunEnabled = isTunEnabled(context)
        return startVpn(context, tunEnabled)
    }

    fun buildStartCommand(
        tunMode: Boolean,
        configPath: String? = null,
        cleanCache: Boolean = false
    ): StartCommand {
        return if (tunMode) {
            StartCommand(
                serviceClass = SingBoxService::class.java,
                action = SingBoxService.ACTION_START,
                configPath = configPath,
                cleanCache = cleanCache
            )
        } else {
            StartCommand(
                serviceClass = ProxyOnlyService::class.java,
                action = ProxyOnlyService.ACTION_START,
                configPath = configPath,
                cleanCache = cleanCache
            )
        }
    }

    internal fun shouldDispatchStopToService(
        activeMode: VpnStateStore.CoreMode,
        serviceMode: VpnStateStore.CoreMode
    ): Boolean {
        return activeMode == VpnStateStore.CoreMode.NONE || activeMode == serviceMode
    }

    suspend fun applyPerAppRuleChangeIfRunning(
        context: Context,
        expectedRevision: Long? = null
    ): Result<Boolean> {
        val appContext = context.applicationContext
        val desiredPolicy = PerAppVpnPolicy.from(SettingsRepository.getInstance(appContext).settings.value)
        val targetRevision = expectedRevision ?: desiredPolicy.revision
        val targetDigest = desiredPolicy.digest()
        val requestGeneration = perAppApplyGeneration.incrementAndGet()
        return perAppApplyMutex.withLock {
            if (requestGeneration != perAppApplyGeneration.get()) return@withLock Result.success(false)
            applyPerAppRuleChangeLocked(
                appContext = appContext,
                targetRevision = targetRevision,
                targetDigest = targetDigest,
                requestGeneration = requestGeneration
            )
        }
    }

    private suspend fun applyPerAppRuleChangeLocked(
        appContext: Context,
        targetRevision: Long,
        targetDigest: String,
        requestGeneration: Long
    ): Result<Boolean> {
        if (!isPerAppRuntimeReady()) return Result.success(false)

        return try {
            val configPath = generatePerAppConfigPath(appContext)
            if (!isPerAppRuntimeReady()) return Result.success(false)
            dispatchPerAppRestart(appContext, configPath, targetRevision)
            val confirmed = awaitPerAppPolicyConfirmation(
                targetRevision,
                targetDigest,
                requestGeneration
            )
            if (!confirmed && requestGeneration == perAppApplyGeneration.get()) {
                throw IllegalStateException("Per-app VPN policy apply was not confirmed")
            }
            Result.success(confirmed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply per-app VPN policy", e)
            Result.failure(e)
        }
    }

    private fun isPerAppRuntimeReady(): Boolean =
        isRunning() &&
            !VpnStateStore.isManuallyStopped() &&
            VpnStateStore.getPending().isBlank() &&
            VpnStateStore.getMode() == VpnStateStore.CoreMode.VPN

    private suspend fun generatePerAppConfigPath(appContext: Context): String =
        ConfigRepository.getInstance(appContext)
            .generateConfigFile()
            ?.path
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Failed to generate config for per-app rule change")

    private fun dispatchPerAppRestart(appContext: Context, configPath: String, targetRevision: Long) {
        appContext.startService(Intent(appContext, SingBoxService::class.java).apply {
            action = SingBoxService.ACTION_FULL_RESTART
            putExtra(SingBoxService.EXTRA_CONFIG_PATH, configPath)
            putExtra(SingBoxService.EXTRA_PER_APP_RULE_RESTART, true)
            putExtra(SingBoxService.EXTRA_PER_APP_POLICY_REVISION, targetRevision)
        })
    }

    private suspend fun awaitPerAppPolicyConfirmation(
        targetRevision: Long,
        targetDigest: String,
        requestGeneration: Long
    ): Boolean = withTimeoutOrNull<Boolean>(PER_APP_APPLY_TIMEOUT_MS) {
        while (true) {
            if (requestGeneration != perAppApplyGeneration.get()) return@withTimeoutOrNull false
            val applied = VpnStateStore.getAppliedPerAppPolicy()
            if (applied.revision == targetRevision && applied.digest == targetDigest) break
            if (!isPerAppRuntimeReady()) return@withTimeoutOrNull false
            delay(PER_APP_APPLY_POLL_MS)
        }
        true
    } ?: false

    fun startVpn(context: Context, tunMode: Boolean): Result<Unit> {
        Log.d(TAG, "startVpn: tunMode=$tunMode")

        val command = buildStartCommand(tunMode)
        val intent = Intent(context, command.serviceClass).apply {
            action = command.action
            command.configPath?.let { putExtra(SingBoxService.EXTRA_CONFIG_PATH, it) }
            if (command.cleanCache) {
                putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
            }
        }

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Unit
        }
            .onFailure { Log.e(TAG, "Failed to start VPN service", it) }
    }

    fun stopVpn(context: Context, initiator: VpnStopInitiator): Result<Unit> {
        Log.d(TAG, "stopVpn: initiator=${initiator.wireValue}")

        return runCatching {
            val appContext = context.applicationContext
            val activeMode = VpnStateStore.getMode()
            val stopResults = buildList {
                if (shouldDispatchStopToService(activeMode, VpnStateStore.CoreMode.VPN)) {
                    add(runCatching {
                        appContext.startService(Intent(appContext, SingBoxService::class.java).apply {
                            action = SingBoxService.ACTION_STOP
                            putExtra(SingBoxService.EXTRA_STOP_INITIATOR, initiator.wireValue)
                        })
                    })
                }
                if (shouldDispatchStopToService(activeMode, VpnStateStore.CoreMode.PROXY)) {
                    add(runCatching {
                        appContext.startService(Intent(appContext, ProxyOnlyService::class.java).apply {
                            action = ProxyOnlyService.ACTION_STOP
                            putExtra(SingBoxService.EXTRA_STOP_INITIATOR, initiator.wireValue)
                        })
                    })
                }
            }
            if (stopResults.none { it.isSuccess }) {
                throw stopResults.firstNotNullOfOrNull { it.exceptionOrNull() }
                    ?: IllegalStateException("Failed to send stop commands")
            }
            Unit
        }
            .onFailure { Log.e(TAG, "Failed to stop VPN service", it) }
    }

    fun forceStop(context: Context): Result<Unit> {
        Log.e(TAG, "forceStop: dispatching emergency stop")
        return runCatching {
            val appContext = context.applicationContext
            val activeMode = VpnStateStore.getMode()
            val stopResults = buildList {
                if (shouldDispatchStopToService(activeMode, VpnStateStore.CoreMode.VPN)) {
                    add(runCatching {
                        appContext.startService(Intent(appContext, SingBoxService::class.java).apply {
                            action = SingBoxService.ACTION_FORCE_STOP
                        })
                    })
                }
                if (shouldDispatchStopToService(activeMode, VpnStateStore.CoreMode.PROXY)) {
                    add(runCatching {
                        appContext.startService(Intent(appContext, ProxyOnlyService::class.java).apply {
                            action = ProxyOnlyService.ACTION_FORCE_STOP
                        })
                    })
                }
            }
            if (stopResults.none { it.isSuccess }) {
                throw stopResults.firstNotNullOfOrNull { it.exceptionOrNull() }
                    ?: IllegalStateException("Failed to send force stop commands")
            }
            Unit
        }.onFailure { Log.e(TAG, "Failed to force stop VPN service", it) }
    }

    fun restartVpn(context: Context) {
        Log.d(TAG, "restartVpn")

        val appContext = context.applicationContext
        val currentTunMode = isTunEnabled(appContext)
        val version = pendingRestartVersion + 1L
        pendingRestartVersion = version

        pendingRestartTask?.let { restartHandler.removeCallbacks(it) }
        stopVpn(appContext, VpnStopInitiator.RESTART)

        val restartTask = Runnable {
            if (pendingRestartVersion != version) return@Runnable
            pendingRestartTask = null
            startVpn(appContext, currentTunMode)
        }
        pendingRestartTask = restartTask
        restartHandler.postDelayed(restartTask, 500)
    }

    private fun isTunEnabled(context: Context? = null): Boolean {
        val now = System.currentTimeMillis()
        val cached = cachedTunEnabled

        if (cached != null && (now - lastTunCheckTime) < CACHE_VALIDITY_MS) {
            return cached
        }

        if (context != null) {
            val tunEnabled = SettingsRepository
                .getInstance(context.applicationContext)
                .settings
                .value
                .tunEnabled

            cachedTunEnabled = tunEnabled
            lastTunCheckTime = now

            return tunEnabled
        }

        return cached ?: true
    }

    fun refreshTunSetting(context: Context) {
        val tunEnabled = SettingsRepository
            .getInstance(context.applicationContext)
            .settings
            .value
            .tunEnabled

        cachedTunEnabled = tunEnabled
        lastTunCheckTime = System.currentTimeMillis()

        Log.d(TAG, "refreshTunSetting: tunEnabled=$tunEnabled")
    }

    fun getCurrentConfig(context: Context): String {
        return buildString {
            append("isRunning: ${isRunning()}\n")
            append("isStarting: ${isStarting()}\n")
            append("activeService: ${getActiveService(context)}\n")
            append("cachedTunEnabled: $cachedTunEnabled\n")
            append("activeLabel: ${SingBoxRemote.activeLabel.value}\n")
        }
    }
}
