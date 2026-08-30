package com.kunk.singbox.service.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.kunk.singbox.aidl.IRootSingBoxService
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

    suspend fun bind(): IRootSingBoxService = withContext(Dispatchers.Main) {
        service?.let { return@withContext it }
        if (!bound) {
            pending = CompletableDeferred()
            RootService.bind(Intent(context, KunBoxRootService::class.java), this@RootServiceConnection)
            bound = true
        }
        withTimeout(BIND_TIMEOUT_MS) { pending.await() }
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
        val rootService = IRootSingBoxService.Stub.asInterface(binder)
        service = rootService
        if (!pending.isCompleted) pending.complete(rootService)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        service = null
        bound = false
        disconnectedCallback()
    }

    override fun onBindingDied(name: ComponentName?) {
        onServiceDisconnected(name)
    }

    override fun onNullBinding(name: ComponentName?) {
        service = null
        bound = false
        if (!pending.isCompleted) {
            pending.completeExceptionally(IllegalStateException("RootService returned null binder"))
        }
        disconnectedCallback()
    }
}

object RootServicePrewarmer {
    private val mutex = Mutex()

    @Volatile
    private var connection: RootServiceConnection? = null

    suspend fun prewarm(context: Context): Result<Unit> = mutex.withLock {
        connection?.service?.let { return@withLock Result.success(Unit) }
        lateinit var next: RootServiceConnection
        next = RootServiceConnection(context.applicationContext) {
            if (connection === next) connection = null
        }
        connection = next
        runCatching {
            next.bind().capabilityReport
            Unit
        }.onFailure {
            next.unbind()
            if (connection === next) connection = null
        }
    }

    fun acquire(onDisconnected: () -> Unit): RootServiceConnection? {
        val next = connection ?: return null
        connection = null
        return next.transferDisconnectedCallback(onDisconnected)
    }
}
