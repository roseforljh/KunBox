package com.kunk.singbox.service

import org.junit.Assert.*
import org.junit.Test

class RecoveryLogicTest {

    private fun makeRequest(
        reason: SingBoxService.RecoveryReason,
        force: Boolean = false,
        requestedAtMs: Long = 0L
    ): SingBoxService.RecoveryRequest {
        return SingBoxService.RecoveryRequest(
            reason = reason,
            rawReason = reason.name,
            force = force,
            requestedAtMs = requestedAtMs,
            merged = false
        )
    }

    @Test
    fun networkTypeChangedHasHighestPriority() {
        val reason = SingBoxService.RecoveryReason.NETWORK_TYPE_CHANGED
        assertEquals(100, reason.priority)
    }

    @Test
    fun networkTypeChangedOutranksNetworkValidated() {
        val networkTypeChanged = SingBoxService.RecoveryReason.NETWORK_TYPE_CHANGED
        val networkValidated = SingBoxService.RecoveryReason.NETWORK_VALIDATED

        assertTrue(networkTypeChanged.priority > networkValidated.priority)
        assertEquals(100, networkTypeChanged.priority)
        assertEquals(80, networkValidated.priority)
    }

    @Test
    fun networkTypeChangedOutranksAppForeground() {
        val networkTypeChanged = SingBoxService.RecoveryReason.NETWORK_TYPE_CHANGED
        val appForeground = SingBoxService.RecoveryReason.APP_FOREGROUND

        assertTrue(networkTypeChanged.priority > appForeground.priority)
        assertEquals(100, networkTypeChanged.priority)
        assertEquals(50, appForeground.priority)
    }

    @Test
    fun priorityOrderingIsCorrect() {
        val priorities = listOf(
            SingBoxService.RecoveryReason.NETWORK_TYPE_CHANGED,
            SingBoxService.RecoveryReason.DOZE_EXIT,
            SingBoxService.RecoveryReason.NETWORK_VALIDATED,
            SingBoxService.RecoveryReason.VPN_HEALTH,
            SingBoxService.RecoveryReason.APP_FOREGROUND,
            SingBoxService.RecoveryReason.SCREEN_ON,
            SingBoxService.RecoveryReason.UNKNOWN
        )

        val expectedOrder = listOf(100, 90, 80, 70, 50, 50, 10)

        for (i in priorities.indices) {
            assertEquals(expectedOrder[i], priorities[i].priority)
        }
    }

    @Test
    fun parseRecoveryReasonRecognizesNetworkTypeChanged() {
        assertEquals(
            SingBoxService.RecoveryReason.NETWORK_TYPE_CHANGED,
            SingBoxService.RecoveryReason.fromReasonString("network_type_changed")
        )
        assertEquals(
            SingBoxService.RecoveryReason.NETWORK_TYPE_CHANGED,
            SingBoxService.RecoveryReason.fromReasonString("NETWORK_TYPE_CHANGED")
        )
        assertEquals(
            SingBoxService.RecoveryReason.NETWORK_TYPE_CHANGED,
            SingBoxService.RecoveryReason.fromReasonString("typechange")
        )
    }

    @Test
    fun parseRecoveryReasonRecognizesNetworkValidated() {
        assertEquals(
            SingBoxService.RecoveryReason.NETWORK_VALIDATED,
            SingBoxService.RecoveryReason.fromReasonString("network_validated")
        )
        assertEquals(
            SingBoxService.RecoveryReason.NETWORK_VALIDATED,
            SingBoxService.RecoveryReason.fromReasonString("NETWORK_VALIDATED")
        )
    }

    @Test
    fun parseRecoveryReasonRecognizesAppForeground() {
        assertEquals(
            SingBoxService.RecoveryReason.APP_FOREGROUND,
            SingBoxService.RecoveryReason.fromReasonString("app_foreground")
        )
        assertEquals(
            SingBoxService.RecoveryReason.APP_FOREGROUND,
            SingBoxService.RecoveryReason.fromReasonString("APP_FOREGROUND")
        )
    }

