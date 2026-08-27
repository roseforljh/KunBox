package com.kunk.singbox.service.root

import android.util.Log
import com.kunk.singbox.model.RootRoutingConstants
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class RootCommandResult(
    val exitCode: Int,
    val output: String
) {
    val success: Boolean get() = exitCode == 0
}

fun interface RootCommandExecutor {
    fun execute(arguments: List<String>): RootCommandResult

    fun executeFastNetfilterPlan(commands: List<List<String>>): RootCommandResult? = null

    fun executeBatch(commands: List<List<String>>): RootCommandResult {
        val output = mutableListOf<String>()
        commands.forEachIndexed { index, command ->
            val result = execute(command)
            if (!result.success) {
                val failure = "Batch command $index failed: ${command.joinToString(" ")}\n${result.output}".trim()
                return result.copy(
                    output = (output + failure).joinToString("\n")
                )
            }
            result.output.takeIf(String::isNotBlank)?.let(output::add)
        }
        return RootCommandResult(0, output.joinToString("\n"))
    }

    fun executeBestEffortBatch(
        commands: List<List<String>>,
        repeatUntilFailure: Set<Int>,
        maxAttempts: Int
    ): RootCommandResult {
        commands.forEachIndexed { index, command ->
            var attempts = 0
            do {
                val result = runCatching { execute(command) }.getOrElse { break }
                attempts++
            } while (index in repeatUntilFailure && result.success && attempts < maxAttempts)
        }
        return RootCommandResult(0, "")
    }
}

class ProcessRootCommandExecutor internal constructor(
    private val timeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS
) : RootCommandExecutor {
    init {
        require(timeoutMs > 0L)
    }

    override fun execute(arguments: List<String>): RootCommandResult {
        require(arguments.isNotEmpty()) { "Root command is empty" }
        val process = ProcessBuilder(arguments)
            .redirectErrorStream(true)
            .start()
        return collectProcess(process)
    }

    override fun executeBatch(commands: List<List<String>>): RootCommandResult =
        executeScript(buildRootCommandBatchScript(commands))

    override fun executeBestEffortBatch(
        commands: List<List<String>>,
        repeatUntilFailure: Set<Int>,
        maxAttempts: Int
    ): RootCommandResult = executeScript(
        buildRootCommandBatchScript(commands, repeatUntilFailure, maxAttempts)
    )

    override fun executeFastNetfilterPlan(commands: List<List<String>>): RootCommandResult? =
        buildRootNetfilterRestoreScript(commands)?.let(::executeScript)

    private fun executeScript(script: String): RootCommandResult {
        if (script.isBlank()) return RootCommandResult(0, "")
        val process = ProcessBuilder("/system/bin/sh")
            .redirectErrorStream(true)
            .start()
        process.outputStream.bufferedWriter().use { it.write(script) }
        return collectProcess(process)
    }

    private fun collectProcess(process: Process): RootCommandResult {
        val output = StringBuilder()
        val readError = AtomicReference<Throwable?>()
        val reader = Thread({
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream)).use { input ->
                    val buffer = CharArray(4_096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.append(buffer, 0, count)
                    }
                }
            }.onFailure(readError::set)
        }, "kunbox-root-command-output").apply {
            isDaemon = true
            start()
        }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroy()
            if (!process.waitFor(PROCESS_DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(PROCESS_DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)
            }
            reader.join(PROCESS_READER_JOIN_MS)
            return RootCommandResult(COMMAND_TIMEOUT_EXIT_CODE, output.toString().trim())
        }
        reader.join(PROCESS_READER_JOIN_MS)
        check(!reader.isAlive) { "Root command output reader did not finish" }
        readError.get()?.let { throw IllegalStateException("Cannot read Root command output", it) }
        return RootCommandResult(process.exitValue(), output.toString().trim())
    }

    private companion object {
        const val DEFAULT_COMMAND_TIMEOUT_MS = 8_000L
        const val PROCESS_DESTROY_GRACE_MS = 250L
        const val PROCESS_READER_JOIN_MS = 1_000L
        const val COMMAND_TIMEOUT_EXIT_CODE = 124
    }
}

@Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod", "ReturnCount")
internal fun buildRootNetfilterRestoreScript(commands: List<List<String>>): String? {
    data class TableRestore(
        val chains: MutableList<String> = mutableListOf(),
        val rules: MutableList<String> = mutableListOf()
    )

    val restores = linkedMapOf<String, LinkedHashMap<String, TableRestore>>()
    val policyCommands = mutableListOf<List<String>>()
    val activationCommands = mutableListOf<List<String>>()

    commands.forEach { command ->
        val binary = command.firstOrNull() ?: return null
        if (binary !in setOf("iptables", "ip6tables")) {
            policyCommands += command
            return@forEach
        }
        if ("-I" in command) {
            activationCommands += command
            return@forEach
        }
        val tableIndex = command.indexOf("-t")
        val table = command.getOrNull(tableIndex + 1) ?: return null
        val operation = command.getOrNull(tableIndex + 2) ?: return null
        val restore = restores.getOrPut(binary) { linkedMapOf() }.getOrPut(table) { TableRestore() }
        when (operation) {
            "-N" -> {
                val chain = command.getOrNull(tableIndex + 3)?.takeIf(::isSafeRestoreToken) ?: return null
                restore.chains += ":$chain - [0:0]"
            }
            "-A" -> {
                val arguments = command.drop(tableIndex + 2)
                if (arguments.any { !isSafeRestoreToken(it) }) return null
                restore.rules += arguments.joinToString(" ")
            }
            else -> return null
        }
    }
    if (restores.isEmpty()) return null

    return buildString {
        restores.forEach { (binary, tables) ->
            val restoreBinary = if (binary == "iptables") "iptables-restore" else "ip6tables-restore"
            append("command -v ").append(restoreBinary).append(" >/dev/null 2>&1 || exit 127\n")
            append(restoreBinary).append(" --noflush <<'KBX_RESTORE_")
                .append(if (binary == "iptables") "4" else "6").append("'\n")
            tables.forEach { (table, restore) ->
                append('*').append(table).append('\n')
                restore.chains.forEach { append(it).append('\n') }
                restore.rules.forEach { append(it).append('\n') }
                append("COMMIT\n")
            }
            append("KBX_RESTORE_").append(if (binary == "iptables") "4" else "6").append('\n')
            append("kb_status=${'$'}?\nif [ \"${'$'}kb_status\" -ne 0 ]; then ")
                .append("exit \"${'$'}kb_status\"; fi\n")
        }
        append(buildRootCommandBatchScript(policyCommands + activationCommands))
    }
}

private fun isSafeRestoreToken(value: String): Boolean = value.isNotBlank() && value.all { char ->
    char.isLetterOrDigit() || char in "_-.:/,"
}

