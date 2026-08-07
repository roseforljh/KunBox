package com.kunk.singbox.repository

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficSnapshotFileStoreMergeTest {
    @Test
    fun independentProcessStoresMergeDeltasWithoutOverwritingEachOther() {
        val directory = Files.createTempDirectory("traffic-cross-process-").toFile()
        val mainProcessStore = TrafficSnapshotFileStore(directory)
        val vpnProcessStore = TrafficSnapshotFileStore(directory)

        mainProcessStore.mergeDeltas(
            deltas = snapshot("node-a", upload = 10L, download = 20L),
            clearCutoffGeneration = 0L
        )
        vpnProcessStore.mergeDeltas(
            deltas = snapshot("node-a", upload = 3L, download = 7L),
            clearCutoffGeneration = 0L
        )

        val merged = mainProcessStore.load(clearCutoffGeneration = 0L)
        assertEquals(13L, merged?.traffic?.get("node-a")?.upload)
        assertEquals(27L, merged?.traffic?.get("node-a")?.download)
    }

    private fun snapshot(nodeId: String, upload: Long, download: Long): TrafficPersistenceSnapshot {
        val stats = NodeTrafficStats(
            nodeId = nodeId,
            upload = upload,
            download = download,
            lastUpdated = 1L,
            nodeName = "Node A"
        )
        return TrafficPersistenceSnapshot(
            generation = 0L,
            traffic = mapOf(nodeId to stats),
            daily = mapOf(
                "2026-8-8" to DailyTrafficRecord("2026-8-8").apply {
                    nodeStats[nodeId] = stats.copy()
                }
            ),
            monthKey = "2026-8"
        )
    }
}
