@file:Suppress("InvalidPackageDeclaration")

package com.kunk.singbox.service.root

import android.os.IBinder
import android.util.Log
import com.kunk.singbox.aidl.IRootSingBoxService
import com.kunk.singbox.repository.LogRepository
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val ROOT_STOP_CALL_TIMEOUT_MS = 15_000L
// Includes process exit, up to 10s waiting for watchdog cleanup, and cleanup/verification itself.
internal const val ROOT_EMERGENCY_CLEANUP_TIMEOUT_MS = 30_000L
private const val ROOT_RECOVERY_BIND_TIMEOUT_MS = 3_000L
private const val ROOT_RECOVERY_STOP_TIMEOUT_MS = 15_000L
private const val ROOT_WATCHDOG_SCRIPT = "/data/adb/kunbox/watchdog.sh"
private const val ROOT_CLEANUP_SCRIPT = "/data/adb/kunbox/cleanup-owned.sh"

@Suppress("LongMethod", "CyclomaticComplexMethod")
internal suspend fun RootTransparentForegroundService.stopRemoteRuntime(): RootRuntimeSnapshot {
    val sessionId = runtimeSessionId.ifBlank { lastRootSnapshot.runtimeSessionId }
    val rootPid = lastRootSnapshot.rootPid
    val rootStartTime = lastRootSnapshot.rootStartTime
    val rootService = rootConnection.service
    if (rootService == null && sessionId.isBlank() && lastRootSnapshot.phase == RootRuntimePhase.STOPPED) {
        return RootRuntimeSnapshot(phase = RootRuntimePhase.STOPPED)
    }
    return withContext(NonCancellable) {
        if (rootService == null) {
            Log.w(
                RootTransparentForegroundService.TAG,
                "[ROOT_STOP] event=service_unavailable_using_emergency_cleanup " +
                    "session=$sessionId rootPid=$rootPid"
            )
        }
        val graceful = gracefulRootStop(rootService, sessionId)
        val gracefulSnapshot = graceful?.getOrNull()
        gracefulSnapshot?.takeIf(::rootCleanupConfirmed)?.let { return@withContext it }
        if (gracefulSnapshot != null) lastRootSnapshot = gracefulSnapshot

        val gracefulError = gracefulSnapshot?.error?.ifBlank {
            "Root graceful stop returned ${gracefulSnapshot.phase}"
        } ?: graceful?.exceptionOrNull()?.message ?: "Root graceful stop timed out"
        Log.e(RootTransparentForegroundService.TAG, "[ROOT_STOP] event=graceful_stop_failed reason=$gracefulError")
        LogRepository.getInstance().addAlwaysLog("ERROR [ROOT_STOP] event=graceful_stop_failed reason=$gracefulError")
        rootConnection.stopRootService()
        val emergency = runEmergencyRootCleanup(sessionId, rootPid, rootStartTime)
        val emergencyResult = emergency?.getOrNull()
        if (emergencyResult?.isSuccess == true) {
            Log.i(RootTransparentForegroundService.TAG, "[ROOT_STOP] event=emergency_cleanup_verified")
            return@withContext RootRuntimeSnapshot(phase = RootRuntimePhase.STOPPED)
        }
        if (sessionId.isNotBlank() && rootPid > 1) {
            val emergencyError = emergencyResult?.let { result ->
                "Root emergency cleanup failed: exitCode=${result.code} " + result.err.joinToString(";").take(512)
            } ?: emergency?.exceptionOrNull()?.message ?: "Root emergency cleanup did not finish before deadline"
            LogRepository.getInstance().addAlwaysLog(
                "ERROR [ROOT_STOP] event=emergency_cleanup_unconfirmed reason=$emergencyError"
            )
            // A timed-out shell may still own cleanup. Do not bind another Root process to compete with it.
            return@withContext failedRootStop(emergencyError)
        }
        recoverRemoteRuntime(rootService?.asBinder())
    }
}

