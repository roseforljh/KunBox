@file:Suppress("UnusedImports", "TooManyFunctions", "LongMethod", "LargeClass", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeCons")

package com.kunk.singbox.service

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Network
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.Log
import com.kunk.singbox.R
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.ipc.SingBoxIpcHub
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.repository.*
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.MeteredNodeConfigGuard
import com.kunk.singbox.repository.RuleSetRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.service.manager.RecoveryIntentLease
import com.kunk.singbox.service.manager.ServiceStateHolder
import com.kunk.singbox.service.manager.ShutdownManager
import com.kunk.singbox.service.manager.VpnStopInitiator
import com.kunk.singbox.service.notification.VpnNotificationManager
import com.kunk.singbox.ui.components.AppNotificationManager
import com.kunk.singbox.utils.DefaultNetworkListener
import com.kunk.singbox.utils.LocalNetworkPermission
import com.kunk.singbox.utils.perf.PerfTracer
import com.kunk.singbox.utils.perf.BackgroundResourceGuard
import io.nekohasekai.libbox.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val STOP_FOREGROUND_REMOVE = android.app.Service.STOP_FOREGROUND_REMOVE

internal fun SingBoxService.performPrepareRestart() {
    if (!SingBoxService.isRunning) {
        Log.w(SingBoxService.TAG, "performPrepareRestart: VPN not running, skip")
        return
    }

    val now = SystemClock.elapsedRealtime()
    val last = lastPrepareRestartAtMs.get()
    val elapsed = now - last
    if (elapsed < prepareRestartDebounceMs) {
        Log.d(SingBoxService.TAG, "performPrepareRestart: skipped (debounce, elapsed=${elapsed}ms)")
        return
    }
    lastPrepareRestartAtMs.set(now)

    serviceScope.launch {
        try {
            Log.i(SingBoxService.TAG, "[PrepareRestart] Step 1/3: Wake up core")
            coreManager.wakeService()

            Log.i(SingBoxService.TAG, "[PrepareRestart] Step 2/3: Disconnect underlying network")
            setUnderlyingNetworks(null)

            // Step 3: 等待应用收到广播
            // 不需要太长时间，因为VPN重启本身也需要时间
            Log.i(SingBoxService.TAG, "[PrepareRestart] Step 3/3: Waiting for apps to process network change...")
            delay(100)

            // 注意：不需要调用 closeAllConnectionsImmediate()
            // 因为 VPN 重启时服务关闭会强制关闭所有连接

            Log.i(SingBoxService.TAG, "[PrepareRestart] Complete - apps should now detect network interruption")
        } catch (e: Exception) {
            Log.e(SingBoxService.TAG, "performPrepareRestart error", e)
        }
    }
}

/**
 * 执行内核级热重载
 * 在 VPN 运行时重载配置，不销毁 VPN 服务
 * 失败时 Toast 报错并关闭 VPN，让用户手动重新打开
 */

internal fun SingBoxService.prepareRuntimeConfigForLocalNetwork(configContent: String, settings: AppSettings): String {
    if (!LocalNetworkPermission.canApplySettings(applicationContext, settings)) {
        throw IllegalStateException(LocalNetworkPermission.MISSING_PERMISSION_ERROR)
    }
    if (!LocalNetworkPermission.shouldRestrictLanListen(applicationContext)) return configContent

    return runCatching {
        val config = gson.fromJson(configContent, SingBoxConfig::class.java)
        val inbounds = config.inbounds ?: return configContent
        var changed = false
        val restrictedInbounds = inbounds.map { inbound ->
            val restrictedInbound = LocalNetworkPermission.restrictInboundListen(inbound)
            if (restrictedInbound != inbound) {
                changed = true
            }
            restrictedInbound
        }
        if (changed) {
            Log.i(SingBoxService.TAG, "Restricted hot reload inbound listen to loopback")
            gson.toJson(config.copy(inbounds = restrictedInbounds))
        } else {
            configContent
        }
    }.getOrElse { e ->
        Log.w(SingBoxService.TAG, "Failed to restrict hot reload local network listen: ${e.message}")
        configContent
    }
}

@Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod", "LongMethod", "ReturnCount")
internal fun SingBoxService.performHotReload(configContent: String) {
    synchronized(this) {
        if (!SingBoxService.isRunning || isStopping) {
            Log.w(SingBoxService.TAG, "performHotReload: VPN not running or stopping, skip")
            return
        }
        val startToken = coreManager.captureStartToken()
        if (startToken == null) {
            Log.w(SingBoxService.TAG, "performHotReload: lifecycle token unavailable")
            return
        }
        hotReloadJob?.cancel()

        val job = serviceScope.launch {
            val startedAtMs = SystemClock.elapsedRealtime()
            try {
                Log.i(SingBoxService.TAG, "[HotReload] Starting kernel-level hot reload...")
                LogRepository.getInstance().addAlwaysLog("INFO [HotReload] phase=request source=service")

                MeteredNodeConfigGuard.requireRuntimeConfigAuthorized(
                    configContent = configContent,
                    selectedNodeId = VpnStateStore.getSelectedNodeId()
                )

                val settingsRepository = SettingsRepository.getInstance(applicationContext)
                settingsRepository.reloadFromStorage()
                val settings = settingsRepository.settings.first()
                coreManager.setCurrentSettings(settings)
                val runtimeConfigContent = prepareRuntimeConfigForLocalNetwork(configContent, settings)

                val result = coreManager.hotReloadConfig(runtimeConfigContent, startToken)

                result.onSuccess { success ->
                    if (success) {
                        PerfTracer.recordDuration(
                            PerfTracer.Phases.HOT_RELOAD,
                            SystemClock.elapsedRealtime() - startedAtMs,
                            "success"
                        )
                        Log.i(SingBoxService.TAG, "[HotReload] Kernel hot reload succeeded")
                        LogRepository.getInstance().addAlwaysLog(
                            "INFO [HotReload] phase=complete outcome=success " +
                                "duration_ms=${SystemClock.elapsedRealtime() - startedAtMs}"
                        )

                        commandManager.getCommandServer()?.let { server ->
                            BoxWrapperManager.init(server)
                        }

                        requestNotificationUpdate(force = true)
                    } else if (!isStopping && SingBoxService.isRunning) {
                        PerfTracer.recordDuration(
                            PerfTracer.Phases.HOT_RELOAD,
                            SystemClock.elapsedRealtime() - startedAtMs,
                            "kernel_error"
                        )
                        handleHotReloadFailure("Kernel hot reload not available")
                    }
                }.onFailure { e ->
                    if (!isStopping && SingBoxService.isRunning) {
                        PerfTracer.recordDuration(
                            PerfTracer.Phases.HOT_RELOAD,
                            SystemClock.elapsedRealtime() - startedAtMs,
                            "exception"
                        )
                        handleHotReloadFailure("Hot reload failed: ${e.message}")
                    }
                }
            } catch (e: CancellationException) {
                PerfTracer.recordDuration(
                    PerfTracer.Phases.HOT_RELOAD,
                    SystemClock.elapsedRealtime() - startedAtMs,
                    "cancelled"
                )
                Log.i(SingBoxService.TAG, "performHotReload cancelled")
                LogRepository.getInstance().addAlwaysLog(
                    "INFO [HotReload] phase=complete outcome=cancelled " +
                        "duration_ms=${SystemClock.elapsedRealtime() - startedAtMs}"
                )
                throw e
            } catch (e: Exception) {
                PerfTracer.recordDuration(
                    PerfTracer.Phases.HOT_RELOAD,
                    SystemClock.elapsedRealtime() - startedAtMs,
                    "exception"
                )
                Log.e(SingBoxService.TAG, "performHotReload error", e)
                if (!isStopping && SingBoxService.isRunning) {
                    handleHotReloadFailure("Hot reload error: ${e.message}")
                }
            }
        }
        hotReloadJob = job
        job.invokeOnCompletion {
            synchronized(this@performHotReload) {
                if (hotReloadJob === job) hotReloadJob = null
            }
        }
    }
}

internal fun SingBoxService.handleHotReloadFailure(errorMsg: String) {
    Log.e(SingBoxService.TAG, "[HotReload] $errorMsg, stopping VPN")
    LogRepository.getInstance().addAlwaysLog("ERROR [HotReload] phase=complete outcome=failed reason=$errorMsg")

    serviceScope.launch(Dispatchers.Main) {
        AppNotificationManager.showMessage(
            context = applicationContext,
            message = errorMsg,
            duration = androidx.compose.material3.SnackbarDuration.Long
        )
    }

    SingBoxService.isManuallyStopped = false
    val recoveryLease = setNonResourceRecoveryIntent(false)
    stopVpn(stopService = true, recoveryIntentLease = recoveryLease)
}

internal fun SingBoxService.performFullRestart(configPath: String) {
    performFullRestart(configPath, setNonResourceRecoveryIntent(false))
}

internal fun SingBoxService.performFullRestart(
    configPath: String,
    recoveryIntentLease: RecoveryIntentLease
): Boolean {
    val resourceRecoveryAttemptId = recoveryIntentLease.attemptId
    if (resourceRecoveryAttemptId == null) {
        cancelResourceGuard()
    }
    val startDirectly = synchronized(this) {
        when {
            pendingRecoveryIntentLease !== recoveryIntentLease -> null
            !ServiceStateHolder.isRecoveryIntentCurrent(recoveryIntentLease) -> null
            !isResourceRecoveryRestartAllowedLocked(recoveryIntentLease) -> null
            !SingBoxService.isRunning && !SingBoxService.isStarting && !isStopping -> true
            else -> {
                pendingStartConfigPath = configPath
                pendingStartRecoveryIntentLease = recoveryIntentLease
                stopSelfRequested = false
                hardStopRecoveryIntentLease = null
                SingBoxService.lastConfigPath = configPath
                false
            }
        }
    } ?: return false

    if (startDirectly) {
        Log.i(SingBoxService.TAG, "performFullRestart: VPN inactive, starting directly")
        startVpn(configPath, recoveryIntentLease)
    } else {
        Log.i(SingBoxService.TAG, "performFullRestart: queued restart after unified cleanup")
        stopVpn(stopService = false, recoveryIntentLease = recoveryIntentLease)
    }
    return true
}

internal fun SingBoxService.isResourceRecoveryRestartAllowedLocked(recoveryIntentLease: RecoveryIntentLease): Boolean = when {
    recoveryIntentLease.attemptId == null -> true
    recoveryIntentLease.ownerId !== resourceGuardOwnerId -> false
    VpnStateStore.isManuallyStopped() -> false
    VpnStateStore.getMode() != VpnStateStore.CoreMode.VPN -> false
    else -> true
}

/**
 *
 * 直接调用 Go 层 StartOrReloadService，阻塞等待结果
 *
 * 这里使用 runBlocking 是因为 AIDL 接口不支持挂起函数，
 * 调用来自 VPN 进程的 Binder 线程池，使用 Dispatchers.IO 避免阻塞调用线程
 *
 * @return true=成功, false=失败
 */

internal fun SingBoxService.performHotReloadSyncRuntime(configContent: String): Boolean {
    if (!SingBoxService.isRunning || isStopping) {
        Log.w(SingBoxService.TAG, "performHotReloadSync: VPN not running or stopping")
        return false
    }
    val startToken = coreManager.captureStartToken() ?: return false
    val startedAtMs = SystemClock.elapsedRealtime()

    return try {
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            Log.i(SingBoxService.TAG, "[HotReload-Sync] Starting kernel-level hot reload...")
            LogRepository.getInstance().addAlwaysLog("INFO [HotReload] phase=request source=ipc")

            MeteredNodeConfigGuard.requireRuntimeConfigAuthorized(
                configContent = configContent,
                selectedNodeId = VpnStateStore.getSelectedNodeId()
            )

            val settingsRepository = SettingsRepository.getInstance(applicationContext)
            settingsRepository.reloadFromStorage()
            val settings = settingsRepository.settings.first()
            coreManager.setCurrentSettings(settings)
            val runtimeConfigContent = prepareRuntimeConfigForLocalNetwork(configContent, settings)

            val result = coreManager.hotReloadConfig(runtimeConfigContent, startToken)

            val success = result.getOrNull() == true
            if (success) {
                Log.i(SingBoxService.TAG, "[HotReload-Sync] Kernel hot reload succeeded")
                LogRepository.getInstance().addAlwaysLog(
                    "INFO [HotReload] phase=complete outcome=success source=ipc " +
                        "duration_ms=${SystemClock.elapsedRealtime() - startedAtMs}"
                )

                commandManager.getCommandServer()?.let { server ->
                    BoxWrapperManager.init(server)
                }

                requestNotificationUpdate(force = true)
                requestRemoteStateUpdate(force = true)
            } else {
                val reason = result.exceptionOrNull()?.message ?: "kernel_returned_false"
                LogRepository.getInstance().addAlwaysLog(
                    "ERROR [HotReload] phase=complete outcome=failed source=ipc " +
                        "duration_ms=${SystemClock.elapsedRealtime() - startedAtMs} reason=$reason"
                )
            }
            success
        }
    } catch (e: CancellationException) {
        LogRepository.getInstance().addAlwaysLog(
            "INFO [HotReload] phase=complete outcome=cancelled source=ipc " +
                "duration_ms=${SystemClock.elapsedRealtime() - startedAtMs}"
        )
        throw e
    } catch (e: Exception) {
        Log.e(SingBoxService.TAG, "performHotReloadSync error", e)
        LogRepository.getInstance().addAlwaysLog(
            "ERROR [HotReload] phase=complete outcome=failed source=ipc " +
                "duration_ms=${SystemClock.elapsedRealtime() - startedAtMs} reason=${e.message.orEmpty()}"
        )
        false
    }
}

