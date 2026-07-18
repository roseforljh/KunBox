package com.kunk.singbox.ipc

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.RemoteCallbackList
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.aidl.ISingBoxServiceCallback
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.service.manager.BackgroundPowerManager
import com.kunk.singbox.service.manager.ServiceStateHolder
import com.kunk.singbox.service.manager.UrlTestTagMatcher
import com.kunk.singbox.utils.perf.PerfTracer
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal fun Bundle.toRuntimeStateSnapshot(): VpnStateStore.RuntimeStateSnapshot {
    return VpnStateStore.normalizeRuntimeStateSnapshot(
        VpnStateStore.RuntimeStateSnapshot(
            generation = getLong(SingBoxIpcHub.SNAPSHOT_GENERATION, 0L),
            stateOrdinal = getInt(SingBoxIpcHub.SNAPSHOT_STATE, ServiceState.STOPPED.ordinal),
            activeLabel = getString(SingBoxIpcHub.SNAPSHOT_ACTIVE_LABEL).orEmpty(),
            lastError = getString(SingBoxIpcHub.SNAPSHOT_LAST_ERROR).orEmpty(),
            manuallyStopped = getBoolean(SingBoxIpcHub.SNAPSHOT_MANUALLY_STOPPED, false)
        )
    )
}

@Suppress("TooManyFunctions")
object SingBoxIpcHub {
    private const val TAG = "SingBoxIpcHub"

    internal const val SNAPSHOT_GENERATION = "generation"
    internal const val SNAPSHOT_STATE = "state"
    internal const val SNAPSHOT_ACTIVE_LABEL = "active_label"
    internal const val SNAPSHOT_LAST_ERROR = "last_error"
    internal const val SNAPSHOT_MANUALLY_STOPPED = "manually_stopped"

    private const val MIN_BROADCAST_INTERVAL_MS = 50L

    private val mainHandler = Handler(Looper.getMainLooper())

    private val broadcastScheduler = ScheduledThreadPoolExecutor(1).apply {
        removeOnCancelPolicy = true
    }