private suspend fun gracefulRootStop(
    rootService: IRootSingBoxService?,
    sessionId: String
): Result<RootRuntimeSnapshot>? {
    if (rootService == null) return null
    sessionId.takeIf(String::isNotBlank)?.let { runCatching { rootService.requestStop(it) } }
    return withContext(Dispatchers.IO) {
        runRootStopCall(ROOT_STOP_CALL_TIMEOUT_MS) {
            RootRuntimeSnapshot.fromBundle(rootService.stop(sessionId))
        }
    }
}

private suspend fun RootTransparentForegroundService.recoverRemoteRuntime(
    previousBinder: IBinder?
): RootRuntimeSnapshot = withContext(NonCancellable) {
    val released = withTimeoutOrNull(ROOT_RECOVERY_BIND_TIMEOUT_MS) {
        while (previousBinder?.isBinderAlive == true) delay(25L)
        true
    } == true
    if (!released) return@withContext failedRootStop("Previous Root binder did not exit before recovery")

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
    val recoverySnapshot = recovered?.getOrNull()
    recoverySnapshot?.takeIf(::rootCleanupConfirmed)?.let { return@withContext it }

    val recoveryError = recoverySnapshot?.let {
        recoverySnapshot.error.ifBlank { "Root recovery returned ${recoverySnapshot.phase}" }
    } ?: recovered?.exceptionOrNull()?.message
        ?: if (recoveryService == null) "Root recovery service bind timed out"
        else "Root recovery cleanup timed out"
    Log.e(RootTransparentForegroundService.TAG, "[ROOT_STOP] event=recovery_failed reason=$recoveryError")
    LogRepository.getInstance().addAlwaysLog("ERROR [ROOT_STOP] event=recovery_failed reason=$recoveryError")
    rootConnection.stopRootService()
    if (recoverySnapshot != null) return@withContext recoverySnapshot.copy(error = recoveryError)
    failedRootStop(recoveryError)
}

private suspend fun RootTransparentForegroundService.runEmergencyRootCleanup(
    runtimeSessionId: String,
    rootPid: Int,
    rootStartTime: String
): Result<Shell.Result>? {
    if (runtimeSessionId.isBlank() || rootPid <= 1 || rootStartTime.isBlank()) return null
    val command = runCatching {
        buildEmergencyRootCleanupCommand(runtimeSessionId, rootPid, rootStartTime)
    }.getOrNull()
        ?: return null
    return withContext(Dispatchers.IO) {
        val cached = emergencyCleanup?.takeIf { it.first == runtimeSessionId }?.second
        val completed = cached?.takeUnless {
            it.isCompleted && it.await().getOrNull()?.isSuccess != true
        } ?: CompletableDeferred<Result<Shell.Result>>().also { next ->
            emergencyCleanup = runtimeSessionId to next
            runCatching { Shell.cmd(command).submit { next.complete(Result.success(it)) } }
                .onFailure { next.complete(Result.failure(it)) }
        }
        observeEmergencyCleanup(runtimeSessionId, lifecycle.snapshot().generation, completed)
        withTimeoutOrNull(ROOT_EMERGENCY_CLEANUP_TIMEOUT_MS) { completed.await() }
    }
}

private fun RootTransparentForegroundService.observeEmergencyCleanup(
    sessionId: String,
    generation: Long,
    completed: CompletableDeferred<Result<Shell.Result>>
) {
    serviceScope.launch {
        val result = completed.await()
        cleanupMutex.withLock {
            if (emergencyCleanup?.second !== completed) return@withLock
            if (result.getOrNull()?.isSuccess != true) {
                emergencyCleanup = null
                return@withLock
            }
            val activeSession = runtimeSessionId.ifBlank { lastRootSnapshot.runtimeSessionId }
            if (shouldResumeRootCleanup(sessionId, activeSession, generation, lifecycle.snapshot())) {
                requestStopRuntime(stopSelfAfter = true, reason = "emergency_cleanup_late_verified")
            }
        }
    }
}

