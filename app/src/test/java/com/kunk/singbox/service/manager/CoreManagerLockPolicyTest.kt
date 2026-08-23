package com.kunk.singbox.service.manager

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreManagerLockPolicyTest {

    @Test
    fun powerSavingSuppressionBlocksServiceLockAcquisition() {
        assertFalse(CoreManager.shouldAcquireServiceLock(isHeld = false, locksSuppressed = true))
        assertFalse(CoreManager.shouldAcquireServiceLock(isHeld = true, locksSuppressed = false))
        assertTrue(CoreManager.shouldAcquireServiceLock(isHeld = false, locksSuppressed = false))
    }

    @Test
    fun unifiedShutdownReleasesLocksBeforePublishingStopped() {
        val source = File("src/main/java/com/kunk/singbox/service/SingBoxService.kt").readText(Charsets.UTF_8)
        val onDestroy = source
            .substringAfter("override fun onDestroy() {")
            .substringBefore("override fun onRevoke()")

        assertTrue(onDestroy.contains("stopVpn(stopService = false)"))
        assertFalse(onDestroy.contains("coreManager.releaseLocks()"))

        val coreSource = File(
            "src/main/java/com/kunk/singbox/service/manager/CoreManager.kt"
        ).readText(Charsets.UTF_8)
        val fullStopBody = coreSource
            .substringAfter("suspend fun stopFully")
            .substringBefore("suspend fun stop():")
        assertTrue(fullStopBody.contains("tunManager.cleanup()"))
        assertTrue(fullStopBody.contains("releaseLocks()"))

        val shutdownSource = File(
            "src/main/java/com/kunk/singbox/service/manager/ShutdownManager.kt"
        ).readText(Charsets.UTF_8)
        val stopVpnBody = shutdownSource
            .substringAfter("fun stopVpn(")
            .substringBefore("private suspend fun waitForSystemVpnDown")
        val coreStopIndex = stopVpnBody.indexOf("coreManager.stopFully(completeLifecycle = false)")
        val completionIndex = stopVpnBody.indexOf("callbacks.completeStop(stopService, recoveryIntentLease)")
        val stoppedIndex = stopVpnBody.indexOf("callbacks.updateServiceState(ServiceState.STOPPED)")
        assertTrue(coreStopIndex >= 0)
        assertTrue(completionIndex > coreStopIndex)
        assertTrue(stoppedIndex > completionIndex)
    }

    @Test
    fun coreLifecycleOperationsShareOneGateAndShutdownCancelsReload() {
        val coreSource = File(
            "src/main/java/com/kunk/singbox/service/manager/CoreManager.kt"
        ).readText(Charsets.UTF_8)
        val shutdownSource = File(
            "src/main/java/com/kunk/singbox/service/manager/ShutdownManager.kt"
        ).readText(Charsets.UTF_8)

        assertTrue(coreSource.contains("private val lifecycleMutex = Mutex()"))
        assertTrue(coreSource.contains("private val stopGeneration = AtomicLong(0L)"))
        assertTrue(coreSource.contains("fun beginStop(): Long"))
        assertTrue(coreSource.contains("fun captureStartToken(): Long?"))
        assertTrue(coreSource.substringAfter("suspend fun startLibbox").contains("lifecycleMutex.withLock"))
        assertTrue(coreSource.substringAfter("suspend fun stopService").contains("lifecycleMutex.withLock"))
        assertTrue(coreSource.substringAfter("suspend fun stopFully").contains("lifecycleMutex.withLock"))
        assertTrue(coreSource.substringAfter("suspend fun hotReloadConfig").contains("lifecycleMutex.withLock"))
        assertTrue(coreSource.contains("if (!isStartTokenCurrent(startToken))"))
        assertTrue(coreSource.contains("Hot reload invalidated while native reload was running"))
        assertTrue(shutdownSource.contains("callbacks.cancelHotReloadJob()"))
    }

    @Test
    fun hardStopHasAProcessWatchdogWhenCleanupHangs() {
        val shutdownSource = File(
            "src/main/java/com/kunk/singbox/service/manager/ShutdownManager.kt"
        ).readText(Charsets.UTF_8)

        assertTrue(shutdownSource.contains("STOP_WATCHDOG_TIMEOUT_MS"))
        assertTrue(shutdownSource.contains("withTimeout(STOP_WATCHDOG_TIMEOUT_MS)"))
        assertTrue(shutdownSource.contains("callbacks.forceStopProcess(\"shutdown_timeout\")"))
    }

    @Test
    fun duplicateVpnStopIsIgnoredBeforeReplacingRecoveryLease() {
        val source = File("src/main/java/com/kunk/singbox/service/SingBoxService.kt")
            .readText(Charsets.UTF_8)
        val stopBranch = source
            .substringAfter("SingBoxService.ACTION_STOP ->")
            .substringBefore("SingBoxService.ACTION_FORCE_STOP ->")

        val guardIndex = stopBranch.indexOf("shouldIgnoreDuplicateHardStop")
        val leaseIndex = stopBranch.indexOf("setNonResourceRecoveryIntent(false)")
        assertTrue(guardIndex >= 0)
        assertTrue(leaseIndex > guardIndex)
    }

    @Test
    fun lifecycleTokenRejectsStoppingAndStaleStarts() {
        assertTrue(
            CoreManager.isStartTokenCurrent(
                startToken = 7L,
                currentGeneration = 7L,
                stopping = false
            )
        )
        assertFalse(
            CoreManager.isStartTokenCurrent(
                startToken = 7L,
                currentGeneration = 8L,
                stopping = false
            )
        )
        assertFalse(
            CoreManager.isStartTokenCurrent(
                startToken = 7L,
                currentGeneration = 7L,
                stopping = true
            )
        )
    }

    @Test
    fun hardStopSuppressesQueuedRestart() {
        val hardStopLease = ServiceStateHolder.setRecoveryIntentOnFailure(false)
        val completion = ShutdownManager.resolveStopCompletion(
            initialStopService = false,
            hardStopRequested = true,
            cleanupRecoveryIntentLease = hardStopLease,
            hardStopRecoveryIntentLease = hardStopLease,
            pendingStartConfigPath = "running.json",
            pendingRecoveryIntentLease = hardStopLease
        )

        assertTrue(completion.stopService)
        assertNull(completion.restartConfigPath)

        val restartLease = ServiceStateHolder.setRecoveryIntentOnFailure(false)
        val restart = ShutdownManager.resolveStopCompletion(
            initialStopService = false,
            hardStopRequested = false,
            cleanupRecoveryIntentLease = hardStopLease,
            hardStopRecoveryIntentLease = null,
            pendingStartConfigPath = "running.json",
            pendingRecoveryIntentLease = restartLease
        )
        assertFalse(restart.stopService)
        assertEquals("running.json", restart.restartConfigPath)
        assertTrue(restart.recoveryIntentLease === restartLease)
    }

    @Test
    fun staleHardStopHandsOffToLatestQueuedStart() {
        val staleStopLease = ServiceStateHolder.setRecoveryIntentOnFailure(false)
        val latestStartLease = ServiceStateHolder.setRecoveryIntentOnFailure(false)

        val completion = ShutdownManager.resolveStopCompletion(
            initialStopService = true,
            hardStopRequested = true,
            cleanupRecoveryIntentLease = staleStopLease,
            hardStopRecoveryIntentLease = staleStopLease,
            pendingStartConfigPath = "latest.json",
            pendingRecoveryIntentLease = latestStartLease
        )

        assertFalse(completion.stopService)
        assertEquals("latest.json", completion.restartConfigPath)
        assertTrue(completion.recoveryIntentLease === latestStartLease)
    }
}
