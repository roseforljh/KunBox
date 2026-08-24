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

class RootServiceConnection(
    private val context: Context,
    private val onDisconnected: () -> Unit
) : ServiceConnection {
    companion object {
        private const val BIND_TIMEOUT_MS = 15_000L
    }

    @Volatile
    private var bound = false

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

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        val rootService = IRootSingBoxService.Stub.asInterface(binder)
        service = rootService
        if (!pending.isCompleted) pending.complete(rootService)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        service = null
        bound = false
        onDisconnected()
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
        onDisconnected()
    }
}
