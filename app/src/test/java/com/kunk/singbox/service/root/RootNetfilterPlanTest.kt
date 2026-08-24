package com.kunk.singbox.service.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootNetfilterPlanTest {
    @Test
    fun fastSetupUsesRestoreAndActivatesHooksAfterStaging() {
        val plan = RootNetfilterPlanner.build(
            RootNetfilterConfig(
                capturedUids = listOf(10123),
                capturedUidRanges = emptyList(),
                excludedUids = listOf(10124),
                appUid = 10234,
                proxyIpv4 = true,
                proxyIpv6 = true,
                blockIpv4 = false,
                blockIpv6 = false,
                blockQuic = true,
                redirectPortIpv4 = 1536,
                redirectPortIpv6 = 1537,
                tproxyPortIpv4 = 1538,
                tproxyPortIpv6 = 1539
            )
        )

        val script = requireNotNull(buildRootNetfilterRestoreScript(plan.setupCommands))

        assertTrue(script.contains("iptables-restore --noflush"))
        assertTrue(script.contains("ip6tables-restore --noflush"))
        assertTrue(script.contains(":KBX_OUT4 - [0:0]"))
        assertTrue(script.contains("-A KBX_OUT4 -m owner --uid-owner 10123 -p udp"))
        assertTrue(
            script.indexOf("'ip' 'rule' 'add'") <
                script.indexOf("'iptables' '-t' 'mangle' '-I' 'PREROUTING'")
        )
        assertEquals(1, script.split("iptables-restore --noflush").size - 1)
        assertEquals(1, script.split("ip6tables-restore --noflush").size - 1)
    }

    @Test
    fun successfulFastSetupSkipsLegacyVerificationProcesses() {
        var legacyCalls = 0
        var fastCalls = 0
        val executor = object : RootCommandExecutor {
            override fun execute(arguments: List<String>): RootCommandResult {
                legacyCalls++
                return RootCommandResult(0, "")
            }

            override fun executeFastNetfilterPlan(commands: List<List<String>>): RootCommandResult {
                fastCalls++
                return RootCommandResult(0, "")
            }
        }
        val result = RootNetfilterManager(executor).apply(
            RootNetfilterConfig(
                capturedUids = listOf(10123),
                capturedUidRanges = emptyList(),
                excludedUids = emptyList(),
                appUid = 10234,
                proxyIpv4 = true,
                proxyIpv6 = false,
                blockIpv4 = false,
                blockIpv6 = false,
                redirectPortIpv4 = 1536,
                redirectPortIpv6 = 1537,
                tproxyPortIpv4 = 1538,
                tproxyPortIpv6 = 1539
            )
        )

        assertTrue(result.isSuccess)
        assertEquals(1, fastCalls)
        assertEquals(0, legacyCalls)
    }

    @Test
    fun defaultBatchExecutorKeepsProbeOutput() {
        val executor = RootCommandExecutor { command -> RootCommandResult(0, command.last()) }

        val result = executor.executeBatch(listOf(listOf("probe", "first"), listOf("probe", "second")))

        assertEquals("first\nsecond", result.output)
    }

    @Test
    fun batchScriptQuotesArgumentsAndReportsTheFailedCommand() {
        val script = buildRootCommandBatchScript(
            listOf(
                listOf("iptables", "-A", "name with space", "value'quoted"),
                listOf("ip", "rule", "show")
            )
        )

        assertTrue(script.contains("'name with space'"))
        assertTrue(script.contains("'value'\"'\"'quoted'"))
        assertTrue(script.contains("Batch command 0 failed"))
        assertTrue(script.contains("Batch command 1 failed"))
        assertTrue(script.contains("exit \"\$kb_status\""))
    }

    @Test
    fun cleanupBatchRepeatsOnlyCommandsThatCanHaveDuplicates() {
        val script = buildRootCommandBatchScript(
            commands = listOf(
                listOf("iptables", "-D", "OUTPUT", "-j", "KBX_OUT4"),
                listOf("iptables", "-F", "KBX_OUT4")
            ),
            repeatUntilFailure = setOf(0),
            maxAttempts = 32
        )

        assertEquals(1, script.split("while [").size - 1)
        assertTrue(script.contains("-lt 32"))
        assertTrue(script.contains("'iptables' '-F' 'KBX_OUT4' >/dev/null 2>&1 || :"))
    }

    @Test
    fun cleanupFailsClosedWhenResidualStateCannotBeQueried() {
        val executor = RootCommandExecutor { command ->
            if (command.takeLast(2) == listOf("table", "all") || command.lastOrNull() == "-S") {
                RootCommandResult(1, "permission denied")
            } else {
                RootCommandResult(1, "not present")
            }
        }

        assertTrue(RootNetfilterManager(executor).cleanup().isFailure)
    }

    @Test
    fun cleanupAcceptsKernelWithoutIpv6NatTable() {
        val executor = RootCommandExecutor { command ->
            when {
                command == listOf("ip6tables", "-t", "nat", "-S") -> RootCommandResult(
                    3,
                    "ip6tables: can't initialize ip6tables table 'nat': Table does not exist"
                )
                "-D" in command || "-F" in command || "-X" in command || "del" in command ->
                    RootCommandResult(1, "not present")
                else -> RootCommandResult(0, "")
            }
        }

        assertTrue(RootNetfilterManager(executor).cleanup().isSuccess)
    }

    @Test
    fun compactsConsecutiveUidsIntoOwnerRanges() {
        assertEquals(
            listOf(RootUidRange(10001, 10003), RootUidRange(10005, 10005)),
            compactRootUids(listOf(10003, 10001, 10002, 10005, 10001))
        )
    }

    @Test
    fun buildsDualStackUidRulesAndActivatesMainChainsLast() {
        val plan = RootNetfilterPlanner.build(
            RootNetfilterConfig(
                capturedUids = listOf(10123, 10124, 10123),
                capturedUidRanges = emptyList(),
                excludedUids = emptyList(),
                appUid = 10234,
                proxyIpv4 = true,
                proxyIpv6 = true,
                blockIpv4 = false,
                blockIpv6 = false,
                redirectPortIpv4 = 1536,
                redirectPortIpv6 = 1537,
                tproxyPortIpv4 = 1538,
                tproxyPortIpv6 = 1539
            )
        )

        val commands = plan.setupCommands.map { it.joinToString(" ") }
        assertTrue(commands.any { "iptables -t mangle -A KBX_OUT4 -m owner --uid-owner 10123-10124" in it })
        assertTrue(commands.any { "ip6tables -t mangle -A KBX_OUT6 -m owner --uid-owner 10123-10124" in it })
        assertTrue(commands.any { "TPROXY --on-port 1538" in it })
        assertTrue(commands.any { "TPROXY --on-port 1539" in it })
        assertTrue(commands.any { "iptables -t nat -A KBX_RED4" in it && "-p tcp -j REDIRECT" in it })
        assertTrue(commands.any { "ip6tables -t nat -A KBX_RED6" in it && "-p tcp -j REDIRECT" in it })
        assertFalse(commands.any { "KBX_OUT4" in it && "-p tcp -j MARK" in it })
        assertTrue(commands.any { "iptables -t mangle -A KBX_OUT4 -d 127.0.0.0/8 -j RETURN" == it })
        assertTrue(commands.any { "ip6tables -t mangle -A KBX_OUT6 -d ::1/128 -j RETURN" == it })
        assertTrue(commands.any { "iptables -t mangle -A KBX_OUT4 -p udp --dport 53" in it })
        assertFalse(commands.any { "iptables -t mangle -A KBX_OUT4 -p udp --dport 853" in it })
        assertFalse(commands.any { "iptables -t nat -A KBX_RED4 -p tcp --dport 853" in it })
        assertTrue(commands.any { "iptables -t filter -A KBX_IN4 -m mark --mark 0x2331 -j ACCEPT" == it })
        assertTrue(commands.any { "iptables -t filter -A KBX_IN4 -p tcp --dport 1536 -j REJECT" == it })
        val firstActivation = commands.indexOfFirst { " -I " in it }
        assertTrue(firstActivation > 0)
        assertTrue(commands.drop(firstActivation).all { " -I " in it })
        assertTrue(commands.indexOfFirst { "PREROUTING" in it && " -I " in it } < commands.indexOfFirst {
            "INPUT" in it && " -I " in it
        })
        assertTrue(commands.indexOfFirst { "INPUT" in it && " -I " in it } < commands.indexOfFirst {
            "OUTPUT" in it && " -I " in it
        })
        assertFalse(commands.any { "com." in it })
    }

    @Test
    fun globalPlainDnsCapturePrecedesPerAppExclusionsByDesign() {
        val commands = RootNetfilterPlanner.build(
            RootNetfilterConfig(
                capturedUids = listOf(10123),
                capturedUidRanges = emptyList(),
                excludedUids = listOf(10124),
                appUid = 10234,
                proxyIpv4 = true,
                proxyIpv6 = false,
                blockIpv4 = false,
                blockIpv6 = false,
                redirectPortIpv4 = 1536,
                redirectPortIpv6 = 1537,
                tproxyPortIpv4 = 1538,
                tproxyPortIpv6 = 1539
            )
        ).setupCommands.map { it.joinToString(" ") }

        val udpDnsRule = commands.indexOfFirst { "KBX_OUT4 -p udp --dport 53" in it }
        val tcpDnsRule = commands.indexOfFirst { "KBX_RED4 -p tcp --dport 53" in it }
        val excludedReturn = commands.indexOfFirst { "KBX_OUT4 -m owner --uid-owner 10124 -j RETURN" in it }
        val redirectExcludedReturn = commands.indexOfFirst {
            "KBX_RED4 -m owner --uid-owner 10124 -j RETURN" in it
        }
        assertTrue(udpDnsRule in 0 until excludedReturn)
        assertTrue(tcpDnsRule in 0 until redirectExcludedReturn)
        assertFalse(commands.any { "--dport 853" in it && "--uid-owner" !in it })
    }

    @Test
    fun systemDnsIsCapturedWhileMarkedCoreSocketsBypass() {
        val commands = RootNetfilterPlanner.build(
            RootNetfilterConfig(
                capturedUids = listOf(10123),
                capturedUidRanges = emptyList(),
                excludedUids = emptyList(),
                appUid = 10234,
                proxyIpv4 = true,
                proxyIpv6 = false,
                blockIpv4 = false,
                blockIpv6 = false,
                redirectPortIpv4 = 1536,
                redirectPortIpv6 = 1537,
                tproxyPortIpv4 = 1538,
                tproxyPortIpv6 = 1539
            )
        ).setupCommands.map { it.joinToString(" ") }

        val bypass = commands.indexOfFirst {
            "KBX_OUT4 -m mark --mark ${RootNetfilterPlanner.CORE_BYPASS_MARK_MATCH} -j RETURN" in it
        }
        val dns = commands.indexOfFirst { "KBX_OUT4 -p udp --dport 53" in it }
        val rootUid = commands.indexOfFirst { "KBX_OUT4 -m owner --uid-owner 0 -j RETURN" in it }

        assertTrue(bypass in 0 until dns)
        assertTrue(dns in 0 until rootUid)
        assertEquals(
            0x100100e1,
            RootNetfilterPlanner.withCoreBypassMark(0x100e1)
        )
        assertThrows(IllegalArgumentException::class.java) {
            RootNetfilterPlanner.withCoreBypassMark(RootNetfilterPlanner.CORE_BYPASS_MARK_MASK)
        }
    }

    @Test
    fun ipv4OnlyBlocksIpv6ForCapturedUids() {
        val plan = RootNetfilterPlanner.build(
            RootNetfilterConfig(
                capturedUids = listOf(10123),
                capturedUidRanges = emptyList(),
                excludedUids = emptyList(),
                appUid = 10234,
                proxyIpv4 = true,
                proxyIpv6 = false,
                blockIpv4 = false,
                blockIpv6 = true,
                redirectPortIpv4 = 1536,
                redirectPortIpv6 = 1537,
                tproxyPortIpv4 = 1538,
                tproxyPortIpv6 = 1539
            )
        )
        val commands = plan.setupCommands.map { it.joinToString(" ") }

        assertTrue(commands.any { "ip6tables -t filter -A KBX_BLOCK6" in it && "--uid-owner 10123" in it })
        assertFalse(commands.any { "ip6tables -t mangle -A KBX_PRE6" in it })
        assertEquals(commands.last(), "ip6tables -t filter -I OUTPUT 1 -j KBX_BLOCK6")
    }

    @Test
    fun blockQuicRejectsCapturedUdp443BeforeTransparentProxy() {
        val plan = RootNetfilterPlanner.build(
            RootNetfilterConfig(
                capturedUids = listOf(10123),
                capturedUidRanges = emptyList(),
                excludedUids = listOf(10124),
                appUid = 10234,
                proxyIpv4 = true,
                proxyIpv6 = false,
                blockIpv4 = false,
                blockIpv6 = false,
                blockQuic = true,
                redirectPortIpv4 = 1536,
                redirectPortIpv6 = 1537,
                tproxyPortIpv4 = 1538,
                tproxyPortIpv6 = 1539
            )
        )
        val commands = plan.setupCommands.map { it.joinToString(" ") }

        assertTrue(commands.any {
            "iptables -t filter -A KBX_QUIC4 -m owner --uid-owner 10123 -p udp --dport 443 -j REJECT" == it
        })
        assertTrue(commands.any { "KBX_QUIC4 -m owner --uid-owner 10124 -j RETURN" in it })
        assertFalse(commands.any { "ip6tables -t filter -A KBX_QUIC6" in it })
        assertTrue(plan.cleanupCommands.any { it.lastOrNull() == RootNetfilterPlanner.CHAIN_QUIC4 })
    }
}
