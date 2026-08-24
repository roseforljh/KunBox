package com.kunk.singbox.service.root

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.kunk.singbox.aidl.IRootSingBoxService
import com.kunk.singbox.core.LatencyProbeTrafficKind
import com.kunk.singbox.core.SelectorManager
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.service.HealthSignalAggregator
import com.kunk.singbox.service.HealthSignalKind
import com.kunk.singbox.service.NodeAutoFailoverPolicy
import com.kunk.singbox.service.manager.CommandManager
import com.kunk.singbox.service.manager.UrlTestTagMatcher
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal enum class RootFailoverSignalSource {
    TRANSPORT,
    DNS,
    ACTIVE_PROBE
}

internal data class RootFailoverTarget(
    val outboundTag: String,
    val groupTag: String? = null,
    val source: RootFailoverSignalSource = RootFailoverSignalSource.TRANSPORT
)

internal data class RootFailoverCandidate(
    val selectTag: String,
    val probeTag: String
)

internal data class RootFailoverGroup(
    val tag: String,
    val currentSelectionTag: String,
    val currentResolvedTag: String,
    val selectableTags: List<String>,
    val candidates: List<RootFailoverCandidate>,
    val dependencyDepth: Int
)

internal data class RootFailoverSwitchPlan(
    val group: RootFailoverGroup,
    val candidate: RootFailoverCandidate,
    val delayMs: Int
)

internal fun chooseRootFailoverTarget(
    transportTarget: RootFailoverTarget?,
    healthTarget: RootFailoverTarget?
): RootFailoverTarget? = when {
    healthTarget?.source == RootFailoverSignalSource.DNS -> healthTarget
    transportTarget != null -> transportTarget
    else -> healthTarget
}

internal fun isRootFailoverRuntimeCurrent(
    expectedGeneration: Long,
    currentGeneration: Long,
    rootRunning: Boolean,
    manuallyStopped: Boolean,
    rootServicePresent: Boolean
): Boolean {
    return rootRunning && !manuallyStopped && rootServicePresent &&
        expectedGeneration > 0L && expectedGeneration == currentGeneration
}

internal enum class RootFailoverGroupRuntimeState {
    NEEDS_SWITCH,
    HEALED,
    UNAVAILABLE
}

internal fun classifyRootFailoverGroupRuntime(
    currentResolvedTag: String?,
    failedTag: String
): RootFailoverGroupRuntimeState = when {
    currentResolvedTag.isNullOrBlank() -> RootFailoverGroupRuntimeState.UNAVAILABLE
    currentResolvedTag.equals(failedTag, ignoreCase = true) -> RootFailoverGroupRuntimeState.NEEDS_SWITCH
    else -> RootFailoverGroupRuntimeState.HEALED
}

internal fun shouldDrainRootFailoverConnections(failedGroupCount: Int): Boolean = failedGroupCount == 0

internal enum class RootFailoverPermit {
    ACQUIRED,
    IN_FLIGHT,
    COOLDOWN,
    BUDGET_EXHAUSTED
}

