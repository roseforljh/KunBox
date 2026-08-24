package com.kunk.singbox.service.manager

import java.util.ArrayDeque

internal enum class ConnectionStormReason {
    SOURCE_CREATION_RATE,
    GLOBAL_CREATION_RATE,
    SOURCE_ACTIVE_LIMIT,
    GLOBAL_ACTIVE_LIMIT,
    OUTBOUND_FAILURE_BURST,
    QUARANTINED_SOURCE
}

internal data class ConnectionSourceIdentity(
    val uid: Int?,
    val packageNames: List<String>,
    val inbound: String?,
    val source: String?
) {
    internal val key: String = when {
        uid != null -> "uid:$uid"
        packageNames.isNotEmpty() -> "package:${packageNames.sorted().joinToString(",")}"
        !inbound.isNullOrBlank() || !source.isNullOrBlank() -> "inbound:${inbound.orEmpty()}:${source.orEmpty()}"
        else -> "unknown"
    }
}

internal data class ConnectionAttributionSnapshot(
    val activeConnections: Int,
    val outboundCounts: Map<String, Int>,
    val chainCounts: Map<String, Int>,
    val protocolCounts: Map<String, Int>,
    val applicationCounts: Map<String, Int>
)

internal data class ConnectionStormDecision(
    val reason: ConnectionStormReason,
    val offender: ConnectionSourceIdentity?,
    val activeConnections: Int,
    val newConnectionsInWindow: Int,
    val creationRatePerSecond: Double,
    val closeAll: Boolean,
    val connectionIds: Set<String> = emptySet(),
    val outboundCounts: Map<String, Int> = emptyMap(),
    val chainCounts: Map<String, Int> = emptyMap(),
    val protocolCounts: Map<String, Int> = emptyMap()
)

internal fun ConnectionStormDecision.incidentCloseReason(): String = when {
    closeAll -> "close_all"
    reason == ConnectionStormReason.OUTBOUND_FAILURE_BURST -> "close_failed_outbound"
    else -> "close_quarantined_source"
}

