package com.kunk.singbox.utils.perf

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.utils.VersionInfo
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class DiagnosticResourceSample(
    val timestampEpochMs: Long,
    val elapsedRealtimeMs: Long,
    val processName: String,
    val pid: Int,
    val pssKb: Int?,
    val cpuTimeMs: Long?,
    val cpuPercent: Double?,
    val fdCount: Int?,
    val fdSoftLimit: Long? = null,
    val fdRatio: Double? = null,
    val fdBreakdown: FdBreakdown? = null,
    val appVersion: String? = null,
    val appVersionCode: Long? = null,
    val processStartedAtEpochMs: Long? = null
)

internal data class FdBreakdown(
    val socketCount: Int = 0,
    val socketUniqueCount: Int = 0,
    val anonInodeCount: Int = 0,
    val eventFdCount: Int = 0,
    val eventPollCount: Int = 0,
    val timerFdCount: Int = 0,
    val pipeCount: Int = 0,
    val ordinaryFileCount: Int = 0,
    val deviceCount: Int = 0,
    val unknownCount: Int = 0,
    val tcpCount: Int = 0,
    val tcp6Count: Int = 0,
    val udpCount: Int = 0,
    val udp6Count: Int = 0,
    val rawCount: Int = 0,
    val raw6Count: Int = 0,
    val unixCount: Int = 0,
    val netlinkCount: Int = 0,
    val packetCount: Int = 0,
    val socketUnknownCount: Int = 0,
    val socketTableFailures: String = "",
    val socketStates: String = ""
)

internal enum class FdTargetType {
    SOCKET,
    ANON_INODE,
    PIPE,
    ORDINARY_FILE,
    DEVICE,
    UNKNOWN
}

internal fun classifyFdTarget(target: String?): FdTargetType = when {
    target == null -> FdTargetType.UNKNOWN
    target.startsWith("socket:[") -> FdTargetType.SOCKET
    target.startsWith("anon_inode:") -> FdTargetType.ANON_INODE
    target.startsWith("pipe:[") -> FdTargetType.PIPE
    target.startsWith("/dev/") -> FdTargetType.DEVICE
    target.startsWith('/') -> FdTargetType.ORDINARY_FILE
    else -> FdTargetType.UNKNOWN
}

internal data class ProcessResourcePoint(
    val pid: Int,
    val elapsedRealtimeMs: Long,
    val cpuTimeMs: Long
)

internal const val DIAGNOSTIC_RESOURCE_CSV_HEADER =
    "timestamp_epoch_ms,elapsed_realtime_ms,process_name,pid,pss_kb,cpu_time_ms,cpu_percent,fd_count," +
        "fd_soft_limit,fd_ratio,fd_socket,socket_unique,fd_anon_inode,fd_eventfd,fd_eventpoll,fd_timerfd,fd_pipe," +
        "fd_file,fd_device,fd_unknown,socket_tcp,socket_tcp6,socket_udp,socket_udp6,socket_raw," +
        "socket_raw6,socket_unix,socket_netlink,socket_packet,socket_unknown,socket_table_failures," +
        "socket_states,app_version,app_version_code,process_started_at_epoch_ms"

