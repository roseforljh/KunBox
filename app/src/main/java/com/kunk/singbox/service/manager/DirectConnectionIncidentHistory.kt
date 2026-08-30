package com.kunk.singbox.service.manager

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal data class DirectConnectionIncident(
    val timestampEpochMs: Long,
    val connectionId: String,
    val uid: Int?,
    val packageNames: List<String>,
    val outbound: String?,
    val chain: List<String>,
    val routeRuleHash: String?,
    val domainHash: String?,
    val destinationHash: String?,
    val routeRuleSemantic: String,
    val attributionStatus: String
)

internal class DirectConnectionIncidentHistory(
    private val historyFile: File,
    private val maxSnapshots: Int = MAX_DIRECT_INCIDENTS,
    saltValue: String = java.util.UUID.randomUUID().toString()
) {
    constructor(context: Context, saltValue: String = java.util.UUID.randomUUID().toString()) : this(
        File(context.filesDir, DIRECT_INCIDENT_RELATIVE_PATH),
        saltValue = saltValue
    )

    private val gson = Gson()
    private val seenConnectionIds = ConcurrentHashMap.newKeySet<String>()
    private val salt = saltValue.toByteArray(Charsets.UTF_8)

    @Synchronized
    fun recordNew(events: List<ConnectionTrafficEventData>): List<DirectConnectionIncident> {
        val incidents = events.asSequence()
            .filter { it.type == ConnectionTrafficAttributor.EVENT_NEW }
            .filter { event -> event.outbound == "direct" || "direct" in event.chain || "direct" in event.tags }
            .filter { seenConnectionIds.add(it.id) }
            .map(::toIncident)
            .toList()
        if (incidents.isEmpty()) return emptyList()
        val retained = (read() + incidents).takeLast(maxSnapshots)
        writeAtomically(retained)
        return incidents
    }

    @Synchronized
    fun read(): List<DirectConnectionIncident> {
        if (!historyFile.isFile) return emptyList()
        return historyFile.useLines(Charsets.UTF_8) { lines ->
            lines.filter(String::isNotBlank)
                .mapNotNull { line ->
                    runCatching { gson.fromJson(line, DirectConnectionIncident::class.java) }.getOrNull()
                }
                .toList()
        }
    }

    private fun toIncident(event: ConnectionTrafficEventData): DirectConnectionIncident {
        return DirectConnectionIncident(
            timestampEpochMs = System.currentTimeMillis(),
            connectionId = event.id,
            uid = event.uid,
            packageNames = event.packageNames,
            outbound = event.outbound,
            chain = event.chain,
            routeRuleHash = hash(event.routeRule),
            domainHash = hash(event.domain),
            destinationHash = hash(event.destination),
            routeRuleSemantic = event.routeRuleSemantic,
            attributionStatus = event.attributionStatus
        )
    }

    private fun hash(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return digest.digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun writeAtomically(incidents: List<DirectConnectionIncident>) {
        val parent = checkNotNull(historyFile.absoluteFile.parentFile)
        check(parent.exists() || parent.mkdirs()) { "无法创建直连诊断目录: ${parent.absolutePath}" }
        val temporary = File(parent, "${historyFile.name}.tmp")
        temporary.writeText(
            incidents.joinToString(separator = "\n", postfix = "\n", transform = gson::toJson),
            Charsets.UTF_8
        )
        if (!temporary.renameTo(historyFile)) {
            temporary.copyTo(historyFile, overwrite = true)
            check(temporary.delete()) { "无法删除直连诊断临时文件: ${temporary.absolutePath}" }
        }
    }
}

internal const val DIRECT_INCIDENT_RELATIVE_PATH = "diagnostics/direct_incidents.jsonl"
internal const val MAX_DIRECT_INCIDENTS = 256