internal class ConnectionStormGuard(
    private val sourceCreationLimit: Int = DEFAULT_SOURCE_CREATION_LIMIT,
    private val globalCreationLimit: Int = DEFAULT_GLOBAL_CREATION_LIMIT,
    private val sourceActiveLimit: Int = DEFAULT_SOURCE_ACTIVE_LIMIT,
    private val globalActiveLimit: Int = DEFAULT_GLOBAL_ACTIVE_LIMIT,
    private val outboundFailureLimit: Int = DEFAULT_OUTBOUND_FAILURE_LIMIT,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val quarantineMs: Long = DEFAULT_QUARANTINE_MS
) {
    private data class TrackedConnection(
        val source: ConnectionSourceIdentity,
        val event: ConnectionTrafficEventData,
        val routingTags: Set<String>
    )

    private data class Creation(
        val connectionId: String,
        val source: ConnectionSourceIdentity,
        val atMs: Long
    )

    private val activeConnections = mutableMapOf<String, TrackedConnection>()
    private val recentCreations = ArrayDeque<Creation>()
    private val quarantinedUntilMs = mutableMapOf<String, Long>()
    private var hasObservedSnapshot = false

    init {
        require(sourceCreationLimit > 0)
        require(globalCreationLimit >= sourceCreationLimit)
        require(sourceActiveLimit > 0)
        require(globalActiveLimit >= sourceActiveLimit)
        require(outboundFailureLimit > 0)
        require(windowMs > 0L)
        require(quarantineMs > 0L)
    }

    @Synchronized
    fun activeConnectionIdsForOutbound(outboundTag: String): Set<String> {
        val normalizedTag = outboundTag.trim()
        if (normalizedTag.isEmpty()) return emptySet()
        return activeConnections
            .filterValues { tracked -> tracked.usesOutbound(normalizedTag) }
            .keys
            .toSet()
    }

    @Synchronized
    fun acknowledgeClosedConnectionIds(connectionIds: Set<String>) {
        if (connectionIds.isEmpty()) return
        activeConnections.keys.removeAll(connectionIds)
        recentCreations.removeAll { it.connectionId in connectionIds }
    }

    @Synchronized
    fun observe(
        reset: Boolean,
        events: List<ConnectionTrafficEventData>,
        nowMs: Long
    ): ConnectionStormDecision? {
        val previousConnectionIds = if (reset && hasObservedSnapshot) activeConnections.keys.toSet() else emptySet()
        if (reset) {
            activeConnections.clear()
            if (!hasObservedSnapshot) recentCreations.clear()
        }
        trim(nowMs)

        val quarantinedConnectionIds = mutableSetOf<String>()
        events.forEach { event ->
            val countAsCreation = !reset || hasObservedSnapshot && event.id !in previousConnectionIds
            observeEvent(event, countAsCreation, nowMs)?.let(quarantinedConnectionIds::add)
        }
        hasObservedSnapshot = true
        trim(nowMs)

        if (quarantinedConnectionIds.isNotEmpty()) {
            val offender = activeConnections[quarantinedConnectionIds.first()]?.source
            return decision(
                reason = ConnectionStormReason.QUARANTINED_SOURCE,
                offender = offender,
                closeAll = quarantinedConnectionIds.size > MAX_TARGETED_CLOSES,
                connectionIds = quarantinedConnectionIds
            )
        }

        val creationsBySource = recentCreations.groupingBy { it.source.key }.eachCount()
        val activeBySource = activeConnections.values.groupingBy { it.source.key }.eachCount()
        val sourceCreationKey = creationsBySource.entries.firstOrNull { it.value >= sourceCreationLimit }?.key
        val sourceActiveKey = activeBySource.entries.firstOrNull { it.value >= sourceActiveLimit }?.key
        val reasonAndKey = when {
            sourceCreationKey != null -> ConnectionStormReason.SOURCE_CREATION_RATE to sourceCreationKey
            recentCreations.size >= globalCreationLimit -> ConnectionStormReason.GLOBAL_CREATION_RATE to
                creationsBySource.maxByOrNull(Map.Entry<String, Int>::value)?.key
            sourceActiveKey != null -> ConnectionStormReason.SOURCE_ACTIVE_LIMIT to sourceActiveKey
            activeConnections.size >= globalActiveLimit -> ConnectionStormReason.GLOBAL_ACTIVE_LIMIT to
                activeBySource.maxByOrNull(Map.Entry<String, Int>::value)?.key
            else -> return null
        }
        val offender = sourceForKey(reasonAndKey.second)
        offender?.let { quarantinedUntilMs[it.key] = nowMs + quarantineMs }
        return decision(reasonAndKey.first, offender, closeAll = true)
    }

    @Synchronized
    fun observeOutboundFailureBurst(
        outboundTag: String,
        failureCount: Int,
        nowMs: Long
    ): ConnectionStormDecision? {
        val normalizedTag = outboundTag.trim()
        if (normalizedTag.isEmpty() || failureCount < outboundFailureLimit) return null
        trim(nowMs)
        val quarantineKey = "outbound:$normalizedTag"
        if (quarantinedUntilMs[quarantineKey]?.let { it > nowMs } == true) return null
        quarantinedUntilMs[quarantineKey] = nowMs + quarantineMs
        val matchingConnectionIds = activeConnections
            .filterValues { tracked -> tracked.usesOutbound(normalizedTag) }
            .keys
            .toSet()
        return ConnectionStormDecision(
            reason = ConnectionStormReason.OUTBOUND_FAILURE_BURST,
            offender = null,
            activeConnections = activeConnections.size,
            newConnectionsInWindow = failureCount,
            creationRatePerSecond = failureCount * 1_000.0 / windowMs,
            closeAll = false,
            connectionIds = matchingConnectionIds,
            outboundCounts = mapOf(normalizedTag to failureCount)
        )
    }

    @Synchronized
    fun acknowledgeClosed(decision: ConnectionStormDecision) {
        if (decision.closeAll) {
            activeConnections.clear()
            recentCreations.clear()
            return
        }
        activeConnections.keys.removeAll(decision.connectionIds)
        recentCreations.removeAll { it.connectionId in decision.connectionIds }
    }

    @Synchronized
    fun snapshot(): ConnectionAttributionSnapshot = buildAttributionSnapshot()

    @Synchronized
    fun clear() {
        activeConnections.clear()
        recentCreations.clear()
        quarantinedUntilMs.clear()
        hasObservedSnapshot = false
    }

    private fun decision(
        reason: ConnectionStormReason,
        offender: ConnectionSourceIdentity?,
        closeAll: Boolean,
        connectionIds: Set<String> = emptySet()
    ): ConnectionStormDecision {
        val snapshot = buildAttributionSnapshot()
        return ConnectionStormDecision(
            reason = reason,
            offender = offender,
            activeConnections = snapshot.activeConnections,
            newConnectionsInWindow = recentCreations.size,
            creationRatePerSecond = recentCreations.size * 1_000.0 / windowMs,
            closeAll = closeAll,
            connectionIds = connectionIds,
            outboundCounts = snapshot.outboundCounts,
            chainCounts = snapshot.chainCounts,
            protocolCounts = snapshot.protocolCounts
        )
    }

    private fun buildAttributionSnapshot(): ConnectionAttributionSnapshot = ConnectionAttributionSnapshot(
        activeConnections = activeConnections.size,
        outboundCounts = activeConnections.values
            .mapNotNull { it.event.outbound ?: it.event.tags.lastOrNull() }
            .countTopValues(),
        chainCounts = activeConnections.values
            .mapNotNull { tracked ->
                tracked.event.chain.takeIf(List<String>::isNotEmpty)?.joinToString(">")
            }
            .countTopValues(),
        protocolCounts = activeConnections.values
            .mapNotNull { tracked ->
                listOfNotNull(tracked.event.network, tracked.event.protocol)
                    .takeIf(List<String>::isNotEmpty)
                    ?.joinToString("/")
            }
            .countTopValues(),
        applicationCounts = activeConnections.values
            .map { tracked ->
                tracked.source.packageNames.takeIf(List<String>::isNotEmpty)?.joinToString("|")
                    ?: tracked.source.uid?.let { "uid:$it" }
                    ?: "unknown"
            }
            .countTopValues()
    )

    private fun List<String>.countTopValues(): Map<String, Int> = groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending(Map.Entry<String, Int>::value)
        .take(MAX_SNAPSHOT_GROUPS)
        .associate { it.key to it.value }

    private fun sourceForKey(key: String?): ConnectionSourceIdentity? {
        if (key == null) return null
        return activeConnections.values.firstOrNull { it.source.key == key }?.source
            ?: recentCreations.firstOrNull { it.source.key == key }?.source
    }

    private fun TrackedConnection.usesOutbound(outboundTag: String): Boolean {
        return outboundTag in routingTags
    }

    private fun observeEvent(event: ConnectionTrafficEventData, countAsCreation: Boolean, nowMs: Long): String? {
        when (event.type) {
            ConnectionTrafficAttributor.EVENT_CLOSED -> activeConnections.remove(event.id)
            ConnectionTrafficAttributor.EVENT_NEW -> {
                val source = event.sourceIdentity()
                val existing = activeConnections[event.id]
                val previous = activeConnections.put(
                    event.id,
                    TrackedConnection(
                        source = source,
                        event = event,
                        routingTags = event.routingTags().ifEmpty { existing?.routingTags.orEmpty() }
                    )
                )
                if (countAsCreation && previous == null) recentCreations.addLast(Creation(event.id, source, nowMs))
                if (quarantinedUntilMs[source.key]?.let { it > nowMs } == true) return event.id
            }
            else -> activeConnections[event.id]?.let { tracked ->
                activeConnections[event.id] = tracked.copy(
                    event = event,
                    routingTags = event.routingTags().ifEmpty { tracked.routingTags }
                )
            }
        }
        return null
    }

    private fun trim(nowMs: Long) {
        while (recentCreations.isNotEmpty() && nowMs - recentCreations.first().atMs >= windowMs) {
            recentCreations.removeFirst()
        }
        quarantinedUntilMs.entries.removeAll { (_, untilMs) -> untilMs <= nowMs }
    }

    private fun ConnectionTrafficEventData.sourceIdentity() = ConnectionSourceIdentity(
        uid = uid,
        packageNames = packageNames.distinct().sorted(),
        inbound = inbound,
        source = source
    )

    private fun ConnectionTrafficEventData.routingTags(): Set<String> {
        return buildSet {
            outbound?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            tags.map(String::trim).filter(String::isNotBlank).forEach(::add)
            chain.map(String::trim).filter(String::isNotBlank).forEach(::add)
        }
    }

    private companion object {
        const val DEFAULT_SOURCE_CREATION_LIMIT = 256
        const val DEFAULT_GLOBAL_CREATION_LIMIT = 1_024
        const val DEFAULT_SOURCE_ACTIVE_LIMIT = 1_024
        const val DEFAULT_GLOBAL_ACTIVE_LIMIT = 4_096
        const val DEFAULT_OUTBOUND_FAILURE_LIMIT = 3
        const val DEFAULT_WINDOW_MS = 5_000L
        const val DEFAULT_QUARANTINE_MS = 60_000L
        const val MAX_TARGETED_CLOSES = 32
        const val MAX_SNAPSHOT_GROUPS = 8
    }
}