internal class RootFailoverIncidentGate(
    private val cooldownMs: Long = NodeAutoFailoverPolicy.AUTO_FAILOVER_COOLDOWN_MS,
    private val budgetWindowMs: Long = NodeAutoFailoverPolicy.AUTO_FAILOVER_BUDGET_WINDOW_MS,
    private val budgetMaxCount: Int = NodeAutoFailoverPolicy.AUTO_FAILOVER_BUDGET_MAX_COUNT
) {
    private data class NodeState(
        var inFlight: Boolean = false,
        var lastCompletedAtMs: Long = 0L,
        var deduplicatedSignals: Int = 0,
        val attempts: ArrayDeque<Long> = ArrayDeque()
    )

    private val stateByNode = mutableMapOf<String, NodeState>()

    init {
        require(cooldownMs > 0L)
        require(budgetWindowMs > 0L)
        require(budgetMaxCount > 0)
    }

    @Synchronized
    fun acquire(outboundTag: String, nowMs: Long): RootFailoverPermit {
        val nodeKey = nodeKey(outboundTag)
        val state = stateByNode.getOrPut(nodeKey) { NodeState() }
        while (state.attempts.isNotEmpty() && nowMs - state.attempts.first >= budgetWindowMs) {
            state.attempts.removeFirst()
        }
        return when {
            state.inFlight -> {
                state.deduplicatedSignals++
                RootFailoverPermit.IN_FLIGHT
            }
            state.lastCompletedAtMs > 0L && nowMs - state.lastCompletedAtMs < cooldownMs -> {
                RootFailoverPermit.COOLDOWN
            }
            state.attempts.size >= budgetMaxCount -> RootFailoverPermit.BUDGET_EXHAUSTED
            else -> {
                state.inFlight = true
                state.attempts.addLast(nowMs)
                RootFailoverPermit.ACQUIRED
            }
        }
    }

    @Synchronized
    fun complete(outboundTag: String, nowMs: Long): Int {
        val state = stateByNode[nodeKey(outboundTag)] ?: return 0
        state.inFlight = false
        state.lastCompletedAtMs = nowMs
        return state.deduplicatedSignals.also { state.deduplicatedSignals = 0 }
    }

    @Synchronized
    fun clear() {
        stateByNode.clear()
    }

    private fun nodeKey(outboundTag: String): String = UrlTestTagMatcher.normalizeTag(outboundTag)
}

internal class RootRuntimeFailureTracker(
    private val windowMs: Long = 20_000L,
    private val minimumFailures: Int = 3
) {
    private data class Binding(
        val outboundTag: String? = null,
        val groupTag: String? = null,
        val atMs: Long
    )

    private val bindings = mutableMapOf<String, Binding>()
    private val failures = mutableMapOf<String, ArrayDeque<Long>>()
    private val expectedOutboundCloseUntilMs = mutableMapOf<String, Long>()

    @Synchronized
    fun expectTeardown(
        outboundTag: String,
        nowMs: Long,
        windowMs: Long = EXPECTED_TEARDOWN_WINDOW_MS
    ) {
        if (outboundTag.isBlank() || windowMs <= 0L) return
        val untilMs = nowMs + windowMs
        expectedOutboundCloseUntilMs[UrlTestTagMatcher.normalizeTag(outboundTag)] = untilMs
    }

    @Synchronized
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun observe(line: String, nowMs: Long): RootFailoverTarget? {
        val cleanLine = ANSI_ESCAPE_REGEX.replace(line, "")
        val connectionId = CONNECTION_ID_REGEX.find(cleanLine)?.groupValues?.getOrNull(1)
        val isError = ERROR_LEVEL_REGEX.containsMatchIn(cleanLine)
        if (connectionId != null && !isError) {
            val outboundTag = OUTBOUND_TAG_REGEX.find(cleanLine)?.groupValues?.getOrNull(1)
                ?.takeUnless { it == "direct" }
            val groupTag = ROUTE_TAG_REGEX.find(cleanLine)?.groupValues?.getOrNull(1)
            if (!outboundTag.isNullOrBlank() || !groupTag.isNullOrBlank()) {
                val previous = bindings[connectionId]
                bindings[connectionId] = Binding(
                    outboundTag = previous?.outboundTag ?: outboundTag,
                    groupTag = previous?.groupTag ?: groupTag,
                    atMs = nowMs
                )
            }
        }
        trimBindings(nowMs)
        trimExpectedTeardowns(nowMs)
        if (cleanLine.contains("dns:", ignoreCase = true)) return null
        if (!isError || !TRANSPORT_FAILURE_REGEX.containsMatchIn(cleanLine)) return null
        val binding = connectionId?.let(bindings::remove)
        val failedTag = binding?.outboundTag
            ?: OUTBOUND_TAG_REGEX.find(cleanLine)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeUnless { it == "direct" }
            ?: return null
        if (EXPECTED_TEARDOWN_REGEX.containsMatchIn(cleanLine) && isExpectedTeardown(failedTag, nowMs)) return null
        val queue = failures.getOrPut(failedTag) { ArrayDeque() }
        queue.addLast(nowMs)
        while (queue.isNotEmpty() && nowMs - queue.first > windowMs) queue.removeFirst()
        if (queue.size < minimumFailures) return null
        queue.clear()
        return RootFailoverTarget(failedTag, binding?.groupTag)
    }

    @Synchronized
    fun clear() {
        bindings.clear()
        failures.clear()
        expectedOutboundCloseUntilMs.clear()
    }

    private fun trimBindings(nowMs: Long) {
        bindings.entries.removeAll { (_, binding) -> nowMs - binding.atMs > BINDING_TTL_MS }
    }

    private fun trimExpectedTeardowns(nowMs: Long) {
        expectedOutboundCloseUntilMs.entries.removeAll { (_, untilMs) -> untilMs < nowMs }
    }

    private fun isExpectedTeardown(outboundTag: String, nowMs: Long): Boolean {
        val outboundKey = UrlTestTagMatcher.normalizeTag(outboundTag)
        return expectedOutboundCloseUntilMs[outboundKey]?.let { it >= nowMs } == true
    }

    companion object {
        private const val BINDING_TTL_MS = 60_000L
        private const val EXPECTED_TEARDOWN_WINDOW_MS = 5_000L
        private val ANSI_ESCAPE_REGEX = Regex("\u001B\\[[;?0-9]*[ -/]*[@-~]")
        private val CONNECTION_ID_REGEX = Regex("""\[(\d+)\s""")
        private val ERROR_LEVEL_REGEX = Regex("""\bERROR\b""")
        private val OUTBOUND_TAG_REGEX = Regex("""outbound/[^\[]+\[([^]]+)]""", RegexOption.IGNORE_CASE)
        private val ROUTE_TAG_REGEX = Regex("""=> route\(([^)]+)\)""", RegexOption.IGNORE_CASE)
        private val TRANSPORT_FAILURE_REGEX = Regex(
            """(?i)(?:i/o timeout|context deadline exceeded|network is unreachable|no route to host|""" +
                """connection reset by peer|connection refused|broken pipe|use of closed network connection|""" +
                """no recent network activity)"""
        )
        private val EXPECTED_TEARDOWN_REGEX = Regex(
            """(?i)(?:use of closed network connection|operation canceled|context canceled)"""
        )
    }
}

