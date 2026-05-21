package com.kunk.singbox.repository

import com.tencent.mmkv.MMKV

object LatencyCache {
    private const val MMKV_ID = "latency_cache"
    private const val KEY_PREFIX = "lat_"
    private const val KEY_TIMESTAMP_PREFIX = "lat_ts_"
    private const val CACHE_VALIDITY_MS = 24 * 60 * 60 * 1000L

    private val mmkv: MMKV by lazy {
        MMKV.mmkvWithID(MMKV_ID, MMKV.SINGLE_PROCESS_MODE)
    }

    /**
     */
    fun get(nodeId: String): Long? {
        val timestamp = mmkv.decodeLong(KEY_TIMESTAMP_PREFIX + nodeId, 0L)
        if (timestamp == 0L) return null

        if (System.currentTimeMillis() - timestamp > CACHE_VALIDITY_MS) {
            remove(nodeId)
            return null
        }

        val latency = mmkv.decodeLong(KEY_PREFIX + nodeId, Long.MIN_VALUE)
        return if (latency == Long.MIN_VALUE) null else latency
    }

    /**
     */
    fun set(nodeId: String, latency: Long) {
        mmkv.encode(KEY_PREFIX + nodeId, latency)
        mmkv.encode(KEY_TIMESTAMP_PREFIX + nodeId, System.currentTimeMillis())
    }

    fun remove(nodeId: String) {
        mmkv.removeValueForKey(KEY_PREFIX + nodeId)
        mmkv.removeValueForKey(KEY_TIMESTAMP_PREFIX + nodeId)
    }

    /**
     */
    fun clear() {
        mmkv.clearAll()
    }

    /**
     * @return Map<nodeId, latency>
     */
    fun getAll(): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        val allKeys = mmkv.allKeys() ?: return result

        allKeys.filter { it.startsWith(KEY_PREFIX) && !it.startsWith(KEY_TIMESTAMP_PREFIX) }
            .forEach { key ->
                val nodeId = key.removePrefix(KEY_PREFIX)
                get(nodeId)?.let { result[nodeId] = it }
            }

        return result
    }

    /**
     */
    fun setAll(latencies: Map<String, Long>) {
        val now = System.currentTimeMillis()
        latencies.forEach { (nodeId, latency) ->
            mmkv.encode(KEY_PREFIX + nodeId, latency)
            mmkv.encode(KEY_TIMESTAMP_PREFIX + nodeId, now)
        }
    }

    fun size(): Int {
        val allKeys = mmkv.allKeys() ?: return 0
        return allKeys.count { it.startsWith(KEY_PREFIX) && !it.startsWith(KEY_TIMESTAMP_PREFIX) }
    }
}
