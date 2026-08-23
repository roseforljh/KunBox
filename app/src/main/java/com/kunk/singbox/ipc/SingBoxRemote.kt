package com.kunk.singbox.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.aidl.ISingBoxService
import com.kunk.singbox.aidl.ISingBoxServiceCallback
import com.kunk.singbox.service.ServiceState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

sealed class RecoveryResult {
    data object AlreadyConnected : RecoveryResult()

    data class Recovering(
        val startTime: Long,
        val expectedDuration: Long
    ) : RecoveryResult()

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : RecoveryResult()
}

internal enum class EnsureBoundAction {
    NONE,
    CONNECT,
    WAIT_FOR_BIND,
    REBIND
}

internal fun resolveSingBoxEnsureBoundAction(
    connectionActive: Boolean,
    bound: Boolean,
    servicePresent: Boolean,
    serviceAlive: Boolean,
    bindingInProgress: Boolean
): EnsureBoundAction {
    return when {
        connectionActive && bound && servicePresent && serviceAlive -> EnsureBoundAction.NONE
        connectionActive && bound && servicePresent && !serviceAlive -> EnsureBoundAction.REBIND
        !connectionActive -> EnsureBoundAction.CONNECT
        bindingInProgress -> EnsureBoundAction.WAIT_FOR_BIND
        !bound || !servicePresent -> EnsureBoundAction.REBIND
        else -> EnsureBoundAction.NONE
    }
}

internal class StateGenerationGate {
    private val lock = Any()
    private var acceptedGeneration = 0L

    fun tryCommit(incomingGeneration: Long, commit: () -> Unit): Boolean {
        return synchronized(lock) {
            if (!SingBoxRemote.shouldAcceptStateGeneration(incomingGeneration, acceptedGeneration)) {
                return@synchronized false
            }
            commit()
            if (incomingGeneration > 0L) {
                acceptedGeneration = maxOf(acceptedGeneration, incomingGeneration)
            }
            true
        }
    }

    fun reset() = synchronized(lock) {
        acceptedGeneration = 0L
    }
}

@Suppress("LargeClass", "TooManyFunctions")
object SingBoxRemote {
    private const val TAG = "SingBoxRemote"

    private const val RECONNECT_DELAY_MS = 100L
    private const val MAX_RECONNECT_ATTEMPTS = 10
    private const val RECONNECT_BACKOFF_MAX = 60000L

    private const val CALLBACK_TIMEOUT_MS = 8_000L
    private const val RECOVERY_EXPECTED_DURATION_MS = 5_000L
    private const val READINESS_WATCHDOG_INTERVAL_MS = 1_000L

    private val _state = MutableStateFlow(ServiceState.STOPPED)
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isStarting = MutableStateFlow(false)
    val isStarting: StateFlow<Boolean> = _isStarting.asStateFlow()

    private val _activeLabel = MutableStateFlow("")
    val activeLabel: StateFlow<String> = _activeLabel.asStateFlow()

    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError.asStateFlow()

    private val _manuallyStopped = MutableStateFlow(false)
    val manuallyStopped: StateFlow<Boolean> = _manuallyStopped.asStateFlow()

    private val _readiness = MutableStateFlow(DataPlaneReadinessSnapshot.stopped())
    val readiness: StateFlow<DataPlaneReadinessSnapshot> = _readiness.asStateFlow()

    @Volatile
    private var service: ISingBoxService? = null

    @Volatile
    private var connectionActive = false

    @Volatile
    private var bound = false

    @Volatile
    private var bindingInProgress = false

    @Volatile
    private var callbackRegistered = false

    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var contextRef: WeakReference<Context>? = null

    @Volatile
    private var reconnectAttempts = 0

    @Volatile
    private var lastSyncTimeMs = 0L

    @Volatile
    private var lastCallbackReceivedAtMs = 0L

    @Volatile
    private var pendingAppLifecycle: Boolean? = null

    @Volatile
    private var pendingLifecycleVersion: Long = 0L

    @Volatile
    private var sentLifecycleVersion: Long = 0L

