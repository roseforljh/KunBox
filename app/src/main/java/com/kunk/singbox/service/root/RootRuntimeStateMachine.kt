package com.kunk.singbox.service.root

import android.os.Bundle
import com.kunk.singbox.model.isRootSha256

enum class RootRuntimePhase {
    STOPPED,
    ROOT_BINDING,
    VALIDATING_PLAN,
    UID_SNAPSHOT_1,
    FAIL_CLOSED,
    CORE_STARTING,
    CORE_VERIFYING,
    RULES_STAGING,
    UID_SNAPSHOT_2,
    RULES_ACTIVATING,
    RUNNING,
    CLEANING,
    ROLLBACK,
    FAILED_UNPROTECTED,
    FAILED_VERIFICATION,
    FAILED_RULES_PRESENT,
    FAILED_BLOCKED
}

internal enum class RootLifecycleState {
    STOPPED,
    STARTING,
    RUNNING,
    RELOADING,
    STOPPING,
    FAILED
}

internal enum class RootDesiredState {
    STOPPED,
    RUNNING
}

internal data class RootLifecycleSnapshot(
    val state: RootLifecycleState,
    val desiredState: RootDesiredState,
    val generation: Long
)

internal class RootLifecycleCoordinator {
    private var state = RootLifecycleState.STOPPED
    private var desiredState = RootDesiredState.STOPPED
    private var generation = 0L

    @Synchronized
    fun requestRunning(reload: Boolean): Long {
        desiredState = RootDesiredState.RUNNING
        generation += 1
        if (state != RootLifecycleState.STOPPING) {
            state = if (reload) RootLifecycleState.RELOADING else RootLifecycleState.STARTING
        }
        return generation
    }

    @Synchronized
    fun requestStopped(): Long {
        desiredState = RootDesiredState.STOPPED
        generation += 1
        state = RootLifecycleState.STOPPING
        return generation
    }

    @Synchronized
    fun transition(token: Long, target: RootLifecycleState): Boolean {
        if (token != generation) {
            val completesActiveStop = state == RootLifecycleState.STOPPING &&
                target in setOf(RootLifecycleState.STOPPED, RootLifecycleState.FAILED)
            if (!completesActiveStop) return false
        }
        if (desiredState == RootDesiredState.STOPPED && target in RUNNING_STATES) return false
        state = target
        return true
    }

    @Synchronized
    fun isCurrentRunningRequest(token: Long): Boolean =
        token == generation && desiredState == RootDesiredState.RUNNING

    @Synchronized
    fun snapshot(): RootLifecycleSnapshot = RootLifecycleSnapshot(state, desiredState, generation)

    companion object {
        private val RUNNING_STATES = setOf(
            RootLifecycleState.STARTING,
            RootLifecycleState.RUNNING,
            RootLifecycleState.RELOADING
        )
    }
}

