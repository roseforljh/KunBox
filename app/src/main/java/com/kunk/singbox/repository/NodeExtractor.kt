package com.kunk.singbox.repository

import android.content.Context
import com.kunk.singbox.R
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

object NodeExtractor {

    private const val PARALLEL_CONCURRENCY = 8

    private val PROXY_TYPES = setOf(
        "shadowsocks", "vmess", "vless", "trojan",
        "hysteria", "hysteria2", "tuic", "wireguard",
        "shadowtls", "ssh", "anytls", "naive", "http", "socks"
    )

    private val nodeIdCache = ConcurrentHashMap<String, String>()

    suspend fun extract(
        config: SingBoxConfig,
        profileId: String,
        trafficRepo: TrafficRepository,
        context: Context,
        onProgress: ((String) -> Unit)? = null
    ): List<NodeUi> = withContext(Dispatchers.Default) {
        val outbounds = config.outbounds ?: return@withContext emptyList()

        val groupOutbounds = outbounds.filter {
            it.type == "selector" || it.type == "urltest" || it.type == "url-test"
        }

        val nodeToGroup = mutableMapOf<String, String>()
        groupOutbounds.forEach { group ->
            group.outbounds?.forEach { nodeName ->
                nodeToGroup[nodeName] = group.tag
            }
        }

        val validOutbounds = outbounds.filter { it.type in PROXY_TYPES }
        if (validOutbounds.isEmpty()) return@withContext emptyList()

        val total = validOutbounds.size
        val completed = AtomicInteger(0)
        val semaphore = Semaphore(PARALLEL_CONCURRENCY)

        validOutbounds.map { outbound ->
            async {
                semaphore.withPermit {
                    val node = createNodeUi(outbound, profileId, nodeToGroup, trafficRepo)
                    val done = completed.incrementAndGet()
                    if (done % 100 == 0 || done == total) {
                        onProgress?.invoke(context.getString(R.string.profiles_extracting_nodes, done, total))
                    }
                    node
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun createNodeUi(
        outbound: Outbound,
        profileId: String,
        nodeToGroup: Map<String, String>,
        trafficRepo: TrafficRepository
    ): NodeUi? {
        if (outbound.tag.isBlank()) return null

        var group = nodeToGroup[outbound.tag] ?: "Default"
        if (group.contains("://") || group.length > 50) {
            group = "Default"
        }

        val id = stableNodeId(profileId, outbound.tag)

        return NodeUi(
            id = id,
            name = outbound.tag,
            protocol = outbound.type,
            group = group,
            latencyMs = null,
            isFavorite = false,
            sourceProfileId = profileId,
            trafficUsed = trafficRepo.getMonthlyTotal(id),
            tags = buildList {
                outbound.tls?.let {
                    if (it.enabled == true) add("TLS")
                    it.reality?.let { r -> if (r.enabled == true) add("Reality") }
                }
                outbound.transport?.type?.let { add(it.uppercase()) }
            }
        )
    }

    fun stableNodeId(profileId: String, outboundTag: String): String {
        val key = "$profileId|$outboundTag"
        return nodeIdCache.getOrPut(key) {
            UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
        }
    }

    fun clearCache() {
        nodeIdCache.clear()
    }
}
