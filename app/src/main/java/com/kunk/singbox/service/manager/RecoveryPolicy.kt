package com.kunk.singbox.service.manager

import com.kunk.singbox.ipc.VpnStateStore

/**
 * VPN 恢复判定唯一入口：sticky / keepalive / 冷启动单路恢复 / autoConnect 守卫共用。
 *
 * 语义约定：
 * - 恢复意图 = !manuallyStopped && mode != NONE（用户没点断开，意图就仍是"要开"）
 * - active 只表示"此刻是否在跑"，不作为恢复开关
 */
object RecoveryPolicy {

    fun hasRecoverableIntent(
        manuallyStopped: Boolean,
        mode: VpnStateStore.CoreMode
    ): Boolean {
        return !manuallyStopped && mode != VpnStateStore.CoreMode.NONE
    }

    fun shouldAttemptRecovery(
        manuallyStopped: Boolean,
        mode: VpnStateStore.CoreMode,
        coreServiceAlive: Boolean,
        runningConfigUsable: Boolean
    ): Boolean {
        return hasRecoverableIntent(manuallyStopped, mode) &&
            !coreServiceAlive &&
            runningConfigUsable
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
     * 恢复启动失败时不得清 mode：意图留给下一次 sticky/keepalive/冷启动。
     * 用户主动 START 失败仍可清 mode。
     */
    fun shouldPreserveModeOnStartFailure(isRecoveryStart: Boolean): Boolean {
        return isRecoveryStart
    }

    /**
     * autoConnect 只允许在"无恢复意图"时点火。
     * 有恢复意图（被杀待恢复）时交给 sticky/keepalive/冷启动单路，autoConnect 不得抢跑。
     */
    @Suppress("LongParameterList")
    fun shouldAllowAutoConnectStart(
        autoConnect: Boolean,
        connectionIdle: Boolean,
        isRunning: Boolean,
        isStarting: Boolean,
        manuallyStopped: Boolean,
        hasRecoverableIntent: Boolean
    ): Boolean {
        if (!autoConnect || !connectionIdle) return false
        if (isRunning || isStarting) return false
        return !manuallyStopped && !hasRecoverableIntent
    }
}
