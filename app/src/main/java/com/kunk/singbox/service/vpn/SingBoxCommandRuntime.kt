@file:Suppress("UnusedImports", "TooManyFunctions", "LongMethod", "LargeClass", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeCons")

package com.kunk.singbox.service

import android.content.Intent
import android.net.Network
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.R
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.core.SelectorManager
import com.kunk.singbox.ipc.SingBoxIpcHub
import com.kunk.singbox.ipc.VpnNetworkOwnership
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.BackgroundPowerSavingDelay
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.repository.*
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.NodeProtectionStore
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.service.manager.BackgroundPowerManager
import com.kunk.singbox.service.manager.CommandManager
import com.kunk.singbox.service.manager.ForeignVpnMonitor
import com.kunk.singbox.service.manager.NodeSwitchManager
import com.kunk.singbox.service.manager.RecoveryIntentLease
import com.kunk.singbox.service.manager.ScreenStateManager
import com.kunk.singbox.service.manager.ServiceStateHolder
import com.kunk.singbox.service.network.TrafficMonitor
import io.nekohasekai.libbox.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

internal fun SingBoxService.isCommandServerStartupCurrent(
    startToken: Long,
    recoveryIntentLease: RecoveryIntentLease
): Boolean = synchronized(this) {
    isCommandServerStartupCurrentLocked(startToken, recoveryIntentLease)
}

internal fun SingBoxService.prepareCommandServerStartup(
    startToken: Long,
    recoveryIntentLease: RecoveryIntentLease
): Boolean {
    if (!isCommandServerStartupCurrent(startToken, recoveryIntentLease)) return false
    if (commandManager.hasActiveRuntime()) return false
    val staleCleared = commandManager.clearStaleServerForStartup().getOrThrow()
    if (staleCleared) coreManager.setCommandServer(null)
    return isCommandServerStartupCurrent(startToken, recoveryIntentLease)
}

internal fun SingBoxService.adoptCommandServerIfCurrent(
    server: CommandServer,
    startToken: Long,
    recoveryIntentLease: RecoveryIntentLease
): Boolean = synchronized(this) {
    if (!isCommandServerStartupCurrentLocked(startToken, recoveryIntentLease)) return@synchronized false
    commandManager.adoptServer(server)
    coreManager.setCommandServer(server)
    true
}

internal fun SingBoxService.isCommandServerStartupCurrentLocked(
    startToken: Long,
    recoveryIntentLease: RecoveryIntentLease
): Boolean {
    return !isStopping &&
        coreManager.isStartTokenCurrent(startToken) &&
        pendingRecoveryIntentLease === recoveryIntentLease &&
        ServiceStateHolder.isRecoveryIntentCurrent(recoveryIntentLease)
}

internal fun SingBoxService.tryRegisterRunningServiceForLibbox() {
    // No longer needed with new CommandServer API
}

internal fun SingBoxService.tryClearRunningServiceForLibbox() {
    // No longer needed with new CommandServer API
}

/**
 * 初始化新架构 Managers (7个核心模块)
 */
@Suppress("CognitiveComplexMethod")
internal fun SingBoxService.initManagers() {
    // 1. 初始化核心管理器
    coreManager.init(platformInterfaceImpl)
    Log.i(SingBoxService.TAG, "CoreManager initialized")

    initCommandManager()
    initSecondaryManagers()

    Log.i(SingBoxService.TAG, "All managers initialized")
}

@Suppress("CognitiveComplexMethod")
internal fun SingBoxService.initCommandManager() {
    // 4. 初始化 Command 管理器
    commandManager.init(object : CommandManager.Callbacks {
        override fun requestNotificationUpdate(force: Boolean) {
            this@initCommandManager.requestNotificationUpdate(force)
        }
        override fun resolveEgressNodeName(tagOrSelector: String?): String? {
            return this@initCommandManager.resolveEgressNodeName(
                ConfigRepository.getInstance(this@initCommandManager),
                tagOrSelector
            )
        }
        override fun onGroupSelectionChanged(groupTag: String, selectedTag: String) {
            this@initCommandManager.handleAutoGroupSelectionChanged(groupTag, selectedTag)
            if (groupTag.equals("PROXY", ignoreCase = true) && selectedTag.isNotBlank()) {
                publishSelectorReady("selector_callback")
            }
        }
        override fun onRuntimeNodeChanged(nodeName: String) {
            realTimeNodeName = nodeName
            if (nodeName == pendingNodeName) {
                pendingNodeName = null
            }
            requestRemoteStateUpdate(force = false)
        }
        override fun onTrafficUpdate(snapshot: TrafficMonitor.TrafficSnapshot) {
            currentUploadSpeed = snapshot.uploadSpeed
            currentDownloadSpeed = snapshot.downloadSpeed
            handleTrafficUpdateForAutoFailover(snapshot)
            if (showNotificationSpeed) {
                requestNotificationUpdate(force = false)
            }
        }
        override fun onControlChannelHealth(ready: Boolean) {
            SingBoxIpcHub.updateReadiness { readiness ->
                val coreReady = ready && SingBoxService.isRunning
                val canBeReady = coreReady && readiness.selectorReady &&
                    readiness.tunEstablished && readiness.systemVpnTransport &&
                    isVpnIdentityReady(readiness)
                val updated = readiness.copy(
                    coreReady = coreReady,
                    lastReadinessReason = if (ready) "command_channel_ready" else "command_channel_lost"
                )
                updated.copy(status = updated.resolveVpnStatus(canBeReady))
            }
        }
        override fun onServiceStop() {
            Log.i(SingBoxService.TAG, "CommandManager: onServiceStop requested")
            serviceScope.launch {
                val recoveryLease = setNonResourceRecoveryIntent(false)
                stopVpn(stopService = true, recoveryIntentLease = recoveryLease)
            }
        }
        override fun onServiceReload() {
            Log.i(SingBoxService.TAG, "CommandManager: onServiceReload requested")
        }
    })
    commandManager.setKernelLogObserver { message ->
        serviceScope.launch(Dispatchers.IO) {
            handleKernelLogForHealthSignal(message)
        }
    }
    Log.i(SingBoxService.TAG, "CommandManager initialized")
}

internal fun SingBoxService.publishSelectorReady(reason: String) {
    SingBoxIpcHub.updateReadiness { readiness ->
        val canBeReady = readiness.coreReady && readiness.tunEstablished && readiness.systemVpnTransport &&
            isVpnIdentityReady(readiness)
        val updated = readiness.copy(
            selectorReady = true,
            lastReadinessReason = reason
        )
        updated.copy(status = updated.resolveVpnStatus(canBeReady))
    }
}

internal fun SingBoxService.isVpnIdentityReady(readiness: com.kunk.singbox.ipc.DataPlaneReadinessSnapshot): Boolean {
    if (readiness.foreignVpnDetected || readiness.ownedVpnNetworkLost) return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        readiness.systemVpnOwnerStatus == com.kunk.singbox.ipc.VpnOwnerStatus.MATCH
    } else {
        readiness.ownedVpnNetworkHandle > 0L &&
            readiness.observedVpnNetworkHandle == readiness.ownedVpnNetworkHandle
    }
}