internal fun buildRootCommandBatchScript(
    commands: List<List<String>>,
    repeatUntilFailure: Set<Int> = emptySet(),
    maxAttempts: Int = 1
): String {
    require(maxAttempts > 0)
    require(repeatUntilFailure.all(commands.indices::contains))
    return buildString {
        commands.forEachIndexed { index, arguments ->
            require(arguments.isNotEmpty()) { "Root command is empty" }
            val command = arguments.joinToString(" ", transform = ::shellQuote)
            if (index in repeatUntilFailure) {
                append("kb_attempt=0\nwhile [ \"${'$'}kb_attempt\" -lt ")
                append(maxAttempts)
                append(" ]; do\n  ")
                append(command)
                append(" >/dev/null 2>&1 || break\n  kb_attempt=${'$'}((kb_attempt + 1))\ndone\n")
            } else if (repeatUntilFailure.isNotEmpty()) {
                append(command)
                append(" >/dev/null 2>&1 || :\n")
            } else {
                append(command)
                append("\nkb_status=${'$'}?\nif [ \"${'$'}kb_status\" -ne 0 ]; then\n")
                append("  printf '%s\\n' ")
                append(shellQuote("Batch command $index failed: $command"))
                append(" >&2\n  exit \"${'$'}kb_status\"\nfi\n")
            }
        }
    }
}

internal fun shellQuote(value: String): String {
    require('\u0000' !in value) { "Root command argument contains NUL" }
    return "'${value.replace("'", "'\"'\"'")}'"
}

data class RootNetfilterConfig(
    val capturedUids: List<Int>,
    val capturedUidRanges: List<RootUidRange>,
    val excludedUids: List<Int>,
    val appUid: Int,
    val proxyIpv4: Boolean,
    val proxyIpv6: Boolean,
    val blockIpv4: Boolean,
    val blockIpv6: Boolean,
    val blockQuic: Boolean = false,
    val redirectPortIpv4: Int,
    val redirectPortIpv6: Int,
    val tproxyPortIpv4: Int,
    val tproxyPortIpv6: Int,
    val lanes: List<RootNetfilterLane> = emptyList()
) {
    init {
        require(appUid > 0)
        require(redirectPortIpv4 in 1..65535)
        require(redirectPortIpv6 in 1..65535)
        require(tproxyPortIpv4 in 1..65535)
        require(tproxyPortIpv6 in 1..65535)
        require(capturedUids.all { it > 0 })
        require(capturedUidRanges.all { it.first > 0 && it.last >= it.first })
        require(excludedUids.all { it > 0 })
        require(lanes.size <= RootRoutingConstants.MAX_LANES)
        require(lanes.map(RootNetfilterLane::laneId).distinct().size == lanes.size)
        require(lanes.map(RootNetfilterLane::slot).distinct().size == lanes.size)
        require(lanes.flatMap(RootNetfilterLane::uids).distinct().size == lanes.sumOf { it.uids.distinct().size })
        require(lanes.flatMap(RootNetfilterLane::uids).none { it in excludedUids })
    }
}

data class RootNetfilterLane(
    val laneId: String,
    val slot: Int,
    val uids: List<Int>,
    val redirectPortIpv4: Int,
    val redirectPortIpv6: Int,
    val tproxyPortIpv4: Int,
    val tproxyPortIpv6: Int,
    val markIpv4: Int,
    val markIpv6: Int,
    val priorityIpv4: Int,
    val priorityIpv6: Int
) {
    init {
        require(laneId.isNotBlank())
        require(slot in 0 until RootRoutingConstants.MAX_LANES)
        require(uids.isNotEmpty() && uids.all { it > 0 })
        require(redirectPortIpv4 == RootRoutingConstants.tcpPortIpv4(slot))
        require(redirectPortIpv6 == RootRoutingConstants.tcpPortIpv6(slot))
        require(tproxyPortIpv4 == RootRoutingConstants.udpPortIpv4(slot))
        require(tproxyPortIpv6 == RootRoutingConstants.udpPortIpv6(slot))
        require(markIpv4 == RootRoutingConstants.markIpv4(slot))
        require(markIpv6 == RootRoutingConstants.markIpv6(slot))
        require(priorityIpv4 == RootRoutingConstants.priorityIpv4(slot))
        require(priorityIpv6 == RootRoutingConstants.priorityIpv6(slot))
    }
}

data class RootNetfilterPlan(
    val stageCommands: List<List<String>>,
    val activationCommands: List<List<String>>,
    val cleanupCommands: List<List<String>>,
    val verifyCommands: List<List<String>>
) {
    val setupCommands: List<List<String>> get() = stageCommands + activationCommands
}

data class RootFailClosedConfig(
    val capturedUids: List<Int>,
    val capturedUidRanges: List<RootUidRange>,
    val excludedUids: List<Int>,
    val appUid: Int,
    val ipv4: Boolean,
    val ipv6: Boolean
)

data class RootGuardPlan(
    val setupCommands: List<List<String>>,
    val cleanupCommands: List<List<String>>,
    val verifyCommands: List<List<String>>
)

data class RootUidRange(val first: Int, val last: Int) {
    init {
        require(first > 0 && last >= first) { "Invalid Root UID range: $first-$last" }
    }

    val ownerValue: String get() = if (first == last) first.toString() else "$first-$last"
}

internal fun compactRootUids(uids: Collection<Int>): List<RootUidRange> {
    val sorted = uids.asSequence().filter { it > 0 }.distinct().sorted().toList()
    if (sorted.isEmpty()) return emptyList()
    val ranges = mutableListOf<RootUidRange>()
    var first = sorted.first()
    var last = first
    sorted.drop(1).forEach { uid ->
        if (last != Int.MAX_VALUE && uid == last + 1) {
            last = uid
        } else {
            ranges += RootUidRange(first, last)
            first = uid
            last = uid
        }
    }
    ranges += RootUidRange(first, last)
    return ranges
}

internal fun rootMark(value: Int): String = "0x${value.toString(16)}"

private data class TransparentFamily(
    val binary: String,
    val familyFlag: String?,
    val outChain: String,
    val preChain: String,
    val inputChain: String,
    val redirectChain: String,
    val mark: String,
    val rulePriority: String,
    val localRoute: String,
    val localBypass: String,
    val multicastAddress: String,
    val redirectPort: Int,
    val tproxyPort: Int
) {
    val ipv6: Boolean get() = familyFlag != null
}

object RootNetfilterPlanner {
    val IPV4_MARK = rootMark(RootRoutingConstants.GENERIC_MARK_IPV4)
    val IPV6_MARK = rootMark(RootRoutingConstants.GENERIC_MARK_IPV6)
    const val CORE_BYPASS_MARK_MASK = 0x10000000
    const val CORE_BYPASS_MARK_MATCH = "0x10000000/0x10000000"
    val ROUTE_TABLE = RootRoutingConstants.ROUTE_TABLE.toString()
    val RULE_PRIORITY_V4 = RootRoutingConstants.GENERIC_PRIORITY_IPV4.toString()
    val RULE_PRIORITY_V6 = RootRoutingConstants.GENERIC_PRIORITY_IPV6.toString()
    val ROUTE_PROTOCOL = RootRoutingConstants.ROUTE_PROTOCOL.toString()
    const val CHAIN_OUT4 = "KBX_OUT4"
    const val CHAIN_PRE4 = "KBX_PRE4"
    const val CHAIN_IN4 = "KBX_IN4"
    const val CHAIN_RED4 = "KBX_RED4"
    const val CHAIN_OUT6 = "KBX_OUT6"
    const val CHAIN_PRE6 = "KBX_PRE6"
    const val CHAIN_IN6 = "KBX_IN6"
    const val CHAIN_RED6 = "KBX_RED6"
    const val CHAIN_BLOCK4 = "KBX_BLOCK4"
    const val CHAIN_BLOCK6 = "KBX_BLOCK6"
    const val CHAIN_QUIC4 = "KBX_QUIC4"
    const val CHAIN_QUIC6 = "KBX_QUIC6"
    const val CHAIN_GUARD4 = "KBX_GUARD4"
    const val CHAIN_GUARD6 = "KBX_GUARD6"

