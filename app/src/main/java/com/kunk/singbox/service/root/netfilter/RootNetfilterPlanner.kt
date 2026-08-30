@file:Suppress("InvalidPackageDeclaration")

package com.kunk.singbox.service.root

import com.kunk.singbox.model.RootRoutingConstants

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

private val ROOT_GUARD_CHAINS = setOf("KBX_GUARD4", "KBX_GUARD6")

internal fun isTrafficAffectingKunBoxIptablesReference(line: String): Boolean =
    isTrafficAffectingKunBoxIptablesReference(line, allowGuard = false)

internal fun isTrafficAffectingKunBoxIptablesReference(line: String, allowGuard: Boolean): Boolean {
    val fields = line.trim().split(' ').filter(String::isNotBlank)
    val jumpIndex = fields.indexOf("-j")
    val parent = fields.getOrNull(1).orEmpty()
    val target = fields.getOrNull(jumpIndex + 1).orEmpty()
    return fields.firstOrNull() == "-A" &&
        parent.isNotBlank() &&
        !parent.startsWith("KBX_") &&
        target.startsWith("KBX_") &&
        !(allowGuard && parent == "OUTPUT" && target in setOf("KBX_GUARD4", "KBX_GUARD6"))
}

@Suppress("CognitiveComplexMethod")
internal fun trafficAffectingKunBoxNftReferences(output: String): List<String> =
    trafficAffectingKunBoxNftReferences(output, allowGuard = false)

@Suppress("CognitiveComplexMethod")
internal fun trafficAffectingKunBoxNftReferences(output: String, allowGuard: Boolean): List<String> {
    var depth = 0
    var table = "<unknown>"
    var chain = "<unknown>"
    var chainDepth = Int.MAX_VALUE
    return buildList {
        output.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.startsWith("table ") && line.endsWith("{")) {
                table = line.removeSuffix("{").trim()
            }
            if (line.startsWith("chain ") && line.endsWith("{")) {
                chain = line.removePrefix("chain ").removeSuffix("{").trim()
                chainDepth = depth + 1
            }
            val isGuardHook = isAllowedKunBoxNftGuardHook(line, chain, allowGuard)
            val hasKunBoxJump = "jump KBX_" in line || "goto KBX_" in line
            val isExternalChain = !chain.startsWith("KBX_")
            if (hasKunBoxJump && isExternalChain && !isGuardHook) {
                add("$table chain=$chain rule=$line")
            }
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth < chainDepth) {
                chain = "<unknown>"
                chainDepth = Int.MAX_VALUE
            }
        }
    }
}

private fun isAllowedKunBoxNftGuardHook(line: String, chain: String, allowGuard: Boolean): Boolean =
    allowGuard && chain == "OUTPUT" && ROOT_GUARD_CHAINS.any { target ->
        line.contains("jump $target") || line.contains("goto $target")
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
                "table", ROUTE_TABLE
            )
        )
        add(
            listOf(
                "ip", "-6", "route", "del", "local", "::/0", "dev", "lo",
                "table", ROUTE_TABLE
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
            "route", "add", "local", family.localRoute, "dev", "lo", "table", ROUTE_TABLE
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
                "pref", priority
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
