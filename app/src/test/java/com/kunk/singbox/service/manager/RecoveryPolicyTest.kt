package com.kunk.singbox.service.manager

import com.kunk.singbox.ipc.VpnStateStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPolicyTest {

    @Test
    fun recoverableIntentRequiresNotManuallyStoppedAndKnownMode() {
        assertTrue(RecoveryPolicy.hasRecoverableIntent(false, VpnStateStore.CoreMode.VPN))
        assertTrue(RecoveryPolicy.hasRecoverableIntent(false, VpnStateStore.CoreMode.PROXY))
        assertFalse(RecoveryPolicy.hasRecoverableIntent(true, VpnStateStore.CoreMode.VPN))
        assertFalse(RecoveryPolicy.hasRecoverableIntent(false, VpnStateStore.CoreMode.NONE))
    }

    @Test
    fun stickyRecoveryOnlyForVpnMode() {
        assertTrue(RecoveryPolicy.shouldRecoverFromStickyRestart(false, VpnStateStore.CoreMode.VPN, true))
        assertFalse(RecoveryPolicy.shouldRecoverFromStickyRestart(false, VpnStateStore.CoreMode.PROXY, true))
        assertFalse(RecoveryPolicy.shouldRecoverFromStickyRestart(true, VpnStateStore.CoreMode.VPN, true))
        assertFalse(RecoveryPolicy.shouldRecoverFromStickyRestart(false, VpnStateStore.CoreMode.NONE, true))
        assertFalse(RecoveryPolicy.shouldRecoverFromStickyRestart(false, VpnStateStore.CoreMode.VPN, false))
    }

    @Test
    fun recoveryStartIsIgnoredWhileCoreActive() {
        assertTrue(RecoveryPolicy.shouldIgnoreRecoveryStart(isRunning = true, isStarting = false))
        assertTrue(RecoveryPolicy.shouldIgnoreRecoveryStart(isRunning = false, isStarting = true))
        assertFalse(RecoveryPolicy.shouldIgnoreRecoveryStart(isRunning = false, isStarting = false))
    }

    @Test
    fun recoveryStartFailurePreservesModeIntent() {
        // 恢复路径失败：保留 mode，留给下一次触发源
        assertTrue(RecoveryPolicy.shouldPreserveModeOnStartFailure(isRecoveryStart = true))
        // 用户主动 START 失败：可清 mode
        assertFalse(RecoveryPolicy.shouldPreserveModeOnStartFailure(isRecoveryStart = false))
    }
}
