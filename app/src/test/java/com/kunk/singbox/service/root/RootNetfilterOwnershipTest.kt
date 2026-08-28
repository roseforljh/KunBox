package com.kunk.singbox.service.root

import com.kunk.singbox.model.RootRoutingConstants
import java.nio.file.Files
import org.junit.Assert.assertEquals
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
