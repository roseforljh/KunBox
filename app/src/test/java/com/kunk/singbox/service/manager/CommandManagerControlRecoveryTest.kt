package com.kunk.singbox.service.manager

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandManagerControlRecoveryTest {
    @Test
    fun logReadinessTimeoutIsRetryableAndNextAttemptCanSucceed() = runBlocking {
        val first = CommandManager.CommandLogAttemptSignals()
        val failure = runCatching { awaitCommandLogAttemptReady(first, timeoutMs = 1L) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertFalse(failure is CancellationException)

        val next = CommandManager.CommandLogAttemptSignals()
        next.ready.complete(Unit)
        awaitCommandLogAttemptReady(next)
    }

    @Test
    fun externalCancellationStillStopsLogReadinessWait() = runBlocking {
        val signals = CommandManager.CommandLogAttemptSignals()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            awaitCommandLogAttemptReady(signals)
        }
        waiting.cancel(CancellationException("service stopped"))

        assertTrue(runCatching { waiting.await() }.exceptionOrNull() is CancellationException)
        assertFalse(signals.ready.isCompleted)
    }

    @Test
    fun disconnectBeforeLogReadinessIsAnAttemptFailure() = runBlocking {
        val signals = CommandManager.CommandLogAttemptSignals()
        signals.disconnected.complete("stream closed")

        val failure = runCatching { awaitCommandLogAttemptReady(signals) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("stream closed"))
    }

    @Test
    fun fdRecoveryAllowsRemoteServerAndRetriesAfterMissingClientHandle() {
        val source = managerSource()
        val recovery = source.substringAfter("internal suspend fun reconnectControlClients(")
            .substringBefore("internal fun startCommandClients(")

        assertTrue(recovery.contains("fdProvider != null || commandServer != null"))
        assertFalse(recovery.contains("handle.server"))
        assertFalse(recovery.contains("error(\"Control runtime is not active\")"))
        assertTrue(recovery.contains("handle?.statusClient"))
        assertTrue(recovery.contains("detachCommandRuntime(preserveServer = true)"))
        assertTrue(recovery.contains("preserveServerOnFailure = true"))
        assertFalse(recovery.contains("closeService("))
    }

    @Test
    fun localDisconnectAndStaleHeartbeatBothRequestFencedClientRecovery() {
        val source = File("src/main/java/com/kunk/singbox/service/manager/runtime/CommandManagerRuntime.kt")
            .readText(Charsets.UTF_8)
        val stale = source.substringAfter("internal fun CommandManager.requireBaseCommandHeartbeats(")
            .substringBefore("internal fun CommandManager.controlChannelDiagnosticSnapshot(")
        val disconnected = source.substringAfter("internal fun CommandManager.markBaseCommandHealth(")
            .substringBefore("internal fun CommandManager.consumeCommandLogMessages(")
        val recovery = source.substringAfter("internal fun CommandManager.requestControlChannelRecovery(")
            .substringBefore("internal fun CommandManager.createLogClientHandler(")

        assertTrue(stale.contains("requestControlChannelRecovery("))
        assertTrue(disconnected.contains("requestControlChannelRecovery("))
        assertTrue(recovery.contains("callbacks?.onControlChannelRecoveryRequired(reason)"))
        assertTrue(recovery.contains("commandFdProvider != null"))
        assertTrue(recovery.contains("localControlRecoveryJob != null"))
        assertTrue(recovery.contains("currentRuntimeGeneration() != expectedGeneration"))
        assertTrue(recovery.contains("reconnectControlClients(expectedGeneration = expectedGeneration)"))
        assertFalse(recovery.contains("repeat(3)"))
    }

    @Test
    fun rootControlRecoveryKeepsRetryingOutsideLifecycleLockAndDestroyClearsFlags() {
        val runtime = File("src/main/java/com/kunk/singbox/service/root/runtime/RootTransparentForegroundRuntime.kt")
            .readText(Charsets.UTF_8)
            .substringAfter("internal fun RootTransparentForegroundService.scheduleControlChannelRecovery(")
            .substringBefore("internal fun RootTransparentForegroundService.recordSelector(")
        assertFalse(runtime.contains("repeat(3)"))
        assertFalse(runtime.contains("recovery_exhausted"))
        assertTrue(runtime.indexOf("delay(") < runtime.indexOf("lifecycleMutex.withLock"))
        val destroy = File("src/main/java/com/kunk/singbox/service/root/RootTransparentForegroundService.kt")
            .readText(Charsets.UTF_8)
            .substringAfter("override fun onDestroy()")
            .substringBefore("internal fun requestRunningRuntime(")
        assertTrue(destroy.contains("isStopping = false"))
    }

    @Test
    fun stoppingCancelsLocalRecoveryWhileFailedAttemptsPreserveServer() {
        val source = managerSource()
        val stop = source.substringAfter("fun stop(\n")
            .substringBefore("fun stopTrafficUpdatesAndWait()")
        val detach = source.substringAfter("internal fun detachCommandRuntime(")
            .substringBefore("internal suspend fun awaitCommandLogReady(")
        val recovery = source.substringAfter("internal suspend fun reconnectControlClients(")
            .substringBefore("internal fun startCommandClients(")

        assertTrue(stop.contains("detachCommandRuntime(expectedRuntimeGeneration, preserveServer = !closeServer)"))
        assertTrue(detach.contains("if (!preserveServer)"))
        assertTrue(detach.contains("localControlRecoveryJob?.cancel()"))
        assertTrue(detach.contains("commandServer = handle?.server.takeIf { preserveServer }"))
        assertTrue(recovery.contains("expectedGeneration == currentRuntimeGeneration()"))
        assertTrue(recovery.contains("stop(closeServer = false, expectedRuntimeGeneration = generation)"))
        assertTrue(recovery.contains("check(!controlRecoveryStopped)"))
    }

    @Test
    fun shutdownCapturesGenerationOnlyAfterCancelledRecoveryFinishesCleanup() = runBlocking {
        val generation = AtomicLong(7L)
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val recovery = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupEntered.complete(Unit)
                    releaseCleanup.await()
                    generation.incrementAndGet()
                }
            }
        }
        recovery.cancel()
        val stoppedGeneration = async(start = CoroutineStart.UNDISPATCHED) {
            awaitControlRecoveryStopAndSnapshot(recovery) { generation.get() }
        }

        cleanupEntered.await()
        assertFalse(stoppedGeneration.isCompleted)
        releaseCleanup.complete(Unit)
        assertEquals(8L, stoppedGeneration.await())
    }

    @Test
    fun shutdownFreezesRecoveryBeforeCoreStopAndClientCleanupIsGenerationFenced() {
        val shutdown = File("src/main/java/com/kunk/singbox/service/manager/ShutdownManager.kt")
            .readText(Charsets.UTF_8)
        assertTrue(shutdown.indexOf("prepareControlRecoveryStop()") < shutdown.indexOf("cleanupScope.launch {"))
        assertTrue(
            shutdown.indexOf("awaitControlRecoveryStopAndSnapshot(controlRecoveryJob)") <
                shutdown.indexOf("coreManager.stopCorePreservingTun(coreRuntimeGeneration)")
        )
        val source = managerSource()
        val startup = source.substringAfter("internal fun startCommandClients(")
            .substringBefore("suspend fun stopAndWaitPortRelease(")
        assertTrue(startup.contains("expectedRuntimeGeneration = generation"))
        assertFalse(startup.contains("if (stillOwnsStartup)"))
    }

    private fun managerSource(): String = File("src/main/java/com/kunk/singbox/service/manager/CommandManager.kt")
        .readText(Charsets.UTF_8)
}