/**
 *
 * 原方法 ~430 行，现在简化为 ~90 行
 */

@Suppress("LongMethod", "CognitiveComplexMethod", "CyclomaticComplexMethod", "ReturnCount")
internal fun SingBoxService.startVpn(
    configPath: String,
    requestedRecoveryIntentLease: RecoveryIntentLease? = null
) {
    // 状态检查（保留在 Service 中，因为涉及多线程同步）
    val (startToken, recoveryLease) = synchronized(this) {
        val capturedRecoveryLease = requestedRecoveryIntentLease
            ?: pendingRecoveryIntentLease
            ?: ServiceStateHolder.setRecoveryIntentOnFailure(false).also { pendingRecoveryIntentLease = it }
        if (requestedRecoveryIntentLease != null &&
            !ServiceStateHolder.isRecoveryIntentCurrent(requestedRecoveryIntentLease)
        ) {
            Log.w(SingBoxService.TAG, "VPN start ignored for superseded recovery lease")
            return
        }
        if (SingBoxService.isManuallyStopped) {
            Log.w(SingBoxService.TAG, "VPN was manually stopped, reject stale start request")
            return
        }
        if (SingBoxService.isRunning) {
            Log.w(SingBoxService.TAG, "VPN already running, ignore start request")
            return
        }
        if (SingBoxService.isStarting) {
            Log.w(SingBoxService.TAG, "VPN is already in starting process, ignore start request")
            return
        }
        if (isStopping) {
            Log.w(SingBoxService.TAG, "VPN is stopping, queue start request")
            pendingStartConfigPath = configPath
            pendingStartRecoveryIntentLease = capturedRecoveryLease
            stopSelfRequested = false
            hardStopRecoveryIntentLease = null
            SingBoxService.lastConfigPath = configPath
            return
        }
        val token = coreManager.captureStartToken()
        if (token == null) {
            Log.w(SingBoxService.TAG, "VPN lifecycle is stopping, reject stale start request")
            return
        }
        SingBoxService.isStarting = true
        token to capturedRecoveryLease
    }

    SingBoxService.lastConfigPath = configPath
    initializeStartupNodeLabel(configPath)

    // 启动前台通知（必须在协程前调用）
    var foregroundStarted = false
    try {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                VpnNotificationManager.NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(VpnNotificationManager.NOTIFICATION_ID, notification)
        }
        notificationManager.markForegroundStarted()
        foregroundStarted = true
    } catch (e: Exception) {
        Log.e(SingBoxService.TAG, "Failed to call startForeground", e)
    }
    if (!SingBoxService.shouldContinueCoreStartAfterForegroundResult(foregroundStarted)) {
        val recoveryAttemptId = recoveryLease.attemptId
        if (recoveryAttemptId == null) {
            if (clearStartCommandFailureState(recoveryLease) {
                    SingBoxService.setLastError("Failed to start foreground service")
                }
            ) {
                stopSelf()
            }
        } else {
            serviceScope.launch {
                BackgroundResourceGuard.failSuccessorAndAwait(resourceGuardOwnerId, recoveryAttemptId)
                withContext(Dispatchers.Main) {
                    if (clearStartCommandFailureState(recoveryLease) {
                            SingBoxService.setLastError("Failed to start foreground service")
                        }
                    ) {
                        stopSelf()
                    }
                }
            }
        }
    } else {
        continueStartVpnAfterForeground(configPath, startToken, recoveryLease)
    }
}

