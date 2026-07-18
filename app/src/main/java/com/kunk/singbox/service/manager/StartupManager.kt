package com.kunk.singbox.service.manager

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Network
import android.net.VpnService
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.kunk.singbox.R
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.RuleSetRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.service.notification.VpnNotificationManager
import com.kunk.singbox.utils.LocalNetworkPermission
import com.kunk.singbox.utils.perf.PerfTracer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket

class StartupManager(
    private val context: Context,
    private val vpnService: VpnService,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "StartupManager"

        internal fun resolveRuntimeLogLevel(debugLoggingEnabled: Boolean): String {
            return if (debugLoggingEnabled) "debug" else "error"
        }
    }

    private val gson = Gson()
    private val logRepo by lazy { LogRepository.getInstance() }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logRepo.addLog("INFO [Startup] $msg")
    }

    private fun isPortAvailable(port: Int): Boolean {
        if (port <= 0) return true
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("127.0.0.1", port))
                true
            }
        } catch (@Suppress("SwallowedException") e: Exception) {
            false
        }
    }

    interface Callbacks {
        fun onStarting()
        fun onStarted(configContent: String)
        fun onFailed(error: String)
        fun onCancelled()

        fun createNotification(): Notification
        fun markForegroundStarted()

        fun registerScreenStateReceiver()
        fun startForeignVpnMonitor()
        fun stopForeignVpnMonitor()

        fun detectExistingVpns(): Boolean

        fun createAndStartCommandServer(): Result<Unit>
        fun launchPostStartTasks(configContent: String)

        fun updateTileState()
        fun setIsRunning(running: Boolean)
        fun setIsStarting(starting: Boolean)
        fun setLastError(error: String?)
        fun persistVpnState(isRunning: Boolean)
        fun persistVpnPending(pending: String)

        suspend fun waitForUsablePhysicalNetwork(timeoutMs: Long): Network?
        suspend fun ensureNetworkCallbackReady(timeoutMs: Long)
        fun setLastKnownNetwork(network: Network?)
        fun setNetworkCallbackReady(ready: Boolean)
        fun restoreUnderlyingNetwork(network: Network)

        suspend fun waitForCleanupJob()
        fun stopSelf()
    }

    sealed class StartResult {
        data class Success(val configContent: String, val durationMs: Long) : StartResult()
        data class Failed(val error: String, val exception: Exception? = null) : StartResult()
        data object Cancelled : StartResult()
        data object NeedPermission : StartResult()
    }

    private data class ParallelInitResult(
        val network: Network?,
        val ruleSetReady: Boolean,
        val settings: AppSettings,
        val configContent: String
    )

    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod")
    suspend fun startVpn(
        configPath: String,
        cleanCache: Boolean,
        startToken: Long,
        coreManager: CoreManager,
        callbacks: Callbacks
    ): StartResult = withContext(Dispatchers.IO) {
        val startupBeginMs = SystemClock.elapsedRealtime()
        PerfTracer.begin(PerfTracer.Phases.VPN_STARTUP)
        log("========== VPN STARTUP BEGIN ==========")

        try {

            var stepStart = SystemClock.elapsedRealtime()
            callbacks.waitForCleanupJob()
            log("[STEP] waitForCleanupJob: ${SystemClock.elapsedRealtime() - stepStart}ms")

            if (!coreManager.isStartTokenCurrent(startToken)) {
                callbacks.onCancelled()
                PerfTracer.end(PerfTracer.Phases.VPN_STARTUP, "cancelled")
                return@withContext StartResult.Cancelled
            }

            callbacks.onStarting()

            stepStart = SystemClock.elapsedRealtime()
            val hasExistingVpn = callbacks.detectExistingVpns()
            log("[STEP] detectExistingVpns: ${SystemClock.elapsedRealtime() - stepStart}ms, found=$hasExistingVpn")

            if (hasExistingVpn) {
                log("[STEP] External VPN detected, waiting for system to prepare takeover...")
                delay(100)
            }
            stepStart = SystemClock.elapsedRealtime()
            val prepareIntent = VpnService.prepare(context)
            log("[STEP] VpnService.prepare: ${SystemClock.elapsedRealtime() - stepStart}ms")
            if (prepareIntent != null) {
                handlePermissionRequired(prepareIntent, callbacks)
                PerfTracer.end(PerfTracer.Phases.VPN_STARTUP, "permission_required")
                return@withContext StartResult.NeedPermission
            }

            stepStart = SystemClock.elapsedRealtime()
            coreManager.acquireLocks()
            callbacks.registerScreenStateReceiver()
            log("[STEP] acquireLocks+registerReceiver: ${SystemClock.elapsedRealtime() - stepStart}ms")

            callbacks.startForeignVpnMonitor()

            stepStart = SystemClock.elapsedRealtime()
            PerfTracer.begin(PerfTracer.Phases.PARALLEL_INIT)
            val initResult = try {
                parallelInit(configPath, callbacks).also {
                    PerfTracer.end(PerfTracer.Phases.PARALLEL_INIT)
                }
            } catch (e: CancellationException) {
                PerfTracer.end(PerfTracer.Phases.PARALLEL_INIT, "cancelled")
                throw e
            } catch (e: Exception) {
                PerfTracer.end(PerfTracer.Phases.PARALLEL_INIT, "error")
                throw e
            }
            log("[STEP] parallelInit: ${SystemClock.elapsedRealtime() - stepStart}ms")

            if (!initResult.ruleSetReady) {
                throw IllegalStateException("Required rule sets are not ready")
            }

            if (initResult.network == null) {
                throw IllegalStateException("No usable physical network before VPN start")
            }
            log("[STEP] network ready: ${initResult.network}")

            callbacks.setLastKnownNetwork(initResult.network)
            callbacks.setNetworkCallbackReady(true)

            coreManager.setCurrentSettings(initResult.settings)

            val configContent = initResult.configContent

            if (cleanCache) {
                stepStart = SystemClock.elapsedRealtime()
                coreManager.cleanCacheDb()
                log("[STEP] cleanCacheDb: ${SystemClock.elapsedRealtime() - stepStart}ms")
            }

            val proxyPort = initResult.settings.proxyPort
            if (proxyPort > 0 && !isPortAvailable(proxyPort)) {
                log("[STEP] Port $proxyPort unexpectedly in use, this should not happen")
                throw IllegalStateException("Port $proxyPort is still in use")
            }

            stepStart = SystemClock.elapsedRealtime()
            callbacks.createAndStartCommandServer().getOrThrow()
            log("[STEP] createAndStartCommandServer: ${SystemClock.elapsedRealtime() - stepStart}ms")

            callbacks.restoreUnderlyingNetwork(initResult.network)

            stepStart = SystemClock.elapsedRealtime()
            when (val result = coreManager.startLibbox(configContent, startToken)) {
                is CoreManager.StartResult.Success -> {
                    log(
                        "[STEP] startLibbox: ${SystemClock.elapsedRealtime() - stepStart}ms " +
                            "(internal: ${result.durationMs}ms)"
                    )
                }
                is CoreManager.StartResult.Failed -> {
                    throw Exception("Libbox start failed: ${result.error}", result.exception)
                }
                is CoreManager.StartResult.Cancelled -> {
                    callbacks.onCancelled()
                    PerfTracer.end(PerfTracer.Phases.VPN_STARTUP, "cancelled")
                    return@withContext StartResult.Cancelled
                }
            }

            stepStart = SystemClock.elapsedRealtime()
            if (!coreManager.isServiceRunning()) {
                throw IllegalStateException("Service is not running after successful start")
            }
            if (!coreManager.isStartTokenCurrent(startToken)) {
                callbacks.onCancelled()
                PerfTracer.end(PerfTracer.Phases.VPN_STARTUP, "cancelled")
                return@withContext StartResult.Cancelled
            }

            stepStart = SystemClock.elapsedRealtime()
            callbacks.setIsRunning(true)
            callbacks.setLastError(null)
            callbacks.persistVpnState(true)
            callbacks.stopForeignVpnMonitor()
            log("[STEP] markRunning: ${SystemClock.elapsedRealtime() - stepStart}ms")

            stepStart = SystemClock.elapsedRealtime()
            callbacks.persistVpnPending("")
            callbacks.updateTileState()
            log("[STEP] updateUI: ${SystemClock.elapsedRealtime() - stepStart}ms")

            runCatching { callbacks.onStarted(configContent) }
                .onFailure { error -> Log.w(TAG, "Post-start notification failed", error) }
            runCatching { callbacks.launchPostStartTasks(configContent) }
                .onFailure { error -> Log.w(TAG, "Failed to launch post-start tasks", error) }

            val totalMs = PerfTracer.end(PerfTracer.Phases.VPN_STARTUP)
            val actualTotal = SystemClock.elapsedRealtime() - startupBeginMs
            log("========== VPN STARTUP COMPLETE: ${actualTotal}ms ==========")

            StartResult.Success(configContent, totalMs)
        } catch (e: CancellationException) {
            PerfTracer.end(PerfTracer.Phases.VPN_STARTUP, "cancelled")
            callbacks.onCancelled()
            StartResult.Cancelled
        } catch (e: Exception) {
            PerfTracer.end(PerfTracer.Phases.VPN_STARTUP, "error")
            val error = parseStartError(e)
            callbacks.onFailed(error)
            StartResult.Failed(error, e)
        } finally {
            callbacks.setIsStarting(false)
        }
    }

    @Suppress("LongMethod")
    private suspend fun parallelInit(
        configPath: String,
        callbacks: Callbacks
    ): ParallelInitResult = coroutineScope {
        val parallelStart = SystemClock.elapsedRealtime()
        log("[parallelInit] BEGIN")

        var stepStart = SystemClock.elapsedRealtime()
        val configFile = File(configPath)
        if (!configFile.exists()) {
            throw IllegalStateException("Config file not found: $configPath")
        }
        val rawConfigContent = configFile.readText()
        log(
            "[parallelInit] readConfig: ${SystemClock.elapsedRealtime() - stepStart}ms, size=${rawConfigContent.length}"
        )

        val networkDeferred = async { ensureNetworkCallbackReady(callbacks) }
        val ruleSetDeferred = async { ensureRuleSetReady() }
        val settingsDeferred = async {
            val t = SystemClock.elapsedRealtime()
            val settingsRepository = SettingsRepository.getInstance(context)
            settingsRepository.reloadFromStorage()
            val settings = settingsRepository.settings.first()
            log("[parallelInit] loadSettings: ${SystemClock.elapsedRealtime() - t}ms")
            settings
        }
        stepStart = SystemClock.elapsedRealtime()
        val settings = settingsDeferred.await()
        ensureLocalNetworkPermission(settings)
        val configContent = patchConfig(rawConfigContent, settings)
        log("[parallelInit] patchConfig: ${SystemClock.elapsedRealtime() - stepStart}ms")

        dumpDebugOutbounds(configPath, configContent, settings.debugLoggingEnabled)

        val network = networkDeferred.await()
        val ruleSetReady = ruleSetDeferred.await()

        log("[parallelInit] END: ${SystemClock.elapsedRealtime() - parallelStart}ms total")

        ParallelInitResult(
            network = network,
            ruleSetReady = ruleSetReady,
            settings = settings,
            configContent = configContent
        )
    }

    private fun dumpDebugOutbounds(configPath: String, configContent: String, debugEnabled: Boolean) {
        if (!debugEnabled) return

        try {
            val configFile = File(configPath)
            logDebug(
                "[DEBUG] running_config path=${configFile.absolutePath}, " +
                    "exists=${configFile.exists()}, size=${configFile.length()} bytes"
            )
            val debugConfig = gson.fromJson(configContent, SingBoxConfig::class.java)
            debugConfig.outbounds?.forEach { outbound ->
                logTransportDebug(outbound)
                logVlessDebug(outbound)
                logNaiveDebug(outbound)
            }
            logRouteDebug(debugConfig)
        } catch (e: Exception) {
            logDebug("[DEBUG] Failed to dump config: ${e.message}")
        }
    }

    private fun logDebug(message: String) {
        Log.d(TAG, message)
        logRepo.addLog("INFO [DBG] [Startup] $message")
    }

    private fun logTransportDebug(outbound: com.kunk.singbox.model.Outbound) {
        if (outbound.transport == null) return
        logDebug(
            "[DEBUG] Outbound '${outbound.tag}' type=${outbound.type} transport=${gson.toJson(outbound.transport)}"
        )
    }

    private fun logVlessDebug(outbound: com.kunk.singbox.model.Outbound) {
        if (outbound.type != "vless") return
        logDebug(
            "[DEBUG] VLESS outbound '${outbound.tag}': server=${outbound.server}:${outbound.serverPort}, " +
                "flow=${outbound.flow}, tls=${outbound.tls != null}, packet_encoding=${outbound.packetEncoding}, " +
                "transport_type=${outbound.transport?.type}"
        )
    }

    private fun logNaiveDebug(outbound: com.kunk.singbox.model.Outbound) {
        if (outbound.type != "naive") return
        val host = outbound.headers?.get("Host") ?: outbound.extraHeaders?.get("Host")
        logDebug(
            "[DEBUG] NAIVE outbound '${outbound.tag}': server=${outbound.server}:${outbound.serverPort}, " +
                "network=${outbound.network}, quic=${outbound.quic}, uot=${outbound.udpOverTcp?.enabled}, " +
                "resolver=${outbound.domainResolver?.server}, sni=${outbound.tls?.serverName}, " +
                "insecure=${outbound.tls?.insecure}, host=$host"
        )
    }

    private fun logRouteDebug(config: SingBoxConfig) {
        val route = config.route ?: return
        val interestingRules = route.rules
            ?.filter { !it.ruleSet.isNullOrEmpty() || !it.packageName.isNullOrEmpty() }
            .orEmpty()

        logDebug(
            "[DEBUG] Route final=${route.finalOutbound}, " +
                "rule_set_count=${route.ruleSet?.size ?: 0}, " +
                "interesting_rules=${interestingRules.size}"
        )

        interestingRules.take(12).forEach { rule ->
            logDebug(
                "[DEBUG] Route rule: rule_set=${rule.ruleSet}, " +
                    "package=${rule.packageName}, " +
                    "outbound=${rule.outbound}, action=${rule.action}"
            )
        }
    }

    private suspend fun ensureNetworkCallbackReady(callbacks: Callbacks): Network? {
        val t = SystemClock.elapsedRealtime()
        callbacks.ensureNetworkCallbackReady(1500L)
        val afterCallback = SystemClock.elapsedRealtime()
        log("[parallelInit] ensureNetworkCallbackReady: ${afterCallback - t}ms")
        val network = callbacks.waitForUsablePhysicalNetwork(3000L)
        log(
            "[parallelInit] waitForUsablePhysicalNetwork: " +
                "${SystemClock.elapsedRealtime() - afterCallback}ms, network=$network"
        )
        return network
    }

    private suspend fun ensureRuleSetReady(): Boolean {
        val t = SystemClock.elapsedRealtime()
        val result = runCatching {
            RuleSetRepository.getInstance(context).ensureRuleSetsReady(
                forceUpdate = false,
                allowNetwork = false
            ) { }
        }.getOrDefault(false)
        log("[parallelInit] ruleSetReady: ${SystemClock.elapsedRealtime() - t}ms, ready=$result")
        return result
    }

    private fun ensureLocalNetworkPermission(settings: AppSettings) {
        if (!LocalNetworkPermission.canApplySettings(context, settings)) {
            throw IllegalStateException(LocalNetworkPermission.MISSING_PERMISSION_ERROR)
        }
    }

    private fun patchConfig(
        rawConfigContent: String,
        settings: AppSettings
    ): String {
        var configContent = rawConfigContent
        val logLevel = resolveRuntimeLogLevel(settings.debugLoggingEnabled)

        try {
            val configObj = gson.fromJson(configContent, SingBoxConfig::class.java)

            val logConfig = configObj.log?.copy(level = logLevel)
                ?: com.kunk.singbox.model.LogConfig(level = logLevel, timestamp = true, output = "box.log")

            var newConfig = configObj.copy(log = logConfig)
            val restrictLanListen = LocalNetworkPermission.shouldRestrictLanListen(context)

            if (newConfig.inbounds != null) {
                val newInbounds = newConfig.inbounds.orEmpty().map { inbound ->
                    when {
                        inbound.type == "tun" -> inbound.copy(
                            autoRoute = settings.autoRoute,
                            strictRoute = settings.strictRoute
                        )
                        restrictLanListen -> LocalNetworkPermission.restrictInboundListen(inbound)
                        else -> inbound
                    }
                }
                newConfig = newConfig.copy(inbounds = newInbounds)
            }

            val proxyTypes = setOf(
                "shadowsocks", "vmess", "vless", "trojan",
                "hysteria", "hysteria2", "tuic", "wireguard",
                "ssh", "shadowtls", "socks", "http", "anytls", "naive"
            )
            val defaultConnectTimeout = "5s"

            if (newConfig.outbounds != null) {
                val newOutbounds = newConfig.outbounds.orEmpty().map { outbound ->
                    if (outbound.type in proxyTypes && outbound.connectTimeout == null) {
                        outbound.copy(connectTimeout = defaultConnectTimeout)
                    } else {
                        outbound
                    }
                }
                newConfig = newConfig.copy(outbounds = newOutbounds)
            }

            configContent = gson.toJson(newConfig)
            Log.i(
                TAG,
                "Patched config: auto_route=${settings.autoRoute}, " +
                    "log_level=$logLevel, connect_timeout=$defaultConnectTimeout, " +
                    "restrict_lan_listen=$restrictLanListen"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to patch config: ${e.message}")
        }

        return configContent
    }

    private fun handlePermissionRequired(prepareIntent: Intent, callbacks: Callbacks) {
        Log.w(TAG, "VPN permission required")
        callbacks.persistVpnState(false)
        callbacks.persistVpnPending("")
        VpnStateStore.clearRuntimeState()
        VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
        VpnStateStore.setLastError("VPN permission required")

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
                val notification = NotificationCompat.Builder(context, VpnNotificationManager.CHANNEL_ID)
                    .setContentTitle("VPN Permission Required")
                    .setContentText("Tap to grant VPN permission")
                    .setSmallIcon(R.drawable.ic_notification)
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
            msg.contains(LocalNetworkPermission.MISSING_PERMISSION_ERROR, ignoreCase = true) -> {
                "Start failed: ${LocalNetworkPermission.MISSING_PERMISSION_ERROR}"
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
