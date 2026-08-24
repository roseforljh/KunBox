package com.kunk.singbox.service.root

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

enum class RootResourceAction {
    NONE,
    WARN,
    STOP
}

internal fun rootResourceAction(fdCount: Int): RootResourceAction = when {
    fdCount >= 30_000 -> RootResourceAction.STOP
    fdCount >= 8_192 -> RootResourceAction.WARN
    else -> RootResourceAction.NONE
}

class RootResourceGuard(
    private val onSample: (Int, RootResourceAction) -> Unit
) {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var task: ScheduledFuture<*>? = null

    fun start() {
        if (task != null) return
        task = executor.scheduleAtFixedRate(
            {
                val count = File("/proc/self/fd").list()?.size ?: -1
                if (count >= 0) onSample(count, rootResourceAction(count))
            },
            0,
            1,
            TimeUnit.SECONDS
        )
    }

    fun stop() {
        task?.cancel(true)
        task = null
    }

    fun close() {
        stop()
        executor.shutdownNow()
    }
}