@Suppress("LongMethod")
internal fun SingBoxService.continueStartVpnAfterForeground(
    configPath: String,
    startToken: Long,
    recoveryLease: RecoveryIntentLease
) {
    // 获取清理缓存标志
    val cleanCache = synchronized(this) {
        val c = pendingCleanCache
        pendingCleanCache = false
        c
    }

    // 委托给 StartupManager
    startVpnJob?.cancel()
    startVpnJob = serviceScope.launch {
        val result = startupManager.startVpn(
            configPath = configPath,
            cleanCache = cleanCache,
            startToken = startToken,
            recoveryIntentLease = recoveryLease,
            coreManager = coreManager,
            callbacks = startupCallbacks
        )

        when (result) {
            is com.kunk.singbox.service.manager.StartupManager.StartResult.Success -> {
                // 成功状态已在 exact lease 门内一次性发布。
            }
            is com.kunk.singbox.service.manager.StartupManager.StartResult.Failed -> {
                recordStartFailureIfCurrent(recoveryLease, result.error)
                val recoveryAttemptId = recoveryLease.attemptId
                BackgroundResourceGuard.failSuccessorAndAwait(resourceGuardOwnerId, recoveryAttemptId)
                if (coreManager.isTunRebuildRequested() && coreManager.isVpnInterfaceValid()) {
                    coreManager.clearTunRebuildRequest()
                    coreManager.stopCorePreservingTun()
                    commandManager.stop()
                    SingBoxService.isRunning = false
                    SingBoxService.isStarting = false
                    SingBoxIpcHub.update(
                        state = ServiceState.RUNNING,
                        lastError = result.error,
                        readiness = SingBoxIpcHub.currentReadiness().copy(
                            status = com.kunk.singbox.ipc.DataPlaneStatus.FAILED_BLOCKED,
                            coreReady = false,
                            selectorReady = false,
                            recoveryActive = false,
                            tunEstablished = true,
                            lastReadinessReason = "tun_rebuild_failed"
                        )
                    )
                    notificationManager.setSuppressUpdates(false)
                    requestNotificationUpdate(force = true)
                    updateTileState()
                } else {
                    stopVpn(
                        stopService = true,
                        recoveryIntentLease = recoveryLease
                    )
                }
            }
            is com.kunk.singbox.service.manager.StartupManager.StartResult.NeedPermission -> {
                BackgroundResourceGuard.failSuccessorAndAwait(
                    resourceGuardOwnerId,
                    recoveryLease.attemptId
                )
                if (clearStartCommandFailureState(recoveryLease) {
                        startupManager.handlePermissionRequired(result.prepareIntent)
                    }
                ) {
                    stopSelf()
                }
            }
            is com.kunk.singbox.service.manager.StartupManager.StartResult.Cancelled -> {
                // 已在 callbacks.onCancelled() 中处理
            }
            is com.kunk.singbox.service.manager.StartupManager.StartResult.Superseded -> {
                stopVpn(false, recoveryIntentLease = recoveryLease)
            }
        }

        // 清理
        startVpnJob = null
        if (!SingBoxService.isRunning && !isStopping && serviceState == ServiceState.STARTING) {
            updateServiceState(ServiceState.STOPPED)
        }
        updateTileState()
    }
}

@Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod")
internal fun SingBoxService.stopVpn(
    stopService: Boolean,
    broadcastStoppingState: Boolean = true,
    recoveryIntentLease: RecoveryIntentLease? = null,
    shutdownKind: ShutdownManager.ShutdownKind = if (stopService) {
        ShutdownManager.ShutdownKind.FINAL_STOP
    } else {
        ShutdownManager.ShutdownKind.CORE_RECOVERY
    }
) {
    if (ServiceStateHolder.shouldIgnoreDuplicateHardStop(isStopping, stopSelfRequested)) {
        Log.i(SingBoxService.TAG, "Ignoring duplicate hard stop while cleanup is already running")
        return
    }
    val cleanupRecoveryLease = recoveryIntentLease ?: synchronized(this) {
        pendingRecoveryIntentLease
            ?: ServiceStateHolder.setRecoveryIntentOnFailure(false).also { pendingRecoveryIntentLease = it }
    }
    val cleanupRecoveryAttemptId = cleanupRecoveryLease.attemptId
    val cleanupIntentCurrent = ServiceStateHolder.isRecoveryIntentCurrent(cleanupRecoveryLease)
    val ownedStopService = stopService && cleanupIntentCurrent
    if (stopService && !ownedStopService) {
        Log.w(SingBoxService.TAG, "Hard stop downgraded for superseded recovery lease")
    }
    if (cleanupIntentCurrent) {
        if (cleanupRecoveryAttemptId == null) {
            cancelResourceGuard()
        } else {
            detachResourceGuard(cleanupRecoveryAttemptId)
        }
    }
    // 状态同步检查（保留在 Service 中，因为涉及多线程同步）
    val startCleanup = synchronized(this) {
        coreManager.beginStop()
        stopSelfRequested = stopSelfRequested || ownedStopService
        if (ownedStopService) {
            hardStopRecoveryIntentLease = cleanupRecoveryLease
            pendingStartConfigPath = null
            pendingStartRecoveryIntentLease = null
        }
        if (isStopping) {
            false
        } else {
            isStopping = true
            true
        }
    }
    if (!startCleanup) return

    autoFailoverCandidateCache.clear()
    autoFailoverOverrideActive = false
    activeAutoGroupTag = null
    autoGroupRestoreInFlight.set(false)

    // 更新状态
    if (shutdownKind != ShutdownManager.ShutdownKind.FINAL_STOP) {
        SingBoxIpcHub.updateReadiness { readiness ->
            readiness.copy(
                status = com.kunk.singbox.ipc.DataPlaneStatus.RECOVERING,
                coreReady = false,
                selectorReady = false,
                recoveryActive = true,
                tunEstablished = coreManager.isVpnInterfaceValid(),
                lastReadinessReason = "shutdown_${shutdownKind.name.lowercase()}"
            )
        }
    } else if (broadcastStoppingState) {
        updateServiceState(ServiceState.STOPPING)
    } else {
        requestRemoteStateUpdate(force = true)
    }
    notificationManager.setSuppressUpdates(shutdownKind == ShutdownManager.ShutdownKind.FINAL_STOP)
    if (shutdownKind == ShutdownManager.ShutdownKind.FINAL_STOP) {
        notificationManager.cancelNotification()
    } else {
        requestNotificationUpdate(force = true)
    }
    updateTileState()

    // 发送 Tile 刷新广播
    runCatching {
        val intent = Intent(VpnTileService.ACTION_REFRESH_TILE).apply {
            `package` = packageName
        }
        sendBroadcast(intent)
    }

    // 重置 VPN 启动时间戳
    autoFailoverServiceStartedAtMs = 0L

    Log.i(SingBoxService.TAG, "stopVpn(stopService=$stopService) SingBoxService.isManuallyStopped=$SingBoxService.isManuallyStopped")

    // 获取代理端口用于等待释放
    val proxyPort = coreManager.currentSettings?.proxyPort ?: 2080

    // 委托给 ShutdownManager
    // 不需要严格等待端口释放，启动时会强杀进程确保端口可用
    cleanupJob = shutdownManager.stopVpn(
        options = ShutdownManager.ShutdownOptions(
            kind = if (ownedStopService) ShutdownManager.ShutdownKind.FINAL_STOP else shutdownKind,
            proxyPort = proxyPort,
            recoveryIntentLease = cleanupRecoveryLease,
            resourceRecoveryAttemptId = cleanupRecoveryAttemptId
        ),
        coreManager = coreManager,
        commandManager = commandManager,
        notificationManager = notificationManager,
        callbacks = shutdownCallbacks
    )
}

