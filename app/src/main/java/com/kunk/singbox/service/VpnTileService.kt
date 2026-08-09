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
import com.kunk.singbox.ipc.StateGenerationGate
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.ipc.SingBoxIpcService
import com.kunk.singbox.ipc.toRuntimeStateSnapshot
import com.kunk.singbox.manager.VpnServiceManager
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.ui.components.AppNotificationManager
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.service.manager.ServiceStateHolder
import com.kunk.singbox.service.manager.VpnStopInitiator
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
    @Volatile private var lastServiceLabel: String = ""
    private var serviceBound = false
    private var bindRequested = false
    private var tapPending = false
    private var tileReceiverRegistered = false

    @Volatile private var isStartingSequence = false
    @Volatile private var startSequenceId: Long = 0L

    @Volatile private var remoteService: ISingBoxService? = null
    private val stateGenerationGate = StateGenerationGate()

    private val tileRefreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_REFRESH_TILE) {
                updateTile()
            }
        }
    }

    private val remoteCallback = object : ISingBoxServiceCallback.Stub() {
        override fun onStateChanged(
            state: Int,
            activeLabel: String?,
            lastError: String?,
            manuallyStopped: Boolean,
            generation: Long
        ) {
            serviceScope.launch(Dispatchers.Main) {
                val snapshot = VpnStateStore.RuntimeStateSnapshot(
                    generation = generation,
                    stateOrdinal = state,
                    activeLabel = activeLabel.orEmpty(),
                    lastError = lastError.orEmpty(),
                    manuallyStopped = manuallyStopped
                )
                if (!applyRemoteStateSnapshot(snapshot)) {
                    Log.w(TAG, "Ignored stale tile snapshot generation=$generation")
                    return@launch
                }
                updateTile()
            }
        }

        override fun onUrlTestNodeDelayResult(requestId: Long, delay: Int) = Unit
    }

    private fun applyRemoteStateSnapshot(snapshot: VpnStateStore.RuntimeStateSnapshot): Boolean {
        val mappedState = ServiceState.values().getOrNull(snapshot.stateOrdinal)
            ?: ServiceState.STOPPED
        return stateGenerationGate.tryCommit(snapshot.generation) {
            lastServiceState = mappedState
            lastServiceLabel = snapshot.activeLabel
            if (shouldCompleteStartingSequence(mappedState)) {
                isStartingSequence = false
                startSequenceId = 0L
            }
        }
    }

    companion object {
        private const val TAG = "VpnTileService"
        const val ACTION_REFRESH_TILE = "com.kunk.singbox.REFRESH_TILE"
        private const val STOP_NOTIFICATION_CLEANUP_DELAY_MS = 250L
        private const val REQUEST_CODE_VPN_PERMISSION = 3001
        fun persistVpnState(isActive: Boolean) {
            VpnStateStore.setActive(isActive)
        }

        fun persistVpnPending(pending: String?) {
            val value = pending.orEmpty()
            VpnStateStore.setPending(value)
        }

        internal fun shouldCompleteStartingSequence(state: ServiceState): Boolean {
            return state == ServiceState.RUNNING ||
                state == ServiceState.STOPPING ||
                state == ServiceState.STOPPED
        }

        internal fun shouldClearStartingSequenceOnListen(
            isStartingSequence: Boolean,
            pending: String
        ): Boolean {
            return isStartingSequence && pending != "starting"
        }

        internal fun shouldClearUnavailablePending(
            pending: String,
            isStartingSequence: Boolean,
            serviceActuallyRunning: Boolean,
            hasVpnTransport: Boolean
        ): Boolean {
            if (serviceActuallyRunning || hasVpnTransport) return false

            return when (pending) {
                "starting" -> !isStartingSequence
                "stopping" -> true
                else -> false
            }
        }

        internal fun shouldClearUnavailablePersistedActive(
            pending: String,
            persistedActive: Boolean,
            serviceActuallyRunning: Boolean,
            hasVpnTransport: Boolean
        ): Boolean {
            if (serviceActuallyRunning || hasVpnTransport) return false
            if (pending == "starting") return false
            return persistedActive
        }

        internal fun shouldRequestVpnPermissionBeforeStart(
            isActive: Boolean,
            tunEnabled: Boolean,
            vpnPrepareRequired: Boolean
        ): Boolean {
            return !isActive && tunEnabled && vpnPrepareRequired
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        clearStaleStartingSequenceOnListen()
        updateTile()
        registerTileRefreshReceiver()
        bindService()
    }

    private fun clearStaleStartingSequenceOnListen() {
        if (!shouldClearStartingSequenceOnListen(isStartingSequence, VpnStateStore.getPending())) return
        isStartingSequence = false
        startSequenceId = 0L
    }

    override fun onStopListening() {
        super.onStopListening()
        unregisterTileRefreshReceiver()
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

        val isActive = tile.state == Tile.STATE_ACTIVE

        if (isActive) {

            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = getString(R.string.connection_disconnecting)
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
        var persistedActive = VpnStateStore.getActive()

        val coreMode = VpnStateStore.getMode()
        var pending = VpnStateStore.getPending()
        val hasVpnTransport = hasSystemVpnTransport()
        val serviceActuallyRunning = isCoreServiceAvailable()

        if (coreMode == VpnStateStore.CoreMode.VPN && shouldClearUnavailablePersistedActive(
                pending = pending,
                persistedActive = persistedActive,
                serviceActuallyRunning = serviceActuallyRunning,
                hasVpnTransport = hasVpnTransport
            )
        ) {
            persistVpnState(false)
            persistVpnPending("")
            pending = ""
            persistedActive = false
        }

        if (shouldClearUnavailablePending(
                pending = pending,
                isStartingSequence = isStartingSequence,
                serviceActuallyRunning = serviceActuallyRunning,
                hasVpnTransport = hasVpnTransport
            )
        ) {
            persistVpnPending("")
            persistVpnState(false)
            pending = ""
            persistedActive = false
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
                ?: lastServiceLabel.takeIf { it.isNotBlank() }
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

    @Suppress("DEPRECATION")
    private fun hasSystemVpnTransport(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@any false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private fun isCoreServiceAvailable(): Boolean {
        return (serviceBound && remoteService != null) ||
            ProxyOnlyService.isRunning ||
            ProxyOnlyService.isStarting ||
            ServiceStateHolder.instance != null ||
            ServiceStateHolder.isRunning ||
            ServiceStateHolder.isStarting
    }

    private fun executeStopVpn() {
        isStartingSequence = false
        startSequenceId = 0L

        persistVpnPending("stopping")
        val stopRequestedAt = SystemClock.elapsedRealtime()

        serviceScope.launch(Dispatchers.IO) {
            try {
                VpnServiceManager.stopVpn(
                    this@VpnTileService,
                    VpnStopInitiator.QUICK_SETTINGS
                ).getOrThrow()

                withContext(Dispatchers.Main) {
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

    @Suppress("CognitiveComplexMethod", "LongMethod")
    private fun executeStartVpn() {

        startSequenceId = SystemClock.elapsedRealtimeNanos()
        isStartingSequence = true
        persistVpnPending("starting")

        serviceScope.launch(Dispatchers.IO) {
            try {
                val settings = SettingsRepository.getInstance(applicationContext).settings.first()

                if (settings.tunEnabled) {
                    val prepareIntent = VpnService.prepare(this@VpnTileService)
                    if (shouldRequestVpnPermissionBeforeStart(
                            isActive = false,
                            tunEnabled = true,
                            vpnPrepareRequired = prepareIntent != null
                        )
                    ) {

                        withContext(Dispatchers.Main) {
                            revertToInactive()
                            prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching {
                                startActivityAndCollapseCompat(prepareIntent, REQUEST_CODE_VPN_PERMISSION)
                            }
                        }
                        return@launch
                    }
                }

                val configRepository = ConfigRepository.getInstance(applicationContext)
                val configResult = configRepository.generateConfigFile(
                    selectedProfileId = VpnStateStore.getSelectedProfileId(),
                    selectedNodeId = VpnStateStore.getSelectedNodeId()
                )

                if (configResult != null) {
                    val command = VpnServiceManager.buildStartCommand(
                        tunMode = settings.tunEnabled,
                        configPath = configResult.path,
                        cleanCache = true
                    )
                    val intent = Intent(this@VpnTileService, command.serviceClass).apply {
                        action = command.action
                        command.configPath?.let { putExtra(SingBoxService.EXTRA_CONFIG_PATH, it) }
                        configResult.activeNodeName?.let {
                            putExtra(SingBoxService.EXTRA_PENDING_NODE_NAME, it)
                        }
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
            }
        }
    }

    private suspend fun handleStartFailure(reason: String) {
        startSequenceId = 0L
        isStartingSequence = false

        persistVpnPending("")
        persistVpnState(false)
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

        val persistedActive = VpnStateStore.getActive()
        val pending = VpnStateStore.getPending()

        val shouldTryBind = force || persistedActive || pending == "starting" || pending == "stopping" ||
            isCoreServiceAvailable()
        if (!shouldTryBind) return

        val intent = Intent(this, SingBoxIpcService::class.java)

        val ok = runCatching {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        bindRequested = ok

        scheduleBindTimeout()

        if (!ok && shouldClearUnavailableAfterBindFailure(
                pending = pending,
                persistedActive = persistedActive
            )
        ) {
            tapPending = false
            persistVpnState(false)
            persistVpnPending("")
            lastServiceState = ServiceState.STOPPED
            updateTile()
        }
    }

    private fun scheduleBindTimeout() {
        bindTimeoutJob?.cancel()
        bindTimeoutJob = serviceScope.launch {
            delay(1200)
            if (serviceBound || remoteService != null) return@launch
            if (!bindRequested) return@launch

            val active = VpnStateStore.getActive()
            val pending = VpnStateStore.getPending()

            if (shouldClearUnavailableAfterBindFailure(pending, active)) {
                unbindService()
                tapPending = false
                persistVpnState(false)
                persistVpnPending("")
                lastServiceState = ServiceState.STOPPED
                updateTile()
            }
        }
    }

    private fun shouldClearUnavailableAfterBindFailure(
        pending: String,
        persistedActive: Boolean
    ): Boolean {
        val hasVpnTransport = hasSystemVpnTransport()
        return shouldClearUnavailablePending(
            pending = pending,
            isStartingSequence = isStartingSequence,
            serviceActuallyRunning = false,
            hasVpnTransport = hasVpnTransport
        ) || shouldClearUnavailablePersistedActive(
            pending = pending,
            persistedActive = persistedActive,
            serviceActuallyRunning = false,
            hasVpnTransport = hasVpnTransport
        )
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = ISingBoxService.Stub.asInterface(service)
            remoteService = binder
            runCatching { binder.registerCallback(remoteCallback) }
            serviceBound = true
            bindRequested = true
            val snapshot = runCatching { binder.stateSnapshot.toRuntimeStateSnapshot() }
                .onFailure { error -> Log.w(TAG, "Failed to read initial tile state snapshot", error) }
                .getOrNull()
            if (snapshot != null && !applyRemoteStateSnapshot(snapshot)) {
                Log.w(TAG, "Ignored stale initial tile snapshot generation=${snapshot.generation}")
            }
            updateTile()
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

    private fun registerTileRefreshReceiver() {
        if (tileReceiverRegistered) return
        val filter = IntentFilter(ACTION_REFRESH_TILE)
        runCatching {
            ContextCompat.registerReceiver(this, tileRefreshReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            tileReceiverRegistered = true
        }.onFailure { error ->
            Log.w(TAG, "Failed to register tile refresh receiver", error)
        }
    }

    private fun unregisterTileRefreshReceiver() {
        if (!tileReceiverRegistered) return
        runCatching {
            unregisterReceiver(tileRefreshReceiver)
        }.onFailure { error ->
            Log.w(TAG, "Failed to unregister tile refresh receiver", error)
        }
        tileReceiverRegistered = false
    }

    override fun onDestroy() {
        unregisterTileRefreshReceiver()
        unbindService()
        serviceScope.cancel()
        super.onDestroy()
    }
}