    internal fun withCoreBypassMark(mark: Int): Int {
        require(mark and CORE_BYPASS_MARK_MASK == 0) { "Core bypass mark bit is already in use" }
        return mark or CORE_BYPASS_MARK_MASK
    }

    @Suppress("LongMethod")
    fun build(config: RootNetfilterConfig): RootNetfilterPlan {
        val setup = mutableListOf<List<String>>()
        val cleanup = cleanupCommands(config)
        val verify = mutableListOf<List<String>>()
        if (config.proxyIpv4) {
            appendTransparentFamily(setup, TransparentFamily(
                binary = "iptables",
                familyFlag = null,
                outChain = CHAIN_OUT4,
                preChain = CHAIN_PRE4,
                inputChain = CHAIN_IN4,
                redirectChain = CHAIN_RED4,
                mark = IPV4_MARK,
                rulePriority = RULE_PRIORITY_V4,
                localRoute = "0.0.0.0/0",
                localBypass = "127.0.0.0/8",
                multicastAddress = "224.0.0.251",
                redirectPort = config.redirectPortIpv4,
                tproxyPort = config.tproxyPortIpv4
            ), config)
            if (config.blockQuic) {
                appendQuicBlockFamily(setup, "iptables", CHAIN_QUIC4, config)
                verify += listOf("iptables", "-t", "filter", "-S", CHAIN_QUIC4)
                verify += listOf("iptables", "-t", "filter", "-C", "OUTPUT", "-j", CHAIN_QUIC4)
            }
            verify += listOf("iptables", "-t", "mangle", "-S", CHAIN_OUT4)
            verify += listOf("iptables", "-t", "mangle", "-S", CHAIN_PRE4)
            verify += listOf("iptables", "-t", "filter", "-S", CHAIN_IN4)
            verify += listOf("iptables", "-t", "nat", "-S", CHAIN_RED4)
            verify += listOf("iptables", "-t", "mangle", "-C", "OUTPUT", "-j", CHAIN_OUT4)
            verify += listOf("iptables", "-t", "mangle", "-C", "PREROUTING", "-j", CHAIN_PRE4)
            verify += listOf("iptables", "-t", "filter", "-C", "INPUT", "-j", CHAIN_IN4)
            verify += listOf("iptables", "-t", "nat", "-C", "OUTPUT", "-j", CHAIN_RED4)
        }
        if (config.proxyIpv6) {
            appendTransparentFamily(setup, TransparentFamily(
                binary = "ip6tables",
                familyFlag = "-6",
                outChain = CHAIN_OUT6,
                preChain = CHAIN_PRE6,
                inputChain = CHAIN_IN6,
                redirectChain = CHAIN_RED6,
                mark = IPV6_MARK,
                rulePriority = RULE_PRIORITY_V6,
                localRoute = "::/0",
                localBypass = "::1/128",
                multicastAddress = "ff02::fb",
                redirectPort = config.redirectPortIpv6,
                tproxyPort = config.tproxyPortIpv6
            ), config)
            if (config.blockQuic) {
                appendQuicBlockFamily(setup, "ip6tables", CHAIN_QUIC6, config)
                verify += listOf("ip6tables", "-t", "filter", "-S", CHAIN_QUIC6)
                verify += listOf("ip6tables", "-t", "filter", "-C", "OUTPUT", "-j", CHAIN_QUIC6)
            }
            verify += listOf("ip6tables", "-t", "mangle", "-S", CHAIN_OUT6)
            verify += listOf("ip6tables", "-t", "mangle", "-S", CHAIN_PRE6)
            verify += listOf("ip6tables", "-t", "filter", "-S", CHAIN_IN6)
            verify += listOf("ip6tables", "-t", "nat", "-S", CHAIN_RED6)
            verify += listOf("ip6tables", "-t", "mangle", "-C", "OUTPUT", "-j", CHAIN_OUT6)
            verify += listOf("ip6tables", "-t", "mangle", "-C", "PREROUTING", "-j", CHAIN_PRE6)
            verify += listOf("ip6tables", "-t", "filter", "-C", "INPUT", "-j", CHAIN_IN6)
            verify += listOf("ip6tables", "-t", "nat", "-C", "OUTPUT", "-j", CHAIN_RED6)
        }
        if (config.blockIpv4) {
            appendBlockFamily(setup, "iptables", CHAIN_BLOCK4, config)
            verify += listOf("iptables", "-t", "filter", "-S", CHAIN_BLOCK4)
        }
        if (config.blockIpv6) {
            appendBlockFamily(setup, "ip6tables", CHAIN_BLOCK6, config)
            verify += listOf("ip6tables", "-t", "filter", "-S", CHAIN_BLOCK6)
        }
        setup.filter { "-A" in it }.forEach { command ->
            verify += command.map { argument -> if (argument == "-A") "-C" else argument }
        }
        val activation = setup.filter(::isActivationCommand).sortedBy(::activationOrder)
        val staged = setup.filterNot(::isActivationCommand)
        return RootNetfilterPlan(staged, activation, cleanup, verify)
    }

    fun buildGuard(config: RootFailClosedConfig): RootGuardPlan {
        require(config.appUid > 0)
        val setup = mutableListOf<List<String>>()
        val cleanup = mutableListOf<List<String>>()
        val verify = mutableListOf<List<String>>()
        listOfNotNull(
            if (config.ipv4) Triple("iptables", CHAIN_GUARD4, "224.0.0.251") else null,
            if (config.ipv6) Triple("ip6tables", CHAIN_GUARD6, "ff02::fb") else null
        ).forEach { (binary, chain, multicast) ->
            val localBypass = if (binary == "ip6tables") "::1/128" else "127.0.0.0/8"
            setup += listOf(binary, "-t", "filter", "-N", chain)
            setup += listOf(
                binary, "-t", "filter", "-A", chain,
                "-m", "owner", "--uid-owner", "0", "-j", "RETURN"
            )
            setup += listOf(
                binary, "-t", "filter", "-A", chain,
                "-m", "owner", "--uid-owner", config.appUid.toString(), "-j", "RETURN"
            )
            setup += listOf(binary, "-t", "filter", "-A", chain, "-d", localBypass, "-j", "RETURN")
            setup += listOf(
                binary, "-t", "filter", "-A", chain,
                "-d", multicast, "-p", "udp", "--dport", "5353", "-j", "RETURN"
            )
            compactRootUids(config.excludedUids).forEach { range ->
                setup += listOf(
                    binary, "-t", "filter", "-A", chain,
                    "-m", "owner", "--uid-owner", range.ownerValue, "-j", "RETURN"
                )
            }
            (compactRootUids(config.capturedUids) + config.capturedUidRanges).distinct().forEach { range ->
                setup += listOf(
                    binary, "-t", "filter", "-A", chain,
                    "-m", "owner", "--uid-owner", range.ownerValue, "-j", "REJECT"
                )
            }
            setup += listOf(binary, "-t", "filter", "-I", "OUTPUT", "1", "-j", chain)
            cleanup += listOf(binary, "-t", "filter", "-D", "OUTPUT", "-j", chain)
            cleanup += deleteChainCommands(binary, "filter", chain)
            verify += listOf(binary, "-t", "filter", "-S", chain)
            verify += listOf(binary, "-t", "filter", "-C", "OUTPUT", "-j", chain)
            setup.filter { command -> command.firstOrNull() == binary && "-A" in command }.forEach { command ->
                verify += command.map { argument -> if (argument == "-A") "-C" else argument }
            }
        }
        return RootGuardPlan(setup, cleanup, verify)
    }