internal fun SingBoxService.updateTileState() {
    try {
        TileService.requestListeningState(this, ComponentName(this, VpnTileService::class.java))

        // 显式触发 TileService 刷新，避免仅依赖 listening/bind 回调导致状态滞后
        val refreshIntent = Intent(this, VpnTileService::class.java).apply {
            action = VpnTileService.ACTION_REFRESH_TILE
        }
        startService(refreshIntent)
    } catch (e: Exception) {
        Log.e(SingBoxService.TAG, "Failed to update tile state", e)
    }
}

internal fun SingBoxService.buildNotificationState(): VpnNotificationManager.NotificationState {
    val configRepository = ConfigRepository.getInstance(this)
    val activeNodeId = configRepository.activeNodeId.value
    val nodeName = resolveNotificationNodeLabel(
        selectedNodeName = configRepository.nodes.value.find { it.id == activeNodeId }?.name,
        selectedNodeStoreLabel = VpnStateStore.getSelectedNodeLabel(),
        runtimeNodeName = realTimeNodeName ?: VpnStateStore.getActiveLabel()
    )

    return VpnNotificationManager.NotificationState(
        isRunning = SingBoxService.isRunning,
        isStopping = isStopping,
        activeNodeName = nodeName,
        showSpeed = showNotificationSpeed,
        uploadSpeed = currentUploadSpeed,
        downloadSpeed = currentDownloadSpeed,
        dataPlaneStatus = SingBoxIpcHub.currentReadiness().status
    )
}

