package com.kunk.singbox.service.manager

import com.kunk.singbox.repository.RuntimeNodeRef
import io.nekohasekai.libbox.Connection
import io.nekohasekai.libbox.ConnectionEvents

internal data class ConnectionTrafficEventData(
    val type: Int,
    val id: String,
    val tags: List<String> = emptyList(),
    val uploadDelta: Long = 0L,
    val downloadDelta: Long = 0L
)

internal data class AttributedConnectionTraffic(
    val targets: Set<RuntimeNodeRef>,
    val uploadDelta: Long,
    val downloadDelta: Long
)

internal object ConnectionTrafficEventReader {
    fun read(events: ConnectionEvents): List<ConnectionTrafficEventData> {
        val iterator = events.iterator()
        return buildList {
            while (iterator.hasNext()) {
                val event = iterator.next()
                if (event != null) {
                    val connection = event.connection
                    val id = event.id?.takeIf(String::isNotBlank)
                        ?: connection?.id?.takeIf(String::isNotBlank)
                    if (id != null) {
                        add(
                            ConnectionTrafficEventData(
                                type = event.type,
                                id = id,
                                tags = readTags(connection),
                                uploadDelta = event.uplinkDelta,
                                downloadDelta = event.downlinkDelta
                            )
                        )
                    }
                }
            }
        }
    }

    private fun readTags(connection: Connection?): List<String> {
        connection ?: return emptyList()
        return buildList {
            connection.outbound?.takeIf(String::isNotBlank)?.let(::add)
            connection.fromOutbound?.takeIf(String::isNotBlank)?.let(::add)
            val chain = connection.chain()
            while (chain?.hasNext() == true) {
                chain.next()?.takeIf(String::isNotBlank)?.let(::add)
            }
        }.distinct()
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
