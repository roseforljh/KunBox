package com.kunk.singbox.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class NodeSwitchGate {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T {
        return mutex.withLock { block() }
    }
}