internal fun formatDiagnosticResourceSamplesCsv(samples: List<DiagnosticResourceSample>): String = buildString {
    appendLine(DIAGNOSTIC_RESOURCE_CSV_HEADER)
    samples.forEach { sample ->
        val breakdown = sample.fdBreakdown
        appendLine(
            listOf(
                sample.timestampEpochMs.toString(),
                sample.elapsedRealtimeMs.toString(),
                sample.processName.toCsvField(),
                sample.pid.toString(),
                sample.pssKb?.toString().orEmpty(),
                sample.cpuTimeMs?.toString().orEmpty(),
                sample.cpuPercent?.let { String.format(Locale.US, "%.2f", it) }.orEmpty(),
                sample.fdCount?.toString().orEmpty(),
                sample.fdSoftLimit?.toString().orEmpty(),
                sample.fdRatio?.let { String.format(Locale.US, "%.4f", it) }.orEmpty(),
                breakdown?.socketCount?.toString().orEmpty(),
                breakdown?.socketUniqueCount?.toString().orEmpty(),
                breakdown?.anonInodeCount?.toString().orEmpty(),
                breakdown?.eventFdCount?.toString().orEmpty(),
                breakdown?.eventPollCount?.toString().orEmpty(),
                breakdown?.timerFdCount?.toString().orEmpty(),
                breakdown?.pipeCount?.toString().orEmpty(),
                breakdown?.ordinaryFileCount?.toString().orEmpty(),
                breakdown?.deviceCount?.toString().orEmpty(),
                breakdown?.unknownCount?.toString().orEmpty(),
                breakdown?.tcpCount?.toString().orEmpty(),
                breakdown?.tcp6Count?.toString().orEmpty(),
                breakdown?.udpCount?.toString().orEmpty(),
                breakdown?.udp6Count?.toString().orEmpty(),
                breakdown?.rawCount?.toString().orEmpty(),
                breakdown?.raw6Count?.toString().orEmpty(),
                breakdown?.unixCount?.toString().orEmpty(),
                breakdown?.netlinkCount?.toString().orEmpty(),
                breakdown?.packetCount?.toString().orEmpty(),
                breakdown?.socketUnknownCount?.toString().orEmpty(),
                breakdown?.socketTableFailures?.toCsvField().orEmpty(),
                breakdown?.socketStates?.toCsvField().orEmpty(),
                sample.appVersion?.toCsvField().orEmpty(),
                sample.appVersionCode?.toString().orEmpty(),
                sample.processStartedAtEpochMs?.toString().orEmpty()
            ).joinToString(",")
        )
    }
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
internal fun parseDiagnosticResourceSamplesCsv(csv: String): List<DiagnosticResourceSample> {
    val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
    if (lines.size < 2) return emptyList()
    val headers = parseCsvLine(lines.first()).withIndex().associate { it.value to it.index }
    fun List<String>.value(name: String): String = headers[name]?.let { index -> getOrNull(index) }.orEmpty()
    fun List<String>.int(name: String): Int? = value(name).toIntOrNull()
    fun List<String>.long(name: String): Long? = value(name).toLongOrNull()
    fun List<String>.double(name: String): Double? = value(name).toDoubleOrNull()

    return lines.drop(1).mapNotNull { line ->
        val values = parseCsvLine(line)
        val timestamp = values.long("timestamp_epoch_ms") ?: return@mapNotNull null
        val elapsed = values.long("elapsed_realtime_ms") ?: return@mapNotNull null
        val processName = values.value("process_name")
        val pid = values.int("pid") ?: return@mapNotNull null
        val breakdownValues = listOf(
            "fd_socket", "socket_unique", "fd_anon_inode", "fd_eventfd", "fd_eventpoll", "fd_timerfd", "fd_pipe",
            "fd_file", "fd_device", "fd_unknown", "socket_tcp", "socket_tcp6", "socket_udp",
            "socket_udp6", "socket_raw", "socket_raw6", "socket_unix", "socket_netlink",
            "socket_packet", "socket_unknown"
        )
        val hasBreakdown = breakdownValues.any { values.value(it).isNotBlank() } ||
            values.value("socket_table_failures").isNotBlank() || values.value("socket_states").isNotBlank()
        DiagnosticResourceSample(
            timestampEpochMs = timestamp,
            elapsedRealtimeMs = elapsed,
            processName = processName,
            pid = pid,
            pssKb = values.int("pss_kb"),
            cpuTimeMs = values.long("cpu_time_ms"),
            cpuPercent = values.double("cpu_percent"),
            fdCount = values.int("fd_count"),
            fdSoftLimit = values.long("fd_soft_limit"),
            fdRatio = values.double("fd_ratio"),
            fdBreakdown = if (hasBreakdown) {
                FdBreakdown(
                    socketCount = values.int("fd_socket") ?: 0,
                    socketUniqueCount = values.int("socket_unique") ?: 0,
                    anonInodeCount = values.int("fd_anon_inode") ?: 0,
                    eventFdCount = values.int("fd_eventfd") ?: 0,
                    eventPollCount = values.int("fd_eventpoll") ?: 0,
                    timerFdCount = values.int("fd_timerfd") ?: 0,
                    pipeCount = values.int("fd_pipe") ?: 0,
                    ordinaryFileCount = values.int("fd_file") ?: 0,
                    deviceCount = values.int("fd_device") ?: 0,
                    unknownCount = values.int("fd_unknown") ?: 0,
                    tcpCount = values.int("socket_tcp") ?: 0,
                    tcp6Count = values.int("socket_tcp6") ?: 0,
                    udpCount = values.int("socket_udp") ?: 0,
                    udp6Count = values.int("socket_udp6") ?: 0,
                    rawCount = values.int("socket_raw") ?: 0,
                    raw6Count = values.int("socket_raw6") ?: 0,
                    unixCount = values.int("socket_unix") ?: 0,
                    netlinkCount = values.int("socket_netlink") ?: 0,
                    packetCount = values.int("socket_packet") ?: 0,
                    socketUnknownCount = values.int("socket_unknown") ?: 0,
                    socketTableFailures = values.value("socket_table_failures"),
                    socketStates = values.value("socket_states")
                )
            } else {
                null
            },
            appVersion = values.value("app_version").takeIf(String::isNotBlank),
            appVersionCode = values.long("app_version_code"),
            processStartedAtEpochMs = values.long("process_started_at_epoch_ms")
        )
    }
}

private fun parseCsvLine(line: String): List<String> {
    val fields = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var index = 0
    while (index < line.length) {
        val char = line[index]
        when {
            char == '"' && quoted && line.getOrNull(index + 1) == '"' -> {
                current.append('"')
                index++
            }
            char == '"' -> quoted = !quoted
            char == ',' && !quoted -> {
                fields += current.toString()
                current.clear()
            }
            else -> current.append(char)
        }
        index++
    }
    fields += current.toString()
    return fields
}

private fun String.toCsvField(): String {
    if (none { it in CSV_SPECIAL_CHARACTERS }) return this
    return "\"${replace("\"", "\"\"")}\""
}

