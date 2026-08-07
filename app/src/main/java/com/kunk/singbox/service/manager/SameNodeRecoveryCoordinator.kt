package com.kunk.singbox.service.manager

internal enum class SameNodeFailureLayer {
    DNS,
    PROXY
}

internal enum class SameNodeRecoveryStage {
    CLOSE_CONNECTIONS,
    RESET_NETWORK,
    RELOAD_CORE,
    FULL_RESTART
}

internal data class SameNodeRecoveryVerification(
    val physicalNetworkHealthy: Boolean,
    val selectorMatches: Boolean,
    val dnsHealthy: Boolean,
    val proxyHealthy: Boolean,
    val probeAttempts: Int = 1,
    val probeFailures: Int = 0
) {
    fun recovered(layer: SameNodeFailureLayer): Boolean {
        if (!physicalNetworkHealthy || !selectorMatches || !proxyHealthy) return false
        return layer != SameNodeFailureLayer.DNS || dnsHealthy
    }
}

internal sealed class SameNodeRecoveryOutcome {
    data class Recovered(val stage: SameNodeRecoveryStage) : SameNodeRecoveryOutcome()
    object NoPhysicalNetwork : SameNodeRecoveryOutcome()
    object MissingSelection : SameNodeRecoveryOutcome()
    object Restarting : SameNodeRecoveryOutcome()
    object Failed : SameNodeRecoveryOutcome()
}

/** 同节点恢复固定执行连接清理、网络重置、核心重载三个层级。 */
internal class SameNodeRecoveryCoordinator(
    private val actions: Actions
) {
    interface Actions {
        fun hasPhysicalNetwork(): Boolean
        fun currentNodeTag(): String?
        suspend fun closeConnections(): Boolean
        suspend fun resetNetwork(): Boolean
        suspend fun reloadCurrentConfig(): Boolean
        fun restartCurrentConfig(): Boolean
        suspend fun verify(nodeTag: String, layer: SameNodeFailureLayer): SameNodeRecoveryVerification
        fun record(stage: SameNodeRecoveryStage, verification: SameNodeRecoveryVerification?)
    }

    @Suppress("ReturnCount")
    suspend fun recover(layer: SameNodeFailureLayer): SameNodeRecoveryOutcome {
        if (!actions.hasPhysicalNetwork()) return SameNodeRecoveryOutcome.NoPhysicalNetwork
        val nodeTag = actions.currentNodeTag()?.takeIf(String::isNotBlank)
            ?: return SameNodeRecoveryOutcome.MissingSelection

        actions.closeConnections()
        actions.record(SameNodeRecoveryStage.CLOSE_CONNECTIONS, null)
        verify(nodeTag, layer, SameNodeRecoveryStage.CLOSE_CONNECTIONS)?.let { return it }

        actions.resetNetwork()
        actions.record(SameNodeRecoveryStage.RESET_NETWORK, null)
        verify(nodeTag, layer, SameNodeRecoveryStage.RESET_NETWORK)?.let { return it }

        val reloaded = actions.reloadCurrentConfig()
        actions.record(SameNodeRecoveryStage.RELOAD_CORE, null)
        if (reloaded) {
            verify(nodeTag, layer, SameNodeRecoveryStage.RELOAD_CORE)?.let { return it }
        }

        val restarting = actions.restartCurrentConfig()
        actions.record(SameNodeRecoveryStage.FULL_RESTART, null)
        return if (restarting) SameNodeRecoveryOutcome.Restarting else SameNodeRecoveryOutcome.Failed
    }

    private suspend fun verify(
        nodeTag: String,
        layer: SameNodeFailureLayer,
        stage: SameNodeRecoveryStage
    ): SameNodeRecoveryOutcome.Recovered? {
        val verification = actions.verify(nodeTag, layer)
        actions.record(stage, verification)
        return SameNodeRecoveryOutcome.Recovered(stage).takeIf { verification.recovered(layer) }
    }
}

internal enum class SameNodeRecoveryPermit {
    ACQUIRED,
    COOLDOWN,
    BUDGET_EXHAUSTED
}

internal class SameNodeRecoveryGate(
    private val cooldownMs: Long = 60_000L,
    private val budgetWindowMs: Long = 10 * 60_000L,
    private val budgetMaxCount: Int = 3
) {
    private var lastAttemptAtMs = 0L
    private var windowStartAtMs = 0L
    private var attemptsInWindow = 0

    @Synchronized
    fun acquire(nowMs: Long): SameNodeRecoveryPermit {
        if (lastAttemptAtMs > 0L && nowMs >= lastAttemptAtMs && nowMs - lastAttemptAtMs < cooldownMs) {
            return SameNodeRecoveryPermit.COOLDOWN
        }
        if (windowStartAtMs <= 0L || nowMs < windowStartAtMs || nowMs - windowStartAtMs >= budgetWindowMs) {
            windowStartAtMs = nowMs
            attemptsInWindow = 0
        }
        if (attemptsInWindow >= budgetMaxCount) return SameNodeRecoveryPermit.BUDGET_EXHAUSTED
        attemptsInWindow += 1
        lastAttemptAtMs = nowMs
        return SameNodeRecoveryPermit.ACQUIRED
    }

    fun tryAcquire(nowMs: Long): Boolean = acquire(nowMs) == SameNodeRecoveryPermit.ACQUIRED
}
