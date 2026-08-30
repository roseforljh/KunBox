package com.kunk.singbox.service.root

import com.kunk.singbox.model.RootRoutingConstants
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootNetfilterOwnershipTest {
    @Test
    fun canonicalManifestRoundTripsProductionRulesRoutesAndChains() {
        val context = RootNetfilterOwnership.context("session-1", 1L, "a".repeat(64))
        val plan = RootNetfilterPlanner.build(configWithLane(0))
        val manifest = RootNetfilterOwnership.fromCommands(context, plan.setupCommands)
        val directory = Files.createTempDirectory("root-owner-test").toFile()
        val file = directory.resolve("netfilter-owner")
        try {
            RootNetfilterOwnership.writeActive(manifest, file)

            assertEquals(manifest, RootNetfilterOwnership.read(file))
            assertTrue(manifest.records.any { it is RootNetfilterOwnerRecord.Rule })
            assertTrue(
                manifest.records.filterIsInstance<RootNetfilterOwnerRecord.Rule>().all { it.protocol == 0 }
            )
            assertTrue(manifest.records.any { it is RootNetfilterOwnerRecord.Route })
            assertTrue(
                manifest.records.filterIsInstance<RootNetfilterOwnerRecord.Route>().all { it.protocol == 0 }
            )
            assertTrue(manifest.records.any { it is RootNetfilterOwnerRecord.Chain })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnsafeSessionIdentifier() {
        assertThrows(IllegalArgumentException::class.java) {
            RootNetfilterOwnership.context("session\nmalformed", 1L, "a".repeat(64))
        }
    }

    @Test
    fun reservedPolicyRangeCoversGenericAndAll128LanesOnly() {
        val tuples = RootNetfilterOwnership.reservedPolicyTuples()

        assertEquals(2 + RootRoutingConstants.MAX_LANES * 2, tuples.size)
        assertTrue(RootRoutingConstants.GENERIC_MARK_IPV4 to RootRoutingConstants.GENERIC_PRIORITY_IPV4 in tuples)
        assertTrue(RootRoutingConstants.markIpv4(127) to RootRoutingConstants.priorityIpv4(127) in tuples)
        assertTrue(RootRoutingConstants.markIpv6(127) to RootRoutingConstants.priorityIpv6(127) in tuples)
    }

    @Test
    fun reservedPolicyVerifierRequiresExactPriorityMarkAndTable() {
        assertTrue(
            RootNetfilterOwnership.isReservedPolicyLine(
                "12031: from all fwmark 0x2331/0xffffffff lookup 20231"
            )
        )
        assertFalse(
            RootNetfilterOwnership.isReservedPolicyLine(
                "12031: from all fwmark 0x9999/0xffffffff lookup 999"
            )
        )
    }

    @Test
    fun refreshesEveryChainFingerprintWithOneBatchProbe() {
        val context = RootNetfilterOwnership.context("session-2", 2L, "b".repeat(64))
        val manifest = RootNetfilterOwnership.fromCommands(
            context,
            RootNetfilterPlanner.build(configWithLane(0)).setupCommands
        )
        var batches = 0
        val executor = RootCommandExecutor { RootCommandResult(1, "unexpected single probe") }.let { fallback ->
            object : RootCommandExecutor by fallback {
                override fun executeBatch(commands: List<List<String>>): RootCommandResult {
                    batches += 1
                    return RootCommandResult(
                        0,
                        commands.joinToString("\n") { command ->
                            val chain = command.last()
                            "-N $chain\n-A $chain -j RETURN"
                        }
                    )
                }
            }
        }

        val refreshed = RootNetfilterOwnership.refreshChainFingerprints(manifest, executor)

        assertEquals(1, batches)
        assertTrue(
            refreshed.records.filterIsInstance<RootNetfilterOwnerRecord.Chain>().all { record ->
                record.rulesSha256 == RootNetfilterOwnership.sha256(
                    "-N ${record.chain}\n-A ${record.chain} -j RETURN"
                )
            }
        )
    }

    @Test
    fun refreshesChainFingerprintsFromVerifiedRestoreSnapshotWithoutRootProbe() {
        val context = RootNetfilterOwnership.context("session-snapshot", 3L, "c".repeat(64))
        val manifest = RootNetfilterOwnership.fromCommands(
            context,
            RootNetfilterPlanner.build(configWithLane(0)).setupCommands
        )
        val snapshot = mapOf(
            ROOT_STATE_IPTABLES4 to manifest.records.filterIsInstance<RootNetfilterOwnerRecord.Chain>()
                .joinToString("\n") { record ->
                    ":${record.chain} - [0:0]\n-A ${record.chain} -j RETURN"
                }
        )

        val refreshed = RootNetfilterOwnership.refreshChainFingerprints(manifest, snapshot)

        assertTrue(
            refreshed.records.filterIsInstance<RootNetfilterOwnerRecord.Chain>().all { record ->
                record.rulesSha256 == RootNetfilterOwnership.sha256(
                    "-N ${record.chain}\n-A ${record.chain} -j RETURN"
                )
            }
        )
    }

    @Test
    fun promotesVerifiedStagingOwnershipWithoutReprobingChains() {
        val directory = Files.createTempDirectory("root-owner-promote-test").toFile()
        var probes = 0
        val executor = RootCommandExecutor {
            probes += 1
            RootCommandResult(1, "unexpected probe")
        }
        val store = RootNetfilterOwnershipStore(executor, directory)
        val context = RootNetfilterOwnership.context("session-promote", 3L, "c".repeat(64))
        val commands = RootNetfilterPlanner.buildGuard(
            RootFailClosedConfig(listOf(10_123), emptyList(), emptyList(), 10_234, true, false)
        ).setupCommands + RootNetfilterPlanner.build(configWithLane(0)).setupCommands
        try {
            store.persist(
                RootNetfilterOwnership.fromCommands(context, commands),
                active = false,
                refreshChainFingerprints = false
            )
            store.promoteStagingExcludingChains(setOf("KBX_GUARD4", "KBX_GUARD6"))

            val active = store.readAnyOwner() ?: error("active ownership missing")
            assertEquals(0, probes)
            assertFalse(
                active.records.filterIsInstance<RootNetfilterOwnerRecord.Chain>()
                    .any { it.chain == "KBX_GUARD4" || it.chain == "KBX_GUARD6" }
            )
            assertTrue(active.records.any { it is RootNetfilterOwnerRecord.Chain })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cleanupWithoutManifestRunsScopedLegacyRecoveryThenVerifiesEmptyState() {
        val directory = Files.createTempDirectory("root-owner-cleanup-test").toFile()
        val commands = mutableListOf<List<String>>()
        val executor = RootCommandExecutor { command ->
            commands += command
            RootCommandResult(0, "")
        }
        try {
            val manager = RootNetfilterManager(
                executor,
                RootNetfilterOwnershipStore(executor, directory)
            )

            assertTrue(manager.cleanup().isSuccess)
            assertTrue(commands.any { it.takeLast(1) == listOf("legacy-cleanup") })
            assertEquals(2, commands.size)
            assertEquals(listOf("nft", "-a", "list", "ruleset"), commands.last())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun startupIgnoresUnreferencedLegacyChainMetadata() {
        val directory = Files.createTempDirectory("root-owner-start-cleanup-test").toFile()
        val commands = mutableListOf<List<String>>()
        val executor = RootCommandExecutor { command ->
            commands += command
            RootCommandResult(
                0,
                if (command == listOf("iptables", "-t", "mangle", "-S")) "-N KBX_OUT4" else ""
            )
        }
        try {
            val manager = RootNetfilterManager(
                executor,
                RootNetfilterOwnershipStore(executor, directory)
            )

            assertTrue(manager.prepareForStart(staleRuntimePresent = true).isSuccess)
            assertFalse(commands.any { it.takeLast(1) == listOf("legacy-cleanup") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun completedLegacyScanSkipsRepeatedRootProbesOnCleanStarts() {
        val directory = Files.createTempDirectory("root-owner-start-cache-test").toFile()
        val commands = mutableListOf<List<String>>()
        val executor = RootCommandExecutor { command ->
            commands += command
            RootCommandResult(0, "")
        }
        try {
            val manager = RootNetfilterManager(
                executor,
                RootNetfilterOwnershipStore(executor, directory)
            )

            assertTrue(manager.prepareForStart(staleRuntimePresent = false).isSuccess)
            val firstStartCommandCount = commands.size
            assertTrue(firstStartCommandCount > 0)
            assertTrue(manager.prepareForStart(staleRuntimePresent = false).isSuccess)

            assertEquals(firstStartCommandCount, commands.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun startupLegacyCleanupUsesThreeSecondDeadline() {
        val directory = Files.createTempDirectory("root-owner-start-deadline-test").toFile()
        var cleanupTimeoutMs = 0L
        val fallback = RootCommandExecutor { command ->
            RootCommandResult(
                0,
                if (command == listOf("iptables", "-t", "mangle", "-S")) {
                    "-N KBX_OUT4\n-A OUTPUT -j KBX_OUT4"
                } else {
                    ""
                }
            )
        }
        val executor = object : RootCommandExecutor by fallback {
            override fun executeWithTimeout(arguments: List<String>, timeoutMs: Long): RootCommandResult {
                cleanupTimeoutMs = timeoutMs
                return RootCommandResult(0, "")
            }
        }
        try {
            val manager = RootNetfilterManager(
                executor,
                RootNetfilterOwnershipStore(executor, directory)
            )

            assertTrue(manager.prepareForStart(staleRuntimePresent = true).isSuccess)
            assertEquals(3_000L, cleanupTimeoutMs)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun malformedManifestStillInvokesScopedCleanupWithoutSessionConstraint() {
        val directory = Files.createTempDirectory("root-owner-malformed-test").toFile()
        val owner = directory.resolve("netfilter-owner").apply { writeText("damaged") }
        var cleanupCommand = emptyList<String>()
        val executor = RootCommandExecutor { command ->
            cleanupCommand = command
            RootCommandResult(0, "")
        }
        try {
            val result = RootNetfilterOwnershipStore(executor, directory).cleanupAnyOwner()

            assertTrue(result.isSuccess)
            assertEquals(
                listOf("/system/bin/sh", directory.resolve("cleanup-owned.sh").path, "cleanup"),
                cleanupCommand
            )
            assertFalse(owner.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cleanupFailureUsesScriptDiagnosticsWithoutStartingRedundantRootProbes() {
        val directory = Files.createTempDirectory("root-owner-script-diagnostics-test").toFile()
        directory.resolve("netfilter-owner").writeText("damaged")
        val commands = mutableListOf<List<String>>()
        val executor = RootCommandExecutor { command ->
            commands += command
            RootCommandResult(75, "", "[ROOT_NET_QUERY] binary=ip6tables classification=QUERY_FAILED")
        }
        try {
            val result = RootNetfilterOwnershipStore(executor, directory).cleanupAnyOwner()

            assertTrue(result.isFailure)
            assertEquals(1, commands.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun configWithLane(slot: Int): RootNetfilterConfig = RootNetfilterConfig(
        capturedUids = listOf(10_123),
        capturedUidRanges = emptyList(),
        excludedUids = emptyList(),
        appUid = 10_234,
        proxyIpv4 = true,
        proxyIpv6 = false,
        blockIpv4 = false,
        blockIpv6 = false,
        redirectPortIpv4 = 1_536,
        redirectPortIpv6 = 1_537,
        tproxyPortIpv4 = 1_538,
        tproxyPortIpv6 = 1_539,
        lanes = listOf(
            RootNetfilterLane(
                laneId = "lane-$slot",
                slot = slot,
                uids = listOf(10_123),
                redirectPortIpv4 = RootRoutingConstants.tcpPortIpv4(slot),
                redirectPortIpv6 = RootRoutingConstants.tcpPortIpv6(slot),
                tproxyPortIpv4 = RootRoutingConstants.udpPortIpv4(slot),
                tproxyPortIpv6 = RootRoutingConstants.udpPortIpv6(slot),
                markIpv4 = RootRoutingConstants.markIpv4(slot),
                markIpv6 = RootRoutingConstants.markIpv6(slot),
                priorityIpv4 = RootRoutingConstants.priorityIpv4(slot),
                priorityIpv6 = RootRoutingConstants.priorityIpv6(slot)
            )
        )
    )
}