data class RootRuntimeSnapshot(
    val phase: RootRuntimePhase = RootRuntimePhase.STOPPED,
    val runtimeSessionId: String = "",
    val generation: Long = 0L,
    val routingGeneration: Long = 0L,
    val ruleRevision: Long = 0L,
    val rootPid: Int = 0,
    val rootFdCount: Int = 0,
    val configFileSha256: String = "",
    val sidecarFileSha256: String = "",
    val staticPlanSha256: String = "",
    val appRoutingSha256: String = "",
    val resolvedPlanSha256: String = "",
    val resolvedUidCount: Int = 0,
    val tproxyIpv4: Boolean = false,
    val tproxyIpv6: Boolean = false,
    val watchdogReady: Boolean = false,
    val rulesInstalled: Boolean = false,
    val error: String = "",
    val startupTimings: String = ""
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_PHASE, phase.name)
        putString(KEY_SESSION_ID, runtimeSessionId)
        putLong(KEY_GENERATION, generation)
        putLong(KEY_ROUTING_GENERATION, routingGeneration)
        putLong(KEY_RULE_REVISION, ruleRevision)
        putInt(KEY_ROOT_PID, rootPid)
        putInt(KEY_ROOT_FD_COUNT, rootFdCount)
        putString(KEY_CONFIG_SHA256, configFileSha256)
        putString(KEY_SIDECAR_SHA256, sidecarFileSha256)
        putString(KEY_STATIC_PLAN_SHA256, staticPlanSha256)
        putString(KEY_APP_ROUTING_SHA256, appRoutingSha256)
        putString(KEY_RESOLVED_PLAN_SHA256, resolvedPlanSha256)
        putInt(KEY_RESOLVED_UID_COUNT, resolvedUidCount)
        putBoolean(KEY_TPROXY_IPV4, tproxyIpv4)
        putBoolean(KEY_TPROXY_IPV6, tproxyIpv6)
        putBoolean(KEY_WATCHDOG_READY, watchdogReady)
        putBoolean(KEY_RULES_INSTALLED, rulesInstalled)
        putString(KEY_ERROR, error)
        putString(KEY_STARTUP_TIMINGS, startupTimings)
    }

    companion object {
        private const val KEY_PHASE = "phase"
        private const val KEY_SESSION_ID = "runtime_session_id"
        private const val KEY_GENERATION = "generation"
        private const val KEY_ROUTING_GENERATION = "routing_generation"
        private const val KEY_RULE_REVISION = "rule_revision"
        private const val KEY_ROOT_PID = "root_pid"
        private const val KEY_ROOT_FD_COUNT = "root_fd_count"
        private const val KEY_CONFIG_SHA256 = "config_file_sha256"
        private const val KEY_SIDECAR_SHA256 = "sidecar_file_sha256"
        private const val KEY_STATIC_PLAN_SHA256 = "static_plan_sha256"
        private const val KEY_APP_ROUTING_SHA256 = "app_routing_sha256"
        private const val KEY_RESOLVED_PLAN_SHA256 = "resolved_plan_sha256"
        private const val KEY_RESOLVED_UID_COUNT = "resolved_uid_count"
        private const val KEY_TPROXY_IPV4 = "tproxy_ipv4"
        private const val KEY_TPROXY_IPV6 = "tproxy_ipv6"
        private const val KEY_WATCHDOG_READY = "watchdog_ready"
        private const val KEY_RULES_INSTALLED = "rules_installed"
        private const val KEY_ERROR = "error"
        private const val KEY_STARTUP_TIMINGS = "startup_timings"

        fun fromBundle(bundle: Bundle?): RootRuntimeSnapshot {
            if (bundle == null) return RootRuntimeSnapshot()
            val rawPhase = runCatching {
                RootRuntimePhase.valueOf(bundle.getString(KEY_PHASE).orEmpty())
            }.getOrDefault(RootRuntimePhase.FAILED_UNPROTECTED)
            return RootRuntimeSnapshot(
                phase = if (rawPhase == RootRuntimePhase.FAILED_RULES_PRESENT) {
                    RootRuntimePhase.FAILED_BLOCKED
                } else {
                    rawPhase
                },
                runtimeSessionId = bundle.getString(KEY_SESSION_ID).orEmpty(),
                generation = bundle.getLong(KEY_GENERATION),
                routingGeneration = bundle.getLong(KEY_ROUTING_GENERATION),
                ruleRevision = bundle.getLong(KEY_RULE_REVISION),
                rootPid = bundle.getInt(KEY_ROOT_PID),
                rootFdCount = bundle.getInt(KEY_ROOT_FD_COUNT),
                configFileSha256 = bundle.getString(KEY_CONFIG_SHA256).orEmpty(),
                sidecarFileSha256 = bundle.getString(KEY_SIDECAR_SHA256).orEmpty(),
                staticPlanSha256 = bundle.getString(KEY_STATIC_PLAN_SHA256).orEmpty(),
                appRoutingSha256 = bundle.getString(KEY_APP_ROUTING_SHA256).orEmpty(),
                resolvedPlanSha256 = bundle.getString(KEY_RESOLVED_PLAN_SHA256).orEmpty(),
                resolvedUidCount = bundle.getInt(KEY_RESOLVED_UID_COUNT),
                tproxyIpv4 = bundle.getBoolean(KEY_TPROXY_IPV4),
                tproxyIpv6 = bundle.getBoolean(KEY_TPROXY_IPV6),
                watchdogReady = bundle.getBoolean(KEY_WATCHDOG_READY),
                rulesInstalled = bundle.getBoolean(KEY_RULES_INSTALLED),
                error = bundle.getString(KEY_ERROR).orEmpty(),
                startupTimings = bundle.getString(KEY_STARTUP_TIMINGS).orEmpty()
            )
        }
    }
}

