package com.kunk.singbox.service.manager

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import io.nekohasekai.libbox.CommandClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class RouteGroupSelector(
    private val context: Context,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "RouteGroupSelector"
        private const val ROUTE_GROUP_AUTO_TAG_SUFFIX = "#AUTO"
        internal const val INITIAL_AUTO_SELECT_DELAY_MS = 0L
        internal const val AUTO_SELECT_INTERVAL_MS = 30L * 60L * 1000L
        private const val LATENCY_TEST_TIMEOUT_MS = 10000L
        private const val MAX_CONCURRENT_TESTS = 4
        internal const val IMMEDIATE_RESELECT_DEBOUNCE_MS = 1500L

        internal data class RouteGroupTarget(
            val groupTag: String,
            val candidates: List<String>,
            val fallbackTag: String?,
            val autoGroupTag: String?
        )

        internal fun isRuntimeAutoGroupTag(tag: String?): Boolean {
            val normalizedTag = tag?.trim().orEmpty()
            return normalizedTag.startsWith("P:") && normalizedTag.endsWith(ROUTE_GROUP_AUTO_TAG_SUFFIX)
        }

        internal fun collectRouteGroupTargets(config: SingBoxConfig): List<RouteGroupTarget> {
            val referencedOutbounds = config.route?.rules
                .orEmpty()
                .mapNotNull { it.outbound?.trim() }
                .filter { it.isNotBlank() }
                .toSet()

            if (referencedOutbounds.isEmpty()) {
                return emptyList()
            }

            val outboundsByTag = config.outbounds.orEmpty().associateBy { it.tag }

            return config.outbounds
                .orEmpty()
                .asSequence()
                .filter { it.type == "selector" }
                .filter { referencedOutbounds.contains(it.tag) }
                .filterNot { it.tag.equals("PROXY", ignoreCase = true) }
                .mapNotNull { selector ->
                    val autoGroupTag = selector.outbounds
                        .orEmpty()
                        .map { it.trim() }
                        .firstOrNull { ref ->
                            isRuntimeAutoGroupTag(ref) &&
                                outboundsByTag[ref]?.type in listOf("urltest", "url-test")
                        }
                    val candidateSource = autoGroupTag?.let { outboundsByTag[it] } ?: selector
                    val candidates = candidateSource.outbounds
                        .orEmpty()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .filterNot {
                            it.equals("direct", ignoreCase = true) ||
                                it.equals("block", ignoreCase = true) ||
                                it.equals("PROXY", ignoreCase = true)
                        }
                        .distinct()
                    val fallbackTag = selector.outbounds
                        .orEmpty()
                        .firstOrNull { it.equals("PROXY", ignoreCase = true) }
                    if (candidates.isEmpty()) {
                        null
                    } else {
                        RouteGroupTarget(
                            groupTag = selector.tag,
                            candidates = candidates,
                            fallbackTag = fallbackTag,
                            autoGroupTag = autoGroupTag
                        )
                    }
                }
                .toList()
        }

        internal fun resolveCandidateTag(
            selectedTag: String?,
            candidates: Collection<String>
        ): String? {
            val normalizedSelected = UrlTestTagMatcher.normalizeTag(selectedTag.orEmpty())
            if (normalizedSelected.isBlank()) {
                return null
            }
            return candidates.firstOrNull { candidate ->
                UrlTestTagMatcher.normalizeTag(candidate) == normalizedSelected
            }
        }

        internal fun shouldNotifyFallback(
            currentSelected: String?,
            fallbackTag: String,
            switchSucceeded: Boolean,
            wasFallbackActive: Boolean
        ): Boolean {
            val isUsingFallback = currentSelected == fallbackTag || switchSucceeded
            return isUsingFallback && !wasFallbackActive
        }

        internal fun computeImmediateReselectDelayMs(
            lastRequestedAtMs: Long,
            nowAtMs: Long,
            debounceMs: Long = IMMEDIATE_RESELECT_DEBOUNCE_MS
        ): Long {
            if (lastRequestedAtMs <= 0L) {
                return 0L
            }
            return (debounceMs - (nowAtMs - lastRequestedAtMs)).coerceAtLeast(0L)
        }

        internal fun extractImmediateReselectReason(trigger: String): String? {
            val normalized = trigger.trim()
            if (!normalized.startsWith("immediate:", ignoreCase = true)) {
                return null
            }
            return normalized.substringAfter(':').trim().takeIf { it.isNotBlank() }
        }

        internal fun isNetworkImmediateReselectTrigger(trigger: String): Boolean {
            val reason = extractImmediateReselectReason(trigger)?.lowercase() ?: return false
            return reason.contains("network_type_changed") ||
                reason.contains("typechange") ||
                reason.contains("network_validated")
        }

        internal fun shouldTriggerConnectionConvergence(
            trigger: String,
            previousSelectedTag: String?,
            newSelectedTag: String?
        ): Boolean {
            val previous = previousSelectedTag?.trim().orEmpty()
            val current = newSelectedTag?.trim().orEmpty()
            if (previous.isBlank() || current.isBlank()) {
                return false
            }
            if (previous == current) {
                return false
            }
            return isNetworkImmediateReselectTrigger(trigger)
        }

        internal fun shouldSwitchToTag(
            currentSelectedTag: String?,
            targetTag: String
        ): Boolean {
            return resolveCandidateTag(currentSelectedTag, listOf(targetTag)) != targetTag
        }

        internal fun selectorTagForCandidateRpc(target: RouteGroupTarget): String {
            return target.autoGroupTag?.takeIf { it.isNotBlank() } ?: target.groupTag
        }

        internal fun selectorTagForFallbackRpc(target: RouteGroupTarget): String {
            return target.groupTag
        }

        internal fun resolveCurrentCandidateSelection(
            groupSelectedTag: String?,
            autoGroupSelectedTag: String?,
            candidates: Collection<String>
        ): String? {
            val autoResolved = autoGroupSelectedTag
                ?.takeIf { it.isNotBlank() }
                ?.let { resolveCandidateTag(it, candidates) ?: it }
            if (!autoResolved.isNullOrBlank()) {
                return autoResolved
            }
            return resolveCandidateTag(groupSelectedTag, candidates)
        }
    }

    private val gson = Gson()
    private var autoSelectJob: Job? = null
    private var immediateSelectJob: Job? = null
    private val fallbackActiveGroups = ConcurrentHashMap.newKeySet<String>()
    private val selectionMutex = Mutex()
    private val immediateReselectLock = Any()

    @Volatile
    private var latestConfigContent: String? = null

    @Volatile
    private var pendingImmediateReselectReason: String? = null

    @Volatile
    private var lastImmediateReselectRequestedAtMs: Long = 0L

    private data class SelectionContext(
        val byTag: Map<String, Outbound>,
        val outbounds: List<Outbound>,
        val core: SingBoxCore,
        val semaphore: Semaphore
    )

    interface Callbacks {
        val isRunning: Boolean
        val isStopping: Boolean
        fun getCommandClient(): CommandClient?
        fun getSelectedOutbound(groupTag: String): String?
        fun onRouteGroupFallback(groupTag: String, actualSelectedTag: String?)
        fun onRouteGroupImmediateSwitch(
            groupTag: String,
            previousSelectedTag: String,
            newSelectedTag: String,
            reason: String
        )
    }

    private var callbacks: Callbacks? = null

    fun init(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    fun start(configContent: String) {
        stop()
        latestConfigContent = configContent

        autoSelectJob = serviceScope.launch {
            runAutoSelectLoop(configContent)
        }
    }

    fun requestImmediateReselect(reason: String) {
        if (!canRunImmediateReselect(reason)) {
            return
        }
        val waitMs = enqueueImmediateReselect(reason)
        if (waitMs == null) {
            return
        }
        immediateSelectJob = serviceScope.launch {
            runImmediateReselectLoop(waitMs)
        }
    }

    fun stop() {
        autoSelectJob?.cancel()
        autoSelectJob = null
        immediateSelectJob?.cancel()
        immediateSelectJob = null
        latestConfigContent = null
        synchronized(immediateReselectLock) {
            pendingImmediateReselectReason = null
            lastImmediateReselectRequestedAtMs = 0L
        }
        fallbackActiveGroups.clear()
    }

    private suspend fun runSelection(configContent: String, trigger: String): Boolean {
        val targets = runCatching {
            gson.fromJson(configContent, SingBoxConfig::class.java)
                ?.let { collectRouteGroupTargets(it) }
                .orEmpty()
        }.getOrDefault(emptyList())

        if (targets.isEmpty()) {
            Log.d(TAG, "No route-linked selector groups found for $trigger, stop loop")
            return false
        }

        selectionMutex.withLock {
            runCatching {
                selectBestForRouteGroups(
                    configContent = configContent,
                    targetGroups = targets,
                    trigger = trigger
                )
            }.onFailure { e ->
                Log.w(TAG, "Route group auto-select failed for $trigger: ${e.message}", e)
            }
        }
        return true
    }

    private suspend fun runAutoSelectLoop(startupConfigContent: String) {
        Log.i(TAG, "Route group auto-select scheduled, initialDelay=${INITIAL_AUTO_SELECT_DELAY_MS}ms")
        if (INITIAL_AUTO_SELECT_DELAY_MS > 0) {
            delay(INITIAL_AUTO_SELECT_DELAY_MS)
        }

        val started = runSelection(configContent = startupConfigContent, trigger = "startup")
        if (!started) {
            logAutoSelectLoopStopped()
            return
        }

        while (callbacks?.isRunning == true && callbacks?.isStopping != true) {
            Log.d(TAG, "Next route group auto-select in ${AUTO_SELECT_INTERVAL_MS / 60000} minutes")
            delay(AUTO_SELECT_INTERVAL_MS)

            val latestConfig = latestConfigContent
            if (latestConfig == null) {
                logAutoSelectLoopStopped()
                return
            }
            val continued = runSelection(configContent = latestConfig, trigger = "periodic")
            if (!continued) {
                logAutoSelectLoopStopped()
                return
            }
        }
        logAutoSelectLoopStopped()
    }

    private fun logAutoSelectLoopStopped() {
        Log.i(TAG, "Route group auto-select loop stopped")
    }

    private fun canRunImmediateReselect(reason: String): Boolean {
        if (latestConfigContent.isNullOrBlank()) {
            Log.d(TAG, "Skip immediate route-group reselect: config not ready, reason=$reason")
            return false
        }
        if (callbacks?.isRunning != true || callbacks?.isStopping == true) {
            Log.d(TAG, "Skip immediate route-group reselect: service not running, reason=$reason")
            return false
        }
        return true
    }

    private fun enqueueImmediateReselect(reason: String): Long? {
        synchronized(immediateReselectLock) {
            pendingImmediateReselectReason = reason
            val now = SystemClock.elapsedRealtime()
            val waitMs = computeImmediateReselectDelayMs(lastImmediateReselectRequestedAtMs, now)
            lastImmediateReselectRequestedAtMs = now

            if (immediateSelectJob?.isActive == true) {
                Log.d(TAG, "Immediate route-group reselect merged, reason=$reason")
                return null
            }
            return waitMs
        }
    }

    private suspend fun runImmediateReselectLoop(initialDelayMs: Long) {
        try {
            var nextDelayMs = initialDelayMs
            while (callbacks?.isRunning == true && callbacks?.isStopping != true) {
                if (nextDelayMs > 0) {
                    delay(nextDelayMs)
                }

                val triggerReason = pollImmediateReselectReason() ?: return
                val latest = latestConfigContent
                if (latest.isNullOrBlank()) {
                    Log.d(TAG, "Skip immediate route-group reselect: latest config missing")
                    return
                }

                runSelection(configContent = latest, trigger = "immediate:$triggerReason")

                val scheduledDelay = resolvePendingImmediateReselectDelay() ?: return
                nextDelayMs = scheduledDelay
            }
        } finally {
            synchronized(immediateReselectLock) {
                immediateSelectJob = null
            }
        }
    }

    private fun pollImmediateReselectReason(): String? {
        return synchronized(immediateReselectLock) {
            pendingImmediateReselectReason.also {
                pendingImmediateReselectReason = null
            }
        }
    }

    private fun resolvePendingImmediateReselectDelay(): Long? {
        return synchronized(immediateReselectLock) {
            pendingImmediateReselectReason?.let {
                computeImmediateReselectDelayMs(
                    lastRequestedAtMs = lastImmediateReselectRequestedAtMs,
                    nowAtMs = SystemClock.elapsedRealtime()
                )
            }
        }
    }

    private suspend fun selectBestForRouteGroups(
        configContent: String,
        targetGroups: List<RouteGroupTarget>,
        trigger: String
    ) {
        val config = runCatching {
            gson.fromJson(configContent, SingBoxConfig::class.java)
        }.getOrNull() ?: return

        val outbounds = config.outbounds.orEmpty()
        val byTag = outbounds.associateBy { it.tag }

        val client = waitForCommandClient(LATENCY_TEST_TIMEOUT_MS) ?: return
        val selectionContext = SelectionContext(
            byTag = byTag,
            outbounds = outbounds,
            core = SingBoxCore.getInstance(context),
            semaphore = Semaphore(permits = MAX_CONCURRENT_TESTS)
        )

        Log.i(TAG, "Route group auto-select running for ${targetGroups.size} groups, trigger=$trigger")

        for (target in targetGroups) {
            if (callbacks?.isRunning != true || callbacks?.isStopping == true) {
                return
            }
            processTargetGroup(
                client = client,
                target = target,
                selectionContext = selectionContext,
                trigger = trigger
            )
        }
    }

    private fun currentCandidateSelection(
        target: RouteGroupTarget,
        groupSelectedTag: String?,
        autoGroupSelectedTag: String?
    ): String? {
        return resolveCurrentCandidateSelection(
            groupSelectedTag = groupSelectedTag,
            autoGroupSelectedTag = autoGroupSelectedTag,
            candidates = target.candidates
        )
    }

    private fun logTargetGroupStart(
        target: RouteGroupTarget,
        initialSelected: String?,
        initialGroupSelected: String?
    ) {
        Log.i(
            TAG,
            "Testing route group '${target.groupTag}', " +
                "current='${initialSelected ?: initialGroupSelected ?: "(none)"}', " +
                "candidates=${target.candidates.size}"
        )
    }

    private suspend fun processTargetGroup(
        client: CommandClient,
        target: RouteGroupTarget,
        selectionContext: SelectionContext,
        trigger: String
    ) {
        val initialGroupSelected = callbacks?.getSelectedOutbound(target.groupTag)
        val initialAutoSelected = target.autoGroupTag?.let { callbacks?.getSelectedOutbound(it) }
        val initialSelected = currentCandidateSelection(target, initialGroupSelected, initialAutoSelected)
        logTargetGroupStart(target, initialSelected, initialGroupSelected)
        val best = findBestCandidate(
            target = target,
            selectionContext = selectionContext
        )
        if (best == null) {
            handleFallbackSelection(
                client = client,
                target = target,
                currentSelected = callbacks?.getSelectedOutbound(target.groupTag) ?: initialGroupSelected,
                trigger = trigger
            )
            return
        }
        val (bestTag, bestDelayMs) = best
        Log.i(TAG, "Best node for '${target.groupTag}' is '$bestTag' (${bestDelayMs}ms)")
        val currentSelected = currentCandidateSelection(
            target = target,
            groupSelectedTag = callbacks?.getSelectedOutbound(target.groupTag) ?: initialGroupSelected,
            autoGroupSelectedTag = target.autoGroupTag
                ?.let { callbacks?.getSelectedOutbound(it) }
                ?: initialAutoSelected
        ) ?: initialSelected
        val desiredSelectedTag = bestTag

        if (!shouldSwitchToTag(currentSelected, desiredSelectedTag)) {
            fallbackActiveGroups.remove(target.groupTag)
            Log.d(TAG, "Group '${target.groupTag}' already uses preferred target '$desiredSelectedTag'")
            return
        }

        val switched = switchToBestCandidate(
            client = client,
            target = target,
            currentSelected = currentSelected,
            bestTag = desiredSelectedTag
        )
        if (switched) {
            fallbackActiveGroups.remove(target.groupTag)
            val selectedAfterSwitch = currentCandidateSelection(
                target = target,
                groupSelectedTag = callbacks?.getSelectedOutbound(target.groupTag),
                autoGroupSelectedTag = target.autoGroupTag?.let { callbacks?.getSelectedOutbound(it) }
            ) ?: desiredSelectedTag
            notifyImmediateSwitchIfNeeded(
                trigger = trigger,
                groupTag = target.groupTag,
                previousSelectedTag = currentSelected,
                newSelectedTag = selectedAfterSwitch
            )
        }
    }

    private fun handleFallbackSelection(
        client: CommandClient,
        target: RouteGroupTarget,
        currentSelected: String?,
        trigger: String
    ) {
        val fallbackTag = target.fallbackTag
        if (fallbackTag.isNullOrBlank()) {
            Log.w(
                TAG,
                "No latency result available for group '${target.groupTag}' and no fallback tag configured"
            )
            return
        }

        val wasFallbackActive = fallbackActiveGroups.contains(target.groupTag)
        val switchSucceeded = if (currentSelected == fallbackTag) {
            Log.d(TAG, "Group '${target.groupTag}' already uses fallback '$fallbackTag'")
            true
        } else {
            switchSelectedOutbound(
                client = client,
                selectorTag = selectorTagForFallbackRpc(target),
                currentSelected = currentSelected,
                bestTag = fallbackTag
            )
        }
        if (!switchSucceeded) {
            return
        }

        fallbackActiveGroups.add(target.groupTag)
        val selectedAfterSwitch = callbacks?.getSelectedOutbound(target.groupTag) ?: fallbackTag
        notifyImmediateSwitchIfNeeded(
            trigger = trigger,
            groupTag = target.groupTag,
            previousSelectedTag = currentSelected,
            newSelectedTag = selectedAfterSwitch
        )
        if (shouldNotifyFallback(currentSelected, fallbackTag, switchSucceeded, wasFallbackActive)) {
            callbacks?.onRouteGroupFallback(
                groupTag = target.groupTag,
                actualSelectedTag = callbacks?.getSelectedOutbound(fallbackTag)
            )
        }
    }

    private fun notifyImmediateSwitchIfNeeded(
        trigger: String,
        groupTag: String,
        previousSelectedTag: String?,
        newSelectedTag: String?
    ) {
        if (!shouldTriggerConnectionConvergence(trigger, previousSelectedTag, newSelectedTag)) {
            return
        }
        val reason = extractImmediateReselectReason(trigger) ?: return
        val previous = previousSelectedTag?.trim() ?: return
        val new = newSelectedTag?.trim() ?: return
        callbacks?.onRouteGroupImmediateSwitch(
            groupTag = groupTag,
            previousSelectedTag = previous,
            newSelectedTag = new,
            reason = reason
        )
    }

    private suspend fun findBestCandidate(
        target: RouteGroupTarget,
        selectionContext: SelectionContext
    ): Pair<String, Long>? {
        return fallbackBestCandidate(
            candidates = target.candidates,
            byTag = selectionContext.byTag,
            outbounds = selectionContext.outbounds,
            core = selectionContext.core,
            semaphore = selectionContext.semaphore
        )
    }

    private fun switchToBestCandidate(
        client: CommandClient,
        target: RouteGroupTarget,
        currentSelected: String?,
        bestTag: String
    ): Boolean {
        val autoGroupTag = target.autoGroupTag?.takeIf { it.isNotBlank() }
        if (autoGroupTag != null) {
            val currentRouteSelection = callbacks?.getSelectedOutbound(target.groupTag)
            if (
                !currentRouteSelection.isNullOrBlank() &&
                !currentRouteSelection.equals(autoGroupTag, ignoreCase = true)
            ) {
                val restored = switchSelectedOutbound(
                    client = client,
                    selectorTag = selectorTagForFallbackRpc(target),
                    currentSelected = currentRouteSelection,
                    bestTag = autoGroupTag
                )
                if (!restored) {
                    return false
                }
            }
        }

        return switchSelectedOutbound(
            client = client,
            selectorTag = selectorTagForCandidateRpc(target),
            currentSelected = currentSelected,
            bestTag = bestTag
        )
    }

    private fun switchSelectedOutbound(
        client: CommandClient,
        selectorTag: String,
        currentSelected: String?,
        bestTag: String
    ): Boolean {
        return runCatching {
            client.selectOutbound(selectorTag, bestTag)
            Log.i(TAG, "Route group '$selectorTag' switched from '${currentSelected ?: "(none)"}' to '$bestTag'")
            true
        }.onFailure { e ->
            Log.w(TAG, "Failed to switch group '$selectorTag' to '$bestTag': ${e.message}", e)
        }.getOrDefault(false)
    }

    private suspend fun fallbackBestCandidate(
        candidates: List<String>,
        byTag: Map<String, Outbound>,
        outbounds: List<Outbound>,
        core: SingBoxCore,
        semaphore: Semaphore
    ): Pair<String, Long>? {
        val results = ConcurrentHashMap<String, Long>()

        coroutineScope {
            candidates.map { tag ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        val outbound = byTag[tag] ?: return@async
                        val rtt = try {
                            core.testOutboundLatency(outbound, outbounds)
                        } catch (_: Exception) {
                            -1L
                        }
                        if (rtt >= 0) {
                            results[tag] = rtt
                        }
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()
        }

        return results.entries.minByOrNull { it.value }?.let { it.key to it.value }
    }

    private suspend fun waitForCommandClient(timeoutMs: Long): CommandClient? {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            val client = callbacks?.getCommandClient()
            if (client != null) {
                return client
            }
            delay(120)
        }
        return callbacks?.getCommandClient()
    }

    fun cleanup() {
        stop()
        callbacks = null
    }
}
