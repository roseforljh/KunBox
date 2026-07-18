package com.kunk.singbox.utils.perf

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.Locale

internal data class DiagnosticResourceSample(
    val timestampEpochMs: Long,
    val elapsedRealtimeMs: Long,
    val processName: String,
    val pid: Int,
    val pssKb: Int?,
    val cpuTimeMs: Long?,
    val cpuPercent: Double?,
    val fdCount: Int?
)

internal data class ProcessResourcePoint(
    val pid: Int,
    val elapsedRealtimeMs: Long,
    val cpuTimeMs: Long
)

internal fun formatDiagnosticResourceSamplesCsv(samples: List<DiagnosticResourceSample>): String = buildString {
    appendLine("timestamp_epoch_ms,elapsed_realtime_ms,process_name,pid,pss_kb,cpu_time_ms,cpu_percent,fd_count")
    samples.forEach { sample ->
        appendLine(
            listOf(
                sample.timestampEpochMs.toString(),
                sample.elapsedRealtimeMs.toString(),
                sample.processName.toCsvField(),
                sample.pid.toString(),
                sample.pssKb?.toString().orEmpty(),
                sample.cpuTimeMs?.toString().orEmpty(),
                sample.cpuPercent?.let { String.format(Locale.US, "%.2f", it) }.orEmpty(),
                sample.fdCount?.toString().orEmpty()
            ).joinToString(",")
        )
    }
}

private fun String.toCsvField(): String {
    if (none { it in CSV_SPECIAL_CHARACTERS }) return this
    return "\"${replace("\"", "\"\"")}\""
}

internal fun parseProcCpuTimeMs(stat: String, ticksPerSecond: Long): Long? {
    val processNameEnd = stat.lastIndexOf(')')
    val fields = if (ticksPerSecond > 0L && processNameEnd >= 0 && processNameEnd + 2 < stat.length) {
        stat.substring(processNameEnd + 2).trim().split(Regex("\\s+"))
    } else {
        emptyList()
    }
    val userTicks = fields.getOrNull(11)?.toLongOrNull()
    val systemTicks = fields.getOrNull(12)?.toLongOrNull()
    return if (userTicks == null || systemTicks == null) {
        null
    } else {
        val totalTicks = userTicks + systemTicks
        totalTicks / ticksPerSecond * 1_000L + totalTicks % ticksPerSecond * 1_000L / ticksPerSecond
    }
}

internal fun calculateProcessCpuPercent(
    previous: ProcessResourcePoint,
    current: ProcessResourcePoint
): Double? {
    if (previous.pid != current.pid) return null
    val elapsedDelta = current.elapsedRealtimeMs - previous.elapsedRealtimeMs
    val cpuDelta = current.cpuTimeMs - previous.cpuTimeMs
    if (elapsedDelta <= 0L || cpuDelta < 0L) return null
    return cpuDelta.toDouble() * 100.0 / elapsedDelta
}

internal class ProcessCpuBaseline {
    private val previousPoints = mutableMapOf<Int, ProcessResourcePoint>()

    fun update(current: ProcessResourcePoint): Double? {
        val previous = previousPoints.put(current.pid, current) ?: return null
        return calculateProcessCpuPercent(previous, current)
    }

    fun retainPids(pids: Set<Int>) {
        previousPoints.keys.retainAll(pids)
    }

    fun reset() {
        previousPoints.clear()
    }
}

internal class DiagnosticResourceSampler(context: Context) {

    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val cpuBaseline = ProcessCpuBaseline()
    private val ticksPerSecond = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }
        .getOrDefault(DEFAULT_TICKS_PER_SECOND)
        .takeIf { it > 0L }
        ?: DEFAULT_TICKS_PER_SECOND

    fun reset() {
        cpuBaseline.reset()
    }

    fun capture(): List<DiagnosticResourceSample> {
        val elapsedRealtimeMs = SystemClock.elapsedRealtime()
        val timestampEpochMs = System.currentTimeMillis()
        val processes = ownProcesses()
        val pids = processes.map { it.pid }.toIntArray()
        val memoryByPid = readMemoryByPid(pids)
        val currentPids = pids.toSet()

        return processes.map { process ->
            val cpuTimeMs = readCpuTimeMs(process.pid)
            val point = cpuTimeMs?.let { ProcessResourcePoint(process.pid, elapsedRealtimeMs, it) }
            val cpuPercent = point?.let(cpuBaseline::update)

            DiagnosticResourceSample(
                timestampEpochMs = timestampEpochMs,
                elapsedRealtimeMs = elapsedRealtimeMs,
                processName = process.processName,
                pid = process.pid,
                pssKb = memoryByPid[process.pid],
                cpuTimeMs = cpuTimeMs,
                cpuPercent = cpuPercent,
                fdCount = readFdCount(process.pid)
            )
        }.also {
            cpuBaseline.retainPids(currentPids)
        }
    }

    private fun ownProcesses(): List<ObservedProcess> {
        val packageName = appContext.packageName
        val visible = activityManager.runningAppProcesses.orEmpty()
            .filter { process ->
                process.uid == Process.myUid() &&
                    (process.processName == packageName || process.processName.startsWith("$packageName:"))
            }
            .sortedBy { it.processName }
            .map { process -> ObservedProcess(process.pid, process.processName) }
        if (visible.isNotEmpty()) return visible

        val currentName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            packageName
        }
        return listOf(ObservedProcess(Process.myPid(), currentName))
    }

    @Suppress("DEPRECATION")
    private fun readMemoryByPid(pids: IntArray): Map<Int, Int> {
        if (pids.isEmpty()) return emptyMap()
        val memoryInfo = runCatching { activityManager.getProcessMemoryInfo(pids) }.getOrNull().orEmpty()
        return pids.zip(memoryInfo).associate { (pid, info) -> pid to info.totalPss.coerceAtLeast(0) }
    }

    private fun readCpuTimeMs(pid: Int): Long? = runCatching {
        parseProcCpuTimeMs(File("/proc/$pid/stat").readText(Charsets.UTF_8), ticksPerSecond)
    }.getOrNull()

    private fun readFdCount(pid: Int): Int? = runCatching {
        File("/proc/$pid/fd").list()?.size
    }.getOrNull()

    private companion object {
        const val DEFAULT_TICKS_PER_SECOND = 100L
    }

    private data class ObservedProcess(val pid: Int, val processName: String)
}

private val CSV_SPECIAL_CHARACTERS = setOf(',', '"', '\n', '\r')