    @Suppress("LongMethod")
    fun cleanupCommands(config: RootNetfilterConfig? = null): List<List<String>> = buildList {
        addAll(
            listOf(
                listOf("iptables", "-t", "mangle", "-D", "OUTPUT", "-j", CHAIN_OUT4),
                listOf("iptables", "-t", "mangle", "-D", "PREROUTING", "-j", CHAIN_PRE4),
                listOf("iptables", "-t", "filter", "-D", "INPUT", "-j", CHAIN_IN4),
                listOf("iptables", "-t", "nat", "-D", "OUTPUT", "-j", CHAIN_RED4),
                listOf("ip6tables", "-t", "mangle", "-D", "OUTPUT", "-j", CHAIN_OUT6),
                listOf("ip6tables", "-t", "mangle", "-D", "PREROUTING", "-j", CHAIN_PRE6),
                listOf("ip6tables", "-t", "filter", "-D", "INPUT", "-j", CHAIN_IN6),
                listOf("ip6tables", "-t", "nat", "-D", "OUTPUT", "-j", CHAIN_RED6),
                listOf("iptables", "-t", "filter", "-D", "OUTPUT", "-j", CHAIN_BLOCK4),
                listOf("ip6tables", "-t", "filter", "-D", "OUTPUT", "-j", CHAIN_BLOCK6),
                listOf("iptables", "-t", "filter", "-D", "OUTPUT", "-j", CHAIN_QUIC4),
                listOf("ip6tables", "-t", "filter", "-D", "OUTPUT", "-j", CHAIN_QUIC6)
            )
        )
        if (config == null) {
            add(listOf("iptables", "-t", "filter", "-D", "OUTPUT", "-j", CHAIN_GUARD4))
            add(listOf("ip6tables", "-t", "filter", "-D", "OUTPUT", "-j", CHAIN_GUARD6))
        }
        add(policyRule("del", false, IPV4_MARK, RULE_PRIORITY_V4))
        add(policyRule("del", true, IPV6_MARK, RULE_PRIORITY_V6))
        val cleanupLanes = (config?.lanes ?: (0 until RootRoutingConstants.MAX_LANES).map { slot ->
            RootNetfilterLane(
                laneId = "cleanup-$slot",
                slot = slot,
                uids = listOf(1),
                redirectPortIpv4 = RootRoutingConstants.tcpPortIpv4(slot),
                redirectPortIpv6 = RootRoutingConstants.tcpPortIpv6(slot),
                tproxyPortIpv4 = RootRoutingConstants.udpPortIpv4(slot),
                tproxyPortIpv6 = RootRoutingConstants.udpPortIpv6(slot),
                markIpv4 = RootRoutingConstants.markIpv4(slot),
                markIpv6 = RootRoutingConstants.markIpv6(slot),
                priorityIpv4 = RootRoutingConstants.priorityIpv4(slot),
                priorityIpv6 = RootRoutingConstants.priorityIpv6(slot)
            )
        }).distinctBy(RootNetfilterLane::slot)
        cleanupLanes.forEach { lane ->
            add(policyRule("del", false, rootMark(lane.markIpv4), lane.priorityIpv4.toString()))
            add(policyRule("del", true, rootMark(lane.markIpv6), lane.priorityIpv6.toString()))
        }
        add(
            listOf(
                "ip", "route", "del", "local", "0.0.0.0/0", "dev", "lo",
                "table", ROUTE_TABLE, "proto", ROUTE_PROTOCOL
            )
        )
        add(
            listOf(
                "ip", "-6", "route", "del", "local", "::/0", "dev", "lo",
                "table", ROUTE_TABLE, "proto", ROUTE_PROTOCOL
            )
        )
        listOf(
            Triple("iptables", "mangle", CHAIN_OUT4),
            Triple("iptables", "mangle", CHAIN_PRE4),
            Triple("iptables", "filter", CHAIN_IN4),
            Triple("iptables", "nat", CHAIN_RED4),
            Triple("ip6tables", "mangle", CHAIN_OUT6),
            Triple("ip6tables", "mangle", CHAIN_PRE6),
            Triple("ip6tables", "filter", CHAIN_IN6),
            Triple("ip6tables", "nat", CHAIN_RED6),
            Triple("iptables", "filter", CHAIN_BLOCK4),
            Triple("ip6tables", "filter", CHAIN_BLOCK6),
            Triple("iptables", "filter", CHAIN_QUIC4),
            Triple("ip6tables", "filter", CHAIN_QUIC6)
        ).forEach { (binary, table, chain) -> addAll(deleteChainCommands(binary, table, chain)) }
        if (config == null) {
            addAll(deleteChainCommands("iptables", "filter", CHAIN_GUARD4))
            addAll(deleteChainCommands("ip6tables", "filter", CHAIN_GUARD6))
        }
    }

    private fun appendTransparentFamily(
        destination: MutableList<List<String>>,
        family: TransparentFamily,
        config: RootNetfilterConfig
    ) {
        val binary = family.binary
        val ipPrefix = listOfNotNull("ip", family.familyFlag)
        destination += listOf(binary, "-t", "mangle", "-N", family.outChain)
        destination += listOf(binary, "-t", "mangle", "-N", family.preChain)
        destination += listOf(binary, "-t", "filter", "-N", family.inputChain)
        destination += listOf(binary, "-t", "nat", "-N", family.redirectChain)
        appendUdpMarkRules(destination, family, config)
        appendTcpRedirectRules(destination, family, config)
        destination += listOf(
            binary, "-t", "mangle", "-A", family.preChain,
            "-d", family.multicastAddress, "-p", "udp", "--dport", "5353", "-j", "RETURN"
        )
        config.lanes.sortedBy(RootNetfilterLane::slot).forEach { lane ->
            destination += listOf(
                binary, "-t", "mangle", "-A", family.preChain,
                "-m", "mark", "--mark", laneMark(family, lane) + "/0xffffffff", "-p", "udp",
                "-j", "TPROXY", "--on-port", laneTproxyPort(family, lane).toString(),
                "--tproxy-mark", laneMark(family, lane)
            )
        }
        destination += listOf(
            binary, "-t", "mangle", "-A", family.preChain,
            "-m", "mark", "--mark", family.mark + "/0xffffffff", "-p", "udp",
            "-j", "TPROXY", "--on-port", family.tproxyPort.toString(), "--tproxy-mark", family.mark
        )
        appendInputProtection(destination, family, config)
        destination += policyRule("add", family.ipv6, family.mark, family.rulePriority)
        config.lanes.sortedBy(RootNetfilterLane::slot).forEach { lane ->
            destination += policyRule(
                "add",
                family.ipv6,
                laneMark(family, lane),
                lanePriority(family, lane).toString()
            )
        }
        destination += ipPrefix + listOf(
            "route", "add", "local", family.localRoute, "dev", "lo", "table", ROUTE_TABLE,
            "proto", ROUTE_PROTOCOL
        )
        destination += listOf(binary, "-t", "mangle", "-I", "PREROUTING", "1", "-j", family.preChain)
        destination += listOf(binary, "-t", "filter", "-I", "INPUT", "1", "-j", family.inputChain)
        destination += listOf(binary, "-t", "nat", "-I", "OUTPUT", "1", "-j", family.redirectChain)
        destination += listOf(binary, "-t", "mangle", "-I", "OUTPUT", "1", "-j", family.outChain)
    }

