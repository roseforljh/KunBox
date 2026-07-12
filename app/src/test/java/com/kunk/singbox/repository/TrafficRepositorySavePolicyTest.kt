package com.kunk.singbox.repository

import com.google.gson.Gson
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficRepositorySavePolicyTest {

    @Test
    fun repeatedRequestsShareOneDelayedSave() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val saveCount = AtomicInteger(0)
        val scheduler = MergedTrafficSaveScheduler(scope, delayMs = 40L) {
            saveCount.incrementAndGet()
            true
        }

        repeat(5) { scheduler.requestSave() }
        delay(180L)

        assertEquals(1, saveCount.get())
        scheduler.cancel()
        scope.cancel()
    }

    @Test
    fun flushPersistsImmediatelyWithoutDelayedDuplicate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val saveCount = AtomicInteger(0)
        val scheduler = MergedTrafficSaveScheduler(scope, delayMs = 200L) {
            saveCount.incrementAndGet()
            true
        }

        scheduler.requestSave()
        assertTrue(scheduler.flush())
        delay(300L)

        assertEquals(1, saveCount.get())
        scheduler.cancel()
        scope.cancel()
    }

    @Test
    fun cancelWaitsForInFlightSaveBeforeReloadOrClearContinues() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val saveStarted = CountDownLatch(1)
        val releaseSave = CountDownLatch(1)
        val cancelCompleted = AtomicBoolean(false)
        val scheduler = MergedTrafficSaveScheduler(scope, delayMs = 0L) {
            saveStarted.countDown()
            releaseSave.await(5, TimeUnit.SECONDS)
            true
        }

        scheduler.requestSave()
        assertTrue(saveStarted.await(5, TimeUnit.SECONDS))
        val cancelJob = async(Dispatchers.Default) {
            scheduler.cancel()
            cancelCompleted.set(true)
        }

        assertFalse(cancelCompleted.get())
        releaseSave.countDown()
        cancelJob.await()

        assertTrue(cancelCompleted.get())
        scope.cancel()
    }

    @Test
    fun flushSnapshotOlderThanClearCutoffCannotCommit() {
        assertFalse(
            canCommitTrafficSnapshot(
                snapshotGeneration = 41L,
                clearCutoffGeneration = 42L
            )
        )
    }

    @Test
    fun flushSnapshotAtClearCutoffCanCommit() {
        assertTrue(
            canCommitTrafficSnapshot(
                snapshotGeneration = 42L,
                clearCutoffGeneration = 42L
            )
        )
    }

    @Test
    fun staleSnapshotWrittenAfterClearIsIgnored() = withTempDirectory { directory ->
        val store = TrafficSnapshotFileStore(directory)
        val staleSnapshot = snapshot(generation = 10L, upload = 100L, download = 200L)
        val clearedSnapshot = emptySnapshot(generation = 11L)

        assertTrue(store.save(staleSnapshot, clearCutoffGeneration = 10L))
        assertTrue(store.save(clearedSnapshot, clearCutoffGeneration = 11L))

        val snapshotFile = directory.listFiles().orEmpty().single { it.extension == "json" }
        snapshotFile.writeText(Gson().toJson(staleSnapshot), Charsets.UTF_8)

        assertNull(store.load(clearCutoffGeneration = 11L))
    }

    @Test
    fun snapshotAtClearGenerationPersistsMonthlyAndDailyTogether() = withTempDirectory { directory ->
        val store = TrafficSnapshotFileStore(directory)
        val snapshot = snapshot(generation = 23L, upload = 321L, download = 654L)

        assertTrue(store.save(snapshot, clearCutoffGeneration = 23L))

        val loaded = requireNotNull(store.load(clearCutoffGeneration = 23L))
        assertEquals(1, directory.listFiles().orEmpty().count { it.extension == "json" })
        assertEquals(23L, loaded.generation)
        assertEquals(321L, loaded.traffic.getValue(NODE_ID).upload)
        assertEquals(654L, loaded.daily.getValue(DATE_KEY).nodeStats.getValue(NODE_ID).download)
    }

    @Test
    fun legacyMonthlyAndDailyFilesMigrateToSingleSnapshot() = withTempDirectory { directory ->
        val gson = Gson()
        val legacySnapshot = snapshot(generation = 0L, upload = 77L, download = 88L)
        val legacyStatsFile = File(directory, "traffic_stats.json")
        val legacyDailyFile = File(directory, "traffic_daily.json")
        legacyStatsFile.writeText(gson.toJson(legacySnapshot.traffic), Charsets.UTF_8)
        legacyDailyFile.writeText(gson.toJson(legacySnapshot.daily), Charsets.UTF_8)

        val loaded = requireNotNull(
            TrafficSnapshotFileStore(directory).load(clearCutoffGeneration = 31L)
        )

        assertEquals(31L, loaded.generation)
        assertEquals(77L, loaded.traffic.getValue(NODE_ID).upload)
        assertEquals(88L, loaded.daily.getValue(DATE_KEY).nodeStats.getValue(NODE_ID).download)
        assertFalse(legacyStatsFile.exists())
        assertFalse(legacyDailyFile.exists())
        assertEquals(1, directory.listFiles().orEmpty().count { it.extension == "json" })
    }

    @Test
    fun loadMaintenanceTransformsLatestSnapshotInsideFileLock() = withTempDirectory { directory ->
        val store = TrafficSnapshotFileStore(directory)
        val oldMonth = snapshot(generation = 9L, upload = 12L, download = 34L)

        assertTrue(store.save(oldMonth, clearCutoffGeneration = 9L))

        val loaded = requireNotNull(store.load(clearCutoffGeneration = 9L) { current ->
            current.copy(traffic = emptyMap(), monthKey = "2026-7")
        })
        val reloaded = requireNotNull(store.load(clearCutoffGeneration = 9L))

        assertTrue(loaded.traffic.isEmpty())
        assertEquals("2026-7", reloaded.monthKey)
        assertTrue(reloaded.traffic.isEmpty())
        assertEquals(34L, reloaded.daily.getValue(DATE_KEY).nodeStats.getValue(NODE_ID).download)
    }

    private fun snapshot(generation: Long, upload: Long, download: Long): TrafficPersistenceSnapshot {
        val monthly = NodeTrafficStats(
            nodeId = NODE_ID,
            upload = upload,
            download = download,
            lastUpdated = generation,
            nodeName = "Test Node"
        )
        val dailyRecord = DailyTrafficRecord(DATE_KEY).apply {
            nodeStats[NODE_ID] = monthly.copy()
        }
        return TrafficPersistenceSnapshot(
            generation = generation,
            traffic = mapOf(NODE_ID to monthly),
            daily = mapOf(DATE_KEY to dailyRecord)
        )
    }

    private fun emptySnapshot(generation: Long): TrafficPersistenceSnapshot {
        return TrafficPersistenceSnapshot(
            generation = generation,
            traffic = emptyMap(),
            daily = emptyMap()
        )
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("traffic_snapshot_").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val NODE_ID = "node-a"
        const val DATE_KEY = "2026-7-11"
    }
}
