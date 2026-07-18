package com.kunk.singbox.utils.dns

import android.util.Log
import com.google.gson.Gson
import com.tencent.mmkv.MMKV

class DnsResolveStore private constructor() {

    companion object {
        private const val TAG = "DnsResolveStore"
        private const val MMKV_ID = "dns_resolve_cache"

        // 节点域名 IP 变化频率低，四小时 TTL 可减少重复解析
        const val DEFAULT_TTL_SECONDS = 14400

        @Volatile
        private var instance: DnsResolveStore? = null

        fun getInstance(): DnsResolveStore {
            return instance ?: synchronized(this) {
                instance ?: DnsResolveStore().also { instance = it }
            }
        }
    }

    data class ResolvedEntry(
        val ip: String,
        val resolvedAt: Long,
        val ttlSeconds: Int = DEFAULT_TTL_SECONDS,
        val source: String = "doh"
    ) {
        fun isExpired(): Boolean {
            return System.currentTimeMillis() - resolvedAt > ttlSeconds * 1000L
        }

        fun remainingSeconds(): Long {
            val elapsed = (System.currentTimeMillis() - resolvedAt) / 1000
            return maxOf(0, ttlSeconds - elapsed)
        }
    }

    private val mmkv: MMKV by lazy {
        MMKV.mmkvWithID(MMKV_ID, MMKV.SINGLE_PROCESS_MODE)
    }
    private val gson = Gson()

    fun getIp(profileId: String, domain: String): String? {
        val json = mmkv.decodeString(makeKey(profileId, domain), null) ?: return null
        return try {
            gson.fromJson(json, ResolvedEntry::class.java)
                ?.takeUnless(ResolvedEntry::isExpired)
                ?.ip
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse entry for $domain", e)
            null
        }
    }

    fun removeAllForProfile(profileId: String) {
        val prefix = "${profileId}_"
        val keysToRemove = mmkv.allKeys()?.filter { it.startsWith(prefix) } ?: return
        keysToRemove.forEach(mmkv::removeValueForKey)
        Log.d(TAG, "Removed ${keysToRemove.size} entries for profile $profileId")
    }

    fun saveBatch(
        profileId: String,
        results: Map<String, DnsResolveResult>,
        ttlSeconds: Int = DEFAULT_TTL_SECONDS
    ): Int {
        var savedCount = 0
        results.forEach { (domain, result) ->
            val ip = result.ip
            if (result.isSuccess && ip != null) {
                save(profileId, domain, ip, ttlSeconds, result.source)
                savedCount++
            }
        }
        Log.d(TAG, "Batch saved $savedCount entries for profile $profileId")
        return savedCount
    }

    private fun save(
        profileId: String,
        domain: String,
        ip: String,
        ttlSeconds: Int,
        source: String
    ) {
        val entry = ResolvedEntry(
            ip = ip,
            resolvedAt = System.currentTimeMillis(),
            ttlSeconds = ttlSeconds,
            source = source
        )
        mmkv.encode(makeKey(profileId, domain), gson.toJson(entry))
        Log.d(TAG, "Saved: $domain -> $ip (TTL: ${ttlSeconds}s)")
    }

    private fun makeKey(profileId: String, domain: String): String {
        return "${profileId}_$domain"
    }
}
