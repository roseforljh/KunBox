package com.kunk.singbox.ipc

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
): EnsureBoundAction = when {
    connectionActive && bound && servicePresent && serviceAlive -> EnsureBoundAction.NONE
    connectionActive && bound && servicePresent && !serviceAlive -> EnsureBoundAction.REBIND
    !connectionActive -> EnsureBoundAction.CONNECT
    bindingInProgress -> EnsureBoundAction.WAIT_FOR_BIND
    !bound || !servicePresent -> EnsureBoundAction.REBIND
    else -> EnsureBoundAction.NONE
}

internal class StateGenerationGate {
    private val lock = Any()
    private var acceptedGeneration = 0L

    fun tryCommit(incomingGeneration: Long, commit: () -> Unit): Boolean = synchronized(lock) {
        if (!SingBoxRemote.shouldAcceptStateGeneration(incomingGeneration, acceptedGeneration)) {
            return@synchronized false
        }
        commit()
        if (incomingGeneration > 0L) acceptedGeneration = maxOf(acceptedGeneration, incomingGeneration)
        true
    }

    fun reset() = synchronized(lock) {
        acceptedGeneration = 0L
    }
}
