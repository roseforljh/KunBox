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
import com.kunk.singbox.service.manager.ConnectionAttributionSnapshot
import com.kunk.singbox.utils.VersionInfo
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    val processStartedAtEpochMs: Long? = null,
    val libboxActiveConnections: Int? = null,
    val nativeLibboxSocketDelta: Int? = null,
    val nativePreConnectGap: Int? = null,
    val socketAttributionStatus: String? = null,
    val fdReadFailureStage: String? = null,
    val connectionAttribution: ConnectionAttributionSnapshot? = null
)

internal val classifySocketAttribution: (Int?, Int?) -> String = { nativeSocketCount, libboxActiveConnections ->
    if (nativeSocketCount == null || libboxActiveConnections == null) {
        "proc_unavailable"
    } else {
        val delta = nativeSocketCount - libboxActiveConnections
        if (delta >= NATIVE_PRECONNECT_GAP_MINIMUM &&
            nativeSocketCount >= (libboxActiveConnections * 2).coerceAtLeast(NATIVE_PRECONNECT_SOCKET_MINIMUM)
        ) {
            "native_preconnect_gap"
        } else {
            "attributed"
        }
    }
}

internal fun buildSocketAttributionDiagnosticLines(sample: DiagnosticResourceSample): List<String> {
    val snapshot = sample.connectionAttribution ?: return emptyList()
    val breakdown = sample.fdBreakdown
    return buildList {
        val appendGroups = { dimension: String, valueKey: String, counts: Map<String, Int> ->
            counts.forEach { (value, count) ->
                val safeValue = value.replace('\r', ' ').replace('\n', ' ').replace('"', '\'')
                add("METRIC resource_socket_group dimension=$dimension $valueKey=\"$safeValue\" count=$count")
            }
        }
        add(
            "METRIC resource_socket_attribution native_socket=${breakdown?.socketUniqueCount ?: -1} " +
                "native_fd_socket=${breakdown?.socketCount ?: -1} " +
                "libbox_active=${sample.libboxActiveConnections ?: -1} " +
                "socket_delta=${sample.nativeLibboxSocketDelta ?: -1} " +
                "native_preconnect_gap=${sample.nativePreConnectGap ?: -1} " +
                "status=${sample.socketAttributionStatus ?: "unknown"} " +
                "fd_read_failure_stage=${sample.fdReadFailureStage ?: "none"}"
        )
        appendGroups("outbound", "outbound", snapshot.outboundCounts)
        appendGroups("chain", "chain", snapshot.chainCounts)
        appendGroups("protocol", "protocol", snapshot.protocolCounts)
        appendGroups("application", "package", snapshot.applicationCounts)
    }
}

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
    val fdReadlinkFailureCount: Int = 0,
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
        "socket_raw6,socket_unix,socket_netlink,socket_packet,socket_unknown,fd_readlink_failures," +
        "socket_table_failures," +
        "socket_states,app_version,app_version_code,process_started_at_epoch_ms,libbox_active_connections," +
        "native_libbox_socket_delta,native_preconnect_gap,socket_attribution_status,fd_read_failure_stage"

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
                breakdown?.fdReadlinkFailureCount?.toString().orEmpty(),
                breakdown?.socketTableFailures?.toCsvField().orEmpty(),
                breakdown?.socketStates?.toCsvField().orEmpty(),
                sample.appVersion?.toCsvField().orEmpty(),
                sample.appVersionCode?.toString().orEmpty(),
                sample.processStartedAtEpochMs?.toString().orEmpty(),
                sample.libboxActiveConnections?.toString().orEmpty(),
                sample.nativeLibboxSocketDelta?.toString().orEmpty(),
                sample.nativePreConnectGap?.toString().orEmpty(),
                sample.socketAttributionStatus?.toCsvField().orEmpty(),
                sample.fdReadFailureStage?.toCsvField().orEmpty()
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
            "socket_packet", "socket_unknown", "fd_readlink_failures"
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
                    fdReadlinkFailureCount = values.int("fd_readlink_failures") ?: 0,
                    socketTableFailures = values.value("socket_table_failures"),
                    socketStates = values.value("socket_states")
                )
            } else {
                null
            },
            appVersion = values.value("app_version").takeIf(String::isNotBlank),
            appVersionCode = values.long("app_version_code"),
            processStartedAtEpochMs = values.long("process_started_at_epoch_ms"),
            libboxActiveConnections = values.int("libbox_active_connections"),
            nativeLibboxSocketDelta = values.int("native_libbox_socket_delta"),
            nativePreConnectGap = values.int("native_preconnect_gap"),
            socketAttributionStatus = values.value("socket_attribution_status").takeIf(String::isNotBlank),
            fdReadFailureStage = values.value("fd_read_failure_stage").takeIf(String::isNotBlank)
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

internal class ProcessStartEpochClock(private val bootEpochMs: Long) {
    fun calculate(elapsedRealtimeMs: Long, processStartElapsedRealtimeMs: Long): Long? {
        if (processStartElapsedRealtimeMs < 0L || processStartElapsedRealtimeMs > elapsedRealtimeMs) return null
        return bootEpochMs + processStartElapsedRealtimeMs
    }
}

