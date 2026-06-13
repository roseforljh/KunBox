package com.kunk.singbox.service

import org.junit.Assert.*
import org.junit.Test

class RecoveryLogicTest {

    private fun makeRequest(
        reason: RecoveryReason,
        force: Boolean = false,
        requestedAtMs: Long = 0L
    ): RecoveryRequest {
        return RecoveryRequest(
            reason = reason,
            rawReason = reason.name,
            force = force,
            requestedAtMs = requestedAtMs,
            merged = false
        )
    }

    @Test
    fun networkTypeChangedHasHighestPriority() {
        val reason = RecoveryReason.NETWORK_TYPE_CHANGED
        assertEquals(100, reason.priority)
    }

    @Test
    fun networkTypeChangedOutranksNetworkValidated() {
        val networkTypeChanged = RecoveryReason.NETWORK_TYPE_CHANGED
        val networkValidated = RecoveryReason.NETWORK_VALIDATED

        assertTrue(networkTypeChanged.priority > networkValidated.priority)
        assertEquals(100, networkTypeChanged.priority)
        assertEquals(80, networkValidated.priority)
    }

    @Test
    fun networkTypeChangedOutranksAppForeground() {
        val networkTypeChanged = RecoveryReason.NETWORK_TYPE_CHANGED
        val appForeground = RecoveryReason.APP_FOREGROUND

        assertTrue(networkTypeChanged.priority > appForeground.priority)
        assertEquals(100, networkTypeChanged.priority)
        assertEquals(50, appForeground.priority)
    }

    @Test
    fun priorityOrderingIsCorrect() {
        val priorities = listOf(
            RecoveryReason.NETWORK_TYPE_CHANGED,
            RecoveryReason.DOZE_EXIT,
            RecoveryReason.NETWORK_VALIDATED,
            RecoveryReason.VPN_HEALTH,
            RecoveryReason.APP_FOREGROUND,
            RecoveryReason.SCREEN_ON,
            RecoveryReason.UNKNOWN
        )

        val expectedOrder = listOf(100, 90, 80, 70, 50, 50, 10)

        for (i in priorities.indices) {
            assertEquals(expectedOrder[i], priorities[i].priority)
        }
    }

    @Test
    fun parseRecoveryReasonRecognizesNetworkTypeChanged() {
        assertEquals(
            RecoveryReason.NETWORK_TYPE_CHANGED,
            RecoveryReason.fromReasonString("network_type_changed")
        )
        assertEquals(
            RecoveryReason.NETWORK_TYPE_CHANGED,
            RecoveryReason.fromReasonString("NETWORK_TYPE_CHANGED")
        )
        assertEquals(
            RecoveryReason.NETWORK_TYPE_CHANGED,
            RecoveryReason.fromReasonString("typechange")
        )
    }

    @Test
    fun parseRecoveryReasonRecognizesNetworkValidated() {
        assertEquals(
            RecoveryReason.NETWORK_VALIDATED,
            RecoveryReason.fromReasonString("network_validated")
        )
        assertEquals(
            RecoveryReason.NETWORK_VALIDATED,
            RecoveryReason.fromReasonString("NETWORK_VALIDATED")
        )
    }

    @Test
    fun parseRecoveryReasonRecognizesAppForeground() {
        assertEquals(
            RecoveryReason.APP_FOREGROUND,
            RecoveryReason.fromReasonString("app_foreground")
        )
        assertEquals(
            RecoveryReason.APP_FOREGROUND,
            RecoveryReason.fromReasonString("APP_FOREGROUND")
        )
    }

    @Test
    fun triggerRouteGroupImmediateReselectForNetworkTypeChangedAndValidated() {
        assertTrue(
            SingBoxService.shouldTriggerRouteGroupImmediateReselect(
                RecoveryReason.NETWORK_TYPE_CHANGED
            )
        )
        assertTrue(
            SingBoxService.shouldTriggerRouteGroupImmediateReselect(
                RecoveryReason.NETWORK_VALIDATED
            )
        )
    }

