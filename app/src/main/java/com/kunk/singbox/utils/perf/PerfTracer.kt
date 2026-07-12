package com.kunk.singbox.utils.perf

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

object PerfTracer {
    private const val TAG = "PerfTracer"

    private val activeTraces = ConcurrentHashMap<String, TraceInfo>()

    data class TraceInfo(
        val startTimeMs: Long,
        val parent: String? = null
    )

    fun begin(name: String, parent: String? = null) {
        activeTraces[name] = TraceInfo(
            startTimeMs = SystemClock.elapsedRealtime(),
            parent = parent
        )
    }

    fun end(name: String): Long {
        val trace = activeTraces.remove(name) ?: return -1
        val durationMs = SystemClock.elapsedRealtime() - trace.startTimeMs

        val parentInfo = trace.parent?.let { " (parent: $it)" } ?: ""
        Log.d(TAG, "[$name] completed in ${durationMs}ms$parentInfo")

        return durationMs
    }

    object Phases {
        const val VPN_STARTUP = "vpn_startup"
        const val PARALLEL_INIT = "parallel_init"
        const val NETWORK_WAIT = "network_wait"
        const val RULESET_CHECK = "ruleset_check"
        const val SETTINGS_LOAD = "settings_load"
        const val CONFIG_LOAD = "config_load"
        const val LIBBOX_START = "libbox_start"
        const val TUN_CREATE = "tun_create"
        const val VPN_VALIDATE = "vpn_validate"
        const val CORE_READY = "core_ready"
    }
}