internal object RootFailoverGroups {
    private val unsupportedCandidateTypes = setOf("direct", "block", "dns")

    fun resolveDnsFailureTarget(
        config: SingBoxConfig,
        dnsServerTag: String?,
        selectedTag: (String) -> String?
    ): RootFailoverTarget? {
        val dnsTag = dnsServerTag?.trim().orEmpty()
        if (dnsTag.isBlank()) return null
        val detourTag = config.dns
            ?.servers
            .orEmpty()
            .firstOrNull { it.tag?.trim() == dnsTag }
            ?.detour
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        val detourType = config.outbounds.orEmpty()
            .firstOrNull { it.tag == detourTag }
            ?.type
            ?.trim()
            ?.lowercase()
        val selectable = detourType == "selector" || detourType == "urltest" || detourType == "url-test"
        val selected = if (selectable) selectedTag(detourTag)?.trim()?.takeIf(String::isNotBlank) else null
        return selected?.let { RootFailoverTarget(it, detourTag, RootFailoverSignalSource.DNS) }
    }

    fun resolve(
        outbounds: List<Outbound>,
        failedTag: String,
        preferredGroupTag: String? = null,
        selectedTag: (String) -> String?
    ): RootFailoverGroup? = resolveAll(
        outbounds = outbounds,
        failedTag = failedTag,
        preferredGroupTag = preferredGroupTag,
        selectedTag = selectedTag,
        resolvedTag = selectedTag
    ).firstOrNull()