    @Test
    fun doesNotTriggerRouteGroupImmediateReselectForOtherReasons() {
        assertFalse(
            SingBoxService.shouldTriggerRouteGroupImmediateReselect(
                RecoveryReason.APP_FOREGROUND
            )
        )
        assertFalse(
            SingBoxService.shouldTriggerRouteGroupImmediateReselect(
                RecoveryReason.VPN_HEALTH
            )
        )
    }

    @Test
    fun routeGroupImmediateSwitchConvergenceOnlyAppliesToNetworkReasons() {
        assertTrue(
            SingBoxService.shouldConvergeConnectionsAfterImmediateRouteGroupSwitch(
                RecoveryReason.NETWORK_TYPE_CHANGED
            )
        )
        assertTrue(
            SingBoxService.shouldConvergeConnectionsAfterImmediateRouteGroupSwitch(
                RecoveryReason.NETWORK_VALIDATED
            )
        )
        assertFalse(
            SingBoxService.shouldConvergeConnectionsAfterImmediateRouteGroupSwitch(
                RecoveryReason.APP_FOREGROUND
            )
        )
    }

    @Test
    fun routeGroupImmediateSwitchConvergenceHonorsDebounceWindow() {
        assertTrue(
            SingBoxService.shouldRunRouteGroupSwitchConvergence(
                lastTriggeredAtMs = 0L,
                nowAtMs = 5_000L,
                debounceMs = 2_000L
            )
        )
        assertFalse(
            SingBoxService.shouldRunRouteGroupSwitchConvergence(
                lastTriggeredAtMs = 4_000L,
                nowAtMs = 5_500L,
                debounceMs = 2_000L
            )
        )
        assertTrue(
            SingBoxService.shouldRunRouteGroupSwitchConvergence(
                lastTriggeredAtMs = 4_000L,
                nowAtMs = 6_100L,
                debounceMs = 2_000L
            )
        )
    }

    @Test
    fun foregroundFastRecoveryDoesNotCloseActiveConnections() {
        assertFalse(
            SingBoxService.shouldCloseConnectionsDuringForegroundFastRecovery(
                RecoveryProfile.DEFAULT
            )
        )
        assertFalse(
            SingBoxService.shouldCloseConnectionsDuringForegroundFastRecovery(
                RecoveryProfile.HYSTERIA2
            )
        )
    }

    @Test
    fun allRecoveryReasonValuesHavePositivePriority() {
        val values = RecoveryReason.values()
        for (reason in values) {
            assertTrue(reason.priority > 0)
        }
    }

    @Test
    fun appForegroundAndScreenOnSharePriority() {
        assertEquals(
            RecoveryReason.APP_FOREGROUND.priority,
            RecoveryReason.SCREEN_ON.priority
        )

        val values = RecoveryReason.values()
        val filtered = values.filter {
            it != RecoveryReason.APP_FOREGROUND &&
                it != RecoveryReason.SCREEN_ON
        }
        val priorities = filtered.map { it.priority }
        assertEquals(priorities.size, priorities.distinct().size)
    }

    @Test
    fun chooseHigherPriorityRecoveryForceWinsOverPriority() {
        val forceLower = makeRequest(
            RecoveryReason.NETWORK_VALIDATED,
            force = true,
            requestedAtMs = 100L
        )
        val noForceHigher = makeRequest(
            RecoveryReason.NETWORK_TYPE_CHANGED,
            force = false,
            requestedAtMs = 50L
        )

        val result = SingBoxService.chooseHigherPriorityRecovery(forceLower, noForceHigher)
        assertEquals(forceLower, result)
    }

    @Test
    fun chooseHigherPriorityRecoveryNetworkTypeChangedWinsOverNetworkValidated() {
        val networkTypeChanged = makeRequest(
            RecoveryReason.NETWORK_TYPE_CHANGED,
            force = false,
            requestedAtMs = 50L
        )
        val networkValidated = makeRequest(
            RecoveryReason.NETWORK_VALIDATED,
            force = false,
            requestedAtMs = 100L
        )

        val result = SingBoxService.chooseHigherPriorityRecovery(networkValidated, networkTypeChanged)
        assertEquals(networkTypeChanged, result)

        val result2 = SingBoxService.chooseHigherPriorityRecovery(networkTypeChanged, networkValidated)
        assertEquals(networkTypeChanged, result2)
    }

