package com.kunk.singbox.utils.perf

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 */
object PerfTracer {
    private const val TAG = "PerfTracer"

    private val activeTraces = ConcurrentHashMap<String, TraceInfo>()

    private val stats = ConcurrentHashMap<String, TraceStats>()

    data class TraceInfo(
        val name: String,
        val startTimeMs: Long,
        val parent: String? = null
    )

    data class TraceStats(
        val name: String,
        var count: Int = 0,
        var totalMs: Long = 0L,
        var minMs: Long = Long.MAX_VALUE,
        var maxMs: Long = 0L
    ) {
        val avgMs: Long get() = if (count > 0) totalMs / count else 0L
    }

    /**
     *
     *
     */
    fun begin(name: String, parent: String? = null) {
        activeTraces[name] = TraceInfo(
            name = name,
            startTimeMs = SystemClock.elapsedRealtime(),
            parent = parent
        )
    }

    /**
     *
     */
    fun end(name: String): Long {
        val trace = activeTraces.remove(name) ?: return -1
        val durationMs = SystemClock.elapsedRealtime() - trace.startTimeMs

        stats.compute(name) { _, existing ->
            (existing ?: TraceStats(name)).apply {
                count++
                totalMs += durationMs
                if (durationMs < minMs) minMs = durationMs
                if (durationMs > maxMs) maxMs = durationMs
            }
        }

        val parentInfo = trace.parent?.let { " (parent: $it)" } ?: ""
        Log.d(TAG, "[$name] completed in ${durationMs}ms$parentInfo")

        return durationMs
    }

    /**
     *
     */
    inline fun <T> trace(name: String, block: () -> T): T {
        begin(name)
        return try {
            block()
        } finally {
            end(name)
        }
    }

    /**
     *
     */
    suspend inline fun <T> traceSuspend(name: String, block: () -> T): T {
        begin(name)
        return try {
            block()
        } finally {
            end(name)
        }
    }

    /**
     */
    fun getStats(name: String): TraceStats? = stats[name]

    /**
     */
    fun getAllStats(): Map<String, TraceStats> = stats.toMap()

    /**
     */
    fun logStats() {
        if (stats.isEmpty()) {
            Log.i(TAG, "No performance stats recorded")
            return
        }

        val sb = StringBuilder("\n=== Performance Stats ===\n")
        stats.values.sortedByDescending { it.avgMs }.forEach { stat ->
            sb.append("${stat.name}: avg=${stat.avgMs}ms, ")
            sb.append("min=${stat.minMs}ms, max=${stat.maxMs}ms, ")
            sb.append("count=${stat.count}\n")
        }
        sb.append("========================")
        Log.i(TAG, sb.toString())
    }

    /**
     */
    fun clearStats() {
        stats.clear()
        activeTraces.clear()
    }

    /**
     */
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
        const val DNS_PREWARM = "dns_prewarm"
    }
}
