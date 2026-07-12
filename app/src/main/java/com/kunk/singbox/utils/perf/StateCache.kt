package com.kunk.singbox.utils.perf

import android.net.Network
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference

object StateCache {
    private const val NETWORK_CACHE_TTL_MS = 1_000L

    private val cachedNetwork = AtomicReference<NetworkCache?>(null)

    private data class NetworkCache(
        val network: Network?,
        val timestampMs: Long
    )

    internal fun isNetworkCacheFresh(cachedAtMs: Long, nowMs: Long): Boolean {
        return nowMs - cachedAtMs in 0 until NETWORK_CACHE_TTL_MS
    }

    fun getNetwork(fetcher: () -> Network?): Network? {
        val cached = cachedNetwork.get()
        val now = SystemClock.elapsedRealtime()

        if (cached?.network != null && isNetworkCacheFresh(cached.timestampMs, now)) {
            return cached.network
        }

        val network = fetcher()
        cachedNetwork.set(NetworkCache(network, now))
        return network
    }

    fun invalidateNetworkCache() {
        cachedNetwork.set(null)
    }
}
