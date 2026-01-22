package com.kunk.singbox.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.kunk.singbox.aidl.ISingBoxService
import com.kunk.singbox.aidl.ISingBoxServiceCallback
import com.kunk.singbox.R
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.ipc.SingBoxIpcService
import com.kunk.singbox.manager.VpnServiceManager
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VpnTileService : TileService() {
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var bindTimeoutJob: Job? = null
    @Volatile private var lastServiceState: SingBoxService.ServiceState = SingBoxService.ServiceState.STOPPED
    private var serviceBound = false
    private var bindRequested = false
    private var tapPending = false
    // 内存标记，用于在重启服务的过程中保持 UI 状态，防止被中间的 STOPPED 状态闪烁
    @Volatile private var isStartingSequence = false

    @Volatile private var remoteService: ISingBoxService? = null

    private val tileRefreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_REFRESH_TILE) {
                updateTile()
            }
        }
    }

    private val remoteCallback = object : ISingBoxServiceCallback.Stub() {
        override fun onStateChanged(state: Int, activeLabel: String?, lastError: String?, manuallyStopped: Boolean) {
            lastServiceState = SingBoxService.ServiceState.values().getOrNull(state)
                ?: SingBoxService.ServiceState.STOPPED
            updateTile(activeLabelOverride = activeLabel)
        }
    }

    companion object {
        private const val PREFS_NAME = "vpn_state"
        private const val KEY_VPN_ACTIVE = "vpn_active"
        private const val KEY_VPN_PENDING = "vpn_pending"
        const val ACTION_REFRESH_TILE = "com.kunk.singbox.REFRESH_TILE"
        /**
         * 持久化 VPN 状态到 SharedPreferences
         * 在 SingBoxService 启动/停止时调用
         */
        fun persistVpnState(context: Context, isActive: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_VPN_ACTIVE, isActive)
                .commit()
        }

        fun persistVpnPending(context: Context, pending: String?) {
            val value = pending.orEmpty()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_VPN_PENDING, value)
                .commit()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
        val filter = IntentFilter(ACTION_REFRESH_TILE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tileRefreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(tileRefreshReceiver, filter)
        }
        bindService()
    }

    override fun onStopListening() {
        super.onStopListening()
        runCatching { unregisterReceiver(tileRefreshReceiver) }
        unbindService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_TILE) {
            updateTile()
        }
        return START_NOT_STICKY
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { handleClick() }
            return
        }
        handleClick()
    }

    private fun handleClick() {
        val tile = qsTile ?: return

        // 1. 检查 VPN 权限，如果需要授权则无法抢跑，必须跳转 Activity
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            startActivityAndCollapse(prepareIntent)
            return
        }

        // 2. UI 抢跑：立即根据当前状态更新 UI
        // 如果当前是 Active 或 Active (Starting)，则认为是想关闭
        // 如果是 Inactive，则认为是想开启
        val isActive = tile.state == Tile.STATE_ACTIVE

        if (isActive) {
            // 用户想关闭
            // 立即更新 UI 为关闭状态
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.app_name)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = null
                }
            } catch (e: Exception) {
                Log.w("VpnTileService", "Failed to set tile subtitle", e)
            }
            tile.updateTile()

            // 异步执行停止逻辑
            executeStopVpn()
        } else {
            // 用户想开启
            // 立即更新 UI 为开启状态
            tile.state = Tile.STATE_ACTIVE
            tile.label = getString(R.string.connection_connecting)
            tile.updateTile()

            // 异步执行开启逻辑
            executeStartVpn()
        }
    }

    private fun updateTile(activeLabelOverride: String? = null) {
        var persistedActive = runCatching {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_VPN_ACTIVE, false)
        }.getOrDefault(false)

        val coreMode = VpnStateStore.getMode()

        if (persistedActive && coreMode == VpnStateStore.CoreMode.VPN && !hasSystemVpnTransport()) {
            persistVpnState(this, false)
            persistVpnPending(this, "")
            persistedActive = false
        }

        val pending = runCatching {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_VPN_PENDING, "")
        }.getOrNull().orEmpty()

        // 优先级：内存中的启动标记 > Pending 状态 > Service 状态 > 持久化状态
        if (isStartingSequence) {
            lastServiceState = SingBoxService.ServiceState.STARTING
        } else if (!serviceBound || remoteService == null || pending.isNotEmpty()) {
            lastServiceState = when (pending) {
                "starting" -> SingBoxService.ServiceState.STARTING
                "stopping" -> SingBoxService.ServiceState.STOPPING
                else -> if (persistedActive) SingBoxService.ServiceState.RUNNING else SingBoxService.ServiceState.STOPPED
            }
        }

        val tile = qsTile ?: return

        // 如果正在启动序列中，强制显示为 Active，覆盖中间的 STOPPED 状态
        if (isStartingSequence) {
            tile.state = Tile.STATE_ACTIVE
        } else {
            when (lastServiceState) {
                SingBoxService.ServiceState.STARTING,
                SingBoxService.ServiceState.RUNNING -> {
                    tile.state = Tile.STATE_ACTIVE
                }
                SingBoxService.ServiceState.STOPPING -> {
                    tile.state = Tile.STATE_UNAVAILABLE
                }
                SingBoxService.ServiceState.STOPPED -> {
                    tile.state = Tile.STATE_INACTIVE
                }
            }
        }
        val activeLabel = if (lastServiceState == SingBoxService.ServiceState.RUNNING ||
            lastServiceState == SingBoxService.ServiceState.STARTING
        ) {
            activeLabelOverride?.takeIf { it.isNotBlank() }
                ?: runCatching { remoteService?.activeLabel }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: runCatching {
                    val repo = ConfigRepository.getInstance(applicationContext)
                    val nodeId = repo.activeNodeId.value
                    if (!nodeId.isNullOrBlank()) repo.getNodeById(nodeId)?.name else null
                }.getOrNull()
        } else {
            null
        }

        tile.label = activeLabel ?: getString(R.string.app_name)
        try {
            tile.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_qs_tile)
        } catch (_: Exception) {
        }
        tile.updateTile()
    }

    private fun hasSystemVpnTransport(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@any false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    /**
     * 执行停止 VPN 逻辑 (使用 VpnServiceManager 统一管理)
     */
    private fun executeStopVpn() {
        // 在主线程立即标记状态,防止竞态条件导致 UI 闪烁
        persistVpnPending(this, "stopping")
        persistVpnState(this, false)

        serviceScope.launch(Dispatchers.IO) {
            try {
                VpnServiceManager.stopVpn(this@VpnTileService)
            } catch (e: Exception) {
                e.printStackTrace()
                // 如果停止失败,恢复 UI 状态 (虽然概率很低)
                handleStartFailure("Stop service failed: ${e.message}")
            }
        }
    }

    /**
     * 执行启动 VPN 逻辑 (使用 VpnServiceManager 统一管理)
     */
    private fun executeStartVpn() {
        // 在主线程立即标记状态,防止竞态条件导致 UI 闪烁
        isStartingSequence = true
        persistVpnPending(this, "starting")

        serviceScope.launch(Dispatchers.IO) {
            try {
                val settings = SettingsRepository.getInstance(applicationContext).settings.first()

                // 双重检查 VPN 权限 (防止在点击间隙权限被吊销)
                if (settings.tunEnabled) {
                    val prepareIntent = VpnService.prepare(this@VpnTileService)
                    if (prepareIntent != null) {
                        // 需要授权,回滚 UI 并跳转
                        withContext(Dispatchers.Main) {
                            revertToInactive()
                            prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { startActivityAndCollapse(prepareIntent) }
                        }
                        return@launch
                    }
                }

                // 生成配置文件 (耗时操作)
                val configRepository = ConfigRepository.getInstance(applicationContext)
                val configResult = configRepository.generateConfigFile()

                if (configResult != null) {
                    // 使用 VpnServiceManager 统一启动逻辑
                    // 内部会根据 tunEnabled 选择 SingBoxService 或 ProxyOnlyService
                    VpnServiceManager.startVpn(this@VpnTileService, settings.tunEnabled)

                    // 启动成功后, Service 的回调会触发 updateTile,
                    // 此时 pending 仍为 "starting", updateTile 会保持 Active 状态
                } else {
                    handleStartFailure(getString(R.string.dashboard_config_generation_failed))
                }
            } catch (e: Exception) {
                handleStartFailure("Start failed: ${e.message}")
            } finally {
                // 无论成功失败,结束启动序列标记
                // 延迟一小会儿清除标记,确保 Service 状态已经稳定
                if (isStartingSequence) {
                    delay(2000)
                    isStartingSequence = false
                    // 最后刷新一次以同步真实状态
                    withContext(Dispatchers.Main) {
                        updateTile()
                    }
                }
            }
        }
    }

    private suspend fun handleStartFailure(reason: String) {
        isStartingSequence = false // 立即取消标记
        // 清除状态
        persistVpnPending(this@VpnTileService, "")
        persistVpnState(this@VpnTileService, false)
        lastServiceState = SingBoxService.ServiceState.STOPPED

        withContext(Dispatchers.Main) {
            revertToInactive()
            Toast.makeText(this@VpnTileService, reason, Toast.LENGTH_LONG).show()
        }
    }

    private fun revertToInactive() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.updateTile()
    }

    // 保留 toggle 方法以防其他地方调用（虽然这是 private）
    private fun toggle() {
        // Redirect to new logic
        handleClick()
    }

    private fun bindService(force: Boolean = false) {
        if (serviceBound || bindRequested) return

        val persistedActive = runCatching {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_VPN_ACTIVE, false)
        }.getOrDefault(false)

        val pending = runCatching {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_VPN_PENDING, "")
        }.getOrNull().orEmpty()

        val shouldTryBind = force || persistedActive || pending == "starting" || pending == "stopping"
        if (!shouldTryBind) return

        val intent = Intent(this, SingBoxIpcService::class.java)

        val ok = runCatching {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        bindRequested = ok

        bindTimeoutJob?.cancel()
        bindTimeoutJob = serviceScope.launch {
            delay(1200)
            if (serviceBound || remoteService != null) return@launch
            if (!bindRequested) return@launch

            val active = runCatching {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_VPN_ACTIVE, false)
            }.getOrDefault(false)
            val p = runCatching {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_VPN_PENDING, "")
            }.getOrNull().orEmpty()

            if (p != "starting" && (active || p == "stopping")) {
                unbindService()
                tapPending = false
                persistVpnState(this@VpnTileService, false)
                persistVpnPending(this@VpnTileService, "")
                lastServiceState = SingBoxService.ServiceState.STOPPED
                updateTile()
            }
        }

        if (!ok && pending != "starting" && (persistedActive || pending == "stopping")) {
            tapPending = false
            persistVpnState(this, false)
            persistVpnPending(this, "")
            lastServiceState = SingBoxService.ServiceState.STOPPED
            updateTile()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = ISingBoxService.Stub.asInterface(service)
            remoteService = binder
            runCatching { binder.registerCallback(remoteCallback) }
            serviceBound = true
            bindRequested = true
            lastServiceState = SingBoxService.ServiceState.values().getOrNull(runCatching { binder.state }.getOrNull() ?: -1)
                ?: SingBoxService.ServiceState.STOPPED
            updateTile(activeLabelOverride = runCatching { binder.activeLabel }.getOrNull())
            if (tapPending) {
                tapPending = false
                toggle()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            runCatching { remoteService?.unregisterCallback(remoteCallback) }
            remoteService = null
            serviceBound = false
            bindRequested = false
            updateTile()
        }
    }

    private fun unbindService() {
        if (!bindRequested) return
        bindTimeoutJob?.cancel()
        bindTimeoutJob = null
        runCatching { remoteService?.unregisterCallback(remoteCallback) }
        runCatching { unbindService(serviceConnection) }
        remoteService = null
        serviceBound = false
        bindRequested = false
    }

    override fun onDestroy() {
        unbindService()
        serviceScope.cancel()
        super.onDestroy()
    }
}