    @Volatile
    private var pendingLifecycleRetry: Runnable? = null

    @Volatile
    private var pendingLifecycleRetryAttempts: Int = 0

    @Volatile
    private var pendingLifecycleRetryVersion: Long = -1L

    @Volatile
    private var pendingReconnect: Runnable? = null

    @Volatile
    private var readinessFreshnessWatchdog: Runnable? = null

    private val pendingRecoveryCallbacks = ConcurrentLinkedQueue<(RecoveryResult) -> Unit>()

    private val urlTestRequestId = AtomicLong(0L)
    private val pendingUrlTestRequests = ConcurrentHashMap<Long, CompletableDeferred<Int?>>()
    private val stateGenerationGate = StateGenerationGate()

    @Volatile
    private var acceptedServiceInstanceId: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())

    internal data class DisconnectedStopState(
        val preserveLastError: Boolean,
        val lastError: String,
        val manuallyStopped: Boolean
    )

    internal fun resolveDisconnectedStopState(
        storedLastError: String,
        storedManuallyStopped: Boolean
    ): DisconnectedStopState {
        return DisconnectedStopState(
            preserveLastError = storedManuallyStopped,
            lastError = if (storedManuallyStopped) storedLastError else "",
            manuallyStopped = storedManuallyStopped
        )
    }

    internal fun shouldReconnectAfterServiceLoss(
        systemVpn: Boolean,
        storedManuallyStopped: Boolean,
        storedMode: VpnStateStore.CoreMode
    ): Boolean {
        return !storedManuallyStopped && (systemVpn || storedMode == VpnStateStore.CoreMode.PROXY)
    }

    private val callback = object : ISingBoxServiceCallback.Stub() {
        override fun onStateChanged(
            @Suppress("UNUSED_PARAMETER") state: Int,
            @Suppress("UNUSED_PARAMETER") activeLabel: String?,
            @Suppress("UNUSED_PARAMETER") lastError: String?,
            @Suppress("UNUSED_PARAMETER") manuallyStopped: Boolean,
            @Suppress("UNUSED_PARAMETER") generation: Long
        ) {
            lastCallbackReceivedAtMs = SystemClock.elapsedRealtime()
            mainHandler.post {
                if (!syncStateFromService(service)) {
                    markReadinessUnavailable("callback_snapshot_failed")
                }
            }
        }

        override fun onUrlTestNodeDelayResult(requestId: Long, delay: Int) {
            pendingUrlTestRequests.remove(requestId)?.complete(delay.takeIf { it > 0 })
        }
    }

    private fun startPendingRecovery(
        callback: ((RecoveryResult) -> Unit)?,
        startTime: Long
    ): RecoveryResult.Recovering {
        callback?.let(pendingRecoveryCallbacks::add)
        return RecoveryResult.Recovering(
            startTime = startTime,
            expectedDuration = RECOVERY_EXPECTED_DURATION_MS
        )
    }

    private fun completePendingRecovery(result: RecoveryResult) {
        while (true) {
            val callback = pendingRecoveryCallbacks.poll() ?: return
            runCatching { callback.invoke(result) }
                .onFailure { Log.e(TAG, "Recovery callback failed", it) }
        }
    }

    private fun failPendingRecovery(reason: String, throwable: Throwable? = null) {
        completePendingRecovery(RecoveryResult.Failed(reason, throwable))
    }

    private fun clearPendingUrlTestRequests() {
        pendingUrlTestRequests.values.forEach { it.complete(null) }
        pendingUrlTestRequests.clear()
    }

    private fun updateState(
        st: ServiceState,
        activeLabel: String? = null,
        lastError: String? = null,
        manuallyStopped: Boolean? = null
    ) {
        _state.value = st
        _isRunning.value = st == ServiceState.RUNNING
        _isStarting.value = st == ServiceState.STARTING
        activeLabel?.let { _activeLabel.value = it }
        lastError?.let { _lastError.value = it }
        manuallyStopped?.let { _manuallyStopped.value = it }
        lastSyncTimeMs = System.currentTimeMillis()
    }

    private fun applyStateSnapshot(
        snapshot: VpnStateStore.RuntimeStateSnapshot,
        fromBoundService: Boolean = false
    ): Boolean {
        val incomingInstanceId = snapshot.readiness.serviceInstanceId
        if (fromBoundService && incomingInstanceId.isNotBlank() && incomingInstanceId != acceptedServiceInstanceId) {
            acceptedServiceInstanceId = incomingInstanceId
            stateGenerationGate.reset()
        } else if (acceptedServiceInstanceId.isNotBlank() && incomingInstanceId != acceptedServiceInstanceId) {
            return false
        }
        return stateGenerationGate.tryCommit(snapshot.generation) {
            val state = ServiceState.values().getOrNull(snapshot.stateOrdinal)
                ?: ServiceState.STOPPED
            updateState(
                state,
                snapshot.activeLabel,
                snapshot.lastError,
                snapshot.manuallyStopped
            )
            _readiness.value = snapshot.readiness
        }
    }

    private fun markReadinessUnavailable(reason: String) {
        _readiness.value = _readiness.value.copy(
            status = DataPlaneStatus.FAILED_UNPROTECTED,
            coreReady = false,
            selectorReady = false,
            recoveryActive = false,
            lastReadinessReason = reason,
            updatedAtElapsedMs = SystemClock.elapsedRealtime()
        )
    }

    private fun startReadinessFreshnessWatchdog() {
        stopReadinessFreshnessWatchdog()
        val task = object : Runnable {
            override fun run() {
                if (!bound || service == null) return
                val snapshot = _readiness.value
                if (snapshot.status != DataPlaneStatus.STOPPED &&
                    !snapshot.isFresh(SystemClock.elapsedRealtime())
                ) {
                    markReadinessUnavailable("readiness_heartbeat_stale")
                }
                mainHandler.postDelayed(this, READINESS_WATCHDOG_INTERVAL_MS)
            }
        }
        readinessFreshnessWatchdog = task
        mainHandler.postDelayed(task, READINESS_WATCHDOG_INTERVAL_MS)
    }

    private fun stopReadinessFreshnessWatchdog() {
        readinessFreshnessWatchdog?.let(mainHandler::removeCallbacks)
        readinessFreshnessWatchdog = null
    }

    internal fun shouldAcceptStateGeneration(incoming: Long, accepted: Long): Boolean {
        return if (incoming <= 0L) accepted <= 0L else accepted <= 0L || incoming >= accepted
    }

    fun clearLastErrorForNewStart() {
        _activeLabel.value = ""
        _lastError.value = ""
    }

    private fun syncStateFromStore() {
        // 有 context 就查真实 VPN transport，避免把运行中的 VPN 误判成 STOPPED
        val hasVpnTransport = contextRef?.get()?.let { hasSystemVpn(it) } ?: false
        val state = resolvePersistedState(hasVpnTransport = hasVpnTransport)
        val snapshot = resolveLocalStateSnapshot(VpnStateStore.getRuntimeStateSnapshot(), state)

        Log.i(TAG, "syncStateFromStore: state=$state, generation=${snapshot.generation}")
        applyStateSnapshot(snapshot)
    }

    private fun syncStoppedStateAfterDisconnect() {
        val stopState = resolveDisconnectedStopState(
            storedLastError = VpnStateStore.getLastError(),
            storedManuallyStopped = VpnStateStore.isManuallyStopped()
        )
        updateState(
            st = ServiceState.STOPPED,
            activeLabel = "",
            lastError = stopState.lastError,
            manuallyStopped = stopState.manuallyStopped
        )
        markReadinessUnavailable("service_disconnected_stopped")
    }

    internal fun resolveLocalStateSnapshot(
        persisted: VpnStateStore.RuntimeStateSnapshot,
        state: ServiceState,
        clearTransientState: Boolean = false
    ): VpnStateStore.RuntimeStateSnapshot {
        val resolved = persisted.copy(
            stateOrdinal = state.ordinal,
            activeLabel = if (clearTransientState) "" else persisted.activeLabel,
            lastError = if (clearTransientState) "" else persisted.lastError
        )
        return if (resolved == persisted) persisted else resolved.copy(generation = 0L)
    }

    private val deathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            Log.w(TAG, "Binder died, delegating to backoff reconnect")
            service = null
            callbackRegistered = false
            bound = false
            stopReadinessFreshnessWatchdog()
            markReadinessUnavailable("binder_died")

            mainHandler.post {
                val ctx = contextRef?.get()
                if (ctx != null && !SagerConnection_restartingApp) {
                    val systemVpn = hasSystemVpn(ctx)
                    val storedManuallyStopped = VpnStateStore.isManuallyStopped()
                    val storedMode = VpnStateStore.getMode()
                    if (!shouldReconnectAfterServiceLoss(systemVpn, storedManuallyStopped, storedMode)) {
                        syncStoppedStateAfterDisconnect()
                    } else {
                        // 统一走指数退避重连逻辑，避免极端情况下的重连风暴
                        scheduleReconnect()
                    }
                }
            }
        }
    }

    @Volatile
    private var SagerConnection_restartingApp = false

    private fun clearPendingLifecycleRetry() {
        pendingLifecycleRetry?.let { mainHandler.removeCallbacks(it) }
        pendingLifecycleRetry = null
    }

    private fun resetPendingLifecycleRetryState() {
        clearPendingLifecycleRetry()
        pendingLifecycleRetryAttempts = 0
        pendingLifecycleRetryVersion = -1L
    }

    private fun clearPendingReconnect() {
        pendingReconnect?.let { mainHandler.removeCallbacks(it) }
        pendingReconnect = null
    }

    private fun resolvePersistedState(hasVpnTransport: Boolean): ServiceState {
        return resolvePersistedStateFromValues(
            pending = VpnStateStore.getPending(),
            isActive = VpnStateStore.getActive(),
            mode = VpnStateStore.getMode(),
            hasVpnTransport = hasVpnTransport
        )
    }

    @JvmStatic
    internal fun resolvePersistedStateFromValues(
        pending: String,
        isActive: Boolean,
        mode: VpnStateStore.CoreMode,
        hasVpnTransport: Boolean
    ): ServiceState {
        return when {
            pending == "starting" -> ServiceState.STARTING
            pending == "stopping" -> ServiceState.STOPPING
            isActive && hasVpnTransport -> ServiceState.RUNNING
            mode == VpnStateStore.CoreMode.PROXY -> ServiceState.RUNNING
            else -> ServiceState.STOPPED
        }
    }

    private fun tryNotifyLifecycle(version: Long, pending: Boolean): Boolean {
        val s = service ?: return false
        if (!connectionActive || !bound) return false

        runCatching {
            s.notifyAppLifecycle(pending)
            sentLifecycleVersion = version
            pendingAppLifecycle = null
            resetPendingLifecycleRetryState()
            Log.w(TAG, "notifyAppLifecycle retried: isForeground=$pending")
        }.onFailure {
            Log.w(TAG, "notifyAppLifecycle retry failed", it)
            schedulePendingLifecycleRetry(version)
        }
        return true
    }

    private fun ensureBindIfNeeded() {
        val ctx = contextRef?.get() ?: return
        val needsBind = !connectionActive || !bound || service == null
        if (needsBind) {
            ensureBound(ctx)
        }
    }

    private fun schedulePendingLifecycleRetry(version: Long) {
        clearPendingLifecycleRetry()
        if (pendingLifecycleRetryVersion != version) {
            pendingLifecycleRetryVersion = version
            pendingLifecycleRetryAttempts = 0
        }
        if (pendingLifecycleRetryAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "notifyAppLifecycle: retry limit reached for version=$version")
            return
        }
        val attempt = pendingLifecycleRetryAttempts
        pendingLifecycleRetryAttempts++
        val delayMs = minOf(RECONNECT_DELAY_MS * (1L shl minOf(attempt, 6)), RECONNECT_BACKOFF_MAX)
        val retryTask = Runnable {
            if (pendingLifecycleVersion != version) return@Runnable
            val pending = pendingAppLifecycle ?: return@Runnable

            if (tryNotifyLifecycle(version, pending)) return@Runnable

            ensureBindIfNeeded()
            schedulePendingLifecycleRetry(version)
        }
        pendingLifecycleRetry = retryTask
        mainHandler.postDelayed(retryTask, delayMs)
    }

    private fun rebindAndNotifyLifecycle(context: Context, isForeground: Boolean, version: Long) {
        pendingAppLifecycle = isForeground
        pendingLifecycleVersion = (version) and Long.MAX_VALUE
        sentLifecycleVersion = minOf(sentLifecycleVersion, version - 1)
        if (!connectionActive) {
            rebind(context)
        }
    }

    private fun flushPendingAppLifecycle(tag: String = "pending") {
        val pending = pendingAppLifecycle ?: return
        val version = pendingLifecycleVersion
        if (version <= sentLifecycleVersion) {
            pendingAppLifecycle = null
            return
        }
        val s = service
        if (s == null || !connectionActive || !bound) {
            val ctx = contextRef?.get()
            if (ctx != null) {
                rebindAndNotifyLifecycle(ctx, pending, version)
            }
            schedulePendingLifecycleRetry(version)
            return
        }

        runCatching {
            s.notifyAppLifecycle(pending)
            sentLifecycleVersion = version
            pendingAppLifecycle = null
            resetPendingLifecycleRetryState()
            Log.d(TAG, "notifyAppLifecycle ($tag): isForeground=$pending")
        }.onFailure {
            Log.w(TAG, "Failed to notify $tag app lifecycle", it)
            val ctx = contextRef?.get()
            if (ctx != null) {
                rebindAndNotifyLifecycle(ctx, pending, version)
            }
            schedulePendingLifecycleRetry(version)
        }
    }

    private fun cleanupConnection() {
        runCatching { binder?.unlinkToDeath(deathRecipient, 0) }
        binder = null
        service = null
        bound = false
        bindingInProgress = false
        callbackRegistered = false
        clearPendingUrlTestRequests()
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "Service connected")
            this@SingBoxRemote.binder = binder
            reconnectAttempts = 0
            clearPendingReconnect()

            runCatching { binder?.linkToDeath(deathRecipient, 0) }

            val s = ISingBoxService.Stub.asInterface(binder)
            service = s
            bound = true
            bindingInProgress = false

            if (s != null && !callbackRegistered) {
                runCatching {
                    s.registerCallback(callback)
                    callbackRegistered = true
                }
            }

            if (!syncStateFromService(s)) {
                Log.w(TAG, "Initial service state sync failed, rebinding")
                contextRef?.get()?.let { rebind(it) }
                return
            }

            startReadinessFreshnessWatchdog()
            flushPendingAppLifecycle()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Service disconnected")
            unregisterCallback()
            service = null
            bound = false
            bindingInProgress = false
            stopReadinessFreshnessWatchdog()
            markReadinessUnavailable("service_disconnected")
            clearPendingUrlTestRequests()

            val ctx = contextRef?.get()
            val systemVpn = ctx != null && hasSystemVpn(ctx)
            val storedManuallyStopped = VpnStateStore.isManuallyStopped()
            val storedMode = VpnStateStore.getMode()
            if (shouldReconnectAfterServiceLoss(systemVpn, storedManuallyStopped, storedMode)) {
                Log.i(
                    TAG,
                    "Service disconnected but runtime marker is active, keeping state and reconnecting"
                )
                scheduleReconnect()
            } else {
                syncStoppedStateAfterDisconnect()
            }
        }
    }

    private fun unregisterCallback() {
        val s = service
        if (s != null && callbackRegistered) {
            runCatching { s.unregisterCallback(callback) }
        }
        callbackRegistered = false
    }

    private fun syncStateFromService(s: ISingBoxService?): Boolean {
        if (s == null) return false
        return runCatching {
            val snapshot = s.stateSnapshot.toRuntimeStateSnapshot()
            val st = ServiceState.values().getOrNull(snapshot.stateOrdinal)
                ?: ServiceState.STOPPED
            if (!applyStateSnapshot(snapshot, fromBoundService = true)) {
                Log.w(TAG, "Ignored stale service snapshot generation=${snapshot.generation}")
                return@runCatching
            }
            Log.i(
                TAG,
                "State synced: $st, generation=${snapshot.generation}, running=${_isRunning.value}"
            )

            when {
                snapshot.readiness.isReady(
                    serviceState = st,
                    mode = VpnStateStore.getMode(),
                    ipcBound = bound,
                    apiLevel = Build.VERSION.SDK_INT,
                    nowElapsedMs = SystemClock.elapsedRealtime()
                ) -> completePendingRecovery(RecoveryResult.AlreadyConnected)
                st == ServiceState.STOPPED -> {
                    if (connectionActive) {
                        failPendingRecovery("Recovery synced STOPPED state from service")
                    }
                }
            }
        }.onFailure {
            Log.e(TAG, "Failed to sync state from service", it)
        }.isSuccess
    }

    @Suppress("DEPRECATION")
    private fun hasSystemVpn(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm?.allNetworks?.any { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@any false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check system VPN", e)
            false
        }
    }

    private fun scheduleReconnect() {
        val ctx = contextRef?.get() ?: return

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            val backoffAttempts = reconnectAttempts - MAX_RECONNECT_ATTEMPTS
            val backoffDelay = minOf(
                RECONNECT_DELAY_MS * (1L shl minOf(backoffAttempts, 6)),
                RECONNECT_BACKOFF_MAX
            )
            Log.w(TAG, "Max reconnect attempts reached, scheduling retry #$reconnectAttempts in ${backoffDelay}ms")
            reconnectAttempts++
            clearPendingReconnect()

            val reconnectTask = Runnable {
                if (canAttemptReconnect()) {
                    Log.i(TAG, "Reconnect backoff attempt #$reconnectAttempts")
                    doBindService(ctx)
                }
            }
            pendingReconnect = reconnectTask
            mainHandler.postDelayed(reconnectTask, backoffDelay)
            return
        }

        reconnectAttempts++
        val delay = RECONNECT_DELAY_MS * reconnectAttempts
        clearPendingReconnect()

        val reconnectTask = Runnable {
            if (canAttemptReconnect()) {
                Log.i(TAG, "Reconnect attempt #$reconnectAttempts")
                doBindService(ctx)
            }
        }
        pendingReconnect = reconnectTask
        mainHandler.postDelayed(reconnectTask, delay)
    }

    private fun canAttemptReconnect(): Boolean {
        if (!connectionActive) return false
        if (bound) return false
        if (bindingInProgress) return false
        return contextRef?.get() != null
    }

    private fun doBindService(context: Context) {
        val intent = Intent(context, SingBoxIpcService::class.java)
        runCatching {
            context.applicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }.onSuccess { boundSuccessfully ->
            if (!boundSuccessfully) {
                Log.w(TAG, "bindService returned false, scheduling reconnect")
                bindingInProgress = false
                bound = false
                service = null
                binder = null
                failPendingRecovery("bindService returned false")
                scheduleReconnect()
            } else {
                bindingInProgress = true
            }
        }.onFailure {
            Log.e(TAG, "Failed to bind service", it)
            bindingInProgress = false
            bound = false
            service = null
            binder = null
            failPendingRecovery("Failed to bind service", it)
            scheduleReconnect()
        }
    }

    fun connect(context: Context) {
        if (connectionActive) {
            Log.d(TAG, "connect: already active, skip")
            return
        }
        connectionActive = true
        contextRef = WeakReference(context.applicationContext)
        reconnectAttempts = 0
        clearPendingReconnect()
        doBindService(context)
    }

    fun disconnect(context: Context) {
        unregisterCallback()
        resetPendingLifecycleRetryState()
        clearPendingReconnect()
        if (connectionActive) {
            runCatching { context.applicationContext.unbindService(conn) }
        }
        connectionActive = false
        runCatching { binder?.unlinkToDeath(deathRecipient, 0) }
        binder = null
        service = null
        bound = false
        bindingInProgress = false
    }

    fun ensureBound(context: Context) {
        contextRef = WeakReference(context.applicationContext)

        val currentService = service
        val servicePresent = currentService != null
        val serviceAlive = if (connectionActive && bound && servicePresent) {
            runCatching { currentService.stateSnapshot }.isSuccess
        } else {
            false
        }

        when (resolveSingBoxEnsureBoundAction(
            connectionActive = connectionActive,
            bound = bound,
            servicePresent = servicePresent,
            serviceAlive = serviceAlive,
            bindingInProgress = bindingInProgress
        )) {
            EnsureBoundAction.NONE -> {
                if (!syncStateFromService(currentService)) {
                    Log.w(TAG, "ensureBound: live service state sync failed, rebinding")
                    rebind(context)
                }
                return
            }
            EnsureBoundAction.CONNECT -> connect(context)
            EnsureBoundAction.WAIT_FOR_BIND -> Log.d(TAG, "ensureBound: binding already in progress")
            EnsureBoundAction.REBIND -> {
                if (bound && servicePresent && !serviceAlive) {
                    Log.w(TAG, "Service connection stale, rebinding...")
                }
                disconnect(context)
                connect(context)
            }
        }
    }

    fun queryAndSyncState(context: Context): Boolean {
        contextRef = WeakReference(context.applicationContext)
        reconnectAttempts = 0

        val s = service
        if (connectionActive && bound && s != null) {
            val synced = syncStateFromService(s)

            if (synced) {
                Log.i(TAG, "queryAndSyncState: synced from service")
                return true
            } else {
                Log.w(TAG, "queryAndSyncState: sync failed, rebinding")
                rebind(context)
                return false
            }
        }

        val ctx = contextRef?.get() ?: return false
        val hasVpn = hasSystemVpn(ctx)
        val persistedState = resolvePersistedState(hasVpn)

        if (persistedState != ServiceState.STOPPED && !connectionActive) {
            Log.i(TAG, "queryAndSyncState: persisted state=$persistedState, connecting")
            connect(ctx)

            if (_state.value != persistedState) {
                applyStateSnapshot(
                    resolveLocalStateSnapshot(VpnStateStore.getRuntimeStateSnapshot(), persistedState)
                )
            }
            return true
        }

        if (persistedState == ServiceState.STOPPED && _state.value != ServiceState.STOPPED) {
            Log.i(TAG, "queryAndSyncState: persisted state is STOPPED, correcting")
            applyStateSnapshot(
                resolveLocalStateSnapshot(
                    persisted = VpnStateStore.getRuntimeStateSnapshot(),
                    state = ServiceState.STOPPED,
                    clearTransientState = true
                )
            )
        }

        if (!connectionActive) {
            connect(ctx)
        }

        return connectionActive
    }

    fun rebind(context: Context) {
        Log.i(TAG, "rebind: forcing disconnect -> connect cycle")
        contextRef = WeakReference(context.applicationContext)
        reconnectAttempts = 0

        disconnect(context)
        connect(context)

        syncStateFromStore()
    }

    fun rebindAndNotifyForeground(context: Context) {
        Log.i(TAG, "rebindAndNotifyForeground: start (atomic rebind + foreground)")
        contextRef = WeakReference(context.applicationContext)
        reconnectAttempts = 0

        pendingAppLifecycle = true

        disconnect(context)

        connect(context)

        syncStateFromStore()
    }

    fun isCallbackStale(): Boolean {
        if (lastCallbackReceivedAtMs == 0L) return false
        val elapsed = SystemClock.elapsedRealtime() - lastCallbackReceivedAtMs
        return elapsed > CALLBACK_TIMEOUT_MS
    }

    fun forceStoreSync() {
        syncStateFromStore()
    }

    fun instantRecovery(
        context: Context,
        callback: ((RecoveryResult) -> Unit)?
    ): RecoveryResult {
        syncStateFromStore()
        Log.i(TAG, "instantRecovery: Phase 1 done, state=${_state.value}")

        contextRef = WeakReference(context.applicationContext)

        if (!connectionActive) {
            val recovering = startPendingRecovery(callback, System.currentTimeMillis())
            callback?.invoke(recovering)
            Log.i(TAG, "instantRecovery: IPC not active, connecting (not rebinding)")
            connect(context)
            return recovering
        }

        if (!bound || service == null) {
            val recovering = startPendingRecovery(callback, System.currentTimeMillis())
            callback?.invoke(recovering)
            Log.i(TAG, "instantRecovery: connection in progress, skip rebind")
            return recovering
        }

        val recovering = startPendingRecovery(callback, System.currentTimeMillis())
        callback?.invoke(recovering)
        mainHandler.post {
            val s = service ?: run {
                Log.w(TAG, "instantRecovery: service became null, rebinding")
                rebind(context)
                return@post
            }

            val ok = syncStateFromService(s)

            if (ok) {
                Log.i(TAG, "instantRecovery: Phase 2 AIDL verify ok")
                return@post
            }

            Log.w(TAG, "instantRecovery: AIDL verify failed, rebinding")
            rebind(context)
        }

        return recovering
    }

    @Deprecated("Use instantRecovery(context, callback) to observe async recovery result")
    fun instantRecovery(context: Context) {
        instantRecovery(context, callback = null)
    }

    fun isBound(): Boolean = connectionActive && bound && service != null

    fun isConnectionActive(): Boolean = connectionActive

    fun unbind(context: Context) {
        disconnect(context)
    }

    fun getLastSyncAge(): Long = System.currentTimeMillis() - lastSyncTimeMs

    fun notifyAppLifecycle(isForeground: Boolean) {
        val version = pendingLifecycleVersion + 1
        pendingLifecycleVersion = (version) and Long.MAX_VALUE
        pendingAppLifecycle = isForeground
        resetPendingLifecycleRetryState()

        val s = service
        if (s != null && connectionActive && bound) {
            flushPendingAppLifecycle(tag = "immediate")
            return
        }

        val ctx = contextRef?.get()
        if (ctx != null) {
            rebindAndNotifyLifecycle(ctx, isForeground, version)
        }
        schedulePendingLifecycleRetry(version)
        Log.d(TAG, "notifyAppLifecycle: queued version=$version isForeground=$isForeground")
    }

    object HotReloadResult {
        const val SUCCESS = 0
        const val VPN_NOT_RUNNING = 1
        const val KERNEL_ERROR = 2
        const val UNKNOWN_ERROR = 3
        const val IPC_ERROR = 4
    }

    suspend fun urlTestNodeDelay(groupTag: String, nodeTag: String, timeoutMs: Int): Int? {
        val s = service ?: return null
        if (!connectionActive || !bound) return null

        val requestId = urlTestRequestId.incrementAndGet()
        val deferred = CompletableDeferred<Int?>()
        pendingUrlTestRequests[requestId] = deferred

        return try {
            s.requestUrlTestNodeDelay(requestId, groupTag, nodeTag, timeoutMs)
            withTimeoutOrNull(timeoutMs.coerceIn(1000, 30000).toLong() + 1000L) {
                deferred.await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "urlTestNodeDelay request failed: requestId=$requestId", e)
            null
        } finally {
            pendingUrlTestRequests.remove(requestId)
        }
    }

    fun hotReloadConfig(configContent: String): Int {
        val s = service
        if (s == null || !connectionActive || !bound) {
            Log.w(TAG, "hotReloadConfig: service not connected")
            return HotReloadResult.IPC_ERROR
        }

        return runCatching {
            val result = s.hotReloadConfig(configContent)
            Log.i(TAG, "hotReloadConfig: result=$result")
            result
        }.getOrElse { e ->
            Log.e(TAG, "hotReloadConfig: IPC failed", e)
            HotReloadResult.IPC_ERROR
        }
    }
}