    @Test
    fun chooseHigherPriorityRecoveryNetworkTypeChangedWinsOverAppForeground() {
        val networkTypeChanged = makeRequest(
            RecoveryReason.NETWORK_TYPE_CHANGED,
            force = false,
            requestedAtMs = 50L
        )
        val appForeground = makeRequest(
            RecoveryReason.APP_FOREGROUND,
            force = false,
            requestedAtMs = 100L
        )

        val result = SingBoxService.chooseHigherPriorityRecovery(appForeground, networkTypeChanged)
        assertEquals(networkTypeChanged, result)

        val result2 = SingBoxService.chooseHigherPriorityRecovery(networkTypeChanged, appForeground)
        assertEquals(networkTypeChanged, result2)
    }

    @Test
    fun chooseHigherPriorityRecoveryNetworkValidatedWinsOverAppForeground() {
        val networkValidated = makeRequest(
            RecoveryReason.NETWORK_VALIDATED,
            force = false,
            requestedAtMs = 50L
        )
        val appForeground = makeRequest(
            RecoveryReason.APP_FOREGROUND,
            force = false,
            requestedAtMs = 100L
        )

        val result = SingBoxService.chooseHigherPriorityRecovery(networkValidated, appForeground)
        assertEquals(networkValidated, result)
    }

    @Test
    fun chooseHigherPriorityRecoveryByTimestampWhenSamePriority() {
        val earlier = makeRequest(
            RecoveryReason.APP_FOREGROUND,
            force = false,
            requestedAtMs = 100L
        )
        val later = makeRequest(
            RecoveryReason.APP_FOREGROUND,
            force = false,
            requestedAtMs = 200L
        )

        val result = SingBoxService.chooseHigherPriorityRecovery(earlier, later)
        assertEquals(later, result)

        val result2 = SingBoxService.chooseHigherPriorityRecovery(later, earlier)
        assertEquals(later, result2)
    }

    @Test
    fun hysteria2ForceDowngradeOnlyWhenNetworkTypeChanged() {
        assertTrue(
            SingBoxService.shouldDowngradeForceForHysteria2(
                RecoveryProfile.HYSTERIA2,
                RecoveryReason.NETWORK_TYPE_CHANGED,
                force = true
            )
        )

        assertFalse(
            SingBoxService.shouldDowngradeForceForHysteria2(
                RecoveryProfile.HYSTERIA2,
                RecoveryReason.NETWORK_VALIDATED,
                force = true
            )
        )

        assertFalse(
            SingBoxService.shouldDowngradeForceForHysteria2(
                RecoveryProfile.HYSTERIA2,
                RecoveryReason.NETWORK_TYPE_CHANGED,
                force = false
            )
        )

        assertFalse(
            SingBoxService.shouldDowngradeForceForHysteria2(
                RecoveryProfile.DEFAULT,
                RecoveryReason.NETWORK_TYPE_CHANGED,
                force = true
            )
        )
    }

    @Test
    fun hysteria2ForceDowngradeDoesNotEraseNetworkTypeChangedIdentity() {
        val profile = RecoveryProfile.HYSTERIA2
        val reason = RecoveryReason.NETWORK_TYPE_CHANGED

        val shouldDowngrade = SingBoxService.shouldDowngradeForceForHysteria2(profile, reason, force = true)
        assertTrue(shouldDowngrade)

        val adjustedForce = if (shouldDowngrade) false else true
        assertFalse(adjustedForce)
        assertEquals(RecoveryReason.NETWORK_TYPE_CHANGED, reason)
    }

    @Test
    fun networkTypeChangedSchedulesFallbackOnlyAfterSuccessfulExecution() {
        val request = makeRequest(RecoveryReason.NETWORK_TYPE_CHANGED)
        val foregroundRequest = makeRequest(RecoveryReason.APP_FOREGROUND)

        assertTrue(SingBoxService.shouldScheduleNetworkTypeChangedFallback(request, success = true))
        assertFalse(SingBoxService.shouldScheduleNetworkTypeChangedFallback(request, success = false))
        assertFalse(SingBoxService.shouldScheduleNetworkTypeChangedFallback(foregroundRequest, success = true))
    }

