@file:Suppress("InvalidPackageDeclaration")

package com.kunk.singbox.service.root

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val ROOT_STOP_CALL_TIMEOUT_MS = 1_500L
private const val ROOT_RECYCLE_GRACE_MS = 250L
private const val ROOT_RECOVERY_BIND_TIMEOUT_MS = 3_000L
private const val ROOT_RECOVERY_STOP_TIMEOUT_MS = 3_000L

internal suspend fun RootTransparentForegroundService.stopRemoteRuntime(): RootRuntimeSnapshot {
    val rootService = rootConnection.service ?: return if (
        runtimeSessionId.isBlank() && lastRootSnapshot.phase == RootRuntimePhase.STOPPED
    ) {
        RootRuntimeSnapshot(phase = RootRuntimePhase.STOPPED)
    } else {
        failedRootStop("Root service disconnected before cleanup could be verified")
    }
    return withContext(NonCancellable) {
        val sessionId = runtimeSessionId
        sessionId.takeIf(String::isNotBlank)?.let { runCatching { rootService.requestStop(it) } }
        val graceful = withContext(Dispatchers.IO) {
            runRootStopCall(ROOT_STOP_CALL_TIMEOUT_MS) {
                RootRuntimeSnapshot.fromBundle(rootService.stop(sessionId))
            }
        }
        graceful?.getOrNull()?.takeIf(::rootCleanupConfirmed)?.let { return@withContext it }

        val gracefulError = graceful?.exceptionOrNull()?.message ?: "timeout"
        Log.e(RootTransparentForegroundService.TAG, "[ROOT_STOP] event=graceful_stop_failed reason=$gracefulError")
        rootConnection.stopRootService()
        delay(ROOT_RECYCLE_GRACE_MS)

        val recoveryService = withTimeoutOrNull(ROOT_RECOVERY_BIND_TIMEOUT_MS) {
            runCatching { rootConnection.bind() }.getOrNull()
        }
        val recovered = recoveryService?.let { service ->
            withContext(Dispatchers.IO) {
                runRootStopCall(ROOT_RECOVERY_STOP_TIMEOUT_MS) {
                    RootRuntimeSnapshot.fromBundle(service.stop(""))
                }
            }
        }
        recovered?.getOrNull()?.takeIf(::rootCleanupConfirmed)?.let { return@withContext it }

        val recoveryError = recovered?.exceptionOrNull()?.message
            ?: if (recoveryService == null) "Root recovery service bind timed out"
            else "Root recovery cleanup timed out"
        Log.e(RootTransparentForegroundService.TAG, "[ROOT_STOP] event=recovery_failed reason=$recoveryError")
        rootConnection.stopRootService()
        failedRootStop(recoveryError)
    }
}

private fun RootTransparentForegroundService.failedRootStop(error: String): RootRuntimeSnapshot = RootRuntimeSnapshot(
    phase = RootRuntimePhase.FAILED_VERIFICATION,
    runtimeSessionId = runtimeSessionId,
    rulesInstalled = lastRootSnapshot.rulesInstalled,
    error = error
)

internal fun rootCleanupConfirmed(snapshot: RootRuntimeSnapshot): Boolean =
    snapshot.phase == RootRuntimePhase.STOPPED && !snapshot.rulesInstalled

internal fun <T> runRootStopCall(timeoutMs: Long, block: () -> T): Result<T>? {
    require(timeoutMs > 0L)
    val task = FutureTask { runCatching(block) }
    Thread(task, "kunbox-root-stop-call").apply {
        isDaemon = true
        start()
    }
    return try {
        task.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        task.cancel(true)
        null
    } catch (error: InterruptedException) {
        task.cancel(true)
        Thread.currentThread().interrupt()
        Result.failure(error)
    }
}
