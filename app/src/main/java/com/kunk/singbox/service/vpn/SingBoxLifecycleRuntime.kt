@file:Suppress("UnusedImports", "TooManyFunctions", "LongMethod", "LargeClass", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeCons")

package com.kunk.singbox.service

import android.content.Intent
import android.net.ConnectivityManager
import android.os.Process
import android.util.Log
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.repository.*
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.repository.buildServiceLifecycleDiagnostic
import com.kunk.singbox.service.manager.RecoveryPolicy
import com.kunk.singbox.service.manager.RecoveryIntentLease
import com.kunk.singbox.service.manager.ServiceStateHolder
import com.kunk.singbox.service.manager.ShutdownManager
import com.kunk.singbox.service.manager.VpnStopInitiator
import com.kunk.singbox.utils.DefaultNetworkListener
import com.kunk.singbox.utils.NetworkClient
import com.kunk.singbox.utils.VersionInfo
import com.kunk.singbox.utils.perf.PerfTracer
import com.kunk.singbox.utils.perf.readProcessStartedAtEpochMs
import io.nekohasekai.libbox.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private const val START_STICKY = android.app.Service.START_STICKY
private const val START_NOT_STICKY = android.app.Service.START_NOT_STICKY
private const val RECOVERY_CLAIM_WINDOW_MS = SingBoxService.RECOVERY_CLAIM_WINDOW_MS

internal fun SingBoxService.recordServiceLifecycle(
    event: String,
    reason: String,
    recovery: Boolean = false,
    action: String? = null
) {
    LogRepository.getInstance().addAlwaysLog(
        buildServiceLifecycleDiagnostic(
            service = "vpn",
            event = event,
            reason = reason,
            pid = Process.myPid(),
            details = buildString {
                append("process_started_at_epoch_ms=${readProcessStartedAtEpochMs() ?: -1L} ")
                append("app_version_code=${VersionInfo.getAppVersionCode(this@recordServiceLifecycle)} ")
                append("mode=${VpnStateStore.getMode().name} ")
                append("manually_stopped=${VpnStateStore.isManuallyStopped()} recovery=$recovery ")
                append("stop_initiator=${lastStopInitiator.wireValue}")
                action?.takeIf(String::isNotBlank)?.let { append(" action=$it") }
            }
        )
    )
}

internal fun SingBoxService.onCreateRuntime() {

    Log.e(SingBoxService.TAG, "SingBoxService onCreate: pid=${android.os.Process.myPid()} SingBoxService.instance=${System.identityHashCode(this)}")
    SingBoxService.instance = this

    // Restore manually stopped state from persistent storage
    SingBoxService.isManuallyStopped = VpnStateStore.isManuallyStopped()
    Log.i(SingBoxService.TAG, "Restored SingBoxService.isManuallyStopped state: $SingBoxService.isManuallyStopped")
    recordServiceLifecycle(event = "create", reason = "service_on_create")

    notificationManager.createNotificationChannel()
    // 初始化 ConnectivityManager
    connectivityManager = getSystemService(ConnectivityManager::class.java)

    connectivityManager?.let { manager ->
        // 保证 Start 消息先于 onDestroy 的 Stop 入队，避免监听器残留。
        DefaultNetworkListener.start(manager, defaultNetworkListenerKey) {
            markPhysicalNetworkChanged()
        }
    }

    // ===== 初始化新架构 Managers =====
    initManagers()

    serviceScope.launch {
        SingBoxService.lastErrorFlow.collect {
            requestRemoteStateUpdate(force = false)
        }
    }

    // 监听活动节点变化，更新通知
    serviceScope.launch {
        ConfigRepository.getInstance(this@onCreateRuntime).activeNodeId.collect { _ ->
            if (SingBoxService.isRunning) {
                requestNotificationUpdate(force = false)
                requestRemoteStateUpdate(force = false)
            }
        }
    }

    // 监听通知栏速度显示设置变化
    serviceScope.launch {
        SettingsRepository.getInstance(this@onCreateRuntime)
            .settings
            .map { it.showNotificationSpeed }
            .distinctUntilChanged()
            .collect { enabled ->
                showNotificationSpeed = enabled
                if (SingBoxService.isRunning) {
                    requestNotificationUpdate(force = true)
                }
            }
    }

    // ⭐ P0修复3: 注册Activity生命周期回调，检测应用返回前台
    screenStateManager.registerActivityLifecycleCallbacks(application)
}

internal fun SingBoxService.onTrimMemoryRuntime(level: Int) {

    when (level) {
        android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
            screenStateManager.onAppBackground()
        }
    }
}

