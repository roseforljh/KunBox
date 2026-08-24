package com.kunk.singbox.service.root

import android.os.Bundle

enum class RootRuntimePhase {
    STOPPED,
    ROOT_BINDING,
    CORE_STARTING,
    RULES_STAGING,
    RUNNING,
    CLEANING,
    FAILED_UNPROTECTED,
    FAILED_RULES_PRESENT
}

data class RootRuntimeSnapshot(
    val phase: RootRuntimePhase = RootRuntimePhase.STOPPED,
    val runtimeSessionId: String = "",
    val generation: Long = 0L,
    val ruleRevision: Long = 0L,
    val rootPid: Int = 0,
    val rootFdCount: Int = 0,
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
        putLong(KEY_RULE_REVISION, ruleRevision)
        putInt(KEY_ROOT_PID, rootPid)
        putInt(KEY_ROOT_FD_COUNT, rootFdCount)
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
        private const val KEY_RULE_REVISION = "rule_revision"
        private const val KEY_ROOT_PID = "root_pid"
        private const val KEY_ROOT_FD_COUNT = "root_fd_count"
        private const val KEY_TPROXY_IPV4 = "tproxy_ipv4"
        private const val KEY_TPROXY_IPV6 = "tproxy_ipv6"
        private const val KEY_WATCHDOG_READY = "watchdog_ready"
        private const val KEY_RULES_INSTALLED = "rules_installed"
        private const val KEY_ERROR = "error"
        private const val KEY_STARTUP_TIMINGS = "startup_timings"

        fun fromBundle(bundle: Bundle?): RootRuntimeSnapshot {
            if (bundle == null) return RootRuntimeSnapshot()
            return RootRuntimeSnapshot(
                phase = runCatching {
                    RootRuntimePhase.valueOf(bundle.getString(KEY_PHASE).orEmpty())
                }.getOrDefault(RootRuntimePhase.FAILED_UNPROTECTED),
                runtimeSessionId = bundle.getString(KEY_SESSION_ID).orEmpty(),
                generation = bundle.getLong(KEY_GENERATION),
                ruleRevision = bundle.getLong(KEY_RULE_REVISION),
                rootPid = bundle.getInt(KEY_ROOT_PID),
                rootFdCount = bundle.getInt(KEY_ROOT_FD_COUNT),
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