internal fun parseProcCpuTimeMs(stat: String, ticksPerSecond: Long): Long? {
    val fields = parseProcStatFields(stat, ticksPerSecond)
    val userTicks = fields.getOrNull(11)?.toLongOrNull()
    val systemTicks = fields.getOrNull(12)?.toLongOrNull()
    return if (userTicks == null || systemTicks == null) {
        null
    } else {
        val totalTicks = userTicks + systemTicks
        totalTicks / ticksPerSecond * 1_000L + totalTicks % ticksPerSecond * 1_000L / ticksPerSecond
    }
}

internal fun parseProcProcessStartElapsedRealtimeMs(stat: String, ticksPerSecond: Long): Long? {
    val startTicks = parseProcStatFields(stat, ticksPerSecond).getOrNull(19)?.toLongOrNull() ?: return null
    return startTicks / ticksPerSecond * 1_000L + startTicks % ticksPerSecond * 1_000L / ticksPerSecond
}

internal fun calculateProcessStartedAtEpochMs(
    timestampEpochMs: Long,
    elapsedRealtimeMs: Long,
    processStartElapsedRealtimeMs: Long
): Long? {
    if (processStartElapsedRealtimeMs < 0L || processStartElapsedRealtimeMs > elapsedRealtimeMs) return null
    return timestampEpochMs - (elapsedRealtimeMs - processStartElapsedRealtimeMs)
}

internal fun readProcessStartedAtEpochMs(pid: Int = Process.myPid()): Long? = runCatching {
    val ticksPerSecond = Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0L } ?: return@runCatching null
    val startElapsedRealtimeMs = parseProcProcessStartElapsedRealtimeMs(
        File("/proc/$pid/stat").readText(Charsets.UTF_8),
        ticksPerSecond
    ) ?: return@runCatching null
    calculateProcessStartedAtEpochMs(
        timestampEpochMs = System.currentTimeMillis(),
        elapsedRealtimeMs = SystemClock.elapsedRealtime(),
        processStartElapsedRealtimeMs = startElapsedRealtimeMs
    )
}.getOrNull()

private fun parseProcStatFields(stat: String, ticksPerSecond: Long): List<String> {
    val processNameEnd = stat.lastIndexOf(')')
    return if (ticksPerSecond > 0L && processNameEnd >= 0 && processNameEnd + 2 < stat.length) {
        stat.substring(processNameEnd + 2).trim().split(Regex("\\s+"))
    } else {
        emptyList()
    }
}

