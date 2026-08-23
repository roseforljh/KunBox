package com.kunk.singbox.service.manager

import com.kunk.singbox.repository.RuntimeNodeRef

internal data class ConnectionTrafficEventData(
    val type: Int,
    val id: String,
    val tags: List<String> = emptyList(),
    val uploadDelta: Long = 0L,
    val downloadDelta: Long = 0L,
    val uid: Int? = null,
    val packageNames: List<String> = emptyList(),
    val inbound: String? = null,
    val network: String? = null,
    val protocol: String? = null,
    val source: String? = null,
    val outbound: String? = null,
    val chain: List<String> = emptyList(),
    val routeRule: String? = null,
    val destination: String? = null,
    val domain: String? = null,
    val routeRuleSemantic: String = "unknown",
    val attributionStatus: String = "unknown"
)

internal data class AttributedConnectionTraffic(
    val targets: Set<RuntimeNodeRef>,
    val uploadDelta: Long,
    val downloadDelta: Long
)

internal object ConnectionTrafficEventReader {
    @Suppress("CognitiveComplexMethod")
    fun read(events: io.nekohasekai.libbox.ConnectionEvents): List<ConnectionTrafficEventData> {
        val iterator = events.iterator()
        return buildList {
            while (iterator.hasNext()) {
                val event = iterator.next()
                if (event != null) {
                    val connection = event.connection
                    val id = event.id?.takeIf(String::isNotBlank)
                        ?: connection?.id?.takeIf(String::isNotBlank)
                    if (id != null) {
                        val chain = readIterator(runCatching { connection?.chain() }.getOrNull())
                        val outbound = runCatching { connection?.outbound }.getOrNull()?.takeIf(String::isNotBlank)
                        val fromOutbound = runCatching { connection?.fromOutbound }.getOrNull()
                            ?.takeIf(String::isNotBlank)
                        val processInfo = runCatching { connection?.processInfo }.getOrNull()
                        val routeRule = runCatching { connection?.rule }.getOrNull()?.takeIf(String::isNotBlank)
                        add(
                            ConnectionTrafficEventData(
                                type = event.type,
                                id = id,
                                tags = buildList {
                                    outbound?.let(::add)
                                    fromOutbound?.let(::add)
                                    addAll(chain)
                                }.distinct(),
                                uploadDelta = event.uplinkDelta,
                                downloadDelta = event.downlinkDelta,
                                uid = runCatching { processInfo?.userID }.getOrNull()?.takeIf { it > 0 },
                                packageNames = readIterator(
                                    runCatching { processInfo?.packageNames() }.getOrNull()
                                ),
                                inbound = runCatching { connection?.inbound }.getOrNull()
                                    ?.takeIf(String::isNotBlank),
                                network = runCatching { connection?.network }.getOrNull()
                                    ?.takeIf(String::isNotBlank),
                                protocol = runCatching { connection?.protocol }.getOrNull()
                                    ?.takeIf(String::isNotBlank),
                                source = runCatching { connection?.source }.getOrNull()
                                    ?.takeIf(String::isNotBlank),
                                outbound = outbound,
                                chain = chain,
                                routeRule = routeRule,
                                destination = runCatching { connection?.destination }.getOrNull()
                                    ?.takeIf(String::isNotBlank),
                                domain = runCatching { connection?.domain }.getOrNull()?.takeIf(String::isNotBlank),
                                routeRuleSemantic = classifyRouteRuleSemantic(routeRule),
                                attributionStatus = if (processInfo != null) "attributed" else "unknown"
                            )
                        )
                    }
                }
            }
        }
    }

    private fun readIterator(iterator: io.nekohasekai.libbox.StringIterator?): List<String> {
        iterator ?: return emptyList()
        return buildList {
            while (iterator.hasNext()) {
                iterator.next()?.takeIf(String::isNotBlank)?.let(::add)
            }
        }.distinct()
    }

    internal fun classifyRouteRuleSemantic(rule: String?): String {
        val value = rule?.lowercase().orEmpty()
        return when {
            value.isBlank() -> "unknown"
            "package_name" in value || "process_name" in value || "user_id" in value -> "user_app_rule"
            "rule_set" in value -> "user_rule_set"
            "ip_is_private" in value || "private" in value -> "private_network"
            "icmp" in value -> "icmp"
            "final" in value -> "fallback_final"
            else -> "unknown"
        }
    }
}

/** 按连接的真实 outbound 与 chain 归属流量，不读取界面当前节点。 */
internal class ConnectionTrafficAttributor {
    private val targetsByConnectionId = mutableMapOf<String, Set<RuntimeNodeRef>>()

    fun apply(
        reset: Boolean,
        events: List<ConnectionTrafficEventData>,
        runtimeMappings: Map<String, RuntimeNodeRef>
    ): List<AttributedConnectionTraffic> {
        if (reset) targetsByConnectionId.clear()
        return buildList {
            events.forEach { event ->
                val targets = resolveTargets(event, runtimeMappings)
                val upload = event.uploadDelta.coerceAtLeast(0L)
                val download = event.downloadDelta.coerceAtLeast(0L)
                if (upload > 0L || download > 0L) {
                    add(
                        AttributedConnectionTraffic(
                            targets = targets,
                            uploadDelta = upload,
                            downloadDelta = download
                        )
                    )
                }
                if (event.type == EVENT_CLOSED) {
                    targetsByConnectionId.remove(event.id)
                }
            }
        }
    }

    fun resolveTargets(
        event: ConnectionTrafficEventData,
        runtimeMappings: Map<String, RuntimeNodeRef>
    ): Set<RuntimeNodeRef> {
        val resolvedTargets = event.tags.asSequence()
            .mapNotNull(runtimeMappings::get)
            .distinctBy(RuntimeNodeRef::nodeId)
            .toSet()
        if (resolvedTargets.isNotEmpty()) {
            targetsByConnectionId[event.id] = resolvedTargets
        }
        return resolvedTargets.ifEmpty {
            targetsByConnectionId[event.id].orEmpty()
        }
    }

    fun clear() {
        targetsByConnectionId.clear()
    }

    companion object {
        const val EVENT_NEW = 0
        const val EVENT_UPDATE = 1
        const val EVENT_CLOSED = 2
    }
}