    @Test
    fun triggerRouteGroupImmediateReselectForNetworkTypeChangedAndValidated() {
        assertTrue(
            SingBoxService.shouldTriggerRouteGroupImmediateReselect(
                SingBoxService.RecoveryReason.NETWORK_TYPE_CHANGED
            )
        )
        assertTrue(
            SingBoxService.shouldTriggerRouteGroupImmediateReselect(
                SingBoxService.RecoveryReason.NETWORK_VALIDATED
            )
        )
    }

    @Test
    fun doesNotTriggerRouteGroupImmediateReselectForOtherReasons() {
        assertFalse(
            SingBoxService.shouldTriggerRouteGroupImmediateReselect(
                SingBoxService.RecoveryReason.APP_FOREGROUND
            )
        )
        assertFalse(
            SingBoxService.shouldTriggerRouteGroupImmediateReselect(
                SingBoxService.RecoveryReason.VPN_HEALTH
            )
        )
    }

    @Test
    fun routeGroupImmediateSwitchConvergenceOnlyAppliesToNetworkReasons() {
        assertTrue(
            SingBoxService.shouldConvergeConnectionsAfterImmediateRouteGroupSwitch(
                SingBoxService.RecoveryReason.NETWORK_TYPE_CHANGED
            )
        )
        assertTrue(
            SingBoxService.shouldConvergeConnectionsAfterImmediateRouteGroupSwitch(
                SingBoxService.RecoveryReason.NETWORK_VALIDATED
            )
        )
        assertFalse(
            SingBoxService.shouldConvergeConnectionsAfterImmediateRouteGroupSwitch(
                SingBoxService.RecoveryReason.APP_FOREGROUND
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
                SingBoxService.RecoveryProfile.DEFAULT
            )
        )
        assertFalse(
            SingBoxService.shouldCloseConnectionsDuringForegroundFastRecovery(
                SingBoxService.RecoveryProfile.HYSTERIA2
            )
        )
    }

    @Test
    fun allRecoveryReasonValuesHavePositivePriority() {
        val values = SingBoxService.RecoveryReason.values()
        for (reason in values) {
            assertTrue(reason.priority > 0)
        }
    }

    @Test
    fun appForegroundAndScreenOnSharePriority() {
        assertEquals(
            SingBoxService.RecoveryReason.APP_FOREGROUND.priority,
            SingBoxService.RecoveryReason.SCREEN_ON.priority
        )

        val values = SingBoxService.RecoveryReason.values()
        val filtered = values.filter {
            it != SingBoxService.RecoveryReason.APP_FOREGROUND &&
                it != SingBoxService.RecoveryReason.SCREEN_ON
        }
        val priorities = filtered.map { it.priority }
        assertEquals(priorities.size, priorities.distinct().size)
    }

    @Test
    fun chooseHigherPriorityRecoveryForceWinsOverPriority() {
        val forceLower = makeRequest(
            SingBoxService.RecoveryReason.NETWORK_VALIDATED,
            force = true,
            requestedAtMs = 100L
        )
        val noForceHigher = makeRequest(
            SingBoxService.RecoveryReason.NETWORK_TYPE_CHANGED,
            force = false,
            requestedAtMs = 50L
        )

        val result = SingBoxService.chooseHigherPriorityRecovery(forceLower, noForceHigher)
        assertEquals(forceLower, result)
    }

    @Test
    fun chooseHigherPriorityRecoveryNetworkTypeChangedWinsOverNetworkValidated() {
        val networkTypeChanged = makeRequest(
            SingBoxService.RecoveryReason.NETWORK_TYPE_CHANGED,
            force = false,
            requestedAtMs = 50L
        )
        val networkValidated = makeRequest(
            SingBoxService.RecoveryReason.NETWORK_VALIDATED,
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
            SingBoxService.RecoveryReason.NETWORK_TYPE_CHANGED,
            force = false,
            requestedAtMs = 50L
        )
        val appForeground = makeRequest(
            SingBoxService.RecoveryReason.APP_FOREGROUND,
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
            SingBoxService.RecoveryReason.NETWORK_VALIDATED,
            force = false,
            requestedAtMs = 50L
        )
        val appForeground = makeRequest(
            SingBoxService.RecoveryReason.APP_FOREGROUND,
            force = false,
            requestedAtMs = 100L
        )

        val result = SingBoxService.chooseHigherPriorityRecovery(networkValidated, appForeground)
        assertEquals(networkValidated, result)
    }