internal fun shouldResumeRootCleanup(
    sessionId: String,
    activeSession: String,
    generation: Long,
    current: RootLifecycleSnapshot
): Boolean {
    val sameStop = sessionId.isNotBlank() && sessionId == activeSession && generation == current.generation
    return sameStop && current.desiredState == RootDesiredState.STOPPED && current.state == RootLifecycleState.FAILED
}

internal fun buildEmergencyRootCleanupCommand(
    runtimeSessionId: String,
    rootPid: Int,
    rootStartTime: String
): String {
    require(runCatching { UUID.fromString(runtimeSessionId) }.isSuccess) { "Invalid Root runtime session ID" }
    require(rootPid > 1) { "Invalid Root process ID" }
    require(rootStartTime.isNotBlank() && rootStartTime.all(Char::isDigit)) {
        "Invalid Root process start time"
    }
    return buildString {
        // Root may be stuck in a native call. Verify PID identity, terminate it, then let watchdog own cleanup.
        append("( fail_cleanup() { printf '%s\\n' \"Root emergency cleanup: ${'$'}1\" >&2; exit 75; }; ")
        append("if [ -d /proc/").append(rootPid).append(" ]; then ")
        append("root_start=${'$'}(sed 's/.*) //' /proc/").append(rootPid)
        append("/stat 2>/dev/null | awk '{print ${'$'}20}'); ")
        append("[ \"${'$'}root_start\" = ").append(shellQuote(rootStartTime))
        append(" ] || fail_cleanup root_pid_identity_mismatch; ")
        append("kill -TERM ").append(rootPid).append(" 2>/dev/null || true; ")
        append("sleep 0.05; ")
        append("root_start=${'$'}(sed 's/.*) //' /proc/").append(rootPid)
        append("/stat 2>/dev/null | awk '{print ${'$'}20}'); ")
        append("if [ \"${'$'}root_start\" = ").append(shellQuote(rootStartTime)).append(" ] && [ -d /proc/")
        append(rootPid).append(" ]; then kill -KILL ").append(rootPid).append(" 2>/dev/null || true; fi; fi; ")
        append("kb_try=0; while [ -d /proc/").append(rootPid)
        append(" ] && [ \"${'$'}kb_try\" -lt 5 ]; do sleep 0.05; ")
        append("kb_try=${'$'}((kb_try + 1)); done; ")
        append("kb_try=0; while [ -d /proc/").append(rootPid)
        append(" ] && [ \"${'$'}kb_try\" -lt 20 ]; do sleep 0.1; ")
        append("kb_try=${'$'}((kb_try + 1)); done; ")
        append("[ ! -d /proc/").append(rootPid).append(" ] || fail_cleanup root_process_still_alive; ")
        append("if [ -f ").append(shellQuote(ROOT_WATCHDOG_SCRIPT)).append(" ]; then ")
        append("exec /system/bin/sh ").append(shellQuote(ROOT_WATCHDOG_SCRIPT))
        append(" cleanup ").append(shellQuote(runtimeSessionId)).append("; fi; ")
        append("[ -f ").append(shellQuote(ROOT_CLEANUP_SCRIPT)).append(" ] || fail_cleanup cleanup_script_missing; ")
        append("exec /system/bin/sh ").append(shellQuote(ROOT_CLEANUP_SCRIPT))
        append(" cleanup ").append(shellQuote(runtimeSessionId)).append(" )")
    }
}

private fun RootTransparentForegroundService.failedRootStop(error: String): RootRuntimeSnapshot = lastRootSnapshot.copy(
    phase = RootRuntimePhase.FAILED_VERIFICATION,
    runtimeSessionId = runtimeSessionId.ifBlank { lastRootSnapshot.runtimeSessionId },
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
