package com.kunk.singbox.service.manager

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStormGuardTest {
    @Test
    fun oneSourceBurstTripsTheGuardBeforeProcessFdExhaustion() {
        val guard = ConnectionStormGuard(
            sourceCreationLimit = 4,
            globalCreationLimit = 8,
            sourceActiveLimit = 8,
            globalActiveLimit = 16,
            windowMs = 5_000L
        )
        val events = (1..4).map { index ->
            ConnectionTrafficEventData(
                type = ConnectionTrafficAttributor.EVENT_NEW,
                id = "connection-$index",
                tags = listOf("proxy-node"),
                uid = 10_123,
                packageNames = listOf("com.example.storm"),
                network = "tcp",
                protocol = "tls",
                outbound = "proxy-node",
                chain = listOf("front-node", "proxy-node")
            )
        }

        val decision = guard.observe(reset = false, events = events, nowMs = 1_000L)

        assertEquals(ConnectionStormReason.SOURCE_CREATION_RATE, decision?.reason)
        assertEquals(10_123, decision?.offender?.uid)
        assertEquals(4, decision?.newConnectionsInWindow)
        assertEquals(mapOf("proxy-node" to 4), decision?.outboundCounts)
        assertEquals(mapOf("front-node>proxy-node" to 4), decision?.chainCounts)
        assertEquals(mapOf("tcp/tls" to 4), decision?.protocolCounts)
        assertTrue(decision?.closeAll == true)
    }

    @Test
    fun attributionSnapshotReportsActiveConnectionsAcrossAllDimensions() {
        val guard = ConnectionStormGuard()
        guard.observe(
            reset = false,
            events = listOf(
                ConnectionTrafficEventData(
                    type = ConnectionTrafficAttributor.EVENT_NEW,
                    id = "connection-1",
                    uid = 10_123,
                    packageNames = listOf("com.example.first"),
                    network = "tcp",
                    protocol = "tls",
                    outbound = "node-a",
                    chain = listOf("front-a", "node-a")
                ),
                ConnectionTrafficEventData(
                    type = ConnectionTrafficAttributor.EVENT_NEW,
                    id = "connection-2",
                    uid = 10_123,
                    packageNames = listOf("com.example.first"),
                    network = "tcp",
                    protocol = "tls",
                    outbound = "node-a",
                    chain = listOf("front-a", "node-a")
                ),
                ConnectionTrafficEventData(
                    type = ConnectionTrafficAttributor.EVENT_NEW,
                    id = "connection-3",
                    uid = 10_456,
                    packageNames = listOf("com.example.second"),
                    network = "udp",
                    protocol = "quic",
                    outbound = "node-b",
                    chain = listOf("node-b")
                )
            ),
            nowMs = 1_000L
        )

        val snapshot = guard.snapshot()

        assertEquals(3, snapshot.activeConnections)
        assertEquals(mapOf("node-a" to 2, "node-b" to 1), snapshot.outboundCounts)
        assertEquals(mapOf("front-a>node-a" to 2, "node-b" to 1), snapshot.chainCounts)
        assertEquals(mapOf("tcp/tls" to 2, "udp/quic" to 1), snapshot.protocolCounts)
        assertEquals(
            mapOf("com.example.first" to 2, "com.example.second" to 1),
            snapshot.applicationCounts
        )
    }

    @Test
    fun repeatedNewEventForSameConnectionIsCountedOnce() {
        val guard = ConnectionStormGuard(
            sourceCreationLimit = 2,
            globalCreationLimit = 4,
            sourceActiveLimit = 4,
            globalActiveLimit = 8,
            windowMs = 5_000L
        )
        val event = ConnectionTrafficEventData(
            type = ConnectionTrafficAttributor.EVENT_NEW,
            id = "connection-1",
            uid = 10_123
        )

        assertEquals(null, guard.observe(reset = false, events = listOf(event), nowMs = 1_000L))
        assertEquals(null, guard.observe(reset = false, events = listOf(event), nowMs = 2_000L))
    }

    @Test
    fun acknowledgedCloseKeepsSourceQuarantinedWithoutRetriggeringStaleConnections() {
        val guard = ConnectionStormGuard(
            sourceCreationLimit = 2,
            globalCreationLimit = 4,
            sourceActiveLimit = 4,
            globalActiveLimit = 8,
            windowMs = 5_000L,
            quarantineMs = 60_000L
        )
        val burst = (1..2).map { index ->
            ConnectionTrafficEventData(
                type = ConnectionTrafficAttributor.EVENT_NEW,
                id = "connection-$index",
                uid = 10_123
            )
        }
        val decision = requireNotNull(guard.observe(reset = false, events = burst, nowMs = 1_000L))

        guard.acknowledgeClosed(decision)

        assertEquals(null, guard.observe(reset = false, events = emptyList(), nowMs = 2_000L))
        val quarantined = guard.observe(
            reset = false,
            events = listOf(
                ConnectionTrafficEventData(
                    type = ConnectionTrafficAttributor.EVENT_NEW,
                    id = "connection-3",
                    uid = 10_123
                )
            ),
            nowMs = 3_000L
        )
        assertEquals(ConnectionStormReason.QUARANTINED_SOURCE, quarantined?.reason)
    }

    @Test
    fun failedDialBurstNeverClosesAllConnectionsWhenNoMatchingConnectionExists() {
        val guard = ConnectionStormGuard(quarantineMs = 60_000L)

        val decision = guard.observeOutboundFailureBurst(
            outboundTag = "app-route-node",
            failureCount = 3,
            nowMs = 1_000L
        )

        assertEquals(ConnectionStormReason.OUTBOUND_FAILURE_BURST, decision?.reason)
        assertEquals(mapOf("app-route-node" to 3), decision?.outboundCounts)
        assertFalse(decision?.closeAll == true)
        assertEquals(emptySet<String>(), decision?.connectionIds)
        assertEquals(
            null,
            guard.observeOutboundFailureBurst(
                outboundTag = "app-route-node",
                failureCount = 3,
                nowMs = 2_000L
            )
        )
    }

    @Test
    fun failedDialBurstUsesAStableIncidentCloseReasonInEveryServiceMode() {
        val decision = requireNotNull(
            ConnectionStormGuard().observeOutboundFailureBurst(
                outboundTag = "app-route-node",
                failureCount = 3,
                nowMs = 1_000L
            )
        )

        assertEquals("close_failed_outbound", decision.incidentCloseReason())
    }

    @Test
    fun failedDialBurstTargetsOnlyConnectionsUsingTheFailedOutbound() {
        val guard = ConnectionStormGuard(quarantineMs = 60_000L)
        guard.observe(
            reset = false,
            events = listOf(
                ConnectionTrafficEventData(
                    type = ConnectionTrafficAttributor.EVENT_NEW,
                    id = "app-route",
                    outbound = "app-route-node"
                ),
                ConnectionTrafficEventData(
                    type = ConnectionTrafficAttributor.EVENT_NEW,
                    id = "main-route",
                    tags = listOf("main-node"),
                    outbound = "main-node"
                )
            ),
            nowMs = 500L
        )

        val decision = guard.observeOutboundFailureBurst(
            outboundTag = "app-route-node",
            failureCount = 3,
            nowMs = 1_000L
        )

        assertFalse(decision?.closeAll == true)
        assertEquals(setOf("app-route"), decision?.connectionIds)
    }

    @Test
    fun activeConnectionIdsForOutboundUseOutboundTagsAndChainWithoutCollateralMatches() {
        val guard = ConnectionStormGuard()
        guard.observe(
            reset = false,
            events = listOf(
                ConnectionTrafficEventData(
                    type = ConnectionTrafficAttributor.EVENT_NEW,
                    id = "outbound-match",
                    outbound = "bad-node"
                ),
                ConnectionTrafficEventData(
                    type = ConnectionTrafficAttributor.EVENT_NEW,
                    id = "tag-match",
                    tags = listOf("bad-node")
                ),
                ConnectionTrafficEventData(
                    type = ConnectionTrafficAttributor.EVENT_NEW,
                    id = "chain-match",
                    chain = listOf("front", "bad-node")
                ),
                ConnectionTrafficEventData(
                    type = ConnectionTrafficAttributor.EVENT_NEW,
                    id = "healthy",
                    outbound = "healthy-node"
                )
            ),
            nowMs = 1_000L
        )

        assertEquals(
            setOf("outbound-match", "tag-match", "chain-match"),
            guard.activeConnectionIdsForOutbound("bad-node")
        )
    }

    @Test
    fun targetedCloseAcknowledgementRemovesOnlyClosedConnectionIds() {
        val guard = ConnectionStormGuard()
        guard.observe(
            reset = false,
            events = listOf(
                ConnectionTrafficEventData(
                    type = ConnectionTrafficAttributor.EVENT_NEW,
                    id = "closed",
                    outbound = "bad-node"
                ),
                ConnectionTrafficEventData(
                    type = ConnectionTrafficAttributor.EVENT_NEW,
                    id = "remaining",
                    outbound = "bad-node"
                )
            ),
            nowMs = 1_000L
        )

        guard.acknowledgeClosedConnectionIds(setOf("closed"))

        assertEquals(setOf("remaining"), guard.activeConnectionIdsForOutbound("bad-node"))
    }

    @Test
    fun metadataFreeUpdateKeepsOriginalOutboundForTargetedClose() {
        val guard = ConnectionStormGuard()
        guard.observe(
            reset = false,
            events = listOf(
                ConnectionTrafficEventData(
                    type = ConnectionTrafficAttributor.EVENT_NEW,
                    id = "connection",
                    outbound = "bad-node"
                )
            ),
            nowMs = 1_000L
        )
        guard.observe(
            reset = false,
            events = listOf(
                ConnectionTrafficEventData(
                    type = ConnectionTrafficAttributor.EVENT_UPDATE,
                    id = "connection",
                    downloadDelta = 100L
                )
            ),
            nowMs = 2_000L
        )

        assertEquals(setOf("connection"), guard.activeConnectionIdsForOutbound("bad-node"))
    }

    @Test
    fun incidentHistoryPersistsBoundedAttributionSnapshots() {
        val directory = Files.createTempDirectory("kunbox-connection-incidents").toFile()
        val file = directory.resolve("connection_incidents.jsonl")
        try {
            val history = ConnectionIncidentHistory(file, maxSnapshots = 2)
            repeat(3) { index ->
                history.append(
                    ConnectionIncidentSnapshot(
                        timestampEpochMs = index.toLong(),
                        elapsedRealtimeMs = index.toLong(),
                        mode = "vpn",
                        reason = ConnectionStormReason.SOURCE_CREATION_RATE.name,
                        closeReason = "storm_guard",
                        closeSucceeded = true,
                        activeConnections = 1_024 + index,
                        newConnectionsInWindow = 256,
                        creationRatePerSecond = 51.2,
                        uid = 10_123,
                        packageNames = listOf("com.example.storm"),
                        inbound = "tun-in",
                        source = "10.0.0.2:12345",
                        outboundCounts = mapOf("proxy-node" to 256),
                        chainCounts = mapOf("front-node>proxy-node" to 256),
                        protocolCounts = mapOf("tcp/tls" to 256)
                    )
                )
            }

            val retained = history.read()
            assertEquals(listOf(1L, 2L), retained.map { it.timestampEpochMs })
            assertEquals(listOf("com.example.storm"), retained.last().packageNames)
            assertEquals(mapOf("front-node>proxy-node" to 256), retained.last().chainCounts)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun incidentHistoryNormalizesLegacyRowsBeforeDiagnosticExport() {
        val directory = Files.createTempDirectory("kunbox-legacy-connection-incidents").toFile()
        val file = directory.resolve("connection_incidents.jsonl")
        try {
            file.writeText(
                """{"timestampEpochMs":1,"elapsedRealtimeMs":2,"mode":"root","reason":"legacy",""" +
                    """"closeReason":"legacy","closeSucceeded":true}""" + "\n"
            )

            val snapshots = ConnectionIncidentHistory(file).read()
            val exported = formatConnectionIncidentSnapshotsJsonl(snapshots)

            assertTrue(exported.contains("\"package_names\":[]"))
            assertTrue(exported.contains("\"outbounds\":[]"))
            assertTrue(exported.contains("\"chains\":[]"))
            assertTrue(exported.contains("\"protocols\":[]"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