    @Test
    fun chooseHigherPriorityRecoveryByTimestampWhenSamePriority() {
        val earlier = makeRequest(
            SingBoxService.RecoveryReason.APP_FOREGROUND,
            force = false,
            requestedAtMs = 100L
        )
        val later = makeRequest(
            SingBoxService.RecoveryReason.APP_FOREGROUND,
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
                SingBoxService.RecoveryProfile.HYSTERIA2,
                SingBoxService.RecoveryReason.NETWORK_TYPE_CHANGED,
                force = true
            )
        )

        assertFalse(
            SingBoxService.shouldDowngradeForceForHysteria2(
                SingBoxService.RecoveryProfile.HYSTERIA2,
                SingBoxService.RecoveryReason.NETWORK_VALIDATED,
                force = true
            )
        )

        assertFalse(
            SingBoxService.shouldDowngradeForceForHysteria2(
                SingBoxService.RecoveryProfile.HYSTERIA2,
                SingBoxService.RecoveryReason.NETWORK_TYPE_CHANGED,
                force = false
            )
        )

        assertFalse(
            SingBoxService.shouldDowngradeForceForHysteria2(
                SingBoxService.RecoveryProfile.DEFAULT,
                SingBoxService.RecoveryReason.NETWORK_TYPE_CHANGED,
                force = true
            )
        )
    }

    @Test
    fun hysteria2ForceDowngradeDoesNotEraseNetworkTypeChangedIdentity() {
        val profile = SingBoxService.RecoveryProfile.HYSTERIA2
        val reason = SingBoxService.RecoveryReason.NETWORK_TYPE_CHANGED

        val shouldDowngrade = SingBoxService.shouldDowngradeForceForHysteria2(profile, reason, force = true)
        assertTrue(shouldDowngrade)

        val adjustedForce = if (shouldDowngrade) false else true
        assertFalse(adjustedForce)
        assertEquals(SingBoxService.RecoveryReason.NETWORK_TYPE_CHANGED, reason)
    }

    @Test
    fun networkTypeChangedSchedulesFallbackOnlyAfterSuccessfulExecution() {
        val request = makeRequest(SingBoxService.RecoveryReason.NETWORK_TYPE_CHANGED)
        val foregroundRequest = makeRequest(SingBoxService.RecoveryReason.APP_FOREGROUND)

        assertTrue(SingBoxService.shouldScheduleNetworkTypeChangedFallback(request, success = true))
        assertFalse(SingBoxService.shouldScheduleNetworkTypeChangedFallback(request, success = false))
        assertFalse(SingBoxService.shouldScheduleNetworkTypeChangedFallback(foregroundRequest, success = true))
    }

    @Test
    fun foregroundFastLaneOnlyAppliesToRealForegroundReturn() {
        val foregroundRequest = SingBoxService.RecoveryRequest(
            reason = SingBoxService.RecoveryReason.APP_FOREGROUND,
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
        val request = makeRequest(SingBoxService.RecoveryReason.APP_FOREGROUND, force = true)

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
            SingBoxService.NetworkTypeChangedFallbackAction.ESCALATE_HARD,
            SingBoxService.determineNetworkTypeChangedFallbackAction(
                com.kunk.singbox.core.BoxWrapperManager.RecoveryMode.SOFT
            )
        )
    }

    @Test
    fun networkTypeChangedFallbackRestartsAfterHardRecoveryStillLooksHalfDead() {
        assertEquals(
            SingBoxService.NetworkTypeChangedFallbackAction.RESTART_VPN,
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
