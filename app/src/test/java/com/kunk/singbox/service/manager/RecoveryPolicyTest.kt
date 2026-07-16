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
    fun recoveryAttemptsOnlyWhenIntentAliveCoreDeadAndConfigUsable() {
        // 用户没点断开 + 服务真死 + 配置可用 → 恢复
        assertTrue(RecoveryPolicy.shouldAttemptRecovery(false, VpnStateStore.CoreMode.VPN, false, true))
        // 服务还活着 → 不恢复
        assertFalse(RecoveryPolicy.shouldAttemptRecovery(false, VpnStateStore.CoreMode.VPN, true, true))
        // 用户手动停过 → 永不恢复
        assertFalse(RecoveryPolicy.shouldAttemptRecovery(true, VpnStateStore.CoreMode.VPN, false, true))
        // 无意图 → 不恢复
        assertFalse(RecoveryPolicy.shouldAttemptRecovery(false, VpnStateStore.CoreMode.NONE, false, true))
        // 配置不可用 → 不恢复
        assertFalse(RecoveryPolicy.shouldAttemptRecovery(false, VpnStateStore.CoreMode.VPN, false, false))
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

    @Test
    fun autoConnectStartRequiresNoRecoverableIntent() {
        // 常规自动连接：开关开、空闲、未运行、未手动停、无恢复意图 → 允许
        assertTrue(
            RecoveryPolicy.shouldAllowAutoConnectStart(
                autoConnect = true,
                connectionIdle = true,
                isRunning = false,
                isStarting = false,
                manuallyStopped = false,
                hasRecoverableIntent = false
            )
        )
        // 回归用例：有恢复意图（被杀待恢复）时，即使 IPC 冷起显示 STOPPED 也不得点火
        assertFalse(
            RecoveryPolicy.shouldAllowAutoConnectStart(
                autoConnect = true,
                connectionIdle = true,
                isRunning = false,
                isStarting = false,
                manuallyStopped = false,
                hasRecoverableIntent = true
            )
        )
        // 手动停过 → 不自动连
        assertFalse(
            RecoveryPolicy.shouldAllowAutoConnectStart(
                autoConnect = true,
                connectionIdle = true,
                isRunning = false,
                isStarting = false,
                manuallyStopped = true,
                hasRecoverableIntent = false
            )
        )
        // 已在跑 → 不自动连
        assertFalse(
            RecoveryPolicy.shouldAllowAutoConnectStart(
                autoConnect = true,
                connectionIdle = true,
                isRunning = true,
                isStarting = false,
                manuallyStopped = false,
                hasRecoverableIntent = false
            )
        )
        // 开关关闭 → 不自动连
        assertFalse(
            RecoveryPolicy.shouldAllowAutoConnectStart(
                autoConnect = false,
                connectionIdle = true,
                isRunning = false,
                isStarting = false,
                manuallyStopped = false,
                hasRecoverableIntent = false
            )
        )
    }
}