    @Suppress("LongParameterList")
    fun resolveAll(
        outbounds: List<Outbound>,
        failedTag: String,
        preferredGroupTag: String? = null,
        selectedTag: (String) -> String?,
        resolvedTag: (String) -> String?
    ): List<RootFailoverGroup> {
        val byTag = outbounds.associateBy(Outbound::tag)
        return outbounds.asSequence()
            .filter { it.type.equals("selector", ignoreCase = true) }
            .mapNotNull { group ->
                val currentSelection = selectedTag(group.tag)?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val currentResolved = resolvedTag(group.tag)?.takeIf(String::isNotBlank)
                    ?: currentSelection
                if (!currentResolved.equals(failedTag, ignoreCase = true)) return@mapNotNull null
                val selectableTags = group.outbounds.orEmpty().distinct()
                val candidates = selectableTags.asSequence()
                    .filter { it != currentSelection }
                    .mapNotNull { childTag ->
                        resolveCandidate(childTag, failedTag, byTag, resolvedTag)
                    }
                    .distinctBy(RootFailoverCandidate::selectTag)
                    .toList()
                RootFailoverGroup(
                    tag = group.tag,
                    currentSelectionTag = currentSelection,
                    currentResolvedTag = currentResolved,
                    selectableTags = selectableTags,
                    candidates = candidates,
                    dependencyDepth = selectionDependencyDepth(group.tag, selectedTag, byTag)
                )
            }
            .sortedWith(
                compareBy<RootFailoverGroup> { it.dependencyDepth }
                    .thenBy { group ->
                        when {
                            group.tag == preferredGroupTag -> 0
                            group.tag == "PROXY" -> 2
                            else -> 1
                        }
                    }
            )
            .toList()
    }

    private fun resolveCandidate(
        selectTag: String,
        failedTag: String,
        byTag: Map<String, Outbound>,
        resolvedTag: (String) -> String?
    ): RootFailoverCandidate? {
        val child = byTag[selectTag] ?: return null
        val childType = child.type.trim().lowercase()
        val probeTag = when {
            childType in unsupportedCandidateTypes -> null
            isGroupType(childType) -> resolvedTag(selectTag)?.trim()?.takeIf(String::isNotBlank)
            else -> selectTag
        }
        val probeType = probeTag?.let(byTag::get)?.type?.trim()?.lowercase().orEmpty()
        return probeTag
            ?.takeUnless { it.equals(failedTag, ignoreCase = true) }
            ?.takeIf { isSupportedProbeType(probeType) }
            ?.let { RootFailoverCandidate(selectTag = selectTag, probeTag = it) }
    }

    private fun isGroupType(type: String): Boolean {
        return type == "selector" || type == "urltest" || type == "url-test"
    }

    private fun isSupportedProbeType(type: String): Boolean {
        return type.isNotBlank() && type !in unsupportedCandidateTypes && !isGroupType(type)
    }

    private fun selectionDependencyDepth(
        groupTag: String,
        selectedTag: (String) -> String?,
        byTag: Map<String, Outbound>,
        visited: Set<String> = emptySet()
    ): Int {
        if (groupTag in visited) return 0
        val childTag = selectedTag(groupTag)?.trim()?.takeIf(String::isNotBlank) ?: return 0
        val childType = byTag[childTag]?.type?.trim()?.lowercase().orEmpty()
        if (childType != "selector") return 0
        return 1 + selectionDependencyDepth(childTag, selectedTag, byTag, visited + groupTag)
    }
}

internal object RootFailoverPlanner {
    fun probeTags(groups: List<RootFailoverGroup>): Set<String> {
        return groups.flatMap(RootFailoverGroup::candidates)
            .map(RootFailoverCandidate::probeTag)
            .toSet()
    }

    fun build(
        groups: List<RootFailoverGroup>,
        delaysByProbeTag: Map<String, Int>,
        quarantinedTags: Set<String>
    ): List<RootFailoverSwitchPlan> {
        val normalizedQuarantine = quarantinedTags.mapTo(mutableSetOf(), UrlTestTagMatcher::normalizeTag)
        return groups.mapNotNull { group ->
            val available = group.candidates.filter { candidate ->
                delaysByProbeTag[candidate.probeTag]?.let { it > 0 } == true &&
                    UrlTestTagMatcher.normalizeTag(candidate.probeTag) !in normalizedQuarantine
            }
            val delaysBySelectTag = available.associate { candidate ->
                candidate.selectTag to checkNotNull(delaysByProbeTag[candidate.probeTag])
            }
            val evaluation = NodeAutoFailoverPolicy.evaluateProbe(
                currentTag = group.currentSelectionTag,
                urlTestResults = delaysBySelectTag,
                treatCurrentAsFailed = true
            )
            val selectTag = evaluation.alternativeTag ?: return@mapNotNull null
            val candidate = available.firstOrNull { it.selectTag == selectTag } ?: return@mapNotNull null
            RootFailoverSwitchPlan(
                group = group,
                candidate = candidate,
                delayMs = checkNotNull(delaysByProbeTag[candidate.probeTag])
            )
        }
    }
}

