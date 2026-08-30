package com.kunk.singbox.service.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.kunk.singbox.aidl.IRootSingBoxService
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.TrafficCaptureMode
import com.kunk.singbox.repository.SettingsRepository
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RootServiceConnection(
    private val context: Context,
    onDisconnected: () -> Unit
) : ServiceConnection {
    companion object {
        private const val TAG = "RootServiceConnection"
        private const val BIND_TIMEOUT_MS = 15_000L
    }

    @Volatile
    private var bound = false

    @Volatile
    private var disconnectedCallback = onDisconnected

    @Volatile
    var service: IRootSingBoxService? = null
        private set

    private var pending = CompletableDeferred<IRootSingBoxService>()

    internal fun beginBind() {
        if (service != null || bound) return
        pending = CompletableDeferred()
        RootService.bind(Intent(context, KunBoxRootService::class.java), this)
        bound = true
        Log.i(TAG, "[ROOT_BOOT] stage=bind_dispatched")
    }

    suspend fun bind(): IRootSingBoxService = withContext(Dispatchers.Main) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        service?.let {
            Log.i(TAG, "[ROOT_BOOT] stage=bind_reused")
            return@withContext it
        }
        Log.i(TAG, "[ROOT_BOOT] stage=bind_begin alreadyBound=$bound")
        try {
            beginBind()
            withTimeout(BIND_TIMEOUT_MS) { pending.await() }.also {
                Log.i(
                    TAG,
                    "[ROOT_BOOT] stage=bind_ready duration_ms=" +
                        (android.os.SystemClock.elapsedRealtime() - startedAt)
                )
            }
        } catch (error: Exception) {
            Log.e(
                TAG,
                "[ROOT_BOOT] stage=bind_failed duration_ms=" +
                    (android.os.SystemClock.elapsedRealtime() - startedAt),
                error
            )
            throw error
        }
    }

    fun unbind() {
        if (!bound) return
        runCatching { RootService.unbind(this) }
        bound = false
        service = null
        if (!pending.isCompleted) pending.cancel()
    }

    fun stopRootService() {
        runCatching { RootService.stop(Intent(context, KunBoxRootService::class.java)) }
        unbind()
    }

    internal fun transferDisconnectedCallback(callback: () -> Unit): RootServiceConnection {
        disconnectedCallback = callback
        return this
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        Log.i(TAG, "[ROOT_BOOT] stage=binder_connected component=${name?.flattenToShortString().orEmpty()}")
        val rootService = IRootSingBoxService.Stub.asInterface(binder)
        service = rootService
        if (!pending.isCompleted) pending.complete(rootService)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        Log.e(TAG, "[ROOT_BOOT] stage=binder_disconnected component=${name?.flattenToShortString().orEmpty()}")
        service = null
        bound = false
        disconnectedCallback()
    }

    override fun onBindingDied(name: ComponentName?) {
        Log.e(TAG, "[ROOT_BOOT] stage=binder_died component=${name?.flattenToShortString().orEmpty()}")
        onServiceDisconnected(name)
    }

    override fun onNullBinding(name: ComponentName?) {
        Log.e(TAG, "[ROOT_BOOT] stage=binder_null component=${name?.flattenToShortString().orEmpty()}")
        service = null
        bound = false
        if (!pending.isCompleted) {
            pending.completeExceptionally(IllegalStateException("RootService returned null binder"))
        }
        disconnectedCallback()
    }
}

internal fun shouldPrewarmRootService(
    captureMode: TrafficCaptureMode,
    active: Boolean,
    pending: String,
    running: Boolean,
    starting: Boolean
): Boolean = captureMode == TrafficCaptureMode.ROOT_TRANSPARENT &&
    !active && pending.isBlank() && !running && !starting

object RootServicePrewarmer {
    private val mutex = Mutex()

    @Volatile
    private var connection: RootServiceConnection? = null

    suspend fun prewarmIfIdle(context: Context): Result<Unit> = mutex.withLock {
        val appContext = context.applicationContext
        val settings = SettingsRepository.getInstance(appContext).settings.value
        if (!shouldPrewarmRootService(
                captureMode = settings.resolvedTrafficCaptureMode(),
                active = VpnStateStore.getActive(),
                pending = VpnStateStore.getPending(),
                running = RootTransparentForegroundService.isRunning,
                starting = RootTransparentForegroundService.isStarting
            )
        ) {
            return@withLock Result.success(Unit)
        }
        prewarmLocked(appContext)
    }

    private suspend fun prewarmLocked(context: Context): Result<Unit> {
        connection?.service?.let { return Result.success(Unit) }
        lateinit var next: RootServiceConnection
        next = RootServiceConnection(context) {
            if (connection === next) connection = null
        }
        connection = next
        val startedAt = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG, "[ROOT_PREWARM] event=started")
        return runCatching {
            next.bind().capabilityReport
            Log.i(
                TAG,
                "[ROOT_PREWARM] event=ready duration_ms=" +
                    (android.os.SystemClock.elapsedRealtime() - startedAt)
            )
            Unit
        }.onFailure { error ->
            Log.w(TAG, "[ROOT_PREWARM] event=failed", error)
            if (connection === next) {
                next.unbind()
                connection = null
            }
        }
    }

    fun acquire(onDisconnected: () -> Unit): RootServiceConnection? {
        val next = connection ?: return null
        connection = null
        Log.i(TAG, "[ROOT_PREWARM] event=acquired ready=${next.service != null}")
        return next.transferDisconnectedCallback(onDisconnected)
    }

    private const val TAG = "RootServicePrewarmer"
}