internal fun SingBoxService.requestNotificationUpdate(force: Boolean) {
    notificationManager.requestNotificationUpdate(buildNotificationState(), this, force)
}

internal fun SingBoxService.createNotification(): Notification {
    return notificationManager.createNotification(buildNotificationState())
}

internal fun SingBoxService.onDestroyRuntime() {
    Log.i(SingBoxService.TAG, "onDestroy called -> stopVpn(stopService=false) pid=${android.os.Process.myPid()}")
    cancelResourceGuard()

    // 清理省电管理器引用
    SingBoxIpcHub.setPowerManager(null)
    screenStateManager.setPowerManager(null)
    backgroundPowerManager.cleanup()

    screenStateManager.unregisterActivityLifecycleCallbacks(application)
    DefaultNetworkListener.stop(defaultNetworkListenerKey)
    foreignVpnMonitor.cleanup()

    val shouldStop = runCatching {
        synchronized(this@onDestroyRuntime) {
            SingBoxService.isRunning || isStopping || coreManager.isServiceRunning() || vpnInterface != null
        }
    }.getOrDefault(false)

    val manualStop = lastStopInitiator.isManualStop || SingBoxService.isManuallyStopped
    val requestedStop = lastStopInitiator != VpnStopInitiator.UNKNOWN || manualStop
    val unexpectedDeath = !requestedStop && shouldStop
    recordServiceLifecycle(
        event = "destroy",
        reason = when {
            manualStop -> "manual_stop"
            requestedStop -> "automatic_stop"
            unexpectedDeath -> "unexpected_destroy"
            shouldStop -> "active_cleanup"
            else -> "inactive_destroy"
        }
    )
    if (unexpectedDeath) {
        // 意外死亡（划卡/系统杀）：恢复意图（mode、manuallyStopped）在启动成功时已持久化，这里不动。
        // active/pending 落成"当前不在跑"，不做完整 stop→restart 收尾；
        // 恢复交给 sticky / keepalive / 冷启动单路（共用 VpnStateStore 互斥）。
        VpnTileService.persistVpnState(false)
        VpnTileService.persistVpnPending("")
        Log.i(SingBoxService.TAG, "onDestroy: unexpected death, recovery intent preserved")
        runCatching {
            LogRepository.getInstance().addAlwaysLog(
                "INFO [Recovery] onDestroy unexpectedDeath mode=${VpnStateStore.getMode()} " +
                    "manuallyStopped=${VpnStateStore.isManuallyStopped()} preserveIntent=true"
            )
        }
    } else if (shouldStop) {
        // Note: stopVpn launches a cleanup job on cleanupScope.
        // If we halt() immediately, that job will die.
        // For app updates, the system kills us anyway, so cleanup might be best-effort.
        stopVpn(stopService = false)
    } else {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        if (!preserveRuntimeStateOnDestroy) {
            VpnTileService.persistVpnState(false)
            VpnTileService.persistVpnPending("")
            updateServiceState(ServiceState.STOPPED)
            updateTileState()
        }
    }

    serviceSupervisorJob.cancel()
    autoFailoverJob?.cancel()
    autoFailoverJob = null
    sameNodeRecoveryInFlight.set(false)
    autoFailoverSupervisorJob.cancel()
    // cleanupSupervisorJob.cancel() // Allow cleanup to finish naturally

    if (SingBoxService.instance == this) {
        SingBoxService.instance = null
    }

    Log.i(SingBoxService.TAG, "SingBoxService cleanup complete, pid=${android.os.Process.myPid()}.")
}

