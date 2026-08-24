package com.kunk.singbox.service.root

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

data class RootCommandResult(
    val exitCode: Int,
    val output: String
) {
    val success: Boolean get() = exitCode == 0
}

fun interface RootCommandExecutor {
    fun execute(arguments: List<String>): RootCommandResult
}

class ProcessRootCommandExecutor : RootCommandExecutor {
    override fun execute(arguments: List<String>): RootCommandResult {
        require(arguments.isNotEmpty()) { "Root command is empty" }
        val process = ProcessBuilder(arguments)
            .redirectErrorStream(true)
            .start()
        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        return RootCommandResult(process.waitFor(), output.trim())
    }
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
    val tproxyPortIpv6: Int
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
    }
}

data class RootNetfilterPlan(
    val setupCommands: List<List<String>>,
    val cleanupCommands: List<List<String>>,
    val verifyCommands: List<List<String>>
)

data class RootUidRange(val first: Int, val last: Int) {
    val ownerValue: String get() = if (first == last) first.toString() else "$first-$last"
}

internal fun compactRootUids(uids: Collection<Int>): List<RootUidRange> {
    val sorted = uids.asSequence().filter { it > 0 }.distinct().sorted().toList()
    if (sorted.isEmpty()) return emptyList()
    val ranges = mutableListOf<RootUidRange>()
    var first = sorted.first()
    var last = first
    sorted.drop(1).forEach { uid ->
        if (uid == last + 1) {
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
    val redirectPort: Int,
    val tproxyPort: Int
)

object RootNetfilterPlanner {
    const val IPV4_MARK = "0x2331"
    const val IPV6_MARK = "0x2332"
    const val CORE_BYPASS_MARK_MASK = 0x10000000
    const val CORE_BYPASS_MARK_MATCH = "0x10000000/0x10000000"
    const val ROUTE_TABLE = "20231"
    const val RULE_PRIORITY_V4 = "12031"
    const val RULE_PRIORITY_V6 = "12032"
    const val ROUTE_PROTOCOL = "233"
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

    internal fun withCoreBypassMark(mark: Int): Int {
        require(mark and CORE_BYPASS_MARK_MASK == 0) { "Core bypass mark bit is already in use" }
        return mark or CORE_BYPASS_MARK_MASK
    }

    @Suppress("LongMethod")
    fun build(config: RootNetfilterConfig): RootNetfilterPlan {
        val setup = mutableListOf<List<String>>()
        val cleanup = cleanupCommands()
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
        val activation = setup.filter(::isActivationCommand).sortedBy(::activationOrder)
        val staged = setup.filterNot(::isActivationCommand)
        return RootNetfilterPlan(staged + activation, cleanup, verify)
    }

    fun cleanupCommands(): List<List<String>> = listOf(
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
        listOf("ip6tables", "-t", "filter", "-D", "OUTPUT", "-j", CHAIN_QUIC6),
        listOf("ip", "rule", "del", "fwmark", IPV4_MARK, "table", ROUTE_TABLE, "pref", RULE_PRIORITY_V4),
        listOf("ip", "-6", "rule", "del", "fwmark", IPV6_MARK, "table", ROUTE_TABLE, "pref", RULE_PRIORITY_V6),
        listOf(
            "ip", "route", "del", "local", "0.0.0.0/0", "dev", "lo",
            "table", ROUTE_TABLE, "proto", ROUTE_PROTOCOL
        ),
        listOf(
            "ip", "-6", "route", "del", "local", "::/0", "dev", "lo",
            "table", ROUTE_TABLE, "proto", ROUTE_PROTOCOL
        ),
        *deleteChainCommands("iptables", "mangle", CHAIN_OUT4).toTypedArray(),
        *deleteChainCommands("iptables", "mangle", CHAIN_PRE4).toTypedArray(),
        *deleteChainCommands("iptables", "filter", CHAIN_IN4).toTypedArray(),
        *deleteChainCommands("iptables", "nat", CHAIN_RED4).toTypedArray(),
        *deleteChainCommands("ip6tables", "mangle", CHAIN_OUT6).toTypedArray(),
        *deleteChainCommands("ip6tables", "mangle", CHAIN_PRE6).toTypedArray(),
        *deleteChainCommands("ip6tables", "filter", CHAIN_IN6).toTypedArray(),
        *deleteChainCommands("ip6tables", "nat", CHAIN_RED6).toTypedArray(),
        *deleteChainCommands("iptables", "filter", CHAIN_BLOCK4).toTypedArray(),
        *deleteChainCommands("ip6tables", "filter", CHAIN_BLOCK6).toTypedArray(),
        *deleteChainCommands("iptables", "filter", CHAIN_QUIC4).toTypedArray(),
        *deleteChainCommands("ip6tables", "filter", CHAIN_QUIC6).toTypedArray()
    )

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
            "-m", "mark", "--mark", family.mark, "-p", "udp",
            "-j", "TPROXY", "--on-port", family.tproxyPort.toString(), "--tproxy-mark", family.mark
        )
        appendInputProtection(destination, family)
        destination += ipPrefix + listOf(
            "rule", "add", "fwmark", family.mark, "table", ROUTE_TABLE, "pref", family.rulePriority
        )
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
        destination += listOf(
            family.binary, "-t", "mangle", "-A", family.outChain,
            "-p", "udp", "--dport", "53", "-j", "MARK", "--set-mark", family.mark
        )
        appendRootUidReturn(destination, family.binary, "mangle", family.outChain)
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
        destination += listOf(
            family.binary, "-t", "nat", "-A", family.redirectChain,
            "-p", "tcp", "--dport", "53",
            "-j", "REDIRECT", "--to-ports", family.redirectPort.toString()
        )
        appendRootUidReturn(destination, family.binary, "nat", family.redirectChain)
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
        destination += listOf(binary, "-t", table, "-A", chain, "-d", family.localBypass, "-j", "RETURN")
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
        family: TransparentFamily
    ) {
        destination += listOf(
            family.binary, "-t", "filter", "-A", family.inputChain,
            "-m", "mark", "--mark", family.mark, "-j", "ACCEPT"
        )
        destination += listOf(
            family.binary, "-t", "filter", "-A", family.inputChain,
            "-i", "lo", "-p", "tcp", "--dport", family.redirectPort.toString(), "-j", "ACCEPT"
        )
        destination += listOf(
            family.binary, "-t", "filter", "-A", family.inputChain,
            "-p", "tcp", "--dport", family.redirectPort.toString(), "-j", "REJECT"
        )
        destination += listOf(
            family.binary, "-t", "filter", "-A", family.inputChain,
            "-p", "udp", "--dport", family.tproxyPort.toString(), "-j", "REJECT"
        )
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

class RootNetfilterManager(
    private val executor: RootCommandExecutor = ProcessRootCommandExecutor()
) {
    companion object {
        private const val TAG = "RootNetfilterManager"
        private const val MAX_CLEANUP_REPEATS = 32
    }

    fun prepareForStart(staleRuntimePresent: Boolean): Result<Unit> = runCatching {
        if (staleRuntimePresent) Log.i(TAG, "Cleaning stale Root runtime before start")
        cleanup(RootNetfilterPlanner.cleanupCommands())
        checkNoResidualState()
    }

    fun apply(config: RootNetfilterConfig): Result<Unit> = runCatching {
        val plan = RootNetfilterPlanner.build(config)
        checkNoResidualState()
        try {
            plan.setupCommands.forEach(::executeRequired)
            plan.verifyCommands.forEach(::executeRequired)
            verifyPolicyRouting(config)
        } catch (error: Exception) {
            cleanup(plan.cleanupCommands)
            val cleanupError = runCatching { checkNoResidualState() }.exceptionOrNull()
            if (cleanupError != null) {
                throw IllegalStateException("KunBox rules remain after failed installation", cleanupError)
            }
            throw error
        }
    }

    fun cleanup(): Result<Unit> = runCatching {
        cleanup(RootNetfilterPlanner.cleanupCommands())
        checkNoResidualState()
    }

    private fun cleanup(commands: List<List<String>>) {
        commands.forEach(::cleanupCommand)
    }

    private fun cleanupCommand(command: List<String>) {
        if (!isRepeatableCleanup(command)) {
            runCatching { executor.execute(command) }
                .onFailure { Log.d(TAG, "Cleanup command skipped: ${command.firstOrNull()}") }
            return
        }
        var attempts = 0
        while (attempts++ < MAX_CLEANUP_REPEATS) {
            val result = runCatching { executor.execute(command) }.getOrElse {
                Log.d(TAG, "Cleanup command skipped: ${command.firstOrNull()}")
                return
            }
            if (!result.success) break
        }
    }

    private fun isRepeatableCleanup(command: List<String>): Boolean =
        "-D" in command || ("rule" in command && "del" in command)

    private fun executeRequired(command: List<String>) {
        val result = executor.execute(command)
        check(result.success) {
            "Root command failed (${result.exitCode}): ${command.joinToString(" ")} ${result.output}"
        }
    }

    private fun checkNoResidualState() {
        val tableRules = buildString {
            append(tableProbeCommands().map(::executeRequiredResult).joinToString("\n") { it.output })
            append('\n')
            append(optionalIpv6NatRules())
        }
        val ipv4Rules = executeRequiredResult(listOf("ip", "rule", "show")).output
        val ipv6Rules = executeRequiredResult(listOf("ip", "-6", "rule", "show")).output
        val ipv4Routes = executeRequiredResult(listOf("ip", "route", "show", "table", "all")).output
        val ipv6Routes = executeRequiredResult(listOf("ip", "-6", "route", "show", "table", "all")).output
        val chainNames = listOf(
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
        )
        check(
            chainNames.none(tableRules::contains) &&
                RootNetfilterPlanner.ROUTE_TABLE !in ipv4Rules &&
                RootNetfilterPlanner.ROUTE_TABLE !in ipv6Rules &&
                "table ${RootNetfilterPlanner.ROUTE_TABLE}" !in ipv4Routes &&
                "table ${RootNetfilterPlanner.ROUTE_TABLE}" !in ipv6Routes
        ) { "KunBox netfilter state remains after cleanup" }
    }

    private fun verifyPolicyRouting(config: RootNetfilterConfig) {
        if (config.proxyIpv4) {
            val rules = executeRequiredResult(listOf("ip", "rule", "show")).output
            val routes = executeRequiredResult(
                listOf("ip", "route", "show", "table", RootNetfilterPlanner.ROUTE_TABLE)
            ).output
            check(RootNetfilterPlanner.IPV4_MARK in rules && RootNetfilterPlanner.ROUTE_TABLE in rules)
            check(routes.isNotBlank()) { "IPv4 local policy route is missing" }
        }
        if (config.proxyIpv6) {
            val rules = executeRequiredResult(listOf("ip", "-6", "rule", "show")).output
            val routes = executeRequiredResult(
                listOf("ip", "-6", "route", "show", "table", RootNetfilterPlanner.ROUTE_TABLE)
            ).output
            check(RootNetfilterPlanner.IPV6_MARK in rules && RootNetfilterPlanner.ROUTE_TABLE in rules)
            check(routes.isNotBlank()) { "IPv6 local policy route is missing" }
        }
    }

    private fun executeRequiredResult(command: List<String>): RootCommandResult =
        executor.execute(command).also { result ->
            check(result.success) {
                "Root probe failed (${result.exitCode}): ${command.joinToString(" ")} ${result.output}"
            }
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
}