    private fun appendUdpMarkRules(
        destination: MutableList<List<String>>,
        family: TransparentFamily,
        config: RootNetfilterConfig
    ) {
        appendEarlyOutputReturns(destination, family, "mangle", family.outChain, config)
        appendMdnsReturn(destination, family, "mangle", family.outChain)
        config.lanes.sortedBy(RootNetfilterLane::slot).forEach { lane ->
            compactRootUids(lane.uids).forEach { uidRange ->
                destination += listOf(
                    family.binary, "-t", "mangle", "-A", family.outChain,
                    "-m", "owner", "--uid-owner", uidRange.ownerValue,
                    "-p", "udp", "-j", "MARK", "--set-mark", laneMark(family, lane)
                )
                destination += listOf(
                    family.binary, "-t", "mangle", "-A", family.outChain,
                    "-m", "owner", "--uid-owner", uidRange.ownerValue,
                    "-p", "udp", "-j", "RETURN"
                )
            }
        }
        appendExcludedUidReturns(destination, family.binary, "mangle", family.outChain, config)
        capturedUidRanges(config).forEach { uidRange ->
            destination += listOf(
                family.binary, "-t", "mangle", "-A", family.outChain,
                "-m", "owner", "--uid-owner", uidRange.ownerValue,
                "-p", "udp", "-j", "MARK", "--set-mark", family.mark
            )
        }
    }

    private fun appendTcpRedirectRules(
        destination: MutableList<List<String>>,
        family: TransparentFamily,
        config: RootNetfilterConfig
    ) {
        appendEarlyOutputReturns(destination, family, "nat", family.redirectChain, config)
        appendMdnsReturn(destination, family, "nat", family.redirectChain)
        config.lanes.sortedBy(RootNetfilterLane::slot).forEach { lane ->
            compactRootUids(lane.uids).forEach { uidRange ->
                destination += listOf(
                    family.binary, "-t", "nat", "-A", family.redirectChain,
                    "-m", "owner", "--uid-owner", uidRange.ownerValue,
                    "-p", "tcp", "-j", "REDIRECT", "--to-ports", laneRedirectPort(family, lane).toString()
                )
            }
        }
        appendExcludedUidReturns(destination, family.binary, "nat", family.redirectChain, config)
        capturedUidRanges(config).forEach { uidRange ->
            destination += listOf(
                family.binary, "-t", "nat", "-A", family.redirectChain,
                "-m", "owner", "--uid-owner", uidRange.ownerValue,
                "-p", "tcp", "-j", "REDIRECT", "--to-ports", family.redirectPort.toString()
            )
        }
    }

    private fun appendEarlyOutputReturns(
        destination: MutableList<List<String>>,
        family: TransparentFamily,
        table: String,
        chain: String,
        config: RootNetfilterConfig
    ) {
        val binary = family.binary
        destination += listOf(
            binary, "-t", table, "-A", chain,
            "-m", "mark", "--mark", CORE_BYPASS_MARK_MATCH, "-j", "RETURN"
        )
        destination += listOf(
            binary, "-t", table, "-A", chain,
            "-m", "owner", "--uid-owner", config.appUid.toString(), "-j", "RETURN"
        )
        appendRootUidReturn(destination, binary, table, chain)
        destination += listOf(binary, "-t", table, "-A", chain, "-d", family.localBypass, "-j", "RETURN")
    }

    private fun appendMdnsReturn(
        destination: MutableList<List<String>>,
        family: TransparentFamily,
        table: String,
        chain: String
    ) {
        destination += listOf(
            family.binary, "-t", table, "-A", chain,
            "-d", family.multicastAddress, "-p", "udp", "--dport", "5353", "-j", "RETURN"
        )
    }

    private fun appendRootUidReturn(
        destination: MutableList<List<String>>,
        binary: String,
        table: String,
        chain: String
    ) {
        destination += listOf(
            binary, "-t", table, "-A", chain,
            "-m", "owner", "--uid-owner", "0", "-j", "RETURN"
        )
    }

    private fun appendExcludedUidReturns(
        destination: MutableList<List<String>>,
        binary: String,
        table: String,
        chain: String,
        config: RootNetfilterConfig
    ) {
        compactRootUids(config.excludedUids).forEach { uidRange ->
            destination += listOf(
                binary, "-t", table, "-A", chain,
                "-m", "owner", "--uid-owner", uidRange.ownerValue, "-j", "RETURN"
            )
        }
    }

    private fun capturedUidRanges(config: RootNetfilterConfig): List<RootUidRange> =
        (compactRootUids(config.capturedUids) + config.capturedUidRanges).distinct()

    private fun appendInputProtection(
        destination: MutableList<List<String>>,
        family: TransparentFamily,
        config: RootNetfilterConfig
    ) {
        destination += listOf(
            family.binary, "-t", "filter", "-A", family.inputChain,
            "-i", "lo", "-p", "tcp", "--dport", family.redirectPort.toString(), "-j", "ACCEPT"
        )
        destination += listOf(
            family.binary, "-t", "filter", "-A", family.inputChain,
            "-i", "lo", "-p", "udp", "-m", "mark", "--mark",
            family.mark + "/0xffffffff", "-j", "ACCEPT"
        )
        config.lanes.sortedBy(RootNetfilterLane::slot).forEach { lane ->
            destination += listOf(
                family.binary, "-t", "filter", "-A", family.inputChain,
                "-i", "lo", "-p", "tcp", "--dport", laneRedirectPort(family, lane).toString(),
                "-j", "ACCEPT"
            )
            destination += listOf(
                family.binary, "-t", "filter", "-A", family.inputChain,
                "-i", "lo", "-p", "udp", "-m", "mark", "--mark",
                laneMark(family, lane) + "/0xffffffff", "-j", "ACCEPT"
            )
        }
        val tcpPorts = listOf(family.redirectPort) + config.lanes.map { laneRedirectPort(family, it) }
        val udpPorts = listOf(family.tproxyPort) + config.lanes.map { laneTproxyPort(family, it) }
        tcpPorts.distinct().forEach { port ->
            destination += listOf(
                family.binary, "-t", "filter", "-A", family.inputChain,
                "-p", "tcp", "--dport", port.toString(), "-j", "REJECT"
            )
        }
        udpPorts.distinct().forEach { port ->
            destination += listOf(
                family.binary, "-t", "filter", "-A", family.inputChain,
                "-p", "udp", "--dport", port.toString(), "-j", "REJECT"
            )
        }
    }

