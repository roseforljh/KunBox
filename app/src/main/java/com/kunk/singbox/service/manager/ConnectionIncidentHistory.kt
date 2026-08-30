package com.kunk.singbox.service.manager

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File

internal data class ConnectionIncidentSnapshot(
    val timestampEpochMs: Long,
    val elapsedRealtimeMs: Long,
    val mode: String,
    val reason: String,
    val closeReason: String,
    val closeSucceeded: Boolean,
    val activeConnections: Int,
    val newConnectionsInWindow: Int,
    val creationRatePerSecond: Double,
    val uid: Int?,
    val packageNames: List<String>,
    val inbound: String?,
    val source: String?,
    val outboundCounts: Map<String, Int>,
    val chainCounts: Map<String, Int>,
    val protocolCounts: Map<String, Int>
)

private fun ConnectionIncidentSnapshot.normalized(): ConnectionIncidentSnapshot = copy(
    mode = mode.orEmpty(),
    reason = reason.orEmpty(),
    closeReason = closeReason.orEmpty(),
    packageNames = packageNames.orEmpty(),
    outboundCounts = outboundCounts.orEmpty(),
    chainCounts = chainCounts.orEmpty(),
    protocolCounts = protocolCounts.orEmpty()
)

internal fun ConnectionStormDecision.toIncidentSnapshot(
    mode: String,
    closeReason: String,
    closeSucceeded: Boolean,
    timestampEpochMs: Long,
    elapsedRealtimeMs: Long
): ConnectionIncidentSnapshot = ConnectionIncidentSnapshot(
    timestampEpochMs = timestampEpochMs,
    elapsedRealtimeMs = elapsedRealtimeMs,
    mode = mode,
    reason = reason.name,
    closeReason = closeReason,
    closeSucceeded = closeSucceeded,
    activeConnections = activeConnections,
    newConnectionsInWindow = newConnectionsInWindow,
    creationRatePerSecond = creationRatePerSecond,
    uid = offender?.uid,
    packageNames = offender?.packageNames.orEmpty(),
    inbound = offender?.inbound,
    source = offender?.source,
    outboundCounts = outboundCounts,
    chainCounts = chainCounts,
    protocolCounts = protocolCounts
)

internal fun formatConnectionIncidentSnapshotsJsonl(snapshots: List<ConnectionIncidentSnapshot>): String {
    if (snapshots.isEmpty()) return ""
    val gson = Gson()
    return snapshots.joinToString(separator = "\n", postfix = "\n") { snapshot ->
        gson.toJson(
            JsonObject().apply {
                addProperty("timestamp_epoch_ms", snapshot.timestampEpochMs)
                addProperty("elapsed_realtime_ms", snapshot.elapsedRealtimeMs)
                addProperty("mode", snapshot.mode)
                addProperty("reason", snapshot.reason)
                addProperty("close_reason", snapshot.closeReason)
                addProperty("close_succeeded", snapshot.closeSucceeded)
                addProperty("active_connections", snapshot.activeConnections)
                addProperty("new_connections_window", snapshot.newConnectionsInWindow)
                addProperty("creation_rate_per_second", snapshot.creationRatePerSecond)
                snapshot.uid?.let { addProperty("uid", it) }
                add("package_names", gson.toJsonTree(snapshot.packageNames))
                snapshot.inbound?.let { addProperty("inbound", it) }
                snapshot.source?.let { addProperty("source", it) }
                add("outbounds", snapshot.outboundCounts.toCountArray("outbound"))
                add("chains", snapshot.chainCounts.toCountArray("chain"))
                add("protocols", snapshot.protocolCounts.toCountArray("protocol"))
            }
        )
    }
}

private fun Map<String, Int>.toCountArray(labelKey: String): JsonArray = JsonArray().also { array ->
    forEach { (label, count) ->
        array.add(
            JsonObject().apply {
                addProperty(labelKey, label)
                addProperty("count", count)
            }
        )
    }
}

internal class ConnectionIncidentHistory(
    private val historyFile: File,
    private val maxSnapshots: Int = MAX_CONNECTION_INCIDENT_SNAPSHOTS
) {
    constructor(
        context: Context,
        maxSnapshots: Int = MAX_CONNECTION_INCIDENT_SNAPSHOTS
    ) : this(File(context.filesDir, CONNECTION_INCIDENT_RELATIVE_PATH), maxSnapshots)

    private val gson = Gson()

    init {
        require(maxSnapshots > 0)
    }

    @Synchronized
    fun append(snapshot: ConnectionIncidentSnapshot) {
        val retained = (read() + snapshot)
            .sortedWith(
                compareBy(
                    ConnectionIncidentSnapshot::timestampEpochMs,
                    ConnectionIncidentSnapshot::elapsedRealtimeMs
                )
            )
            .takeLast(maxSnapshots)
        writeAtomically(retained)
    }

    @Synchronized
    fun read(): List<ConnectionIncidentSnapshot> {
        if (!historyFile.isFile) return emptyList()
        return historyFile.useLines(Charsets.UTF_8) { lines ->
            lines.filter(String::isNotBlank)
                .mapNotNull { line ->
                    runCatching {
                        gson.fromJson(line, ConnectionIncidentSnapshot::class.java)?.normalized()
                    }.getOrNull()
                }
                .toList()
        }
    }

    private fun writeAtomically(snapshots: List<ConnectionIncidentSnapshot>) {
        val parent = checkNotNull(historyFile.absoluteFile.parentFile)
        check(parent.exists() || parent.mkdirs()) { "无法创建连接诊断目录: ${parent.absolutePath}" }
        val temporary = File(parent, "${historyFile.name}.tmp")
        temporary.writeText(
            snapshots.joinToString(separator = "\n", postfix = "\n", transform = gson::toJson),
            Charsets.UTF_8
        )
        if (!temporary.renameTo(historyFile)) {
            temporary.copyTo(historyFile, overwrite = true)
            check(temporary.delete()) { "无法删除连接诊断临时文件: ${temporary.absolutePath}" }
        }
    }
}

internal const val CONNECTION_INCIDENT_RELATIVE_PATH = "diagnostics/connection_incidents.jsonl"
internal const val MAX_CONNECTION_INCIDENT_SNAPSHOTS = 256
