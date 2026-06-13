package com.kunk.singbox.service

import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.core.ProbeManager
import com.kunk.singbox.core.SelectorManager
import io.nekohasekai.libbox.*
import kotlinx.coroutines.*
import java.io.File

@Suppress("TooManyFunctions")
abstract class SingBoxServicePart2 : SingBoxServicePart1() {
    protected override fun submitRecoveryRequest(request: RecoveryRequest) {
        val invalidState = recoveryInvalidStateSummary()
        if (invalidState != null) {
            logRecoveryEvent(
                event = "skipped_invalid_state",
                request = request,
                mode = null,
                merged = request.merged,
                skipped = true,
                outcome = "invalid_state($invalidState)"
            )
            return
        }

        synchronized(this) {
            // 2025-fix-v7: APP_FOREGROUND + force 走快车道，不进合并窗口
            // 直接 wake + resetNetwork，跳过 800ms 合并等待和多级探测
            if (SingBoxService.shouldUseForegroundFastLane(request) && !recoveryInFlight) {
                recoveryInFlight = true
                serviceScope.launch {
                    try {
                        executeForegroundFastRecovery(request)
                    } finally {
                        val nextRequest = synchronized(this@SingBoxServicePart2) {
                            recoveryInFlight = false
                            val next = pendingRecoveryRequest
                            pendingRecoveryRequest = null
                            next
                        }
                        if (nextRequest != null) {
                            executeRecoveryRequest(nextRequest)
                        }
                    }
                }
                return
            }

            if (recoveryInFlight) {
                val current = pendingRecoveryRequest
                pendingRecoveryRequest = if (current == null) {
                    request.copy(merged = true)
                } else {
                    mergeRecoveryRequests(current, request)
                }
                recoveryMergedCount.incrementAndGet()
                logRecoveryEvent(
                    event = "merged_inflight",
                    request = request,
                    mode = null,
                    merged = true,
                    skipped = false,
                    outcome = null
                )
                return
            }

            val existingMerge = pendingMergeRequest
            pendingMergeRequest = if (existingMerge == null) {
                request
            } else {
                mergeRecoveryRequests(existingMerge, request)
            }

            val hadExisting = existingMerge != null
            if (hadExisting) {
                recoveryMergedCount.incrementAndGet()
                logRecoveryEvent(
                    event = "merged_window",
                    request = request,
                    mode = null,
                    merged = true,
                    skipped = false,
                    outcome = null
                )
            }

            if (recoveryMergeJob?.isActive != true) {
                recoveryMergeJob = serviceScope.launch {
                    delay(recoveryMergeWindowMs)
                    val toRun = synchronized(this@SingBoxServicePart2) {
                        val r = pendingMergeRequest
                        pendingMergeRequest = null
                        r
                    }
                    if (toRun != null) {
                        executeRecoveryRequest(toRun)
                    }
                }
            }
        }
    }

    protected override fun mergeRecoveryRequests(
        existing: RecoveryRequest,
        incoming: RecoveryRequest
    ): RecoveryRequest {
        val winning = SingBoxService.chooseHigherPriorityRecovery(existing, incoming)
        return if (winning.merged) winning else winning.copy(merged = true)
    }

    protected override fun cancelPendingRecoveryWork() {
        recoveryMergeJob?.cancel()
        recoveryMergeJob = null
        pendingMergeRequest = null
        pendingRecoveryRequest = null

        foregroundHardFallbackJob?.cancel()
        foregroundHardFallbackJob = null

        networkTypeChangedFallbackJob?.cancel()
        networkTypeChangedFallbackJob = null
    }

    protected override fun recoveryInvalidStateSummary(): String? {
        return SingBoxService.buildRecoveryInvalidStateSummary(
            isRunning = SingBoxService.isRunning,
            isStarting = SingBoxService.isStarting,
            isStopping = isStopping,
            isManuallyStopped = SingBoxService.isManuallyStopped
        )
    }

    protected override fun buildRecoveryDebounceContext(request: RecoveryRequest): SingBoxServiceRecoveryDebounceContext {
        val lane = if (request.reason.isFastLane) "fast" else "normal"
        val effectiveGlobalDebounceMs = if (request.reason.isFastLane) {
            recoveryFastLaneGlobalDebounceMs
        } else {
            recoveryGlobalDebounceMs
        }
        val effectiveSourceDebounceMs = if (request.reason.isFastLane) {
            minOf(request.reason.sourceDebounceMs, recoveryFastLaneSourceDebounceCapMs)
        } else {
            request.reason.sourceDebounceMs
        }
        return SingBoxServiceRecoveryDebounceContext(
            now = SystemClock.elapsedRealtime(),
            lane = lane,
            effectiveGlobalDebounceMs = effectiveGlobalDebounceMs,
            effectiveSourceDebounceMs = effectiveSourceDebounceMs,
            reasonKey = request.reason.name
        )
    }

