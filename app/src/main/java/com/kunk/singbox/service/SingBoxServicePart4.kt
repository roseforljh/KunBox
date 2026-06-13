package com.kunk.singbox.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.Network
import android.os.Build
import android.os.Process
import android.util.Log
import android.service.quicksettings.TileService
import android.content.ComponentName
import com.kunk.singbox.R
import com.kunk.singbox.ipc.SingBoxIpcHub
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.RuleSetRepository
import com.kunk.singbox.repository.TrafficRepository
import com.kunk.singbox.service.notification.VpnNotificationManager
import com.kunk.singbox.service.manager.ShutdownManager
import io.nekohasekai.libbox.*
import kotlinx.coroutines.*

@Suppress("TooManyFunctions")
open class SingBoxServicePart4 : SingBoxServicePart3() {
    protected override fun stopVpn(stopService: Boolean, broadcastStoppingState: Boolean) {
        // 状态同步检查（保留在 Service 中，因为涉及多线程同步）
        synchronized(this) {
            stopSelfRequested = stopSelfRequested || stopService
            if (isStopping) {
                return
            }
            isStopping = true
        }

        cancelPendingRecoveryWork()

        // 更新状态
        if (broadcastStoppingState) {
            updateServiceState(ServiceState.STOPPING)
        } else {
            requestRemoteStateUpdate(force = true)
        }
        notificationManager.setSuppressUpdates(true)
        notificationManager.cancelNotification()
        updateTileState()

        // 发送 Tile 刷新广播
        runCatching {
            val intent = Intent(VpnTileService.ACTION_REFRESH_TILE).apply {
                `package` = packageName
            }
            sendBroadcast(intent)
        }

        // 重置 VPN 启动时间戳
        vpnStartedAtMs.set(0)
        stallRefreshAttempts = 0
        autoFailoverServiceStartedAtMs = 0L
        isProxyIdleForAutoFailover = false

        // 清理 networkManager (stopService 时释放)
        if (stopService) {
            networkManager?.reset()
            networkManager = null
        } else {
            networkManager?.reset()
        }

        Log.i(SingBoxService.TAG, "stopVpn(stopService=$stopService) SingBoxService.isManuallyStopped=$SingBoxService.isManuallyStopped")

        // 获取代理端口用于等待释放
        val proxyPort = currentSettings?.proxyPort ?: 2080

        // 委托给 ShutdownManager
        // 不需要严格等待端口释放，启动时会强杀进程确保端口可用
        cleanupJob = shutdownManager.stopVpn(
            options = ShutdownManager.ShutdownOptions(
                stopService = stopService,
                preserveTunInterface = !stopService,
                proxyPort = proxyPort,
                strictPortRelease = false
            ),
            coreManager = coreManager,
            commandManager = commandManager,
            trafficMonitor = trafficMonitor,
            networkManager = networkManager,
            notificationManager = notificationManager,
            selectorManager = serviceSelectorManager,
            platformInterfaceImpl = platformInterfaceImpl,
            callbacks = shutdownCallbacks
        )
    }

