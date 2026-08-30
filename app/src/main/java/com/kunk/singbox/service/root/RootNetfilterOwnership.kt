package com.kunk.singbox.service.root

import android.util.Log
import com.kunk.singbox.model.RootRoutingConstants
import com.kunk.singbox.model.isRootSha256
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class RootNetfilterOwnerContext(
    val sessionId: String,
    val generation: Long,
    val resolvedPlanSha256: String
)

internal sealed interface RootNetfilterOwnerRecord {
    val sortKey: String

    data class Rule(
        val family: String,
        val mark: String,
        val mask: String,
        val priority: Int,
        val table: Int,
        val protocol: Int,
        val commandSha256: String
    ) : RootNetfilterOwnerRecord {
        override val sortKey: String = "RULE|$family|$priority|$mark"
    }

    data class Route(
        val family: String,
        val prefix: String,
        val device: String,
        val table: Int,
        val protocol: Int,
        val commandSha256: String
    ) : RootNetfilterOwnerRecord {
        override val sortKey: String = "ROUTE|$family|$prefix|$table"
    }

    data class UidRule(
        val family: String,
        val uidRange: String,
        val priority: Int,
        val table: Int,
        val protocol: Int,
        val commandSha256: String
    ) : RootNetfilterOwnerRecord {
        override val sortKey: String = "UID_RULE|$family|$priority|$uidRange"
    }

    data class Chain(
        val family: String,
        val table: String,
        val chain: String,
        val hook: String,
        val rulesSha256: String
    ) : RootNetfilterOwnerRecord {
        override val sortKey: String = "CHAIN|$family|$table|$chain"
    }
}

internal data class RootNetfilterOwnerManifest(
    val context: RootNetfilterOwnerContext,
    val records: List<RootNetfilterOwnerRecord>
)

/**
 * The only Kotlin-side generator for Root netfilter ownership records.
 * The shell cleanup path consumes this exact canonical format.
 */
internal object RootNetfilterOwnership {
    const val RUNTIME_DIR = "/data/adb/kunbox"
    const val OWNER_FILE = "$RUNTIME_DIR/netfilter-owner"
    const val STAGING_FILE = "$RUNTIME_DIR/netfilter-owner.staging"
    const val CONFLICT_FILE = "$RUNTIME_DIR/cleanup_conflict"
    const val CLEANUP_SCRIPT = "$RUNTIME_DIR/cleanup-owned.sh"

    private const val SCHEMA = 1

    fun context(sessionId: String, generation: Long, resolvedPlanSha256: String): RootNetfilterOwnerContext {
        require(
            sessionId.isNotBlank() &&
                sessionId.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        ) {
            "Root netfilter ownership session is invalid"
        }
        require(generation > 0L) { "Root netfilter ownership generation is invalid" }
        require(isRootSha256(resolvedPlanSha256)) { "Root resolved plan digest is invalid" }
        return RootNetfilterOwnerContext(sessionId, generation, resolvedPlanSha256)
    }

    fun fromCommands(
        context: RootNetfilterOwnerContext,
        commands: List<List<String>>
    ): RootNetfilterOwnerManifest {
        val chainRules = linkedMapOf<String, MutableList<String>>()
        val chainMeta = linkedMapOf<String, Triple<String, String, String>>()
        val records = mutableListOf<RootNetfilterOwnerRecord>()
        commands.forEach { command ->
            val binary = command.firstOrNull() ?: return@forEach
            when (binary) {
                "iptables", "ip6tables" -> parseIptablesCommand(command, chainRules, chainMeta)
                    ?.let { records += it }
                "ip" -> parseIpCommand(command)?.let { records += it }
            }
        }
        chainMeta.forEach { (key, value) ->
            val rules = chainRules[key].orEmpty().joinToString("\n")
            records += RootNetfilterOwnerRecord.Chain(
                family = value.first,
                table = value.second,
                chain = value.third,
                hook = chainMetaHook(commands, value.first, value.third),
                rulesSha256 = sha256(rules)
            )
        }
        return RootNetfilterOwnerManifest(context, records.distinctBy { it.sortKey }.sortedBy { it.sortKey })
    }

