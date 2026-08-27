package com.kunk.singbox.service.root

import com.kunk.singbox.model.RootRoutingConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootNetfilterPlanTest {
    @Test
    fun twoLanesBindUidTcpUdpAndInputToTheirOwnPortsAndMarks() {
        val lanes = listOf(lane(0, 10123), lane(1, 10124))
        val plan = RootNetfilterPlanner.build(
            RootNetfilterConfig(
                capturedUids = listOf(10123, 10124, 10125),
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
                tproxyPortIpv6 = 1539,
                lanes = lanes
            )
        )
        val commands = plan.setupCommands.map { it.joinToString(" ") }
        val laneUdp = commands.indexOfFirst {
            "KBX_OUT4 -m owner --uid-owner 10123 -p udp -j MARK --set-mark 0x2400" in it
        }
        val genericUdp = commands.indexOfFirst {
            "KBX_OUT4 -m owner --uid-owner 10123-10125 -p udp -j MARK --set-mark 0x2331" in it
        }
        val mdns = commands.indexOfFirst { "KBX_OUT4 -d 224.0.0.251 -p udp --dport 5353" in it }

        assertTrue(mdns in 0 until laneUdp)
        assertTrue(laneUdp in 0 until genericUdp)
        assertFalse(commands.any { "KBX_OUT4 -p udp --dport 53" in it })
        assertTrue(commands.any { "--uid-owner 10123 -p tcp -j REDIRECT --to-ports 16000" in it })
        assertTrue(commands.any { "--uid-owner 10124 -p tcp -j REDIRECT --to-ports 16004" in it })
        assertTrue(commands.any { "--mark 0x2400/0xffffffff -p udp -j TPROXY --on-port 16001" in it })
        assertTrue(commands.any { "--mark 0x2401/0xffffffff -p udp -j TPROXY --on-port 16005" in it })
        assertTrue(commands.any {
            "KBX_IN4 -i lo -p udp -m mark --mark 0x2400/0xffffffff -j ACCEPT" in it
        })
        assertTrue(commands.any {
            "ip rule add fwmark 0x2400/0xffffffff table 20231 pref 12100 protocol 233" == it
        })
        assertTrue(plan.verifyCommands.any { command ->
            command.joinToString(" ").contains(
                "-C KBX_IN4 -i lo -p udp -m mark --mark 0x2401/0xffffffff -j ACCEPT"
            )
        })
    }

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
    fun successfulFastSetupStillPerformsReadOnlyVerification() {
        var legacyCalls = 0
        var fastCalls = 0
        val executor = object : RootCommandExecutor {
            override fun execute(arguments: List<String>): RootCommandResult {
                legacyCalls++
                val output = when {
                    arguments == listOf("ip", "rule", "show") ->
                        "12031: from all fwmark 0x2331/0xffffffff lookup 20231 protocol 233"
                    arguments == listOf("ip", "route", "show", "table", "20231") ->
                        "local 0.0.0.0/0 dev lo table 20231 proto 233"
                    arguments == listOf("iptables", "-t", "mangle", "-S") ||
                        arguments == listOf("iptables", "-t", "filter", "-S") ||
                        arguments == listOf("iptables", "-t", "nat", "-S") -> ""
                    else -> ""
                }
                return RootCommandResult(0, output)
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
        assertTrue(legacyCalls > 0)
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
    fun compactsIntMaxUidWithoutOverflow() {
        assertEquals(
            listOf(RootUidRange(Int.MAX_VALUE - 1, Int.MAX_VALUE)),
            compactRootUids(listOf(Int.MAX_VALUE, Int.MAX_VALUE - 1))
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
        assertTrue(commands.any {
            "iptables -t mangle -A KBX_OUT4 -m owner --uid-owner 10123-10124 -p udp " +
                "-j MARK --set-mark 0x2331" == it
        })
        assertFalse(commands.any { "iptables -t mangle -A KBX_OUT4 -p udp --dport 853" in it })
        assertFalse(commands.any { "iptables -t nat -A KBX_RED4 -p tcp --dport 853" in it })
        assertTrue(commands.any {
            "iptables -t filter -A KBX_IN4 -i lo -p udp -m mark --mark 0x2331/0xffffffff -j ACCEPT" == it
        })
        assertFalse(commands.any { "KBX_IN4 -m mark --mark 0x2331 -j ACCEPT" in it })
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
    fun dnsCaptureFollowsUidPolicyAndNeverCapturesExcludedApps() {
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

        val excludedReturn = commands.indexOfFirst { "KBX_OUT4 -m owner --uid-owner 10124 -j RETURN" in it }
        val redirectExcludedReturn = commands.indexOfFirst {
            "KBX_RED4 -m owner --uid-owner 10124 -j RETURN" in it
        }
        val capturedUdp = commands.indexOfFirst {
            "KBX_OUT4 -m owner --uid-owner 10123 -p udp -j MARK --set-mark 0x2331" in it
        }
        val capturedTcp = commands.indexOfFirst {
            "KBX_RED4 -m owner --uid-owner 10123 -p tcp -j REDIRECT --to-ports 1536" in it
        }
        assertTrue(excludedReturn in 0 until capturedUdp)
        assertTrue(redirectExcludedReturn in 0 until capturedTcp)
        assertFalse(commands.any { "KBX_OUT4 -p udp --dport 53" in it })
        assertFalse(commands.any { "KBX_RED4 -p tcp --dport 53" in it })
        assertFalse(commands.any { "--dport 853" in it && "--uid-owner" !in it })
    }

    @Test
    fun rootAndMarkedCoreSocketsBypassBeforeGenericDnsCapture() {
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
        val capturedUdp = commands.indexOfFirst {
            "KBX_OUT4 -m owner --uid-owner 10123 -p udp -j MARK --set-mark 0x2331" in it
        }
        val rootUid = commands.indexOfFirst { "KBX_OUT4 -m owner --uid-owner 0 -j RETURN" in it }

        assertTrue(bypass in 0 until capturedUdp)
        assertTrue(rootUid in 0 until capturedUdp)
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

    @Test
    fun rootCommandTimeoutTerminatesHungChildProcess() {
        val command = if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "Start-Sleep -Seconds 2")
        } else {
            listOf("sh", "-c", "sleep 2")
        }
        val startedAt = System.nanoTime()

        val result = ProcessRootCommandExecutor(timeoutMs = 100L).execute(command)

        val durationMs = (System.nanoTime() - startedAt) / 1_000_000L
        assertEquals(124, result.exitCode)
        assertTrue("Timed out Root command took ${durationMs}ms", durationMs < 1_500L)
    }

    private fun lane(slot: Int, uid: Int): RootNetfilterLane = RootNetfilterLane(
        laneId = "lane-$slot",
        slot = slot,
        uids = listOf(uid),
        redirectPortIpv4 = RootRoutingConstants.tcpPortIpv4(slot),
        redirectPortIpv6 = RootRoutingConstants.tcpPortIpv6(slot),
        tproxyPortIpv4 = RootRoutingConstants.udpPortIpv4(slot),
        tproxyPortIpv6 = RootRoutingConstants.udpPortIpv6(slot),
        markIpv4 = RootRoutingConstants.markIpv4(slot),
        markIpv6 = RootRoutingConstants.markIpv6(slot),
        priorityIpv4 = RootRoutingConstants.priorityIpv4(slot),
        priorityIpv6 = RootRoutingConstants.priorityIpv6(slot)
    )
}