    @Test
    fun foregroundFastLaneOnlyAppliesToRealForegroundReturn() {
        val foregroundRequest = RecoveryRequest(
            reason = RecoveryReason.APP_FOREGROUND,
            rawReason = "app_foreground",
            force = true,
            requestedAtMs = 100L,
            merged = false
        )
        val hardFallbackRequest = foregroundRequest.copy(rawReason = "app_foreground_hard_fallback")

        assertTrue(SingBoxService.shouldUseForegroundFastLane(foregroundRequest))
        assertFalse(SingBoxService.shouldUseForegroundFastLane(hardFallbackRequest))
    }

    @Test
    fun foregroundHardFallbackRunsAfterForcedFastRecovery() {
        val request = makeRequest(RecoveryReason.APP_FOREGROUND, force = true)

        assertTrue(
            SingBoxService.shouldScheduleForegroundHardFallback(
                request = request,
                mode = com.kunk.singbox.core.BoxWrapperManager.RecoveryMode.SOFT,
                success = true
            )
        )
        assertFalse(
            SingBoxService.shouldScheduleForegroundHardFallback(
                request = request,
                mode = com.kunk.singbox.core.BoxWrapperManager.RecoveryMode.HARD,
                success = true
            )
        )
        assertFalse(
            SingBoxService.shouldScheduleForegroundHardFallback(
                request = request,
                mode = com.kunk.singbox.core.BoxWrapperManager.RecoveryMode.SOFT,
                success = false
            )
        )
    }

    @Test
    fun networkTypeChangedStrongSignalRequiresProbeAndNoPendingKernelRecovery() {
        assertTrue(
            SingBoxService.hasStrongNetworkTypeChangedRecoverySignal(
                probeSucceeded = true,
                networkRecoveryNeeded = false
            )
        )
        assertFalse(
            SingBoxService.hasStrongNetworkTypeChangedRecoverySignal(
                probeSucceeded = false,
                networkRecoveryNeeded = false
            )
        )
        assertFalse(
            SingBoxService.hasStrongNetworkTypeChangedRecoverySignal(
                probeSucceeded = true,
                networkRecoveryNeeded = true
            )
        )
    }

    @Test
    fun networkTypeChangedFallbackSkipsWhenServiceStateIsNotRunnable() {
        assertTrue(
            SingBoxService.shouldSkipNetworkTypeChangedFallbackByState(
                isRunning = false,
                isStarting = false,
                isStopping = false,
                isManuallyStopped = false
            )
        )
        assertTrue(
            SingBoxService.shouldSkipNetworkTypeChangedFallbackByState(
                isRunning = true,
                isStarting = true,
                isStopping = false,
                isManuallyStopped = false
            )
        )
        assertTrue(
            SingBoxService.shouldSkipNetworkTypeChangedFallbackByState(
                isRunning = true,
                isStarting = false,
                isStopping = true,
                isManuallyStopped = false
            )
        )
        assertFalse(
            SingBoxService.shouldSkipNetworkTypeChangedFallbackByState(
                isRunning = true,
                isStarting = false,
                isStopping = false,
                isManuallyStopped = false
            )
        )
    }

    @Test
    fun userReturnRecoveryRequiresRunnableState() {
        assertTrue(
            SingBoxService.shouldAllowUserReturnRecovery(
                isRunning = true,
                isStarting = false,
                isStopping = false,
                isManuallyStopped = false
            )
        )

        assertFalse(
            SingBoxService.shouldAllowUserReturnRecovery(
                isRunning = true,
                isStarting = false,
                isStopping = true,
                isManuallyStopped = false
            )
        )

        assertFalse(
            SingBoxService.shouldAllowUserReturnRecovery(
                isRunning = true,
                isStarting = false,
                isStopping = false,
                isManuallyStopped = true
            )
        )
    }

