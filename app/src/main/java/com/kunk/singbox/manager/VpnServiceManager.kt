package com.kunk.singbox.manager

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.service.SingBoxService

/**
 *
 *
 */
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
    private val restartHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var pendingRestartTask: Runnable? = null

    @Volatile
    private var pendingRestartVersion: Long = 0L

    /**
     *
     */
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

    /**
     */
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

    /**
     *
     */
    fun toggleVpn(context: Context): Result<Unit> {
        return if (isRunning()) {
            stopVpn(context)
        } else {
            startVpn(context)
        }
    }

    /**
     *
     */
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

    /**
     *
     */
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

    /**
     *
     */
    fun stopVpn(context: Context): Result<Unit> {
        Log.d(TAG, "stopVpn")

        return runCatching {
            val appContext = context.applicationContext
            val tunResult = runCatching {
                appContext.startService(Intent(appContext, SingBoxService::class.java).apply {
                    action = SingBoxService.ACTION_STOP
                })
            }
            val proxyResult = runCatching {
                appContext.startService(Intent(appContext, ProxyOnlyService::class.java).apply {
                    action = ProxyOnlyService.ACTION_STOP
                })
            }

            if (tunResult.isFailure && proxyResult.isFailure) {
                throw tunResult.exceptionOrNull() ?: proxyResult.exceptionOrNull()
                    ?: IllegalStateException("Failed to send stop commands")
            }
            Unit
        }
            .onFailure { Log.e(TAG, "Failed to stop VPN service", it) }
    }

    /**
     *
     */
    fun restartVpn(context: Context) {
        Log.d(TAG, "restartVpn")

        val appContext = context.applicationContext
        val currentTunMode = isTunEnabled(appContext)
        val version = pendingRestartVersion + 1L
        pendingRestartVersion = version

        pendingRestartTask?.let { restartHandler.removeCallbacks(it) }
        stopVpn(appContext)

        val restartTask = Runnable {
            if (pendingRestartVersion != version) return@Runnable
            pendingRestartTask = null
            startVpn(appContext, currentTunMode)
        }
        pendingRestartTask = restartTask
        restartHandler.postDelayed(restartTask, 500)
    }

    /**
     *
     */
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

    /**
     */
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
