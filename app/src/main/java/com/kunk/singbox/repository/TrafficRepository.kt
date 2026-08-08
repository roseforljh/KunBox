package com.kunk.singbox.repository

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.kunk.singbox.ipc.VpnStateStore
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class MergedTrafficSaveScheduler(
    private val scope: CoroutineScope,
    private val delayMs: Long,
    private val save: () -> Boolean
) {
    private val stateLock = Any()
    private val executionLock = Any()
    private var scheduledJob: Job? = null
    private var dirty = false
    private var generation = 0L

    fun requestSave() {
        synchronized(stateLock) {
            dirty = true
            scheduleLocked()
        }
    }

    fun flush(): Boolean {
        val job = synchronized(stateLock) {
            generation++
            dirty = false
            scheduledJob.also { scheduledJob = null }
        }
        job?.cancel()

        val success = synchronized(executionLock) { save() }
        if (!success) {
            synchronized(stateLock) {
                dirty = true
                scheduleLocked()
            }
        }
        return success
    }

    fun cancel() {
        val job = synchronized(stateLock) {
            generation++
            dirty = false
            scheduledJob.also { scheduledJob = null }
        }
        job?.cancel()
        synchronized(executionLock) { Unit }
    }

    private fun scheduleLocked() {
        if (scheduledJob?.isActive == true) return
        val expectedGeneration = generation
        scheduledJob = scope.launch {
            delay(delayMs)
            runScheduledSave(expectedGeneration)
        }
    }

    private fun runScheduledSave(expectedGeneration: Long) {
        val shouldSave = synchronized(stateLock) {
            if (expectedGeneration != generation) {
                false
            } else {
                dirty = false
                true
            }
        }
        if (!shouldSave) return

        val success = synchronized(executionLock) {
            val stillCurrent = synchronized(stateLock) { expectedGeneration == generation }
            stillCurrent && save()
        }

        synchronized(stateLock) {
            if (expectedGeneration != generation) return
            scheduledJob = null
            if (!success) dirty = true
            if (dirty) scheduleLocked()
        }
    }
}

data class NodeTrafficStats(
    val nodeId: String? = null,
    var upload: Long = 0,
    var download: Long = 0,
    var lastUpdated: Long = 0,
    var nodeName: String? = null
) {
    fun isValid(): Boolean = !nodeId.isNullOrBlank()
}

data class DailyTrafficRecord(
    val dateKey: String,
    @Suppress("ConstructorParameterNaming")
    private var _nodeStats: MutableMap<String, NodeTrafficStats>? = null
) {
    val nodeStats: MutableMap<String, NodeTrafficStats>
        get() = _nodeStats ?: mutableMapOf<String, NodeTrafficStats>().also { _nodeStats = it }
}

enum class TrafficPeriod {
    TODAY,
    THIS_WEEK,
    THIS_MONTH,
    ALL_TIME
}

data class TrafficSummary(
    val totalUpload: Long,
    val totalDownload: Long,
    val nodeStats: List<NodeTrafficStats>,
    val period: TrafficPeriod
)

internal data class TrafficPersistenceSnapshot(
    val generation: Long,
    @SerializedName("monthly")
    val traffic: Map<String, NodeTrafficStats>,
    val daily: Map<String, DailyTrafficRecord>,
    val monthKey: String? = null
)

internal fun canCommitTrafficSnapshot(snapshotGeneration: Long, clearCutoffGeneration: Long): Boolean {
    return snapshotGeneration >= clearCutoffGeneration
}

private fun nextTrafficGenerationAfter(generation: Long): Long {
    return if (generation == Long.MAX_VALUE) Long.MAX_VALUE else generation + 1L
}