@Suppress("CognitiveComplexMethod")
internal fun SingBoxService.initSecondaryManagers() {
    // 初始化屏幕状态管理器
    screenStateManager.init(object : ScreenStateManager.Callbacks {
        override val isRunning: Boolean
            get() = SingBoxService.isRunning

        override fun notifyRemoteStateUpdate(force: Boolean) {
            this@initSecondaryManagers.requestRemoteStateUpdate(force)
        }
    })
    Log.i(SingBoxService.TAG, "ScreenStateManager initialized")

    // 9. 初始化外部 VPN 监控器
    foreignVpnMonitor.init(object : ForeignVpnMonitor.Callbacks {
        override val isStarting: Boolean
            get() = SingBoxService.isStarting
        override val isRunning: Boolean
            get() = SingBoxService.isRunning
        override val isConnectingTun: Boolean
            get() = this@initSecondaryManagers.isConnectingTun.get()

        override fun onVpnNetworkObserved(
            network: Network,
            ownership: VpnNetworkOwnership,
            sessionId: Long
        ) {
            if (sessionId != activeVpnSessionId || !foreignVpnMonitor.isSessionCurrent(sessionId)) {
                Log.d(
                    SingBoxService.TAG,
                    "Ignoring stale VPN observation session=$sessionId active=$activeVpnSessionId network=$network"
                )
                return
            }
            Log.i(
                SingBoxService.TAG,
                "VPN ownership=$ownership session=$sessionId networkHandle=${network.networkHandle}"
            )
            SingBoxIpcHub.updateReadiness { readiness ->
                if (readiness.vpnSessionId != sessionId) return@updateReadiness readiness
                readiness.observeVpnNetwork(ownership, network.networkHandle, serviceState)
            }
        }

        override fun onVpnNetworkLost(network: Network, owned: Boolean, sessionId: Long) {
            if (!owned) return
            if (sessionId != activeVpnSessionId || !foreignVpnMonitor.isSessionCurrent(sessionId)) {
                Log.d(
                    SingBoxService.TAG,
                    "Ignoring stale VPN loss session=$sessionId active=$activeVpnSessionId network=$network"
                )
                return
            }
            Log.w(
                SingBoxService.TAG,
                "Owned VPN network lost session=$sessionId networkHandle=${network.networkHandle}"
            )
            SingBoxIpcHub.updateReadiness { readiness ->
                if (readiness.vpnSessionId != sessionId) return@updateReadiness readiness
                readiness.observeOwnedVpnNetworkLost(network.networkHandle, serviceState)
            }
        }
    })
    Log.i(SingBoxService.TAG, "ForeignVpnMonitor initialized")

    nodeSwitchManager.init(object : NodeSwitchManager.Callbacks {
        override val isRunning: Boolean
            get() = SingBoxService.isRunning
        override suspend fun hotSwitchNode(nodeTag: String): Boolean = this@initSecondaryManagers.hotSwitchNode(nodeTag)
        override fun getConfigPath(): String = pendingHotSwitchFallbackConfigPath
            ?: File(filesDir, "running_config.json").absolutePath
        override fun setRealTimeNodeName(name: String?) {
            realTimeNodeName = name
            if (!name.isNullOrBlank() && name == pendingNodeName) {
                pendingNodeName = null
            }
        }
        override fun requestNotificationUpdate(force: Boolean) {
            this@initSecondaryManagers.requestNotificationUpdate(force)
        }
        override fun notifyRemoteStateUpdate(force: Boolean) {
            this@initSecondaryManagers.requestRemoteStateUpdate(force)
        }
        override fun startServiceIntent(intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    })
    Log.i(SingBoxService.TAG, "NodeSwitchManager initialized")

    initBackgroundPowerManager()
    Log.i(SingBoxService.TAG, "BackgroundPowerManager initialized")

    Log.i(SingBoxService.TAG, "KunBox VPN started successfully")
    notificationManager.setSuppressUpdates(false)
}

internal fun SingBoxService.initBackgroundPowerManager() {
    val initialThresholdMs = backgroundPowerSavingThresholdMs

    backgroundPowerManager.init(
        callbacks = object : BackgroundPowerManager.Callbacks {
            override fun suspendNonEssentialProcesses() {
                coreManager.enterPowerSavingMode().onFailure { e ->
                    Log.w(SingBoxService.TAG, "[PowerSaving] Failed to release locks", e)
                }
            }

            override fun resumeNonEssentialProcesses() {
                coreManager.exitPowerSavingMode().onFailure { e ->
                    Log.w(SingBoxService.TAG, "[PowerSaving] Failed to restore locks", e)
                }
            }
        },
        thresholdMs = initialThresholdMs
    )

    // Load user setting asynchronously to avoid blocking service initialization.
    serviceScope.launch {
        val thresholdMs = runCatching {
            val settings = SettingsRepository.getInstance(this@initBackgroundPowerManager).settings.first()
            settings.backgroundPowerSavingDelay.delayMs
        }.getOrElse { e ->
            Log.w(SingBoxService.TAG, "Failed to read power saving delay setting, using default", e)
            BackgroundPowerSavingDelay.MINUTES_30.delayMs
        }
        backgroundPowerSavingThresholdMs = thresholdMs
        backgroundPowerManager.setThreshold(thresholdMs)
    }

    // 设置 IPC Hub 的 PowerManager 引用，用于接收主进程的生命周期通知
    SingBoxIpcHub.setPowerManager(backgroundPowerManager)
    // 设置 ScreenStateManager 的 PowerManager 引用，用于接收屏幕状态通知
    screenStateManager.setPowerManager(backgroundPowerManager)
}

/**
 * StartupManager 回调实现
 */

internal fun SingBoxService.initSelectorManager(configContent: String): String? {
    return try {
        val config = gson.fromJson(configContent, SingBoxConfig::class.java) ?: return null
        val proxySelector = config.outbounds?.find {
            it.type == "selector" && it.tag.equals("PROXY", ignoreCase = true)
        }

        if (proxySelector == null) {
            Log.w(SingBoxService.TAG, "No PROXY selector found in config")
            return null
        }

        val outboundTags = proxySelector.outbounds?.filter { it.isNotBlank() } ?: emptyList()
        val preferredTag = resolvePreferredProxyTag(outboundTags, proxySelector.default)

        SelectorManager.recordSelectorSignature(outboundTags)
        Log.i(
            SingBoxService.TAG,
            "SelectorManager initialized: ${outboundTags.size} outbounds, selected=$preferredTag"
        )
        preferredTag
    } catch (e: Exception) {
        Log.e(SingBoxService.TAG, "Failed to init SelectorManager", e)
        null
    }
}

/**
 * 启动后强制 PROXY 到手选节点。
 * 优先使用 intent 指定节点，其次使用主进程生成配置时写入的 default。
 */
internal fun SingBoxService.resolvePreferredProxyTag(
    outboundTags: List<String>,
    configDefault: String?
): String? {
    fun pick(name: String?): String? {
        if (name.isNullOrBlank()) return null
        if (name in outboundTags) return name
        return outboundTags.firstOrNull { it.equals(name, ignoreCase = true) }
    }

    return pick(pendingNodeName) ?: pick(configDefault) ?: outboundTags.firstOrNull()
}

internal suspend fun SingBoxService.applyPreferredProxySelection(preferredTag: String?) {
    if (preferredTag.isNullOrBlank()) {
        publishSelectorReady("no_proxy_selector")
        return
    }

    val currentSelectedTag = commandManager.getSelectedOutbound("PROXY")
    if (currentSelectedTag.isNullOrBlank()) {
        Log.w(SingBoxService.TAG, "Waiting for initial PROXY selection callback: $preferredTag")
        return
    }
    val result = if (currentSelectedTag.equals(preferredTag, ignoreCase = true)) {
        SelectorManager.SwitchResult.Success("AlreadySelected")
    } else {
        SelectorManager.switchNode(preferredTag)
    }
    when (result) {
        is SelectorManager.SwitchResult.Success -> {
            val concreteTag = resolveConfirmedProxyRuntimeLabel(
                kernelResolvedTag = commandManager.getResolvedSelectedOutbound("PROXY"),
                preferredTag = preferredTag,
                currentRuntimeTag = commandManager.realTimeNodeName ?: realTimeNodeName
            )
            if (concreteTag != null) {
                val displayName = resolveRuntimeNodeLabel(
                    concreteTag,
                    NodeProtectionStore.runtimeMappings()
                ) ?: concreteTag
                realTimeNodeName = displayName
                commandManager.realTimeNodeName = displayName
                VpnStateStore.setActiveLabel(displayName)
            } else {
                Log.w(
                    SingBoxService.TAG,
                    "Kernel confirmed automatic group but concrete node is not available yet: $preferredTag"
                )
            }
            if (pendingNodeName == preferredTag) {
                pendingNodeName = null
            }
            requestNotificationUpdate(force = true)
            requestRemoteStateUpdate(force = true)
            publishSelectorReady("selector_readback")
            Log.i(SingBoxService.TAG, "Applied preferred PROXY selection: $preferredTag")
        }
        is SelectorManager.SwitchResult.NeedRestart -> Log.w(
            SingBoxService.TAG,
            "Preferred PROXY selection not applied: ${result.reason}, tag=$preferredTag"
        )
    }
}

internal fun SingBoxService.launchPostStartTasks(configContent: String) {
    val generation = postStartGeneration.incrementAndGet()
    val previousJob = postStartJob
    previousJob?.cancel()
    postStartJob = serviceScope.launch {
        try {
            previousJob?.join()
            if (!isPostStartTaskActive(generation)) return@launch

            commandManager.getCommandServer()?.let { server ->
                BoxWrapperManager.init(server)
            }
            Log.i(SingBoxService.TAG, "BoxWrapperManager initialized")

            commandManager.startClients().onFailure { error ->
                Log.e(SingBoxService.TAG, "Failed to start Command Clients", error)
            }
            if (!isPostStartTaskActive(generation)) return@launch

            SelectorManager.updateCommandClient(commandManager.getCommandClient())
            applyPreferredProxySelection(initSelectorManager(configContent))
            if (!isPostStartTaskActive(generation)) return@launch

            scheduleAsyncRuleSetUpdate()

            Log.i(SingBoxService.TAG, "VPN post-start tasks completed")
        } finally {
            if (postStartGeneration.get() == generation) {
                postStartJob = null
            }
        }
    }
}

internal fun SingBoxService.isPostStartTaskActive(generation: Long): Boolean {
    return postStartGeneration.get() == generation && SingBoxService.isRunning && !isStopping
}

/**
 * 使用统一离线临时服务测速路径并返回结果
 *
 * @param groupTag 要测试的 group 标签 (如 "PROXY")
 * @param timeoutMs 等待结果的超时时间
 * @return 节点延迟映射 (tag -> delay ms)，失败返回空 Map
 */
@Suppress("UNUSED_PARAMETER")
internal suspend fun SingBoxService.urlTestGroup(groupTag: String, timeoutMs: Long): Map<String, Int> {
    return testGroupCandidatesLatency(groupTag)
}

@Suppress("UNUSED_PARAMETER")
internal suspend fun SingBoxService.urlTestGroup(
    groupTag: String,
    timeoutMs: Long,
    expectedTags: Set<String>,
    onProgress: ((Map<String, Int>) -> Unit)?): Map<String, Int> {
    val results = testGroupCandidatesLatency(groupTag)
        .filterKeys { expectedTags.isEmpty() || it in expectedTags }
    onProgress?.invoke(results)
    return results
}

internal fun SingBoxService.closeRecentConnectionsBestEffort(reason: String) {
    val ids = recentConnectionIds
    if (ids.isEmpty()) return
    var closed = 0
    for (id in ids) {
        if (id.isBlank()) continue
        if (commandManager.closeConnection(id)) closed++
    }
    if (closed > 0) {
        LogRepository.getInstance().addLog("INFO: closeConnection($reason) closed=$closed")
    }
}

internal fun SingBoxService.resolveEgressNodeName(repo: ConfigRepository, tagOrSelector: String?): String? {
    if (tagOrSelector.isNullOrBlank()) return null

    // 1) Direct outbound tag -> node name
    repo.resolveNodeNameFromOutboundTag(tagOrSelector)?.let { return it }

    // 2) Selector/group tag -> selected outbound -> resolve again (depth-limited)
    var current: String? = tagOrSelector
    repeat(4) {
        val next = current?.let { commandManager.getSelectedOutbound(it) }
        if (next.isNullOrBlank() || next == current) return@repeat
        repo.resolveNodeNameFromOutboundTag(next)?.let { return it }
        current = next
    }

    return null
}

internal fun SingBoxService.notifyRemoteStateNow() {
    val activeLabel = runCatching {
        val repo = ConfigRepository.getInstance(applicationContext)
        val activeNodeId = repo.activeNodeId.value
        val nodeName = resolveNotificationNodeLabel(
            selectedNodeName = repo.nodes.value.find { it.id == activeNodeId }?.name,
            selectedNodeStoreLabel = VpnStateStore.getSelectedNodeLabel(),
            runtimeNodeName = realTimeNodeName ?: VpnStateStore.getActiveLabel()
        )
        nodeName.orEmpty()
    }.getOrDefault("")

    SingBoxIpcHub.update(
        state = serviceState,
        activeLabel = activeLabel,
        lastError = SingBoxService.lastErrorFlow.value.orEmpty(),
        manuallyStopped = SingBoxService.isManuallyStopped
    )
}

internal fun SingBoxService.publishEstablishedTunReadiness() {
    val ownedVpnNetwork = foreignVpnMonitor.findOwnedVpnNetwork(applicationInfo.uid)
    foreignVpnMonitor.setOwnedVpnNetwork(ownedVpnNetwork)
    val ownedHandle = ownedVpnNetwork?.networkHandle ?: 0L
    val plan = coreManager.appliedPerAppVpnPlan()
    val (alwaysOnPackage, lockdown) = coreManager.alwaysOnVpnStatus()
    SingBoxIpcHub.updateReadiness { readiness ->
        val updated = readiness.copy(
            tunEstablished = coreManager.isVpnInterfaceValid(),
            routingScope = "${plan.mode}:allowed=${plan.appliedAllowedPackages.size}:" +
                "excluded=${plan.appliedDisallowedPackages.size}:skipped=${plan.skippedPackages.size}:" +
                "browser=${plan.browserCoverage}",
            lockdownStatus = when {
                lockdown && alwaysOnPackage == packageName -> com.kunk.singbox.ipc.VpnLockdownStatus.ENABLED
                alwaysOnPackage == packageName -> com.kunk.singbox.ipc.VpnLockdownStatus.DISABLED
                else -> com.kunk.singbox.ipc.VpnLockdownStatus.UNKNOWN
            },
            lastReadinessReason = if (ownedVpnNetwork == null) {
                "tun_established_owner_pending"
            } else {
                "tun_established"
            }
        )
        if (ownedVpnNetwork != null) {
            updated.observeVpnNetwork(
                ownership = VpnNetworkOwnership.OWNED,
                networkHandle = ownedHandle,
                serviceState = serviceState
            )
        } else {
            updated.copy(status = updated.resolveVpnStatus(canBeReady = false))
        }
    }
}

internal fun SingBoxService.requestRemoteStateUpdate(force: Boolean) {
    val now = SystemClock.elapsedRealtime()
    val last = lastRemoteStateUpdateAtMs.get()

    if (force) {
        lastRemoteStateUpdateAtMs.set(now)
        remoteStateUpdateJob?.cancel()
        remoteStateUpdateJob = null
        notifyRemoteStateNow()
        return
    }

    val delayMs = (remoteStateUpdateDebounceMs - (now - last)).coerceAtLeast(0L)
    if (delayMs <= 0L) {
        lastRemoteStateUpdateAtMs.set(now)
        remoteStateUpdateJob?.cancel()
        remoteStateUpdateJob = null
        notifyRemoteStateNow()
        return
    }

    if (remoteStateUpdateJob?.isActive == true) return
    remoteStateUpdateJob = serviceScope.launch {
        delay(delayMs)
        lastRemoteStateUpdateAtMs.set(SystemClock.elapsedRealtime())
        notifyRemoteStateNow()
    }
}
