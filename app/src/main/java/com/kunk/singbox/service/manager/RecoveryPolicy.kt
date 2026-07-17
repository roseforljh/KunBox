package com.kunk.singbox.service.manager

import com.kunk.singbox.ipc.VpnStateStore

/**
 * VPN 恢复判定入口。
 *
 * 恢复意图 = !manuallyStopped && mode != NONE（仅 sticky 使用）。
 */
object RecoveryPolicy {

    fun hasRecoverableIntent(
        manuallyStopped: Boolean,
        mode: VpnStateStore.CoreMode
    ): Boolean {
        return !manuallyStopped && mode != VpnStateStore.CoreMode.NONE
    }

    /**
     * Sticky 恢复只适用于 VPN(tun) 模式。
     * ProxyOnlyService 返回 START_NOT_STICKY，收不到 null intent。
     */
    fun shouldRecoverFromStickyRestart(
        manuallyStopped: Boolean,
        mode: VpnStateStore.CoreMode,
        runningConfigUsable: Boolean
    ): Boolean {
        return !manuallyStopped &&
            mode == VpnStateStore.CoreMode.VPN &&
            runningConfigUsable
    }

    /** 核心已在跑/正在起时，恢复 START 必须幂等，禁止 clean restart。 */
    fun shouldIgnoreRecoveryStart(isRunning: Boolean, isStarting: Boolean): Boolean {
        return isRunning || isStarting
    }

    /**
     * 恢复启动失败时不得清 mode：意图留给下一次 sticky。
     * 用户主动 START 失败仍可清 mode。
     */
    fun shouldPreserveModeOnStartFailure(isRecoveryStart: Boolean): Boolean {
        return isRecoveryStart
    }
}
