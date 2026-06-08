package com.kunk.singbox.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.aidl.ISingBoxService
import com.kunk.singbox.aidl.ISingBoxServiceCallback
import com.kunk.singbox.service.ServiceState
import java.util.concurrent.ConcurrentHashMap
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

@Suppress("TooManyFunctions")
object SingBoxRemote {
    private const val TAG = "SingBoxRemote"

    private const val RECONNECT_DELAY_MS = 100L
    private const val MAX_RECONNECT_ATTEMPTS = 10
    private const val RECONNECT_BACKOFF_MAX = 60000L

    private const val CALLBACK_TIMEOUT_MS = 8_000L
    private const val RECOVERY_EXPECTED_DURATION_MS = 5_000L

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
    private var pendingRecoveryCallback: ((RecoveryResult) -> Unit)? = null

    private val urlTestRequestId = AtomicLong(0L)
    private val pendingUrlTestRequests = ConcurrentHashMap<Long, CompletableDeferred<Int?>>()

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
        storedManuallyStopped: Boolean
    ): Boolean {
        return systemVpn && !storedManuallyStopped
    }

    private val callback = object : ISingBoxServiceCallback.Stub() {
        override fun onStateChanged(state: Int, activeLabel: String?, lastError: String?, manuallyStopped: Boolean) {
            lastCallbackReceivedAtMs = SystemClock.elapsedRealtime()
            val st = ServiceState.values().getOrNull(state)
                ?: ServiceState.STOPPED
            val oldState = _state.value
            updateState(st, activeLabel, lastError, manuallyStopped)
            Log.i(TAG, "[UI] Callback received: $oldState -> $st, activeLabel=$activeLabel")

            when (st) {
                ServiceState.RUNNING -> completePendingRecovery(RecoveryResult.AlreadyConnected)
                ServiceState.STOPPED -> {
                    if (connectionActive) {
                        failPendingRecovery("Recovery stopped before reaching RUNNING")
                    }
                }

                else -> Unit
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
        pendingRecoveryCallback = callback
        return RecoveryResult.Recovering(
            startTime = startTime,
            expectedDuration = RECOVERY_EXPECTED_DURATION_MS
        )
    }

    private fun completePendingRecovery(result: RecoveryResult) {
        val callback = pendingRecoveryCallback ?: return
        pendingRecoveryCallback = null
        callback.invoke(result)
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

    /**
     */
    private fun syncStateFromStore() {
        val state = resolvePersistedState(hasVpnTransport = false)
        val storedLabel = VpnStateStore.getActiveLabel()
        val storedError = VpnStateStore.getLastError()
        val storedManuallyStopped = VpnStateStore.isManuallyStopped()

        Log.i(TAG, "syncStateFromStore: state=$state, label=$storedLabel")
        updateState(state, storedLabel, storedError, storedManuallyStopped)
    }

    private fun syncStoppedStateAfterDisconnect() {
        val stopState = resolveDisconnectedStopState(
            storedLastError = VpnStateStore.getLastError(),
            storedManuallyStopped = VpnStateStore.isManuallyStopped()
        )
        VpnStateStore.clearRuntimeState(preserveLastError = stopState.preserveLastError)
        updateState(ServiceState.STOPPED, "", stopState.lastError, stopState.manuallyStopped)
    }

    private val deathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            Log.w(TAG, "Binder died, delegating to backoff reconnect")
            service = null
            callbackRegistered = false
            bound = false

            mainHandler.post {
                val ctx = contextRef?.get()
                if (ctx != null && !SagerConnection_restartingApp) {
                    val systemVpn = hasSystemVpn(ctx)
                    val storedManuallyStopped = VpnStateStore.isManuallyStopped()
                    if (!shouldReconnectAfterServiceLoss(systemVpn, storedManuallyStopped)) {
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
            mode == VpnStateStore.CoreMode.PROXY && hasVpnTransport -> ServiceState.RUNNING
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

            syncStateFromService(s)

            flushPendingAppLifecycle()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Service disconnected")
            unregisterCallback()
            service = null
            bound = false
            bindingInProgress = false
            clearPendingUrlTestRequests()

            val ctx = contextRef?.get()
            val systemVpn = ctx != null && hasSystemVpn(ctx)
            val storedManuallyStopped = VpnStateStore.isManuallyStopped()
            if (shouldReconnectAfterServiceLoss(systemVpn, storedManuallyStopped)) {
                Log.i(
                    TAG,
                    "Service disconnected but system VPN present, keeping state and reconnecting"
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

    private fun syncStateFromService(s: ISingBoxService?) {
        if (s == null) return
        runCatching {
            val st = ServiceState.values().getOrNull(s.state)
                ?: ServiceState.STOPPED
            updateState(st, s.activeLabel.orEmpty(), s.lastError.orEmpty(), s.isManuallyStopped)
            Log.i(TAG, "State synced: $st, running=${_isRunning.value}")

            when (st) {
                ServiceState.RUNNING -> completePendingRecovery(RecoveryResult.AlreadyConnected)
                ServiceState.STOPPED -> {
                    if (connectionActive) {
                        failPendingRecovery("Recovery synced STOPPED state from service")
                    }
                }

                else -> Unit
            }
        }.onFailure {
            Log.e(TAG, "Failed to sync state from service", it)
        }
    }

    @Suppress("DEPRECATION")
    private fun hasSystemVpn(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                cm?.allNetworks?.any { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@any false
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } == true
            } else {
                false
            }
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

        if (connectionActive && bound && service != null) {
            val isAlive = runCatching { service?.state }.isSuccess
            if (isAlive) return

            Log.w(TAG, "Service connection stale, rebinding...")
        }

        if (!connectionActive) {
            connect(context)
        } else if (bindingInProgress) {
            Log.d(TAG, "ensureBound: binding already in progress")
        } else if (!bound || service == null) {
            disconnect(context)
            connect(context)
        }
    }

    /**
     *
     */
    fun queryAndSyncState(context: Context): Boolean {
        contextRef = WeakReference(context.applicationContext)
        reconnectAttempts = 0

        val s = service
        if (connectionActive && bound && s != null) {
            val synced = runCatching {
                syncStateFromService(s)
                true
            }.getOrDefault(false)

            if (synced) {
                Log.i(TAG, "queryAndSyncState: synced from service")
                return true
            } else {
                Log.w(TAG, "queryAndSyncState: sync failed")
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
                updateState(
                    persistedState,
                    VpnStateStore.getActiveLabel(),
                    VpnStateStore.getLastError(),
                    VpnStateStore.isManuallyStopped()
                )
            }
            return true
        }

        if (persistedState == ServiceState.STOPPED && _state.value != ServiceState.STOPPED) {
            Log.i(TAG, "queryAndSyncState: persisted state is STOPPED, correcting")
            updateState(ServiceState.STOPPED, "", "", VpnStateStore.isManuallyStopped())
        }

        if (!connectionActive) {
            connect(ctx)
        }

        return connectionActive
    }

    /**
     */
    fun rebind(context: Context) {
        Log.i(TAG, "rebind: forcing disconnect -> connect cycle")
        contextRef = WeakReference(context.applicationContext)
        reconnectAttempts = 0

        disconnect(context)
        connect(context)

        syncStateFromStore()
    }

    /**
     *
     *
     */
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

    /**
     */
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

        mainHandler.post {
            val s = service ?: run {
                Log.w(TAG, "instantRecovery: service became null, rebinding")
                val recovering = startPendingRecovery(callback, System.currentTimeMillis())
                callback?.invoke(recovering)
                rebind(context)
                return@post
            }

            val ok = runCatching {
                syncStateFromService(s)
                true
            }.getOrDefault(false)

            if (ok) {
                Log.i(TAG, "instantRecovery: Phase 2 AIDL verify ok")
                callback?.invoke(RecoveryResult.AlreadyConnected)
                return@post
            }

            Log.w(TAG, "instantRecovery: AIDL verify failed, rebinding")
            val recovering = startPendingRecovery(callback, System.currentTimeMillis())
            callback?.invoke(recovering)
            rebind(context)
        }

        return RecoveryResult.AlreadyConnected
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

    /**
     */
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
