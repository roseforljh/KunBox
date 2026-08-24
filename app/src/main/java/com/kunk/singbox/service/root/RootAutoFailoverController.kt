package com.kunk.singbox.service.root

import android.content.Context
import android.os.SystemClock
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
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class RootFailoverGroup(
    val tag: String,
    val currentTag: String,
    val candidateTags: List<String>
)

internal data class RootFailoverTarget(
    val outboundTag: String,
    val groupTag: String? = null
)

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

    @Synchronized
    @Suppress("ReturnCount")
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
        if (!isError || !TRANSPORT_FAILURE_REGEX.containsMatchIn(cleanLine)) return null
        val binding = connectionId?.let(bindings::remove)
        val failedTag = binding?.outboundTag
            ?: OUTBOUND_TAG_REGEX.find(cleanLine)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeUnless { it == "direct" }
            ?: return null
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
    }

    private fun trimBindings(nowMs: Long) {
        bindings.entries.removeAll { (_, binding) -> nowMs - binding.atMs > BINDING_TTL_MS }
    }

    companion object {
        private const val BINDING_TTL_MS = 60_000L
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
    }
}

internal object RootFailoverGroups {
    private val skippedTypes = setOf("selector", "urltest", "direct", "block", "dns")

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
        return selected?.let { RootFailoverTarget(it, detourTag) }
    }

    fun resolve(
        outbounds: List<Outbound>,
        failedTag: String,
        preferredGroupTag: String? = null,
        selectedTag: (String) -> String?
    ): RootFailoverGroup? {
        val byTag = outbounds.associateBy(Outbound::tag)
        return outbounds.asSequence()
            .filter { it.type.equals("selector", ignoreCase = true) }
            .filter { preferredGroupTag.isNullOrBlank() || it.tag == preferredGroupTag }
            .mapNotNull { group ->
                val current = selectedTag(group.tag)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                if (!current.equals(failedTag, ignoreCase = true)) return@mapNotNull null
                val candidates = group.outbounds.orEmpty().flatMap { childTag ->
                    val child = byTag[childTag]
                    if (child?.type.equals("urltest", ignoreCase = true)) {
                        child?.outbounds.orEmpty()
                    } else {
                        listOf(childTag)
                    }
                }.filter { tag -> byTag[tag]?.type?.lowercase() !in skippedTypes }.distinct()
                RootFailoverGroup(group.tag, current, candidates)
            }
            .sortedBy { if (it.tag == "PROXY") 1 else 0 }
            .firstOrNull()
    }
}

internal class RootAutoFailoverController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val commandManager: CommandManager,
    private val rootService: () -> IRootSingBoxService?,
    private val onSwitched: (String, String) -> Unit
) {
    private val gson = Gson()
    private val failureTracker = RootRuntimeFailureTracker()
    private val healthSignals = HealthSignalAggregator()
    private val inFlight = AtomicBoolean(false)
    private var job: Job? = null

    fun onKernelLog(message: String) {
        val now = SystemClock.elapsedRealtime()
        val transportFailure = failureTracker.observe(message, now)
        val healthSignal = healthSignals.observeKernelLog(message, now)
        val target = transportFailure ?: when (healthSignal?.kind) {
            HealthSignalKind.ACTIVE_PROBE_FAILED -> healthSignal.outboundTag?.let(::RootFailoverTarget)
            HealthSignalKind.REMOTE_DNS_TIMEOUT -> loadRunningConfig()?.let { config ->
                RootFailoverGroups.resolveDnsFailureTarget(
                    config,
                    healthSignal.dnsServerTag,
                    commandManager::getResolvedSelectedOutbound
                )
            }
            else -> null
        }
        if (target == null || !inFlight.compareAndSet(false, true)) return
        job = scope.launch(Dispatchers.IO) {
            try {
                failover(target)
            } finally {
                inFlight.set(false)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        inFlight.set(false)
        failureTracker.clear()
        healthSignals.clearDnsFailures()
    }

    @Suppress("LongMethod", "ReturnCount")
    private suspend fun failover(target: RootFailoverTarget) {
        if (!RootTransparentForegroundService.isRunning || VpnStateStore.isManuallyStopped()) return
        LogRepository.getInstance().addAlwaysLog(
            "WARN: Root auto failover triggered group=${target.groupTag.orEmpty()} " +
                "outbound=${target.outboundTag}"
        )
        val now = System.currentTimeMillis()
        if (NodeAutoFailoverPolicy.isCooldownActive(VpnStateStore.getLastAutoFailoverAtMs(), now)) return
        if (NodeAutoFailoverPolicy.isBudgetExhausted(
                VpnStateStore.getAutoFailoverWindowStartAtMs(),
                VpnStateStore.getAutoFailoverCountInWindow(),
                now
            )
        ) return
        val config = loadRunningConfig() ?: return
        val outbounds = config.outbounds.orEmpty()
        val group = RootFailoverGroups.resolve(
            outbounds,
            target.outboundTag,
            target.groupTag,
            commandManager::getResolvedSelectedOutbound
        )
            ?: run {
                LogRepository.getInstance().addAlwaysLog(
                    "WARN: Root auto failover skipped reason=no_group outbound=${target.outboundTag}"
                )
                return
            }
        val byTag = outbounds.associateBy(Outbound::tag)
        val quarantine = NodeAutoFailoverPolicy.cleanupExpiredQuarantine(
            NodeAutoFailoverPolicy.decodeQuarantine(VpnStateStore.getAutoFailoverQuarantinedTags()),
            now
        )
        VpnStateStore.setAutoFailoverQuarantinedTags(NodeAutoFailoverPolicy.encodeQuarantine(quarantine))
        val quarantinedTags = quarantine.mapTo(mutableSetOf()) { it.tag }
        val candidates = group.candidateTags
            .filter { !it.equals(group.currentTag, ignoreCase = true) }
            .filter { candidate ->
                quarantinedTags.none { quarantined -> quarantined.equals(candidate, ignoreCase = true) }
            }
            .mapNotNull(byTag::get)
        if (candidates.isEmpty()) return
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
        val selectedCandidate = NodeAutoFailoverPolicy.evaluateProbe(
            currentTag = group.currentTag,
            urlTestResults = delays,
            quarantinedTags = quarantinedTags,
            treatCurrentAsFailed = true
        ).alternativeTag ?: run {
            LogRepository.getInstance().addAlwaysLog(
                "WARN: Root auto failover skipped reason=no_healthy_candidate group=${group.tag}"
            )
            return
        }
        val result = SelectorManager.switchNode(group.tag, selectedCandidate, group.candidateTags)
        if (result !is SelectorManager.SwitchResult.Success) return
        commandManager.closeConnections()
        rootService()?.resetNetwork()
        recordSuccess(group.currentTag, now)
        onSwitched(group.tag, selectedCandidate)
        LogRepository.getInstance().addAlwaysLog(
            "INFO: Root auto failover committed group=${group.tag} " +
                "${group.currentTag} -> $selectedCandidate"
        )
    }

    private fun loadRunningConfig(): SingBoxConfig? = runCatching {
        gson.fromJson(File(context.filesDir, "running_config.json").readText(), SingBoxConfig::class.java)
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

    companion object {
        private const val PROBE_TIMEOUT_MS = 3_000
        private const val PROBE_CONCURRENCY = 6
    }
}