    protected override fun shouldSkipByGlobalDebounce(
        request: RecoveryRequest,
        context: SingBoxServiceRecoveryDebounceContext
    ): Boolean {
        val lastGlobal = recoveryLastTriggeredAtMs.get()
        if (!request.force && context.now - lastGlobal < context.effectiveGlobalDebounceMs) {
            recoverySkippedDebounceCount.incrementAndGet()
            logRecoveryEvent(
                event = "skipped_global_debounce",
                request = request,
                mode = null,
                merged = request.merged,
                skipped = true,
                outcome = "debounce(lane=${context.lane},threshold=${context.effectiveGlobalDebounceMs}ms)"
            )
            return true
        }
        return false
    }

    protected override fun shouldSkipBySourceDebounce(
        request: RecoveryRequest,
        context: SingBoxServiceRecoveryDebounceContext
    ): Boolean {
        val reasonLast = recoveryReasonLastAtMs[context.reasonKey] ?: 0L
        if (!request.force && context.now - reasonLast < context.effectiveSourceDebounceMs) {
            recoverySkippedDebounceCount.incrementAndGet()
            logRecoveryEvent(
                event = "skipped_source_debounce",
                request = request,
                mode = null,
                merged = request.merged,
                skipped = true,
                outcome = "debounce(lane=${context.lane},threshold=${context.effectiveSourceDebounceMs}ms)"
            )
            return true
        }
        return false
    }

    protected override fun requestImmediateRouteGroupReselectIfNeeded(request: RecoveryRequest) {
        if (!SingBoxService.shouldTriggerRouteGroupImmediateReselect(request.reason)) {
            return
        }
        routeGroupSelector.requestImmediateReselect(request.rawReason)
    }

    protected override fun convergeConnectionsAfterImmediateRouteGroupSwitch(
        groupTag: String,
        previousSelectedTag: String,
        newSelectedTag: String,
        rawReason: String
    ) {
        val reason = RecoveryReason.fromReasonString(rawReason)
        if (!SingBoxService.shouldConvergeConnectionsAfterImmediateRouteGroupSwitch(reason)) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (!SingBoxService.shouldRunRouteGroupSwitchConvergence(
                lastTriggeredAtMs = lastConnectionsResetAtMs,
                nowAtMs = now,
                debounceMs = connectionsResetDebounceMs
            )
        ) {
            Log.d(
                SingBoxService.TAG,
                "[RouteGroupConvergence] skipped by debounce, group=$groupTag, " +
                    "from=$previousSelectedTag, to=$newSelectedTag, reason=$rawReason"
            )
            return
        }

        lastConnectionsResetAtMs = now
        val closedTrackedConnections = BoxWrapperManager.closeAllTrackedConnections()
        val resetAllTriggered = BoxWrapperManager.resetAllConnections(true)
        Log.i(
            SingBoxService.TAG,
            "[RouteGroupConvergence] group=$groupTag from=$previousSelectedTag to=$newSelectedTag, " +
                "reason=${reason.name}, closedTracked=$closedTrackedConnections, " +
                "resetAllTriggered=$resetAllTriggered"
        )
    }