    @Test
    fun recoveryExecutionRequiresRunnableState() {
        assertTrue(
            SingBoxService.shouldAllowRecoveryExecution(
                isRunning = true,
                isStarting = false,
                isStopping = false,
                isManuallyStopped = false
            )
        )
        assertFalse(
            SingBoxService.shouldAllowRecoveryExecution(
                isRunning = false,
                isStarting = false,
                isStopping = false,
                isManuallyStopped = false
            )
        )
        assertFalse(
            SingBoxService.shouldAllowRecoveryExecution(
                isRunning = true,
                isStarting = false,
                isStopping = true,
                isManuallyStopped = false
            )
        )
        assertFalse(
            SingBoxService.shouldAllowRecoveryExecution(
                isRunning = true,
                isStarting = false,
                isStopping = false,
                isManuallyStopped = true
            )
        )
    }

    @Test
    fun recoveryInvalidStateSummaryIncludesTerminalStopFlags() {
        assertEquals(
            "running=true, starting=false, stopping=true, manuallyStopped=true",
            SingBoxService.buildRecoveryInvalidStateSummary(
                isRunning = true,
                isStarting = false,
                isStopping = true,
                isManuallyStopped = true
            )
        )
        assertNull(
            SingBoxService.buildRecoveryInvalidStateSummary(
                isRunning = true,
                isStarting = false,
                isStopping = false,
                isManuallyStopped = false
            )
        )
    }

    @Test
    fun networkTypeChangedFallbackEscalatesSoftRecoveryFirst() {
        assertEquals(
            NetworkTypeChangedFallbackAction.ESCALATE_HARD,
            SingBoxService.determineNetworkTypeChangedFallbackAction(
                com.kunk.singbox.core.BoxWrapperManager.RecoveryMode.SOFT
            )
        )
    }

    @Test
    fun networkTypeChangedFallbackRestartsAfterHardRecoveryStillLooksHalfDead() {
        assertEquals(
            NetworkTypeChangedFallbackAction.RESTART_VPN,
            SingBoxService.determineNetworkTypeChangedFallbackAction(
                com.kunk.singbox.core.BoxWrapperManager.RecoveryMode.HARD
            )
        )
    }

    @Test
    fun networkTypeChangedFallbackDebounceHonorsWindow() {
        assertTrue(
            SingBoxService.shouldRunNetworkTypeChangedFallback(
                lastTriggeredAtMs = 0L,
                nowAtMs = 5_000L,
                debounceMs = 2_000L
            )
        )
        assertFalse(
            SingBoxService.shouldRunNetworkTypeChangedFallback(
                lastTriggeredAtMs = 4_500L,
                nowAtMs = 5_500L,
                debounceMs = 2_000L
            )
        )
        assertTrue(
            SingBoxService.shouldRunNetworkTypeChangedFallback(
                lastTriggeredAtMs = 2_000L,
                nowAtMs = 4_500L,
                debounceMs = 2_000L
            )
        )
    }

    @Test
    fun coreStartContinuesOnlyAfterForegroundStartSucceeds() {
        assertTrue(SingBoxService.shouldContinueCoreStartAfterForegroundResultForTest(true))
        assertFalse(SingBoxService.shouldContinueCoreStartAfterForegroundResultForTest(false))
    }

    @Test
    fun stickyRestartRecoversOnlyVpnModeWithReadableRunningConfig() {
        assertTrue(
            SingBoxService.shouldRecoverFromStickyRestartForTest(
                manuallyStopped = false,
                mode = com.kunk.singbox.ipc.VpnStateStore.CoreMode.VPN,
                runningConfigUsable = true
            )
        )
        assertFalse(
            SingBoxService.shouldRecoverFromStickyRestartForTest(
                manuallyStopped = true,
                mode = com.kunk.singbox.ipc.VpnStateStore.CoreMode.VPN,
                runningConfigUsable = true
            )
        )
        assertFalse(
            SingBoxService.shouldRecoverFromStickyRestartForTest(
                manuallyStopped = false,
                mode = com.kunk.singbox.ipc.VpnStateStore.CoreMode.PROXY,
                runningConfigUsable = true
            )
        )
        assertFalse(
            SingBoxService.shouldRecoverFromStickyRestartForTest(
                manuallyStopped = false,
                mode = com.kunk.singbox.ipc.VpnStateStore.CoreMode.VPN,
                runningConfigUsable = false
            )
        )
    }
}
