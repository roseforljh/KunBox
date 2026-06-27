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
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.service.VpnTileService
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.viewmodel.shared.NodeDisplaySettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DashboardViewModel"

        internal fun resolveTrustedConnectionStateForTest(serviceState: ServiceState, ipcBound: Boolean): ConnectionState =
            resolveTrustedDashboardConnectionState(serviceState, ipcBound)
    }

    private val configRepository = ConfigRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)
    private val singBoxCore = SingBoxCore.getInstance(application)

    // 使用共享的设置状态，和 NodesViewModel 共享同一份数据
    private val displaySettings = NodeDisplaySettings.getInstance(application)

    // Connection state
    private val _connectionState = MutableStateFlow(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Stats
    private val _statsBase = MutableStateFlow(ConnectionStats(0, 0, 0, 0, 0))
    private val _connectedAtElapsedMs = MutableStateFlow<Long?>(null)
    private val trafficMonitor = DashboardTrafficMonitor(viewModelScope) { snapshot ->
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
        configRepository.setActiveProfile(profileId)
        val name = profiles.value.find { it.id == profileId }?.name
        if (!name.isNullOrBlank()) {
            viewModelScope.launch {
                val msg = getApplication<Application>().getString(R.string.node_switch_success, name)
                _actionStatus.value = msg
                delay(1500)
                if (_actionStatus.value == msg) {
                    _actionStatus.value = null
                }
            }
        }

        // 否则VPN仍然使用旧配置，导致用户看到"选中"了新配置的节点但实际没联网
        if (SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value) {
            viewModelScope.launch {
                configRepository.setActiveProfileAndWait(profileId)
                val currentNodeId = configRepository.activeNodeId.value
                if (currentNodeId != null) {
                    Log.i(TAG, "Profile switched while VPN running, triggering node switch for: $currentNodeId")
                    configRepository.setActiveNodeWithResult(currentNodeId)
                }
            }
        }
    }

    fun setActiveNode(nodeId: String) {
        // 2025-fix: 先同步更新 activeNodeId，避免竞态条件
        configRepository.setActiveNodeIdOnly(nodeId)

        viewModelScope.launch {
            val node = nodes.value.find { it.id == nodeId }
            val result = configRepository.setActiveNodeWithResult(nodeId)

            if (SingBoxRemote.isRunning.value && node != null) {
                val msg = when (result) {
                    is ConfigRepository.NodeSwitchResult.Success,
                    is ConfigRepository.NodeSwitchResult.NotRunning -> getApplication<Application>().getString(R.string.node_switch_success, node.name)

                    is ConfigRepository.NodeSwitchResult.Failed ->
                        getApplication<Application>().getString(R.string.node_switch_failed, node.name)
                }
                _actionStatus.value = msg
                delay(1500)
                if (_actionStatus.value == msg) {
                    _actionStatus.value = null
                }
            }
        }
    }

    val stats: StateFlow<ConnectionStats> = combine(_statsBase, durationMsFlow) { base, duration ->
        base.copy(duration = duration)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ConnectionStats(0, 0, 0, 0, 0)
    )

    // Ping 测试状态：true = 正在测试中
    private val _isPingTesting = MutableStateFlow(false)
    val isPingTesting: StateFlow<Boolean> = _isPingTesting.asStateFlow()

    private var pingTestJob: Job? = null
    private var lastErrorToastJob: Job? = null
    private var startMonitorJob: Job? = null

    // Active profile and node from ConfigRepository
    val activeProfileId: StateFlow<String?> = configRepository.activeProfileId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val activeNodeId: StateFlow<String?> = configRepository.activeNodeId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val activeNodeLatency = kotlinx.coroutines.flow.combine(configRepository.nodes, activeNodeId) { nodes, id ->
        nodes.find { it.id == id }?.latencyMs
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    val profiles: StateFlow<List<ProfileUi>> = configRepository.profiles
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val nodes: StateFlow<List<NodeUi>> = combine(
        configRepository.nodes,
        displaySettings.nodeFilter,
        displaySettings.sortType,
        displaySettings.customOrder,
        configRepository.activeNodeId
    ) { nodes, filter, sortType, customOrder, _ ->
        buildDashboardNodes(nodes, filter, sortType, customOrder)
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Status
    private val _updateStatus = MutableStateFlow<String?>(null)
    val updateStatus: StateFlow<String?> = _updateStatus.asStateFlow()

    private val _testStatus = MutableStateFlow<String?>(null)
    val testStatus: StateFlow<String?> = _testStatus.asStateFlow()

    private val _actionStatus = MutableStateFlow<String?>(null)
    val actionStatus: StateFlow<String?> = _actionStatus.asStateFlow()

    // VPN 权限请求结果
    private val _vpnPermissionNeeded = MutableStateFlow(false)
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
                val hasSystemVpn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cm?.allNetworks?.any { network ->
                        val caps = cm.getNetworkCapabilities(network) ?: return@any false
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    } == true
                } else {
                    false
                }

                if (hasSystemVpn) {
                    systemVpnDetectedOnBoot = true
                }

                val persisted = context.getSharedPreferences("vpn_state", Context.MODE_PRIVATE)
                    .getBoolean("vpn_active", false)

                if (!hasSystemVpn && persisted) {
                    VpnTileService.persistVpnState(context, false)
                }

                val trustedInitialState = resolveTrustedDashboardConnectionState(
                    serviceState = SingBoxRemote.state.value,
                    ipcBound = SingBoxRemote.isBound()
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
                if (!err.isNullOrBlank()) {
                    _testStatus.value = err
                    lastErrorToastJob?.cancel()
                    lastErrorToastJob = viewModelScope.launch {
                        delay(3000)
                        if (_testStatus.value == err) {
                            _testStatus.value = null
                        }
                    }
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
    private var startGraceUntilElapsedMs: Long? = null
    private var refreshStateJob: Job? = null

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
                when (resolveTrustedDashboardConnectionState(state, SingBoxRemote.isBound())) {
                    ConnectionState.Connected -> {
                        systemVpnDetectedOnBoot = false
                        setConnectionState(ConnectionState.Connected)
                    }
                    ConnectionState.Connecting -> {
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
            SingBoxRemote.activeLabel
                .filter { it.isNotBlank() }
                .distinctUntilChanged()
                .collect { nodeName ->
                    Log.d(TAG, "activeLabel changed from service: $nodeName")
                    configRepository.syncActiveNodeFromProxySelection(nodeName)
                }
        }

        Log.i(TAG, "startStateCollector: collectors launched")
    }

    /**
     */
    private fun setConnectionState(newState: ConnectionState) {
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
                    startGraceUntilElapsedMs = SystemClock.elapsedRealtime() + 800L
                } else {
                    startGraceUntilElapsedMs = null
                }
                if (_connectionState.value != newState) {
                    _connectionState.value = newState
                }
            }
        }
    }

    private fun performDisconnect() {
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
            val phase1State = resolveTrustedDashboardConnectionState(
                serviceState = SingBoxRemote.state.value,
                ipcBound = SingBoxRemote.isBound()
            )
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
                setConnectionState(resolveTrustedDashboardConnectionState(state, ipcBound = true))
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
                    startGraceUntilElapsedMs = SystemClock.elapsedRealtime() + 800L
                    _connectionState.value = ConnectionState.Connecting
                    startCore()
                }
                ConnectionState.Connecting -> {
                    // P0 Optimization: Optimistic UI
                    startGraceUntilElapsedMs = null
                    _connectionState.value = ConnectionState.Disconnecting
                    stopVpn()
                }
                ConnectionState.Connected, ConnectionState.Disconnecting -> {
                    // P0 Optimization: Optimistic UI
                    startGraceUntilElapsedMs = null
                    _connectionState.value = ConnectionState.Disconnecting
                    stopVpn()
                }
            }
        }
    }

    fun restartVpn() {
        viewModelScope.launch {
            val context = getApplication<Application>()

            val settings = SettingsRepository.getInstance(context).settings.first()
            if (settings.tunEnabled) {
                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) {
                    _vpnPermissionNeeded.value = true
                    return@launch
                }
            }

            val configResult = withContext(Dispatchers.IO) {
                val settingsRepository = SettingsRepository.getInstance(context)
                settingsRepository.checkAndMigrateRuleSets()
                configRepository.generateConfigFile()
            }

            if (configResult == null) {
                _testStatus.value = getApplication<Application>().getString(R.string.dashboard_config_generation_failed)
                delay(2000)
                _testStatus.value = null
                return@launch
            }

            val useTun = settings.tunEnabled
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

            val requiresFullRestart = perAppSettingsChanged || tunSettingsChanged

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
                        "Full restart required: perAppChanged=$perAppSettingsChanged, tunChanged=$tunSettingsChanged"
                    )
                }
            }

            performRestart(context, configResult.path, useTun, perAppSettingsChanged)
        }
    }

    private fun logRestartDebugInfo(settings: AppSettings) {
        Log.d(
            TAG,
            "restartVpn: useTun=${settings.tunEnabled}, isRunning=${SingBoxRemote.isRunning.value}"
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
        useTun: Boolean,
        perAppSettingsChanged: Boolean
    ) {
        if (perAppSettingsChanged && useTun && SingBoxRemote.isRunning.value) {
            Log.i(TAG, "Per-app settings changed, using full restart to rebuild TUN")
            val intent = Intent(context, SingBoxService::class.java).apply {
                action = SingBoxService.ACTION_FULL_RESTART
                putExtra(SingBoxService.EXTRA_CONFIG_PATH, configPath)
            }
            startServiceCompat(context, intent)
            return
        }

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
        viewModelScope.launch {
            val context = getApplication<Application>()

            val settings = runCatching {
                SettingsRepository.getInstance(context).settings.first()
            }.getOrNull()

            val desiredMode = if (settings?.tunEnabled == true) {
                VpnStateStore.CoreMode.VPN
            } else {
                VpnStateStore.CoreMode.PROXY
            }

            if (settings?.tunEnabled == true) {
                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) {
                    _vpnPermissionNeeded.value = true
                    return@launch
                }
            }

            _connectionState.value = ConnectionState.Connecting

            // Ensure only one core instance is running at a time to avoid local port conflicts.
            // Do not rely on VpnStateStore here (multi-process timing); just stop the opposite service.
            val needToStopOpposite = when (desiredMode) {
                VpnStateStore.CoreMode.VPN -> {
                    runCatching {
                        context.startService(Intent(context, ProxyOnlyService::class.java).apply {
                            action = ProxyOnlyService.ACTION_STOP
                        })
                    }
                    true
                }
                VpnStateStore.CoreMode.PROXY -> {
                    runCatching {
                        context.startService(Intent(context, SingBoxService::class.java).apply {
                            action = SingBoxService.ACTION_STOP
                        })
                    }
                    true
                }
                else -> false
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
                    configRepository.generateConfigFile()
                }
                if (configResult == null) {
                    _connectionState.value = ConnectionState.Error
                    _testStatus.value = getApplication<Application>().getString(R.string.dashboard_config_generation_failed)
                    delay(2000)
                    _testStatus.value = null
                    return@launch
                }

                val useTun = desiredMode == VpnStateStore.CoreMode.VPN
                val intent = if (useTun) {
                    Intent(context, SingBoxService::class.java).apply {
                        action = SingBoxService.ACTION_START
                        putExtra(SingBoxService.EXTRA_CONFIG_PATH, configResult.path)
                        // 从停止状态启动时，强制清理缓存，确保使用配置文件中选中的节点
                        // 修 bug: App 更新后 cache.db 保留了旧的选中节点，导致 UI 上选中的新节点无效
                        putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
                    }
                } else {
                    Intent(context, ProxyOnlyService::class.java).apply {
                        action = ProxyOnlyService.ACTION_START
                        putExtra(ProxyOnlyService.EXTRA_CONFIG_PATH, configResult.path)
                        // 同理，Proxy 模式也需要清理缓存
                        putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }

                // 2) 后续只在服务端明确失败（lastErrorFlow）或服务异常退出时才置 Error
                startMonitorJob?.cancel()
                startMonitorJob = viewModelScope.launch {
                    val startTime = System.currentTimeMillis()
                    val quickFeedbackMs = 1000L
                    var showedStartingHint = false

                    while (true) {
                        if (SingBoxRemote.isRunning.value) {
                            _connectionState.value = ConnectionState.Connected
                            startTrafficMonitor()
                            return@launch
                        }

                        val err = SingBoxRemote.lastError.value
                        if (!err.isNullOrBlank()) {
                            _connectionState.value = ConnectionState.Error
                            _testStatus.value = err
                            delay(3000)
                            _testStatus.value = null
                            return@launch
                        }

                        val elapsed = System.currentTimeMillis() - startTime
                        if (!showedStartingHint && elapsed >= quickFeedbackMs) {
                            showedStartingHint = true
                            _testStatus.value = getApplication<Application>().getString(R.string.connection_connecting)
                            lastErrorToastJob?.cancel()
                            lastErrorToastJob = viewModelScope.launch {
                                delay(1200)
                                if (_testStatus.value == getApplication<Application>().getString(R.string.connection_connecting)) {
                                    _testStatus.value = null
                                }
                            }
                        }

                        val intervalMs = when {
                            elapsed < 10_000L -> 200L
                            elapsed < 60_000L -> 1000L
                            else -> 5000L
                        }
                        delay(intervalMs)
                    }
                }
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error
                _testStatus.value = getApplication<Application>().getString(R.string.node_start_failed, e.message ?: "")
                delay(2000)
                _testStatus.value = null
            }
        }
    }

    private fun stopVpn() {
        val context = getApplication<Application>()
        startMonitorJob?.cancel()
        startMonitorJob = null
        stopTrafficMonitor()
        stopPingTest()
        // Immediately set to Idle for responsive UI
        _connectionState.value = ConnectionState.Idle
        _connectedAtElapsedMs.value = null
        _statsBase.value = ConnectionStats(0, 0, 0, 0, 0)
        VpnTileService.persistVpnPending(context, "stopping")
        VpnTileService.persistVpnState(context, false)

        when (VpnStateStore.getMode()) {
            VpnStateStore.CoreMode.PROXY -> {
                context.startService(Intent(context, ProxyOnlyService::class.java).apply {
                    action = ProxyOnlyService.ACTION_STOP
                })
            }
            VpnStateStore.CoreMode.VPN -> {
                context.startService(Intent(context, SingBoxService::class.java).apply {
                    action = SingBoxService.ACTION_STOP
                })
            }
            VpnStateStore.CoreMode.NONE -> {
                context.startService(Intent(context, ProxyOnlyService::class.java).apply {
                    action = ProxyOnlyService.ACTION_STOP
                })
                context.startService(Intent(context, SingBoxService::class.java).apply {
                    action = SingBoxService.ACTION_STOP
                })
            }
        }
        context.startService(Intent(context, VpnTileService::class.java).apply {
            action = VpnTileService.ACTION_REFRESH_TILE
        })
    }

    private fun startPingTest() {
        if (_connectionState.value != ConnectionState.Connected) return
        if (_isPingTesting.value) return

        val targetNodeId = activeNodeId.value
        if (targetNodeId.isNullOrBlank()) {
            Log.w(TAG, "No active node to test ping")
            return
        }

        stopPingTest()
        pingTestJob = viewModelScope.launch {
            _isPingTesting.value = true
            try {
                configRepository.testAllNodesLatency(targetNodeIds = listOf(targetNodeId))
            } catch (e: Exception) {
                Log.e(TAG, "Error during ping test", e)
            } finally {
                _isPingTesting.value = false
            }
        }
    }

    private fun stopPingTest() {
        pingTestJob?.cancel()
        pingTestJob = null
        _isPingTesting.value = false
    }

    fun retestCurrentNodePing() {
        startPingTest()
    }

    fun onVpnPermissionResult(granted: Boolean) {
        _vpnPermissionNeeded.value = false
        if (!granted) {
            startGraceUntilElapsedMs = null
            startMonitorJob?.cancel()
            startMonitorJob = null
            _connectionState.value = ConnectionState.Idle
            return
        }
        if (granted) {
            startCore()
        }
    }

    fun updateAllSubscriptions() {
        viewModelScope.launch {
            _updateStatus.value = getApplication<Application>().getString(R.string.common_loading)

            val result = configRepository.updateAllProfiles()

            // 根据结果显示不同的提示
            _updateStatus.value = result.toDisplayMessage(getApplication())
            delay(2500)
            _updateStatus.value = null
        }
    }

    fun testAllNodesLatency() {
        viewModelScope.launch {
            _testStatus.value = getApplication<Application>().getString(R.string.common_loading)
            val targetNodeIds = configRepository.nodes.value.map { it.id }
            if (targetNodeIds.isEmpty()) {
                _testStatus.value = null
                return@launch
            }
            configRepository.testAllNodesLatency(targetNodeIds = targetNodeIds)
            _testStatus.value = getApplication<Application>().getString(R.string.dashboard_test_complete)
            delay(2000)
            _testStatus.value = null
        }
    }

    private fun startTrafficMonitor() {
        trafficMonitor.start()
    }

    private fun stopTrafficMonitor() {
        trafficMonitor.stop()
    }

    fun getActiveProfileName(): String? =
        activeProfileId.value?.let { activeId -> profiles.value.find { it.id == activeId }?.name }

    fun getActiveNodeName(): String? =
        activeNodeId.value?.let { activeId -> configRepository.getNodeById(activeId)?.displayName }

    override fun onCleared() {
        super.onCleared()
        startMonitorJob?.cancel()
        startMonitorJob = null
        stopTrafficMonitor()
        stopPingTest()
    }
}