internal fun parseProcSocketRows(
    lines: Sequence<String>,
    inodeColumn: Int,
    stateColumn: Int
): Map<String, String> = buildMap {
    lines.drop(1).forEach { line ->
        val fields = line.trim().split(' ', '\t').filter(String::isNotBlank)
        val inode = fields.getOrNull(inodeColumn)
        val state = fields.getOrNull(stateColumn)
        if (!inode.isNullOrBlank() && !state.isNullOrBlank()) put(inode, state)
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
    private val appVersion = VersionInfo.getAppVersionName(appContext)
    private val appVersionCode = VersionInfo.getAppVersionCode(appContext)

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
            captureProcess(
                process = process,
                timestampEpochMs = timestampEpochMs,
                elapsedRealtimeMs = elapsedRealtimeMs,
                pssKb = memoryByPid[process.pid],
                includeFdBreakdown = false
            )
        }.also {
            cpuBaseline.retainPids(currentPids)
        }
    }

    fun captureCurrentProcess(includeFdBreakdown: Boolean = false): DiagnosticResourceSample {
        val process = ObservedProcess(Process.myPid(), currentProcessName())
        val memoryByPid = readMemoryByPid(intArrayOf(process.pid))
        return captureProcess(
            process = process,
            timestampEpochMs = System.currentTimeMillis(),
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            pssKb = memoryByPid[process.pid],
            includeFdBreakdown = includeFdBreakdown
        ).also { cpuBaseline.retainPids(setOf(process.pid)) }
    }

    private fun captureProcess(
        process: ObservedProcess,
        timestampEpochMs: Long,
        elapsedRealtimeMs: Long,
        pssKb: Int?,
        includeFdBreakdown: Boolean
    ): DiagnosticResourceSample {
        val stat = readProcessStat(process.pid)
        val cpuTimeMs = stat?.let { parseProcCpuTimeMs(it, ticksPerSecond) }
        val processStartElapsedRealtimeMs = stat?.let {
            parseProcProcessStartElapsedRealtimeMs(it, ticksPerSecond)
        }
        val point = cpuTimeMs?.let { ProcessResourcePoint(process.pid, elapsedRealtimeMs, it) }
        val fdCount = readFdCount(process.pid)
        val fdSoftLimit = readFdSoftLimit(process.pid)
        return DiagnosticResourceSample(
            timestampEpochMs = timestampEpochMs,
            elapsedRealtimeMs = elapsedRealtimeMs,
            processName = process.processName,
            pid = process.pid,
            pssKb = pssKb,
            cpuTimeMs = cpuTimeMs,
            cpuPercent = point?.let(cpuBaseline::update),
            fdCount = fdCount,
            fdSoftLimit = fdSoftLimit,
            fdRatio = if (fdCount != null && fdSoftLimit != null && fdSoftLimit > 0L) {
                fdCount.toDouble() / fdSoftLimit.toDouble()
            } else {
                null
            },
            fdBreakdown = if (includeFdBreakdown) readFdBreakdown(process.pid) else null,
            appVersion = appVersion,
            appVersionCode = appVersionCode,
            processStartedAtEpochMs = processStartElapsedRealtimeMs?.let {
                calculateProcessStartedAtEpochMs(timestampEpochMs, elapsedRealtimeMs, it)
            }
        )
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

        return listOf(ObservedProcess(Process.myPid(), currentProcessName()))
    }

    private fun currentProcessName(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Application.getProcessName()
    } else {
        appContext.packageName
    }

    @Suppress("DEPRECATION")
    private fun readMemoryByPid(pids: IntArray): Map<Int, Int> {
        if (pids.isEmpty()) return emptyMap()
        val memoryInfo = runCatching { activityManager.getProcessMemoryInfo(pids) }.getOrNull().orEmpty()
        return pids.zip(memoryInfo).associate { (pid, info) -> pid to info.totalPss.coerceAtLeast(0) }
    }

    private fun readProcessStat(pid: Int): String? = runCatching {
        File("/proc/$pid/stat").readText(Charsets.UTF_8)
    }.getOrNull()

    private fun readFdCount(pid: Int): Int? = runCatching {
        File("/proc/$pid/fd").list()?.size
    }.getOrNull()

    private fun readFdSoftLimit(pid: Int): Long? {
        if (pid == Process.myPid()) {
            val platformLimit = runCatching {
                val resource = OsConstants::class.java.getField("RLIMIT_NOFILE").getInt(null)
                val method = Os::class.java.getMethod("getrlimit", Int::class.javaPrimitiveType)
                val limit = method.invoke(null, resource)
                limit.javaClass.getField("rlim_cur").getLong(limit)
            }
                .getOrNull()
                ?.takeIf { it > 0L && it < Long.MAX_VALUE }
            if (platformLimit != null) return platformLimit
        }
        return runCatching {
            File("/proc/$pid/limits").useLines(Charsets.UTF_8) { lines ->
                lines.firstOrNull { it.startsWith("Max open files") }
                    ?.removePrefix("Max open files")
                    ?.trim()
                    ?.split(' ', '\t')
                    ?.firstOrNull { it.isNotBlank() }
                    ?.toLongOrNull()
            }
        }.getOrNull()
    }

    private fun readFdBreakdown(pid: Int): FdBreakdown? = runCatching {
        val socketDetails = readSocketDetails(pid)
        val counts = MutableFdBreakdown()
        File("/proc/$pid/fd").listFiles().orEmpty().forEach { fd ->
            val target = runCatching { Os.readlink(fd.absolutePath) }.getOrNull()
            counts.observe(target, socketDetails.details)
        }
        counts.build(socketDetails.failures)
    }.getOrNull()

    private fun readSocketDetails(pid: Int): SocketDetails {
        val result = mutableMapOf<String, SocketDetail>()
        val failures = mutableSetOf<String>()
        SOCKET_TABLES.forEach { table ->
            runCatching {
                File("/proc/$pid/net/${table.fileName}").useLines(Charsets.UTF_8) { lines ->
                    parseProcSocketRows(lines, table.inodeColumn, table.stateColumn).forEach { (inode, state) ->
                        result[inode] = SocketDetail(table.protocol, state)
                    }
                }
            }.onFailure { error ->
                failures += "${table.protocol}:${error.javaClass.simpleName}"
            }
        }
        return SocketDetails(result, failures)
    }

    private companion object {
        const val DEFAULT_TICKS_PER_SECOND = 100L
        val SOCKET_TABLES = listOf(
            SocketTable("tcp", "tcp", inodeColumn = 9, stateColumn = 3),
            SocketTable("tcp6", "tcp6", inodeColumn = 9, stateColumn = 3),
            SocketTable("udp", "udp", inodeColumn = 9, stateColumn = 3),
            SocketTable("udp6", "udp6", inodeColumn = 9, stateColumn = 3),
            SocketTable("raw", "raw", inodeColumn = 9, stateColumn = 3),
            SocketTable("raw6", "raw6", inodeColumn = 9, stateColumn = 3),
            SocketTable("unix", "unix", inodeColumn = 6, stateColumn = 5),
            SocketTable("netlink", "netlink", inodeColumn = 9, stateColumn = 1),
            SocketTable("packet", "packet", inodeColumn = 8, stateColumn = 3)
        )
    }

    private data class ObservedProcess(val pid: Int, val processName: String)
    private data class SocketTable(
        val fileName: String,
        val protocol: String,
        val inodeColumn: Int,
        val stateColumn: Int
    )
    private data class SocketDetail(val protocol: String, val state: String)
    private data class SocketDetails(
        val details: Map<String, SocketDetail>,
        val failures: Set<String>
    )

    private class MutableFdBreakdown {
        var socketCount = 0
        var anonInodeCount = 0
        var eventFdCount = 0
        var eventPollCount = 0
        var timerFdCount = 0
        var pipeCount = 0
        var ordinaryFileCount = 0
        var deviceCount = 0
        var unknownCount = 0
        var socketUnknownCount = 0
        val socketInodes = mutableSetOf<String>()
        val protocolCounts = mutableMapOf<String, Int>()
        val stateCounts = mutableMapOf<String, Int>()

        fun observe(target: String?, socketDetails: Map<String, SocketDetail>) {
            when (classifyFdTarget(target)) {
                FdTargetType.SOCKET -> observeSocket(target.orEmpty(), socketDetails)
                FdTargetType.ANON_INODE -> {
                    anonInodeCount++
                    when {
                        "eventfd" in target.orEmpty() -> eventFdCount++
                        "eventpoll" in target.orEmpty() -> eventPollCount++
                        "timerfd" in target.orEmpty() -> timerFdCount++
                    }
                }
                FdTargetType.PIPE -> pipeCount++
                FdTargetType.ORDINARY_FILE -> ordinaryFileCount++
                FdTargetType.DEVICE -> deviceCount++
                FdTargetType.UNKNOWN -> unknownCount++
            }
        }

        private fun observeSocket(target: String, socketDetails: Map<String, SocketDetail>) {
            socketCount++
            val inode = target.substringAfter('[', "").substringBefore(']', "")
            if (inode.isNotBlank()) socketInodes += inode
            val detail = socketDetails[inode]
            if (detail == null) {
                socketUnknownCount++
                return
            }
            protocolCounts[detail.protocol] = protocolCounts.getOrDefault(detail.protocol, 0) + 1
            val stateKey = "${detail.protocol}:${detail.state}"
            stateCounts[stateKey] = stateCounts.getOrDefault(stateKey, 0) + 1
        }

        fun build(socketTableFailures: Set<String>): FdBreakdown = FdBreakdown(
            socketCount = socketCount,
            socketUniqueCount = socketInodes.size,
            anonInodeCount = anonInodeCount,
            eventFdCount = eventFdCount,
            eventPollCount = eventPollCount,
            timerFdCount = timerFdCount,
            pipeCount = pipeCount,
            ordinaryFileCount = ordinaryFileCount,
            deviceCount = deviceCount,
            unknownCount = unknownCount,
            tcpCount = protocolCounts["tcp"] ?: 0,
            tcp6Count = protocolCounts["tcp6"] ?: 0,
            udpCount = protocolCounts["udp"] ?: 0,
            udp6Count = protocolCounts["udp6"] ?: 0,
            rawCount = protocolCounts["raw"] ?: 0,
            raw6Count = protocolCounts["raw6"] ?: 0,
            unixCount = protocolCounts["unix"] ?: 0,
            netlinkCount = protocolCounts["netlink"] ?: 0,
            packetCount = protocolCounts["packet"] ?: 0,
            socketUnknownCount = socketUnknownCount,
            socketTableFailures = socketTableFailures.sorted().joinToString(";"),
            socketStates = stateCounts.toSortedMap().entries.joinToString(";") { (state, count) -> "$state=$count" }
        )
    }
}