    @Suppress("LongMethod", "CognitiveComplexMethod")
    protected override suspend fun executeRecoveryRequest(request: RecoveryRequest) {
        synchronized(this) {
            recoveryInFlight = true
        }
        try {
            val invalidState = recoveryInvalidStateSummary()
            if (invalidState != null) {
                logRecoveryEvent(
                    event = "skipped_invalid_state",
                    request = request,
                    mode = null,
                    merged = request.merged,
                    skipped = true,
                    outcome = "invalid_state($invalidState)"
                )
                return
            }

            val recoveryProfile = getRecoveryProfile()
            val forceDowngraded = SingBoxService.shouldDowngradeForceForHysteria2(
                profile = recoveryProfile,
                reason = request.reason,
                force = request.force
            )
            val executionForce = if (forceDowngraded) false else request.force
            val context = buildRecoveryDebounceContext(request)
            if (shouldSkipByGlobalDebounce(request, context)) return
            if (shouldSkipBySourceDebounce(request, context)) return

            recoveryLastTriggeredAtMs.set(context.now)
            recoveryReasonLastAtMs[context.reasonKey] = context.now
            recoveryTriggerCount.incrementAndGet()

            val smartResult = BoxWrapperManager.smartRecover(
                context = this@SingBoxServicePart2,
                source = request.rawReason,
                skipProbe = executionForce
            )

            val mode = when (smartResult.level) {
                BoxWrapperManager.RecoveryLevel.NONE,
                BoxWrapperManager.RecoveryLevel.PROBE -> BoxWrapperManager.RecoveryMode.SOFT
                BoxWrapperManager.RecoveryLevel.SELECTIVE -> {
                    recoverySoftCount.incrementAndGet()
                    BoxWrapperManager.RecoveryMode.SOFT
                }
                BoxWrapperManager.RecoveryLevel.NUCLEAR -> {
                    recoveryHardCount.incrementAndGet()
                    BoxWrapperManager.RecoveryMode.HARD
                }
            }

            val success = smartResult.success
            if (success) {
                recoverySuccessCount.incrementAndGet()
                recoveryConsecutiveFailureCount.set(0)
            } else {
                recoveryFailureCount.incrementAndGet()
                recoveryConsecutiveFailureCount.incrementAndGet()
            }

            val successRate = calculateRecoverySuccessRate()
            val outcomeDetail = buildString {
                append(if (success) "success" else "failed")
                append("(level=${smartResult.level}")
                smartResult.probeLatencyMs?.let { append(",probe=${it}ms") }
                if (smartResult.closedConnections > 0) {
                    append(",closed=${smartResult.closedConnections}")
                }
                if (forceDowngraded) {
                    append(",force_downgraded=true")
                }
                append(",rate=$successRate)")
            }
            logRecoveryEvent(
                event = "executed",
                request = request,
                mode = mode,
                merged = request.merged,
                skipped = false,
                outcome = outcomeDetail
            )

            requestImmediateRouteGroupReselectIfNeeded(request)

            if (smartResult.level == BoxWrapperManager.RecoveryLevel.PROBE) {
                scheduleForegroundHardFallbackIfNeeded(request, mode, success)
            }
            scheduleNetworkTypeChangedFallbackIfNeeded(request, mode, success)
        } finally {
            val nextRequest = synchronized(this) {
                recoveryInFlight = false
                val next = pendingRecoveryRequest
                pendingRecoveryRequest = null
                next
            }
            if (nextRequest != null) {
                executeRecoveryRequest(nextRequest)
            }
        }
    }

    protected override fun calculateRecoverySuccessRate(): String {
        val success = recoverySuccessCount.get()
        val failure = recoveryFailureCount.get()
        val total = success + failure
        if (total <= 0L) return "n/a"
        val percentage = (success * 100.0) / total.toDouble()
        return "%.1f%%".format(java.util.Locale.US, percentage)
    }

    /**
     * 2025-fix-v7: 前台快速恢复 - 跳过探测，直接 wake + resetNetwork
     * 比 smartRecover 少 2-5 秒（不做 PROBE + SELECTIVE 的验证循环）
     * 仅在 APP_FOREGROUND + force 时使用
     */

    protected override fun isSelectedHysteria2Outbound(): Boolean {
        val selectedTag = SelectorManager.getSelectedOutbound()
            ?: BoxWrapperManager.getSelectedOutbound()
            ?: return false

        return try {
            val configPath = SingBoxService.lastConfigPath ?: File(filesDir, "running_config.json").absolutePath
            val configContent = File(configPath).takeIf { it.exists() }?.readText() ?: return false
            val config = gson.fromJson(configContent, SingBoxConfig::class.java) ?: return false
            config.outbounds
                ?.firstOrNull { it.tag == selectedTag }
                ?.type
                ?.equals("hysteria2", ignoreCase = true) == true
        } catch (e: Exception) {
            Log.w(SingBoxService.TAG, "isSelectedHysteria2Outbound failed: ${e.message}")
            false
        }
    }

    protected override fun getRecoveryProfile(): RecoveryProfile {
        return if (isSelectedHysteria2Outbound()) RecoveryProfile.HYSTERIA2 else RecoveryProfile.DEFAULT
    }

