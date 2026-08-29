@file:Suppress("UnusedImports", "TooManyFunctions", "LongMethod", "LargeClass", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeCons")

package com.kunk.singbox.service

import android.content.Intent
import android.net.Network
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.core.LatencyProbeTrafficKind
import com.kunk.singbox.core.SelectorManager
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.repository.*
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.NodeProtectionStore
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.service.manager.UrlTestTagMatcher
import com.kunk.singbox.utils.DefaultNetworkListener
import com.kunk.singbox.utils.perf.StateCache
import com.kunk.singbox.utils.perf.PerfTracer
import io.nekohasekai.libbox.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal suspend fun SingBoxService.runAutoFailoverProbeSequence(trigger: String) {
    try {
        val completed = if (SingBoxService.isHealthFastPathTrigger(trigger)) {
            withTimeoutOrNull(SingBoxService.HEALTH_FAST_FAILOVER_TOTAL_TIMEOUT_MS) {
                runAutoFailoverProbeSequenceBody(trigger)
                true
            } == true
        } else {
            runAutoFailoverProbeSequenceBody(trigger)
            true
        }
        if (!completed) {
            LogRepository.getInstance().addLog(
                "WARN: Health failover probe timed out trigger=$trigger " +
                    "budget=${SingBoxService.HEALTH_FAST_FAILOVER_TOTAL_TIMEOUT_MS}ms"
            )
            Log.w(SingBoxService.TAG, "[AutoFailover] health fast path timed out: $trigger")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(SingBoxService.TAG, "[AutoFailover] probe sequence failed: $trigger", e)
    } finally {
        autoFailoverJob = null
    }
}

internal suspend fun SingBoxService.runAutoFailoverProbeSequenceBody(trigger: String) {
    val currentTag = resolveCurrentProxyOutboundTag()
    if (currentTag.isNullOrBlank()) {
        LogRepository.getInstance().addLog(
            "WARN: Health failover probe skipped reason=no_proxy_selection trigger=$trigger"
        )
        return
    }

    LogRepository.getInstance().addLog(
        "INFO: Health failover probe started current=$currentTag trigger=$trigger"
    )

    val firstEvaluation = runAutoFailoverProbeRound(currentTag, trigger)
    when {
        firstEvaluation.outcome == NodeAutoFailoverPolicy.ProbeOutcome.CURRENT_HEALTHY -> {
            LogRepository.getInstance().addLog(
                "INFO: Health failover probe keep current=$currentTag reason=offline_healthy " +
                    "delay=${firstEvaluation.currentDelayMs ?: -1} trigger=$trigger"
            )
        }

        firstEvaluation.outcome !=
            NodeAutoFailoverPolicy.ProbeOutcome.CURRENT_FAILED_WITH_ALTERNATIVE -> {
            LogRepository.getInstance().addLog(
                "WARN: Health failover probe no switch current=$currentTag " +
                    "outcome=${firstEvaluation.outcome} " +
                    "delay=${firstEvaluation.currentDelayMs ?: -1} trigger=$trigger"
            )
        }

        // 运行态已死（远程 DNS 超时等）：有候选就立刻切，不再做第二轮离线确认
        SingBoxService.isHealthFastPathTrigger(trigger) -> {
            val targetTag = firstEvaluation.alternativeTag.orEmpty()
            if (targetTag.isBlank()) {
                LogRepository.getInstance().addLog(
                    "WARN: Health failover probe no switch current=$currentTag " +
                        "outcome=no_target trigger=$trigger"
                )
                return
            }
            LogRepository.getInstance().addLog(
                "INFO: Health failover fast switch current=$currentTag " +
                    "to=$targetTag altDelay=${firstEvaluation.alternativeDelayMs ?: -1} trigger=$trigger"
            )
            performAutoFailoverSwitch(currentTag, targetTag, trigger)
        }

        else -> {
            handleSecondAutoFailoverProbe(
                currentTag = currentTag,
                firstEvaluation = firstEvaluation,
                trigger = trigger
            )
        }
    }
}

internal suspend fun SingBoxService.handleSecondAutoFailoverProbe(
    currentTag: String,
    firstEvaluation: NodeAutoFailoverPolicy.ProbeEvaluation,
    trigger: String
) {
    delay(SingBoxService.resolveAutoFailoverRetryDelayMs(trigger))
    val secondEvaluation = runAutoFailoverProbeRound(currentTag, trigger)
    when {
        secondEvaluation.outcome !=
            NodeAutoFailoverPolicy.ProbeOutcome.CURRENT_FAILED_WITH_ALTERNATIVE -> {
            LogRepository.getInstance().addLog(
                "INFO: Health failover second probe no switch current=$currentTag " +
                    "outcome=${secondEvaluation.outcome} trigger=$trigger"
            )
        }

        secondEvaluation.alternativeTag.isNullOrBlank() && firstEvaluation.alternativeTag.isNullOrBlank() -> {
            LogRepository.getInstance().addLog(
                "WARN: Health failover second probe no target current=$currentTag trigger=$trigger"
            )
        }

        else -> {
            val targetTag = secondEvaluation.alternativeTag
                ?: firstEvaluation.alternativeTag.orEmpty()
            performAutoFailoverSwitch(currentTag, targetTag, trigger)
        }
    }
}

internal suspend fun SingBoxService.runAutoFailoverProbeRound(
    currentTag: String,
    trigger: String
): NodeAutoFailoverPolicy.ProbeEvaluation {
    val quarantined = loadActiveAutoFailoverQuarantine(System.currentTimeMillis())
    val quarantinedTags = quarantined.map { it.tag }.toSet()
    var results = testGroupCandidatesLatency("PROXY", currentTag, trigger)
    var sampleSource = "live_probe"

    // 当前轮无结果时仅允许使用一分钟内的实时探测缓存
    if (results.isEmpty() && SingBoxService.isHealthFastPathTrigger(trigger)) {
        results = resolveAutoFailoverFallbackDelays(currentTag, quarantinedTags)
        sampleSource = "recent_live_cache"
    }

    // dns/active 失败已证明运行态挂了，离线测速不得把当前节点判回健康
    val evaluation = NodeAutoFailoverPolicy.evaluateProbe(
        currentTag = currentTag,
        urlTestResults = results,
        quarantinedTags = quarantinedTags,
        treatCurrentAsFailed = SingBoxService.isHealthFastPathTrigger(trigger)
    )
    LogRepository.getInstance().addLog(
        "INFO: Health failover probe result current=$currentTag outcome=${evaluation.outcome} " +
            "currentDelay=${evaluation.currentDelayMs ?: -1} " +
            "alt=${evaluation.alternativeTag ?: "(none)"} " +
            "altDelay=${evaluation.alternativeDelayMs ?: -1} " +
            "samples=${results.size} source=$sampleSource trigger=$trigger"
    )
    return evaluation
}

internal fun SingBoxService.resolveAutoFailoverFallbackDelays(
    currentTag: String,
    quarantinedTags: Set<String>
): Map<String, Int> {
    val config = loadLastRunningConfig() ?: return emptyMap()
    val outbounds = config.outbounds.orEmpty()
    val byTag = outbounds.associateBy { it.tag }
    val groupTags = resolveAutoFailoverGroupCandidates("PROXY", byTag)
        .map { it.tag }
        .filter { tag ->
            tag.isNotBlank() &&
                UrlTestTagMatcher.normalizeTag(tag) != UrlTestTagMatcher.normalizeTag(currentTag) &&
                quarantinedTags.none { q ->
                    UrlTestTagMatcher.normalizeTag(q) == UrlTestTagMatcher.normalizeTag(tag)
                }
        }
    if (groupTags.isEmpty()) return emptyMap()

    val cached = autoFailoverCandidateCache.resolve(
        currentTag = currentTag,
        nowMs = System.currentTimeMillis(),
        quarantinedTags = quarantinedTags
    )
    return cached
        ?.takeIf { candidate ->
            groupTags.any {
                UrlTestTagMatcher.normalizeTag(it) == UrlTestTagMatcher.normalizeTag(candidate)
            }
        }
        ?.let { mapOf(it to 1) }
        .orEmpty()
}

internal suspend fun SingBoxService.testGroupCandidatesLatency(groupTag: String): Map<String, Int> {
    return testGroupCandidatesLatency(groupTag, currentTag = null, trigger = "")
}

internal suspend fun SingBoxService.testGroupCandidatesLatency(
    groupTag: String,
    currentTag: String?,
    trigger: String
): Map<String, Int> = coroutineScope {
    val config = loadLastRunningConfig() ?: return@coroutineScope emptyMap()
    val outbounds = config.outbounds.orEmpty()
    val byTag = outbounds.associateBy { it.tag }
    var groupCandidates = resolveAutoFailoverGroupCandidates(groupTag, byTag)
    if (groupCandidates.isEmpty()) return@coroutineScope emptyMap()

    val quarantined = loadActiveAutoFailoverQuarantine(System.currentTimeMillis()).map { it.tag }.toSet()
    groupCandidates = limitAutoFailoverCandidatesForTrigger(
        groupCandidates = groupCandidates,
        currentTag = currentTag,
        trigger = trigger,
        quarantinedTags = quarantined
    )
    if (groupCandidates.isEmpty()) return@coroutineScope emptyMap()

    val resultMap = measureAutoFailoverCandidateLatencies(groupCandidates, outbounds, trigger)
    updateAutoFailoverCandidateCache(currentTag, resultMap, quarantined)
    resultMap
}

internal fun SingBoxService.resolveAutoFailoverGroupCandidates(
    groupTag: String,
    byTag: Map<String, Outbound>
): List<Outbound> {
    val requestedGroup = byTag[groupTag] ?: return emptyList()
    val autoGroup = requestedGroup.takeIf { it.type.equals("urltest", ignoreCase = true) }
        ?: requestedGroup.outbounds.orEmpty()
            .asSequence()
            .mapNotNull(byTag::get)
            .firstOrNull { it.type.equals("urltest", ignoreCase = true) }
        ?: return emptyList()
    return autoGroup.outbounds.orEmpty()
        .mapNotNull(byTag::get)
        .filter { candidate -> candidate.type !in SingBoxService.LATENCY_SKIPPED_OUTBOUND_TYPES }
}

internal fun SingBoxService.limitAutoFailoverCandidatesForTrigger(
    groupCandidates: List<Outbound>,
    currentTag: String?,
    trigger: String,
    quarantinedTags: Set<String>
): List<Outbound> {
    if (!SingBoxService.isHealthFastPathTrigger(trigger)) {
        return groupCandidates
    }
    val cachedBackup = currentTag?.let {
        autoFailoverCandidateCache.resolve(
            currentTag = it,
            nowMs = System.currentTimeMillis(),
            quarantinedTags = quarantinedTags
        )
    }
    val selectedTags = SingBoxService.selectAutoFailoverProbeCandidates(
        currentTag = currentTag.orEmpty(),
        cachedBackupTag = cachedBackup,
        candidateTags = groupCandidates.map { it.tag },
        trigger = trigger,
        quarantinedTags = quarantinedTags
    ).toSet()
    return groupCandidates.filter { it.tag in selectedTags }
}

@Suppress("CognitiveComplexMethod")
internal suspend fun SingBoxService.measureAutoFailoverCandidateLatencies(
    groupCandidates: List<Outbound>,
    outbounds: List<Outbound>,
    trigger: String
): Map<String, Int> = coroutineScope {
    if (SingBoxService.isHealthFastPathTrigger(trigger)) {
        return@coroutineScope measureFastAutoFailoverCandidateLatencies(groupCandidates, outbounds, trigger)
    }
    val settings = SettingsRepository.getInstance(this@measureAutoFailoverCandidateLatencies).settings.first()
    val concurrency = SingBoxService.resolveAutoFailoverCandidateConcurrency(
        trigger = trigger,
        userConcurrency = settings.latencyTestConcurrency,
        candidateCount = groupCandidates.size
    )
    val timeoutMs = SingBoxService.resolveAutoFailoverCandidateTimeoutMs(
        trigger = trigger,
        userTimeoutMs = settings.latencyTestTimeout
    )
    val semaphore = Semaphore(concurrency)
    val core = SingBoxCore.getInstance(this@measureAutoFailoverCandidateLatencies)
    val results = ConcurrentHashMap<String, Int>()
    groupCandidates.map { outbound ->
        async(Dispatchers.IO) {
            semaphore.withPermit {
                val latency = runCatching {
                    core.testOutboundLatency(
                        outbound = outbound,
                        allOutbounds = outbounds,
                        timeoutOverrideMs = timeoutMs,
                        trafficKind = if (trigger.isBlank()) {
                            LatencyProbeTrafficKind.BACKGROUND_PROBE
                        } else {
                            LatencyProbeTrafficKind.HEALTH_CHECK
                        }
                    )
                }.getOrDefault(-1L)
                if (latency > 0L && latency <= Int.MAX_VALUE) {
                    results[outbound.tag] = latency.toInt()
                }
            }
        }
    }.awaitAll()
    results.toMap()
}

internal suspend fun SingBoxService.measureFastAutoFailoverCandidateLatencies(
    groupCandidates: List<Outbound>,
    outbounds: List<Outbound>,
    trigger: String
): Map<String, Int> {
    val settings = SettingsRepository.getInstance(this@measureFastAutoFailoverCandidateLatencies).settings.first()
    val concurrency = SingBoxService.resolveAutoFailoverCandidateConcurrency(
        trigger = trigger,
        userConcurrency = settings.latencyTestConcurrency,
        candidateCount = groupCandidates.size
    )
    val timeoutMs = SingBoxService.resolveAutoFailoverCandidateTimeoutMs(
        trigger = trigger,
        userTimeoutMs = settings.latencyTestTimeout
    )
    val results = ConcurrentHashMap<String, Int>()
    SingBoxCore.getInstance(this@measureFastAutoFailoverCandidateLatencies).testOutboundsLatency(
        outbounds = groupCandidates,
        allOutbounds = outbounds,
        timeoutOverrideMs = timeoutMs,
        concurrencyOverride = concurrency,
        portReadyTimeoutOverrideMs = SingBoxService.resolveAutoFailoverPortReadyTimeoutMs(trigger),
        trafficKind = if (trigger.isBlank()) {
            LatencyProbeTrafficKind.BACKGROUND_PROBE
        } else {
            LatencyProbeTrafficKind.HEALTH_CHECK
        }
    ) { tag, latency ->
        if (latency > 0L && latency <= Int.MAX_VALUE) {
            results[tag] = latency.toInt()
        }
    }
    return results.toMap()
}

internal fun SingBoxService.updateAutoFailoverCandidateCache(
    currentTag: String?,
    resultMap: Map<String, Int>,
    quarantinedTags: Set<String>
) {
    if (!currentTag.isNullOrBlank() && resultMap.isNotEmpty()) {
        autoFailoverCandidateCache.update(
            currentTag = currentTag,
            delays = resultMap,
            nowMs = System.currentTimeMillis(),
            quarantinedTags = quarantinedTags
        )
    }
}

internal fun SingBoxService.loadLastRunningConfig(): SingBoxConfig? {
    val configPath = SingBoxService.lastConfigPath ?: File(filesDir, "running_config.json").absolutePath
    return runCatching {
        val configContent = File(configPath).readText()
        gson.fromJson(configContent, SingBoxConfig::class.java)
    }.onFailure { e ->
        Log.w(SingBoxService.TAG, "[AutoFailover] failed to load running config for latency test: ${e.message}")
    }.getOrNull()
}

internal suspend fun SingBoxService.probeAutoFailoverTargetLatency(targetTag: String): Long? {
    val config = loadLastRunningConfig() ?: return null
    val outbounds = config.outbounds.orEmpty()
    val target = outbounds.firstOrNull {
        UrlTestTagMatcher.normalizeTag(it.tag) == UrlTestTagMatcher.normalizeTag(targetTag)
    } ?: return null
    return runCatching {
        SingBoxCore.getInstance(this@probeAutoFailoverTargetLatency).testOutboundLatency(
            outbound = target,
            allOutbounds = outbounds,
            dnsConfig = config.dns,
            timeoutOverrideMs = SingBoxService.HEALTH_FAST_FAILOVER_CANDIDATE_TIMEOUT_MS,
            trafficKind = LatencyProbeTrafficKind.HEALTH_CHECK
        ).takeIf { it > 0L }
    }.onFailure { error ->
        Log.w(SingBoxService.TAG, "[AutoFailover] target HTTPS probe failed: $targetTag", error)
    }.getOrNull()
}

internal suspend fun SingBoxService.verifyAutoFailoverTargetConnectivity(targetTag: String): Boolean {
    return probeAutoFailoverTargetLatency(targetTag) != null
}

internal fun SingBoxService.handleAutoGroupSelectionChanged(groupTag: String, selectedTag: String) {
    val autoTag = activeAutoGroupTag ?: return
    if (!autoFailoverOverrideActive || groupTag != autoTag) return
    if (!autoGroupRestoreInFlight.compareAndSet(false, true)) return
    serviceScope.launch {
        try {
            val profileId = VpnStateStore.getSelectedProfileId()
            val repository = ConfigRepository.getInstance(this@handleAutoGroupSelectionChanged)
            if (!repository.isProfileAutoSelectionEnabled(profileId)) {
                autoFailoverOverrideActive = false
                activeAutoGroupTag = null
                return@launch
            }
            if (!verifyAutoFailoverTargetConnectivity(selectedTag)) return@launch
            if (hotSwitchNode(autoTag)) {
                autoFailoverOverrideActive = false
                LogRepository.getInstance().addLog(
                    "INFO: Auto failover returned control to automatic group=$autoTag node=$selectedTag"
                )
            }
        } finally {
            autoGroupRestoreInFlight.set(false)
        }
    }
}

internal fun SingBoxService.resolveActiveAutoGroupTag(): String? {
    val outbounds = loadLastRunningConfig()?.outbounds.orEmpty()
    val byTag = outbounds.associateBy { it.tag }
    return byTag["PROXY"]?.outbounds.orEmpty()
        .asSequence()
        .mapNotNull(byTag::get)
        .firstOrNull { it.type.equals("urltest", ignoreCase = true) }
        ?.tag
}

@Suppress("LongMethod")
internal suspend fun SingBoxService.performAutoFailoverSwitch(
    currentTag: String,
    targetTag: String,
    trigger: String
) {
    val now = System.currentTimeMillis()
    val startedAtMs = SystemClock.elapsedRealtime()
    val currentQuarantine = loadActiveAutoFailoverQuarantine(now).toMutableList()
    currentQuarantine.add(NodeAutoFailoverPolicy.createQuarantineRecord(currentTag, now))
    val cleanedQuarantine = NodeAutoFailoverPolicy.cleanupExpiredQuarantine(currentQuarantine, now)
    val budgetState = NodeAutoFailoverPolicy.registerFailoverAttempt(
        windowStartAtMs = VpnStateStore.getAutoFailoverWindowStartAtMs(),
        count = VpnStateStore.getAutoFailoverCountInWindow(),
        nowAtMs = now
    )

    VpnStateStore.setLastAutoFailoverAtMs(now)
    VpnStateStore.setAutoFailoverWindowStartAtMs(budgetState.windowStartAtMs)
    VpnStateStore.setAutoFailoverCountInWindow(budgetState.count)
    VpnStateStore.setAutoFailoverQuarantinedTags(NodeAutoFailoverPolicy.encodeQuarantine(cleanedQuarantine))
    VpnStateStore.setLastAutoFailoverNodeTag(currentTag)

    LogRepository.getInstance().addLog(
        "INFO: Auto failover screened from=$currentTag to=$targetTag trigger=$trigger"
    )

    val success = hotSwitchNode(targetTag)
    if (!success) {
        PerfTracer.recordDuration(
            PerfTracer.Phases.AUTO_FAILOVER,
            SystemClock.elapsedRealtime() - startedAtMs,
            "hot_switch_failed"
        )
        LogRepository.getInstance().addLog(
            "WARN: Auto failover escalate restart reason=hot_switch_failed target=$targetTag"
        )
        restartVpnForAutoFailoverRecovery(targetTag)
        return
    }

    // L2/L3：收敛连接 + 重置网络栈，缩小与冷启动恢复能力的差距
    val closed = commandManager.closeConnections()
    LogRepository.getInstance().addLog(
        "INFO: Health failover converged connections, trigger=$trigger, closed=$closed"
    )
    val reset = BoxWrapperManager.resetNetwork()
    LogRepository.getInstance().addLog(
        "INFO: Auto failover escalate resetNetwork result=$reset trigger=$trigger"
    )

    // live 终验：只看选中正确 + 观察窗远程 DNS，不再依赖离线延迟
    healthSignalAggregator.clearDnsFailures()
    delay(SingBoxService.AUTO_FAILOVER_LIVE_OBSERVE_MS)
    val selectedTag = resolveCurrentProxyOutboundTag()
    val targetProbeSucceeded = verifyAutoFailoverTargetConnectivity(targetTag)
    val recentDnsFailures = healthSignalAggregator.recentRemoteDnsFailureCount(
        nowMs = SystemClock.elapsedRealtime(),
        windowMs = SingBoxService.AUTO_FAILOVER_LIVE_OBSERVE_MS
    )
    val failReason = evaluateAutoFailoverLiveCheck(
        targetTag = targetTag,
        selectedTag = selectedTag,
        targetProbeSucceeded = targetProbeSucceeded,
        recentRemoteDnsFailures = recentDnsFailures
    )
    if (failReason != null) {
        autoFailoverOverrideActive = false
        activeAutoGroupTag = null
        quarantineAutoFailoverNode(targetTag)
        val rolledBack = hotSwitchNode(currentTag)
        commandManager.closeConnections()
        BoxWrapperManager.resetNetwork()
        val failureLog = "WARN: Auto failover liveCheck FAIL node=$targetTag reason=$failReason " +
            "targetProbe=$targetProbeSucceeded dnsFails=$recentDnsFailures " +
            "selected=${selectedTag ?: "(none)"} rollback=${if (rolledBack) "ok" else "failed"}"
        LogRepository.getInstance().addLog(failureLog)
        if (!rolledBack) {
            LogRepository.getInstance().addLog(
                "WARN: Auto failover escalate restart reason=rollback_failed from=$currentTag"
            )
            restartVpnForAutoFailoverRecovery(currentTag)
        }
        PerfTracer.recordDuration(
            PerfTracer.Phases.AUTO_FAILOVER,
            SystemClock.elapsedRealtime() - startedAtMs,
            if (rolledBack) "live_check_failed" else "rollback_failed"
        )
        return
    }

    val configRepository = ConfigRepository.getInstance(this@performAutoFailoverSwitch)
    val displayName = resolveRuntimeNodeLabel(
        targetTag,
        NodeProtectionStore.runtimeMappings()
    ) ?: configRepository.getNodeByName(targetTag)?.name ?: targetTag
    // 自动切换只更新运行态标签，不改用户手选偏好
    VpnStateStore.setActiveLabel(displayName)
    realTimeNodeName = displayName
    requestNotificationUpdate(force = true)
    requestRemoteStateUpdate(force = true)
    activeAutoGroupTag = resolveActiveAutoGroupTag()
    autoFailoverOverrideActive = activeAutoGroupTag != null
    val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
    PerfTracer.recordDuration(
        PerfTracer.Phases.AUTO_FAILOVER,
        elapsedMs,
        "success"
    )
    LogRepository.getInstance().addLog(
        "INFO: Auto failover committed $currentTag -> $displayName trigger=$trigger " +
            "elapsed=${elapsedMs}ms dnsFails=$recentDnsFailures"
    )
    Log.i(SingBoxService.TAG, "[AutoFailover] switched from $currentTag to $displayName, trigger=$trigger")
}

internal fun SingBoxService.quarantineAutoFailoverNode(tag: String) {
    val now = System.currentTimeMillis()
    val current = loadActiveAutoFailoverQuarantine(now).toMutableList()
    current.add(NodeAutoFailoverPolicy.createQuarantineRecord(tag, now))
    val cleaned = NodeAutoFailoverPolicy.cleanupExpiredQuarantine(current, now)
    VpnStateStore.setAutoFailoverQuarantinedTags(NodeAutoFailoverPolicy.encodeQuarantine(cleaned))
}

internal fun SingBoxService.restartVpnForAutoFailoverRecovery(preferredTag: String?) {
    val configPath = pendingHotSwitchFallbackConfigPath ?: File(filesDir, "running_config.json").absolutePath
    val restartIntent = Intent(this@restartVpnForAutoFailoverRecovery, SingBoxService::class.java).apply {
        action = SingBoxService.ACTION_START
        putExtra(SingBoxService.EXTRA_CONFIG_PATH, configPath)
        preferredTag?.takeIf { it.isNotBlank() }?.let {
            putExtra(SingBoxService.EXTRA_PENDING_NODE_NAME, it)
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(restartIntent)
    } else {
        startService(restartIntent)
    }
}

internal fun SingBoxService.loadActiveAutoFailoverQuarantine(nowAtMs: Long): List<NodeAutoFailoverPolicy.QuarantinedNode> {
    val records = NodeAutoFailoverPolicy.decodeQuarantine(VpnStateStore.getAutoFailoverQuarantinedTags())
    val cleaned = NodeAutoFailoverPolicy.cleanupExpiredQuarantine(records, nowAtMs)
    if (cleaned.size != records.size) {
        VpnStateStore.setAutoFailoverQuarantinedTags(NodeAutoFailoverPolicy.encodeQuarantine(cleaned))
    }
    return cleaned
}

internal fun SingBoxService.resolveCurrentProxyOutboundTag(): String? {
    return commandManager.getResolvedSelectedOutbound("PROXY")
        ?.takeIf { it.isNotBlank() }
        ?: SelectorManager.getSelectedOutbound()
            ?.takeIf { tag ->
                tag.isNotBlank() && ConfigRepository.getInstance(applicationContext)
                    .resolveNodeNameFromOutboundTag(tag) != null
            }
}

// 屏幕/前台状态从 ScreenStateManager 读取

internal fun SingBoxService.getCurrentPhysicalNetwork(): Network? {
    return StateCache.getNetwork {
        connectivityManager?.let { DefaultNetworkListener.selectBestPhysicalNetwork(it) }
    }
}

internal fun SingBoxService.markPhysicalNetworkChanged() {
    StateCache.invalidateNetworkCache()
}

internal fun SingBoxService.findBestPhysicalNetwork(): Network? {
    return getCurrentPhysicalNetwork()
}