internal enum class FdPressureLevel {
    NORMAL,
    OBSERVE,
    WARNING,
    RECOVERY,
    EMERGENCY
}

internal data class FdPressureDecision(
    val level: FdPressureLevel,
    val sampleIntervalMs: Long,
    val shouldClassify: Boolean,
    val shouldRecover: Boolean
)

internal fun evaluateFdPressure(
    fdCount: Int?,
    fdSoftLimit: Long?,
    growthOverFiveMinutes: Int,
    consecutiveHighSamples: Int
): FdPressureDecision {
    val ratio = if (fdCount != null && fdSoftLimit != null && fdSoftLimit > 0L) {
        fdCount.toDouble() / fdSoftLimit.toDouble()
    } else {
        null
    }
    return when {
        ratio != null && ratio >= FD_EMERGENCY_RATIO -> FdPressureDecision(
            FdPressureLevel.EMERGENCY,
            FD_WARNING_SAMPLE_INTERVAL_MS,
            shouldClassify = true,
            shouldRecover = true
        )
        ratio != null && ratio >= FD_RECOVERY_RATIO && consecutiveHighSamples >= 2 -> FdPressureDecision(
            FdPressureLevel.RECOVERY,
            FD_WARNING_SAMPLE_INTERVAL_MS,
            shouldClassify = true,
            shouldRecover = true
        )
        ratio != null && ratio >= FD_WARNING_RATIO || growthOverFiveMinutes >= FD_FIVE_MINUTE_GROWTH_WARNING -> {
            FdPressureDecision(
                FdPressureLevel.WARNING,
                FD_WARNING_SAMPLE_INTERVAL_MS,
                shouldClassify = true,
                shouldRecover = false
            )
        }
        ratio != null && ratio >= FD_OBSERVE_RATIO -> FdPressureDecision(
            FdPressureLevel.OBSERVE,
            FD_OBSERVE_SAMPLE_INTERVAL_MS,
            shouldClassify = false,
            shouldRecover = false
        )
        else -> FdPressureDecision(
            FdPressureLevel.NORMAL,
            FD_NORMAL_SAMPLE_INTERVAL_MS,
            shouldClassify = false,
            shouldRecover = false
        )
    }
}