    private fun appendBlockFamily(
        destination: MutableList<List<String>>,
        binary: String,
        chain: String,
        config: RootNetfilterConfig
    ) {
        destination += listOf(binary, "-t", "filter", "-N", chain)
        destination += listOf(
            binary, "-t", "filter", "-A", chain,
            "-m", "owner", "--uid-owner", "0", "-j", "RETURN"
        )
        destination += listOf(
            binary, "-t", "filter", "-A", chain,
            "-m", "owner", "--uid-owner", config.appUid.toString(), "-j", "RETURN"
        )
        destination += listOf(
            binary, "-t", "filter", "-A", chain,
            "-d", if (binary == "ip6tables") "ff02::fb" else "224.0.0.251",
            "-p", "udp", "--dport", "5353", "-j", "RETURN"
        )
        listOf("tcp", "udp").forEach { protocol ->
            destination += listOf(
                binary, "-t", "filter", "-A", chain,
                "-p", protocol, "--dport", "53", "-j", "REJECT"
            )
        }
        compactRootUids(config.excludedUids).forEach { uidRange ->
            destination += listOf(
                binary, "-t", "filter", "-A", chain,
                "-m", "owner", "--uid-owner", uidRange.ownerValue, "-j", "RETURN"
            )
        }
        (compactRootUids(config.capturedUids) + config.capturedUidRanges).distinct().forEach { uidRange ->
            destination += listOf(
                binary, "-t", "filter", "-A", chain,
                "-m", "owner", "--uid-owner", uidRange.ownerValue, "-j", "REJECT"
            )
        }
        destination += listOf(binary, "-t", "filter", "-I", "OUTPUT", "1", "-j", chain)
    }

    private fun appendQuicBlockFamily(
        destination: MutableList<List<String>>,
        binary: String,
        chain: String,
        config: RootNetfilterConfig
    ) {
        destination += listOf(binary, "-t", "filter", "-N", chain)
        appendRootUidReturn(destination, binary, "filter", chain)
        destination += listOf(
            binary, "-t", "filter", "-A", chain,
            "-m", "owner", "--uid-owner", config.appUid.toString(), "-j", "RETURN"
        )
        appendExcludedUidReturns(destination, binary, "filter", chain, config)
        capturedUidRanges(config).forEach { uidRange ->
            destination += listOf(
                binary, "-t", "filter", "-A", chain,
                "-m", "owner", "--uid-owner", uidRange.ownerValue,
                "-p", "udp", "--dport", "443", "-j", "REJECT"
            )
        }
        destination += listOf(binary, "-t", "filter", "-I", "OUTPUT", "1", "-j", chain)
    }

    private fun laneMark(family: TransparentFamily, lane: RootNetfilterLane): String =
        rootMark(if (family.ipv6) lane.markIpv6 else lane.markIpv4)

    private fun lanePriority(family: TransparentFamily, lane: RootNetfilterLane): Int =
        if (family.ipv6) lane.priorityIpv6 else lane.priorityIpv4

    private fun laneRedirectPort(family: TransparentFamily, lane: RootNetfilterLane): Int =
        if (family.ipv6) lane.redirectPortIpv6 else lane.redirectPortIpv4

    private fun laneTproxyPort(family: TransparentFamily, lane: RootNetfilterLane): Int =
        if (family.ipv6) lane.tproxyPortIpv6 else lane.tproxyPortIpv4

    private fun policyRule(
        operation: String,
        ipv6: Boolean,
        mark: String,
        priority: String
    ): List<String> = buildList {
        add("ip")
        if (ipv6) add("-6")
        addAll(
            listOf(
                "rule", operation, "fwmark", "$mark/0xffffffff", "table", ROUTE_TABLE,
                "pref", priority, "protocol", ROUTE_PROTOCOL
            )
        )
    }

    private fun deleteChainCommands(binary: String, table: String, chain: String): List<List<String>> = listOf(
        listOf(binary, "-t", table, "-F", chain),
        listOf(binary, "-t", table, "-X", chain)
    )

    private fun isActivationCommand(command: List<String>): Boolean =
        "-I" in command && ("OUTPUT" in command || "PREROUTING" in command || "INPUT" in command)

    private fun activationOrder(command: List<String>): Int = when {
        "PREROUTING" in command -> 0
        "INPUT" in command -> 1
        else -> 2
    }
}