    protected override fun executeForegroundFastRecovery(request: RecoveryRequest) {
        val invalidState = recoveryInvalidStateSummary()
        if (invalidState != null) {
            logRecoveryEvent(
                event = "foreground_fast_recovery_skipped_state",
                request = request,
                mode = BoxWrapperManager.RecoveryMode.SOFT,
                merged = false,
                skipped = true,
                outcome = "invalid_state($invalidState)"
            )
            return
        }

        val startMs = SystemClock.elapsedRealtime()
        val isHy2 = isSelectedHysteria2Outbound()

        val recoveryProfile = getRecoveryProfile()
        BoxWrapperManager.wake()
        if (SingBoxService.shouldCloseConnectionsDuringForegroundFastRecovery(recoveryProfile)) {
            BoxWrapperManager.closeAllTrackedConnections()
            BoxWrapperManager.resetAllConnections(true)
        } else if (isHy2) {
            Log.i(SingBoxService.TAG, "[ForegroundFastRecovery] hysteria2 selected, skip aggressive reset")
        }
        BoxWrapperManager.resetNetwork()

        val elapsedMs = SystemClock.elapsedRealtime() - startMs
        Log.i(SingBoxService.TAG, "[ForegroundFastRecovery] completed in ${elapsedMs}ms")

        recoveryLastTriggeredAtMs.set(SystemClock.elapsedRealtime())
        recoveryTriggerCount.incrementAndGet()
        recoverySoftCount.incrementAndGet()
        recoverySuccessCount.incrementAndGet()
        recoveryConsecutiveFailureCount.set(0)

        logRecoveryEvent(
            event = "foreground_fast_recovery",
            request = request,
            mode = BoxWrapperManager.RecoveryMode.SOFT,
            merged = false,
            skipped = false,
            outcome = if (isHy2) "hy2_fast_path(${elapsedMs}ms)" else "fast_path(${elapsedMs}ms)"
        )

        scheduleForegroundHardFallbackIfNeeded(
            request = request,
            mode = BoxWrapperManager.RecoveryMode.SOFT,
            success = true
        )
    }

    protected override fun evaluateForegroundFallbackState(): SingBoxServiceForegroundFallbackState {
        val invalidState = recoveryInvalidStateSummary()
        val stateSkipOutcome = invalidState?.let { "state_$it" } ?: ""
        val shouldSkipByState = invalidState != null

        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastForegroundHardFallbackAtMs.get()
        val shouldSkipByDebounce = elapsed in 0 until foregroundHardFallbackDebounceMs

        val skipReason = when {
            shouldSkipByState -> "state"
            vpnLinkValidated -> "validated"
            shouldSkipByDebounce -> "debounce"
            else -> null
        }

        return when (skipReason) {
            "state" -> SingBoxServiceForegroundFallbackState(
                shouldSkip = true,
                event = "foreground_hard_fallback_skipped_state",
                outcome = stateSkipOutcome
            )
            "validated" -> SingBoxServiceForegroundFallbackState(
                shouldSkip = true,
                event = "foreground_hard_fallback_skipped_validated",
                outcome = "vpn_link_validated"
            )
            "debounce" -> SingBoxServiceForegroundFallbackState(
                shouldSkip = true,
                event = "foreground_hard_fallback_skipped_debounce",
                outcome = "debounce(elapsed=${elapsed}ms," +
                    "threshold=${foregroundHardFallbackDebounceMs}ms)"
            )
            else -> {
                lastForegroundHardFallbackAtMs.set(now)
                SingBoxServiceForegroundFallbackState(
                    shouldSkip = false,
                    event = "foreground_hard_fallback_enqueued",
                    outcome = "grace=${foregroundRecoveryGraceMs}ms"
                )
            }
        }
    }

    protected override fun scheduleForegroundHardFallbackIfNeeded(
        request: RecoveryRequest,
        mode: BoxWrapperManager.RecoveryMode,
        success: Boolean
    ) {
        if (!SingBoxService.shouldScheduleForegroundHardFallback(request, mode, success)) {
            return
        }

        foregroundHardFallbackJob?.cancel()
        foregroundHardFallbackJob = serviceScope.launch {
            delay(foregroundRecoveryGraceMs)

            // 先探测 VPN 链路，如果正常则跳过 HARD fallback
            val probeOk = runCatching {
                ProbeManager.probeFirstSuccessViaVpn(
                    context = this@SingBoxServicePart2,
                    timeoutMs = 1500L
                )
            }.getOrNull() != null

            if (probeOk) {
                logRecoveryEvent(
                    event = "foreground_hard_fallback_skipped_probe_ok",
                    request = request,
                    mode = BoxWrapperManager.RecoveryMode.HARD,
                    merged = false,
                    skipped = true,
                    outcome = "vpn_link_healthy_on_probe"
                )
                return@launch
            }

            val state = evaluateForegroundFallbackState()
            logRecoveryEvent(
                event = state.event,
                request = request,
                mode = BoxWrapperManager.RecoveryMode.HARD,
                merged = false,
                skipped = state.shouldSkip,
                outcome = state.outcome
            )
            if (state.shouldSkip) {
                return@launch
            }

            val hardRequest = RecoveryRequest(
                reason = RecoveryReason.APP_FOREGROUND,
                rawReason = "app_foreground_hard_fallback",
                force = true,
                requestedAtMs = SystemClock.elapsedRealtime(),
                merged = false
            )

            submitRecoveryRequest(hardRequest)
        }
    }