internal fun isFdRecoverySufficient(
    beforeCount: Int?,
    afterCount: Int?,
    softLimit: Long?
): Boolean {
    if (beforeCount == null || afterCount == null) return false
    if (softLimit == null || softLimit <= 0L) return false
    val afterRatio = afterCount.toDouble() / softLimit.toDouble()
    val fellByHalf = afterCount <= beforeCount / 2
    return afterRatio < FD_WARNING_RATIO && (afterRatio < FD_OBSERVE_RATIO || fellByHalf)
}

internal class ResourceFdTracker {
    private val samples = ArrayDeque<Pair<Long, Int>>()
    private var pid: Int? = null
    private var consecutiveHighSamples = 0

    fun observe(sample: DiagnosticResourceSample): FdPressureDecision {
        if (pid != sample.pid) {
            samples.clear()
            consecutiveHighSamples = 0
            pid = sample.pid
        }
        val count = sample.fdCount
        if (count != null) {
            samples.addLast(sample.elapsedRealtimeMs to count)
            while (samples.size > 1 &&
                sample.elapsedRealtimeMs - samples.first().first > FD_GROWTH_WINDOW_MS
            ) {
                samples.removeFirst()
            }
        }
        val ratio = if (count != null && sample.fdSoftLimit != null && sample.fdSoftLimit > 0L) {
            count.toDouble() / sample.fdSoftLimit.toDouble()
        } else {
            null
        }
        consecutiveHighSamples = if (ratio != null && ratio >= FD_RECOVERY_RATIO) {
            consecutiveHighSamples + 1
        } else {
            0
        }
        val growth = if (samples.size >= 2) samples.last().second - samples.first().second else 0
        return evaluateFdPressure(count, sample.fdSoftLimit, growth, consecutiveHighSamples)
    }
}