class RootNetfilterManager internal constructor(
    private val executor: RootCommandExecutor = ProcessRootCommandExecutor(),
    private val ownership: RootNetfilterOwnershipStore? = null
) {
    companion object {
        private const val TAG = "RootNetfilterManager"
        private const val MAX_CLEANUP_REPEATS = 32
    }

    private var activePlan: RootNetfilterPlan? = null
    private var guardPlan: RootGuardPlan? = null
    private var ownershipContext: RootNetfilterOwnerContext? = null

    internal fun beginOwnership(context: RootNetfilterOwnerContext): Result<Unit> = runCatching {
        ownershipContext = context
    }

    fun hasGuard(): Boolean = guardPlan != null

    internal fun rebindOwnership(context: RootNetfilterOwnerContext): Result<Unit> = runCatching {
        ownershipContext = context
        persistOwnership(active = false)
    }

    fun checkReservedStateAvailable(): Result<Unit> = runCatching {
        val currentOwner = ownership?.readAnyOwner()
        val expectedSession = ownershipContext?.sessionId
        if (currentOwner != null && currentOwner.context.sessionId != expectedSession) {
            error("Reserved Root policy routing is owned by another session")
        }
        val rules4 = executeRequiredResult(listOf("ip", "rule", "show")).output
        val rules6 = executeRequiredResult(listOf("ip", "-6", "rule", "show")).output
        val routes4 = executeRequiredResult(
            listOf("ip", "route", "show", "table", RootNetfilterPlanner.ROUTE_TABLE)
        ).output
        val routes6 = executeRequiredResult(
            listOf("ip", "-6", "route", "show", "table", RootNetfilterPlanner.ROUTE_TABLE)
        ).output
        checkNoForeignReservedPolicy(rules4, ipv6 = false)
        checkNoForeignReservedPolicy(rules6, ipv6 = true)
        checkNoForeignReservedRoute(routes4, ipv6 = false)
        checkNoForeignReservedRoute(routes6, ipv6 = true)
    }

    fun prepareForStart(staleRuntimePresent: Boolean): Result<Unit> = runCatching {
        if (staleRuntimePresent) Log.i(TAG, "Cleaning stale Root runtime before start")
        if (ownership != null) {
            val owned = ownership.hasOwner()
            if (owned) {
                ownership.cleanupAnyOwner().getOrThrow()
            } else if (staleRuntimePresent || hasKunBoxChainState()) {
                ownership.cleanupLegacy().getOrThrow()
            }
        } else {
            cleanup(RootNetfilterPlanner.cleanupCommands())
        }
        checkNoResidualState()
        activePlan = null
        guardPlan = null
    }

    fun apply(config: RootNetfilterConfig): Result<Unit> = runCatching {
        val plan = RootNetfilterPlanner.build(config)
        try {
            persistExpectedOwnership(plan = plan)
            val fastResult = executor.executeFastNetfilterPlan(plan.setupCommands)
            if (fastResult?.success == true) {
                Log.i(TAG, "Fast netfilter restore applied")
            } else {
                if (fastResult != null) {
                    Log.w(
                        TAG,
                        "Fast netfilter restore unavailable or failed (${fastResult.exitCode}); " +
                            "using compatible setup"
                    )
                    cleanup(plan.cleanupCommands)
                    checkNoResidualState()
                }
                executeRequired(plan.setupCommands)
            }
            executeRequired(plan.verifyCommands)
            verifyPolicyRouting(config)
            activePlan = plan
            persistOwnership(active = true)
        } catch (error: Exception) {
            cleanup(plan.cleanupCommands)
            activePlan = null
            val cleanupError = runCatching { checkNoResidualState() }.exceptionOrNull()
            if (cleanupError != null) {
                throw IllegalStateException("KunBox rules remain after failed installation", cleanupError)
            }
            throw error
        }
    }

    fun installGuard(config: RootFailClosedConfig): Result<Unit> = runCatching {
        val previousGuard = guardPlan
        previousGuard?.let {
            cleanup(it.cleanupCommands)
            checkGuardAbsent()
            guardPlan = null
            persistOwnership(active = true)
        }
        val plan = RootNetfilterPlanner.buildGuard(config)
        try {
            persistExpectedOwnership(guard = plan)
            val fastResult = executor.executeFastNetfilterPlan(plan.setupCommands)
            if (fastResult?.success != true) {
                if (fastResult != null) cleanup(plan.cleanupCommands)
                executeRequired(plan.setupCommands)
            }
            executeRequired(plan.verifyCommands)
            guardPlan = plan
            persistOwnership(active = false)
        } catch (error: Exception) {
            runCatching { cleanup(plan.cleanupCommands) }
            guardPlan = null
            if (previousGuard == null && activePlan == null) {
                ownership?.clearStaging()
            } else {
                runCatching { persistOwnership(active = previousGuard == null) }
            }
            throw error
        }
    }

    fun removeGuard(): Result<Unit> = runCatching {
        val plan = guardPlan ?: return@runCatching
        cleanup(plan.cleanupCommands)
        checkGuardAbsent()
        guardPlan = null
        persistOwnership(active = true)
    }

    fun stage(config: RootNetfilterConfig): Result<RootNetfilterPlan> = runCatching {
        val plan = RootNetfilterPlanner.build(config)
        try {
            persistExpectedOwnership(plan = plan)
            val fastResult = executor.executeFastNetfilterPlan(plan.stageCommands)
            if (fastResult?.success != true) {
                if (fastResult != null) {
                    cleanup(plan.cleanupCommands)
                    checkNoResidualState(allowGuard = guardPlan != null)
                }
                executeRequired(plan.stageCommands)
            }
            executeRequired(plan.verifyCommands.filterNot(::isActivationVerification))
            verifyPolicyRouting(config)
            activePlan = plan
            persistOwnership(active = false)
            plan
        } catch (error: Exception) {
            runCatching { cleanup(plan.cleanupCommands) }
            activePlan = null
            if (guardPlan != null) {
                runCatching { persistOwnership(active = false) }
            } else {
                ownership?.clearStaging()
            }
            throw error
        }
    }

    fun activate(plan: RootNetfilterPlan): Result<Unit> = runCatching {
        executeRequired(plan.activationCommands)
        executeRequired(plan.verifyCommands.filter(::isActivationVerification))
        activePlan = plan
        persistOwnership(active = true)
    }

    fun discard(plan: RootNetfilterPlan): Result<Unit> = runCatching {
        cleanup(plan.cleanupCommands)
        checkNoResidualState()
        if (activePlan === plan) activePlan = null
        persistOwnership(active = false)
    }

    fun cleanup(): Result<Unit> = runCatching {
        if (ownership != null) {
            val hadPlan = activePlan != null || guardPlan != null
            ownership.cleanupAnyOwner().getOrThrow()
            check(!hadPlan || !ownership.hasOwner()) {
                "Root ownership record disappeared before cleanup confirmation"
            }
        } else {
            guardPlan?.let { cleanup(it.cleanupCommands) }
            cleanup(activePlan?.cleanupCommands ?: RootNetfilterPlanner.cleanupCommands())
        }
        checkNoResidualState()
        activePlan = null
        guardPlan = null
        ownershipContext = null
    }

    fun cleanupActivePlanKeepingGuard(): Result<Unit> = runCatching {
        val plan = activePlan ?: return@runCatching
        cleanup(plan.cleanupCommands)
        checkNoResidualState(allowGuard = true)
        activePlan = null
        persistOwnership(active = false)
    }

    private fun cleanup(commands: List<List<String>>) {
        val repeatable = commands.indices.filterTo(mutableSetOf()) { isRepeatableCleanup(commands[it]) }
        executor.executeBestEffortBatch(commands, repeatable, MAX_CLEANUP_REPEATS)
    }

    private fun isRepeatableCleanup(command: List<String>): Boolean =
        "-D" in command || ("rule" in command && "del" in command)

    private fun isActivationVerification(command: List<String>): Boolean {
        val checkIndex = command.indexOf("-C")
        val chain = command.getOrNull(checkIndex + 1)
        return checkIndex >= 0 && chain in setOf("OUTPUT", "PREROUTING", "INPUT")
    }

    private fun executeRequired(commands: List<List<String>>) {
        if (commands.isEmpty()) return
        val result = executor.executeBatch(commands)
        check(result.success) {
            "Root command batch failed (${result.exitCode}): ${result.output}"
        }
    }

    private fun checkNoResidualState(allowGuard: Boolean = false) {
        val tableRules = buildString {
            append(executeRequiredBatchResult(tableProbeCommands()).output)
            append('\n')
            append(optionalIpv6NatRules())
        }
        val policyState = executeRequiredBatchResult(
            listOf(
                listOf("ip", "rule", "show"),
                listOf("ip", "-6", "rule", "show"),
                listOf("ip", "route", "show", "table", "all"),
                listOf("ip", "-6", "route", "show", "table", "all")
            )
        ).output
        val chainNames = buildList {
            addAll(listOf(
                RootNetfilterPlanner.CHAIN_OUT4,
                RootNetfilterPlanner.CHAIN_PRE4,
                RootNetfilterPlanner.CHAIN_IN4,
                RootNetfilterPlanner.CHAIN_RED4,
                RootNetfilterPlanner.CHAIN_OUT6,
                RootNetfilterPlanner.CHAIN_PRE6,
                RootNetfilterPlanner.CHAIN_IN6,
                RootNetfilterPlanner.CHAIN_RED6,
                RootNetfilterPlanner.CHAIN_BLOCK4,
                RootNetfilterPlanner.CHAIN_BLOCK6,
                RootNetfilterPlanner.CHAIN_QUIC4,
                RootNetfilterPlanner.CHAIN_QUIC6
            ))
            if (!allowGuard) {
                add(RootNetfilterPlanner.CHAIN_GUARD4)
                add(RootNetfilterPlanner.CHAIN_GUARD6)
            }
        }
        check(
            chainNames.none(tableRules::contains) && RootNetfilterPlanner.ROUTE_TABLE !in policyState
        ) { "KunBox netfilter state remains after cleanup" }
    }

    private fun hasKunBoxChainState(): Boolean {
        val output = buildString {
            append(executeRequiredBatchResult(tableProbeCommands()).output)
            append('\n')
            append(optionalIpv6NatRules())
        }
        return listOf(
            RootNetfilterPlanner.CHAIN_OUT4,
            RootNetfilterPlanner.CHAIN_PRE4,
            RootNetfilterPlanner.CHAIN_IN4,
            RootNetfilterPlanner.CHAIN_RED4,
            RootNetfilterPlanner.CHAIN_OUT6,
            RootNetfilterPlanner.CHAIN_PRE6,
            RootNetfilterPlanner.CHAIN_IN6,
            RootNetfilterPlanner.CHAIN_RED6,
            RootNetfilterPlanner.CHAIN_BLOCK4,
            RootNetfilterPlanner.CHAIN_BLOCK6,
            RootNetfilterPlanner.CHAIN_QUIC4,
            RootNetfilterPlanner.CHAIN_QUIC6,
            RootNetfilterPlanner.CHAIN_GUARD4,
            RootNetfilterPlanner.CHAIN_GUARD6
        ).any(output::contains)
    }

    private fun checkGuardAbsent() {
        val output = executeRequiredBatchResult(
            listOf(
                listOf("iptables", "-t", "filter", "-S"),
                listOf("ip6tables", "-t", "filter", "-S")
            )
        ).output
        check(RootNetfilterPlanner.CHAIN_GUARD4 !in output && RootNetfilterPlanner.CHAIN_GUARD6 !in output) {
            "KunBox fail-closed guard remains after cleanup"
        }
    }

    private fun verifyPolicyRouting(config: RootNetfilterConfig) {
        if (config.proxyIpv4) {
            val rules = executeRequiredResult(listOf("ip", "rule", "show")).output
            val routes = executeRequiredResult(
                listOf("ip", "route", "show", "table", RootNetfilterPlanner.ROUTE_TABLE)
            ).output
            check(RootNetfilterPlanner.IPV4_MARK in rules && RootNetfilterPlanner.ROUTE_TABLE in rules)
            config.lanes.forEach { lane ->
                check(rootMark(lane.markIpv4) in rules && lane.priorityIpv4.toString() in rules) {
                    "IPv4 lane policy rule is missing: ${lane.laneId}"
                }
            }
            check(routes.isNotBlank()) { "IPv4 local policy route is missing" }
        }
        if (config.proxyIpv6) {
            val rules = executeRequiredResult(listOf("ip", "-6", "rule", "show")).output
            val routes = executeRequiredResult(
                listOf("ip", "-6", "route", "show", "table", RootNetfilterPlanner.ROUTE_TABLE)
            ).output
            check(RootNetfilterPlanner.IPV6_MARK in rules && RootNetfilterPlanner.ROUTE_TABLE in rules)
            config.lanes.forEach { lane ->
                check(rootMark(lane.markIpv6) in rules && lane.priorityIpv6.toString() in rules) {
                    "IPv6 lane policy rule is missing: ${lane.laneId}"
                }
            }
            check(routes.isNotBlank()) { "IPv6 local policy route is missing" }
        }
    }

    private fun executeRequiredResult(command: List<String>): RootCommandResult =
        executor.execute(command).also { result ->
            check(result.success) {
                "Root probe failed (${result.exitCode}): ${command.joinToString(" ")} ${result.output}"
            }
        }

    private fun executeRequiredBatchResult(commands: List<List<String>>): RootCommandResult =
        executor.executeBatch(commands).also { result ->
            check(result.success) { "Root probe batch failed (${result.exitCode}): ${result.output}" }
        }

    private fun optionalIpv6NatRules(): String {
        val command = listOf("ip6tables", "-t", "nat", "-S")
        val result = executor.execute(command)
        if (result.success) return result.output
        check("can't initialize ip6tables table" in result.output && "nat" in result.output) {
            "Root probe failed (${result.exitCode}): ${command.joinToString(" ")} ${result.output}"
        }
        return ""
    }

    private fun tableProbeCommands(): List<List<String>> = listOf(
        listOf("iptables", "-t", "mangle", "-S"),
        listOf("iptables", "-t", "filter", "-S"),
        listOf("iptables", "-t", "nat", "-S"),
        listOf("ip6tables", "-t", "mangle", "-S"),
        listOf("ip6tables", "-t", "filter", "-S")
    )

    private fun persistOwnership(active: Boolean) {
        persistOwnership(active, refreshChainFingerprints = true)
    }

    private fun persistExpectedOwnership(
        plan: RootNetfilterPlan? = null,
        guard: RootGuardPlan? = null
    ) {
        val owner = ownership ?: return
        val context = ownershipContext ?: return
        val commands = buildList {
            guard?.setupCommands?.let(::addAll)
            guardPlan?.setupCommands?.let(::addAll)
            activePlan?.setupCommands?.let(::addAll)
            plan?.setupCommands?.let(::addAll)
        }
        if (commands.isEmpty()) {
            owner.clearStaging()
            return
        }
        owner.persist(
            RootNetfilterOwnership.fromCommands(context, commands),
            active = false,
            refreshChainFingerprints = false
        )
    }

    private fun persistOwnership(active: Boolean, refreshChainFingerprints: Boolean) {
        val owner = ownership ?: return
        val context = ownershipContext ?: return
        val commands = buildList {
            guardPlan?.setupCommands?.let(::addAll)
            activePlan?.setupCommands?.let(::addAll)
        }
        if (commands.isEmpty()) {
            owner.clearStaging()
            return
        }
        val manifest = RootNetfilterOwnership.fromCommands(context, commands)
        owner.persist(manifest, active, refreshChainFingerprints)
    }

    private fun checkNoForeignReservedPolicy(output: String, ipv6: Boolean) {
        val tuples = buildList {
            add(
                if (ipv6) {
                    RootRoutingConstants.GENERIC_MARK_IPV6 to RootRoutingConstants.GENERIC_PRIORITY_IPV6
                } else {
                    RootRoutingConstants.GENERIC_MARK_IPV4 to RootRoutingConstants.GENERIC_PRIORITY_IPV4
                }
            )
            repeat(RootRoutingConstants.MAX_LANES) { slot ->
                add(
                    if (ipv6) {
                        RootRoutingConstants.markIpv6(slot) to RootRoutingConstants.priorityIpv6(slot)
                    } else {
                        RootRoutingConstants.markIpv4(slot) to RootRoutingConstants.priorityIpv4(slot)
                    }
                )
            }
        }
        output.lineSequence().filter(String::isNotBlank).forEach { line ->
            val conflict = tuples.any { (mark, priority) ->
                line.trimStart().startsWith("$priority:") ||
                    line.contains("fwmark ${rootMark(mark)}/0xffffffff") ||
                    line.contains("lookup ${RootRoutingConstants.ROUTE_TABLE}")
            }
            check(!conflict) { "Reserved Root policy routing conflict: $line" }
        }
    }

    private fun checkNoForeignReservedRoute(output: String, ipv6: Boolean) {
        val prefix = if (ipv6) "::/0" else "0.0.0.0/0"
        output.lineSequence().filter(String::isNotBlank).forEach { line ->
            check(!line.contains("local $prefix") && !line.contains("table ${RootRoutingConstants.ROUTE_TABLE}")) {
                "Reserved Root policy route conflict: $line"
            }
        }
    }
}
