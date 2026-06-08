package com.kunk.singbox.utils.dns

import android.util.Log
import com.google.gson.Gson
import com.tencent.mmkv.MMKV

/**
 *
 */
class DnsResolveStore private constructor() {

    companion object {
        private const val TAG = "DnsResolveStore"
        private const val MMKV_ID = "dns_resolve_cache"

        // 默认 TTL: 4 小时 (节点域名 IP 变化频率低，减少断连后重复解析)
        const val DEFAULT_TTL_SECONDS = 14400

        @Volatile
        private var instance: DnsResolveStore? = null

        fun getInstance(): DnsResolveStore {
            return instance ?: synchronized(this) {
                instance ?: DnsResolveStore().also { instance = it }
            }
        }
    }

    private val mmkv: MMKV by lazy {
        MMKV.mmkvWithID(MMKV_ID, MMKV.SINGLE_PROCESS_MODE)
    }

    private val gson = Gson()

    /**
     */
    data class ResolvedEntry(
        val ip: String,
        val resolvedAt: Long,
        val ttlSeconds: Int = DEFAULT_TTL_SECONDS,
        val source: String = "doh"
    ) {

        fun isExpired(): Boolean {
            val now = System.currentTimeMillis()
            return now - resolvedAt > ttlSeconds * 1000L
        }

        /**
         */
        fun remainingSeconds(): Long {
            val elapsed = (System.currentTimeMillis() - resolvedAt) / 1000
            return maxOf(0, ttlSeconds - elapsed)
        }
    }

    /**
     * 生成存储 key
     */
    private fun makeKey(profileId: String, domainName: String): String {
        return profileId + "_" + domainName
    }

    /**
     */
    fun save(
        profileId: String,
        domain: String,
        ip: String,
        ttlSeconds: Int = DEFAULT_TTL_SECONDS,
        source: String = "doh"
    ) {
        val entry = ResolvedEntry(
            ip = ip,
            resolvedAt = System.currentTimeMillis(),
            ttlSeconds = ttlSeconds,
            source = source
        )
        val key = makeKey(profileId, domain)
        val json = gson.toJson(entry)
        mmkv.encode(key, json)
        Log.d(TAG, "Saved: $domain -> $ip (TTL: ${ttlSeconds}s)")
    }

    /**
     *
     *
     * @param domain 域名
     */
    fun get(
        profileId: String,
        domain: String,
        allowExpired: Boolean = false
    ): ResolvedEntry? {
        val key = makeKey(profileId, domain)
        val json = mmkv.decodeString(key, null) ?: return null

        return try {
            val entry = gson.fromJson(json, ResolvedEntry::class.java)
            when {
                entry == null -> null
                allowExpired -> entry
                entry.isExpired() -> {
                    Log.d(TAG, "Entry expired: $domain")
                    null
                }
                else -> entry
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse entry for $domain", e)
            null
        }
    }

    /**
     *
     */
    fun getIp(profileId: String, domain: String): String? {
        return get(profileId, domain)?.ip
    }

    /**
     */
    fun remove(profileId: String, domain: String) {
        val key = makeKey(profileId, domain)
        mmkv.removeValueForKey(key)
    }

    /**
     */
    fun removeAllForProfile(profileId: String) {
        val prefix = "${profileId}_"
        val keysToRemove = mmkv.allKeys()?.filter { it.startsWith(prefix) } ?: return
        keysToRemove.forEach { mmkv.removeValueForKey(it) }
        Log.d(TAG, "Removed ${keysToRemove.size} entries for profile $profileId")
    }

    /**
     */
    fun saveBatch(
        profileId: String,
        results: Map<String, DnsResolveResult>,
        ttlSeconds: Int = DEFAULT_TTL_SECONDS
    ): Int {
        var savedCount = 0
        for ((domain, result) in results) {
            if (result.isSuccess && result.ip != null) {
                save(profileId, domain, result.ip, ttlSeconds, result.source)
                savedCount++
            }
        }
        Log.d(TAG, "Batch saved $savedCount entries for profile $profileId")
        return savedCount
    }

    /**
     */
    fun getAllForProfile(profileId: String): Map<String, ResolvedEntry> {
        val prefix = "${profileId}_"
        val result = mutableMapOf<String, ResolvedEntry>()

        mmkv.allKeys()?.filter { it.startsWith(prefix) }?.forEach { key ->
            val domain = key.removePrefix(prefix)
            val entry = get(profileId, domain)
            if (entry != null) {
                result[domain] = entry
            }
        }

        return result
    }

    /**
     */
    fun cleanupExpired(): Int {
        var cleanedCount = 0
        mmkv.allKeys()?.forEach { key ->
            val json = mmkv.decodeString(key, null) ?: return@forEach
            try {
                val entry = gson.fromJson(json, ResolvedEntry::class.java)
                if (entry?.isExpired() == true) {
                    mmkv.removeValueForKey(key)
                    cleanedCount++
                }
            } catch (e: Exception) {
                Log.w(TAG, "Invalid entry, removing: ${e.message}")
                mmkv.removeValueForKey(key)
                cleanedCount++
            }
        }
        if (cleanedCount > 0) {
            Log.d(TAG, "Cleaned up $cleanedCount expired entries")
        }
        return cleanedCount
    }

    /**
     */
    fun getStats(): Stats {
        var total = 0
        var valid = 0
        var expired = 0

        mmkv.allKeys()?.forEach { key ->
            val entry = parseEntry(key)
            if (entry != null) {
                total++
                if (entry.isExpired()) expired++ else valid++
            }
        }

        return Stats(total, valid, expired)
    }

    private fun parseEntry(key: String): ResolvedEntry? {
        val json = mmkv.decodeString(key, null) ?: return null
        return try {
            gson.fromJson(json, ResolvedEntry::class.java)
        } catch (e: Exception) {
            Log.v(TAG, "Failed to parse entry: ${e.message}")
            null
        }
    }

    data class Stats(
        val total: Int,
        val valid: Int,
        val expired: Int
    )

    /**
     */
    fun clear() {
        mmkv.clearAll()
        Log.d(TAG, "Cleared all entries")
    }
}
