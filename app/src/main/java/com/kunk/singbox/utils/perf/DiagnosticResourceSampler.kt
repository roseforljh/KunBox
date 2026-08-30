@file:Suppress("TooManyFunctions", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "LongMethod", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeConst")

package com.kunk.singbox.utils.perf

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.kunk.singbox.service.manager.ConnectionAttributionSnapshot
import com.kunk.singbox.utils.VersionInfo
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

internal fun parseCsvLine(line: String): List<String> {
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

internal fun String.toCsvField(): String {
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

internal val processStartEpochClock by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
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

internal fun parseProcStatFields(stat: String, ticksPerSecond: Long): List<String> {
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
