package com.kunk.singbox.service.manager

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SameNodeRecoveryCoordinatorTest {
    @Test
    fun recoveryStopsImmediatelyWithoutPhysicalNetwork() = runBlocking {
        val actions = FakeActions(physicalNetwork = false)

        val outcome = SameNodeRecoveryCoordinator(actions).recover(SameNodeFailureLayer.PROXY)

        assertEquals(SameNodeRecoveryOutcome.NoPhysicalNetwork, outcome)
        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun recoveryUsesFixedOrderAndStopsAfterNetworkResetSucceeds() = runBlocking {
        val actions = FakeActions(
            verifications = ArrayDeque(
                listOf(
                    failedVerification(),
                    successfulVerification()
                )
            )
        )

        val outcome = SameNodeRecoveryCoordinator(actions).recover(SameNodeFailureLayer.PROXY)

        assertEquals(
            SameNodeRecoveryOutcome.Recovered(SameNodeRecoveryStage.RESET_NETWORK),
            outcome
        )
        assertEquals(
            listOf("close", "verify", "reset", "verify"),
            actions.calls
        )
    }

    @Test
    fun recoveryStopsBeforeNetworkResetWhenPhysicalNetworkDisappearsDuringVerification() = runBlocking {
        val actions = FakeActions(
            verifications = ArrayDeque(
                listOf(
                    failedVerification(physicalNetworkHealthy = false)
                )
            )
        )

        val outcome = SameNodeRecoveryCoordinator(actions).recover(SameNodeFailureLayer.PROXY)

        assertEquals(SameNodeRecoveryOutcome.NoPhysicalNetwork, outcome)
        assertEquals(listOf("close", "verify"), actions.calls)
    }

    @Test
    fun recoveryReloadsThenRequestsFullRestartWhenVerificationStillFails() = runBlocking {
        val actions = FakeActions(
            reloadResult = true,
            restartResult = true,
            verifications = ArrayDeque(
                listOf(
                    failedVerification(),
                    failedVerification(),
                    failedVerification()
                )
            )
        )

        val outcome = SameNodeRecoveryCoordinator(actions).recover(SameNodeFailureLayer.DNS)

        assertEquals(SameNodeRecoveryOutcome.Restarting, outcome)
        assertEquals(
            listOf("close", "verify", "reset", "verify", "reload", "verify", "restart"),
            actions.calls
        )
    }

    @Test
    fun recoveryGateEnforcesCooldownBudgetAndWindowReset() {
        val gate = SameNodeRecoveryGate(cooldownMs = 100L, budgetWindowMs = 1_000L, budgetMaxCount = 2)

        assertEquals(SameNodeRecoveryPermit.ACQUIRED, gate.acquire(100L))
        assertEquals(SameNodeRecoveryPermit.COOLDOWN, gate.acquire(150L))
        assertEquals(SameNodeRecoveryPermit.ACQUIRED, gate.acquire(250L))
        assertEquals(SameNodeRecoveryPermit.BUDGET_EXHAUSTED, gate.acquire(400L))
        assertEquals(SameNodeRecoveryPermit.ACQUIRED, gate.acquire(1_200L))
    }

    private class FakeActions(
        private val physicalNetwork: Boolean = true,
        private val reloadResult: Boolean = true,
        private val restartResult: Boolean = false,
        private val verifications: ArrayDeque<SameNodeRecoveryVerification> =
            ArrayDeque(listOf(successfulVerification()))
    ) : SameNodeRecoveryCoordinator.Actions {
        val calls = mutableListOf<String>()

        override fun hasPhysicalNetwork(): Boolean = physicalNetwork

        override fun currentNodeTag(): String = "node-a"

        override suspend fun closeConnections(): Boolean {
            calls += "close"
            return true
        }

        override suspend fun resetNetwork(): Boolean {
            calls += "reset"
            return true
        }

        override suspend fun reloadCurrentConfig(): Boolean {
            calls += "reload"
            return reloadResult
        }

        override fun restartCurrentConfig(): Boolean {
            calls += "restart"
            return restartResult
        }

        override suspend fun verify(
            nodeTag: String,
            layer: SameNodeFailureLayer
        ): SameNodeRecoveryVerification {
            calls += "verify"
            return verifications.removeFirst()
        }

        override fun record(stage: SameNodeRecoveryStage, verification: SameNodeRecoveryVerification?) = Unit
    }

    companion object {
        private fun successfulVerification() = SameNodeRecoveryVerification(
            physicalNetworkHealthy = true,
            selectorMatches = true,
            dnsHealthy = true,
            proxyHealthy = true
        )

        private fun failedVerification(
            physicalNetworkHealthy: Boolean = true
        ) = SameNodeRecoveryVerification(
            physicalNetworkHealthy = physicalNetworkHealthy,
            selectorMatches = true,
            dnsHealthy = false,
            proxyHealthy = false,
            probeFailures = 1
        )
    }
}
