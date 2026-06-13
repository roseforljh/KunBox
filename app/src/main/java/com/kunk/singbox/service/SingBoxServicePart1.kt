package com.kunk.singbox.service

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.ipc.SingBoxIpcHub
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.core.SelectorManager
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.service.network.TrafficMonitor
import com.kunk.singbox.service.manager.CommandManager
import com.kunk.singbox.service.manager.ScreenStateManager
import com.kunk.singbox.service.manager.RouteGroupSelector
import com.kunk.singbox.service.manager.ForeignVpnMonitor
import com.kunk.singbox.service.manager.NodeSwitchManager
import com.kunk.singbox.service.manager.BackgroundPowerManager
import com.kunk.singbox.service.manager.ServiceStateHolder
import com.kunk.singbox.model.BackgroundPowerSavingDelay
import com.kunk.singbox.utils.L
import io.nekohasekai.libbox.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue

@Suppress("TooManyFunctions")
abstract class SingBoxServicePart1 : SingBoxServiceBase() {
    protected override fun tryRegisterRunningServiceForLibbox() {
        // No longer needed with new CommandServer API
    }

    protected override fun tryClearRunningServiceForLibbox() {
        // No longer needed with new CommandServer API
    }

    /**
     * 初始化新架构 Managers (7个核心模块)
     */
    @Suppress("CognitiveComplexMethod")
    protected override fun initManagers() {
        // 1. 初始化核心管理器
        coreManager.init(platformInterfaceImpl)
        Log.i(SingBoxService.TAG, "CoreManager initialized")

        initConnectManager()
        initServiceSelectorManager()
        initCommandManager()
        initSecondaryManagers()

        Log.i(SingBoxService.TAG, "All managers initialized")
    }

    protected override fun initConnectManager() {
        connectManager.init(
            onNetworkChanged = { network ->
                if (network != null) {
                    Log.d(SingBoxService.TAG, "Network changed: $network")
                }
            },
            onNetworkLost = {
                Log.i(SingBoxService.TAG, "Network lost")
            },
            setUnderlyingNetworksFn = { nets ->
                setUnderlyingNetworks(nets)
            }
        )
        Log.i(SingBoxService.TAG, "ConnectManager initialized")
    }

    protected override fun initServiceSelectorManager() {
        // 3. 初始化节点选择管理器
        serviceSelectorManager.init(commandManager.getCommandClient())
        Log.i(SingBoxService.TAG, "ServiceSelectorManager initialized")
    }

    protected override fun initCommandManager() {
        // 4. 初始化 Command 管理器
        commandManager.init(object : CommandManager.Callbacks {
            override fun requestNotificationUpdate(force: Boolean) {
                this@SingBoxServicePart1.requestNotificationUpdate(force)
            }
            override fun resolveEgressNodeName(tagOrSelector: String?): String? {
                return this@SingBoxServicePart1.resolveEgressNodeName(
                    ConfigRepository.getInstance(this@SingBoxServicePart1),
                    tagOrSelector
                )
            }
            override fun onServiceStop() {
                Log.i(SingBoxService.TAG, "CommandManager: onServiceStop requested")
                serviceScope.launch {
                    stopVpn(stopService = true)
                }
            }
            override fun onServiceReload() {
                Log.i(SingBoxService.TAG, "CommandManager: onServiceReload requested")
            }
        })
        Log.i(SingBoxService.TAG, "CommandManager initialized")
    }

