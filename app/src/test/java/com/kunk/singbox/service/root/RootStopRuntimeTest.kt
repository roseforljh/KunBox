package com.kunk.singbox.service.root

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootStopRuntimeTest {
    @Test
    fun rootStopAndUnbindWaitForMainThreadRelease() {
        val source = File("src/main/java/com/kunk/singbox/service/root/RootServiceConnection.kt")
            .readText(Charsets.UTF_8)
        val unbind = source.substringAfter("suspend fun unbind()")
            .substringBefore("private fun unbindOnMainThread()")
        val stop = source.substringAfter("suspend fun stopRootService()")
            .substringBefore("internal fun stopRootServiceOnMainThread()")
        val release = source.substringAfter("private fun unbindOnMainThread()")
            .substringBefore("suspend fun stopRootService()")

        assertTrue(unbind.contains("withContext(NonCancellable + Dispatchers.Main.immediate)"))
        assertTrue(stop.contains("withContext(NonCancellable + Dispatchers.Main.immediate)"))
        assertTrue(stop.contains("stopRootServiceOnMainThread()"))
        assertFalse(release.contains("runCatching { RootService.unbind"))
        assertTrue(release.indexOf("RootService.unbind(this)") in 0 until release.indexOf("bound = false"))
    }

    @Test
    fun serviceDestroyReleasesRootSynchronouslyBeforeCancellingItsScope() {
        val source = File("src/main/java/com/kunk/singbox/service/root/RootTransparentForegroundService.kt")
            .readText(Charsets.UTF_8)
        val destroy = source.substringAfter("override fun onDestroy()")
            .substringBefore("internal fun requestRunningRuntime(")

        assertTrue(destroy.contains("rootConnection.stopRootServiceOnMainThread()"))
        assertTrue(
            destroy.indexOf("rootConnection.stopRootServiceOnMainThread()") <
                destroy.indexOf("serviceScope.cancel()")
        )
    }

    @Test
    fun inPlaceReloadChecksStopBeforeCommittingRunningSnapshot() {
        val source = File("src/main/java/com/kunk/singbox/service/root/runtime/KunBoxRootServiceRuntime.kt")
            .readText(Charsets.UTF_8)
        val reload = source.substringAfter("inPlaceReloadAttempted = true")
            .substringBefore("inPlaceReloadAttempted = false")
        val reloadCall = reload.indexOf("reloadCommandServer(artifacts)")
        val stopCheck = reload.indexOf("throwIfStopRequested(runtimeSessionId)", reloadCall)

        assertTrue(stopCheck > reloadCall)
        assertTrue(stopCheck < reload.indexOf("activeRoutingArtifacts = artifacts"))
    }

    @Test
    fun newSessionCannotClearStopBeforeThePreviousTransactionFinishes() {
        val source = File("src/main/java/com/kunk/singbox/service/root/KunBoxRootService.kt")
            .readText(Charsets.UTF_8)
        val start = source.substringAfter("override fun start(")
            .substringBefore("override fun hotReload(")
        val reload = source.substringAfter("override fun hotReload(")
            .substringBefore("override fun requestStop(")

        assertTrue(
            start.indexOf("clearStopRequestForNewSession(") > start.indexOf("runRuntimeTransaction {")
        )
        assertFalse(reload.contains("clearStopRequestForNewSession("))
        val clear = source.substringAfter("internal fun clearStopRequestForNewSession(")
            .substringBefore("internal fun throwIfStopRequested(")
        assertTrue(clear.contains("snapshot.phase == RootRuntimePhase.STOPPED"))
    }

    @Test
    fun unconfirmedStopPreservesProcessIdentityAndReturnedFailure() {
        val source = File("src/main/java/com/kunk/singbox/service/root/runtime/RootStopRuntime.kt")
            .readText(Charsets.UTF_8)
        val failure = source.substringAfter("private fun RootTransparentForegroundService.failedRootStop(")
            .substringBefore("internal fun rootCleanupConfirmed(")
        val recovery = source.substringAfter("val recovered =")
            .substringBefore("private suspend fun RootTransparentForegroundService.runEmergencyRootCleanup(")

        assertTrue(failure.contains("lastRootSnapshot.copy("))
        assertTrue(failure.contains("runtimeSessionId.ifBlank { lastRootSnapshot.runtimeSessionId }"))
        assertTrue(recovery.contains("recovered?.getOrNull()"))
        assertTrue(recovery.contains("recoverySnapshot.error.ifBlank"))
        assertTrue(recovery.contains("return@withContext recoverySnapshot.copy("))
    }

    @Test
    fun emergencyCleanupPreservesSharedShellSuccessAndFailureMarkers() {
        listOf(0, 75).forEach { exitCode ->
            val output = runEmergencyCommand(exitCode)

            assertTrue(output, output.contains("RESULT=$exitCode"))
            assertTrue(output, output.contains("NEXT_JOB"))
        }
        val legacyOutput = runEmergencyCommand(0, isolated = false)
        assertFalse(legacyOutput, legacyOutput.contains("NEXT_JOB"))
    }

    @Test
    fun emergencyCleanupBudgetAllowsCleanupBeyondTheOldFiveSecondDeadline() {
        val output = runEmergencyCommand(0, delaySeconds = "5.2")

        assertTrue(output, output.contains("RESULT=0"))
        assertTrue(ROOT_EMERGENCY_CLEANUP_TIMEOUT_MS >= 10_000L + 15_000L + 2_250L)
    }

    @Test
    fun missingEmergencyCleanupScriptReportsTheReasonForExit75() {
        val output = runEmergencyCommand(0, installScript = false)

        assertTrue(output, output.contains("RESULT=75"))
        assertTrue(output, output.contains("cleanup_script_missing"))
        assertTrue(output, output.contains("NEXT_JOB"))
    }

    @Test
    fun cleanupEntryPointSurvivesAnotherCleanupOwnerFinishingFirst() {
        val watchdog = File("src/main/assets/root/kunbox-root-watchdog.sh").readText()
        val installer = File("src/main/java/com/kunk/singbox/service/root/RootWatchdogInstaller.kt").readText()
        val cleanup = watchdog.substringAfter("cleanup_runtime() {").substringBefore("cleanup_owned() {")
        val clear = installer.substringAfter("private fun clearRuntimeFiles(")
            .substringBefore("private data class WatchdogIdentity")

        assertFalse(cleanup.contains("rm -f \"${'$'}RUNTIME_DIR/watchdog.sh\""))
        assertTrue(cleanup.indexOf("= \"${'$'}1\" ] || return 0") in 0 until cleanup.indexOf("rm -f"))
        assertTrue(watchdog.contains("cleanup_runtime \"${'$'}EXPECTED_SESSION\""))
        assertTrue(watchdog.contains("cleanup_runtime \"${'$'}SESSION_ID\""))
        assertFalse(clear.contains("\"watchdog.sh\""))
    }

    @Test
    fun emergencyTimeoutDoesNotRebindWhileTheShellMayStillOwnCleanup() {
        val source = File("src/main/java/com/kunk/singbox/service/root/runtime/RootStopRuntime.kt").readText()
        val emergency = source.substringAfter("val emergency =").substringBefore("val recoveryService =")
        val shellCall = source.substringAfter(
            "private suspend fun RootTransparentForegroundService.runEmergencyRootCleanup("
        ).substringBefore("internal fun buildEmergencyRootCleanupCommand(")

        assertTrue(emergency.contains("return@withContext failedRootStop(emergencyError)"))
        assertTrue(emergency.contains("isBinderAlive"))
        assertTrue(shellCall.contains(".submit { next.complete(Result.success(it)) }"))
        assertTrue(shellCall.contains("emergencyCleanup?.takeIf { it.first == runtimeSessionId }"))
        assertTrue(shellCall.contains("observeEmergencyCleanup(runtimeSessionId"))
        assertFalse(shellCall.contains("runRootStopCall("))
    }

    @Test
    fun lateCleanupSuccessResumesOnlyTheSameFailedStop() = runBlocking {
        val lifecycle = RootLifecycleCoordinator()
        lifecycle.requestRunning(reload = false)
        val stop = lifecycle.requestStopped()
        lifecycle.transition(stop, RootLifecycleState.FAILED)
        val completed = CompletableDeferred<Boolean>()
        var resumed = false
        val observer = launch {
            if (completed.await()) {
                resumed = shouldResumeRootCleanup("session", "session", stop, lifecycle.snapshot())
            }
        }

        assertFalse(resumed)
        completed.complete(true)
        observer.join()
        assertTrue(resumed)
        assertFalse(shouldResumeRootCleanup("old-session", "session", stop, lifecycle.snapshot()))
        assertFalse(shouldResumeRootCleanup("session", "session", stop - 1L, lifecycle.snapshot()))
        lifecycle.requestRunning(reload = false)
        assertFalse(shouldResumeRootCleanup("session", "session", stop, lifecycle.snapshot()))
    }

    @Test
    fun synchronousAndOnewayStopShareTheSameIdempotentPreemption() {
        val source = File("src/main/java/com/kunk/singbox/service/root/KunBoxRootService.kt").readText()
        val stop = source.substringAfter("override fun stop(").substringBefore("override fun blockForUidRefresh(")
        val preemption = source.substringAfter("private fun preemptRuntimeForStop(")
            .substringBefore("private fun scheduleForcedProcessExit(")

        assertTrue(stop.contains("preemptRuntimeForStop(runtimeSessionId.orEmpty())"))
        assertFalse(stop.contains("stopRequestedSession::set"))
        assertTrue(preemption.contains("stopRequestedSession.compareAndSet(\"\", sessionId)"))
        assertTrue(preemption.contains("if (!claimed) return"))
        assertTrue(preemption.contains("current.runtimeSessionId != sessionId"))
    }

    @Test
    fun stopPreemptionCannotKillCleanupFromAnotherExecutor() {
        val root = Files.createTempDirectory("root-stop-cleanup-test").toFile()
        val ready = root.resolve("ready")
        val release = root.resolve("release")
        val result = AtomicReference<RootCommandResult>()
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        val command = if (windows) {
            listOf(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "Set-Content -LiteralPath '$ready' -Value ready; " +
                    "while (!(Test-Path -LiteralPath '$release')) { Start-Sleep -Milliseconds 25 }; exit 0"
            )
        } else {
            listOf("sh", "-c", "touch '$ready'; while [ ! -f '$release' ]; do sleep 0.025; done")
        }
        val worker = Thread {
            ProcessRootCommandExecutor.withCleanupCommands {
                result.set(ProcessRootCommandExecutor(timeoutMs = 5_000L).execute(command))
            }
        }
        try {
            worker.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!ready.isFile && System.nanoTime() < deadline) CountDownLatch(1).await(25, TimeUnit.MILLISECONDS)
            assertTrue("Cleanup command did not reach its barrier", ready.isFile)

            ProcessRootCommandExecutor().cancelActiveCommands()
            ProcessRootCommandExecutor().cancelActiveCommands()
            release.writeText("release")
            worker.join(2_000L)

            assertFalse("Cleanup command did not finish", worker.isAlive)
            assertEquals(0, result.get().exitCode)
        } finally {
            release.writeText("release")
            worker.join(6_000L)
            root.deleteRecursively()
        }
    }

    private fun runEmergencyCommand(
        exitCode: Int,
        isolated: Boolean = true,
        delaySeconds: String = "0",
        installScript: Boolean = true
    ): String {
        val root = Files.createTempDirectory("root-stop-shell-test").toFile()
        try {
            val script = root.resolve("watchdog.sh")
            if (installScript) {
                script.writeText("#!/bin/sh\nsleep $delaySeconds\nexit $exitCode\n")
                script.setExecutable(true)
            }
            val shell = listOf(
                "/bin/sh", "C:/Program Files/Git/usr/bin/sh.exe", "C:/Program Files/Git/bin/sh.exe"
            ).firstOrNull { File(it).isFile } ?: error("POSIX shell is unavailable")
            val generated = buildEmergencyRootCleanupCommand(
                "0d833321-aaf8-4b3f-b91c-295b1d8b3133",
                Int.MAX_VALUE,
                "123456"
            )
                .replace("/data/adb/kunbox/watchdog.sh", script.absolutePath.replace('\\', '/'))
                .replace(
                    "/data/adb/kunbox/cleanup-owned.sh",
                    root.resolve("missing.sh").absolutePath.replace('\\', '/')
                )
                .replace("/system/bin/sh", "'${shell.replace('\\', '/')}'")
            val command = if (isolated) generated else generated.removePrefix("( ").removeSuffix(" )")
            val job = root.resolve("job.sh")
            job.writeText("$command\nprintf 'RESULT=%s\\n' \"${'$'}?\"; echo NEXT_JOB\n")
            val process = ProcessBuilder(shell, job.absolutePath.replace('\\', '/'))
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(ROOT_EMERGENCY_CLEANUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            check(finished) { "Emergency shell command did not finish" }
            return process.inputStream.bufferedReader().use { it.readText() }
        } finally {
            root.deleteRecursively()
        }
    }
}
