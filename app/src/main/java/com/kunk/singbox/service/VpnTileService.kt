package com.kunk.singbox.service

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.os.SystemClock
import android.util.Log
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.kunk.singbox.aidl.ISingBoxService
import com.kunk.singbox.aidl.ISingBoxServiceCallback
import com.kunk.singbox.R
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.ipc.SingBoxIpcService
import com.kunk.singbox.manager.VpnServiceManager
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.ui.components.AppNotificationManager
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.service.notification.VpnNotificationManager
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
    @Volatile private var lastServiceState: ServiceState = ServiceState.STOPPED
    private var serviceBound = false
    private var bindRequested = false
    private var tapPending = false

    @Volatile private var isStartingSequence = false
    @Volatile private var startSequenceId: Long = 0L

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
            serviceScope.launch(Dispatchers.Main) {
                val mappedState = ServiceState.values().getOrNull(state)
                    ?: ServiceState.STOPPED
                lastServiceState = mappedState
                if (mappedState == ServiceState.STOPPING || mappedState == ServiceState.STOPPED) {
                    isStartingSequence = false
                    startSequenceId = 0L
                }
                updateTile(activeLabelOverride = activeLabel)
            }
        }

        override fun onUrlTestNodeDelayResult(requestId: Long, delay: Int) = Unit
    }

    companion object {
        private const val TAG = "VpnTileService"
        private const val PREFS_NAME = "vpn_state"
        private const val KEY_VPN_ACTIVE = "vpn_active"
        private const val KEY_VPN_PENDING = "vpn_pending"
        const val ACTION_REFRESH_TILE = "com.kunk.singbox.REFRESH_TILE"
        private const val STOP_NOTIFICATION_CLEANUP_DELAY_MS = 250L
        private const val REQUEST_CODE_VPN_PERMISSION = 3001
        private const val REQUEST_CODE_VPN_PERMISSION_RETRY = 3002
        /**
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
            VpnStateStore.setPending(value)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
        val filter = IntentFilter(ACTION_REFRESH_TILE)
        ContextCompat.registerReceiver(this, tileRefreshReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
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

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            startActivityAndCollapseCompat(prepareIntent, REQUEST_CODE_VPN_PERMISSION)
            return
        }

        val isActive = tile.state == Tile.STATE_ACTIVE

        if (isActive) {

            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.app_name)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set tile subtitle", e)
            }
            tile.updateTile()

            executeStopVpn()
        } else {

            tile.state = Tile.STATE_ACTIVE
            tile.label = getString(R.string.connection_connecting)
            tile.updateTile()

            executeStartVpn()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun startActivityAndCollapseCompat(intent: Intent, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
            return
        }

        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
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

        var pending = runCatching {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_VPN_PENDING, "")
        }.getOrNull().orEmpty()

        if ((pending == "stopping" || pending == "starting") && !isStartingSequence) {
            val serviceActuallyRunning = serviceBound && remoteService != null
            val hasVpnTransport = hasSystemVpnTransport()

            if (!serviceActuallyRunning && !hasVpnTransport) {
                persistVpnPending(this, "")
                persistVpnState(this, false)
                pending = ""
                persistedActive = false
            }
        }

        val effectiveState = if (isStartingSequence) {
            ServiceState.STARTING
        } else if (!serviceBound || remoteService == null || pending.isNotEmpty()) {
            when (pending) {
                "starting" -> ServiceState.STARTING
                "stopping" -> ServiceState.STOPPING
                else -> if (persistedActive) ServiceState.RUNNING else ServiceState.STOPPED
            }
        } else {
            lastServiceState
        }

        val tile = qsTile ?: return

        if (isStartingSequence) {
            tile.state = Tile.STATE_ACTIVE
        } else {
            when (effectiveState) {
                ServiceState.STARTING,
                ServiceState.RUNNING -> {
                    tile.state = Tile.STATE_ACTIVE
                }
                ServiceState.STOPPING -> {
                    tile.state = Tile.STATE_UNAVAILABLE
                }
                ServiceState.STOPPED -> {
                    tile.state = Tile.STATE_INACTIVE
                }
            }
        }
        val activeLabel = if (effectiveState == ServiceState.RUNNING ||
            effectiveState == ServiceState.STARTING
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
     */
    private fun executeStopVpn() {
        isStartingSequence = false
        startSequenceId = 0L

        persistVpnPending(this, "stopping")
        persistVpnState(this, false)
        val stopRequestedAt = SystemClock.elapsedRealtime()

        serviceScope.launch(Dispatchers.IO) {
            try {
                VpnServiceManager.stopVpn(this@VpnTileService).getOrThrow()

                withContext(Dispatchers.Main) {

                    persistVpnPending(this@VpnTileService, "")
                    updateTile()
                }

                delay(STOP_NOTIFICATION_CLEANUP_DELAY_MS)
                withContext(Dispatchers.Main) {
                    runCatching {
                        val nm = getSystemService(NotificationManager::class.java)

                        nm?.cancel(VpnNotificationManager.NOTIFICATION_ID)

                        nm?.cancel(11)
                    }
                    Log.d(TAG, "executeStopVpn ui settle in ${SystemClock.elapsedRealtime() - stopRequestedAt}ms")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Stop service failed", e)

                handleStartFailure("Stop service failed: ${e.message}")
            }
        }
    }

    /**
     */
    @Suppress("CognitiveComplexMethod", "LongMethod")
    private fun executeStartVpn() {

        val currentSequenceId = SystemClock.elapsedRealtimeNanos()
        startSequenceId = currentSequenceId
        isStartingSequence = true
        persistVpnPending(this, "starting")

        serviceScope.launch(Dispatchers.IO) {
            try {
                val settings = SettingsRepository.getInstance(applicationContext).settings.first()

                if (settings.tunEnabled) {
                    val prepareIntent = VpnService.prepare(this@VpnTileService)
                    if (prepareIntent != null) {

                        withContext(Dispatchers.Main) {
                            revertToInactive()
                            prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching {
                                startActivityAndCollapseCompat(prepareIntent, REQUEST_CODE_VPN_PERMISSION_RETRY)
                            }
                        }
                        return@launch
                    }
                }

                val configRepository = ConfigRepository.getInstance(applicationContext)
                val configResult = configRepository.generateConfigFile()

                if (configResult != null) {
                    val command = VpnServiceManager.buildStartCommand(
                        tunMode = settings.tunEnabled,
                        configPath = configResult.path,
                        cleanCache = true
                    )
                    val intent = Intent(this@VpnTileService, command.serviceClass).apply {
                        action = command.action
                        command.configPath?.let { putExtra(SingBoxService.EXTRA_CONFIG_PATH, it) }
                        if (command.cleanCache) {
                            putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                } else {
                    handleStartFailure(getString(R.string.dashboard_config_generation_failed))
                }
            } catch (e: Exception) {
                handleStartFailure("Start failed: ${e.message}")
            } finally {

                if (isStartingSequence && startSequenceId == currentSequenceId) {
                    delay(2000)
                    if (startSequenceId == currentSequenceId) {
                        isStartingSequence = false
                        startSequenceId = 0L

                        withContext(Dispatchers.Main) {
                            updateTile()
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleStartFailure(reason: String) {
        startSequenceId = 0L
        isStartingSequence = false

        persistVpnPending(this@VpnTileService, "")
        persistVpnState(this@VpnTileService, false)
        lastServiceState = ServiceState.STOPPED

        withContext(Dispatchers.Main) {
            revertToInactive()
            AppNotificationManager.showMessage(
                context = this@VpnTileService,
                message = reason,
                duration = androidx.compose.material3.SnackbarDuration.Long
            )
        }
    }

    private fun revertToInactive() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.updateTile()
    }

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
                lastServiceState = ServiceState.STOPPED
                updateTile()
            }
        }

        if (!ok && pending != "starting" && (persistedActive || pending == "stopping")) {
            tapPending = false
            persistVpnState(this, false)
            persistVpnPending(this, "")
            lastServiceState = ServiceState.STOPPED
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
            lastServiceState = ServiceState.values().getOrNull(runCatching { binder.state }.getOrNull() ?: -1)
                ?: ServiceState.STOPPED
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