internal class DiagnosticResourceHistory(
    private val historyFile: File,
    private val maxSamples: Int = MAX_BACKGROUND_RESOURCE_SAMPLES
) {
    constructor(context: Context, maxSamples: Int = MAX_BACKGROUND_RESOURCE_SAMPLES) : this(
        File(context.filesDir, RESOURCE_HISTORY_RELATIVE_PATH),
        maxSamples
    )

    private var knownCount: Int? = null

    @Synchronized
    fun append(sample: DiagnosticResourceSample) {
        historyFile.parentFile?.mkdirs()
        val csv = formatDiagnosticResourceSamplesCsv(listOf(sample))
        val row = csv.lineSequence().drop(1).firstOrNull().orEmpty()
        if (!historyFile.exists()) {
            writeAtomically(listOf(sample))
            knownCount = 1
            return
        }
        val currentHeader = historyFile.bufferedReader(Charsets.UTF_8).use { it.readLine() }
        if (currentHeader != DIAGNOSTIC_RESOURCE_CSV_HEADER) {
            val migrated = (read() + sample).takeLast(maxSamples)
            writeAtomically(migrated)
            knownCount = migrated.size
            return
        }
        val previousCount = knownCount ?: read().size
        FileOutputStream(historyFile, true).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.append(row)
            writer.newLine()
        }
        val count = previousCount + 1
        knownCount = count
        if (count > maxSamples) {
            // ponytail: 仅在 4096 条边界发生一次 O(n) 轮转，避免常态维护额外索引文件。
            val retained = read().takeLast(maxSamples)
            writeAtomically(retained)
            knownCount = retained.size
        }
    }

    @Synchronized
    fun read(): List<DiagnosticResourceSample> {
        if (!historyFile.isFile) return emptyList()
        return runCatching {
            parseDiagnosticResourceSamplesCsv(historyFile.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    private fun writeAtomically(samples: List<DiagnosticResourceSample>) {
        val tempFile = File(historyFile.parentFile, "${historyFile.name}.tmp")
        tempFile.writeText(formatDiagnosticResourceSamplesCsv(samples), Charsets.UTF_8)
        val renamed = tempFile.renameTo(historyFile) || runCatching {
            Os.rename(tempFile.absolutePath, historyFile.absolutePath)
            historyFile.isFile && !tempFile.exists()
        }.getOrDefault(false)
        if (!renamed) {
            tempFile.copyTo(historyFile, overwrite = true)
            tempFile.delete()
        }
    }
}

internal fun mergeDiagnosticResourceSamples(
    backgroundSamples: List<DiagnosticResourceSample>,
    inMemorySamples: List<DiagnosticResourceSample>
): List<DiagnosticResourceSample> {
    return (backgroundSamples + inMemorySamples)
        .distinctBy { Triple(it.timestampEpochMs, it.processName, it.pid) }
        .sortedWith(compareBy<DiagnosticResourceSample> { it.timestampEpochMs }.thenBy { it.processName })
}

internal interface ResourceGuardOwner {
    fun isRecoveryAllowed(): Boolean
    fun closeConnections(): Boolean
    fun resetNetwork(): Boolean
    fun restartCore(reason: String): Boolean
    fun recycleProcess(reason: String)
    fun publishBudgetExhausted(reason: String)
}

internal object BackgroundResourceGuard {
    private const val TAG = "BackgroundResourceGuard"
    private val lock = Any()
    private var ownerToken: String? = null
    private var monitorJob: Job? = null
    private var recoveryJob: Job? = null
    private var owner: ResourceGuardOwner? = null
    private var ownerScope: CoroutineScope? = null
    private var sampler: DiagnosticResourceSampler? = null
    private var history: DiagnosticResourceHistory? = null

    @Volatile
    private var recovering = false

    fun start(context: Context, scope: CoroutineScope, token: String, owner: ResourceGuardOwner) {
        synchronized(lock) {
            if (ownerToken == token && monitorJob?.isActive == true) return
            monitorJob?.cancel()
            recoveryJob?.cancel()
            this.ownerToken = token
            this.owner = owner
            ownerScope = scope
            sampler = DiagnosticResourceSampler(context)
            history = DiagnosticResourceHistory(context)
            recovering = false
            monitorJob = scope.launch {
                monitor(token)
            }
        }
    }

    fun stop(token: String) {
        synchronized(lock) {
            if (ownerToken != token) return
            monitorJob?.cancel()
            recoveryJob?.cancel()
            monitorJob = null
            recoveryJob = null
            ownerToken = null
            owner = null
            ownerScope = null
            sampler = null
            history = null
            recovering = false
        }
    }

    fun isRecovering(): Boolean = recovering

    fun signalResourceExhaustion(token: String, reason: String) {
        val sample = synchronized(lock) {
            if (ownerToken != token) return
            runCatching { sampler?.captureCurrentProcess(includeFdBreakdown = true) }.getOrNull()
        }
        requestRecovery(token, reason, sample)
    }

    private suspend fun monitor(token: String) {
        val tracker = ResourceFdTracker()
        while (kotlin.coroutines.coroutineContext.isActive && isCurrentOwner(token)) {
            try {
                var sample = sampler?.captureCurrentProcess() ?: return
                val decision = tracker.observe(sample)
                if (decision.shouldClassify) {
                    sample = sampler?.captureCurrentProcess(includeFdBreakdown = true) ?: sample
                }
                runCatching { history?.append(sample) }
                    .onFailure { Log.w(TAG, "Failed to persist resource sample: ${it.message}") }
                logSample(sample, decision)
                if (decision.shouldRecover) {
                    requestRecovery(token, "fd_${decision.level.name.lowercase(Locale.US)}", sample)
                }
                delay(decision.sampleIntervalMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Resource monitor sample failed", e)
                delay(FD_NORMAL_SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun requestRecovery(token: String, reason: String, before: DiagnosticResourceSample?) {
        synchronized(lock) {
            val scope = ownerScope ?: return
            val activeOwner = owner ?: return
            if (ownerToken != token || recoveryJob?.isActive == true || !activeOwner.isRecoveryAllowed()) return
            recovering = true
            recoveryJob = scope.launch {
                try {
                    recover(token, reason, before, activeOwner)
                } finally {
                    synchronized(lock) {
                        if (ownerToken == token) {
                            recovering = false
                            recoveryJob = null
                        }
                    }
                }
            }
        }
    }

    private suspend fun recover(
        token: String,
        reason: String,
        before: DiagnosticResourceSample?,
        activeOwner: ResourceGuardOwner
    ) {
        if (!isCurrentOwner(token) || !activeOwner.isRecoveryAllowed()) return
        val closed = activeOwner.closeConnections()
        val reset = activeOwner.resetNetwork()
        logRecovery("close_connections", before?.fdCount, null, "closed=$closed reset=$reset")
        delay(RESOURCE_RECOVERY_RESAMPLE_DELAY_MS)
        val afterReset = runCatching { sampler?.captureCurrentProcess(includeFdBreakdown = true) }.getOrNull()
        afterReset?.let { runCatching { history?.append(it) } }
        if (isFdRecoverySufficient(before?.fdCount, afterReset?.fdCount, afterReset?.fdSoftLimit)) {
            logRecovery("close_connections", before?.fdCount, afterReset?.fdCount, "success")
            return
        }
        if (!VpnStateStore.tryConsumeResourceRecovery(VpnStateStore.ResourceRecoveryAction.CORE_RESTART)) {
            recycleProcessIfAllowed(reason, activeOwner)
            return
        }
        val restartIssued = activeOwner.restartCore("resource_exhausted:$reason")
        logRecovery("restart_core", afterReset?.fdCount, null, "issued=$restartIssued")
        if (!restartIssued) {
            recycleProcessIfAllowed(reason, activeOwner)
            return
        }
        delay(RESOURCE_CORE_RESTART_OBSERVE_MS)
        val afterRestart = runCatching { sampler?.captureCurrentProcess(includeFdBreakdown = true) }.getOrNull()
        afterRestart?.let { runCatching { history?.append(it) } }
        if (!isFdRecoverySufficient(afterReset?.fdCount, afterRestart?.fdCount, afterRestart?.fdSoftLimit)) {
            recycleProcessIfAllowed(reason, activeOwner)
        } else {
            logRecovery("restart_core", afterReset?.fdCount, afterRestart?.fdCount, "success")
        }
    }

    private fun recycleProcessIfAllowed(reason: String, activeOwner: ResourceGuardOwner) {
        if (VpnStateStore.tryConsumeResourceRecovery(VpnStateStore.ResourceRecoveryAction.PROCESS_RECLAIM)) {
            activeOwner.recycleProcess("resource_exhausted:$reason")
        } else {
            activeOwner.publishBudgetExhausted("process_reclaim:$reason")
        }
    }

    private fun isCurrentOwner(token: String): Boolean = synchronized(lock) { ownerToken == token }

    private fun logSample(sample: DiagnosticResourceSample, decision: FdPressureDecision) {
        if (decision.level == FdPressureLevel.NORMAL) return
        val metric = "METRIC resource_fd process=${sample.processName.substringAfter(':', sample.processName)} " +
            "pid=${sample.pid} count=${sample.fdCount ?: -1} soft_limit=${sample.fdSoftLimit ?: -1} " +
            "ratio=${sample.fdRatio?.let { String.format(Locale.US, "%.3f", it) } ?: "unknown"} " +
            "level=${decision.level.name.lowercase(Locale.US)}"
        LogRepository.getInstance().addAlwaysLog(metric)
        sample.fdBreakdown?.let { breakdown ->
            LogRepository.getInstance().addAlwaysLog(
                "METRIC resource_fd_breakdown socket=${breakdown.socketCount} " +
                    "unique_socket=${breakdown.socketUniqueCount} " +
                    "raw=${breakdown.rawCount + breakdown.raw6Count} " +
                    "udp=${breakdown.udpCount + breakdown.udp6Count} " +
                    "tcp=${breakdown.tcpCount + breakdown.tcp6Count} anon_inode=${breakdown.anonInodeCount} " +
                    "unix=${breakdown.unixCount} netlink=${breakdown.netlinkCount} " +
                    "packet=${breakdown.packetCount} socket_unknown=${breakdown.socketUnknownCount} " +
                    "pipe=${breakdown.pipeCount} file=${breakdown.ordinaryFileCount} " +
                    "device=${breakdown.deviceCount} unknown=${breakdown.unknownCount} " +
                    "table_failures=${breakdown.socketTableFailures.ifBlank { "none" }}"
            )
        }
    }

    private fun logRecovery(stage: String, before: Int?, after: Int?, result: String) {
        LogRepository.getInstance().addAlwaysLog(
            "WARN recovery resource_exhausted stage=$stage before=${before ?: -1} " +
                "after=${after ?: -1} result=$result"
        )
    }
}

internal const val RESOURCE_HISTORY_RELATIVE_PATH = "diagnostics/resource_history.csv"
internal const val MAX_BACKGROUND_RESOURCE_SAMPLES = 4_096
internal const val FD_NORMAL_SAMPLE_INTERVAL_MS = 60_000L
internal const val FD_OBSERVE_SAMPLE_INTERVAL_MS = 15_000L
internal const val FD_WARNING_SAMPLE_INTERVAL_MS = 5_000L
internal const val FD_GROWTH_WINDOW_MS = 5 * 60_000L
internal const val FD_FIVE_MINUTE_GROWTH_WARNING = 1_024
internal const val FD_OBSERVE_RATIO = 0.50
internal const val FD_WARNING_RATIO = 0.70
internal const val FD_RECOVERY_RATIO = 0.85
internal const val FD_EMERGENCY_RATIO = 0.95
private const val RESOURCE_RECOVERY_RESAMPLE_DELAY_MS = 2_000L
private const val RESOURCE_CORE_RESTART_OBSERVE_MS = 5_000L

private val CSV_SPECIAL_CHARACTERS = setOf(',', '"', '\n', '\r')