internal class RootAutoFailoverController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val commandManager: CommandManager,
    private val rootService: () -> IRootSingBoxService?,
    private val onSwitched: (String, String) -> Unit
) {
    private data class Incident(
        val id: Long,
        val target: RootFailoverTarget,
        val runtimeGeneration: Long,
        val nodeKey: String
    )

    private val gson = Gson()
    private val failureTracker = RootRuntimeFailureTracker()
    private val healthSignals = HealthSignalAggregator()
    private val incidentGate = RootFailoverIncidentGate()
    private val incidentSequence = AtomicLong(0L)
    private val jobs = ConcurrentHashMap<String, Job>()

    fun onKernelLog(message: String) {
        val now = SystemClock.elapsedRealtime()
        val transportFailure = failureTracker.observe(message, now)
        val healthSignal = healthSignals.observeKernelLog(message, now)
        val healthTarget = when (healthSignal?.kind) {
            HealthSignalKind.ACTIVE_PROBE_FAILED -> healthSignal.outboundTag?.let { outboundTag ->
                RootFailoverTarget(
                    outboundTag = outboundTag,
                    source = RootFailoverSignalSource.ACTIVE_PROBE
                )
            }
            HealthSignalKind.REMOTE_DNS_TIMEOUT -> loadRunningConfig()?.let { config ->
                RootFailoverGroups.resolveDnsFailureTarget(
                    config,
                    healthSignal.dnsServerTag,
                    commandManager::getResolvedSelectedOutbound
                )
            }
            else -> null
        }
        val target = chooseRootFailoverTarget(transportFailure, healthTarget) ?: return
        val permit = incidentGate.acquire(target.outboundTag, now)
        if (permit != RootFailoverPermit.ACQUIRED) {
            if (permit != RootFailoverPermit.IN_FLIGHT) logSuppressed(target, permit)
            return
        }
        val incident = Incident(
            id = incidentSequence.incrementAndGet(),
            target = target,
            runtimeGeneration = commandManager.currentRuntimeGeneration(),
            nodeKey = UrlTestTagMatcher.normalizeTag(target.outboundTag)
        )
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            var outcome = "failed"
            try {
                outcome = failover(incident)
            } catch (error: kotlinx.coroutines.CancellationException) {
                outcome = "cancelled"
                logIncident(incident, "cancelled", "reason=scope_cancelled")
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Root failover incident failed id=${incident.id}", error)
                outcome = fail(incident, "exception_${error.javaClass.simpleName}")
            } finally {
                val deduplicated = incidentGate.complete(target.outboundTag, SystemClock.elapsedRealtime())
                if (deduplicated > 0) {
                    logIncident(incident, "deduplicated", "count=$deduplicated outcome=$outcome")
                }
            }
        }
        jobs[incident.nodeKey] = job
        job.invokeOnCompletion { jobs.remove(incident.nodeKey, job) }
        job.start()
    }

    fun stop() {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        incidentGate.clear()
        failureTracker.clear()
        healthSignals.clearDnsFailures()
    }

    @Suppress("LongMethod", "ReturnCount", "CognitiveComplexMethod", "CyclomaticComplexMethod")
    private suspend fun failover(incident: Incident): String {
        if (!isRuntimeCurrent(incident)) return fail(incident, "stale_runtime")
        logIncident(
            incident,
            "detected",
            "source=${incident.target.source.name.lowercase()} preferred_group=${incident.target.groupTag.orEmpty()}"
        )
        val now = System.currentTimeMillis()
        val config = loadRunningConfig() ?: return fail(incident, "config_unavailable")
        val outbounds = config.outbounds.orEmpty()
        val groups = RootFailoverGroups.resolveAll(
            outbounds = outbounds,
            failedTag = incident.target.outboundTag,
            preferredGroupTag = incident.target.groupTag,
            selectedTag = commandManager::getSelectedOutbound,
            resolvedTag = commandManager::getResolvedSelectedOutbound
        )
        if (groups.isEmpty()) return fail(incident, "no_affected_group")
        val byTag = outbounds.associateBy(Outbound::tag)
        val quarantine = NodeAutoFailoverPolicy.cleanupExpiredQuarantine(
            NodeAutoFailoverPolicy.decodeQuarantine(VpnStateStore.getAutoFailoverQuarantinedTags()),
            now
        )
        VpnStateStore.setAutoFailoverQuarantinedTags(NodeAutoFailoverPolicy.encodeQuarantine(quarantine))
        val quarantinedTags = quarantine.mapTo(mutableSetOf()) { it.tag }
        val probeTags = RootFailoverPlanner.probeTags(groups).filterNot { probeTag ->
            quarantinedTags.any { quarantined ->
                UrlTestTagMatcher.normalizeTag(quarantined) == UrlTestTagMatcher.normalizeTag(probeTag)
            }
        }
        val candidates = probeTags.mapNotNull(byTag::get)
        if (candidates.isEmpty()) return fail(incident, "no_candidate")
        val delays = ConcurrentHashMap<String, Int>()
        SingBoxCore.getInstance(context).testOutboundsLatency(
            outbounds = candidates,
            allOutbounds = outbounds,
            timeoutOverrideMs = PROBE_TIMEOUT_MS,
            concurrencyOverride = PROBE_CONCURRENCY,
            trafficKind = LatencyProbeTrafficKind.HEALTH_CHECK
        ) { tag, latency ->
            if (latency > 0L && latency <= Int.MAX_VALUE) delays[tag] = latency.toInt()
        }
        val plans = RootFailoverPlanner.build(groups, delays, quarantinedTags)
        if (plans.isEmpty()) return fail(incident, "no_healthy_candidate")
        logIncident(
            incident,
            "planned",
            "groups=${groups.size} probe_candidates=${candidates.size} switch_plans=${plans.size}"
        )
        val initialConnectionIds = commandManager.connectionIdsForOutboundTag(incident.target.outboundTag)
        failureTracker.expectTeardown(
            outboundTag = incident.target.outboundTag,
            nowMs = SystemClock.elapsedRealtime()
        )
        val planByGroup = plans.associateBy { it.group.tag }
        val switched = mutableListOf<RootFailoverSwitchPlan>()
        val failedGroups = mutableListOf<String>()
        val healedGroups = mutableListOf<String>()
        groups.forEach { group ->
            if (!isRuntimeCurrent(incident)) return fail(incident, "stale_generation")
            val currentResolved = commandManager.getResolvedSelectedOutbound(group.tag)
            when (classifyRootFailoverGroupRuntime(currentResolved, incident.target.outboundTag)) {
                RootFailoverGroupRuntimeState.HEALED -> {
                    healedGroups += group.tag
                    return@forEach
                }
                RootFailoverGroupRuntimeState.UNAVAILABLE -> {
                    failedGroups += group.tag
                    return@forEach
                }
                RootFailoverGroupRuntimeState.NEEDS_SWITCH -> Unit
            }
            val plan = planByGroup[group.tag]
            if (plan == null) {
                failedGroups += group.tag
                return@forEach
            }
            failureTracker.expectTeardown(
                outboundTag = incident.target.outboundTag,
                nowMs = SystemClock.elapsedRealtime()
            )
            val result = SelectorManager.switchNode(
                groupTag = group.tag,
                nodeTag = plan.candidate.selectTag,
                allowedOutboundTags = group.selectableTags
            )
            val resolvedAfterSwitch = commandManager.getResolvedSelectedOutbound(group.tag)
            if (result is SelectorManager.SwitchResult.Success &&
                resolvedAfterSwitch.equals(plan.candidate.probeTag, ignoreCase = true)
            ) {
                switched += plan
                onSwitched(group.tag, plan.candidate.selectTag)
            } else {
                failedGroups += group.tag
            }
        }
        if (switched.isEmpty() && healedGroups.isEmpty()) return fail(incident, "no_switch_committed")
        if (!isRuntimeCurrent(incident)) return fail(incident, "stale_generation_after_switch")
        if (switched.isNotEmpty()) recordSuccess(incident.target.outboundTag, System.currentTimeMillis())
        if (!shouldDrainRootFailoverConnections(failedGroups.size)) {
            logIncident(
                incident,
                "partial",
                "switched=${switched.size} healed=${healedGroups.size} failed=${failedGroups.size} " +
                    "drain=skipped_unresolved_groups"
            )
            return "partial"
        }
        if (!isRuntimeCurrent(incident)) return fail(incident, "stale_generation_before_drain")
        val connectionIds = initialConnectionIds +
            commandManager.connectionIdsForOutboundTag(incident.target.outboundTag)
        failureTracker.expectTeardown(
            outboundTag = incident.target.outboundTag,
            nowMs = SystemClock.elapsedRealtime()
        )
        val closedIds = commandManager.closeConnectionsById(connectionIds)
        logIncident(
            incident,
            "drained",
            "requested=${connectionIds.size} closed=${closedIds.size} failed=${connectionIds.size - closedIds.size}"
        )
        val outcome = if (switched.isNotEmpty()) "committed" else "resolved_external"
        logIncident(
            incident,
            outcome,
            "switched=${switched.size} healed=${healedGroups.size} failed=${failedGroups.size} " +
                "groups=${switched.joinToString(",") { it.group.tag }}"
        )
        return outcome
    }

    private fun loadRunningConfig(): SingBoxConfig? = runCatching {
        gson.fromJson(File(context.filesDir, "running_config.json").readText(), SingBoxConfig::class.java)
    }.onFailure { error ->
        Log.e(TAG, "Load running config for Root failover failed", error)
    }.getOrNull()

    private fun recordSuccess(currentTag: String, now: Long) {
        val budget = NodeAutoFailoverPolicy.registerFailoverAttempt(
            VpnStateStore.getAutoFailoverWindowStartAtMs(),
            VpnStateStore.getAutoFailoverCountInWindow(),
            now
        )
        VpnStateStore.setLastAutoFailoverAtMs(now)
        VpnStateStore.setAutoFailoverWindowStartAtMs(budget.windowStartAtMs)
        VpnStateStore.setAutoFailoverCountInWindow(budget.count)
        VpnStateStore.setLastAutoFailoverNodeTag(currentTag)
        val quarantine = NodeAutoFailoverPolicy.cleanupExpiredQuarantine(
            NodeAutoFailoverPolicy.decodeQuarantine(VpnStateStore.getAutoFailoverQuarantinedTags()) +
                NodeAutoFailoverPolicy.createQuarantineRecord(currentTag, now),
            now
        )
        VpnStateStore.setAutoFailoverQuarantinedTags(NodeAutoFailoverPolicy.encodeQuarantine(quarantine))
    }

    private fun isRuntimeCurrent(incident: Incident): Boolean {
        return isRootFailoverRuntimeCurrent(
            expectedGeneration = incident.runtimeGeneration,
            currentGeneration = commandManager.currentRuntimeGeneration(),
            rootRunning = RootTransparentForegroundService.isRunning,
            manuallyStopped = VpnStateStore.isManuallyStopped(),
            rootServicePresent = rootService() != null
        )
    }

    private fun fail(incident: Incident, reason: String): String {
        logIncident(incident, "failed", "reason=$reason")
        return "failed:$reason"
    }

    private fun logIncident(incident: Incident, state: String, details: String) {
        val level = if (state == "failed" || state == "partial") "WARN" else "INFO"
        LogRepository.getInstance().addAlwaysLog(
            "$level [ROOT_FAILOVER] incident=${incident.id} node=${incident.target.outboundTag} " +
                "state=$state $details"
        )
    }

    private fun logSuppressed(target: RootFailoverTarget, permit: RootFailoverPermit) {
        LogRepository.getInstance().addAlwaysLog(
            "WARN [ROOT_FAILOVER] incident=none node=${target.outboundTag} state=suppressed " +
                "reason=${permit.name.lowercase()}"
        )
    }

    companion object {
        private const val TAG = "RootAutoFailover"
        private const val PROBE_TIMEOUT_MS = 3_000
        private const val PROBE_CONCURRENCY = 6
    }
}