    protected override suspend fun collectNetworkTypeChangedRecoverySignal(): SingBoxServiceNetworkTypeChangedRecoverySignal {
        val probeSucceeded = runCatching {
            ProbeManager.probeFirstSuccessViaVpn(
                context = this@SingBoxServicePart2,
                timeoutMs = 1500L
            )
        }.getOrNull() != null

        val networkRecoveryNeeded = runCatching {
            !BoxWrapperManager.isAvailable() || BoxWrapperManager.isNetworkRecoveryNeeded()
        }.getOrDefault(true)

        return SingBoxServiceNetworkTypeChangedRecoverySignal(
            probeSucceeded = probeSucceeded,
            networkRecoveryNeeded = networkRecoveryNeeded,
            strongSignal = SingBoxService.hasStrongNetworkTypeChangedRecoverySignal(
                probeSucceeded = probeSucceeded,
                networkRecoveryNeeded = networkRecoveryNeeded
            )
        )
    }

    protected override fun evaluateNetworkTypeChangedFallbackState(
        mode: BoxWrapperManager.RecoveryMode,
        signal: SingBoxServiceNetworkTypeChangedRecoverySignal
    ): SingBoxServiceNetworkTypeChangedFallbackState {
        val signalOutcome = "probe_ok=${signal.probeSucceeded},network_recovery_needed=${signal.networkRecoveryNeeded}"
        val fallbackState = buildNetworkTypeChangedStateSkip()
            ?: if (signal.strongSignal) {
                SingBoxServiceNetworkTypeChangedFallbackState(
                    shouldSkip = true,
                    event = "network_type_changed_fallback_skipped_recovered",
                    outcome = signalOutcome
                )
            } else {
                buildTriggeredNetworkTypeChangedFallbackState(mode, signalOutcome)
            }
        return fallbackState
    }

    protected override fun buildTriggeredNetworkTypeChangedFallbackState(
        mode: BoxWrapperManager.RecoveryMode,
        signalOutcome: String
    ): SingBoxServiceNetworkTypeChangedFallbackState {
        val action = SingBoxService.determineNetworkTypeChangedFallbackAction(mode)
        val now = SystemClock.elapsedRealtime()
        val debounceMs = resolveNetworkTypeChangedFallbackDebounceMs(action)
        val lastActionAtMs = resolveLastNetworkTypeChangedFallbackAtMs(action)
        return if (!SingBoxService.shouldRunNetworkTypeChangedFallback(lastActionAtMs, now, debounceMs)) {
            SingBoxServiceNetworkTypeChangedFallbackState(
                shouldSkip = true,
                event = "network_type_changed_fallback_skipped_debounce",
                outcome = "$signalOutcome,action=${action.name},debounce=${debounceMs}ms"
            )
        } else {
            recordNetworkTypeChangedFallbackAt(action, now)
            SingBoxServiceNetworkTypeChangedFallbackState(
                shouldSkip = false,
                event = "network_type_changed_fallback_triggered",
                outcome = "$signalOutcome,action=${action.name}",
                action = action
            )
        }
    }

    protected override fun buildNetworkTypeChangedStateSkip(): SingBoxServiceNetworkTypeChangedFallbackState? {
        val stateSkipOutcome = recoveryInvalidStateSummary()?.let { "state_$it" } ?: ""
        val shouldSkipByState = SingBoxService.shouldSkipNetworkTypeChangedFallbackByState(
            isRunning = SingBoxService.isRunning,
            isStarting = SingBoxService.isStarting,
            isStopping = isStopping,
            isManuallyStopped = SingBoxService.isManuallyStopped
        )
        return if (shouldSkipByState) {
            SingBoxServiceNetworkTypeChangedFallbackState(
                shouldSkip = true,
                event = "network_type_changed_fallback_skipped_state",
                outcome = stateSkipOutcome
            )
        } else {
            null
        }
    }