    private val logRepo by lazy { LogRepository.getInstance() }
    private val ipcScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingUrlTestJobs = ConcurrentHashMap<Long, kotlinx.coroutines.Job>()

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logRepo.addLog("INFO [IPC] $msg")
    }

    private val stateLock = Any()

    @Volatile
    private var stateSnapshot = VpnStateStore.RuntimeStateSnapshot()

    private val callbacks = RemoteCallbackList<ISingBoxServiceCallback>()

    private val stateNames = ServiceState.entries.map { it.name }.toTypedArray()

    private val lastBroadcastAtMs = AtomicLong(0L)
    private val broadcastPending = AtomicBoolean(false)
    private val broadcasting = AtomicBoolean(false)
    private val broadcastLock = ReentrantLock()
    private val callbackBroadcastLock = ReentrantLock()

    @Volatile
    private var powerManager: BackgroundPowerManager? = null

    @Volatile
    private var serviceRef: WeakReference<SingBoxIpcService>? = null

    private val lastStateUpdateAtMs = AtomicLong(0L)
    private val lastBackgroundAtMs = AtomicLong(0L)

    fun setPowerManager(manager: BackgroundPowerManager?) {
        powerManager = manager
        Log.d(TAG, "PowerManager ${if (manager != null) "set" else "cleared"}")
    }

    fun registerService(service: SingBoxIpcService) {
        synchronized(this) {
            serviceRef?.clear()
            serviceRef = WeakReference(service)
        }
        synchronized(stateLock) {
            val persisted = VpnStateStore.getRuntimeStateSnapshot()
            if (persisted.generation >= stateSnapshot.generation) {
                stateSnapshot = persisted
            }
        }
        log("SingBoxIpcService registered")
    }

    fun unregisterService() {
        synchronized(this) {
            serviceRef?.clear()
            serviceRef = null
        }
        log("SingBoxIpcService unregistered")
    }

    fun onServiceBinderDied() {
        synchronized(this) {
            serviceRef?.clear()
            serviceRef = null
        }
        synchronized(stateLock) {
            val current = stateSnapshot
            VpnStateStore.clearRuntimeState(
                preserveLastError = shouldPreserveLastErrorOnBinderDied(
                    lastError = current.lastError,
                    manuallyStopped = current.manuallyStopped
                )
            )
            stateSnapshot = VpnStateStore.getRuntimeStateSnapshot()
        }
        Log.w(TAG, "SingBoxIpcService binder died")
        runCatching {
            logRepo.addLog("WARN [IPC] SingBoxIpcService binder died")
        }
    }

    internal fun shouldPreserveLastErrorOnBinderDied(
        lastError: String,
        manuallyStopped: Boolean
    ): Boolean {
        return manuallyStopped && lastError.isNotBlank()
    }

    internal fun resolveRealtimeUrlTestNodeDelay(
        nodeTag: String,
        progressResults: List<Map<String, Int>>
    ): Int {
        var resolvedDelay = -1
        progressResults.forEach { results ->
            val matched = UrlTestTagMatcher.resolveDelayDetail(results, nodeTag)
            val candidate = matched?.delay ?: -1
            if (candidate > 0) {
                resolvedDelay = candidate
            }
        }
        return resolvedDelay
    }

    private fun currentLiveCoreState(): ServiceState? {
        return when {
            ServiceStateHolder.instance != null && ServiceStateHolder.isRunning -> ServiceState.RUNNING
            ProxyOnlyService.isRunning -> ServiceState.RUNNING
            ServiceStateHolder.instance != null && ServiceStateHolder.isStarting -> ServiceState.STARTING
            ProxyOnlyService.isStarting -> ServiceState.STARTING
            ServiceStateHolder.instance != null -> ServiceState.STOPPING
            else -> null
        }
    }

    internal fun resolveVisibleStateOrdinal(
        cachedStateOrdinal: Int,
        liveCoreState: ServiceState?
    ): Int {
        return when (ServiceState.values().getOrNull(cachedStateOrdinal)) {
            ServiceState.RUNNING -> {
                if (liveCoreState == ServiceState.RUNNING) cachedStateOrdinal else ServiceState.STOPPED.ordinal
            }
            ServiceState.STARTING -> {
                if (liveCoreState == ServiceState.STARTING || liveCoreState == ServiceState.RUNNING) {
                    cachedStateOrdinal
                } else {
                    ServiceState.STOPPED.ordinal
                }
            }
            ServiceState.STOPPING -> {
                if (liveCoreState != null) cachedStateOrdinal else ServiceState.STOPPED.ordinal
            }
            ServiceState.STOPPED -> ServiceState.STOPPED.ordinal
            null -> ServiceState.STOPPED.ordinal
        }
    }

    fun onAppLifecycle(isForeground: Boolean) {
        val vpnState = stateNames.getOrNull(stateSnapshot.stateOrdinal) ?: "UNKNOWN"
        log("onAppLifecycle: isForeground=$isForeground, vpnState=$vpnState")

        if (isForeground) {
            powerManager?.onAppForeground()
        } else {
            lastBackgroundAtMs.set(SystemClock.elapsedRealtime())
            powerManager?.onAppBackground()
        }
    }

    fun getStateSnapshotBundle(): Bundle {
        return currentStateSnapshot().toBundle()
    }

    fun getLastStateUpdateTime(): Long = lastStateUpdateAtMs.get()

    private fun currentStateSnapshot(): VpnStateStore.RuntimeStateSnapshot {
        return synchronized(stateLock) {
            val snapshot = stateSnapshot
            val visibleStateOrdinal = resolveVisibleStateOrdinal(
                cachedStateOrdinal = snapshot.stateOrdinal,
                liveCoreState = currentLiveCoreState()
            )
            if (visibleStateOrdinal == snapshot.stateOrdinal) {
                snapshot
            } else {
                VpnStateStore.updateRuntimeStateSnapshot(
                    state = ServiceState.values().getOrNull(visibleStateOrdinal) ?: ServiceState.STOPPED
                ).also { stateSnapshot = it }
            }
        }
    }

    private fun VpnStateStore.RuntimeStateSnapshot.toBundle(): Bundle {
        return Bundle().apply {
            putLong(SNAPSHOT_GENERATION, generation)
            putInt(SNAPSHOT_STATE, stateOrdinal)
            putString(SNAPSHOT_ACTIVE_LABEL, activeLabel)
            putString(SNAPSHOT_LAST_ERROR, lastError)
            putBoolean(SNAPSHOT_MANUALLY_STOPPED, manuallyStopped)
        }
    }

    fun update(
        state: ServiceState? = null,
        activeLabel: String? = null,
        lastError: String? = null,
        manuallyStopped: Boolean? = null
    ) {
        val updateStart = SystemClock.elapsedRealtime()
        val updatedSnapshot = synchronized(stateLock) {
            val current = stateSnapshot
            state?.let {
                val oldState = stateNames.getOrNull(current.stateOrdinal) ?: "UNKNOWN"
                log("state update: $oldState -> ${it.name}")
            }
            VpnStateStore.updateRuntimeStateSnapshot(
                state = state,
                activeLabel = activeLabel,
                lastError = lastError,
                manuallyStopped = manuallyStopped
            ).also { stateSnapshot = it }
        }

        lastStateUpdateAtMs.set(SystemClock.elapsedRealtime())
        broadcastLock.withLock {
            broadcastPending.set(true)
            scheduleBroadcastIfNeededLocked()
        }

        Log.d(
            TAG,
            "[IPC] update generation=${updatedSnapshot.generation} " +
                "completed in ${SystemClock.elapsedRealtime() - updateStart}ms"
        )
    }

    fun registerCallback(callback: ISingBoxServiceCallback) {
        callbacks.register(callback)
        mainHandler.post {
            runCatching {
                val snapshot = currentStateSnapshot()
                callback.onStateChanged(
                    snapshot.stateOrdinal,
                    snapshot.activeLabel,
                    snapshot.lastError,
                    snapshot.manuallyStopped,
                    snapshot.generation
                )
            }
        }
    }

    fun unregisterCallback(callback: ISingBoxServiceCallback) {
        callbacks.unregister(callback)
    }

    private inline fun broadcastCallbacks(crossinline action: (ISingBoxServiceCallback) -> Unit) {
        callbackBroadcastLock.withLock {
            val n = callbacks.beginBroadcast()
            try {
                for (i in 0 until n) {
                    runCatching {
                        action(callbacks.getBroadcastItem(i))
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }

    private fun scheduleBroadcastIfNeededLocked() {
        if (broadcasting.compareAndSet(false, true)) {
            broadcastScheduler.execute { drainOrReschedule() }
        }
    }

    private fun drainOrReschedule() {
        try {
            val remaining = broadcastLock.withLock {
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - lastBroadcastAtMs.get()
                val delayMs = MIN_BROADCAST_INTERVAL_MS - elapsed

                if (delayMs <= 0L) {
                    broadcastPending.set(false)
                }

                delayMs
            }

            if (remaining > 0) {
                broadcastScheduler.schedule(
                    { drainOrReschedule() },
                    remaining,
                    TimeUnit.MILLISECONDS
                )
                return
            }

            val snapshot = currentStateSnapshot()

            broadcastCallbacks { callback ->
                callback.onStateChanged(
                    snapshot.stateOrdinal,
                    snapshot.activeLabel,
                    snapshot.lastError,
                    snapshot.manuallyStopped,
                    snapshot.generation
                )
            }

            broadcastLock.withLock {
                lastBroadcastAtMs.set(SystemClock.elapsedRealtime())
                broadcasting.set(false)
                if (broadcastPending.get()) {
                    scheduleBroadcastIfNeededLocked()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "drainOrReschedule failed", t)
            broadcastLock.withLock {
                broadcasting.set(false)
                if (broadcastPending.get()) {
                    scheduleBroadcastIfNeededLocked()
                }
            }
        }
    }

    object HotReloadResult {
        const val SUCCESS = 0
        const val VPN_NOT_RUNNING = 1
        const val KERNEL_ERROR = 2
        const val UNKNOWN_ERROR = 3
    }

    @Suppress("UNUSED_PARAMETER")
    fun requestUrlTestNodeDelay(requestId: Long, groupTag: String, nodeTag: String, timeoutMs: Int) {
        val context = serviceRef?.get()
        if (context == null) {
            requestUrlTestNodeDelayResult(requestId, -1)
            return
        }
        pendingUrlTestJobs.remove(requestId)?.cancel()
        val job = ipcScope.launch {
            val delay = runCatching {
                val repo = ConfigRepository.getInstance(context)
                val node = repo.getNodeByName(nodeTag) ?: return@runCatching -1
                repo.testNodeLatency(node.id).takeIf { it > 0L && it <= Int.MAX_VALUE }?.toInt() ?: -1
            }.getOrElse {
                Log.w(TAG, "requestUrlTestNodeDelay failed: requestId=$requestId", it)
                -1
            }
            requestUrlTestNodeDelayResult(requestId, delay)
        }
        pendingUrlTestJobs[requestId] = job
        job.invokeOnCompletion { pendingUrlTestJobs.remove(requestId) }
    }

    fun requestUrlTestNodeDelayResult(requestId: Long, delay: Int) {
        broadcastCallbacks { callback ->
            callback.onUrlTestNodeDelayResult(requestId, delay)
        }
    }

    fun hotReloadConfig(configContent: String): Int {
        log("[HotReload] IPC request received")

        if (stateSnapshot.stateOrdinal != ServiceState.RUNNING.ordinal) {
            Log.w(TAG, "[HotReload] VPN not running, state=${stateSnapshot.stateOrdinal}")
            PerfTracer.recordEvent(PerfTracer.Phases.HOT_RELOAD, "vpn_not_running")
            return HotReloadResult.VPN_NOT_RUNNING
        }

        val service = ServiceStateHolder.instance
        if (service == null) {
            Log.e(TAG, "[HotReload] SingBoxService instance is null")
            PerfTracer.recordEvent(PerfTracer.Phases.HOT_RELOAD, "service_missing")
            return HotReloadResult.VPN_NOT_RUNNING
        }

        val startedAtMs = SystemClock.elapsedRealtime()
        return try {
            val result = service.performHotReloadSync(configContent)
            if (result) {
                log("[HotReload] Success")
                PerfTracer.recordDuration(
                    PerfTracer.Phases.HOT_RELOAD,
                    SystemClock.elapsedRealtime() - startedAtMs,
                    "success"
                )
                HotReloadResult.SUCCESS
            } else {
                Log.e(TAG, "[HotReload] Kernel returned false")
                PerfTracer.recordDuration(
                    PerfTracer.Phases.HOT_RELOAD,
                    SystemClock.elapsedRealtime() - startedAtMs,
                    "kernel_error"
                )
                HotReloadResult.KERNEL_ERROR
            }
        } catch (e: Exception) {
            Log.e(TAG, "[HotReload] Exception: ${e.message}", e)
            PerfTracer.recordDuration(
                PerfTracer.Phases.HOT_RELOAD,
                SystemClock.elapsedRealtime() - startedAtMs,
                "exception"
            )
            HotReloadResult.UNKNOWN_ERROR
        }
    }
}