    fun refreshChainFingerprints(
        manifest: RootNetfilterOwnerManifest,
        executor: RootCommandExecutor
    ): RootNetfilterOwnerManifest {
        val chains = manifest.records.filterIsInstance<RootNetfilterOwnerRecord.Chain>()
        if (chains.isEmpty()) return manifest
        val commands = chains.map { record ->
            val binary = if (record.family == "6") "ip6tables" else "iptables"
            listOf(binary, "-t", record.table, "-S", record.chain)
        }
        val result = executor.executeBatch(commands)
        check(result.success) { "Cannot read owned Root chains: ${result.diagnosticOutput}" }
        val outputLines = result.output.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val fingerprints = chains.associate { record ->
            val declaration = "-N ${record.chain}"
            val rulePrefix = "-A ${record.chain} "
            val live = outputLines.filter { it == declaration || it.startsWith(rulePrefix) }
            check(live.firstOrNull() == declaration) {
                "Cannot read owned Root chain ${record.chain}"
            }
            record.sortKey to sha256(live.joinToString("\n"))
        }
        val refreshed = manifest.records.map { record ->
            if (record !is RootNetfilterOwnerRecord.Chain) return@map record
            record.copy(rulesSha256 = fingerprints.getValue(record.sortKey))
        }
        return manifest.copy(records = refreshed.sortedBy(RootNetfilterOwnerRecord::sortKey))
    }

    fun refreshChainFingerprints(
        manifest: RootNetfilterOwnerManifest,
        snapshot: Map<String, String>
    ): RootNetfilterOwnerManifest {
        val refreshed = manifest.records.map { record ->
            if (record !is RootNetfilterOwnerRecord.Chain) return@map record
            val section = snapshot[if (record.family == "6") ROOT_STATE_IPTABLES6 else ROOT_STATE_IPTABLES4]
                ?: error("Root netfilter snapshot is missing for IPv${record.family}")
            val lines = section.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
            check(lines.any { it.startsWith(":${record.chain} ") }) {
                "Cannot read owned Root chain ${record.chain}"
            }
            val live = buildList {
                add("-N ${record.chain}")
                addAll(lines.filter { it.startsWith("-A ${record.chain} ") })
            }
            record.copy(rulesSha256 = sha256(live.joinToString("\n")))
        }
        return manifest.copy(records = refreshed.sortedBy(RootNetfilterOwnerRecord::sortKey))
    }

    fun writeStaging(manifest: RootNetfilterOwnerManifest, file: File = File(STAGING_FILE)) =
        write(manifest, file)

    fun writeActive(manifest: RootNetfilterOwnerManifest, file: File = File(OWNER_FILE)) =
        write(manifest, file)

    fun read(file: File): RootNetfilterOwnerManifest? {
        check(!Files.isSymbolicLink(file.toPath())) { "Root netfilter owner file cannot be a symbolic link" }
        if (!file.isFile) return null
        val lines = file.readLines(Charsets.UTF_8)
        check(lines.size >= 4) { "Root netfilter owner file is incomplete" }
        check(lines[0] == "schema=$SCHEMA") { "Root netfilter owner schema mismatch" }
        check(lines[1].startsWith("session=")) { "Root netfilter owner session is malformed" }
        check(lines[2].startsWith("generation=")) { "Root netfilter owner generation is malformed" }
        check(lines[3].startsWith("resolved_plan_sha256=")) { "Root netfilter owner digest is malformed" }
        val session = lines[1].substringAfter("session=")
        val generation = lines[2].substringAfter("generation=").toLongOrNull()
        val resolved = lines[3].substringAfter("resolved_plan_sha256=")
        val context = context(session, generation ?: 0L, resolved)
        val records = lines.drop(4).map { parseRecord(it) }
        check(records.map(RootNetfilterOwnerRecord::sortKey).distinct().size == records.size) {
            "Duplicate Root netfilter owner record"
        }
        return RootNetfilterOwnerManifest(context, records.sortedBy(RootNetfilterOwnerRecord::sortKey))
    }