    protected override fun resolveLastNetworkTypeChangedFallbackAtMs(
        action: NetworkTypeChangedFallbackAction
    ): Long {
        return if (action == NetworkTypeChangedFallbackAction.ESCALATE_HARD) {
            lastNetworkTypeChangedHardFallbackAtMs.get()
        } else {
            lastNetworkTypeChangedRestartAtMs.get()
        }
    }

    protected override fun resolveNetworkTypeChangedFallbackDebounceMs(
        action: NetworkTypeChangedFallbackAction
    ): Long {
        return if (action == NetworkTypeChangedFallbackAction.ESCALATE_HARD) {
            networkTypeChangedHardFallbackDebounceMs
        } else {
            networkTypeChangedRestartDebounceMs
        }
    }

    protected override fun recordNetworkTypeChangedFallbackAt(
        action: NetworkTypeChangedFallbackAction,
        now: Long
    ) {
        if (action == NetworkTypeChangedFallbackAction.ESCALATE_HARD) {
            lastNetworkTypeChangedHardFallbackAtMs.set(now)
        } else {
            lastNetworkTypeChangedRestartAtMs.set(now)
        }
    }

    protected override fun scheduleNetworkTypeChangedFallbackIfNeeded(
        request: RecoveryRequest,
        mode: BoxWrapperManager.RecoveryMode,
        success: Boolean
    ) {
        if (!SingBoxService.shouldScheduleNetworkTypeChangedFallback(request, success)) {
            return
        }

        networkTypeChangedFallbackJob?.cancel()
        networkTypeChangedFallbackJob = serviceScope.launch {
            delay(networkTypeChangedRecoveryGraceMs)

            val signal = collectNetworkTypeChangedRecoverySignal()
            val state = evaluateNetworkTypeChangedFallbackState(mode, signal)
            logRecoveryEvent(
                event = state.event,
                request = request,
                mode = mode,
                merged = false,
                skipped = state.shouldSkip,
                outcome = state.outcome
            )
            if (state.shouldSkip) {
                return@launch
            }

            when (state.action) {
                NetworkTypeChangedFallbackAction.ESCALATE_HARD -> {
                    val hardRequest = RecoveryRequest(
                        reason = RecoveryReason.NETWORK_TYPE_CHANGED,
                        rawReason = "network_type_changed_hard_fallback",
                        force = true,
                        requestedAtMs = SystemClock.elapsedRealtime(),
                        merged = false
                    )
                    submitRecoveryRequest(hardRequest)
                }

                NetworkTypeChangedFallbackAction.RESTART_VPN -> {
                    restartVpnService("network_type_changed_unrecovered")
                }

                null -> Unit
            }
        }
    }

    @Suppress("LongParameterList")
    protected override fun logRecoveryEvent(
        event: String,
        request: RecoveryRequest,
        mode: BoxWrapperManager.RecoveryMode?,
        merged: Boolean,
        skipped: Boolean,
        outcome: String?
    ) {
        val modeText = mode?.name ?: "n/a"
        val lane = if (request.reason.isFastLane) "fast" else "normal"
        val message = buildString {
            append("[RecoveryGate] event=")
            append(event)
            append(" lane=")
            append(lane)
            append(" reason=")
            append(request.reason.name)
            append(" raw=")
            append(request.rawReason)
            append(" priority=")
            append(request.reason.priority)
            append(" mode=")
            append(modeText)
            append(" merged=")
            append(merged)
            append(" skipped=")
            append(skipped)
            append(" force=")
            append(request.force)
            append(" trigger_count=")
            append(recoveryTriggerCount.get())
            append(" merged_count=")
            append(recoveryMergedCount.get())
            append(" skipped_debounce=")
            append(recoverySkippedDebounceCount.get())
            append(" soft_count=")
            append(recoverySoftCount.get())
            append(" hard_count=")
            append(recoveryHardCount.get())
            append(" success_rate=")
            append(calculateRecoverySuccessRate())
            if (!outcome.isNullOrBlank()) {
                append(" outcome=")
                append(outcome)
            }
        }
        Log.i(SingBoxService.TAG, message)
        runCatching { LogRepository.getInstance().addLog("INFO: $message") }
    }

    /**
     * 重启 VPN 服务以彻底清理网络状态
     * 用于处理网络栈重置无效的严重情况
     */
}