    protected override fun updateTileState() {
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

    protected override fun buildNotificationState(): VpnNotificationManager.NotificationState {
        val configRepository = ConfigRepository.getInstance(this)
        val activeNodeId = configRepository.activeNodeId.value
        val nodeName = resolveNotificationNodeLabel(
            selectedNodeName = configRepository.nodes.value.find { it.id == activeNodeId }?.name,
            selectedNodeStoreLabel = VpnStateStore.getSelectedNodeLabel()
        )

        return VpnNotificationManager.NotificationState(
            isRunning = SingBoxService.isRunning,
            isStopping = isStopping,
            activeNodeName = nodeName,
            showSpeed = showNotificationSpeed,
            uploadSpeed = currentUploadSpeed,
            downloadSpeed = currentDownloadSpeed
        )
    }

    protected override fun requestNotificationUpdate(force: Boolean) {
        notificationManager.requestNotificationUpdate(buildNotificationState(), this as SingBoxService, force)
    }

    protected override fun createNotification(): Notification {
        return notificationManager.createNotification(buildNotificationState())
    }

    override fun onDestroy() {
        Log.i(SingBoxService.TAG, "onDestroy called -> stopVpn(stopService=false) pid=${android.os.Process.myPid()}")
        TrafficRepository.getInstance(this).saveStats()

        // 清理省电管理器引用
        SingBoxIpcHub.setPowerManager(null)
        screenStateManager.setPowerManager(null)
        backgroundPowerManager.cleanup()

        screenStateManager.unregisterActivityLifecycleCallbacks(application)
        cancelPendingRecoveryWork()

        val shouldStop = runCatching {
            synchronized(this@SingBoxServicePart4) {
                SingBoxService.isRunning || isStopping || coreManager.isServiceRunning() || vpnInterface != null
            }
        }.getOrDefault(false)

        // Ensure critical state is saved synchronously before we potentially halt
        if (!SingBoxService.isManuallyStopped && shouldStop) {
            // If we are being destroyed but not manually stopped (e.g. app update or system kill),
            // keep the persisted intent recoverable for VpnKeepaliveWorker.
            VpnTileService.persistVpnState(applicationContext, true)
            VpnTileService.persistVpnPending(applicationContext, "")
            VpnStateStore.setMode(VpnStateStore.CoreMode.VPN)
            Log.i(SingBoxService.TAG, "onDestroy: Preserved recoverable VPN state")
        }

        if (shouldStop) {
            // Note: stopVpn launches a cleanup job on cleanupScope.
            // If we halt() immediately, that job will die.
            // For app updates, the system kills us anyway, so cleanup might be best-effort.
            stopVpn(stopService = false)
        } else {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            VpnTileService.persistVpnState(applicationContext, false)
            VpnTileService.persistVpnPending(applicationContext, "")
            updateServiceState(ServiceState.STOPPED)
            updateTileState()
        }

        serviceSupervisorJob.cancel()
        autoFailoverJob?.cancel()
        autoFailoverJob = null
        autoFailoverSupervisorJob.cancel()
        autoFailoverDispatcher.close()
        // cleanupSupervisorJob.cancel() // Allow cleanup to finish naturally

        if (SingBoxService.instance == this) {
            SingBoxService.instance = null
        }
        super.onDestroy()

        Log.i(SingBoxService.TAG, "SingBoxService cleanup complete, pid=${android.os.Process.myPid()}.")
    }

    override fun onRevoke() {
        Log.i(SingBoxService.TAG, "onRevoke called -> stopVpn(stopService=true)")
        SingBoxService.isManuallyStopped = true
        VpnStateStore.setManuallyStopped(true)
        // Another VPN took over. Persist OFF state immediately so QS tile won't stay active.
        VpnTileService.persistVpnState(applicationContext, false)
        VpnTileService.persistVpnPending(applicationContext, "")
        SingBoxService.setLastError("VPN revoked by system (another VPN may have started)")
        updateServiceState(ServiceState.STOPPED)
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
        stopVpn(stopService = true, broadcastStoppingState = false)
        super.onRevoke()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // If the user swiped away the app, we might want to keep the VPN running
        // as a foreground service, but some users expect it to stop.
        // Usually, a foreground service continues running.
        // However, if we want to ensure no "zombie" states, we can at least log or check health.
    }

    /**
     * 确保网络回调就绪，最多等待指定超时时间
     * 如果超时仍未就绪，尝试主动采样当前活跃网络
     */

    protected override suspend fun ensureNetworkCallbackReadyWithTimeout(timeoutMs: Long) {
        networkHelper.ensureNetworkCallbackReady(
            isCallbackReady = { networkCallbackReady },
            lastKnownNetwork = { lastKnownNetwork },
            findBestPhysicalNetwork = { findBestPhysicalNetwork() },
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

    protected override fun scheduleAsyncRuleSetUpdate() {
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
                val ruleSetRepo = RuleSetRepository.getInstance(this@SingBoxServicePart4)
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

    protected override suspend fun waitForUsablePhysicalNetwork(timeoutMs: Long): Network? {
        return networkHelper.waitForUsablePhysicalNetwork(
            lastKnownNetwork = lastKnownNetwork,
            networkManager = networkManager,
            findBestPhysicalNetwork = { findBestPhysicalNetwork() },
            timeoutMs = timeoutMs
        )
    }
}
