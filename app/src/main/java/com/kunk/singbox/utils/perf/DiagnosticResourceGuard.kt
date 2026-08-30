@file:Suppress("TooManyFunctions", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "LongMethod", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeConst")

package com.kunk.singbox.utils.perf

import android.content.Context
import android.system.Os
import android.util.Log
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.service.manager.ConnectionAttributionSnapshot
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class FdPressureLevel {
    NORMAL,
    OBSERVE,
    WARNING,
    RECOVERY,
    EMERGENCY
}

internal data class FdPressureDecision(
    val level: FdPressureLevel,
    val sampleIntervalMs: Long,
    val shouldClassify: Boolean,
    val shouldRecover: Boolean
)

internal fun evaluateFdPressure(
    fdCount: Int?,
    fdSoftLimit: Long?,
    growthOverFiveMinutes: Int,
    consecutiveHighSamples: Int,
    rapidGrowth: Boolean = false
): FdPressureDecision {
    val ratio = if (fdCount != null && fdSoftLimit != null && fdSoftLimit > 0L) {
        fdCount.toDouble() / fdSoftLimit.toDouble()
    } else {
        null
    }
    val absoluteFallbackExceeded = fdSoftLimit == null &&
        fdCount != null && fdCount >= FD_ABSOLUTE_RECOVERY_COUNT
    return when {
        ratio != null && ratio >= FD_EMERGENCY_RATIO -> FdPressureDecision(
            FdPressureLevel.EMERGENCY,
            FD_WARNING_SAMPLE_INTERVAL_MS,
            shouldClassify = true,
            shouldRecover = true
        )
        rapidGrowth || absoluteFallbackExceeded -> FdPressureDecision(
            FdPressureLevel.RECOVERY,
            FD_WARNING_SAMPLE_INTERVAL_MS,
            shouldClassify = true,
            shouldRecover = true
        )
        ratio != null && ratio >= FD_RECOVERY_RATIO && consecutiveHighSamples >= 2 -> FdPressureDecision(
            FdPressureLevel.RECOVERY,
            FD_WARNING_SAMPLE_INTERVAL_MS,
            shouldClassify = true,
            shouldRecover = true
        )
        ratio != null && ratio >= FD_WARNING_RATIO || growthOverFiveMinutes >= FD_FIVE_MINUTE_GROWTH_WARNING -> {
            FdPressureDecision(
                FdPressureLevel.WARNING,
                FD_WARNING_SAMPLE_INTERVAL_MS,
                shouldClassify = true,
                shouldRecover = false
            )
        }
        ratio != null && ratio >= FD_OBSERVE_RATIO -> FdPressureDecision(
            FdPressureLevel.OBSERVE,
            FD_OBSERVE_SAMPLE_INTERVAL_MS,
            shouldClassify = false,
            shouldRecover = false
        )
        else -> FdPressureDecision(
            FdPressureLevel.NORMAL,
            FD_NORMAL_SAMPLE_INTERVAL_MS,
            shouldClassify = false,
            shouldRecover = false
        )
    }
}

internal fun isFdRecoverySufficient(
    beforeCount: Int?,
    afterCount: Int?,
    softLimit: Long?
): Boolean {
    if (beforeCount == null || afterCount == null) return false
    if (softLimit == null || softLimit <= 0L) return false
    val afterRatio = afterCount.toDouble() / softLimit.toDouble()
    val fellByHalf = afterCount <= beforeCount / 2
    return afterRatio < FD_WARNING_RATIO && (afterRatio < FD_OBSERVE_RATIO || fellByHalf)
}

internal class ResourceFdTracker {
    private val samples = ArrayDeque<Pair<Long, Int>>()
    private var pid: Int? = null
    private var consecutiveHighSamples = 0
    private var consecutiveRapidGrowthSamples = 0
    private var recoverySuppressedUntilMs = 0L

    fun observe(sample: DiagnosticResourceSample): FdPressureDecision {
        resetForPid(sample.pid)
        val count = sample.fdCount
        val growth = recordSample(sample.elapsedRealtimeMs, count)
        val ratio = if (count != null && sample.fdSoftLimit != null && sample.fdSoftLimit > 0L) {
            count.toDouble() / sample.fdSoftLimit.toDouble()
        } else {
            null
        }
        consecutiveHighSamples = if (ratio != null && ratio >= FD_RECOVERY_RATIO) {
            consecutiveHighSamples + 1
        } else {
            0
        }
        val rapidGrowth = isRapidGrowth(sample.elapsedRealtimeMs, count)
        consecutiveRapidGrowthSamples = if (rapidGrowth) consecutiveRapidGrowthSamples + 1 else 0
        val sustainedRapidGrowth = rapidGrowth &&
            consecutiveRapidGrowthSamples >= FD_RAPID_GROWTH_REQUIRED_SAMPLES &&
            (count ?: 0) >= rapidRecoveryFloor(sample.fdSoftLimit)
        val decision = evaluateFdPressure(
            count, sample.fdSoftLimit, growth, consecutiveHighSamples, sustainedRapidGrowth
        )
        return applyRecoveryGuards(decision, rapidGrowth, sample.elapsedRealtimeMs)
    }

    fun markRecoveryStarted(elapsedRealtimeMs: Long) {
        samples.clear()
        consecutiveHighSamples = 0
        consecutiveRapidGrowthSamples = 0
        recoverySuppressedUntilMs = elapsedRealtimeMs + FD_RECOVERY_COOLDOWN_MS
    }

    private fun resetForPid(currentPid: Int) {
        if (pid == currentPid) return
        samples.clear()
        consecutiveHighSamples = 0
        consecutiveRapidGrowthSamples = 0
        recoverySuppressedUntilMs = 0L
        pid = currentPid
    }

    private fun recordSample(elapsedRealtimeMs: Long, count: Int?): Int {
        count ?: return 0
        samples.addLast(elapsedRealtimeMs to count)
        while (samples.size > 1 && elapsedRealtimeMs - samples.first().first > FD_GROWTH_WINDOW_MS) {
            samples.removeFirst()
        }
        return if (samples.size >= 2) samples.last().second - samples.first().second else 0
    }

    private fun isRapidGrowth(elapsedRealtimeMs: Long, count: Int?): Boolean {
        count ?: return false
        val baseline = samples.firstOrNull { point ->
            elapsedRealtimeMs - point.first <= FD_RAPID_GROWTH_WINDOW_MS
        } ?: return false
        return count >= FD_RAPID_GROWTH_MIN_COUNT && count - baseline.second >= FD_RAPID_GROWTH_THRESHOLD
    }

    private fun rapidRecoveryFloor(fdSoftLimit: Long?): Int {
        val ratioFloor = fdSoftLimit
            ?.takeIf { it > 0L }
            ?.let { (it * FD_RAPID_GROWTH_RECOVERY_RATIO).coerceAtMost(FD_ABSOLUTE_RECOVERY_COUNT.toDouble()) }
            ?.toInt()
        return ratioFloor?.coerceAtLeast(FD_RAPID_GROWTH_MIN_COUNT) ?: FD_ABSOLUTE_RECOVERY_COUNT
    }

    private fun applyRecoveryGuards(
        decision: FdPressureDecision,
        rapidGrowth: Boolean,
        elapsedRealtimeMs: Long
    ): FdPressureDecision {
        val classified = if (rapidGrowth && decision.level < FdPressureLevel.WARNING) {
            FdPressureDecision(
                level = FdPressureLevel.WARNING,
                sampleIntervalMs = FD_WARNING_SAMPLE_INTERVAL_MS,
                shouldClassify = true,
                shouldRecover = false
            )
        } else {
            decision
        }
        return if (elapsedRealtimeMs < recoverySuppressedUntilMs &&
            classified.shouldRecover && classified.level != FdPressureLevel.EMERGENCY
        ) {
            classified.copy(
                level = FdPressureLevel.WARNING,
                shouldClassify = true,
                shouldRecover = false
            )
        } else {
            classified
        }
    }
}

internal class ResourceRecoveryBudgetHealthTracker(
    private val healthyWindowMs: Long = RESOURCE_RECOVERY_BUDGET_HEALTHY_RESET_MS
) {
    private var pid: Int? = null
    private var healthySinceMs: Long? = null
    private var resetEmitted = false

    init {
        require(healthyWindowMs > 0L)
    }

    fun observe(sample: DiagnosticResourceSample, level: FdPressureLevel): Boolean {
        if (pid != sample.pid) {
            pid = sample.pid
            healthySinceMs = null
            resetEmitted = false
        }
        if (level != FdPressureLevel.NORMAL || sample.fdCount == null) {
            healthySinceMs = null
            resetEmitted = false
            return false
        }
        val healthySince = healthySinceMs ?: sample.elapsedRealtimeMs.also { healthySinceMs = it }
        if (resetEmitted || sample.elapsedRealtimeMs - healthySince < healthyWindowMs) return false
        resetEmitted = true
        return true
    }
}

internal class ResourceRecoveryNoticeGate {
    private var active = false

    fun claim(): Boolean {
        if (active) return false
        active = true
        return true
    }

    fun clear(): Boolean = active.also { active = false }
}

internal val isResourceRecoveryBudgetError: (String?) -> Boolean = { message ->
    message?.startsWith(RESOURCE_RECOVERY_BUDGET_ERROR_PREFIX) == true
}

internal class DiagnosticResourceHistory(
    private val historyFile: File,
    private val maxSamples: Int = MAX_BACKGROUND_RESOURCE_SAMPLES
) {
    constructor(context: Context, maxSamples: Int = MAX_BACKGROUND_RESOURCE_SAMPLES) : this(
        File(context.filesDir, RESOURCE_HISTORY_RELATIVE_PATH),
        maxSamples
    )

    private var knownCount: Int? = null

    @Synchronized
    fun append(sample: DiagnosticResourceSample) {
        historyFile.parentFile?.mkdirs()
        val csv = formatDiagnosticResourceSamplesCsv(listOf(sample))
        val row = csv.lineSequence().drop(1).firstOrNull().orEmpty()
        if (!historyFile.exists()) {
            writeAtomically(listOf(sample))
            knownCount = 1
            return
        }
        val currentHeader = historyFile.bufferedReader(Charsets.UTF_8).use { it.readLine() }
        if (currentHeader != DIAGNOSTIC_RESOURCE_CSV_HEADER) {
            val migrated = (read() + sample).takeLast(maxSamples)
            writeAtomically(migrated)
            knownCount = migrated.size
            return
        }
        val previousCount = knownCount ?: read().size
        FileOutputStream(historyFile, true).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.append(row)
            writer.newLine()
        }
        val count = previousCount + 1
        knownCount = count
        if (count > maxSamples) {
            // ponytail: 仅在 4096 条边界发生一次 O(n) 轮转，避免常态维护额外索引文件。
            val retained = read().takeLast(maxSamples)
            writeAtomically(retained)
            knownCount = retained.size
        }
    }

    @Synchronized
    fun read(): List<DiagnosticResourceSample> {
        if (!historyFile.isFile) return emptyList()
        return runCatching {
            parseDiagnosticResourceSamplesCsv(historyFile.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    private fun writeAtomically(samples: List<DiagnosticResourceSample>) {
        val tempFile = File(historyFile.parentFile, "${historyFile.name}.tmp")
        tempFile.writeText(formatDiagnosticResourceSamplesCsv(samples), Charsets.UTF_8)
        val renamed = tempFile.renameTo(historyFile) || runCatching {
            Os.rename(tempFile.absolutePath, historyFile.absolutePath)
            historyFile.isFile && !tempFile.exists()
        }.getOrDefault(false)
        if (!renamed) {
            tempFile.copyTo(historyFile, overwrite = true)
            tempFile.delete()
        }
    }
}

internal fun mergeDiagnosticResourceSamples(
    backgroundSamples: List<DiagnosticResourceSample>,
    inMemorySamples: List<DiagnosticResourceSample>
): List<DiagnosticResourceSample> {
    return (backgroundSamples + inMemorySamples)
        .distinctBy { Triple(it.timestampEpochMs, it.processName, it.pid) }
        .sortedWith(compareBy<DiagnosticResourceSample> { it.timestampEpochMs }.thenBy { it.processName })
}

internal interface ResourceGuardOwner {
    fun isRecoveryAllowed(): Boolean
    fun connectionAttributionSnapshot(): ConnectionAttributionSnapshot
    fun restartCore(reason: String, attemptId: Long): Boolean
    fun recycleProcess(reason: String)
    fun publishBudgetExhausted(reason: String)
    fun clearBudgetExhaustedError()
}

internal data class ResourceRecoverySuccessor(
    val registration: ResourceGuardRegistration,
    val owner: ResourceGuardOwner,
    val sampler: DiagnosticResourceSampler,
    val history: DiagnosticResourceHistory
)

internal data class ActiveResourceRecovery(
    val attemptId: Long,
    val registration: ResourceGuardRegistration,
    val reason: String,
    val owner: ResourceGuardOwner,
    val sampler: DiagnosticResourceSampler,
    val history: DiagnosticResourceHistory,
    val successorSignal: CompletableDeferred<Unit> = CompletableDeferred(),
    var successorResolved: Boolean = false,
    var successorResult: ResourceRecoverySuccessor? = null,
    var job: Job? = null
)

internal object BackgroundResourceGuard {
    private const val TAG = "BackgroundResourceGuard"
    private val lock = Any()
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = ResourceRecoveryGate()
    private val noticeGate = ResourceRecoveryNoticeGate()
    private var registration: ResourceGuardRegistration? = null
    private var monitorJob: Job? = null
    private var recovery: ActiveResourceRecovery? = null
    private var owner: ResourceGuardOwner? = null
    private var sampler: DiagnosticResourceSampler? = null
    private var history: DiagnosticResourceHistory? = null
    private var noticeOwnerId: Any? = null

    @Volatile
    private var recovering = false

    @Suppress("CognitiveComplexMethod")
    fun start(
        context: Context,
        scope: CoroutineScope,
        registration: ResourceGuardRegistration,
        owner: ResourceGuardOwner
    ) {
        if (synchronized(lock) { gate.isCurrent(registration) && monitorJob?.isActive == true }) return

        val activeSampler = DiagnosticResourceSampler(context)
        val activeHistory = DiagnosticResourceHistory(context)
        val newMonitorJob = scope.launch(start = CoroutineStart.LAZY) {
            monitor(registration, activeSampler, activeHistory, owner)
        }
        var installMonitor = false
        var oldMonitorJob: Job? = null
        var recoveryToCancel: ActiveResourceRecovery? = null
        var successorSignal: CompletableDeferred<Unit>? = null
        synchronized(lock) {
            if (gate.isCurrent(registration) && monitorJob?.isActive == true) return@synchronized

            val registerResult = gate.register(registration)
            if (registerResult.rejected) return@synchronized

            oldMonitorJob = monitorJob
            recoveryToCancel = removeRecoveryLocked(registerResult.cancelledAttemptId)

            this.registration = registration
            this.owner = owner
            sampler = activeSampler
            history = activeHistory

            registerResult.successorAttemptId?.let { attemptId ->
                val activeRecovery = recovery
                if (activeRecovery?.attemptId == attemptId) {
                    if (activeRecovery.successorResolved) {
                        gate.finish(attemptId)
                        recoveryToCancel = removeRecoveryLocked(attemptId)
                    } else {
                        activeRecovery.successorResolved = true
                        activeRecovery.successorResult = ResourceRecoverySuccessor(
                            registration = registration,
                            owner = owner,
                            sampler = activeSampler,
                            history = activeHistory
                        )
                        successorSignal = activeRecovery.successorSignal
                    }
                } else {
                    gate.finish(attemptId)
                }
            }

            monitorJob = newMonitorJob
            installMonitor = true
        }

        oldMonitorJob?.cancel()
        cancelRecoveryOutsideLock(recoveryToCancel)
        successorSignal?.complete(Unit)
        if (installMonitor && synchronized(lock) {
                monitorJob === newMonitorJob && gate.isCurrent(registration)
            }
        ) {
            newMonitorJob.start()
        } else {
            newMonitorJob.cancel()
        }
    }

    fun detach(registration: ResourceGuardRegistration, handoffAttemptId: Long) {
        var oldMonitorJob: Job? = null
        var recoveryToCancel: ActiveResourceRecovery? = null
        synchronized(lock) {
            val result = gate.detach(registration, handoffAttemptId)
            if (!result.detached) return

            oldMonitorJob = monitorJob
            monitorJob = null
            this.registration = null
            owner = null
            sampler = null
            history = null
            recoveryToCancel = removeRecoveryLocked(result.cancelledAttemptId)
        }
        oldMonitorJob?.cancel()
        cancelRecoveryOutsideLock(recoveryToCancel)
    }

    fun cancelOwner(ownerId: Any) {
        var oldMonitorJob: Job? = null
        var recoveryToCancel: ActiveResourceRecovery? = null
        synchronized(lock) {
            val result = gate.cancelOwner(ownerId)
            if (result.registrationCancelled) {
                oldMonitorJob = monitorJob
                monitorJob = null
                registration = null
                owner = null
                sampler = null
                history = null
            }
            if (noticeOwnerId === ownerId) {
                noticeGate.clear()
                noticeOwnerId = null
            }
            recoveryToCancel = removeRecoveryLocked(result.cancelledAttemptId)
        }
        oldMonitorJob?.cancel()
        cancelRecoveryOutsideLock(recoveryToCancel)
    }

    fun isRecovering(): Boolean = recovering

    fun signalResourceExhaustion(registration: ResourceGuardRegistration, reason: String) {
        val candidate = synchronized(lock) {
            if (!gate.isCurrent(registration)) return
            val activeOwner = owner ?: return
            val activeSampler = sampler ?: return
            activeOwner to activeSampler
        }
        val sample = runCatching {
            candidate.second.captureCurrentFdPressure()
                .attachConnectionAttribution(candidate.first)
        }.getOrNull()
        requestRecovery(registration, reason, sample)
    }

    suspend fun failSuccessorAndAwait(ownerId: Any, attemptId: Long?) {
        if (attemptId == null) return
        var successorSignal: CompletableDeferred<Unit>? = null
        val recoveryJob = synchronized(lock) {
            if (!gate.isAttemptCurrent(ownerId, attemptId, ResourceRecoveryPhase.AWAITING_SUCCESSOR)) {
                return@synchronized null
            }
            recovery
                ?.takeIf { it.attemptId == attemptId }
                ?.also { activeRecovery ->
                    if (!activeRecovery.successorResolved) {
                        activeRecovery.successorResolved = true
                        activeRecovery.successorResult = null
                        successorSignal = activeRecovery.successorSignal
                    }
                }
                ?.job
        }
        successorSignal?.complete(Unit)
        recoveryJob?.join()
    }

    fun isRecoveryAttemptActive(ownerId: Any, attemptId: Long): Boolean = synchronized(lock) {
        gate.isAttemptCurrent(ownerId, attemptId)
    }

    @Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod", "NestedBlockDepth", "LongMethod")
    private suspend fun monitor(
        registration: ResourceGuardRegistration,
        activeSampler: DiagnosticResourceSampler,
        activeHistory: DiagnosticResourceHistory,
        activeOwner: ResourceGuardOwner
    ) {
        val tracker = ResourceFdTracker()
        val budgetHealthTracker = ResourceRecoveryBudgetHealthTracker()
        val errorHealthTracker = ResourceRecoveryBudgetHealthTracker(
            healthyWindowMs = RESOURCE_RECOVERY_ERROR_HEALTHY_CLEAR_MS
        )
        var lastFullSampleAtMs: Long? = null
        var lastBreakdownAtMs: Long? = null
        var previousLevel = FdPressureLevel.NORMAL
        while (kotlin.coroutines.coroutineContext.isActive && isCurrent(registration)) {
            try {
                val pressureSample = activeSampler.captureCurrentFdPressure()
                val decision = tracker.observe(pressureSample)
                if (errorHealthTracker.observe(pressureSample, decision.level)) {
                    val shouldClearError = synchronized(lock) {
                        if (noticeOwnerId !== registration.ownerId) {
                            false
                        } else {
                            noticeGate.clear()
                        }
                    }
                    if (shouldClearError) activeOwner.clearBudgetExhaustedError()
                }
                if (budgetHealthTracker.observe(pressureSample, decision.level)) {
                    VpnStateStore.resetResourceRecoveryBudget()
                    LogRepository.getInstance().addAlwaysLog(
                        "INFO recovery resource_exhausted stage=budget_reset result=healthy_fd_window"
                    )
                }
                val levelChanged = decision.level != previousLevel
                if (decision.shouldRecover) {
                    val attributedPressure = pressureSample.attachConnectionAttribution(activeOwner)
                    if (startImmediateFdRecovery(registration, activeHistory, attributedPressure, decision)) {
                        tracker.markRecoveryStarted(pressureSample.elapsedRealtimeMs)
                    }
                    previousLevel = decision.level
                    delay(decision.sampleIntervalMs)
                    continue
                }
                val breakdownDue = decision.shouldClassify && (
                    levelChanged || lastBreakdownAtMs == null ||
                        pressureSample.elapsedRealtimeMs - checkNotNull(lastBreakdownAtMs) >= FD_BREAKDOWN_INTERVAL_MS
                    )
                val fullSampleDue = lastFullSampleAtMs == null || breakdownDue ||
                    pressureSample.elapsedRealtimeMs - checkNotNull(lastFullSampleAtMs) >= FD_FULL_SAMPLE_INTERVAL_MS
                val rawSample = if (fullSampleDue) {
                    activeSampler.captureCurrentProcess(includeFdBreakdown = breakdownDue)
                } else {
                    pressureSample
                }
                val sample = if (fullSampleDue || levelChanged) {
                    rawSample.attachConnectionAttribution(activeOwner)
                } else {
                    rawSample
                }
                if (fullSampleDue) {
                    runCatching { activeHistory.append(sample) }
                        .onFailure { Log.w(TAG, "Failed to persist resource sample: ${it.message}") }
                    lastFullSampleAtMs = sample.elapsedRealtimeMs
                    if (sample.fdBreakdown != null) lastBreakdownAtMs = sample.elapsedRealtimeMs
                }
                if (levelChanged || fullSampleDue) logSample(sample, decision)
                previousLevel = decision.level
                delay(decision.sampleIntervalMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Resource monitor sample failed", e)
                delay(FD_NORMAL_SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun startImmediateFdRecovery(
        registration: ResourceGuardRegistration,
        activeHistory: DiagnosticResourceHistory,
        pressureSample: DiagnosticResourceSample,
        decision: FdPressureDecision
    ): Boolean {
        runCatching { activeHistory.append(pressureSample) }
            .onFailure { Log.w(TAG, "Failed to persist recovery pressure sample: ${it.message}") }
        logSample(pressureSample, decision)
        return requestRecovery(
            registration,
            "fd_${decision.level.name.lowercase(Locale.US)}",
            pressureSample
        )
    }

    @Suppress("CyclomaticComplexMethod")
    private fun requestRecovery(
        registration: ResourceGuardRegistration,
        reason: String,
        before: DiagnosticResourceSample?
    ): Boolean {
        val candidate = synchronized(lock) {
            val activeOwner = owner ?: return false
            val activeSampler = sampler ?: return false
            val activeHistory = history ?: return false
            if (!gate.isCurrent(registration) || recovery != null) return false
            ResourceRecoverySuccessor(registration, activeOwner, activeSampler, activeHistory)
        }
        if (!candidate.owner.isRecoveryAllowed()) return false

        val activeRecovery = synchronized(lock) {
            if (!isRecoveryCandidateCurrentLocked(registration, candidate)) return false
            val attemptId = gate.beginRecovery(registration) ?: return false
            ActiveResourceRecovery(
                attemptId = attemptId,
                registration = registration,
                reason = reason,
                owner = candidate.owner,
                sampler = candidate.sampler,
                history = candidate.history
            ).also {
                recovery = it
                recovering = true
            }
        }
        val job = recoveryScope.launch(start = CoroutineStart.LAZY) {
            try {
                recover(activeRecovery, before)
            } finally {
                finishRecovery(activeRecovery.attemptId)
            }
        }
        val shouldStart = synchronized(lock) {
            if (recovery !== activeRecovery ||
                !gate.isAttemptCurrent(registration.ownerId, activeRecovery.attemptId)
            ) {
                false
            } else {
                activeRecovery.job = job
                true
            }
        }
        if (shouldStart) {
            job.start()
        } else {
            job.cancel()
        }
        return shouldStart
    }

    private fun isRecoveryCandidateCurrentLocked(
        registration: ResourceGuardRegistration,
        candidate: ResourceRecoverySuccessor
    ): Boolean = when {
        !gate.isCurrent(registration) -> false
        recovery != null -> false
        owner !== candidate.owner -> false
        sampler !== candidate.sampler -> false
        history !== candidate.history -> false
        else -> true
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod", "ReturnCount")
    private suspend fun recover(
        activeRecovery: ActiveResourceRecovery,
        before: DiagnosticResourceSample?
    ) {
        val attemptId = activeRecovery.attemptId
        val sourceRegistration = activeRecovery.registration
        val activeOwner = activeRecovery.owner
        if (!activeOwner.isRecoveryAllowed() ||
            !isAttemptCurrent(activeRecovery, ResourceRecoveryPhase.RESETTING)
        ) {
            return
        }
        val diagnosticBefore = captureRecoveryDiagnostic(activeRecovery, before)

        val awaitingSuccessor = synchronized(lock) {
            gate.isAttemptCurrent(
                sourceRegistration.ownerId,
                attemptId,
                ResourceRecoveryPhase.RESETTING
            ) && gate.awaitSuccessor(sourceRegistration, attemptId)
        }
        if (!awaitingSuccessor) return

        val restartAllowed = VpnStateStore.tryConsumeResourceRecovery(
            VpnStateStore.ResourceRecoveryAction.CORE_RESTART
        )
        if (!isAttemptCurrent(activeRecovery, ResourceRecoveryPhase.AWAITING_SUCCESSOR)) return
        if (!restartAllowed) {
            recycleProcessIfAllowed(activeRecovery, activeOwner)
            return
        }
        if (!activeOwner.isRecoveryAllowed() ||
            !isAttemptCurrent(activeRecovery, ResourceRecoveryPhase.AWAITING_SUCCESSOR)
        ) {
            return
        }
        val restartIssued = activeOwner.restartCore(
            "resource_exhausted:${activeRecovery.reason}",
            attemptId
        )
        if (!isAttemptCurrent(activeRecovery)) return
        logRecovery("restart_core", diagnosticBefore?.fdCount, null, "issued=$restartIssued global_close=skipped")
        if (!restartIssued) {
            recycleProcessIfAllowed(activeRecovery, activeOwner)
            return
        }

        val successorSignalled = withTimeoutOrNull(RESOURCE_CORE_RESTART_SUCCESSOR_TIMEOUT_MS) {
            activeRecovery.successorSignal.await()
            true
        } == true
        if (!successorSignalled) {
            recycleProcessIfAllowed(activeRecovery, activeOwner)
            return
        }
        val successor = synchronized(lock) {
            if (!gate.isAttemptCurrent(sourceRegistration.ownerId, attemptId) ||
                !activeRecovery.successorResolved
            ) {
                return
            }
            activeRecovery.successorResult?.also { resolvedSuccessor ->
                if (!gate.isAttemptCurrent(
                        sourceRegistration.ownerId,
                        attemptId,
                        ResourceRecoveryPhase.OBSERVING_SUCCESSOR
                    ) || !gate.isCurrent(resolvedSuccessor.registration)
                ) {
                    return
                }
            }
        }
        if (successor == null) {
            recycleProcessIfAllowed(activeRecovery, activeOwner)
            return
        }
        if (!successor.owner.isRecoveryAllowed() || !isSuccessorCurrent(activeRecovery, successor)) return

        delay(RESOURCE_CORE_RESTART_OBSERVE_MS)
        if (!successor.owner.isRecoveryAllowed() || !isSuccessorCurrent(activeRecovery, successor)) {
            return
        }

        val afterRestart = runCatching {
            successor.sampler.captureCurrentProcess(includeFdBreakdown = true)
                .attachConnectionAttribution(successor.owner)
        }.getOrNull()
        if (!isSuccessorCurrent(activeRecovery, successor) || !successor.owner.isRecoveryAllowed()) {
            return
        }
        afterRestart?.let { sample ->
            runCatching { successor.history.append(sample) }
            logResourceDetails(sample)
        }
        if (!isSuccessorCurrent(activeRecovery, successor)) return
        if (!isFdRecoverySufficient(
                diagnosticBefore?.fdCount,
                afterRestart?.fdCount,
                afterRestart?.fdSoftLimit
            )
        ) {
            logRecovery(
                "restart_core",
                diagnosticBefore?.fdCount,
                afterRestart?.fdCount,
                "insufficient deferred_to_next_pressure"
            )
        } else {
            logRecovery("restart_core", diagnosticBefore?.fdCount, afterRestart?.fdCount, "success")
        }
    }

    private fun captureRecoveryDiagnostic(
        activeRecovery: ActiveResourceRecovery,
        fallback: DiagnosticResourceSample?
    ): DiagnosticResourceSample? {
        val sample = runCatching {
            activeRecovery.sampler.captureCurrentProcess(includeFdBreakdown = true)
                .attachConnectionAttribution(activeRecovery.owner)
        }.getOrNull() ?: fallback
        sample?.let { captured ->
            runCatching { activeRecovery.history.append(captured) }
            logResourceDetails(captured)
        }
        return sample
    }

    private fun recycleProcessIfAllowed(
        activeRecovery: ActiveResourceRecovery,
        activeOwner: ResourceGuardOwner
    ) {
        if (!activeOwner.isRecoveryAllowed()) return
        val claimed = synchronized(lock) {
            gate.claimProcessReclaim(activeRecovery.registration.ownerId, activeRecovery.attemptId)
        }
        if (!claimed) return
        val reclaimAllowed = VpnStateStore.tryConsumeResourceRecovery(
            VpnStateStore.ResourceRecoveryAction.PROCESS_RECLAIM
        )
        if (!isAttemptCurrent(activeRecovery, ResourceRecoveryPhase.RECLAIM_CLAIMED) ||
            !activeOwner.isRecoveryAllowed() ||
            !isAttemptCurrent(activeRecovery, ResourceRecoveryPhase.RECLAIM_CLAIMED)
        ) {
            return
        }
        val reason = activeRecovery.reason
        if (reclaimAllowed) {
            activeOwner.recycleProcess("resource_exhausted:$reason")
        } else if (claimBudgetExhaustedNotice(activeRecovery)) {
            activeOwner.publishBudgetExhausted("process_reclaim:$reason")
        }
    }

    private fun claimBudgetExhaustedNotice(activeRecovery: ActiveResourceRecovery): Boolean = synchronized(lock) {
        if (!gate.isAttemptCurrent(
                activeRecovery.registration.ownerId,
                activeRecovery.attemptId,
                ResourceRecoveryPhase.RECLAIM_CLAIMED
            )
        ) {
            return@synchronized false
        }
        if (noticeOwnerId !== activeRecovery.registration.ownerId) {
            noticeGate.clear()
            noticeOwnerId = activeRecovery.registration.ownerId
        }
        noticeGate.claim()
    }

    private fun finishRecovery(attemptId: Long) {
        synchronized(lock) {
            if (recovery?.attemptId != attemptId) return
            gate.finish(attemptId)
            recovery = null
            recovering = false
        }
    }

    private fun removeRecoveryLocked(attemptId: Long?): ActiveResourceRecovery? {
        if (attemptId == null) return null
        val activeRecovery = recovery?.takeIf { it.attemptId == attemptId } ?: return null
        recovery = null
        recovering = false
        return activeRecovery
    }

    private fun cancelRecoveryOutsideLock(activeRecovery: ActiveResourceRecovery?) {
        activeRecovery ?: return
        activeRecovery.successorSignal.cancel()
        activeRecovery.job?.cancel()
    }

    private fun isCurrent(registration: ResourceGuardRegistration): Boolean = synchronized(lock) {
        gate.isCurrent(registration)
    }

    private fun isAttemptCurrent(
        activeRecovery: ActiveResourceRecovery,
        phase: ResourceRecoveryPhase? = null
    ): Boolean = synchronized(lock) {
        gate.isAttemptCurrent(
            activeRecovery.registration.ownerId,
            activeRecovery.attemptId,
            phase
        )
    }

    private fun isSuccessorCurrent(
        activeRecovery: ActiveResourceRecovery,
        successor: ResourceRecoverySuccessor
    ): Boolean = synchronized(lock) {
        gate.isAttemptCurrent(
            activeRecovery.registration.ownerId,
            activeRecovery.attemptId,
            ResourceRecoveryPhase.OBSERVING_SUCCESSOR
        ) && gate.isCurrent(successor.registration)
    }

    private fun logSample(sample: DiagnosticResourceSample, decision: FdPressureDecision) {
        if (decision.level == FdPressureLevel.NORMAL) return
        val metric = "METRIC resource_fd process=${sample.processName.substringAfter(':', sample.processName)} " +
            "pid=${sample.pid} count=${sample.fdCount ?: -1} soft_limit=${sample.fdSoftLimit ?: -1} " +
            "ratio=${sample.fdRatio?.let { String.format(Locale.US, "%.3f", it) } ?: "unknown"} " +
            "level=${decision.level.name.lowercase(Locale.US)}"
        LogRepository.getInstance().addAlwaysLog(metric)
        logResourceDetails(sample)
    }

    private fun logResourceDetails(sample: DiagnosticResourceSample) {
        sample.fdBreakdown?.let { breakdown ->
            LogRepository.getInstance().addAlwaysLog(
                "METRIC resource_fd_breakdown socket=${breakdown.socketCount} " +
                    "unique_socket=${breakdown.socketUniqueCount} " +
                    "raw=${breakdown.rawCount + breakdown.raw6Count} " +
                    "udp=${breakdown.udpCount + breakdown.udp6Count} " +
                    "tcp=${breakdown.tcpCount + breakdown.tcp6Count} anon_inode=${breakdown.anonInodeCount} " +
                    "unix=${breakdown.unixCount} netlink=${breakdown.netlinkCount} " +
                    "packet=${breakdown.packetCount} socket_unknown=${breakdown.socketUnknownCount} " +
                    "fd_readlink_failures=${breakdown.fdReadlinkFailureCount} " +
                    "pipe=${breakdown.pipeCount} file=${breakdown.ordinaryFileCount} " +
                    "device=${breakdown.deviceCount} unknown=${breakdown.unknownCount} " +
                    "table_failures=${breakdown.socketTableFailures.ifBlank { "none" }}"
            )
        }
        buildSocketAttributionDiagnosticLines(sample).forEach { line ->
            LogRepository.getInstance().addAlwaysLog(line)
        }
    }

    private fun DiagnosticResourceSample.attachConnectionAttribution(
        activeOwner: ResourceGuardOwner
    ): DiagnosticResourceSample {
        val snapshot = runCatching(activeOwner::connectionAttributionSnapshot).getOrNull() ?: return this
        val nativeSocketCount = fdBreakdown?.socketUniqueCount
        val socketDelta = nativeSocketCount?.minus(snapshot.activeConnections)
        return copy(
            libboxActiveConnections = snapshot.activeConnections,
            nativeLibboxSocketDelta = socketDelta,
            nativePreConnectGap = socketDelta?.coerceAtLeast(0),
            socketAttributionStatus = classifySocketAttribution(nativeSocketCount, snapshot.activeConnections),
            connectionAttribution = snapshot
        )
    }

    private fun logRecovery(stage: String, before: Int?, after: Int?, result: String) {
        LogRepository.getInstance().addAlwaysLog(
            "WARN recovery resource_exhausted stage=$stage before=${before ?: -1} " +
                "after=${after ?: -1} result=$result"
        )
    }
}

internal const val RESOURCE_HISTORY_RELATIVE_PATH = "diagnostics/resource_history.csv"
internal const val MAX_BACKGROUND_RESOURCE_SAMPLES = 4_096
internal const val NATIVE_PRECONNECT_GAP_MINIMUM = 64
internal const val NATIVE_PRECONNECT_SOCKET_MINIMUM = 128
internal const val FD_NORMAL_SAMPLE_INTERVAL_MS = 1_000L
internal const val FD_OBSERVE_SAMPLE_INTERVAL_MS = 1_000L
internal const val FD_WARNING_SAMPLE_INTERVAL_MS = 1_000L
internal const val FD_FULL_SAMPLE_INTERVAL_MS = 60_000L
internal const val FD_BREAKDOWN_INTERVAL_MS = 5_000L
internal const val FD_GROWTH_WINDOW_MS = 5 * 60_000L
internal const val FD_FIVE_MINUTE_GROWTH_WARNING = 1_024
internal const val FD_RAPID_GROWTH_WINDOW_MS = 5_000L
internal const val FD_RAPID_GROWTH_THRESHOLD = 512
internal const val FD_RAPID_GROWTH_MIN_COUNT = 1_024
internal const val FD_RAPID_GROWTH_REQUIRED_SAMPLES = 3
internal const val FD_RAPID_GROWTH_RECOVERY_RATIO = 0.50
internal const val FD_ABSOLUTE_RECOVERY_COUNT = 16_384
internal const val FD_RECOVERY_COOLDOWN_MS = 10_000L
internal const val FD_OBSERVE_RATIO = 0.50
internal const val FD_WARNING_RATIO = 0.70
internal const val FD_RECOVERY_RATIO = 0.85
internal const val FD_EMERGENCY_RATIO = 0.95
internal const val RESOURCE_CORE_RESTART_OBSERVE_MS = 5_000L
internal const val RESOURCE_CORE_RESTART_SUCCESSOR_TIMEOUT_MS = 30_000L
internal const val RESOURCE_RECOVERY_BUDGET_HEALTHY_RESET_MS = 5 * 60_000L
internal const val RESOURCE_RECOVERY_ERROR_HEALTHY_CLEAR_MS = 10_000L
internal const val RESOURCE_RECOVERY_BUDGET_ERROR_PREFIX = "Resource recovery budget exhausted:"

internal val CSV_SPECIAL_CHARACTERS = setOf(',', '"', '\n', '\r')
