package com.kunk.singbox.service

import androidx.work.ExistingPeriodicWorkPolicy
import com.kunk.singbox.ipc.VpnStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnKeepaliveWorkerTest {

    @Test
    fun keepaliveScheduleKeepsExistingWorkWithoutResettingEnqueueTime() {
        // KEEP：冷启动反复 schedule 不得把 15min 周期推后
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, VpnKeepaliveWorker.existingWorkPolicyForSchedule())
    }

    @Test
    fun runningConfigUsableRequiresReadableNonEmptyFile() {
        assertTrue(
            VpnKeepaliveWorker.isRunningConfigUsable(
                exists = true,
                isFile = true,
                canRead = true,
                length = 128L
            )
        )

        assertFalse(
            VpnKeepaliveWorker.isRunningConfigUsable(
                exists = true,
                isFile = true,
                canRead = true,
                length = 0L
            )
        )
        assertFalse(
            VpnKeepaliveWorker.isRunningConfigUsable(
                exists = true,
                isFile = false,
                canRead = true,
                length = 128L
            )
        )
        assertFalse(
            VpnKeepaliveWorker.isRunningConfigUsable(
                exists = true,
                isFile = true,
                canRead = false,
                length = 128L
            )
        )
    }

    @Test
    fun keepaliveAttemptsRecoveryOnlyWhenCoreServiceIsDeadAndConfigUsable() {
        assertTrue(
            VpnKeepaliveWorker.shouldAttemptRecovery(
                manuallyStopped = false,
                mode = VpnStateStore.CoreMode.VPN,
                coreServiceAlive = false,
                runningConfigUsable = true
            )
        )

        assertFalse(
            VpnKeepaliveWorker.shouldAttemptRecovery(
                manuallyStopped = false,
                mode = VpnStateStore.CoreMode.VPN,
                coreServiceAlive = true,
                runningConfigUsable = true
            )
        )
        assertFalse(
            VpnKeepaliveWorker.shouldAttemptRecovery(
                manuallyStopped = true,
                mode = VpnStateStore.CoreMode.VPN,
                coreServiceAlive = false,
                runningConfigUsable = true
            )
        )
        assertFalse(
            VpnKeepaliveWorker.shouldAttemptRecovery(
                manuallyStopped = false,
                mode = VpnStateStore.CoreMode.NONE,
                coreServiceAlive = false,
                runningConfigUsable = true
            )
        )
    }

    @Test
    fun keepaliveClearsStaleStateWhenConfigIsMissingForDeadProcess() {
        assertTrue(
            VpnKeepaliveWorker.shouldClearStaleRecoveryState(
                manuallyStopped = false,
                mode = VpnStateStore.CoreMode.PROXY,
                coreServiceAlive = false,
                runningConfigUsable = false
            )
        )

        assertFalse(
            VpnKeepaliveWorker.shouldClearStaleRecoveryState(
                manuallyStopped = false,
                mode = VpnStateStore.CoreMode.PROXY,
                coreServiceAlive = true,
                runningConfigUsable = false
            )
        )
        assertFalse(
            VpnKeepaliveWorker.shouldClearStaleRecoveryState(
                manuallyStopped = true,
                mode = VpnStateStore.CoreMode.PROXY,
                coreServiceAlive = false,
                runningConfigUsable = false
            )
        )
        assertFalse(
            VpnKeepaliveWorker.shouldClearStaleRecoveryState(
                manuallyStopped = false,
                mode = VpnStateStore.CoreMode.NONE,
                coreServiceAlive = false,
                runningConfigUsable = false
            )
        )
    }

    @Test
    fun keepaliveClearsStateAfterForegroundStartDeniedOrRetryBudgetExhausted() {
        assertTrue(
            VpnKeepaliveWorker.shouldClearAfterRecoveryFailure(
                runAttemptCount = 0,
                foregroundStartDenied = true
            )
        )

        assertTrue(
            VpnKeepaliveWorker.shouldClearAfterRecoveryFailure(
                runAttemptCount = 3,
                foregroundStartDenied = false
            )
        )

        assertFalse(
            VpnKeepaliveWorker.shouldClearAfterRecoveryFailure(
                runAttemptCount = 2,
                foregroundStartDenied = false
            )
        )
    }

    @Test
    fun sourceChecksCoreServiceInsteadOfSharedBackgroundProcess() {
        val source = java.io.File("src/main/java/com/kunk/singbox/service/VpnKeepaliveWorker.kt").readText()

        assertTrue(
            source.contains(
                "private fun isCoreServiceAlive(context: Context, mode: VpnStateStore.CoreMode): Boolean"
            )
        )
        assertTrue(source.contains("VpnRecoveryManager.isCoreServiceAlive"))
        val managerSource = java.io.File(
            "src/main/java/com/kunk/singbox/service/manager/VpnRecoveryManager.kt"
        ).readText()
        assertTrue(managerSource.contains("running.service.className == expectedServiceName"))
        assertFalse(source.contains("private fun isBackgroundProcessAlive"))
        assertFalse(source.contains("bgProcessName"))
    }

    @Test
    fun keepaliveScheduleRequiresConnectedNetworkAndBatteryNotLow() {
        val source = java.io.File("src/main/java/com/kunk/singbox/service/VpnKeepaliveWorker.kt")
            .readText(Charsets.UTF_8)

        assertTrue(source.contains("val constraints = Constraints.Builder()"))
        assertTrue(source.contains(".setRequiredNetworkType(NetworkType.CONNECTED)"))
        assertTrue(source.contains(".setRequiresBatteryNotLow(true)"))
        assertTrue(source.contains(".setConstraints(constraints)"))
    }
}