data class RootRuntimeExpectation(
    val runtimeSessionId: String,
    val routingGeneration: Long,
    val configFileSha256: String,
    val sidecarFileSha256: String,
    val staticPlanSha256: String,
    val appRoutingSha256: String,
    val tproxyIpv4: Boolean,
    val tproxyIpv6: Boolean
)

internal fun rootStartFailureRequiresSynchronousStop(snapshot: RootRuntimeSnapshot?): Boolean {
    val phase = snapshot?.phase ?: return true
    return phase !in setOf(
        RootRuntimePhase.FAILED_UNPROTECTED,
        RootRuntimePhase.FAILED_VERIFICATION,
        RootRuntimePhase.FAILED_RULES_PRESENT,
        RootRuntimePhase.FAILED_BLOCKED
    )
}

internal fun rootDestroyRequiresCleanup(snapshot: RootRuntimeSnapshot, activeTransactions: Int): Boolean =
    activeTransactions == 0 && snapshot.phase !in setOf(
        RootRuntimePhase.STOPPED,
        RootRuntimePhase.FAILED_UNPROTECTED,
        RootRuntimePhase.FAILED_VERIFICATION,
        RootRuntimePhase.FAILED_RULES_PRESENT,
        RootRuntimePhase.FAILED_BLOCKED
    )

internal fun rootRunningSnapshotError(
    snapshot: RootRuntimeSnapshot,
    expected: RootRuntimeExpectation
): String? = when {
    snapshot.phase != RootRuntimePhase.RUNNING -> snapshot.error.ifBlank { "Root runtime did not enter RUNNING" }
    snapshot.runtimeSessionId != expected.runtimeSessionId -> "Root runtime session mismatch"
    snapshot.routingGeneration != expected.routingGeneration -> "Root routing generation mismatch"
    snapshot.configFileSha256 != expected.configFileSha256 -> "Root config digest mismatch"
    snapshot.sidecarFileSha256 != expected.sidecarFileSha256 -> "Root sidecar digest mismatch"
    snapshot.staticPlanSha256 != expected.staticPlanSha256 -> "Root static plan digest mismatch"
    snapshot.appRoutingSha256 != expected.appRoutingSha256 -> "Root app routing digest mismatch"
    !isRootSha256(snapshot.resolvedPlanSha256) -> "Root resolved plan digest is missing"
    snapshot.resolvedUidCount <= 0 -> "Root resolved UID snapshot is empty"
    snapshot.rootPid <= 0 -> "Root runtime PID is missing"
    snapshot.ruleRevision <= 0L -> "Root rule revision is missing"
    !snapshot.watchdogReady -> "Root watchdog is not ready"
    !snapshot.rulesInstalled -> "Root rules are not installed"
    expected.tproxyIpv4 && !snapshot.tproxyIpv4 -> "Root IPv4 capability result mismatch"
    expected.tproxyIpv6 && !snapshot.tproxyIpv6 -> "Root IPv6 capability result mismatch"
    snapshot.error.isNotBlank() -> snapshot.error
    else -> null
}

internal fun shouldAcceptRootSnapshot(
    current: RootRuntimeSnapshot,
    incoming: RootRuntimeSnapshot
): Boolean {
    if (incoming.runtimeSessionId.isBlank()) return incoming.phase == RootRuntimePhase.STOPPED
    if (current.runtimeSessionId.isBlank()) return true
    if (current.runtimeSessionId != incoming.runtimeSessionId) return false
    return incoming.generation >= current.generation
}

internal fun formatRootStartupTimings(timings: Map<String, Long>): String =
    timings.entries.joinToString(",") { (phase, durationMs) -> "$phase=$durationMs" }