@Suppress(
    "NestedBlockDepth",
    "LongMethod",
    "CyclomaticComplexMethod",
    "CognitiveComplexMethod",
    "ReturnCount"
)
internal fun SingBoxService.onStartCommandRuntime(intent: Intent?, flags: Int, startId: Int): Int {
    preserveRuntimeStateOnDestroy = false
    val recoveryFlag = intent?.getBooleanExtra(SingBoxService.EXTRA_RECOVERY, false) == true
    Log.i(SingBoxService.TAG, "onStartCommand action=${intent?.action} recovery=$recoveryFlag")
    recordServiceLifecycle(
        event = "start_request",
        reason = if (intent?.action == null) "sticky_restart" else "intent",
        recovery = recoveryFlag,
        action = intent?.action?.substringAfterLast('.')
    )
    if (recoveryFlag) {
        runCatching {
            LogRepository.getInstance().addAlwaysLog(
                "INFO [Recovery] SingBoxService START " +
                    "running=${SingBoxService.isRunning} starting=${SingBoxService.isStarting}"
            )
        }
    }
    if (intent?.action == null) {
        handleStickyRestartIntent()
        return if (SingBoxService.isRunning || SingBoxService.isStarting) {
            START_STICKY
        } else {
            stopSelf(startId)
            START_NOT_STICKY
        }
    }
    when (intent.action) {
        SingBoxService.ACTION_START -> {
            // 恢复 START 幂等：核心已在跑/正在起时只刷新状态，禁止 clean restart
            val isRecoveryStart = intent.getBooleanExtra(SingBoxService.EXTRA_RECOVERY, false)
            if (isRecoveryStart &&
                RecoveryPolicy.shouldIgnoreRecoveryStart(SingBoxService.isRunning, SingBoxService.isStarting)
            ) {
                Log.i(SingBoxService.TAG, "Recovery START ignored: core already active")
                runCatching {
                    LogRepository.getInstance().addAlwaysLog(
                        "INFO [Recovery] START ignored: already active " +
                            "running=${SingBoxService.isRunning} starting=${SingBoxService.isStarting}"
                    )
                }
                VpnStateStore.clearRecoveryClaim()
                requestNotificationUpdate(force = true)
                requestRemoteStateUpdate(force = true)
                return START_STICKY
            }
            if (isRecoveryStart && !isValidRecoveryStart(VpnStateStore.CoreMode.VPN)) {
                rejectStaleRecoveryStart("VPN recovery intent no longer matches persisted intent")
                return START_STICKY
            }
            // recovery 不覆盖 manuallyStopped：意图由 claim 前判定，失败路径也不应洗成"未手动停"
            if (!isRecoveryStart) {
                SingBoxService.isManuallyStopped = false
                VpnStateStore.setManuallyStopped(false)
                lastStopInitiator = VpnStopInitiator.UNKNOWN
            }
            val recoveryLease = setNonResourceRecoveryIntent(isRecoveryStart)
            VpnTileService.persistVpnPending("starting")

            val configPath = intent.getStringExtra(SingBoxService.EXTRA_CONFIG_PATH)
            val pendingNode = intent.getStringExtra(SingBoxService.EXTRA_PENDING_NODE_NAME)
            pendingNodeName = pendingNode?.takeIf { it.isNotBlank() }
            val cleanCache = intent.getBooleanExtra(SingBoxService.EXTRA_CLEAN_CACHE, false)

            // P0 Optimization: If config path is missing (Shortcut/Headless), generate it inside Service
            if (configPath == null) {
                Log.i(SingBoxService.TAG, "SingBoxService.ACTION_START received without config path, generating config...")
                serviceScope.launch {
                    try {
                        val repo = ConfigRepository.getInstance(applicationContext)
                        val result = repo.generateConfigFile()
                        if (result != null) {
                            Log.i(SingBoxService.TAG, "Config generated successfully: ${result.path}")
                            // Recursively call start command with the generated path
                            val newIntent = Intent(applicationContext, SingBoxService::class.java).apply {
                                action = SingBoxService.ACTION_START
                                putExtra(SingBoxService.EXTRA_CONFIG_PATH, result.path)
                                putExtra(SingBoxService.EXTRA_CLEAN_CACHE, cleanCache)
                                putExtra(SingBoxService.EXTRA_RECOVERY, isRecoveryStart)
                                pendingNodeName?.let {
                                    putExtra(SingBoxService.EXTRA_PENDING_NODE_NAME, it)
                                }
                            }
                            synchronized(this@onStartCommandRuntime) {
                                if (ServiceStateHolder.isRecoveryIntentCurrent(recoveryLease)) {
                                    startService(newIntent)
                                }
                            }
                        } else {
                            Log.e(SingBoxService.TAG, "Failed to generate config file")
                            withContext(Dispatchers.Main) {
                                if (clearStartCommandFailureState(recoveryLease) {
                                        SingBoxService.setLastError("Failed to generate config file")
                                    }
                                ) {
                                    stopSelf()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(SingBoxService.TAG, "Error generating config in Service", e)
                        withContext(Dispatchers.Main) {
                            if (clearStartCommandFailureState(recoveryLease) {
                                    SingBoxService.setLastError("Error generating config: ${e.message}")
                                }
                            ) {
                                stopSelf()
                            }
                        }
                    }
                }
                return START_STICKY
            }

            // 同配置重复 START 幂等：已在跑且配置未变（且未要求清缓存）时只刷新状态，禁止 clean restart
            if (SingBoxService.isRunning &&
                !cleanCache &&
                configPath == SingBoxService.lastConfigPath
            ) {
                Log.i(SingBoxService.TAG, "Duplicate START ignored: same config already running")
                if (!completeRecoveryIntentOnSuccess(recoveryLease)) return START_STICKY
                runCatching {
                    LogRepository.getInstance().addAlwaysLog(
                        "INFO [Recovery] Duplicate START ignored: same config already running " +
                            "recovery=$isRecoveryStart path=$configPath"
                    )
                }
                VpnTileService.persistVpnPending("")
                requestNotificationUpdate(force = true)
                requestRemoteStateUpdate(force = true)
                return START_STICKY
            }

            initializeStartupNodeLabel(configPath)
            updateServiceState(ServiceState.STARTING)
            synchronized(this) {
                // FIX: Ensure pendingCleanCache is set from intent even for cold start
                if (cleanCache) pendingCleanCache = true

                if (SingBoxService.isStarting) {
                    pendingStartConfigPath = configPath
                    pendingStartRecoveryIntentLease = recoveryLease
                    stopSelfRequested = false
                    hardStopRecoveryIntentLease = null
                    SingBoxService.lastConfigPath = configPath
                    // Return STICKY to allow system to restart VPN if killed due to memory pressure
                    return START_STICKY
                }
                if (isStopping) {
                    pendingStartConfigPath = configPath
                    pendingStartRecoveryIntentLease = recoveryLease
                    stopSelfRequested = false
                    hardStopRecoveryIntentLease = null
                    SingBoxService.lastConfigPath = configPath
                    // Return STICKY to allow system to restart VPN if killed due to memory pressure
                    return START_STICKY
                }
                // If already running, do a clean restart to avoid half-broken tunnel state
                if (SingBoxService.isRunning) {
                    pendingStartConfigPath = configPath
                    pendingStartRecoveryIntentLease = recoveryLease
                    stopSelfRequested = false
                    hardStopRecoveryIntentLease = null
                    SingBoxService.lastConfigPath = configPath
                }
            }
            if (SingBoxService.isRunning) {
                val activeLabel = pendingNodeName ?: runCatching {
                    val repo = ConfigRepository.getInstance(applicationContext)
                    val profileId = repo.activeProfileId.value
                    if (repo.isProfileAutoSelectionEnabled(profileId)) {
                        null
                    } else {
                        val nodeId = repo.activeNodeId.value
                        repo.nodes.value.find { it.id == nodeId }?.name
                    }
                }.getOrNull()
                realTimeNodeName = null
                if (!activeLabel.isNullOrBlank()) {
                    VpnStateStore.setActiveLabel(activeLabel)
                }
                requestNotificationUpdate(force = true)
                requestRemoteStateUpdate(force = true)
                // 只有当需要更改核心配置（如路由规则、DNS 等）时才重启
                stopVpn(stopService = false)
            } else {
                startVpn(configPath)
            }
        }
        SingBoxService.ACTION_STOP -> {
            if (ServiceStateHolder.shouldIgnoreDuplicateHardStop(isStopping, stopSelfRequested)) {
                Log.i(SingBoxService.TAG, "Ignoring duplicate ACTION_STOP while cleanup is already running")
                return START_NOT_STICKY
            }
            val stopInitiator = VpnStopInitiator.fromWireValue(
                intent.getStringExtra(SingBoxService.EXTRA_STOP_INITIATOR)
            )
            lastStopInitiator = stopInitiator
            Log.i(
                SingBoxService.TAG,
                "Received SingBoxService.ACTION_STOP initiator=${stopInitiator.wireValue} -> stopping VPN"
            )
            SingBoxService.isManuallyStopped = stopInitiator.isManualStop
            VpnStateStore.setManuallyStopped(stopInitiator.isManualStop)
            recordServiceLifecycle(
                event = "stop_request",
                reason = if (stopInitiator.isManualStop) "manual_stop" else "automatic_stop",
                action = SingBoxService.ACTION_STOP.substringAfterLast('.')
            )
            val recoveryLease = setNonResourceRecoveryIntent(false)
            VpnTileService.persistVpnPending("stopping")
            updateServiceState(ServiceState.STOPPING)
            notificationManager.setSuppressUpdates(true)
            notificationManager.cancelNotification()
            synchronized(this) {
                pendingStartConfigPath = null
                pendingStartRecoveryIntentLease = null
                pendingPerAppPolicyRevision = 0L
                pendingAppRouteRequestId = ""
                pendingConfigDigest = ""
                pendingAppRoutingDigest = ""
            }
            stopVpn(stopService = true, recoveryIntentLease = recoveryLease)
            return START_NOT_STICKY
        }
        SingBoxService.ACTION_FORCE_STOP -> {
            forceStopProcess("explicit_force_stop")
        }
        SingBoxService.ACTION_SWITCH_NODE -> {
            Log.i(SingBoxService.TAG, "Received SingBoxService.ACTION_SWITCH_NODE -> switching node")
            val targetNodeId = intent.getStringExtra("node_id")
            val outboundTag = intent.getStringExtra("outbound_tag")
            val targetNodeName = intent.getStringExtra(SingBoxService.EXTRA_PENDING_NODE_NAME)
            runCatching {
                LogRepository.getInstance().addLog(
                    "INFO SingBoxService: SingBoxService.ACTION_SWITCH_NODE nodeId=${targetNodeId.orEmpty()} " +
                        "outboundTag=${outboundTag.orEmpty()} targetNodeName=${targetNodeName.orEmpty()}"
                )
            }
            // Remember latest config path for fallback restart if hot switch doesn't apply.
            val fallbackConfigPath = intent.getStringExtra(SingBoxService.EXTRA_CONFIG_PATH)
            if (!fallbackConfigPath.isNullOrBlank()) {
                synchronized(this) {
                    pendingHotSwitchFallbackConfigPath = fallbackConfigPath
                }
                runCatching {
                    LogRepository.getInstance().addLog("INFO SingBoxService: SWITCH_NODE fallback configPath=$fallbackConfigPath")
                }
            }
            if (targetNodeId != null) {
                nodeSwitchManager.performHotSwitch(
                    nodeId = targetNodeId,
                    outboundTag = outboundTag,
                    targetNodeName = targetNodeName
                )
            } else {
                nodeSwitchManager.switchNextNode(
                    serviceClass = SingBoxService::class.java,
                    actionStart = SingBoxService.ACTION_START,
                    extraConfigPath = SingBoxService.EXTRA_CONFIG_PATH
                )
            }
        }
        SingBoxService.ACTION_UPDATE_SETTING -> {
            val key = intent.getStringExtra(SingBoxService.EXTRA_SETTING_KEY)
            if (key == "show_notification_speed") {
                val value = intent.getBooleanExtra(SingBoxService.EXTRA_SETTING_VALUE_BOOL, true)
                Log.i(SingBoxService.TAG, "Received setting update: $key = $value")
                showNotificationSpeed = value
                if (SingBoxService.isRunning) {
                    requestNotificationUpdate(force = true)
                }
            }
        }
        SingBoxService.ACTION_PREPARE_RESTART -> {
            val reason = intent.getStringExtra(SingBoxService.EXTRA_PREPARE_RESTART_REASON).orEmpty()
            Log.i(SingBoxService.TAG, "Received SingBoxService.ACTION_PREPARE_RESTART (reason='$reason') -> preparing for VPN restart")
            performPrepareRestart()
        }
        SingBoxService.ACTION_HOT_RELOAD -> {
            // ⭐ 2025-fix: 内核级热重载
            // 在 VPN 运行时重载配置，不销毁 VPN 服务
            Log.i(SingBoxService.TAG, "Received SingBoxService.ACTION_HOT_RELOAD -> performing hot reload")
            val configContent = intent.getStringExtra(SingBoxService.EXTRA_CONFIG_CONTENT)
            if (configContent.isNullOrEmpty()) {
                Log.e(SingBoxService.TAG, "SingBoxService.ACTION_HOT_RELOAD: config content is empty")
            } else {
                performHotReload(configContent)
            }
        }
        SingBoxService.ACTION_FULL_RESTART -> {
            val isPerAppRuleRestart = intent.getBooleanExtra(
                SingBoxService.EXTRA_PER_APP_RULE_RESTART,
                false
            )
            val expectedPerAppRevision = intent.getLongExtra(
                SingBoxService.EXTRA_PER_APP_POLICY_REVISION,
                0L
            ).coerceAtLeast(0L)
            val manuallyStopped = isPerAppRuleRestart && VpnStateStore.isManuallyStopped()
            val mode = if (isPerAppRuleRestart) {
                VpnStateStore.getMode()
            } else {
                VpnStateStore.CoreMode.VPN
            }
            val shouldRejectPerAppRuleRestart = isPerAppRuleRestart && (
                !SingBoxService.isRunning ||
                    isStopping ||
                    manuallyStopped ||
                    mode != VpnStateStore.CoreMode.VPN
                )
            if (shouldRejectPerAppRuleRestart) {
                Log.w(
                    SingBoxService.TAG,
                    "Per-app rule FULL_RESTART rejected: " +
                        "running=${SingBoxService.isRunning}, starting=${SingBoxService.isStarting}, " +
                        "stopping=$isStopping, manuallyStopped=$manuallyStopped, mode=$mode"
                )
                val shouldStopIdleService = !SingBoxService.isRunning &&
                    !SingBoxService.isStarting &&
                    !isStopping
                if (shouldStopIdleService) {
                    preserveRuntimeStateOnDestroy = true
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
            Log.i(SingBoxService.TAG, "Received SingBoxService.ACTION_FULL_RESTART -> performing full restart (TUN rebuild)")
            val configPath = intent.getStringExtra(SingBoxService.EXTRA_CONFIG_PATH)
            if (configPath.isNullOrEmpty()) {
                PerfTracer.recordEvent(PerfTracer.Phases.FULL_RESTART, "missing_config")
                Log.e(SingBoxService.TAG, "SingBoxService.ACTION_FULL_RESTART: config path is empty")
            } else {
                val expectedConfigDigest = intent.getStringExtra(
                    ServiceStateHolder.EXTRA_CONFIG_DIGEST
                ).orEmpty()
                if (expectedConfigDigest.isNotBlank()) {
                    val actualConfigDigest = runCatching {
                        ConfigRepository.sha256(File(configPath).readText(Charsets.UTF_8))
                    }.getOrNull()
                    if (actualConfigDigest != expectedConfigDigest) {
                        Log.e(SingBoxService.TAG, "Per-app candidate config digest mismatch")
                        VpnStateStore.setLastError("应用分流候选配置校验失败，已阻止重启")
                        return START_STICKY
                    }
                }
                PerfTracer.recordEvent(PerfTracer.Phases.FULL_RESTART, "requested")
                val recoveryLease = setNonResourceRecoveryIntent(false)
                synchronized(this) {
                    pendingPerAppPolicyRevision = if (isPerAppRuleRestart) expectedPerAppRevision else 0L
                    pendingAppRouteRequestId = intent.getStringExtra(
                        ServiceStateHolder.EXTRA_APP_ROUTE_REQUEST_ID
                    ).orEmpty()
                    pendingConfigDigest = intent.getStringExtra(
                        ServiceStateHolder.EXTRA_CONFIG_DIGEST
                    ).orEmpty()
                    pendingAppRoutingDigest = intent.getStringExtra(
                        ServiceStateHolder.EXTRA_APP_ROUTING_DIGEST
                    ).orEmpty()
                    pendingStartConfigPath = configPath
                    pendingStartRecoveryIntentLease = recoveryLease
                    stopSelfRequested = false
                    hardStopRecoveryIntentLease = null
                    SingBoxService.lastConfigPath = configPath
                }
                stopVpn(
                    stopService = false,
                    recoveryIntentLease = recoveryLease,
                    shutdownKind = ShutdownManager.ShutdownKind.TUN_REBUILD
                )
            }
        }
        SingBoxService.ACTION_RESET_CONNECTIONS -> {
            Log.i(SingBoxService.TAG, "Received SingBoxService.ACTION_RESET_CONNECTIONS -> user requested connection reset")
            if (SingBoxService.isRunning) {
                serviceScope.launch {
                    val closed = commandManager.closeConnections()
                    val reset = BoxWrapperManager.resetNetwork()
                    runCatching {
                        LogRepository.getInstance().addLog(
                            "INFO: User triggered connection reset via notification " +
                                "closed=$closed resetNetwork=$reset"
                        )
                    }
                }
            }
        }
    }
    // Use START_STICKY to allow system auto-restart if killed due to memory pressure
    // This prevents "VPN mysteriously stops" issue on Android 14+
    // System will restart service with null intent, we handle it gracefully above
    return START_STICKY
}

internal fun SingBoxService.isValidRecoveryStart(expectedMode: VpnStateStore.CoreMode): Boolean {
    return !VpnStateStore.isManuallyStopped() && VpnStateStore.getMode() == expectedMode
}

internal fun SingBoxService.rejectStaleRecoveryStart(reason: String) {
    Log.w(SingBoxService.TAG, reason)
    runCatching {
        LogRepository.getInstance().addAlwaysLog("INFO [Recovery] START rejected: $reason")
    }
    VpnTileService.persistVpnPending("")
    VpnStateStore.clearRecoveryClaim()
}

internal fun SingBoxService.handleStickyRestartIntent() {
    if (SingBoxService.isRunning || SingBoxService.isStarting) {
        Log.i(SingBoxService.TAG, "Sticky restart ignored: service is already running or starting")
        return
    }

    val runningConfigFile = File(filesDir, "running_config.json")
    val runningConfigUsable = SingBoxService.isRunningConfigUsable(runningConfigFile)
    val mode = VpnStateStore.getMode()
    val manuallyStopped = VpnStateStore.isManuallyStopped()
    if (!RecoveryPolicy.shouldRecoverFromStickyRestart(manuallyStopped, mode, runningConfigUsable)) {
        Log.i(
            SingBoxService.TAG,
            "Sticky restart skipped: manuallyStopped=$manuallyStopped, mode=$mode, " +
                "runningConfigUsable=$runningConfigUsable"
        )
        return
    }
    if (!VpnStateStore.tryClaimRecovery(RECOVERY_CLAIM_WINDOW_MS)) {
        Log.i(SingBoxService.TAG, "Sticky restart skipped: another recovery was issued recently")
        return
    }

    // 只冷恢复：running_config 原样拉起，不 CLEAN_CACHE、不重新生成配置、不覆盖 manuallyStopped
    Log.w(SingBoxService.TAG, "Sticky restart recovering VPN from ${runningConfigFile.absolutePath}")
    runCatching {
        LogRepository.getInstance().addAlwaysLog(
            "INFO [Recovery] Sticky restart recovering path=${runningConfigFile.absolutePath} " +
                "mode=$mode manuallyStopped=$manuallyStopped"
        )
    }
    setNonResourceRecoveryIntent(true)
    VpnTileService.persistVpnPending("starting")
    initializeStartupNodeLabel(runningConfigFile.absolutePath, explicitTag = null)
    updateServiceState(ServiceState.STARTING)
    startVpn(runningConfigFile.absolutePath)
}

internal fun SingBoxService.recordStartFailureIfCurrent(recoveryLease: RecoveryIntentLease, error: String) {
    synchronized(this) {
        if (ServiceStateHolder.isRecoveryIntentCurrent(recoveryLease)) {
            startupCallbacks.onFailed(error)
        }
    }
}

internal fun SingBoxService.clearStartCommandFailureState(
    recoveryLease: RecoveryIntentLease,
    beforeCleanup: (() -> Unit)? = null
): Boolean = synchronized(this) {
    val consumedIntent = ServiceStateHolder.consumeRecoveryIntentOnFailure(recoveryLease)
    if (consumedIntent == null) {
        Log.w(SingBoxService.TAG, "Startup failure ignored for superseded recovery lease")
        return@synchronized false
    }
    beforeCleanup?.invoke()
    val preserveMode = RecoveryPolicy.shouldPreserveModeOnStartFailure(consumedIntent)
    synchronized(this) {
        if (pendingRecoveryIntentLease === recoveryLease) pendingRecoveryIntentLease = null
    }
    synchronized(this) {
        SingBoxService.isRunning = false
        SingBoxService.isStarting = false
        isStopping = false
        pendingStartConfigPath = null
        pendingStartRecoveryIntentLease = null
        pendingCleanCache = false
        stopSelfRequested = false
        hardStopRecoveryIntentLease = null
    }
    NetworkClient.onVpnStateChanged(false)
    VpnTileService.persistVpnState(false)
    if (preserveMode) {
        // 恢复失败：只清 runtime，保留 mode，留给 keepalive/冷启动再试
        VpnStateStore.clearRuntimeState(preserveLastError = true)
    } else {
        VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
    }
    VpnTileService.persistVpnPending("")
    // 启动失败立即释放恢复互斥，让后续触发源按当时意图重新判定，而不是干等窗口过期
    VpnStateStore.clearRecoveryClaim()
    updateServiceState(ServiceState.STOPPED)
    updateTileState()
    true
}

/**
 * 执行预清理操作
 */