internal class TrafficSnapshotFileStore(
    private val directory: File,
    private val gson: Gson = Gson()
) {
    companion object {
        private const val TAG = "TrafficSnapshotStore"
        private const val SNAPSHOT_FILE_NAME = "traffic_snapshot.json"
        private const val LOCK_FILE_NAME = "traffic_snapshot.lock"
        private const val GENERATION_FILE_NAME = "traffic_clear_generation"
        private const val LEGACY_STATS_FILE_NAME = "traffic_stats.json"
        private const val LEGACY_DAILY_FILE_NAME = "traffic_daily.json"
        private val LEGACY_STATS_TYPE = object : TypeToken<Map<String, NodeTrafficStats>>() {}.type
        private val LEGACY_DAILY_TYPE = object : TypeToken<Map<String, DailyTrafficRecord>>() {}.type
        private val processFileLock = Any()
    }

    private data class StoredSnapshot(
        val generation: Long = 0L,
        @SerializedName("monthly")
        val traffic: Map<String, NodeTrafficStats>? = null,
        val daily: Map<String, DailyTrafficRecord>? = null,
        val monthKey: String? = null
    )

    private val snapshotFile = File(directory, SNAPSHOT_FILE_NAME)
    private val lockFile = File(directory, LOCK_FILE_NAME)
    private val generationFile = File(directory, GENERATION_FILE_NAME)
    private val legacyStatsFile = File(directory, LEGACY_STATS_FILE_NAME)
    private val legacyDailyFile = File(directory, LEGACY_DAILY_FILE_NAME)

    fun load(
        clearCutoffGeneration: Long,
        transform: (TrafficPersistenceSnapshot) -> TrafficPersistenceSnapshot = { it }
    ): TrafficPersistenceSnapshot? = withFileLock {
        var effectiveCutoff = persistCutoffLocked(clearCutoffGeneration)
        val snapshotExists = snapshotFile.exists()
        val snapshot = if (snapshotExists) {
            readSnapshotLocked()
        } else {
            readLegacySnapshotLocked(effectiveCutoff)
        } ?: return@withFileLock null
        if (snapshot.generation > effectiveCutoff) {
            effectiveCutoff = persistCutoffLocked(snapshot.generation)
        }
        if (!canCommitTrafficSnapshot(snapshot.generation, effectiveCutoff)) {
            return@withFileLock null
        }

        val transformed = transform(snapshot)
        if (!snapshotExists || transformed != snapshot) {
            writeSnapshotLocked(transformed)
        }
        deleteLegacyFilesLocked()
        transformed
    }

    fun save(snapshot: TrafficPersistenceSnapshot, clearCutoffGeneration: Long): Boolean {
        val content = gson.toJson(snapshot)
        return withFileLock {
            val effectiveCutoff = persistCutoffLocked(
                maxOf(clearCutoffGeneration, snapshot.generation)
            )
            if (!canCommitTrafficSnapshot(snapshot.generation, effectiveCutoff)) {
                return@withFileLock false
            }

            writeTextFileAtomically(snapshotFile, content)
            deleteLegacyFilesLocked()
            true
        }
    }

    /** 在文件锁内合并增量，避免主进程测速与 VPN 进程连接统计互相覆盖。 */
    fun mergeDeltas(
        deltas: TrafficPersistenceSnapshot,
        clearCutoffGeneration: Long
    ): TrafficPersistenceSnapshot? = withFileLock {
        val effectiveCutoff = persistCutoffLocked(clearCutoffGeneration)
        if (!canCommitTrafficSnapshot(deltas.generation, effectiveCutoff)) {
            return@withFileLock null
        }

        val stored = readSnapshotLocked()
            ?.takeIf { it.generation >= effectiveCutoff }
            ?: TrafficPersistenceSnapshot(
                generation = effectiveCutoff,
                traffic = emptyMap(),
                daily = emptyMap(),
                monthKey = deltas.monthKey
            )
        if (stored.generation > deltas.generation) return@withFileLock null

        val merged = TrafficPersistenceSnapshot(
            generation = maxOf(stored.generation, deltas.generation),
            traffic = mergeNodeStats(
                base = stored.traffic.takeIf { stored.monthKey == deltas.monthKey }.orEmpty(),
                deltas = deltas.traffic
            ),
            daily = mergeDailyStats(stored.daily, deltas.daily),
            monthKey = deltas.monthKey
        )
        writeSnapshotLocked(merged)
        deleteLegacyFilesLocked()
        merged
    }

    fun clear(
        minimumGeneration: Long,
        monthKey: String,
        publishCutoff: (Long) -> Unit
    ): Long = withFileLock {
        val persistedGeneration = readPersistedGenerationLocked()
        val snapshotGeneration = readSnapshotLocked()?.generation ?: 0L
        val nextGeneration = maxOf(
            minimumGeneration,
            nextTrafficGenerationAfter(persistedGeneration),
            nextTrafficGenerationAfter(snapshotGeneration)
        )

        publishCutoff(nextGeneration)
        writeTextFileAtomically(generationFile, nextGeneration.toString())
        writeSnapshotLocked(
            TrafficPersistenceSnapshot(
                generation = nextGeneration,
                traffic = emptyMap(),
                daily = emptyMap(),
                monthKey = monthKey
            )
        )
        deleteLegacyFilesLocked()
        nextGeneration
    }

    private fun readLegacySnapshotLocked(generation: Long): TrafficPersistenceSnapshot? {
        if (!legacyStatsFile.exists() && !legacyDailyFile.exists()) return null

        return TrafficPersistenceSnapshot(
            generation = generation,
            traffic = readLegacyStatsLocked(),
            daily = readLegacyDailyLocked()
        )
    }

    private fun mergeNodeStats(
        base: Map<String, NodeTrafficStats>,
        deltas: Map<String, NodeTrafficStats>
    ): Map<String, NodeTrafficStats> {
        val merged = base.mapValuesTo(mutableMapOf()) { (_, stats) -> stats.copy() }
        deltas.forEach { (nodeId, delta) ->
            val target = merged.getOrPut(nodeId) { NodeTrafficStats(nodeId = nodeId) }
            target.upload = addTrafficValue(target.upload, delta.upload)
            target.download = addTrafficValue(target.download, delta.download)
            target.lastUpdated = maxOf(target.lastUpdated, delta.lastUpdated)
            if (!delta.nodeName.isNullOrBlank()) target.nodeName = delta.nodeName
        }
        return merged
    }

    private fun mergeDailyStats(
        base: Map<String, DailyTrafficRecord>,
        deltas: Map<String, DailyTrafficRecord>
    ): Map<String, DailyTrafficRecord> {
        val merged = base.mapValuesTo(mutableMapOf()) { (dateKey, record) ->
            DailyTrafficRecord(dateKey).apply {
                nodeStats.putAll(record.nodeStats.mapValues { (_, stats) -> stats.copy() })
            }
        }
        deltas.forEach { (dateKey, deltaRecord) ->
            val target = merged.getOrPut(dateKey) { DailyTrafficRecord(dateKey) }
            val combined = mergeNodeStats(target.nodeStats, deltaRecord.nodeStats)
            target.nodeStats.clear()
            target.nodeStats.putAll(combined)
        }
        return merged
    }

    private fun addTrafficValue(current: Long, delta: Long): Long {
        val safeCurrent = current.coerceAtLeast(0L)
        val safeDelta = delta.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - safeCurrent < safeDelta) Long.MAX_VALUE else safeCurrent + safeDelta
    }

    private fun readSnapshotLocked(): TrafficPersistenceSnapshot? {
        if (!snapshotFile.exists()) return null
        return try {
            val stored = gson.fromJson(snapshotFile.readText(Charsets.UTF_8), StoredSnapshot::class.java)
                ?: return null
            TrafficPersistenceSnapshot(
                generation = stored.generation,
                traffic = cleanTraffic(stored.traffic.orEmpty()),
                daily = cleanDaily(stored.daily.orEmpty()),
                monthKey = stored.monthKey
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read traffic snapshot", e)
            null
        }
    }

    private fun readLegacyStatsLocked(): Map<String, NodeTrafficStats> {
        if (!legacyStatsFile.exists()) return emptyMap()
        return try {
            val loaded: Map<String, NodeTrafficStats>? = gson.fromJson(
                legacyStatsFile.readText(Charsets.UTF_8),
                LEGACY_STATS_TYPE
            )
            cleanTraffic(loaded.orEmpty())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read legacy monthly traffic", e)
            emptyMap()
        }
    }

    private fun readLegacyDailyLocked(): Map<String, DailyTrafficRecord> {
        if (!legacyDailyFile.exists()) return emptyMap()
        return try {
            val loaded: Map<String, DailyTrafficRecord>? = gson.fromJson(
                legacyDailyFile.readText(Charsets.UTF_8),
                LEGACY_DAILY_TYPE
            )
            cleanDaily(loaded.orEmpty())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read legacy daily traffic", e)
            emptyMap()
        }
    }

    private fun cleanTraffic(source: Map<String, NodeTrafficStats>): Map<String, NodeTrafficStats> {
        return source.filterValues { stats -> runCatching { stats.isValid() }.getOrDefault(false) }
    }

    private fun cleanDaily(source: Map<String, DailyTrafficRecord>): Map<String, DailyTrafficRecord> {
        return buildMap {
            source.forEach { (dateKey, record) ->
                val validStats = runCatching { cleanTraffic(record.nodeStats) }.getOrNull()
                    ?: return@forEach
                if (validStats.isNotEmpty()) {
                    val cleanRecord = DailyTrafficRecord(dateKey)
                    cleanRecord.nodeStats.putAll(validStats)
                    put(dateKey, cleanRecord)
                }
            }
        }
    }

    private fun writeSnapshotLocked(snapshot: TrafficPersistenceSnapshot) {
        writeTextFileAtomically(snapshotFile, gson.toJson(snapshot))
    }

    private fun persistCutoffLocked(requestedGeneration: Long): Long {
        val persistedGeneration = readPersistedGenerationLocked()
        val effectiveGeneration = maxOf(requestedGeneration, persistedGeneration)
        if (effectiveGeneration > persistedGeneration) {
            writeTextFileAtomically(generationFile, effectiveGeneration.toString())
        }
        return effectiveGeneration
    }

    private fun readPersistedGenerationLocked(): Long {
        if (!generationFile.exists()) return 0L
        return generationFile.readText(Charsets.UTF_8).trim().toLongOrNull() ?: 0L
    }

    private fun deleteLegacyFilesLocked() {
        listOf(legacyStatsFile, legacyDailyFile).forEach { file ->
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Failed to delete legacy traffic file: ${file.absolutePath}")
            }
        }
    }

    private fun <T> withFileLock(block: () -> T): T {
        return synchronized(processFileLock) {
            check(directory.exists() || directory.mkdirs()) {
                "无法创建流量持久化目录: ${directory.absolutePath}"
            }
            RandomAccessFile(lockFile, "rw").use { lockAccess ->
                lockAccess.channel.lock().use { block() }
            }
        }
    }
}

