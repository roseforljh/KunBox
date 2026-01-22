package com.kunk.singbox.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

data class NodeTrafficStats(
    val nodeId: String,
    var upload: Long = 0,
    var download: Long = 0,
    var lastUpdated: Long = 0
)

/**
 * 流量统计仓库
 * 负责持久化存储节点流量数据
 */
class TrafficRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TrafficRepository"
        private const val FILE_NAME = "traffic_stats.json"
        private val TRAFFIC_STATS_MAP_TYPE = object : TypeToken<Map<String, NodeTrafficStats>>() {}.type

        @Volatile
        private var instance: TrafficRepository? = null

        fun getInstance(context: Context): TrafficRepository {
            return instance ?: synchronized(this) {
                instance ?: TrafficRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val gson = Gson()
    private val trafficMap = ConcurrentHashMap<String, NodeTrafficStats>()
    private val statsFile: File get() = File(context.filesDir, FILE_NAME)
    private var lastSaveTime = 0L

    init {
        loadStats()
        checkMonthlyReset()
    }

    private fun loadStats() {
        if (!statsFile.exists()) return
        try {
            val json = statsFile.readText()
            val loaded: Map<String, NodeTrafficStats>? = gson.fromJson(json, TRAFFIC_STATS_MAP_TYPE)
            if (loaded != null) {
                trafficMap.putAll(loaded)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load traffic stats", e)
        }
    }

    fun saveStats() {
        val now = System.currentTimeMillis()
        if (now - lastSaveTime < 10000) return // Throttling save (10s)

        try {
            val json = gson.toJson(trafficMap)
            // Atomic write
            val tmpFile = File(context.filesDir, "$FILE_NAME.tmp")
            tmpFile.writeText(json)
            if (tmpFile.renameTo(statsFile)) {
                lastSaveTime = now
            } else {
                // fallback
                tmpFile.copyTo(statsFile, overwrite = true)
                tmpFile.delete()
            }
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
            trafficMap.clear()
            saveStats()
        }

        if (lastMonth != currentMonth) {
            prefs.edit().putInt("last_month", currentMonth).apply()
        }
    }

    fun addTraffic(nodeId: String, uploadDiff: Long, downloadDiff: Long) {
        if (uploadDiff <= 0 && downloadDiff <= 0) return

        val stats = trafficMap.getOrPut(nodeId) { NodeTrafficStats(nodeId) }
        stats.upload += uploadDiff
        stats.download += downloadDiff
        stats.lastUpdated = System.currentTimeMillis()

        // Auto-save occasionally handled by service calling saveStats() explicitly or implicit throttling
    }

    fun getStats(nodeId: String): NodeTrafficStats? {
        return trafficMap[nodeId]
    }

    fun getMonthlyTotal(nodeId: String): Long {
        val stats = trafficMap[nodeId] ?: return 0
        return stats.upload + stats.download
    }
}
