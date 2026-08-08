package com.kunk.singbox.service.manager

import android.net.Network
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToLong

internal data class TimedProbeResult(
    val succeeded: Boolean,
    val latencyMs: Long? = null
)

internal data class ProbeStatistics(
    val attempts: Int,
    val successes: Int,
    val failures: Int,
    val lossPercent: Int,
    val averageLatencyMs: Long?,
    val jitterMs: Long?
) {
    val hasMajoritySuccess: Boolean
        get() = attempts > 0 && successes >= attempts / 2 + 1
}

internal fun summarizeTimedProbeResults(results: List<TimedProbeResult>): ProbeStatistics {
    val latencies = results.mapNotNull { result -> result.latencyMs.takeIf { result.succeeded } }
    val jitter = latencies.zipWithNext { previous, current -> abs(current - previous) }
    val failures = results.count { !it.succeeded }
    return ProbeStatistics(
        attempts = results.size,
        successes = results.size - failures,
        failures = failures,
        lossPercent = if (results.isEmpty()) 0 else failures * 100 / results.size,
        averageLatencyMs = latencies.takeIf(List<Long>::isNotEmpty)?.average()?.roundToLong(),
        jitterMs = jitter.takeIf(List<Long>::isNotEmpty)?.average()?.roundToLong()
    )
}

internal suspend fun probePhysicalDns(
    network: Network?,
    host: String?,
    timeoutMs: Long
): TimedProbeResult {
    if (network == null || host.isNullOrBlank()) return TimedProbeResult(succeeded = false)
    val startedAtMs = SystemClock.elapsedRealtime()
    val succeeded = withTimeoutOrNull(timeoutMs) {
        runInterruptible(Dispatchers.IO) { network.getAllByName(host).isNotEmpty() }
    } == true
    return TimedProbeResult(
        succeeded = succeeded,
        latencyMs = (SystemClock.elapsedRealtime() - startedAtMs).takeIf { succeeded }
    )
}

internal fun ProbeStatistics.toDiagnosticFields(prefix: String): String {
    return "${prefix}_attempts=$attempts ${prefix}_loss_pct=$lossPercent " +
        "${prefix}_avg_ms=${averageLatencyMs ?: -1L} ${prefix}_jitter_ms=${jitterMs ?: -1L}"
}

internal data class LayeredNetworkHealthSnapshot(
    val physical: ProbeStatistics,
    val dns: ProbeStatistics,
    val proxy: ProbeStatistics
)

internal class LayeredNetworkHealthSampler(
    private val attempts: Int = DEFAULT_ATTEMPTS,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS
) {
    init {
        require(attempts > 0)
        require(intervalMs >= 0L)
    }

    suspend fun sample(
        physicalProbe: suspend () -> TimedProbeResult,
        dnsProbe: suspend () -> TimedProbeResult,
        proxyProbe: suspend () -> TimedProbeResult
    ): LayeredNetworkHealthSnapshot {
        val physicalResults = mutableListOf<TimedProbeResult>()
        val dnsResults = mutableListOf<TimedProbeResult>()
        val proxyResults = mutableListOf<TimedProbeResult>()
        repeat(attempts) { index ->
            val physical = runProbe(physicalProbe)
            physicalResults += physical
            if (physical.succeeded) {
                val (dns, proxy) = coroutineScope {
                    val dnsDeferred = async { runProbe(dnsProbe) }
                    val proxyDeferred = async { runProbe(proxyProbe) }
                    dnsDeferred.await() to proxyDeferred.await()
                }
                dnsResults += dns
                proxyResults += proxy
            } else {
                dnsResults += TimedProbeResult(succeeded = false)
                proxyResults += TimedProbeResult(succeeded = false)
            }
            if (intervalMs > 0L && index < attempts - 1) delay(intervalMs)
        }
        return LayeredNetworkHealthSnapshot(
            physical = summarizeTimedProbeResults(physicalResults),
            dns = summarizeTimedProbeResults(dnsResults),
            proxy = summarizeTimedProbeResults(proxyResults)
        )
    }

    private suspend fun runProbe(probe: suspend () -> TimedProbeResult): TimedProbeResult {
        return try {
            probe()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            TimedProbeResult(succeeded = false)
        }
    }

    private companion object {
        const val DEFAULT_ATTEMPTS = 3
        const val DEFAULT_INTERVAL_MS = 100L
    }
}