internal fun SingBoxService.onRevokeRuntime() {
    Log.i(SingBoxService.TAG, "onRevoke called -> stopVpn(stopService=true)")
    lastStopInitiator = VpnStopInitiator.SYSTEM_REVOKE
    SingBoxService.isManuallyStopped = false
    VpnStateStore.setManuallyStopped(false)
    recordServiceLifecycle(
        event = "stop_request",
        reason = "automatic_stop",
        action = "SYSTEM_REVOKE"
    )
    val recoveryLease = setNonResourceRecoveryIntent(false)
    VpnTileService.persistVpnPending("stopping")
    SingBoxService.setLastError("VPN revoked by system (another VPN may have started)")
    updateServiceState(ServiceState.STOPPING)
    requestRemoteStateUpdate(force = true)
    updateTileState()

    // 记录日志，告知用户原因
    com.kunk.singbox.repository.LogRepository.getInstance()
        .addLog("WARN: VPN permission revoked by system (possibly another VPN app started)")

    // 发送通知提醒用户
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = getSystemService(NotificationManager::class.java)
        val notification = Notification.Builder(this, VpnNotificationManager.CHANNEL_ID)
            .setContentTitle("VPN Disconnected")
            .setContentText("VPN permission revoked, possibly by another VPN app.")
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .build()
        manager.notify(VpnNotificationManager.NOTIFICATION_ID + 1, notification)
    }

    // 停止服务
    stopVpn(
        stopService = true,
        broadcastStoppingState = false,
        recoveryIntentLease = recoveryLease
    )
}

