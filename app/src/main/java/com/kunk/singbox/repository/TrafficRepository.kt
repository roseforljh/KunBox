package com.kunk.singbox.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kunk.singbox.ipc.VpnStateStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

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

/**
 */
class TrafficRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TrafficRepository"
        private const val FILE_NAME = "traffic_stats.json"
        private const val DAILY_FILE_NAME = "traffic_daily.json"
        private val TRAFFIC_STATS_MAP_TYPE = object : TypeToken<Map<String, NodeTrafficStats>>() {}.type
        private val DAILY_RECORDS_TYPE = object : TypeToken<Map<String, DailyTrafficRecord>>() {}.type

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

        private fun writeTextFileAtomically(targetFile: File, content: String) {
            targetFile.parentFile?.mkdirs()
            val tempFile = File.createTempFile("${targetFile.name.take(64)}.", ".tmp", targetFile.parentFile)
            try {
                tempFile.writeText(content, Charsets.UTF_8)
                try {
                    Files.move(
                        tempFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: IOException) {
                    Files.move(
                        tempFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            } finally {
                if (tempFile.isFile && !tempFile.delete()) {
                    Log.w(TAG, "Failed to delete traffic temp file: ${tempFile.absolutePath}")
                }
            }
        }
    }

    private val gson = Gson()
    private val statsLock = Any()
    private val trafficMap = ConcurrentHashMap<String, NodeTrafficStats>()
    private val dailyRecords = ConcurrentHashMap<String, DailyTrafficRecord>()
    private val statsFile: File get() = File(context.filesDir, FILE_NAME)
    private val dailyFile: File get() = File(context.filesDir, DAILY_FILE_NAME)
    private var lastSaveTime = 0L
    private var lastKnownClearTimestamp = 0L

    init {
        lastKnownClearTimestamp = VpnStateStore.getTrafficClearTimestamp()
        loadStatsLocked()
        loadDailyRecordsLocked()
        checkMonthlyReset()
        cleanOldRecords()
    }

    private fun loadStatsLocked() {
        if (!statsFile.exists()) return
        try {
            val json = statsFile.readText()
            val loaded: Map<String, NodeTrafficStats>? = gson.fromJson(json, TRAFFIC_STATS_MAP_TYPE)
            if (loaded != null) {
                loaded.filterValues { it.isValid() }.forEach { (key, value) ->
                    trafficMap[key] = value
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load traffic stats", e)
        }
    }

    @Suppress("NestedBlockDepth")
    private fun loadDailyRecordsLocked() {
        if (!dailyFile.exists()) return
        try {
            val json = dailyFile.readText()
            val loaded: Map<String, DailyTrafficRecord>? = gson.fromJson(json, DAILY_RECORDS_TYPE)
            if (loaded != null) {
                loaded.forEach { (dateKey, record) ->
                    val validStats = record.nodeStats.filterValues { it.isValid() }
                    if (validStats.isNotEmpty()) {
                        val cleanRecord = DailyTrafficRecord(dateKey)
                        cleanRecord.nodeStats.putAll(validStats)
                        dailyRecords[dateKey] = cleanRecord
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load daily records", e)
        }
    }

    fun saveStats() {
        synchronized(statsLock) {
            saveStatsLocked(force = false)
        }
    }

    private fun saveStatsLocked(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSaveTime < 10000) return

        try {
            val json = gson.toJson(trafficMap.mapValues { it.value.snapshot() })
            writeTextFileAtomically(statsFile, json)
            lastSaveTime = now

            val dailyJson = gson.toJson(dailyRecords.mapValues { it.value.snapshot() })
            writeTextFileAtomically(dailyFile, dailyJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save traffic stats", e)
        }
    }

    private fun checkMonthlyReset() {
        val prefs = context.getSharedPreferences("traffic_prefs", Context.MODE_PRIVATE)
        val lastMonth = prefs.getInt("last_month", -1)
        val currentMonth = Calendar.getInstance().get(Calendar.MONTH)

        if (lastMonth != -1 && lastMonth != currentMonth) {
            Log.i(TAG, "New month detected ($lastMonth -> $currentMonth), resetting traffic stats")
            synchronized(statsLock) {
                trafficMap.clear()
                saveStatsLocked(force = true)
            }
        }

        if (lastMonth != currentMonth) {
            prefs.edit().putInt("last_month", currentMonth).apply()
        }
    }

    private fun cleanOldRecords() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -60)
        val cutoffTime = cal.timeInMillis

        val keysToRemove = dailyRecords.keys.filter { dateKey ->
            try {
                val parts = dateKey.split("-")
                if (parts.size == 3) {
                    val recordCal = Calendar.getInstance()
                    recordCal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                    recordCal.timeInMillis < cutoffTime
                } else false
            } catch (_: Exception) {
                false
            }
        }

        keysToRemove.forEach { dailyRecords.remove(it) }
        if (keysToRemove.isNotEmpty()) {
            Log.i(TAG, "Cleaned ${keysToRemove.size} old daily records")
        }
    }

    fun addTraffic(nodeId: String, uploadDiff: Long, downloadDiff: Long, nodeName: String? = null) {
        if (uploadDiff <= 0 && downloadDiff <= 0) return

        synchronized(statsLock) {
            checkCrossProcessClearLocked()

            val stats = trafficMap.getOrPut(nodeId) { NodeTrafficStats(nodeId) }
            stats.upload += uploadDiff
            stats.download += downloadDiff
            stats.lastUpdated = System.currentTimeMillis()
            if (!nodeName.isNullOrBlank()) {
                stats.nodeName = nodeName
            }

            val todayKey = getTodayKey()
            val dailyRecord = dailyRecords.getOrPut(todayKey) { DailyTrafficRecord(todayKey) }
            val dailyStats = dailyRecord.nodeStats.getOrPut(nodeId) { NodeTrafficStats(nodeId) }
            dailyStats.upload += uploadDiff
            dailyStats.download += downloadDiff
            dailyStats.lastUpdated = System.currentTimeMillis()
            if (!nodeName.isNullOrBlank()) {
                dailyStats.nodeName = nodeName
            }

            saveStatsLocked(force = false)
        }
    }

    private fun checkCrossProcessClearLocked() {
        val currentClearTs = VpnStateStore.getTrafficClearTimestamp()
        if (currentClearTs > lastKnownClearTimestamp) {
            Log.i(TAG, "Cross-process clear detected, clearing local data")
            trafficMap.clear()
            dailyRecords.clear()
            lastKnownClearTimestamp = currentClearTs
        }
    }

    fun getStats(nodeId: String): NodeTrafficStats? {
        return synchronized(statsLock) {
            trafficMap[nodeId]?.snapshot()
        }
    }

    fun getMonthlyTotal(nodeId: String): Long {
        return synchronized(statsLock) {
            val stats = trafficMap[nodeId] ?: return@synchronized 0L
            stats.upload + stats.download
        }
    }

    fun getAllNodeStats(): List<NodeTrafficStats> {
        return synchronized(statsLock) {
            trafficMap.values.map { it.snapshot() }.sortedByDescending { it.upload + it.download }
        }
    }

    fun getTotalTraffic(): Pair<Long, Long> {
        return synchronized(statsLock) {
            var totalUpload = 0L
            var totalDownload = 0L
            trafficMap.values.forEach {
                totalUpload += it.upload
                totalDownload += it.download
            }
            Pair(totalUpload, totalDownload)
        }
    }

    fun getTrafficSummary(period: TrafficPeriod): TrafficSummary {
        return synchronized(statsLock) {
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

    fun forceSave() {
        synchronized(statsLock) {
            lastSaveTime = 0L
            saveStatsLocked(force = true)
        }
    }

    /**
     */
    fun reloadFromDisk() {
        synchronized(statsLock) {
            trafficMap.clear()
            dailyRecords.clear()
            loadStatsLocked()
            loadDailyRecordsLocked()
            Log.i(TAG, "Reloaded traffic stats from disk: ${trafficMap.size} nodes")
        }
    }

    fun clearAllStats() {
        val clearTs = synchronized(statsLock) {
            trafficMap.clear()
            dailyRecords.clear()
            val clearTs = System.currentTimeMillis()
            lastKnownClearTimestamp = clearTs
            VpnStateStore.setTrafficClearTimestamp(clearTs)
            saveStatsLocked(force = true)
            clearTs
        }
        Log.i(TAG, "All traffic stats cleared, timestamp=$clearTs")
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