    fun cleanupCommand(expectedSessionId: String? = null): List<String> = buildList {
        add("/system/bin/sh")
        add(CLEANUP_SCRIPT)
        add("cleanup")
        expectedSessionId?.takeIf(String::isNotBlank)?.let(::add)
    }

    fun reservedPolicyTuples(): List<Pair<Int, Int>> = buildList {
        add(RootRoutingConstants.GENERIC_MARK_IPV4 to RootRoutingConstants.GENERIC_PRIORITY_IPV4)
        add(RootRoutingConstants.GENERIC_MARK_IPV6 to RootRoutingConstants.GENERIC_PRIORITY_IPV6)
        repeat(RootRoutingConstants.MAX_LANES) { slot ->
            add(RootRoutingConstants.markIpv4(slot) to RootRoutingConstants.priorityIpv4(slot))
            add(RootRoutingConstants.markIpv6(slot) to RootRoutingConstants.priorityIpv6(slot))
        }
    }

    fun isReservedPolicyLine(line: String): Boolean =
        isReservedMarkPolicyLine(line) || isReservedUidPolicyLine(line)

    fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun write(manifest: RootNetfilterOwnerManifest, file: File) {
        val content = encode(manifest)
        val parent = file.parentFile ?: error("Root owner file has no parent")
        check(!Files.isSymbolicLink(file.toPath())) { "Root netfilter owner file cannot be a symbolic link" }
        check(!Files.isSymbolicLink(parent.toPath())) {
            "Root netfilter owner directory cannot be a symbolic link"
        }
        check(parent.exists() || parent.mkdirs()) { "Cannot create Root owner directory" }
        val temp = File.createTempFile(".${file.name}.", ".tmp", parent)
        try {
            FileOutputStream(temp).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
                runCatching { output.fd.sync() }
            }
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
            runCatching { FileChannel.open(parent.toPath()).use { channel -> channel.force(true) } }
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        } catch (error: Exception) {
            runCatching {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse { throw error }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun encode(manifest: RootNetfilterOwnerManifest): String = buildString {
        append("schema=").append(SCHEMA).append('\n')
        append("session=").append(manifest.context.sessionId).append('\n')
        append("generation=").append(manifest.context.generation).append('\n')
        append("resolved_plan_sha256=").append(manifest.context.resolvedPlanSha256).append('\n')
        manifest.records.sortedBy(RootNetfilterOwnerRecord::sortKey).forEach { record ->
            append(record.encode()).append('\n')
        }
    }

    private fun RootNetfilterOwnerRecord.encode(): String = when (this) {
        is RootNetfilterOwnerRecord.Rule ->
            "RULE|$family|$mark|$mask|$priority|$table|$protocol|$commandSha256"
        is RootNetfilterOwnerRecord.Route ->
            "ROUTE|$family|$prefix|$device|$table|$protocol|$commandSha256"
        is RootNetfilterOwnerRecord.UidRule ->
            "UID_RULE|$family|$uidRange|$priority|$table|$protocol|$commandSha256"
        is RootNetfilterOwnerRecord.Chain ->
            "CHAIN|$family|$table|$chain|$hook|$rulesSha256"
    }

    private fun parseRecord(line: String): RootNetfilterOwnerRecord {
        val fields = line.split('|', limit = 8)
        return when (fields.firstOrNull()) {
            "RULE" -> {
                check(fields.size == 8)
                RootNetfilterOwnerRecord.Rule(
                    family = fields[1], mark = fields[2], mask = fields[3],
                    priority = fields[4].toInt(), table = fields[5].toInt(),
                    protocol = fields[6].toInt(), commandSha256 = fields[7]
                ).also(::validateRule)
            }
            "ROUTE" -> {
                check(fields.size == 7)
                RootNetfilterOwnerRecord.Route(
                    family = fields[1], prefix = fields[2], device = fields[3],
                    table = fields[4].toInt(), protocol = fields[5].toInt(), commandSha256 = fields[6]
                ).also(::validateRoute)
            }
            "UID_RULE" -> {
                check(fields.size == 7)
                RootNetfilterOwnerRecord.UidRule(
                    family = fields[1], uidRange = fields[2], priority = fields[3].toInt(),
                    table = fields[4].toInt(), protocol = fields[5].toInt(), commandSha256 = fields[6]
                ).also(::validateUidRule)
            }
            "CHAIN" -> {
                check(fields.size == 6)
                RootNetfilterOwnerRecord.Chain(
                    family = fields[1], table = fields[2], chain = fields[3],
                    hook = fields[4], rulesSha256 = fields[5]
                ).also(::validateChain)
            }
            else -> error("Unknown Root netfilter owner record")
        }
    }

    @Suppress("ReturnCount")
    private fun parseIptablesCommand(
        command: List<String>,
        chainRules: MutableMap<String, MutableList<String>>,
        chainMeta: MutableMap<String, Triple<String, String, String>>
    ): RootNetfilterOwnerRecord? {
        val family = if (command.first() == "ip6tables") "6" else "4"
        val tableIndex = command.indexOf("-t")
        val table = command.getOrNull(tableIndex + 1) ?: return null
        when {
            "-N" in command -> {
                val chain = command.getOrNull(command.indexOf("-N") + 1) ?: return null
                val key = "$family|$table|$chain"
                chainMeta[key] = Triple(family, table, chain)
                if (chain.startsWith("KBX_")) {
                    chainRules.getOrPut(key) { mutableListOf() } += "-N $chain"
                }
            }

            "-A" in command -> {
                val chain = command.getOrNull(command.indexOf("-A") + 1) ?: return null
                val key = "$family|$table|$chain"
                if (chain.startsWith("KBX_")) {
                    chainRules.getOrPut(key) { mutableListOf() } +=
                        command.drop(command.indexOf("-A")).joinToString(" ")
                }
            }
        }
        return null
    }

    private fun chainMetaHook(commands: List<List<String>>, family: String, chain: String): String =
        commands.firstOrNull { command ->
            (family == "4" && command.firstOrNull() == "iptables" ||
                family == "6" && command.firstOrNull() == "ip6tables") &&
                "-I" in command && "-j" in command && command.getOrNull(command.indexOf("-j") + 1) == chain
        }?.let { command ->
            command.getOrNull(command.indexOf("-I") + 1).orEmpty()
        }.orEmpty()

    @Suppress("CyclomaticComplexMethod")
    private fun parseIpCommand(command: List<String>): RootNetfilterOwnerRecord? {
        if ("rule" in command && "add" in command) {
            command.valueAfter("uidrange")?.let { uidRange ->
                val table = (command.valueAfter("table") ?: return null).toIntOrNull() ?: return null
                val priority = (command.valueAfter("pref") ?: return null).toIntOrNull() ?: return null
                val protocol = command.valueAfter("protocol")?.toIntOrNull() ?: 0
                return RootNetfilterOwnerRecord.UidRule(
                    family = if ("-6" in command) "6" else "4",
                    uidRange = uidRange,
                    priority = priority,
                    table = table,
                    protocol = protocol,
                    commandSha256 = sha256(command.joinToString(" "))
                ).also(::validateUidRule)
            }
            val mark = command.valueAfter("fwmark") ?: return null
            val (markValue, mask) = mark.split('/', limit = 2).let { it[0] to it.getOrElse(1) { "0xffffffff" } }
            val table = (command.valueAfter("table") ?: return null).toIntOrNull() ?: return null
            val priority = (command.valueAfter("pref") ?: return null).toIntOrNull() ?: return null
            val protocol = command.valueAfter("protocol")?.toIntOrNull() ?: 0
            val family = if ("-6" in command) "6" else "4"
            val canonical = command.joinToString(" ")
            return RootNetfilterOwnerRecord.Rule(
                family, markValue, mask, priority, table, protocol, sha256(canonical)
            ).also(::validateRule)
        }
        if ("route" in command && "add" in command && "local" in command) {
            val family = if ("-6" in command) "6" else "4"
            val routeIndex = command.indexOf("route")
            val prefix = command.getOrNull(routeIndex + 3) ?: return null
            val device = command.valueAfter("dev") ?: return null
            val table = (command.valueAfter("table") ?: return null).toIntOrNull() ?: return null
            val protocol = command.valueAfter("proto")?.toIntOrNull() ?: 0
            return RootNetfilterOwnerRecord.Route(
                family, prefix, device, table, protocol, sha256(command.joinToString(" "))
            ).also(::validateRoute)
        }
        return null
    }

    private fun List<String>.valueAfter(value: String): String? =
        indexOf(value).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

    private fun validateRule(record: RootNetfilterOwnerRecord.Rule) {
        check(record.family == "4" || record.family == "6")
        check(record.mark.matches(Regex("0x[0-9a-f]+")) && record.mask == "0xffffffff")
        check(record.table == RootRoutingConstants.ROUTE_TABLE)
        check(record.protocol == 0 || record.protocol == RootRoutingConstants.ROUTE_PROTOCOL)
        check(record.priority in 1..32_767)
        val mark = record.mark.removePrefix("0x").toIntOrNull(16)
        check(mark != null && mark to record.priority in reservedPolicyTuples())
        check(isRootSha256(record.commandSha256))
    }

    private fun validateRoute(record: RootNetfilterOwnerRecord.Route) {
        check(record.family == "4" || record.family == "6")
        check(record.device == "lo")
        check(record.table == RootRoutingConstants.ROUTE_TABLE)
        check(record.protocol == 0 || record.protocol == RootRoutingConstants.ROUTE_PROTOCOL)
        check(record.prefix == "0.0.0.0/0" || record.prefix == "::/0")
        check(isRootSha256(record.commandSha256))
    }

    private fun validateUidRule(record: RootNetfilterOwnerRecord.UidRule) {
        check(record.family == "6")
        check(parseUidRange(record.uidRange) != null)
        check(
            record.priority in RootNetfilterPlanner.IPV6_UID_RULE_PRIORITY_BASE until
                RootNetfilterPlanner.IPV6_UID_RULE_PRIORITY_BASE + RootNetfilterPlanner.MAX_IPV6_UID_RULES
        )
        check(record.table == RootRoutingConstants.ROUTE_TABLE)
        check(record.protocol == 0 || record.protocol == RootRoutingConstants.ROUTE_PROTOCOL)
        check(isRootSha256(record.commandSha256))
    }

    private fun isReservedMarkPolicyLine(line: String): Boolean = reservedPolicyTuples().any { (mark, priority) ->
        line.trimStart().startsWith("$priority:") &&
            line.contains("fwmark ${rootMark(mark)}") &&
            (line.contains("lookup ${RootRoutingConstants.ROUTE_TABLE}") ||
                line.contains("table ${RootRoutingConstants.ROUTE_TABLE}"))
    }

    private fun isReservedUidPolicyLine(line: String): Boolean {
        val trimmed = line.trim()
        val priority = trimmed.substringBefore(':').toIntOrNull() ?: return false
        val fields = trimmed.split(' ').filter(String::isNotBlank)
        val uidRangeIndex = fields.indexOf("uidrange")
        val uidRange = fields.getOrNull(uidRangeIndex + 1) ?: return false
        val priorityReserved = priority in RootNetfilterPlanner.IPV6_UID_RULE_PRIORITY_BASE until
            RootNetfilterPlanner.IPV6_UID_RULE_PRIORITY_BASE + RootNetfilterPlanner.MAX_IPV6_UID_RULES
        return priorityReserved && parseUidRange(uidRange) != null &&
            (line.contains("lookup ${RootRoutingConstants.ROUTE_TABLE}") ||
                line.contains("table ${RootRoutingConstants.ROUTE_TABLE}"))
    }

    private fun parseUidRange(value: String): Pair<Int, Int>? {
        val fields = value.split('-', limit = 2)
        val first = fields.getOrNull(0)?.toIntOrNull()
        val last = fields.getOrNull(1)?.toIntOrNull()
        if (first == null || last == null) return null
        return (first to last).takeIf { first > 0 && last >= first }
    }

    private fun validateChain(record: RootNetfilterOwnerRecord.Chain) {
        check(record.family == "4" || record.family == "6")
        check(
            when (record.family to record.chain) {
                "4" to "KBX_OUT4", "4" to "KBX_PRE4", "4" to "KBX_IN4",
                "4" to "KBX_RED4", "4" to "KBX_BLOCK4", "4" to "KBX_QUIC4",
                "4" to "KBX_GUARD4", "6" to "KBX_OUT6", "6" to "KBX_PRE6",
                "6" to "KBX_IN6", "6" to "KBX_RED6", "6" to "KBX_BLOCK6",
                "6" to "KBX_QUIC6", "6" to "KBX_PRIV6", "6" to "KBX_GUARD6" -> true
                else -> false
            }
        )
        check(record.hook.isEmpty() || record.hook in setOf("OUTPUT", "PREROUTING", "INPUT"))
        check(isRootSha256(record.rulesSha256))
    }
}

internal class RootNetfilterOwnershipStore(
    private val executor: RootCommandExecutor,
    private val rootDirectory: File = File(RootNetfilterOwnership.RUNTIME_DIR)
) {
    private companion object {
        const val STARTUP_CLEANUP_TIMEOUT_MS = 3_000L
        const val TAG = "RootNetfilterOwnership"
    }

    private val ownerFile = File(rootDirectory, "netfilter-owner")
    private val stagingFile = File(rootDirectory, "netfilter-owner.staging")
    private val cleanupScript = File(rootDirectory, "cleanup-owned.sh")
    private val conflictFile = File(rootDirectory, "cleanup_conflict")
    private val legacyScanMarker = File(rootDirectory, "legacy-scan-v1")

    fun clearStaging() {
        check(!Files.isSymbolicLink(stagingFile.toPath())) {
            "Root owner staging file cannot be a symbolic link"
        }
        if (stagingFile.isFile) check(stagingFile.delete()) { "Cannot clear Root owner staging file" }
    }

    fun clearVerifiedOwner() {
        listOf(ownerFile, stagingFile, conflictFile).forEach { file ->
            check(!Files.isSymbolicLink(file.toPath())) {
                "Root netfilter cleanup file cannot be a symbolic link: ${file.name}"
            }
            check(!file.exists() || file.delete()) { "Cannot remove Root netfilter cleanup file: ${file.name}" }
        }
    }

    fun hasOwner(): Boolean {
        check(!Files.isSymbolicLink(ownerFile.toPath())) { "Root owner file cannot be a symbolic link" }
        check(!Files.isSymbolicLink(stagingFile.toPath())) {
            "Root owner staging file cannot be a symbolic link"
        }
        return ownerFile.isFile || stagingFile.isFile
    }

    fun readAnyOwner(): RootNetfilterOwnerManifest? {
        hasOwner()
        return RootNetfilterOwnership.read(stagingFile) ?: RootNetfilterOwnership.read(ownerFile)
    }

    fun persist(
        manifest: RootNetfilterOwnerManifest,
        active: Boolean,
        refreshChainFingerprints: Boolean = true,
        chainSnapshot: Map<String, String>? = null
    ) {
        check(!Files.isSymbolicLink(rootDirectory.toPath())) {
            "Root netfilter runtime directory cannot be a symbolic link"
        }
        val refreshed = when {
            !refreshChainFingerprints -> manifest
            chainSnapshot != null -> RootNetfilterOwnership.refreshChainFingerprints(manifest, chainSnapshot)
            else -> RootNetfilterOwnership.refreshChainFingerprints(manifest, executor)
        }
        if (active) {
            RootNetfilterOwnership.writeActive(refreshed, ownerFile)
            clearStaging()
        } else {
            RootNetfilterOwnership.writeStaging(refreshed, stagingFile)
        }
    }

    fun promoteStagingExcludingChains(chains: Set<String>) {
        val manifest = RootNetfilterOwnership.read(stagingFile)
            ?: error("Root netfilter staging ownership is unavailable")
        persist(
            manifest.copy(
                records = manifest.records.filterNot { record ->
                    record is RootNetfilterOwnerRecord.Chain && record.chain in chains
                }
            ),
            active = true,
            refreshChainFingerprints = false
        )
    }

    fun cleanupAnyOwner(timeoutMs: Long? = null): Result<Unit> = runCatching {
        hasOwner()
        val owner = runCatching {
            RootNetfilterOwnership.read(stagingFile) ?: RootNetfilterOwnership.read(ownerFile)
        }.getOrNull()
        val command = RootNetfilterOwnership.cleanupCommand(owner?.context?.sessionId).toMutableList().apply {
            this[1] = cleanupScript.absolutePath
        }
        val result = timeoutMs?.let { executor.executeWithTimeout(command, it) } ?: executor.execute(command)
        if (!result.success) cleanupFailed("owned", result)
        check(!ownerFile.exists() || ownerFile.delete()) { "Cannot remove Root owner file" }
        check(!stagingFile.exists() || stagingFile.delete()) { "Cannot remove Root owner staging file" }
    }

    fun cleanupLegacy(timeoutMs: Long? = null): Result<Unit> = runCatching {
        val command = listOf("/system/bin/sh", cleanupScript.absolutePath, "legacy-cleanup")
        val result = timeoutMs?.let { executor.executeWithTimeout(command, it) } ?: executor.execute(command)
        if (!result.success) cleanupFailed("legacy", result)
    }

    fun cleanupAnyOwnerForStartup(): Result<Unit> = cleanupAnyOwner(STARTUP_CLEANUP_TIMEOUT_MS)

    fun cleanupLegacyForStartup(): Result<Unit> = cleanupLegacy(STARTUP_CLEANUP_TIMEOUT_MS)

    fun hasCompletedLegacyScan(): Boolean {
        check(!Files.isSymbolicLink(legacyScanMarker.toPath())) { "Root legacy marker cannot be a symbolic link" }
        return legacyScanMarker.isFile
    }

    fun markLegacyScanCompleted() {
        check(!Files.isSymbolicLink(rootDirectory.toPath())) {
            "Root netfilter runtime directory cannot be a symbolic link"
        }
        check(rootDirectory.exists() || rootDirectory.mkdirs()) { "Cannot create Root runtime directory" }
        check(!Files.isSymbolicLink(legacyScanMarker.toPath())) { "Root legacy marker cannot be a symbolic link" }
        check(legacyScanMarker.isFile || legacyScanMarker.createNewFile()) { "Cannot persist Root legacy marker" }
    }

    private fun cleanupFailed(mode: String, result: RootCommandResult): Nothing {
        val fileReason = runCatching {
            check(!Files.isSymbolicLink(conflictFile.toPath()))
            conflictFile.takeIf(File::isFile)?.readText()?.trim()
        }.getOrNull().orEmpty()
        val reason = fileReason.ifBlank {
            result.diagnosticOutput.ifBlank { "cleanup_script_exitCode=${result.exitCode}" }
        }
        val diagnostics = if (hasCleanupScriptDiagnostics(result)) {
            Log.i(TAG, "[ROOT_NET] event=cleanup_diagnostics source=cleanup_script")
            cleanupScriptDiagnostics(mode, reason, result)
        } else {
            cleanupDiagnostics(mode, reason, result)
        }
        diagnostics.forEachIndexed { index, line ->
            Log.e(TAG, "[ROOT_NET] event=cleanup_failed part=$index $line")
        }
        error("Root $mode netfilter cleanup failed: exitCode=${result.exitCode} reason=$reason")
    }

    private fun hasCleanupScriptDiagnostics(cleanup: RootCommandResult): Boolean =
        cleanup.output.lineSequence().any(::isCleanupScriptDiagnosticLine) ||
            cleanup.stderr.lineSequence().any(::isCleanupScriptDiagnosticLine)

    private fun isCleanupScriptDiagnosticLine(line: String): Boolean =
        "[ROOT_NET_QUERY]" in line || "cleanup_command=" in line || "[ROOT_NET_CLEANUP]" in line

    private fun cleanupScriptDiagnostics(
        mode: String,
        reason: String,
        cleanup: RootCommandResult
    ): List<String> = buildList {
        add("mode=$mode reason=$reason cleanup_exitCode=${cleanup.exitCode}")
        cleanup.output.lineSequence().filter(String::isNotBlank).forEach { add("cleanup_stdout=$it") }
        cleanup.stderr.lineSequence().filter(String::isNotBlank).forEach { add("cleanup_stderr=$it") }
    }

    private fun cleanupDiagnostics(mode: String, reason: String, cleanup: RootCommandResult): List<String> {
        val probes = listOf(
            "backend_ipv4" to listOf("iptables", "-V"),
            "backend_ipv6" to listOf("ip6tables", "-V"),
            "table_ipv4_mangle" to listOf("iptables", "-t", "mangle", "-S"),
            "table_ipv4_nat" to listOf("iptables", "-t", "nat", "-S"),
            "table_ipv4_filter" to listOf("iptables", "-t", "filter", "-S"),
            "table_ipv6_mangle" to listOf("ip6tables", "-t", "mangle", "-S"),
            "table_ipv6_nat" to listOf("ip6tables", "-t", "nat", "-S"),
            "table_ipv6_filter" to listOf("ip6tables", "-t", "filter", "-S"),
            "ip_rule_ipv4" to listOf("ip", "rule", "show"),
            "ip_rule_ipv6" to listOf("ip", "-6", "rule", "show"),
            "route_ipv4_20231" to listOf("ip", "route", "show", "table", "20231"),
            "route_ipv6_20231" to listOf("ip", "-6", "route", "show", "table", "20231"),
            "nft_version" to listOf("nft", "--version"),
            "nft_ruleset" to listOf("nft", "-a", "list", "ruleset"),
            "ipset" to listOf("ipset", "list", "-n")
        )
        return buildList {
            add("mode=$mode reason=$reason cleanup_exitCode=${cleanup.exitCode}")
            cleanup.output.lineSequence().filter(String::isNotBlank).forEach { add("cleanup_stdout=$it") }
            cleanup.stderr.lineSequence().filter(String::isNotBlank).forEach { add("cleanup_stderr=$it") }
            probes.forEach { (name, command) ->
                val probe = runCatching { executor.execute(command) }.getOrElse { error ->
                    add("probe=$name command=${command.joinToString(" ")} exception=${error.message.orEmpty()}")
                    return@forEach
                }
                val ownedOutput = ownedDiagnosticLines(name, probe.output)
                val backend = if (name.startsWith("backend_")) {
                    " backend=${classifyIptablesBackend(probe.output)}"
                } else {
                    ""
                }
                val commandText = command.joinToString(" ")
                add("probe=$name$backend command=$commandText exitCode=${probe.exitCode}")
                ownedOutput.ifEmpty { listOf("<none>") }.forEach { add("probe=$name stdout=$it") }
                probe.stderr.lineSequence().filter(String::isNotBlank).toList()
                    .ifEmpty { listOf("<none>") }
                    .forEach { add("probe=$name stderr=$it") }
            }
        }
    }

    private fun classifyIptablesBackend(version: String): String = when {
        version.contains("nf_tables", ignoreCase = true) -> "iptables-nft"
        version.contains("legacy", ignoreCase = true) -> "iptables-legacy"
        version.isBlank() -> "unknown"
        else -> "iptables"
    }

    private fun ownedDiagnosticLines(probe: String, output: String): List<String> {
        if (probe == "nft_ruleset") {
            var table = "table=<unknown>"
            return buildList {
                output.lineSequence().map(String::trim).filter(String::isNotBlank).forEach { line ->
                    if (line.startsWith("table ")) table = line.substringBefore('{').trim()
                    if ("KBX_" in line) add("$table $line")
                }
            }
        }
        return output.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filter { line -> diagnosticLineOwnedByKunBox(probe, line) }
            .toList()
    }

    private fun diagnosticLineOwnedByKunBox(probe: String, line: String): Boolean = when {
        probe.startsWith("backend_") || probe == "nft_version" -> true
        probe.startsWith("table_") || probe == "ipset" -> "KBX_" in line
        probe.startsWith("route_") -> true
        probe.startsWith("ip_rule_") -> RootNetfilterOwnership.isReservedPolicyLine(line)
        else -> false
    }
}