/**
 * 确保网络回调就绪，最多等待指定超时时间
 * 如果超时仍未就绪，尝试主动采样当前活跃网络
 */

internal suspend fun SingBoxService.ensureNetworkCallbackReadyWithTimeout(timeoutMs: Long) {
    networkHelper.ensureNetworkCallbackReady(
        isCallbackReady = { networkCallbackReady },
        lastKnownNetwork = { lastKnownNetwork },
        updateNetworkState = { network, ready ->
            lastKnownNetwork = network
            networkCallbackReady = ready
        },
        timeoutMs = timeoutMs
    )
}

/**
 * 后台异步更新规则集 - 性能优化
 * VPN 启动成功后延迟执行，在后台静默更新规则集
 * 这样启动时不需要等待规则集下载
 *
 * 2026-fix: 增加延迟时间并检查 CommandClient 状态，防止与 gomobile 回调并发导致
 * go/Seq Unknown reference 崩溃
 */

internal fun SingBoxService.scheduleAsyncRuleSetUpdate() {
    serviceScope.launch(Dispatchers.IO) {
        // 2026-fix: 增加延迟到 15 秒，确保 CommandClient 回调已稳定
        delay(15000)

        if (!SingBoxService.isRunning || isStopping) {
            Log.d(SingBoxService.TAG, "scheduleAsyncRuleSetUpdate: VPN not running, skip")
            return@launch
        }

        // 2026-fix: 检查 CommandClient 是否已收到回调，避免在初始化阶段并发访问
        val groupsCount = commandManager.getGroupsCount()
        if (groupsCount == 0) {
            Log.d(SingBoxService.TAG, "scheduleAsyncRuleSetUpdate: CommandClient not ready yet, skip")
            return@launch
        }

        try {
            val ruleSetRepo = RuleSetRepository.getInstance(this@scheduleAsyncRuleSetUpdate)
            val now = System.currentTimeMillis()
            if (now - lastRuleSetCheckMs >= ruleSetCheckIntervalMs) {
                lastRuleSetCheckMs = now
                Log.i(SingBoxService.TAG, "Starting async rule set update...")
                val allReady = ruleSetRepo.ensureRuleSetsReady(
                    forceUpdate = false,
                    allowNetwork = true
                ) { progress ->
                    Log.d(SingBoxService.TAG, "Async rule set update: $progress")
                }
                Log.i(SingBoxService.TAG, "Async rule set update completed, allReady=$allReady")
            }
        } catch (e: Exception) {
            Log.w(SingBoxService.TAG, "Async rule set update failed", e)
        }
    }
}

internal suspend fun SingBoxService.waitForUsablePhysicalNetwork(timeoutMs: Long): Network? {
    return networkHelper.waitForUsablePhysicalNetwork(timeoutMs)
}
