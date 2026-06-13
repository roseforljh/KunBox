package com.kunk.singbox.service

import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.service.manager.ConnectionOwnerStatsSnapshot
import com.kunk.singbox.service.manager.ServiceStateHolder
import io.nekohasekai.libbox.*
import kotlinx.coroutines.*
import java.io.File

@Suppress("TooManyFunctions")
abstract class SingBoxServiceCompanionBase {
    internal val TAG = "SingBoxService"

    val ACTION_START = ServiceStateHolder.ACTION_START

    val ACTION_STOP = ServiceStateHolder.ACTION_STOP

    val ACTION_SWITCH_NODE = ServiceStateHolder.ACTION_SWITCH_NODE

    val ACTION_SERVICE = ServiceStateHolder.ACTION_SERVICE

    val ACTION_UPDATE_SETTING = ServiceStateHolder.ACTION_UPDATE_SETTING

    val ACTION_RESET_CONNECTIONS = ServiceStateHolder.ACTION_RESET_CONNECTIONS

    val ACTION_PREPARE_RESTART = ServiceStateHolder.ACTION_PREPARE_RESTART

    val ACTION_HOT_RELOAD = ServiceStateHolder.ACTION_HOT_RELOAD

    val ACTION_FULL_RESTART = ServiceStateHolder.ACTION_FULL_RESTART

    val ACTION_NETWORK_BUMP = "com.kunk.singbox.action.NETWORK_BUMP"

    val EXTRA_CONFIG_PATH = ServiceStateHolder.EXTRA_CONFIG_PATH

    val EXTRA_PENDING_NODE_NAME = "pending_node_name"

    val EXTRA_CONFIG_CONTENT = ServiceStateHolder.EXTRA_CONFIG_CONTENT

    val EXTRA_CLEAN_CACHE = ServiceStateHolder.EXTRA_CLEAN_CACHE

    val EXTRA_SETTING_KEY = ServiceStateHolder.EXTRA_SETTING_KEY

    val EXTRA_SETTING_VALUE_BOOL = ServiceStateHolder.EXTRA_SETTING_VALUE_BOOL

    val EXTRA_PREPARE_RESTART_REASON = ServiceStateHolder.EXTRA_PREPARE_RESTART_REASON

    internal val AUTO_FAILOVER_MEANINGFUL_TRAFFIC_BPS = 1024L

    internal val AUTO_FAILOVER_STARTUP_GRACE_MS = 30_000L

    internal val AUTO_FAILOVER_NETWORK_GRACE_MS = 4_000L

    internal val AUTO_FAILOVER_PROBE_RETRY_DELAY_MS = 2_500L

    internal val LATENCY_SKIPPED_OUTBOUND_TYPES = setOf(
        "direct",
        "block",
        "dns",
        "selector",
        "urltest",
        "url-test"
    )

    var instance: SingBoxService?
        get() = ServiceStateHolder.instance
        internal set(value) { ServiceStateHolder.instance = value }

    var isRunning: Boolean
        get() = ServiceStateHolder.isRunning
        internal set(value) { ServiceStateHolder.isRunning = value }

    val isRunningFlow get() = ServiceStateHolder.isRunningFlow

    var isStarting: Boolean
        get() = ServiceStateHolder.isStarting
        internal set(value) { ServiceStateHolder.isStarting = value }

    val isStartingFlow get() = ServiceStateHolder.isStartingFlow

    val lastErrorFlow get() = ServiceStateHolder.lastErrorFlow

    var isManuallyStopped: Boolean
        get() = ServiceStateHolder.isManuallyStopped
        internal set(value) { ServiceStateHolder.isManuallyStopped = value }

    internal var lastConfigPath: String?
        get() = ServiceStateHolder.lastConfigPath
        set(value) { ServiceStateHolder.lastConfigPath = value }

    // Virtual declarations keep split companion helpers callable across files.
    internal abstract fun setLastError(message: String?)

    abstract fun getConnectionOwnerStatsSnapshot(): ConnectionOwnerStatsSnapshot

    abstract fun resetConnectionOwnerStats()

    internal abstract fun chooseHigherPriorityRecovery(
        a: RecoveryRequest,
        b: RecoveryRequest
    ): RecoveryRequest

    internal abstract fun shouldDowngradeForceForHysteria2(
        profile: RecoveryProfile,
        reason: RecoveryReason,
        force: Boolean
    ): Boolean

    internal abstract fun shouldTriggerRouteGroupImmediateReselect(reason: RecoveryReason): Boolean

    internal abstract fun shouldConvergeConnectionsAfterImmediateRouteGroupSwitch(reason: RecoveryReason): Boolean

    internal abstract fun shouldRunRouteGroupSwitchConvergence(
        lastTriggeredAtMs: Long,
        nowAtMs: Long,
        debounceMs: Long
    ): Boolean

    internal abstract fun shouldContinueCoreStartAfterForegroundResultForTest(foregroundStarted: Boolean): Boolean

    internal abstract fun shouldContinueCoreStartAfterForegroundResult(foregroundStarted: Boolean): Boolean

    internal abstract fun shouldRecoverFromStickyRestartForTest(
        manuallyStopped: Boolean,
        mode: VpnStateStore.CoreMode,
        runningConfigUsable: Boolean
    ): Boolean

    internal abstract fun shouldRecoverFromStickyRestart(
        manuallyStopped: Boolean,
        mode: VpnStateStore.CoreMode,
        runningConfigUsable: Boolean
    ): Boolean

    internal abstract fun isRunningConfigUsable(file: File): Boolean

    internal abstract fun shouldScheduleNetworkTypeChangedFallback(
        request: RecoveryRequest,
        success: Boolean
    ): Boolean

    internal abstract fun shouldUseForegroundFastLane(request: RecoveryRequest): Boolean

    internal abstract fun shouldScheduleForegroundHardFallback(
        request: RecoveryRequest,
        mode: BoxWrapperManager.RecoveryMode,
        success: Boolean
    ): Boolean

    internal abstract fun hasStrongNetworkTypeChangedRecoverySignal(
        probeSucceeded: Boolean,
        networkRecoveryNeeded: Boolean
    ): Boolean

    internal abstract fun shouldRunNetworkTypeChangedFallback(
        lastTriggeredAtMs: Long,
        nowAtMs: Long,
        debounceMs: Long
    ): Boolean

    internal abstract fun shouldSkipNetworkTypeChangedFallbackByState(
        isRunning: Boolean,
        isStarting: Boolean,
        isStopping: Boolean,
        isManuallyStopped: Boolean
    ): Boolean

    internal abstract fun shouldAllowRecoveryExecution(
        isRunning: Boolean,
        isStarting: Boolean,
        isStopping: Boolean,
        isManuallyStopped: Boolean
    ): Boolean

    internal abstract fun buildRecoveryInvalidStateSummary(
        isRunning: Boolean,
        isStarting: Boolean,
        isStopping: Boolean,
        isManuallyStopped: Boolean
    ): String?

    internal abstract fun shouldAllowUserReturnRecovery(
        isRunning: Boolean,
        isStarting: Boolean,
        isStopping: Boolean,
        isManuallyStopped: Boolean
    ): Boolean

    internal abstract fun determineNetworkTypeChangedFallbackAction(
        mode: BoxWrapperManager.RecoveryMode
    ): NetworkTypeChangedFallbackAction

    internal abstract fun shouldCloseConnectionsDuringForegroundFastRecovery(profile: RecoveryProfile): Boolean
}
