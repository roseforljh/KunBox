package com.kunk.singbox.service

import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.service.manager.ServiceStateHolder
import io.nekohasekai.libbox.*
import kotlinx.coroutines.*
import java.io.File

@Suppress("TooManyFunctions")
open class SingBoxServiceCompanionPart1 : SingBoxServiceCompanionBase() {
    internal override fun setLastError(message: String?) = ServiceStateHolder.setLastError(message)

    override fun getConnectionOwnerStatsSnapshot() = ServiceStateHolder.getConnectionOwnerStatsSnapshot()

    override fun resetConnectionOwnerStats() = ServiceStateHolder.resetConnectionOwnerStats()

    internal override fun chooseHigherPriorityRecovery(
        a: RecoveryRequest,
        b: RecoveryRequest
    ): RecoveryRequest {
        return when {
            a.force != b.force -> if (a.force) a else b
            a.reason.priority != b.reason.priority -> if (a.reason.priority >= b.reason.priority) a else b
            else -> if (a.requestedAtMs >= b.requestedAtMs) a else b
        }
    }

    internal override fun shouldDowngradeForceForHysteria2(
        profile: RecoveryProfile,
        reason: RecoveryReason,
        force: Boolean
    ): Boolean {
        return profile == RecoveryProfile.HYSTERIA2 &&
            reason == RecoveryReason.NETWORK_TYPE_CHANGED &&
            force
    }

    internal override fun shouldTriggerRouteGroupImmediateReselect(reason: RecoveryReason): Boolean {
        return reason == RecoveryReason.NETWORK_TYPE_CHANGED ||
            reason == RecoveryReason.NETWORK_VALIDATED
    }

    internal override fun shouldConvergeConnectionsAfterImmediateRouteGroupSwitch(reason: RecoveryReason): Boolean {
        return shouldTriggerRouteGroupImmediateReselect(reason)
    }

    internal override fun shouldRunRouteGroupSwitchConvergence(
        lastTriggeredAtMs: Long,
        nowAtMs: Long,
        debounceMs: Long
    ): Boolean {
        return lastTriggeredAtMs <= 0L || nowAtMs - lastTriggeredAtMs >= debounceMs
    }

    internal override fun shouldContinueCoreStartAfterForegroundResultForTest(foregroundStarted: Boolean): Boolean {
        return shouldContinueCoreStartAfterForegroundResult(foregroundStarted)
    }

    internal override fun shouldContinueCoreStartAfterForegroundResult(foregroundStarted: Boolean): Boolean {
        return foregroundStarted
    }

    internal override fun shouldRecoverFromStickyRestartForTest(
        manuallyStopped: Boolean,
        mode: VpnStateStore.CoreMode,
        runningConfigUsable: Boolean
    ): Boolean {
        return shouldRecoverFromStickyRestart(manuallyStopped, mode, runningConfigUsable)
    }

    internal override fun shouldRecoverFromStickyRestart(
        manuallyStopped: Boolean,
        mode: VpnStateStore.CoreMode,
        runningConfigUsable: Boolean
    ): Boolean {
        return !manuallyStopped &&
            mode == VpnStateStore.CoreMode.VPN &&
            runningConfigUsable
    }

    internal override fun isRunningConfigUsable(file: File): Boolean {
        return file.exists() && file.isFile && file.canRead() && file.length() > 0L
    }

    internal override fun shouldScheduleNetworkTypeChangedFallback(
        request: RecoveryRequest,
        success: Boolean
    ): Boolean {
        return request.reason == RecoveryReason.NETWORK_TYPE_CHANGED && success
    }

    internal override fun shouldUseForegroundFastLane(request: RecoveryRequest): Boolean {
        return request.reason == RecoveryReason.APP_FOREGROUND &&
            request.force &&
            request.rawReason == "app_foreground"
    }

    internal override fun shouldScheduleForegroundHardFallback(
        request: RecoveryRequest,
        mode: BoxWrapperManager.RecoveryMode,
        success: Boolean
    ): Boolean {
        return request.reason == RecoveryReason.APP_FOREGROUND &&
            mode == BoxWrapperManager.RecoveryMode.SOFT &&
            success
    }

    internal override fun hasStrongNetworkTypeChangedRecoverySignal(
        probeSucceeded: Boolean,
        networkRecoveryNeeded: Boolean
    ): Boolean {
        return probeSucceeded && !networkRecoveryNeeded
    }

    internal override fun shouldRunNetworkTypeChangedFallback(
        lastTriggeredAtMs: Long,
        nowAtMs: Long,
        debounceMs: Long
    ): Boolean {
        return lastTriggeredAtMs <= 0L || nowAtMs - lastTriggeredAtMs >= debounceMs
    }

    internal override fun shouldSkipNetworkTypeChangedFallbackByState(
        isRunning: Boolean,
        isStarting: Boolean,
        isStopping: Boolean,
        isManuallyStopped: Boolean
    ): Boolean {
        return !shouldAllowRecoveryExecution(
            isRunning = isRunning,
            isStarting = isStarting,
            isStopping = isStopping,
            isManuallyStopped = isManuallyStopped
        )
    }

    internal override fun shouldAllowRecoveryExecution(
        isRunning: Boolean,
        isStarting: Boolean,
        isStopping: Boolean,
        isManuallyStopped: Boolean
    ): Boolean {
        return isRunning && !isStarting && !isStopping && !isManuallyStopped
    }

    internal override fun buildRecoveryInvalidStateSummary(
        isRunning: Boolean,
        isStarting: Boolean,
        isStopping: Boolean,
        isManuallyStopped: Boolean
    ): String? {
        if (shouldAllowRecoveryExecution(
                isRunning = isRunning,
                isStarting = isStarting,
                isStopping = isStopping,
                isManuallyStopped = isManuallyStopped
            )
        ) {
            return null
        }
        return "running=$isRunning, starting=$isStarting, stopping=$isStopping, manuallyStopped=$isManuallyStopped"
    }

    internal override fun shouldAllowUserReturnRecovery(
        isRunning: Boolean,
        isStarting: Boolean,
        isStopping: Boolean,
        isManuallyStopped: Boolean
    ): Boolean {
        return shouldAllowRecoveryExecution(
            isRunning = isRunning,
            isStarting = isStarting,
            isStopping = isStopping,
            isManuallyStopped = isManuallyStopped
        )
    }

    internal override fun determineNetworkTypeChangedFallbackAction(
        mode: BoxWrapperManager.RecoveryMode
    ): NetworkTypeChangedFallbackAction {
        return if (mode == BoxWrapperManager.RecoveryMode.SOFT) {
            NetworkTypeChangedFallbackAction.ESCALATE_HARD
        } else {
            NetworkTypeChangedFallbackAction.RESTART_VPN
        }
    }

    internal override fun shouldCloseConnectionsDuringForegroundFastRecovery(profile: RecoveryProfile): Boolean {
        return when (profile) {
            RecoveryProfile.DEFAULT,
            RecoveryProfile.HYSTERIA2 -> false
        }
    }
}