private val processStartEpochClock by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    val elapsedRealtimeMs = SystemClock.elapsedRealtime()
    ProcessStartEpochClock(System.currentTimeMillis() - elapsedRealtimeMs)
}

internal fun calculateProcessStartedAtEpochMs(
    timestampEpochMs: Long,
    elapsedRealtimeMs: Long,
    processStartElapsedRealtimeMs: Long
): Long? = ProcessStartEpochClock(timestampEpochMs - elapsedRealtimeMs)
    .calculate(elapsedRealtimeMs, processStartElapsedRealtimeMs)

internal fun readProcessStartedAtEpochMs(pid: Int = Process.myPid()): Long? = runCatching {
    val ticksPerSecond = Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0L } ?: return@runCatching null
    val startElapsedRealtimeMs = parseProcProcessStartElapsedRealtimeMs(
        File("/proc/$pid/stat").readText(Charsets.UTF_8),
        ticksPerSecond
    ) ?: return@runCatching null
    processStartEpochClock.calculate(
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

internal data class ProcSocketTableReadResult(
    val rows: Map<String, String>?,
    val failures: List<String>
)

internal fun readProcSocketTableRows(
    pid: Int,
    fileName: String,
    inodeColumn: Int,
    stateColumn: Int,
    readLines: (String) -> List<String> = { path -> File(path).readLines(Charsets.UTF_8) }
): ProcSocketTableReadResult {
    val failures = mutableListOf<String>()
    val paths = listOf(
        "/proc/self/net/$fileName",
        "/proc/net/$fileName",
        "/proc/$pid/net/$fileName"
    ).distinct()
    paths.forEach { path ->
        try {
            return ProcSocketTableReadResult(
                rows = parseProcSocketRows(readLines(path).asSequence(), inodeColumn, stateColumn),
                failures = emptyList()
            )
        } catch (error: Exception) {
            failures += "${path.substringBeforeLast('/')}:${error.javaClass.simpleName}"
        }
    }
    return ProcSocketTableReadResult(rows = null, failures = failures)
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
    private val currentPid = Process.myPid()
    private val currentProcessName = currentProcessName()
    private val currentFdSoftLimit by lazy(LazyThreadSafetyMode.NONE) { readFdSoftLimit(currentPid) }
    private val currentProcessStartedAtEpochMs by lazy(LazyThreadSafetyMode.NONE) {
        readProcessStartedAtEpochMs(currentPid)
    }

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
        val process = ObservedProcess(currentPid, currentProcessName)
        val memoryByPid = readMemoryByPid(intArrayOf(process.pid))
        return captureProcess(
            process = process,
            timestampEpochMs = System.currentTimeMillis(),
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            pssKb = memoryByPid[process.pid],
            includeFdBreakdown = includeFdBreakdown
        ).also { cpuBaseline.retainPids(setOf(process.pid)) }
    }

    fun captureCurrentFdPressure(): DiagnosticResourceSample {
        val elapsedRealtimeMs = SystemClock.elapsedRealtime()
        val fdCount = readFdCount(currentPid)
        val fdSoftLimit = currentFdSoftLimit
        return DiagnosticResourceSample(
            timestampEpochMs = System.currentTimeMillis(),
            elapsedRealtimeMs = elapsedRealtimeMs,
            processName = currentProcessName,
            pid = currentPid,
            pssKb = null,
            cpuTimeMs = null,
            cpuPercent = null,
            fdCount = fdCount,
            fdSoftLimit = fdSoftLimit,
            fdRatio = if (fdCount != null && fdSoftLimit != null && fdSoftLimit > 0L) {
                fdCount.toDouble() / fdSoftLimit.toDouble()
            } else {
                null
            },
            fdReadFailureStage = "fd_count".takeIf { fdCount == null },
            appVersion = appVersion,
            appVersionCode = appVersionCode,
            processStartedAtEpochMs = currentProcessStartedAtEpochMs
        )
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
        val fdBreakdown = if (includeFdBreakdown) readFdBreakdown(process.pid) else null
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
            fdBreakdown = fdBreakdown,
            appVersion = appVersion,
            appVersionCode = appVersionCode,
            processStartedAtEpochMs = processStartElapsedRealtimeMs?.let {
                processStartEpochClock.calculate(elapsedRealtimeMs, it)
            },
            fdReadFailureStage = buildFdReadFailureStage(fdCount, includeFdBreakdown, fdBreakdown)
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
        val fdFiles = File("/proc/$pid/fd").listFiles() ?: return@runCatching null
        val socketDetails = readSocketDetails(pid)
        val counts = MutableFdBreakdown()
        fdFiles.forEach { fd ->
            val target = runCatching { Os.readlink(fd.absolutePath) }.getOrNull()
            counts.observe(target, socketDetails.details)
        }
        counts.build(socketDetails.failures)
    }.getOrNull()

    private fun buildFdReadFailureStage(
        fdCount: Int?,
        includeFdBreakdown: Boolean,
        breakdown: FdBreakdown?
    ): String? = buildList {
        if (fdCount == null) add("fd_count")
        if (includeFdBreakdown && breakdown == null) add("fd_scan")
        if ((breakdown?.fdReadlinkFailureCount ?: 0) > 0) add("fd_readlink")
        if (!breakdown?.socketTableFailures.isNullOrBlank()) add("socket_tables")
    }.joinToString(";").takeIf(String::isNotBlank)

    private fun readSocketDetails(pid: Int): SocketDetails {
        val result = mutableMapOf<String, SocketDetail>()
        val failures = mutableSetOf<String>()
        SOCKET_TABLES.forEach { table ->
            val readResult = readProcSocketTableRows(
                pid = pid,
                fileName = table.fileName,
                inodeColumn = table.inodeColumn,
                stateColumn = table.stateColumn
            )
            val rows = readResult.rows
            if (rows == null) {
                failures += "${table.protocol}:${readResult.failures.joinToString(",")}"
            } else {
                rows.forEach { (inode, state) -> result[inode] = SocketDetail(table.protocol, state) }
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
        var fdReadlinkFailureCount = 0
        val socketInodes = mutableSetOf<String>()
        val protocolCounts = mutableMapOf<String, Int>()
        val stateCounts = mutableMapOf<String, Int>()

        fun observe(target: String?, socketDetails: Map<String, SocketDetail>) {
            if (target == null) fdReadlinkFailureCount++
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
            fdReadlinkFailureCount = fdReadlinkFailureCount,
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
    consecutiveHighSamples: Int,
    rapidGrowth: Boolean = false
): FdPressureDecision {
    val ratio = if (fdCount != null && fdSoftLimit != null && fdSoftLimit > 0L) {
        fdCount.toDouble() / fdSoftLimit.toDouble()
    } else {
        null
    }
    val absoluteFallbackExceeded = fdSoftLimit == null &&
        fdCount != null && fdCount >= FD_ABSOLUTE_RECOVERY_COUNT
    return when {
        ratio != null && ratio >= FD_EMERGENCY_RATIO -> FdPressureDecision(
            FdPressureLevel.EMERGENCY,
            FD_WARNING_SAMPLE_INTERVAL_MS,
            shouldClassify = true,
            shouldRecover = true
        )
        rapidGrowth || absoluteFallbackExceeded -> FdPressureDecision(
            FdPressureLevel.RECOVERY,
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
    private var consecutiveRapidGrowthSamples = 0
    private var recoverySuppressedUntilMs = 0L

    fun observe(sample: DiagnosticResourceSample): FdPressureDecision {
        resetForPid(sample.pid)
        val count = sample.fdCount
        val growth = recordSample(sample.elapsedRealtimeMs, count)
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
        val rapidGrowth = isRapidGrowth(sample.elapsedRealtimeMs, count)
        consecutiveRapidGrowthSamples = if (rapidGrowth) consecutiveRapidGrowthSamples + 1 else 0
        val sustainedRapidGrowth = rapidGrowth &&
            consecutiveRapidGrowthSamples >= FD_RAPID_GROWTH_REQUIRED_SAMPLES &&
            (count ?: 0) >= rapidRecoveryFloor(sample.fdSoftLimit)
        val decision = evaluateFdPressure(
            count, sample.fdSoftLimit, growth, consecutiveHighSamples, sustainedRapidGrowth
        )
        return applyRecoveryGuards(decision, rapidGrowth, sample.elapsedRealtimeMs)
    }

    fun markRecoveryStarted(elapsedRealtimeMs: Long) {
        samples.clear()
        consecutiveHighSamples = 0
        consecutiveRapidGrowthSamples = 0
        recoverySuppressedUntilMs = elapsedRealtimeMs + FD_RECOVERY_COOLDOWN_MS
    }

    private fun resetForPid(currentPid: Int) {
        if (pid == currentPid) return
        samples.clear()
        consecutiveHighSamples = 0
        consecutiveRapidGrowthSamples = 0
        recoverySuppressedUntilMs = 0L
        pid = currentPid
    }

    private fun recordSample(elapsedRealtimeMs: Long, count: Int?): Int {
        count ?: return 0
        samples.addLast(elapsedRealtimeMs to count)
        while (samples.size > 1 && elapsedRealtimeMs - samples.first().first > FD_GROWTH_WINDOW_MS) {
            samples.removeFirst()
        }
        return if (samples.size >= 2) samples.last().second - samples.first().second else 0
    }

    private fun isRapidGrowth(elapsedRealtimeMs: Long, count: Int?): Boolean {
        count ?: return false
        val baseline = samples.firstOrNull { point ->
            elapsedRealtimeMs - point.first <= FD_RAPID_GROWTH_WINDOW_MS
        } ?: return false
        return count >= FD_RAPID_GROWTH_MIN_COUNT && count - baseline.second >= FD_RAPID_GROWTH_THRESHOLD
    }

    private fun rapidRecoveryFloor(fdSoftLimit: Long?): Int {
        val ratioFloor = fdSoftLimit
            ?.takeIf { it > 0L }
            ?.let { (it * FD_RAPID_GROWTH_RECOVERY_RATIO).coerceAtMost(FD_ABSOLUTE_RECOVERY_COUNT.toDouble()) }
            ?.toInt()
        return ratioFloor?.coerceAtLeast(FD_RAPID_GROWTH_MIN_COUNT) ?: FD_ABSOLUTE_RECOVERY_COUNT
    }

    private fun applyRecoveryGuards(
        decision: FdPressureDecision,
        rapidGrowth: Boolean,
        elapsedRealtimeMs: Long
    ): FdPressureDecision {
        val classified = if (rapidGrowth && decision.level < FdPressureLevel.WARNING) {
            FdPressureDecision(
                level = FdPressureLevel.WARNING,
                sampleIntervalMs = FD_WARNING_SAMPLE_INTERVAL_MS,
                shouldClassify = true,
                shouldRecover = false
            )
        } else {
            decision
        }
        return if (elapsedRealtimeMs < recoverySuppressedUntilMs &&
            classified.shouldRecover && classified.level != FdPressureLevel.EMERGENCY
        ) {
            classified.copy(
                level = FdPressureLevel.WARNING,
                shouldClassify = true,
                shouldRecover = false
            )
        } else {
            classified
        }
    }
}

internal class ResourceRecoveryBudgetHealthTracker(
    private val healthyWindowMs: Long = RESOURCE_RECOVERY_BUDGET_HEALTHY_RESET_MS
) {
    private var pid: Int? = null
    private var healthySinceMs: Long? = null
    private var resetEmitted = false

    init {
        require(healthyWindowMs > 0L)
    }

    fun observe(sample: DiagnosticResourceSample, level: FdPressureLevel): Boolean {
        if (pid != sample.pid) {
            pid = sample.pid
            healthySinceMs = null
            resetEmitted = false
        }
        if (level != FdPressureLevel.NORMAL || sample.fdCount == null) {
            healthySinceMs = null
            resetEmitted = false
            return false
        }
        val healthySince = healthySinceMs ?: sample.elapsedRealtimeMs.also { healthySinceMs = it }
        if (resetEmitted || sample.elapsedRealtimeMs - healthySince < healthyWindowMs) return false
        resetEmitted = true
        return true
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
    fun connectionAttributionSnapshot(): ConnectionAttributionSnapshot
    fun restartCore(reason: String, attemptId: Long): Boolean
    fun recycleProcess(reason: String)
    fun publishBudgetExhausted(reason: String)
}

private data class ResourceRecoverySuccessor(
    val registration: ResourceGuardRegistration,
    val owner: ResourceGuardOwner,
    val sampler: DiagnosticResourceSampler,
    val history: DiagnosticResourceHistory
)

private data class ActiveResourceRecovery(
    val attemptId: Long,
    val registration: ResourceGuardRegistration,
    val reason: String,
    val owner: ResourceGuardOwner,
    val sampler: DiagnosticResourceSampler,
    val history: DiagnosticResourceHistory,
    val successorSignal: CompletableDeferred<Unit> = CompletableDeferred(),
    var successorResolved: Boolean = false,
    var successorResult: ResourceRecoverySuccessor? = null,
    var job: Job? = null
)

internal object BackgroundResourceGuard {
    private const val TAG = "BackgroundResourceGuard"
    private val lock = Any()
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = ResourceRecoveryGate()
    private var registration: ResourceGuardRegistration? = null
    private var monitorJob: Job? = null
    private var recovery: ActiveResourceRecovery? = null
    private var owner: ResourceGuardOwner? = null
    private var sampler: DiagnosticResourceSampler? = null
    private var history: DiagnosticResourceHistory? = null

    @Volatile
    private var recovering = false

    @Suppress("CognitiveComplexMethod")
    fun start(
        context: Context,
        scope: CoroutineScope,
        registration: ResourceGuardRegistration,
        owner: ResourceGuardOwner
    ) {
        if (synchronized(lock) { gate.isCurrent(registration) && monitorJob?.isActive == true }) return

        val activeSampler = DiagnosticResourceSampler(context)
        val activeHistory = DiagnosticResourceHistory(context)
        val newMonitorJob = scope.launch(start = CoroutineStart.LAZY) {
            monitor(registration, activeSampler, activeHistory, owner)
        }
        var installMonitor = false
        var oldMonitorJob: Job? = null
        var recoveryToCancel: ActiveResourceRecovery? = null
        var successorSignal: CompletableDeferred<Unit>? = null
        synchronized(lock) {
            if (gate.isCurrent(registration) && monitorJob?.isActive == true) return@synchronized

            val registerResult = gate.register(registration)
            if (registerResult.rejected) return@synchronized

            oldMonitorJob = monitorJob
            recoveryToCancel = removeRecoveryLocked(registerResult.cancelledAttemptId)

            this.registration = registration
            this.owner = owner
            sampler = activeSampler
            history = activeHistory

            registerResult.successorAttemptId?.let { attemptId ->
                val activeRecovery = recovery
                if (activeRecovery?.attemptId == attemptId) {
                    if (activeRecovery.successorResolved) {
                        gate.finish(attemptId)
                        recoveryToCancel = removeRecoveryLocked(attemptId)
                    } else {
                        activeRecovery.successorResolved = true
                        activeRecovery.successorResult = ResourceRecoverySuccessor(
                            registration = registration,
                            owner = owner,
                            sampler = activeSampler,
                            history = activeHistory
                        )
                        successorSignal = activeRecovery.successorSignal
                    }
                } else {
                    gate.finish(attemptId)
                }
            }

            monitorJob = newMonitorJob
            installMonitor = true
        }

        oldMonitorJob?.cancel()
        cancelRecoveryOutsideLock(recoveryToCancel)
        successorSignal?.complete(Unit)
        if (installMonitor && synchronized(lock) {
                monitorJob === newMonitorJob && gate.isCurrent(registration)
            }
        ) {
            newMonitorJob.start()
        } else {
            newMonitorJob.cancel()
        }
    }

    fun detach(registration: ResourceGuardRegistration, handoffAttemptId: Long) {
        var oldMonitorJob: Job? = null
        var recoveryToCancel: ActiveResourceRecovery? = null
        synchronized(lock) {
            val result = gate.detach(registration, handoffAttemptId)
            if (!result.detached) return

            oldMonitorJob = monitorJob
            monitorJob = null
            this.registration = null
            owner = null
            sampler = null
            history = null
            recoveryToCancel = removeRecoveryLocked(result.cancelledAttemptId)
        }
        oldMonitorJob?.cancel()
        cancelRecoveryOutsideLock(recoveryToCancel)
    }

    fun cancelOwner(ownerId: Any) {
        var oldMonitorJob: Job? = null
        var recoveryToCancel: ActiveResourceRecovery? = null
        synchronized(lock) {
            val result = gate.cancelOwner(ownerId)
            if (result.registrationCancelled) {
                oldMonitorJob = monitorJob
                monitorJob = null
                registration = null
                owner = null
                sampler = null
                history = null
            }
            recoveryToCancel = removeRecoveryLocked(result.cancelledAttemptId)
        }
        oldMonitorJob?.cancel()
        cancelRecoveryOutsideLock(recoveryToCancel)
    }

    fun isRecovering(): Boolean = recovering

    fun signalResourceExhaustion(registration: ResourceGuardRegistration, reason: String) {
        val candidate = synchronized(lock) {
            if (!gate.isCurrent(registration)) return
            val activeOwner = owner ?: return
            val activeSampler = sampler ?: return
            activeOwner to activeSampler
        }
        val sample = runCatching {
            candidate.second.captureCurrentFdPressure()
                .attachConnectionAttribution(candidate.first)
        }.getOrNull()
        requestRecovery(registration, reason, sample)
    }

    suspend fun failSuccessorAndAwait(ownerId: Any, attemptId: Long?) {
        if (attemptId == null) return
        var successorSignal: CompletableDeferred<Unit>? = null
        val recoveryJob = synchronized(lock) {
            if (!gate.isAttemptCurrent(ownerId, attemptId, ResourceRecoveryPhase.AWAITING_SUCCESSOR)) {
                return@synchronized null
            }
            recovery
                ?.takeIf { it.attemptId == attemptId }
                ?.also { activeRecovery ->
                    if (!activeRecovery.successorResolved) {
                        activeRecovery.successorResolved = true
                        activeRecovery.successorResult = null
                        successorSignal = activeRecovery.successorSignal
                    }
                }
                ?.job
        }
        successorSignal?.complete(Unit)
        recoveryJob?.join()
    }

    fun isRecoveryAttemptActive(ownerId: Any, attemptId: Long): Boolean = synchronized(lock) {
        gate.isAttemptCurrent(ownerId, attemptId)
    }

    @Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod", "NestedBlockDepth")
    private suspend fun monitor(
        registration: ResourceGuardRegistration,
        activeSampler: DiagnosticResourceSampler,
        activeHistory: DiagnosticResourceHistory,
        activeOwner: ResourceGuardOwner
    ) {
        val tracker = ResourceFdTracker()
        val budgetHealthTracker = ResourceRecoveryBudgetHealthTracker()
        var lastFullSampleAtMs: Long? = null
        var lastBreakdownAtMs: Long? = null
        var previousLevel = FdPressureLevel.NORMAL
        while (kotlin.coroutines.coroutineContext.isActive && isCurrent(registration)) {
            try {
                val pressureSample = activeSampler.captureCurrentFdPressure()
                val decision = tracker.observe(pressureSample)
                if (budgetHealthTracker.observe(pressureSample, decision.level)) {
                    VpnStateStore.resetResourceRecoveryBudget()
                    LogRepository.getInstance().addAlwaysLog(
                        "INFO recovery resource_exhausted stage=budget_reset result=healthy_fd_window"
                    )
                }
                val levelChanged = decision.level != previousLevel
                if (decision.shouldRecover) {
                    val attributedPressure = pressureSample.attachConnectionAttribution(activeOwner)
                    if (startImmediateFdRecovery(registration, activeHistory, attributedPressure, decision)) {
                        tracker.markRecoveryStarted(pressureSample.elapsedRealtimeMs)
                    }
                    previousLevel = decision.level
                    delay(decision.sampleIntervalMs)
                    continue
                }
                val breakdownDue = decision.shouldClassify && (
                    levelChanged || lastBreakdownAtMs == null ||
                        pressureSample.elapsedRealtimeMs - checkNotNull(lastBreakdownAtMs) >= FD_BREAKDOWN_INTERVAL_MS
                    )
                val fullSampleDue = lastFullSampleAtMs == null || breakdownDue ||
                    pressureSample.elapsedRealtimeMs - checkNotNull(lastFullSampleAtMs) >= FD_FULL_SAMPLE_INTERVAL_MS
                val rawSample = if (fullSampleDue) {
                    activeSampler.captureCurrentProcess(includeFdBreakdown = breakdownDue)
                } else {
                    pressureSample
                }
                val sample = if (fullSampleDue || levelChanged) {
                    rawSample.attachConnectionAttribution(activeOwner)
                } else {
                    rawSample
                }
                if (fullSampleDue) {
                    runCatching { activeHistory.append(sample) }
                        .onFailure { Log.w(TAG, "Failed to persist resource sample: ${it.message}") }
                    lastFullSampleAtMs = sample.elapsedRealtimeMs
                    if (sample.fdBreakdown != null) lastBreakdownAtMs = sample.elapsedRealtimeMs
                }
                if (levelChanged || fullSampleDue) logSample(sample, decision)
                previousLevel = decision.level
                delay(decision.sampleIntervalMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Resource monitor sample failed", e)
                delay(FD_NORMAL_SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun startImmediateFdRecovery(
        registration: ResourceGuardRegistration,
        activeHistory: DiagnosticResourceHistory,
        pressureSample: DiagnosticResourceSample,
        decision: FdPressureDecision
    ): Boolean {
        runCatching { activeHistory.append(pressureSample) }
            .onFailure { Log.w(TAG, "Failed to persist recovery pressure sample: ${it.message}") }
        logSample(pressureSample, decision)
        return requestRecovery(
            registration,
            "fd_${decision.level.name.lowercase(Locale.US)}",
            pressureSample
        )
    }

    @Suppress("CyclomaticComplexMethod")
    private fun requestRecovery(
        registration: ResourceGuardRegistration,
        reason: String,
        before: DiagnosticResourceSample?
    ): Boolean {
        val candidate = synchronized(lock) {
            val activeOwner = owner ?: return false
            val activeSampler = sampler ?: return false
            val activeHistory = history ?: return false
            if (!gate.isCurrent(registration) || recovery != null) return false
            ResourceRecoverySuccessor(registration, activeOwner, activeSampler, activeHistory)
        }
        if (!candidate.owner.isRecoveryAllowed()) return false

        val activeRecovery = synchronized(lock) {
            if (!isRecoveryCandidateCurrentLocked(registration, candidate)) return false
            val attemptId = gate.beginRecovery(registration) ?: return false
            ActiveResourceRecovery(
                attemptId = attemptId,
                registration = registration,
                reason = reason,
                owner = candidate.owner,
                sampler = candidate.sampler,
                history = candidate.history
            ).also {
                recovery = it
                recovering = true
            }
        }
        val job = recoveryScope.launch(start = CoroutineStart.LAZY) {
            try {
                recover(activeRecovery, before)
            } finally {
                finishRecovery(activeRecovery.attemptId)
            }
        }
        val shouldStart = synchronized(lock) {
            if (recovery !== activeRecovery ||
                !gate.isAttemptCurrent(registration.ownerId, activeRecovery.attemptId)
            ) {
                false
            } else {
                activeRecovery.job = job
                true
            }
        }
        if (shouldStart) {
            job.start()
        } else {
            job.cancel()
        }
        return shouldStart
    }

    private fun isRecoveryCandidateCurrentLocked(
        registration: ResourceGuardRegistration,
        candidate: ResourceRecoverySuccessor
    ): Boolean = when {
        !gate.isCurrent(registration) -> false
        recovery != null -> false
        owner !== candidate.owner -> false
        sampler !== candidate.sampler -> false
        history !== candidate.history -> false
        else -> true
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod", "ReturnCount")
    private suspend fun recover(
        activeRecovery: ActiveResourceRecovery,
        before: DiagnosticResourceSample?
    ) {
        val attemptId = activeRecovery.attemptId
        val sourceRegistration = activeRecovery.registration
        val activeOwner = activeRecovery.owner
        if (!activeOwner.isRecoveryAllowed() ||
            !isAttemptCurrent(activeRecovery, ResourceRecoveryPhase.RESETTING)
        ) {
            return
        }
        val diagnosticBefore = captureRecoveryDiagnostic(activeRecovery, before)

        val awaitingSuccessor = synchronized(lock) {
            gate.isAttemptCurrent(
                sourceRegistration.ownerId,
                attemptId,
                ResourceRecoveryPhase.RESETTING
            ) && gate.awaitSuccessor(sourceRegistration, attemptId)
        }
        if (!awaitingSuccessor) return

        val restartAllowed = VpnStateStore.tryConsumeResourceRecovery(
            VpnStateStore.ResourceRecoveryAction.CORE_RESTART
        )
        if (!isAttemptCurrent(activeRecovery, ResourceRecoveryPhase.AWAITING_SUCCESSOR)) return
        if (!restartAllowed) {
            recycleProcessIfAllowed(activeRecovery, activeOwner)
            return
        }
        if (!activeOwner.isRecoveryAllowed() ||
            !isAttemptCurrent(activeRecovery, ResourceRecoveryPhase.AWAITING_SUCCESSOR)
        ) {
            return
        }
        val restartIssued = activeOwner.restartCore(
            "resource_exhausted:${activeRecovery.reason}",
            attemptId
        )
        if (!isAttemptCurrent(activeRecovery)) return
        logRecovery("restart_core", diagnosticBefore?.fdCount, null, "issued=$restartIssued global_close=skipped")
        if (!restartIssued) {
            recycleProcessIfAllowed(activeRecovery, activeOwner)
            return
        }

        val successorSignalled = withTimeoutOrNull(RESOURCE_CORE_RESTART_SUCCESSOR_TIMEOUT_MS) {
            activeRecovery.successorSignal.await()
            true
        } == true
        if (!successorSignalled) {
            recycleProcessIfAllowed(activeRecovery, activeOwner)
            return
        }
        val successor = synchronized(lock) {
            if (!gate.isAttemptCurrent(sourceRegistration.ownerId, attemptId) ||
                !activeRecovery.successorResolved
            ) {
                return
            }
            activeRecovery.successorResult?.also { resolvedSuccessor ->
                if (!gate.isAttemptCurrent(
                        sourceRegistration.ownerId,
                        attemptId,
                        ResourceRecoveryPhase.OBSERVING_SUCCESSOR
                    ) || !gate.isCurrent(resolvedSuccessor.registration)
                ) {
                    return
                }
            }
        }
        if (successor == null) {
            recycleProcessIfAllowed(activeRecovery, activeOwner)
            return
        }
        if (!successor.owner.isRecoveryAllowed() || !isSuccessorCurrent(activeRecovery, successor)) return

        delay(RESOURCE_CORE_RESTART_OBSERVE_MS)
        if (!successor.owner.isRecoveryAllowed() || !isSuccessorCurrent(activeRecovery, successor)) {
            return
        }

        val afterRestart = runCatching {
            successor.sampler.captureCurrentProcess(includeFdBreakdown = true)
                .attachConnectionAttribution(successor.owner)
        }.getOrNull()
        if (!isSuccessorCurrent(activeRecovery, successor) || !successor.owner.isRecoveryAllowed()) {
            return
        }
        afterRestart?.let { sample ->
            runCatching { successor.history.append(sample) }
            logResourceDetails(sample)
        }
        if (!isSuccessorCurrent(activeRecovery, successor)) return
        if (!isFdRecoverySufficient(
                diagnosticBefore?.fdCount,
                afterRestart?.fdCount,
                afterRestart?.fdSoftLimit
            )
        ) {
            logRecovery(
                "restart_core",
                diagnosticBefore?.fdCount,
                afterRestart?.fdCount,
                "insufficient deferred_to_next_pressure"
            )
        } else {
            logRecovery("restart_core", diagnosticBefore?.fdCount, afterRestart?.fdCount, "success")
        }
    }

    private fun captureRecoveryDiagnostic(
        activeRecovery: ActiveResourceRecovery,
        fallback: DiagnosticResourceSample?
    ): DiagnosticResourceSample? {
        val sample = runCatching {
            activeRecovery.sampler.captureCurrentProcess(includeFdBreakdown = true)
                .attachConnectionAttribution(activeRecovery.owner)
        }.getOrNull() ?: fallback
        sample?.let { captured ->
            runCatching { activeRecovery.history.append(captured) }
            logResourceDetails(captured)
        }
        return sample
    }

    private fun recycleProcessIfAllowed(
        activeRecovery: ActiveResourceRecovery,
        activeOwner: ResourceGuardOwner
    ) {
        if (!activeOwner.isRecoveryAllowed()) return
        val claimed = synchronized(lock) {
            gate.claimProcessReclaim(activeRecovery.registration.ownerId, activeRecovery.attemptId)
        }
        if (!claimed) return
        val reclaimAllowed = VpnStateStore.tryConsumeResourceRecovery(
            VpnStateStore.ResourceRecoveryAction.PROCESS_RECLAIM
        )
        if (!isAttemptCurrent(activeRecovery, ResourceRecoveryPhase.RECLAIM_CLAIMED) ||
            !activeOwner.isRecoveryAllowed() ||
            !isAttemptCurrent(activeRecovery, ResourceRecoveryPhase.RECLAIM_CLAIMED)
        ) {
            return
        }
        val reason = activeRecovery.reason
        if (reclaimAllowed) {
            activeOwner.recycleProcess("resource_exhausted:$reason")
        } else {
            activeOwner.publishBudgetExhausted("process_reclaim:$reason")
        }
    }

    private fun finishRecovery(attemptId: Long) {
        synchronized(lock) {
            if (recovery?.attemptId != attemptId) return
            gate.finish(attemptId)
            recovery = null
            recovering = false
        }
    }

    private fun removeRecoveryLocked(attemptId: Long?): ActiveResourceRecovery? {
        if (attemptId == null) return null
        val activeRecovery = recovery?.takeIf { it.attemptId == attemptId } ?: return null
        recovery = null
        recovering = false
        return activeRecovery
    }

    private fun cancelRecoveryOutsideLock(activeRecovery: ActiveResourceRecovery?) {
        activeRecovery ?: return
        activeRecovery.successorSignal.cancel()
        activeRecovery.job?.cancel()
    }

    private fun isCurrent(registration: ResourceGuardRegistration): Boolean = synchronized(lock) {
        gate.isCurrent(registration)
    }

    private fun isAttemptCurrent(
        activeRecovery: ActiveResourceRecovery,
        phase: ResourceRecoveryPhase? = null
    ): Boolean = synchronized(lock) {
        gate.isAttemptCurrent(
            activeRecovery.registration.ownerId,
            activeRecovery.attemptId,
            phase
        )
    }

    private fun isSuccessorCurrent(
        activeRecovery: ActiveResourceRecovery,
        successor: ResourceRecoverySuccessor
    ): Boolean = synchronized(lock) {
        gate.isAttemptCurrent(
            activeRecovery.registration.ownerId,
            activeRecovery.attemptId,
            ResourceRecoveryPhase.OBSERVING_SUCCESSOR
        ) && gate.isCurrent(successor.registration)
    }

    private fun logSample(sample: DiagnosticResourceSample, decision: FdPressureDecision) {
        if (decision.level == FdPressureLevel.NORMAL) return
        val metric = "METRIC resource_fd process=${sample.processName.substringAfter(':', sample.processName)} " +
            "pid=${sample.pid} count=${sample.fdCount ?: -1} soft_limit=${sample.fdSoftLimit ?: -1} " +
            "ratio=${sample.fdRatio?.let { String.format(Locale.US, "%.3f", it) } ?: "unknown"} " +
            "level=${decision.level.name.lowercase(Locale.US)}"
        LogRepository.getInstance().addAlwaysLog(metric)
        logResourceDetails(sample)
    }

    private fun logResourceDetails(sample: DiagnosticResourceSample) {
        sample.fdBreakdown?.let { breakdown ->
            LogRepository.getInstance().addAlwaysLog(
                "METRIC resource_fd_breakdown socket=${breakdown.socketCount} " +
                    "unique_socket=${breakdown.socketUniqueCount} " +
                    "raw=${breakdown.rawCount + breakdown.raw6Count} " +
                    "udp=${breakdown.udpCount + breakdown.udp6Count} " +
                    "tcp=${breakdown.tcpCount + breakdown.tcp6Count} anon_inode=${breakdown.anonInodeCount} " +
                    "unix=${breakdown.unixCount} netlink=${breakdown.netlinkCount} " +
                    "packet=${breakdown.packetCount} socket_unknown=${breakdown.socketUnknownCount} " +
                    "fd_readlink_failures=${breakdown.fdReadlinkFailureCount} " +
                    "pipe=${breakdown.pipeCount} file=${breakdown.ordinaryFileCount} " +
                    "device=${breakdown.deviceCount} unknown=${breakdown.unknownCount} " +
                    "table_failures=${breakdown.socketTableFailures.ifBlank { "none" }}"
            )
        }
        buildSocketAttributionDiagnosticLines(sample).forEach { line ->
            LogRepository.getInstance().addAlwaysLog(line)
        }
    }

    private fun DiagnosticResourceSample.attachConnectionAttribution(
        activeOwner: ResourceGuardOwner
    ): DiagnosticResourceSample {
        val snapshot = runCatching(activeOwner::connectionAttributionSnapshot).getOrNull() ?: return this
        val nativeSocketCount = fdBreakdown?.socketUniqueCount
        val socketDelta = nativeSocketCount?.minus(snapshot.activeConnections)
        return copy(
            libboxActiveConnections = snapshot.activeConnections,
            nativeLibboxSocketDelta = socketDelta,
            nativePreConnectGap = socketDelta?.coerceAtLeast(0),
            socketAttributionStatus = classifySocketAttribution(nativeSocketCount, snapshot.activeConnections),
            connectionAttribution = snapshot
        )
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
private const val NATIVE_PRECONNECT_GAP_MINIMUM = 64
private const val NATIVE_PRECONNECT_SOCKET_MINIMUM = 128
internal const val FD_NORMAL_SAMPLE_INTERVAL_MS = 1_000L
internal const val FD_OBSERVE_SAMPLE_INTERVAL_MS = 1_000L
internal const val FD_WARNING_SAMPLE_INTERVAL_MS = 1_000L
internal const val FD_FULL_SAMPLE_INTERVAL_MS = 60_000L
internal const val FD_BREAKDOWN_INTERVAL_MS = 5_000L
internal const val FD_GROWTH_WINDOW_MS = 5 * 60_000L
internal const val FD_FIVE_MINUTE_GROWTH_WARNING = 1_024
internal const val FD_RAPID_GROWTH_WINDOW_MS = 5_000L
internal const val FD_RAPID_GROWTH_THRESHOLD = 512
internal const val FD_RAPID_GROWTH_MIN_COUNT = 1_024
internal const val FD_RAPID_GROWTH_REQUIRED_SAMPLES = 3
internal const val FD_RAPID_GROWTH_RECOVERY_RATIO = 0.50
internal const val FD_ABSOLUTE_RECOVERY_COUNT = 16_384
internal const val FD_RECOVERY_COOLDOWN_MS = 10_000L
internal const val FD_OBSERVE_RATIO = 0.50
internal const val FD_WARNING_RATIO = 0.70
internal const val FD_RECOVERY_RATIO = 0.85
internal const val FD_EMERGENCY_RATIO = 0.95
private const val RESOURCE_CORE_RESTART_OBSERVE_MS = 5_000L
private const val RESOURCE_CORE_RESTART_SUCCESSOR_TIMEOUT_MS = 30_000L
internal const val RESOURCE_RECOVERY_BUDGET_HEALTHY_RESET_MS = 5 * 60_000L

private val CSV_SPECIAL_CHARACTERS = setOf(',', '"', '\n', '\r')