private fun writeTextFileAtomically(targetFile: File, content: String) {
    targetFile.parentFile?.mkdirs()
    val tempFile = File.createTempFile("${targetFile.name.take(64)}.", ".tmp", targetFile.parentFile)
    try {
        tempFile.writeText(content, Charsets.UTF_8)
        moveTempFileIntoPlace(tempFile, targetFile)
    } finally {
        if (tempFile.isFile && !tempFile.delete()) {
            Log.w("TrafficSnapshotStore", "Failed to delete traffic temp file: ${tempFile.absolutePath}")
        }
    }
}

private fun moveTempFileIntoPlace(tempFile: File, targetFile: File) {
    if (Build.VERSION.SDK_INT == 0 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        try {
            moveTempFileWithNio(tempFile, targetFile, atomic = true)
        } catch (_: IOException) {
            moveTempFileWithNio(tempFile, targetFile, atomic = false)
        }
    } else {
        Os.rename(tempFile.absolutePath, targetFile.absolutePath)
    }
}

@TargetApi(Build.VERSION_CODES.O)
private fun moveTempFileWithNio(tempFile: File, targetFile: File, atomic: Boolean) {
    if (atomic) {
        Files.move(
            tempFile.toPath(),
            targetFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
    } else {
        Files.move(
            tempFile.toPath(),
            targetFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
    }
}

class TrafficRepository private constructor(private val context: Context) {

    companion object {
        const val UNATTRIBUTED_NODE_ID = "__unattributed__"
        const val BACKGROUND_PROBE_NODE_ID = "__background_probe__"
        const val HEALTH_CHECK_NODE_ID = "__health_check__"
        private const val TAG = "TrafficRepository"
        private const val SAVE_DELAY_MS = 30_000L

        @Volatile
        private var instance: TrafficRepository? = null

        fun getInstance(context: Context): TrafficRepository {
            return instance ?: synchronized(this) {
                instance ?: TrafficRepository(context.applicationContext).also { instance = it }
            }
        }

        private fun getTodayKey(): String {
            val cal = Calendar.getInstance()
            return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH) + 1}-${cal.get(Calendar.DAY_OF_MONTH)}"
        }

        private fun getCurrentMonthKey(): String {
            val cal = Calendar.getInstance()
            return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH) + 1}"
        }
    }

    private val statsLock = Any()
    private val trafficMap = mutableMapOf<String, NodeTrafficStats>()
    private val dailyRecords = mutableMapOf<String, DailyTrafficRecord>()
    private val pendingTrafficDeltas = mutableMapOf<String, NodeTrafficStats>()
    private val pendingDailyDeltas = mutableMapOf<String, DailyTrafficRecord>()
    private val snapshotStore = TrafficSnapshotFileStore(context.filesDir)
    private var lastKnownClearTimestamp = 0L
    private var lastKnownMonthKey = getCurrentMonthKey()
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveScheduler = MergedTrafficSaveScheduler(saveScope, SAVE_DELAY_MS) {
        persistCurrentSnapshot()
    }

    init {
        lastKnownClearTimestamp = VpnStateStore.getTrafficClearTimestamp()
        loadSnapshotLocked()
    }

    private fun loadSnapshotLocked() {
        val clearCutoff = VpnStateStore.getTrafficClearTimestamp()
        lastKnownClearTimestamp = maxOf(lastKnownClearTimestamp, clearCutoff)
        try {
            val currentMonthKey = getCurrentMonthKey()
            val resetLegacyMonthly = shouldResetLegacyMonthly(currentMonthKey)
            val snapshot = snapshotStore.load(clearCutoff) { stored ->
                val shouldResetMonthly = stored.monthKey?.takeIf { it.isNotBlank() }
                    ?.let { it != currentMonthKey }
                    ?: resetLegacyMonthly
                stored.copy(
                    traffic = if (shouldResetMonthly) emptyMap() else stored.traffic,
                    daily = removeExpiredDailyRecords(stored.daily),
                    monthKey = currentMonthKey
                )
            } ?: return
            if (snapshot.generation > clearCutoff) {
                VpnStateStore.setTrafficClearTimestamp(snapshot.generation)
            }
            lastKnownClearTimestamp = maxOf(lastKnownClearTimestamp, snapshot.generation)
            lastKnownMonthKey = snapshot.monthKey ?: currentMonthKey
            trafficMap.putAll(snapshot.traffic)
            dailyRecords.putAll(snapshot.daily)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load traffic stats", e)
        }
    }

    private fun shouldResetLegacyMonthly(currentMonthKey: String): Boolean {
        val prefs = context.getSharedPreferences("traffic_prefs", Context.MODE_PRIVATE)
        val currentMonth = Calendar.getInstance().get(Calendar.MONTH)
        val lastMonthKey = prefs.getString("last_month_key", null)
        val legacyLastMonth = prefs.getInt("last_month", -1)
        val shouldReset = when {
            !lastMonthKey.isNullOrBlank() -> lastMonthKey != currentMonthKey
            legacyLastMonth >= 0 -> legacyLastMonth != currentMonth
            else -> false
        }
        prefs.edit()
            .putString("last_month_key", currentMonthKey)
            .putInt("last_month", currentMonth)
            .apply()
        return shouldReset
    }

    private fun removeExpiredDailyRecords(
        records: Map<String, DailyTrafficRecord>
    ): Map<String, DailyTrafficRecord> {
        val cutoff = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -60) }.timeInMillis
        return records.filterKeys { dateKey ->
            runCatching {
                val parts = dateKey.split("-")
                parts.size != 3 || Calendar.getInstance().apply {
                    set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                }.timeInMillis >= cutoff
            }.getOrDefault(true)
        }
    }

    fun saveStats() {
        saveScheduler.flush()
    }

    @Suppress("LongMethod")
    private fun persistCurrentSnapshot(): Boolean {
        val currentClearTimestamp = VpnStateStore.getTrafficClearTimestamp()
        val deltas = synchronized(statsLock) {
            checkCrossProcessClearLocked(currentClearTimestamp)
            checkMonthChangedLocked(getCurrentMonthKey())
            if (pendingTrafficDeltas.isEmpty() && pendingDailyDeltas.isEmpty()) {
                return true
            }
            TrafficPersistenceSnapshot(
                generation = lastKnownClearTimestamp,
                traffic = pendingTrafficDeltas.mapValues { it.value.snapshot() },
                daily = pendingDailyDeltas.mapValues { it.value.snapshot() },
                monthKey = getCurrentMonthKey()
            ).also {
                pendingTrafficDeltas.clear()
                pendingDailyDeltas.clear()
            }
        }

        val merged = try {
            snapshotStore.mergeDeltas(
                deltas = deltas,
                clearCutoffGeneration = VpnStateStore.getTrafficClearTimestamp()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save traffic stats", e)
            null
        }
        if (merged == null) {
            val latestClearTimestamp = VpnStateStore.getTrafficClearTimestamp()
            synchronized(statsLock) {
                if (deltas.generation >= latestClearTimestamp) {
                    restorePendingDeltasLocked(deltas)
                } else {
                    checkCrossProcessClearLocked(latestClearTimestamp)
                }
            }
            return deltas.generation < latestClearTimestamp
        }

        synchronized(statsLock) {
            if (lastKnownClearTimestamp != deltas.generation ||
                VpnStateStore.getTrafficClearTimestamp() > deltas.generation
            ) {
                return@synchronized
            }
            trafficMap.clear()
            trafficMap.putAll(merged.traffic.mapValues { (_, stats) -> stats.snapshot() })
            pendingTrafficDeltas.forEach { (nodeId, delta) ->
                applyNodeTrafficDelta(trafficMap, nodeId, delta)
            }
            dailyRecords.clear()
            dailyRecords.putAll(
                removeExpiredDailyRecords(merged.daily).mapValues { (_, record) -> record.snapshot() }
            )
            pendingDailyDeltas.forEach { (dateKey, record) ->
                record.nodeStats.forEach { (nodeId, delta) ->
                    applyDailyTrafficDelta(dailyRecords, dateKey, nodeId, delta)
                }
            }
            lastKnownMonthKey = merged.monthKey ?: getCurrentMonthKey()
        }
        return true
    }

    fun addTraffic(nodeId: String, uploadDiff: Long, downloadDiff: Long, nodeName: String? = null) {
        if (uploadDiff <= 0 && downloadDiff <= 0) return
        val currentClearTimestamp = VpnStateStore.getTrafficClearTimestamp()
        val upload = uploadDiff.coerceAtLeast(0L)
        val download = downloadDiff.coerceAtLeast(0L)
        val now = System.currentTimeMillis()

        synchronized(statsLock) {
            checkCrossProcessClearLocked(currentClearTimestamp)
            checkMonthChangedLocked(getCurrentMonthKey())

            val todayKey = getTodayKey()
            val delta = NodeTrafficStats(nodeId, upload, download, now, nodeName)
            applyNodeTrafficDelta(trafficMap, nodeId, delta)
            applyDailyTrafficDelta(dailyRecords, todayKey, nodeId, delta)
            applyNodeTrafficDelta(pendingTrafficDeltas, nodeId, delta)
            applyDailyTrafficDelta(pendingDailyDeltas, todayKey, nodeId, delta)
        }
        saveScheduler.requestSave()
    }

    private fun applyNodeTrafficDelta(
        target: MutableMap<String, NodeTrafficStats>,
        nodeId: String,
        delta: NodeTrafficStats
    ) {
        val stats = target.getOrPut(nodeId) { NodeTrafficStats(nodeId) }
        stats.upload = addTrafficValue(stats.upload, delta.upload)
        stats.download = addTrafficValue(stats.download, delta.download)
        stats.lastUpdated = maxOf(stats.lastUpdated, delta.lastUpdated)
        if (!delta.nodeName.isNullOrBlank()) stats.nodeName = delta.nodeName
    }

    private fun applyDailyTrafficDelta(
        target: MutableMap<String, DailyTrafficRecord>,
        dateKey: String,
        nodeId: String,
        delta: NodeTrafficStats
    ) {
        val record = target.getOrPut(dateKey) { DailyTrafficRecord(dateKey) }
        applyNodeTrafficDelta(record.nodeStats, nodeId, delta)
    }

    private fun restorePendingDeltasLocked(deltas: TrafficPersistenceSnapshot) {
        deltas.traffic.forEach { (nodeId, delta) ->
            applyNodeTrafficDelta(pendingTrafficDeltas, nodeId, delta)
        }
        deltas.daily.forEach { (dateKey, record) ->
            record.nodeStats.forEach { (nodeId, delta) ->
                applyDailyTrafficDelta(pendingDailyDeltas, dateKey, nodeId, delta)
            }
        }
    }

    private fun addTrafficValue(current: Long, delta: Long): Long {
        val safeCurrent = current.coerceAtLeast(0L)
        val safeDelta = delta.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - safeCurrent < safeDelta) Long.MAX_VALUE else safeCurrent + safeDelta
    }

    private fun checkCrossProcessClearLocked(currentClearTs: Long) {
        if (currentClearTs > lastKnownClearTimestamp) {
            Log.i(TAG, "Cross-process clear detected, clearing local data")
            trafficMap.clear()
            dailyRecords.clear()
            pendingTrafficDeltas.clear()
            pendingDailyDeltas.clear()
            lastKnownClearTimestamp = currentClearTs
        }
    }

    private fun checkMonthChangedLocked(currentMonthKey: String) {
        if (currentMonthKey == lastKnownMonthKey) return
        Log.i(TAG, "Month changed ($lastKnownMonthKey -> $currentMonthKey), resetting monthly traffic")
        trafficMap.clear()
        pendingTrafficDeltas.clear()
        lastKnownMonthKey = currentMonthKey
    }

    fun getStats(nodeId: String): NodeTrafficStats? {
        return synchronized(statsLock) {
            trafficMap[nodeId]?.snapshot()
        }
    }

    fun getMonthlyTotal(nodeId: String): Long {
        return synchronized(statsLock) {
            checkMonthChangedLocked(getCurrentMonthKey())
            val stats = trafficMap[nodeId] ?: return@synchronized 0L
            stats.upload + stats.download
        }
    }

    fun getTrafficSummary(period: TrafficPeriod): TrafficSummary {
        return synchronized(statsLock) {
            checkMonthChangedLocked(getCurrentMonthKey())
            when (period) {
                TrafficPeriod.TODAY -> getTodayTrafficLocked()
                TrafficPeriod.THIS_WEEK -> getWeekTrafficLocked()
                TrafficPeriod.THIS_MONTH -> getMonthTrafficLocked()
                TrafficPeriod.ALL_TIME -> getAllTimeTrafficLocked()
            }
        }
    }

    private fun getTodayTrafficLocked(): TrafficSummary {
        val todayKey = getTodayKey()
        val record = dailyRecords[todayKey]

        if (record == null) {
            return TrafficSummary(0, 0, emptyList(), TrafficPeriod.TODAY)
        }

        var totalUp = 0L
        var totalDown = 0L
        record.nodeStats.values.forEach {
            totalUp += it.upload
            totalDown += it.download
        }

        val nodeList = record.nodeStats.values.map { it.snapshot() }.sortedByDescending { it.upload + it.download }
        return TrafficSummary(totalUp, totalDown, nodeList, TrafficPeriod.TODAY)
    }

    @Suppress("NestedBlockDepth", "CognitiveComplexMethod")
    private fun getWeekTrafficLocked(): TrafficSummary {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        val weekStart = cal.timeInMillis

        val aggregated = mutableMapOf<String, NodeTrafficStats>()

        dailyRecords.values.forEach { record ->
            try {
                val parts = record.dateKey.split("-")
                if (parts.size == 3) {
                    val recordCal = Calendar.getInstance()
                    recordCal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                    if (recordCal.timeInMillis >= weekStart) {
                        record.nodeStats.forEach { (nodeId, stats) ->
                            val existing = aggregated.getOrPut(nodeId) { NodeTrafficStats(nodeId) }
                            existing.upload += stats.upload
                            existing.download += stats.download
                            if (stats.lastUpdated > existing.lastUpdated) {
                                existing.lastUpdated = stats.lastUpdated
                            }
                            if (existing.nodeName.isNullOrBlank() && !stats.nodeName.isNullOrBlank()) {
                                existing.nodeName = stats.nodeName
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ignoring invalid daily traffic key: ${record.dateKey}", e)
            }
        }

        var totalUp = 0L
        var totalDown = 0L
        aggregated.values.forEach {
            totalUp += it.upload
            totalDown += it.download
        }

        val nodeList = aggregated.values.toList().sortedByDescending { it.upload + it.download }
        return TrafficSummary(totalUp, totalDown, nodeList, TrafficPeriod.THIS_WEEK)
    }

    private fun getMonthTrafficLocked(): TrafficSummary {
        var totalUp = 0L
        var totalDown = 0L
        trafficMap.values.forEach {
            totalUp += it.upload
            totalDown += it.download
        }

        val nodeList = trafficMap.values.map { it.snapshot() }.sortedByDescending { it.upload + it.download }
        return TrafficSummary(totalUp, totalDown, nodeList, TrafficPeriod.THIS_MONTH)
    }

    private fun getAllTimeTrafficLocked(): TrafficSummary {
        val aggregated = mutableMapOf<String, NodeTrafficStats>()
        dailyRecords.values.forEach { record ->
            record.nodeStats.forEach { (nodeId, stats) ->
                val existing = aggregated.getOrPut(nodeId) { NodeTrafficStats(nodeId) }
                existing.upload += stats.upload
                existing.download += stats.download
                if (stats.lastUpdated > existing.lastUpdated) {
                    existing.lastUpdated = stats.lastUpdated
                }
                if (existing.nodeName.isNullOrBlank() && !stats.nodeName.isNullOrBlank()) {
                    existing.nodeName = stats.nodeName
                }
            }
        }

        if (aggregated.isEmpty()) {
            trafficMap.forEach { (nodeId, stats) ->
                aggregated[nodeId] = NodeTrafficStats(
                    nodeId, stats.upload, stats.download, stats.lastUpdated, stats.nodeName
                )
            }
        }

        var totalUp = 0L
        var totalDown = 0L
        aggregated.values.forEach {
            totalUp += it.upload
            totalDown += it.download
        }

        val nodeList = aggregated.values.toList().sortedByDescending { it.upload + it.download }
        return TrafficSummary(totalUp, totalDown, nodeList, TrafficPeriod.ALL_TIME)
    }

    fun getTopNodes(period: TrafficPeriod, limit: Int = 10): List<NodeTrafficStats> {
        return getTopNodes(getTrafficSummary(period), limit)
    }

    fun getTopNodes(summary: TrafficSummary, limit: Int = 10): List<NodeTrafficStats> {
        return mergeByNodeName(summary.nodeStats)
            .filter { it.upload + it.download > 0 }
            .sortedByDescending { it.upload + it.download }
            .take(limit)
    }

    private fun mergeByNodeName(stats: List<NodeTrafficStats>): List<NodeTrafficStats> {
        val byName = mutableMapOf<String, NodeTrafficStats>()
        stats.filter { it.isValid() }.forEach { stat ->
            val key = stat.nodeName ?: stat.nodeId ?: return@forEach
            val existing = byName[key]
            if (existing == null) {
                byName[key] = NodeTrafficStats(
                    stat.nodeId, stat.upload, stat.download, stat.lastUpdated, stat.nodeName
                )
            } else {
                existing.upload += stat.upload
                existing.download += stat.download
                if (stat.lastUpdated > existing.lastUpdated) {
                    existing.lastUpdated = stat.lastUpdated
                }
            }
        }
        return byName.values.toList()
    }

    fun getNodeTrafficPercentages(period: TrafficPeriod): List<Pair<NodeTrafficStats, Float>> {
        return getNodeTrafficPercentages(getTrafficSummary(period))
    }

    fun getNodeTrafficPercentages(summary: TrafficSummary): List<Pair<NodeTrafficStats, Float>> {
        val total = summary.totalUpload + summary.totalDownload
        if (total == 0L) return emptyList()

        return mergeByNodeName(summary.nodeStats)
            .filter { it.upload + it.download > 0 }
            .sortedByDescending { it.upload + it.download }
            .map { stats ->
                val nodeTotal = stats.upload + stats.download
                val percentage = (nodeTotal.toFloat() / total.toFloat()) * 100f
                Pair(stats, percentage)
            }
    }

    fun reloadFromDisk() {
        if (!saveScheduler.flush()) {
            Log.e(TAG, "Reload skipped because pending traffic could not be persisted")
            return
        }
        saveScheduler.cancel()
        synchronized(statsLock) {
            trafficMap.clear()
            dailyRecords.clear()
            pendingTrafficDeltas.clear()
            pendingDailyDeltas.clear()
            loadSnapshotLocked()
            Log.i(TAG, "Reloaded traffic stats from disk: ${trafficMap.size} nodes")
        }
    }

    fun clearAllStats() {
        saveScheduler.cancel()
        var clearFailure: Exception? = null
        val clearGeneration = synchronized(statsLock) {
            val minimumGeneration = maxOf(
                System.currentTimeMillis(),
                nextTrafficGenerationAfter(lastKnownClearTimestamp)
            )
            var publishedGeneration = minimumGeneration
            val appliedGeneration = try {
                snapshotStore.clear(minimumGeneration, getCurrentMonthKey()) { generation ->
                    publishedGeneration = generation
                    VpnStateStore.setTrafficClearTimestamp(generation)
                }
            } catch (e: Exception) {
                clearFailure = e
                VpnStateStore.setTrafficClearTimestamp(publishedGeneration)
                publishedGeneration
            }
            trafficMap.clear()
            dailyRecords.clear()
            pendingTrafficDeltas.clear()
            pendingDailyDeltas.clear()
            lastKnownClearTimestamp = appliedGeneration
            lastKnownMonthKey = getCurrentMonthKey()
            appliedGeneration
        }
        clearFailure?.let { error ->
            Log.e(TAG, "Failed to persist cleared traffic snapshot", error)
            runCatching {
                snapshotStore.save(
                    snapshot = TrafficPersistenceSnapshot(
                        generation = clearGeneration,
                        traffic = emptyMap(),
                        daily = emptyMap(),
                        monthKey = getCurrentMonthKey()
                    ),
                    clearCutoffGeneration = clearGeneration
                )
            }.onFailure { retryError ->
                Log.e(TAG, "Failed to retry cleared traffic snapshot", retryError)
            }
        }
        Log.i(TAG, "All traffic stats cleared, generation=$clearGeneration")
    }

    private fun NodeTrafficStats.snapshot(): NodeTrafficStats {
        return NodeTrafficStats(
            nodeId = nodeId,
            upload = upload,
            download = download,
            lastUpdated = lastUpdated,
            nodeName = nodeName
        )
    }

    private fun DailyTrafficRecord.snapshot(): DailyTrafficRecord {
        val copy = DailyTrafficRecord(dateKey)
        copy.nodeStats.putAll(nodeStats.mapValues { it.value.snapshot() })
        return copy
    }
}
