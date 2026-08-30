@file:Suppress("TooManyFunctions", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "LongMethod", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeConst")

package com.kunk.singbox.viewmodel

import com.kunk.singbox.R
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.model.ConnectionState
import com.kunk.singbox.model.ConnectionStats
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.TrafficCaptureMode
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.manager.VpnServiceManager
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.service.VpnTileService
import com.kunk.singbox.service.manager.VpnStopInitiator
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.utils.perf.PerfTracer
import com.kunk.singbox.repository.*
import com.kunk.singbox.viewmodel.shared.NodeDisplaySettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        internal const val TAG = "DashboardViewModel"
        internal const val START_STOPPED_CONFIRM_MS = 5_000L
        internal const val START_MONITOR_TIMEOUT_MS = 60_000L
        private const val START_REBIND_INTERVAL_MS = 2_000L

        internal fun shouldReportStartError(currentError: String?): Boolean {
            return !currentError.isNullOrBlank()
        }

        internal fun shouldPresentServiceError(connectionState: ConnectionState, error: String?): Boolean {
            return !error.isNullOrBlank() && connectionState != ConnectionState.Connecting
        }

        internal fun shouldFinishStartAsStopped(
            serviceState: ServiceState,
            ipcBound: Boolean,
            observedActiveState: Boolean,
            elapsedMs: Long
        ): Boolean {
            return ipcBound &&
                serviceState == ServiceState.STOPPED &&
                (observedActiveState || elapsedMs >= START_STOPPED_CONFIRM_MS)
        }

        internal fun hasStartMonitorTimedOut(elapsedMs: Long): Boolean {
            return elapsedMs >= START_MONITOR_TIMEOUT_MS
        }

        internal fun requiresFullRestart(
            perAppSettingsChanged: Boolean,
            tunSettingsChanged: Boolean,
            routingModeChanged: Boolean
        ): Boolean {
            return perAppSettingsChanged || tunSettingsChanged || routingModeChanged
        }

        internal fun shouldStopOppositeService(
            desiredMode: VpnStateStore.CoreMode,
            activeMode: VpnStateStore.CoreMode
        ): Boolean = activeMode != VpnStateStore.CoreMode.NONE && activeMode != desiredMode
    }

    internal val configRepository = ConfigRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)
    private val singBoxCore = SingBoxCore.getInstance(application)

    // 使用共享的设置状态，和 NodesViewModel 共享同一份数据
    private val displaySettings = NodeDisplaySettings.getInstance(application)

    // Connection state
    internal val _connectionState = MutableStateFlow(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    val dataPlaneReadiness: StateFlow<com.kunk.singbox.ipc.DataPlaneReadinessSnapshot> = SingBoxRemote.readiness

    // Stats
    internal val _statsBase = MutableStateFlow(ConnectionStats(0, 0, 0, 0, 0))
    internal val _connectedAtElapsedMs = MutableStateFlow<Long?>(null)
    @Volatile internal var statsUiActive = false
    internal val trafficMonitor = DashboardTrafficMonitor(viewModelScope) { snapshot ->
        _statsBase.update { current ->
            current.copy(
                uploadSpeed = snapshot.uploadSpeed,
                downloadSpeed = snapshot.downloadSpeed,
                uploadTotal = snapshot.uploadTotal,
                downloadTotal = snapshot.downloadTotal
            )
        }
    }

    private val durationMsFlow: Flow<Long> = connectionState.flatMapLatest { state ->
        if (state == ConnectionState.Connected) {
            flow {
                while (true) {
                    val start = _connectedAtElapsedMs.value
                    emit(if (start != null) SystemClock.elapsedRealtime() - start else 0L)
                    delay(1000)
                }
            }
        } else {
            flowOf(0L)
        }
    }

    fun setActiveProfile(profileId: String) {
        viewModelScope.launch {
            val result = configRepository.setActiveProfileWithResult(profileId)
            val name = profiles.value.find { it.id == profileId }?.name
            if (!name.isNullOrBlank()) {
                val message = if (result is ConfigRepository.NodeSwitchResult.Failed) {
                    result.reason
                } else {
                    getApplication<Application>().getString(R.string.node_switch_success, name)
                }
                emitToast(message)
            }
        }
    }

    fun setActiveNode(nodeId: String) {
        viewModelScope.launch {
            val node = nodes.value.find { it.id == nodeId }
            val result = configRepository.setActiveNodeWithResult(nodeId)

            if (SingBoxRemote.isRunning.value && node != null) {
                val msg = when (result) {
                    is ConfigRepository.NodeSwitchResult.Success,
                    is ConfigRepository.NodeSwitchResult.NotRunning -> getApplication<Application>().getString(R.string.node_switch_success, node.name)

                    is ConfigRepository.NodeSwitchResult.Failed -> result.reason
                }
                emitToast(msg)
            }
        }
    }

    fun enableAutoSelection() {
        val profileId = activeProfileId.value ?: return
        viewModelScope.launch {
            val result = configRepository.enableAutoSelectionWithResult(profileId)
            emitToast(
                getApplication<Application>().getString(
                    if (result is ConfigRepository.NodeSwitchResult.Failed) {
                        R.string.nodes_auto_selection_failed
                    } else {
                        R.string.nodes_auto_selection_enabled
                    }
                )
            )
        }
    }

    val stats: StateFlow<ConnectionStats> = combine(_statsBase, durationMsFlow) { base, duration ->
        base.copy(duration = duration)
    }.onStart {
        statsUiActive = true
        startTrafficMonitor()
    }.onCompletion {
        statsUiActive = false
        stopTrafficMonitor()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ConnectionStats(0, 0, 0, 0, 0)
    )

    // Ping 测试状态：true = 正在测试中
    internal val _isPingTesting = MutableStateFlow(false)
    val isPingTesting: StateFlow<Boolean> = _isPingTesting.asStateFlow()

    internal var pingTestJob: Job? = null
    internal var startMonitorJob: Job? = null

    // Active profile and node from ConfigRepository
    val activeProfileId: StateFlow<String?> = configRepository.activeProfileId
    val activeNodeId: StateFlow<String?> = configRepository.activeNodeId
    val isAutoSelectionEnabled: StateFlow<Boolean> = combine(
        activeProfileId,
        configRepository.profileAutoSelections
    ) { profileId, selections ->
        profileId != null && selections[profileId] == true
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = configRepository.isProfileAutoSelectionEnabled(activeProfileId.value)
    )

    val activeNodeLatency = kotlinx.coroutines.flow.combine(configRepository.nodes, activeNodeId) { nodes, id ->
        nodes.find { it.id == id }?.latencyMs
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    val profiles: StateFlow<List<ProfileUi>> = configRepository.profiles

    val nodes: StateFlow<List<NodeUi>> = combine(
        configRepository.nodes,
        displaySettings.nodeFilter,
        displaySettings.sortType,
        displaySettings.customOrder
    ) { nodes, filter, sortType, customOrder ->
        buildDashboardNodes(nodes, filter, sortType, customOrder)
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    internal fun emitToast(message: String) {
        _toastEvents.tryEmit(message)
    }

    // VPN 权限请求结果
    internal val _vpnPermissionNeeded = MutableStateFlow(false)
    val vpnPermissionNeeded: StateFlow<Boolean> = _vpnPermissionNeeded.asStateFlow()

    // 2025-fix-v12: 用于确保状态监听器只启动一次
    @Volatile private var stateCollectorStarted = false

    // 2025-fix: 标记是否在启动时检测到了系统 VPN
    // 用于过滤 IPC 连接初期的虚假 STOPPED 状态
    private var systemVpnDetectedOnBoot = false

    // 2025-fix: 使用更健壮的 IPC 绑定逻辑
    // 原因: 原来的等待只有 1000ms，在系统负载高时可能不够
    // 改进: 增加重试次数 + 每次重试前先尝试 ensureBound
    init {
        viewModelScope.launch {
            // 第一阶段：确保 IPC 绑定（带重试）
            for (attempt in 1..5) {
                runCatching { SingBoxRemote.ensureBound(getApplication()) }
                delay(300) // 每次等待 300ms，总共最大 1500ms
                if (SingBoxRemote.isBound()) {
                    Log.i(TAG, "IPC bound successfully on attempt $attempt")
                    break
                }
                Log.w(TAG, "IPC not bound, attempt $attempt/5")
            }

            // 第二阶段：同步初始状态（从 MMKV 兜底）
            runCatching {
                val context = getApplication<Application>()
                val cm = context.getSystemService(ConnectivityManager::class.java)
                @Suppress("DEPRECATION")
                val hasSystemVpn = cm?.allNetworks?.any { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@any false
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } == true

                if (hasSystemVpn) {
                    systemVpnDetectedOnBoot = true
                }

                val persisted = VpnStateStore.getActive()

                // 收紧：仅当 IPC 已确认 STOPPED 才清运行态；IPC 未可信时不写，避免"假停"窗口
                if (shouldClearPersistedActiveOnBoot(
                        hasSystemVpn = hasSystemVpn,
                        persistedActive = persisted,
                        mode = VpnStateStore.getMode(),
                        ipcBound = SingBoxRemote.isBound(),
                        serviceState = SingBoxRemote.state.value
                    )
                ) {
                    VpnTileService.persistVpnState(false)
                }

                val trustedInitialState = resolveTrustedDashboardConnectionState(
                    serviceState = SingBoxRemote.state.value,
                    ipcBound = SingBoxRemote.isBound(),
                    readiness = SingBoxRemote.readiness.value,
                    mode = VpnStateStore.getMode(),
                    apiLevel = Build.VERSION.SDK_INT,
                    nowElapsedMs = SystemClock.elapsedRealtime()
                )
                if (trustedInitialState == ConnectionState.Connected) {
                    _connectionState.value = ConnectionState.Connected
                    _connectedAtElapsedMs.value = SystemClock.elapsedRealtime()
                } else {
                    _connectionState.value = trustedInitialState
                }
            }

            // 第三阶段：确保状态收集器启动（关键修复）
            // 原来只在绑定成功后才启动，现在无论绑定是否成功都启动
            // 这样即使 IPC 绑定失败，MMKV 状态也能持续更新 UI
            startStateCollector()
        }

        // Surface service-level startup errors on UI
        viewModelScope.launch {
            SingBoxRemote.lastError.collect { err ->
                if (DashboardViewModel.shouldPresentServiceError(_connectionState.value, err)) {
                    emitToast(err.orEmpty())
                }
            }
        }

        // 节点变化时清理首页缓存延迟，避免旧值长期覆盖节点列表中的最新延迟
        viewModelScope.launch {
            activeNodeId
                .drop(1)
                .distinctUntilChanged()
                .collect {
                    stopPingTest()
                }
        }
    }
    /**
     * 2025-fix-v12: 启动状态监听器
     * 确保只在 IPC 绑定完成后调用一次
     * 注意: 现在允许重复调用（幂等），内部会检查是否已启动
     */
    // 2025-fix: 用于处理连接状态变更的防抖 Job
    private var pendingIdleJob: Job? = null
    internal var startGraceUntilElapsedMs: Long? = null
    private var refreshStateJob: Job? = null
    internal var startCoreJob: Job? = null
    internal var startServiceDispatched = false
    internal var stopRequestedByUser = false
    /**
     * 启动状态收集器（幂等方法）
     * 2025-fix-v12: 确保只启动一次，但保证在 init 和 refreshState 中都会被调用
     * 关键修复: 使用 synchronized 确保线程安全，同时允许在必要时重新启动
     */
    private fun startStateCollector() {
        // 使用 synchronized 确保只启动一次
        if (stateCollectorStarted) {
            Log.d(TAG, "startStateCollector: already started, skipping")
            return
        }
        synchronized(this) {
            if (stateCollectorStarted) return
            stateCollectorStarted = true
        }
        // 收集器: 监听 SingBoxService 状态变化
        val stateFlow = SingBoxRemote.state
        viewModelScope.launch {
            stateFlow.collect { state ->
                if (stopRequestedByUser && state != ServiceState.STOPPED) return@collect
                when (resolveDashboardConnectionState(state)) {
                    ConnectionState.Connected -> {
                        if (stopRequestedByUser) return@collect
                        systemVpnDetectedOnBoot = false
                        setConnectionState(ConnectionState.Connected)
                    }
                    ConnectionState.Connecting -> {
                        if (stopRequestedByUser) return@collect
                        systemVpnDetectedOnBoot = false
                        setConnectionState(ConnectionState.Connecting)
                    }
                    ConnectionState.Disconnecting -> {
                        systemVpnDetectedOnBoot = false
                        setConnectionState(ConnectionState.Disconnecting)
                    }
                    ConnectionState.Idle -> {
                        setConnectionState(ConnectionState.Idle)
                    }
                    ConnectionState.Error -> setConnectionState(ConnectionState.Error)
                }
            }
        }
        viewModelScope.launch {
            SingBoxRemote.readiness.collect {
                val state = SingBoxRemote.state.value
                if (!stopRequestedByUser || state == ServiceState.STOPPED) {
                    setConnectionState(resolveDashboardConnectionState(state))
                }
            }
        }

        // 运行态 activeLabel 只用于展示，不写回用户手选节点（auto-failover 不得持久化选择）

        Log.i(TAG, "startStateCollector: collectors launched")
    }

    private fun resolveDashboardConnectionState(state: ServiceState): ConnectionState {
        return resolveTrustedDashboardConnectionState(
            serviceState = state,
            ipcBound = SingBoxRemote.isBound(),
            readiness = SingBoxRemote.readiness.value,
            mode = VpnStateStore.getMode(),
            apiLevel = Build.VERSION.SDK_INT,
            nowElapsedMs = SystemClock.elapsedRealtime()
        )
    }

    internal fun setConnectionState(newState: ConnectionState) {
        if (newState == ConnectionState.Disconnecting && _connectionState.value == ConnectionState.Connecting) {
            val graceUntil = startGraceUntilElapsedMs
            if (graceUntil != null && SystemClock.elapsedRealtime() < graceUntil) {
                return
            }
        }
        when (newState) {
            ConnectionState.Connected -> {
                // 如果有挂起的"变更为Idle"的任务，立即取消，说明是虚惊一场
                pendingIdleJob?.cancel()
                pendingIdleJob = null
                startGraceUntilElapsedMs = null

                if (_connectionState.value != ConnectionState.Connected) {
                    _connectionState.value = ConnectionState.Connected
                    _connectedAtElapsedMs.value = SystemClock.elapsedRealtime()
                    startTrafficMonitor()
                }
            }
            ConnectionState.Idle -> {
                // 如果当前是已连接，不要立即断开，而是延迟执行
                if (_connectionState.value == ConnectionState.Connected) {
                    // 如果已经在等待断开，不要重复创建
                    if (pendingIdleJob?.isActive == true) return

                    pendingIdleJob = viewModelScope.launch {
                        // 2025-fix-v7: 如果 MMKV 记录 VPN 正在运行，给更长宽限期等 IPC 恢复
                        // 避免 IPC 还在绑定中时误触发断连（从 300ms 延长到 3000ms）
                        val delayTime = when {
                            VpnStateStore.getActive() -> 3000L
                            systemVpnDetectedOnBoot -> 1000L
                            else -> 300L
                        }
                        delay(delayTime)

                        // 宽限期过，再次检查 SingBoxRemote 状态
                        // 只有当服务端依然坚持是 STOPPED 时，才真正断开 UI
                        if (SingBoxRemote.state.value == ServiceState.STOPPED) {
                            performDisconnect()
                        }
                        // 宽限期结束，标记失效
                        systemVpnDetectedOnBoot = false
                        pendingIdleJob = null
                    }
                } else if (_connectionState.value == ConnectionState.Connecting) {
                    val graceUntil = startGraceUntilElapsedMs
                    if (graceUntil != null) {
                        val now = SystemClock.elapsedRealtime()
                        val remaining = graceUntil - now
                        if (remaining > 0) {
                            if (pendingIdleJob?.isActive == true) return
                            pendingIdleJob = viewModelScope.launch {
                                delay(remaining)
                                if (SingBoxRemote.state.value == ServiceState.STOPPED) {
                                    performDisconnect()
                                }
                                pendingIdleJob = null
                            }
                            return
                        }
                    }
                    performDisconnect()
                } else {
                    // 当前不是连接状态，直接更新
                    performDisconnect()
                }
            }
            else -> {
                // 其他状态（Connecting/Disconnecting/Error）直接更新
                pendingIdleJob?.cancel()
                if (newState == ConnectionState.Connecting) {
                    startGraceUntilElapsedMs = SystemClock.elapsedRealtime() + START_MONITOR_TIMEOUT_MS
                } else {
                    startGraceUntilElapsedMs = null
                }
                if (_connectionState.value != newState) {
                    _connectionState.value = newState
                }
            }
        }
    }
    internal fun performDisconnect() {
        stopRequestedByUser = false
        if (_connectionState.value != ConnectionState.Idle) {
            _connectionState.value = ConnectionState.Idle
            _connectedAtElapsedMs.value = null
            stopTrafficMonitor()
            stopPingTest()
            _statsBase.value = ConnectionStats(0, 0, 0, 0, 0)
        }
    }
    /**
     * 刷新 VPN 状态。
     *
     * 首页只展示 IPC 返回的实时服务态。MMKV 里的 active/pending 只能作为服务恢复线索，
     * 不能直接驱动首页开关，否则清后台后残留的 starting/running 会让耿鬼先醒再睡。
     */
    fun refreshState() {
        refreshStateJob?.cancel()
        refreshStateJob = viewModelScope.launch {
            val context = getApplication<Application>()

            SingBoxRemote.ensureBound(context)

            // 统一前台恢复入口：由 AppLifecycleObserver -> IPC -> :bg 网关处理
            // 注意：这里不再主动调用 SingBoxRemote.notifyAppLifecycle(true)
            // 因为 AppLifecycleObserver.onStart 已经负责了生命周期的同步，
            // 避免产生重复的前台通知导致网络恢复抖动

            // 只信任已绑定 IPC 的实时状态，避免清后台后的过期 MMKV active/pending 造成假启动态
            val phase1State = resolveDashboardConnectionState(SingBoxRemote.state.value)
            setConnectionState(phase1State)

            startStateCollector()

            var retries = 0
            val maxRetries = 80 // 80 * 100ms = 8 秒
            while (!SingBoxRemote.isBound() && retries < maxRetries) {
                delay(100)
                retries++
            }

            if (SingBoxRemote.isBound()) {
                val state = SingBoxRemote.state.value
                Log.i(TAG, "refreshState Phase 2: state=$state, bound=true, retries=$retries")
                setConnectionState(resolveDashboardConnectionState(state))
            } else {
                Log.w(TAG, "refreshState Phase 2: IPC not bound, showing idle until real service state arrives")
                setConnectionState(ConnectionState.Idle)
            }
        }
    }

    fun toggleConnection() {
        viewModelScope.launch {
            when (_connectionState.value) {
                ConnectionState.Idle, ConnectionState.Error -> {
                    // P0 Optimization: Optimistic UI
                    startGraceUntilElapsedMs = SystemClock.elapsedRealtime() + START_MONITOR_TIMEOUT_MS
                    _connectionState.value = ConnectionState.Connecting
                    startCore()
                }
                ConnectionState.Connecting -> {
                    // P0 Optimization: Optimistic UI
                    startGraceUntilElapsedMs = null
                    _connectionState.value = ConnectionState.Disconnecting
                    stopVpn()
                }
                ConnectionState.Connected -> {
                    // P0 Optimization: Optimistic UI
                    startGraceUntilElapsedMs = null
                    _connectionState.value = ConnectionState.Disconnecting
                    stopVpn()
                }
                ConnectionState.Disconnecting -> Unit
            }
        }
    }

    @Suppress("LongMethod", "CognitiveComplexMethod")
    fun restartVpn() {
        viewModelScope.launch {
            val context = getApplication<Application>()

            val settings = SettingsRepository.getInstance(context).settings.first()
            val captureMode = settings.resolvedTrafficCaptureMode()
            if (captureMode == TrafficCaptureMode.VPN) {
                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) {
                    _vpnPermissionNeeded.value = true
                    return@launch
                }
            }

            val configResult = withContext(Dispatchers.IO) {
                val settingsRepository = SettingsRepository.getInstance(context)
                settingsRepository.checkAndMigrateRuleSets()
                configRepository.generateConfigFile(
                    candidateRequestId = VpnServiceManager.newCandidateRequestId(captureMode).orEmpty()
                )
            }

            if (configResult == null) {
                emitToast(
                    configRepository.getLastConfigGenerationError()
                        ?: getApplication<Application>().getString(R.string.dashboard_config_generation_failed)
                )
                return@launch
            }

            val useTun = captureMode == TrafficCaptureMode.VPN
            val perAppSettingsChanged = VpnStateStore.hasPerAppVpnSettingsChanged(
                appMode = settings.vpnAppMode.name,
                allowlist = settings.vpnAllowlist,
                blocklist = settings.vpnBlocklist
            )

            logRestartDebugInfo(settings)

            val tunSettingsChanged = VpnStateStore.hasTunSettingsChanged(
                tunStack = settings.tunStack.name,
                tunMtu = settings.tunMtu,
                autoRoute = settings.autoRoute,
                strictRoute = settings.strictRoute,
                proxyPort = settings.proxyPort
            )

            val routingModeChanged = VpnStateStore.hasRoutingModeChanged(settings.routingMode.name)
            val requiresFullRestart = DashboardViewModel.requiresFullRestart(
                perAppSettingsChanged = perAppSettingsChanged,
                tunSettingsChanged = tunSettingsChanged,
                routingModeChanged = routingModeChanged
            )

            if (useTun && SingBoxRemote.isRunning.value && !requiresFullRestart) {
                Log.i(TAG, "Settings are hot-reloadable, attempting kernel hot reload")
                if (tryHotReload(configResult.path)) {
                    Log.i(TAG, "Hot reload succeeded, settings applied without VPN reconnection")
                    return@launch
                }
                Log.w(TAG, "Hot reload failed, falling back to full restart")
            } else {
                if (requiresFullRestart) {
                    Log.i(
                        TAG,
                        "Full restart required: perAppChanged=$perAppSettingsChanged, " +
                            "tunChanged=$tunSettingsChanged, routingModeChanged=$routingModeChanged"
                    )
                }
            }

            performRestart(
                context,
                configResult.path,
                configResult.requestId,
                captureMode,
                requiresFullRestart
            )
        }
    }

    private fun logRestartDebugInfo(settings: AppSettings) {
        Log.d(
            TAG,
            "restartVpn: mode=${settings.resolvedTrafficCaptureMode()}, isRunning=${SingBoxRemote.isRunning.value}"
        )
        Log.d(
            TAG,
            "restartVpn: currentMode=${settings.vpnAppMode.name}, " +
                "allowlist=${settings.vpnAllowlist.take(100)}, blocklist=${settings.vpnBlocklist.take(100)}"
        )
    }

    private suspend fun tryHotReload(configPath: String): Boolean {
        val configContent = withContext(Dispatchers.IO) {
            runCatching { java.io.File(configPath).readText() }.getOrNull()
        }

        if (!configContent.isNullOrEmpty()) {
            Log.i(TAG, "Attempting kernel hot reload via IPC...")

            val result = withContext(Dispatchers.IO) {
                SingBoxRemote.hotReloadConfig(configContent)
            }

            when (result) {
                SingBoxRemote.HotReloadResult.SUCCESS -> {
                    Log.i(TAG, "Hot reload succeeded via IPC")
                    return true
                }
                SingBoxRemote.HotReloadResult.IPC_ERROR -> {
                    Log.w(TAG, "Hot reload IPC failed, falling back to traditional restart")
                }
                else -> {
                    Log.w(TAG, "Hot reload failed (code=$result), falling back to traditional restart")
                }
            }
        }
        return false
    }

    private suspend fun performRestart(
        context: Context,
        configPath: String,
        requestId: String,
        captureMode: TrafficCaptureMode,
        requiresFullRestart: Boolean
    ) {
        val useTun = captureMode == TrafficCaptureMode.VPN
        if (captureMode == TrafficCaptureMode.ROOT_TRANSPARENT) {
            startServiceCompat(
                context,
                Intent(context, com.kunk.singbox.service.root.RootTransparentForegroundService::class.java).apply {
                    action = com.kunk.singbox.service.root.RootTransparentForegroundService.ACTION_RESTART
                    putExtra(
                        com.kunk.singbox.service.root.RootTransparentForegroundService.EXTRA_CONFIG_PATH,
                        configPath
                    )
                    putExtra(
                        com.kunk.singbox.service.root.RootTransparentForegroundService.EXTRA_APP_ROUTE_REQUEST_ID,
                        requestId
                    )
                }
            )
            return
        }
        if (requiresFullRestart && useTun && SingBoxRemote.isRunning.value) {
            Log.i(TAG, "Runtime settings changed, using full restart to rebuild core")
            val intent = Intent(context, SingBoxService::class.java).apply {
                action = SingBoxService.ACTION_FULL_RESTART
                putExtra(SingBoxService.EXTRA_CONFIG_PATH, configPath)
            }
            startServiceCompat(context, intent)
            return
        }

        PerfTracer.recordEvent(PerfTracer.Phases.FULL_RESTART, "service_restart")

        runCatching {
            if (!com.kunk.singbox.ipc.VpnStateStore.shouldTriggerPrepareRestart(1500L)) {
                Log.d(TAG, "PREPARE_RESTART suppressed (sender throttle)")
            } else {
                context.startService(Intent(context, SingBoxService::class.java).apply {
                    action = SingBoxService.ACTION_PREPARE_RESTART
                    putExtra(
                        SingBoxService.EXTRA_PREPARE_RESTART_REASON,
                        "DashboardViewModel:restartVpn"
                    )
                })
            }
        }

        delay(150)

        val intent = if (useTun) {
            Intent(context, SingBoxService::class.java).apply {
                action = SingBoxService.ACTION_START
                putExtra(SingBoxService.EXTRA_CONFIG_PATH, configPath)
                putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
            }
        } else {
            Intent(context, ProxyOnlyService::class.java).apply {
                action = ProxyOnlyService.ACTION_START
                putExtra(ProxyOnlyService.EXTRA_CONFIG_PATH, configPath)
                putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
            }
        }

        startServiceCompat(context, intent)
    }

    private fun startServiceCompat(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
    private fun startCore() {
        startCoreJob?.cancel()
        startCoreJob = viewModelScope.launch {
            startServiceDispatched = false
            stopRequestedByUser = false
            val context = getApplication<Application>()

            val settings = runCatching {
                SettingsRepository.getInstance(context).settings.first()
            }.getOrNull()

            val captureMode = settings?.resolvedTrafficCaptureMode() ?: TrafficCaptureMode.VPN
            val desiredMode = when (captureMode) {
                TrafficCaptureMode.VPN -> VpnStateStore.CoreMode.VPN
                TrafficCaptureMode.ROOT_TRANSPARENT -> VpnStateStore.CoreMode.ROOT
                TrafficCaptureMode.PROXY_ONLY -> VpnStateStore.CoreMode.PROXY
            }

            if (captureMode == TrafficCaptureMode.VPN) {
                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) {
                    _vpnPermissionNeeded.value = true
                    return@launch
                }
            }

            SingBoxRemote.clearLastErrorForNewStart()
            withContext(Dispatchers.IO) { VpnStateStore.setLastError("") }
            _connectionState.value = ConnectionState.Connecting

            // Only stop an actually active opposite core. Starting an idle service just to stop it
            // creates unnecessary service lifecycle churn and can race the requested startup.
            val activeMode = VpnStateStore.getMode()
            val needToStopOpposite = DashboardViewModel.shouldStopOppositeService(desiredMode, activeMode)
            if (needToStopOpposite) {
                VpnServiceManager.stopVpn(context, VpnStopInitiator.MODE_SWITCH)
            }

            // 如果需要停止对立服务，等待其完全停止
            if (needToStopOpposite) {
                // 先检查对立服务是否正在运行
                val oppositeWasRunning = SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value
                if (oppositeWasRunning) {
                    try {
                        // 增加超时时间：BoxService.close() 可能需要较长时间释放端口
                        withTimeout(8000L) {
                            // 使用 drop(1) 跳过当前值，等待真正的状态变化
                            SingBoxRemote.state
                                .drop(1)
                                .first { it == ServiceState.STOPPED }
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.w(TAG, "Timeout waiting for opposite service to stop")
                    }
                }
                // 原因: BoxService.close() 后端口释放可能有延迟
                delay(500)
            }

            // 生成配置文件并启动 VPN 服务
            try {
                // 在生成配置前先执行强制迁移，修复可能导致 404 的旧配置
                val configResult = withContext(Dispatchers.IO) {
                    val settingsRepository = com.kunk.singbox.repository.SettingsRepository.getInstance(context)
                    settingsRepository.checkAndMigrateRuleSets()
                    configRepository.generateConfigFile(
                        candidateRequestId = VpnServiceManager.newCandidateRequestId(captureMode).orEmpty()
                    )
                }
                if (configResult == null) {
                    _connectionState.value = ConnectionState.Error
                    emitToast(
                        configRepository.getLastConfigGenerationError()
                            ?: getApplication<Application>().getString(R.string.dashboard_config_generation_failed)
                    )
                    return@launch
                }

                VpnServiceManager.startVpn(
                    context = context,
                    mode = captureMode,
                    configPath = configResult.path,
                    requestId = configResult.requestId,
                    cleanCache = true
                ).getOrThrow()
                startServiceDispatched = true

                // 2) 后续只在服务端明确失败（lastErrorFlow）或服务异常退出时才置 Error
                startMonitorJob?.cancel()
                startMonitorJob = viewModelScope.launch {
                    val startTime = SystemClock.elapsedRealtime()
                    val quickFeedbackMs = 1000L
                    var showedStartingHint = false
                    var observedActiveState = false
                    var lastRebindAttemptMs = -START_REBIND_INTERVAL_MS

                    while (!DashboardViewModel.hasStartMonitorTimedOut(SystemClock.elapsedRealtime() - startTime)) {
                        val elapsed = SystemClock.elapsedRealtime() - startTime
                        val serviceState = SingBoxRemote.state.value
                        val ipcBound = SingBoxRemote.isBound()
                        if (serviceState == ServiceState.STARTING || serviceState == ServiceState.RUNNING) {
                            observedActiveState = true
                        }

                        if (SingBoxRemote.readiness.value.isReady(
                                serviceState = serviceState,
                                mode = VpnStateStore.getMode(),
                                ipcBound = ipcBound,
                                apiLevel = Build.VERSION.SDK_INT,
                                nowElapsedMs = SystemClock.elapsedRealtime()
                            )
                        ) {
                            _connectionState.value = ConnectionState.Connected
                            startTrafficMonitor()
                            return@launch
                        }

                        val err = SingBoxRemote.lastError.value
                            .takeIf { it.isNotBlank() }
                            ?: VpnStateStore.getLastError().takeIf { it.isNotBlank() }
                        if (DashboardViewModel.shouldReportStartError(err)) {
                            _connectionState.value = ConnectionState.Error
                            emitToast(err.orEmpty())
                            return@launch
                        }

                        if (DashboardViewModel.shouldFinishStartAsStopped(
                                serviceState = serviceState,
                                ipcBound = ipcBound,
                                observedActiveState = observedActiveState,
                                elapsedMs = elapsed
                            )
                        ) {
                            performDisconnect()
                            return@launch
                        }

                        if (!ipcBound && elapsed - lastRebindAttemptMs >= START_REBIND_INTERVAL_MS) {
                            lastRebindAttemptMs = elapsed
                            SingBoxRemote.ensureBound(context)
                        }

                        if (!showedStartingHint && elapsed >= quickFeedbackMs) {
                            showedStartingHint = true
                            emitToast(getApplication<Application>().getString(R.string.connection_connecting))
                        }

                        val intervalMs = when {
                            elapsed < 10_000L -> 200L
                            else -> 1000L
                        }
                        delay(intervalMs)
                    }

                    VpnTileService.persistVpnPending("")
                    VpnServiceManager.stopVpn(context, VpnStopInitiator.START_TIMEOUT).onFailure { error ->
                        Log.e(TAG, "Failed to stop timed-out VPN start", error)
                    }
                    _connectionState.value = ConnectionState.Error
                    emitToast(
                        getApplication<Application>().getString(
                            R.string.node_start_failed,
                            getApplication<Application>().getString(R.string.common_timeout)
                        )
                    )
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error
                emitToast(getApplication<Application>().getString(R.string.node_start_failed, e.message ?: ""))
            }
        }
    }
    private fun stopVpn() = stopVpnRuntime()

    private fun startPingTest() = startPingTestRuntime()

    private fun stopPingTest() = stopPingTestRuntime()

    fun retestCurrentNodePing() {
        startPingTest()
    }

    fun onVpnPermissionResult(granted: Boolean) = onVpnPermissionResultRuntime(granted) { startCore() }

    fun updateAllSubscriptions() = updateAllSubscriptionsRuntime()

    fun testAllNodesLatency() = testAllNodesLatencyRuntime()

    private fun startTrafficMonitor() {
        if (statsUiActive && _connectionState.value == ConnectionState.Connected) {
            trafficMonitor.start()
        }
    }

    private fun stopTrafficMonitor() {
        trafficMonitor.stop()
    }

    fun getActiveProfileName(): String? = getActiveProfileNameRuntime()

    fun getActiveNodeName(): String? = getActiveNodeNameRuntime()

    override fun onCleared() {
        startMonitorJob?.cancel()
        startMonitorJob = null
        stopTrafficMonitor()
        stopPingTest()
    }
}