    protected override fun initSecondaryManagers() {
        // 初始化屏幕状态管理器
        screenStateManager.init(object : ScreenStateManager.Callbacks {
            override val isRunning: Boolean
                get() = SingBoxService.isRunning

            override fun notifyRemoteStateUpdate(force: Boolean) {
                this@SingBoxServicePart1.requestRemoteStateUpdate(force)
            }

            override fun requestCoreNetworkRecovery(reason: String, force: Boolean) {
                this@SingBoxServicePart1.requestCoreNetworkReset(reason, force)
            }
        })
        Log.i(SingBoxService.TAG, "ScreenStateManager initialized")

        // 初始化路由组自动选择管理器
        routeGroupSelector.init(object : RouteGroupSelector.Callbacks {
            override val isRunning: Boolean
                get() = SingBoxService.isRunning
            override val isStopping: Boolean
                get() = coreManager.isStopping
            override fun getCommandClient() = commandManager.getCommandClient()
            override fun getSelectedOutbound(groupTag: String) = commandManager.getSelectedOutbound(groupTag)
            override fun onRouteGroupFallback(groupTag: String, actualSelectedTag: String?) {
                val targetTag = actualSelectedTag?.takeIf { it.isNotBlank() } ?: "当前全局节点"
                val message =
                    "配置分流 $groupTag 节点全部不可用，已临时回退到全局节点 $targetTag"
                val notificationId = 2000 + (groupTag.hashCode().absoluteValue % 500)
                val notification = notificationManager.createStartingNotification(message)
                notificationManager.showTemporaryNotification(notificationId, notification)
                serviceScope.launch {
                    delay(8000)
                    notificationManager.cancelNotification(notificationId)
                }
            }

            override fun onRouteGroupImmediateSwitch(
                groupTag: String,
                previousSelectedTag: String,
                newSelectedTag: String,
                reason: String
            ) {
                this@SingBoxServicePart1.convergeConnectionsAfterImmediateRouteGroupSwitch(
                    groupTag = groupTag,
                    previousSelectedTag = previousSelectedTag,
                    newSelectedTag = newSelectedTag,
                    rawReason = reason
                )
            }
        })
        Log.i(SingBoxService.TAG, "RouteGroupSelector initialized")

        // 9. 初始化外部 VPN 监控器
        foreignVpnMonitor.init(object : ForeignVpnMonitor.Callbacks {
            override val isStarting: Boolean
                get() = SingBoxService.isStarting
            override val isRunning: Boolean
                get() = SingBoxService.isRunning
            override val isConnectingTun: Boolean
                get() = this@SingBoxServicePart1.isConnectingTun.get()
        })
        Log.i(SingBoxService.TAG, "ForeignVpnMonitor initialized")

        nodeSwitchManager.init(object : NodeSwitchManager.Callbacks {
            override val isRunning: Boolean
                get() = SingBoxService.isRunning
            override suspend fun hotSwitchNode(nodeTag: String): Boolean = this@SingBoxServicePart1.hotSwitchNode(nodeTag)
            override fun getConfigPath(): String = pendingHotSwitchFallbackConfigPath
                ?: File(filesDir, "running_config.json").absolutePath
            override fun setRealTimeNodeName(name: String?) {
                realTimeNodeName = name
                if (!name.isNullOrBlank() && name == pendingNodeName) {
                    pendingNodeName = null
                }
            }
            override fun requestNotificationUpdate(force: Boolean) {
                this@SingBoxServicePart1.requestNotificationUpdate(force)
            }
            override fun notifyRemoteStateUpdate(force: Boolean) {
                this@SingBoxServicePart1.requestRemoteStateUpdate(force)
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

    protected override fun initBackgroundPowerManager() {
        val initialThresholdMs = backgroundPowerSavingThresholdMs

        backgroundPowerManager.init(
            callbacks = object : BackgroundPowerManager.Callbacks {
                override val isVpnRunning: Boolean
                    get() = SingBoxService.isRunning

                override val isVpnStarting: Boolean
                    get() = SingBoxService.isStarting

                override val isVpnStopping: Boolean
                    get() = this@SingBoxServicePart1.isStopping

                override val isManuallyStopped: Boolean
                    get() = ServiceStateHolder.isManuallyStopped

                override fun requestCoreNetworkRecovery(reason: String, force: Boolean) {
                    this@SingBoxServicePart1.requestCoreNetworkReset(reason, force)
                }

                override fun suspendNonEssentialProcesses() {
                    Log.d(SingBoxService.TAG, "[PowerSaving] suspendNonEssentialProcesses ignored")
                }

                override fun resumeNonEssentialProcesses() {
                    Log.d(SingBoxService.TAG, "[PowerSaving] resumeNonEssentialProcesses ignored")
                }
            },
            thresholdMs = initialThresholdMs
        )

        // Load user setting asynchronously to avoid blocking service initialization.
        serviceScope.launch {
            val thresholdMs = runCatching {
                val settings = SettingsRepository.getInstance(this@SingBoxServicePart1).settings.first()
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

    protected override fun initSelectorManager(configContent: String) {
        try {
            val config = gson.fromJson(configContent, SingBoxConfig::class.java) ?: return
            val proxySelector = config.outbounds?.find {
                it.type == "selector" && it.tag.equals("PROXY", ignoreCase = true)
            }

            if (proxySelector == null) {
                Log.w(SingBoxService.TAG, "No PROXY selector found in config")
                return
            }

            val outboundTags = proxySelector.outbounds?.filter { it.isNotBlank() } ?: emptyList()
            val selectedTag = proxySelector.default ?: outboundTags.firstOrNull()

            SelectorManager.recordSelectorSignature(outboundTags, selectedTag)
            Log.i(SingBoxService.TAG, "SelectorManager initialized: ${outboundTags.size} outbounds, selected=$selectedTag")
        } catch (e: Exception) {
            Log.e(SingBoxService.TAG, "Failed to init SelectorManager", e)
        }
    }

    /**
     * 使用统一离线临时服务测速路径并返回结果
     *
     * @param groupTag 要测试的 group 标签 (如 "PROXY")
     * @param timeoutMs 等待结果的超时时间
     * @return 节点延迟映射 (tag -> delay ms)，失败返回空 Map
     */
    @Suppress("UNUSED_PARAMETER")
    override suspend fun urlTestGroup(groupTag: String, timeoutMs: Long): Map<String, Int> {
        return testGroupCandidatesLatency(groupTag)
    }

    @Suppress("UNUSED_PARAMETER")
    override suspend fun urlTestGroup(
        groupTag: String,
        timeoutMs: Long,
        expectedTags: Set<String>,
        onProgress: ((Map<String, Int>) -> Unit)?): Map<String, Int> {
        val results = testGroupCandidatesLatency(groupTag)
            .filterKeys { expectedTags.isEmpty() || it in expectedTags }
        onProgress?.invoke(results)
        return results
    }

    protected override fun closeRecentConnectionsBestEffort(reason: String) {
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

    /**
     * 重置所有连接 - 渐进式降级策略
     */

    protected override suspend fun resetConnectionsOptimal(reason: String, skipDebounce: Boolean) {
        networkHelper.resetConnectionsOptimal(
            reason = reason,
            skipDebounce = skipDebounce,
            lastResetAtMs = lastConnectionsResetAtMs,
            debounceMs = connectionsResetDebounceMs,
            commandManager = commandManager,
            closeRecentFn = { r -> closeRecentConnectionsBestEffort(r) },
            updateLastReset = { ms -> lastConnectionsResetAtMs = ms }
        )
    }

    @Volatile protected var serviceState: ServiceState = ServiceState.STOPPED

    protected override fun resolveEgressNodeName(repo: ConfigRepository, tagOrSelector: String?): String? {
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

    protected override fun notifyRemoteStateNow() {
        val activeLabel = runCatching {
            val repo = ConfigRepository.getInstance(applicationContext)
            val activeNodeId = repo.activeNodeId.value
            val nodeName = resolveNotificationNodeLabel(
                selectedNodeName = repo.nodes.value.find { it.id == activeNodeId }?.name,
                selectedNodeStoreLabel = VpnStateStore.getSelectedNodeLabel()
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

    protected override fun requestRemoteStateUpdate(force: Boolean) {
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

    protected override fun updateServiceState(state: ServiceState) {
        if (serviceState == state) return
        serviceState = state
        requestRemoteStateUpdate(force = true)
    }

    /**
     *
     * @return true if hot switch triggered successfully, false if restart is needed
     *
     * 核心原理:
     * sing-box 的 Selector.SelectOutbound() 内部会调用 interruptGroup.Interrupt(interruptExternalConnections)
     * 当 PROXY selector 配置了 interrupt_exist_connections=true 时,
     * selectOutbound 会自动中断所有外部连接(入站连接)
     */

    override suspend fun hotSwitchNode(nodeTag: String): Boolean {
        if (!coreManager.isServiceRunning() || !SingBoxService.isRunning) return false

        try {
            L.connection("HotSwitch", "Starting switch to: $nodeTag")

            // Step 1: 唤醒核心
            coreManager.wakeService()
            L.step("HotSwitch", 1, 2, "Called wakeService()")

            L.step("HotSwitch", 2, 2, "Calling SelectorManager.switchNode...")

            when (val result = serviceSelectorManager.switchNode(nodeTag)) {
                is com.kunk.singbox.service.manager.SelectorManager.SwitchResult.Success -> {
                    L.result("HotSwitch", true, "Switched to $nodeTag via ${result.method}")
                    requestNotificationUpdate(force = true)
                    return true
                }
                is com.kunk.singbox.service.manager.SelectorManager.SwitchResult.NeedRestart -> {
                    L.warn("HotSwitch", "Need restart: ${result.reason}")
                    // 需要完整重启，返回 false 让调用者处理
                    return false
                }
                is com.kunk.singbox.service.manager.SelectorManager.SwitchResult.Failed -> {
                    L.error("HotSwitch", "Failed: ${result.error}")
                    return false
                }
            }
        } catch (e: Exception) {
            L.error("HotSwitch", "Unexpected exception", e)
            return false
        }
    }

    protected override fun cacheUidToPackage(uid: Int, pkg: String) {
        if (uid <= 0 || pkg.isBlank()) return
        uidToPackageCache[uid] = pkg
        if (uidToPackageCache.size > maxUidToPackageCacheSize) {
            uidToPackageCache.clear()
        }
    }

    protected override fun requestCoreNetworkReset(reason: String, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val parsedReason = parseRecoveryReason(reason)
        if (
            parsedReason == RecoveryReason.NETWORK_TYPE_CHANGED ||
            parsedReason == RecoveryReason.NETWORK_VALIDATED
        ) {
            lastAutoFailoverNetworkEventAtMs = System.currentTimeMillis()
        }
        val request = RecoveryRequest(
            reason = parsedReason,
            rawReason = reason,
            force = force,
            requestedAtMs = now,
            merged = false
        )
        submitRecoveryRequest(request)
    }

    protected override fun parseRecoveryReason(reason: String): RecoveryReason {
        val normalized = reason.trim().lowercase()
        return when {
            normalized.contains("network_type_changed") ||
                normalized.contains("typechange") -> RecoveryReason.NETWORK_TYPE_CHANGED
            normalized.contains("doze_exit") -> RecoveryReason.DOZE_EXIT
            normalized.contains("network_validated") -> RecoveryReason.NETWORK_VALIDATED
            normalized.contains("vpnhealth") || normalized.contains("vpn_health") -> RecoveryReason.VPN_HEALTH
            normalized.contains("app_foreground") -> RecoveryReason.APP_FOREGROUND
            normalized.contains("screen_on") -> RecoveryReason.SCREEN_ON
            else -> RecoveryReason.UNKNOWN
        }
    }

    protected override fun handleTrafficUpdateForAutoFailover(snapshot: TrafficMonitor.TrafficSnapshot) {
        val totalSpeed = snapshot.uploadSpeed + snapshot.downloadSpeed
        if (totalSpeed < SingBoxService.AUTO_FAILOVER_MEANINGFUL_TRAFFIC_BPS) {
            return
        }
        lastMeaningfulTrafficAtMs = System.currentTimeMillis()
        isProxyIdleForAutoFailover = false
    }

    protected override fun submitAutoFailoverSuspicion(trigger: String) {
        if (autoFailoverJob?.isActive == true) {
            Log.d(SingBoxService.TAG, "[AutoFailover] suspicion ignored, job already running: $trigger")
            return
        }

        val now = System.currentTimeMillis()
        val context = NodeAutoFailoverPolicy.TriggerContext(
            isVpnRunning = SingBoxService.isRunning,
            isManuallyStopped = SingBoxService.isManuallyStopped,
            isAutoFailoverInFlight = autoFailoverJob?.isActive == true,
            isRecoveryInFlight = recoveryInFlight,
            inStartupGracePeriod = isAutoFailoverStartupGracePeriod(now),
            inNetworkChangeGracePeriod = isAutoFailoverNetworkGracePeriod(now),
            isProxyIdle = isProxyIdleForAutoFailover,
            lastMeaningfulTrafficAtMs = lastMeaningfulTrafficAtMs,
            nowAtMs = now,
            lastAutoFailoverAtMs = VpnStateStore.getLastAutoFailoverAtMs(),
            budgetWindowStartAtMs = VpnStateStore.getAutoFailoverWindowStartAtMs(),
            budgetCount = VpnStateStore.getAutoFailoverCountInWindow()
        )

        if (!NodeAutoFailoverPolicy.shouldStartProbe(context)) {
            Log.d(SingBoxService.TAG, "[AutoFailover] suspicion ignored by policy: $trigger")
            return
        }

        autoFailoverJob = autoFailoverScope.launch {
            runAutoFailoverProbeSequence(trigger)
        }
    }

    protected override fun isAutoFailoverStartupGracePeriod(nowAtMs: Long): Boolean {
        val startedAtMs = autoFailoverServiceStartedAtMs
        if (startedAtMs <= 0L || nowAtMs < startedAtMs) {
            return false
        }
        return nowAtMs - startedAtMs < SingBoxService.AUTO_FAILOVER_STARTUP_GRACE_MS
    }

    protected override fun isAutoFailoverNetworkGracePeriod(nowAtMs: Long): Boolean {
        val eventAtMs = lastAutoFailoverNetworkEventAtMs
        if (eventAtMs <= 0L || nowAtMs < eventAtMs) {
            return false
        }
        return nowAtMs - eventAtMs < SingBoxService.AUTO_FAILOVER_NETWORK_GRACE_MS
    }

    protected override suspend fun runAutoFailoverProbeSequence(trigger: String) {
        try {
            val currentTag = resolveCurrentProxyOutboundTag()
            if (currentTag.isNullOrBlank()) {
                Log.d(SingBoxService.TAG, "[AutoFailover] skip, no current PROXY selection: $trigger")
                return
            }

            val firstEvaluation = runAutoFailoverProbeRound(currentTag)
            when {
                firstEvaluation.outcome == NodeAutoFailoverPolicy.ProbeOutcome.CURRENT_HEALTHY -> {
                    Log.i(SingBoxService.TAG, "[AutoFailover] current node healthy on first probe: $currentTag")
                }

                firstEvaluation.outcome !=
                    NodeAutoFailoverPolicy.ProbeOutcome.CURRENT_FAILED_WITH_ALTERNATIVE -> {
                    Log.i(
                        SingBoxService.TAG,
                        "[AutoFailover] probe did not find a healthy alternative: ${firstEvaluation.outcome}"
                    )
                }

                else -> {
                    handleSecondAutoFailoverProbe(
                        currentTag = currentTag,
                        firstEvaluation = firstEvaluation,
                        trigger = trigger
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(SingBoxService.TAG, "[AutoFailover] probe sequence failed: $trigger", e)
        } finally {
            autoFailoverJob = null
        }
    }

    protected override suspend fun handleSecondAutoFailoverProbe(
        currentTag: String,
        firstEvaluation: NodeAutoFailoverPolicy.ProbeEvaluation,
        trigger: String
    ) {
        delay(SingBoxService.AUTO_FAILOVER_PROBE_RETRY_DELAY_MS)
        val secondEvaluation = runAutoFailoverProbeRound(currentTag)
        when {
            secondEvaluation.outcome !=
                NodeAutoFailoverPolicy.ProbeOutcome.CURRENT_FAILED_WITH_ALTERNATIVE -> {
                Log.i(
                    SingBoxService.TAG,
                    "[AutoFailover] second probe recovered or no alternative: ${secondEvaluation.outcome}"
                )
            }

            secondEvaluation.alternativeTag.isNullOrBlank() && firstEvaluation.alternativeTag.isNullOrBlank() -> {
                Log.i(SingBoxService.TAG, "[AutoFailover] second probe has no target alternative")
            }

            else -> {
                val targetTag = secondEvaluation.alternativeTag
                    ?: firstEvaluation.alternativeTag.orEmpty()
                performAutoFailoverSwitch(currentTag, targetTag, trigger)
            }
        }
    }

    protected override suspend fun runAutoFailoverProbeRound(
        currentTag: String
    ): NodeAutoFailoverPolicy.ProbeEvaluation {
        val results = testGroupCandidatesLatency("PROXY")
        val quarantined = loadActiveAutoFailoverQuarantine(System.currentTimeMillis())
        val evaluation = NodeAutoFailoverPolicy.evaluateProbe(
            currentTag = currentTag,
            urlTestResults = results,
            quarantinedTags = quarantined.map { it.tag }.toSet()
        )
        Log.i(
            SingBoxService.TAG,
            "[AutoFailover] probe current=$currentTag outcome=${evaluation.outcome} " +
                "alt=${evaluation.alternativeTag ?: "(none)"} delays=${results.size}"
        )
        return evaluation
    }

    protected override suspend fun testGroupCandidatesLatency(groupTag: String): Map<String, Int> = coroutineScope {
        val config = loadLastRunningConfig() ?: return@coroutineScope emptyMap()
        val outbounds = config.outbounds.orEmpty()
        val byTag = outbounds.associateBy { it.tag }
        val groupCandidates = byTag[groupTag]
            ?.outbounds
            .orEmpty()
            .mapNotNull { byTag[it] }
            .ifEmpty {
                outbounds.filter { outbound -> outbound.type !in SingBoxService.LATENCY_SKIPPED_OUTBOUND_TYPES }
            }
        if (groupCandidates.isEmpty()) return@coroutineScope emptyMap()

        val settings = SettingsRepository.getInstance(this@SingBoxServicePart1).settings.first()
        val semaphore = Semaphore(settings.latencyTestConcurrency.coerceIn(1, 20))
        val core = SingBoxCore.getInstance(this@SingBoxServicePart1)
        val results = ConcurrentHashMap<String, Int>()

        groupCandidates.map { outbound ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val latency = runCatching {
                        core.testOutboundLatency(outbound, outbounds)
                    }.getOrDefault(-1L)
                    if (latency > 0L && latency <= Int.MAX_VALUE) {
                        results[outbound.tag] = latency.toInt()
                    }
                }
            }
        }.awaitAll()

        results.toMap()
    }

    protected override fun loadLastRunningConfig(): SingBoxConfig? {
        val configPath = SingBoxService.lastConfigPath ?: File(filesDir, "running_config.json").absolutePath
        return runCatching {
            val configContent = File(configPath).readText()
            gson.fromJson(configContent, SingBoxConfig::class.java)
        }.onFailure { e ->
            Log.w(SingBoxService.TAG, "[AutoFailover] failed to load running config for latency test: ${e.message}")
        }.getOrNull()
    }

    protected override suspend fun performAutoFailoverSwitch(
        currentTag: String,
        targetTag: String,
        trigger: String
    ) {
        val now = System.currentTimeMillis()
        val currentQuarantine = loadActiveAutoFailoverQuarantine(now).toMutableList()
        currentQuarantine.add(NodeAutoFailoverPolicy.createQuarantineRecord(currentTag, now))
        val cleanedQuarantine = NodeAutoFailoverPolicy.cleanupExpiredQuarantine(currentQuarantine, now)
        val budgetState = NodeAutoFailoverPolicy.registerFailoverAttempt(
            windowStartAtMs = VpnStateStore.getAutoFailoverWindowStartAtMs(),
            count = VpnStateStore.getAutoFailoverCountInWindow(),
            nowAtMs = now
        )

        VpnStateStore.setLastAutoFailoverAtMs(now)
        VpnStateStore.setAutoFailoverWindowStartAtMs(budgetState.windowStartAtMs)
        VpnStateStore.setAutoFailoverCountInWindow(budgetState.count)
        VpnStateStore.setAutoFailoverQuarantinedTags(NodeAutoFailoverPolicy.encodeQuarantine(cleanedQuarantine))
        VpnStateStore.setLastAutoFailoverNodeTag(currentTag)

        val success = hotSwitchNode(targetTag)
        if (success) {
            val configRepository = ConfigRepository.getInstance(this@SingBoxServicePart1)
            val node = configRepository.getNodeByName(targetTag)
            val displayName = node?.name ?: targetTag
            VpnStateStore.setActiveLabel(displayName)
            realTimeNodeName = displayName
            runCatching {
                configRepository.syncActiveNodeFromProxySelection(displayName)
            }
            trafficMonitor.resetStallCounter()
            stallRefreshAttempts = 0
            isProxyIdleForAutoFailover = false
            requestNotificationUpdate(force = true)
            requestRemoteStateUpdate(force = true)
            routeGroupSelector.requestImmediateReselect("vpn_health_auto_failover")
            LogRepository.getInstance().addLog(
                "INFO: Auto failover switched from $currentTag to $displayName (trigger=$trigger)"
            )
            Log.i(SingBoxService.TAG, "[AutoFailover] switched from $currentTag to $displayName, trigger=$trigger")
            return
        }

        Log.w(SingBoxService.TAG, "[AutoFailover] hot switch failed, falling back to restart: $targetTag")
        val configPath = pendingHotSwitchFallbackConfigPath ?: File(filesDir, "running_config.json").absolutePath
        val restartIntent = Intent(this@SingBoxServicePart1, SingBoxService::class.java).apply {
            action = SingBoxService.ACTION_START
            putExtra(SingBoxService.EXTRA_CONFIG_PATH, configPath)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }
    }

    protected override fun loadActiveAutoFailoverQuarantine(nowAtMs: Long): List<NodeAutoFailoverPolicy.QuarantinedNode> {
        val records = NodeAutoFailoverPolicy.decodeQuarantine(VpnStateStore.getAutoFailoverQuarantinedTags())
        val cleaned = NodeAutoFailoverPolicy.cleanupExpiredQuarantine(records, nowAtMs)
        if (cleaned.size != records.size) {
            VpnStateStore.setAutoFailoverQuarantinedTags(NodeAutoFailoverPolicy.encodeQuarantine(cleaned))
        }
        return cleaned
    }

    protected override fun resolveCurrentProxyOutboundTag(): String? {
        return commandManager.getSelectedOutbound("PROXY")
            ?.takeIf { it.isNotBlank() }
            ?: SelectorManager.getSelectedOutbound()?.takeIf { it.isNotBlank() }
            ?: BoxWrapperManager.getSelectedOutbound()?.takeIf { it.isNotBlank() }
    }
}
