@file:Suppress("TooManyFunctions", "Indentation", "InvalidPackageDeclaration")

package com.kunk.singbox.service.root

import android.os.Binder
import android.os.Process
import android.util.Log
import com.kunk.singbox.model.RootAppRoutingPlan
import com.kunk.singbox.model.RootAppRoutingCanonical
import com.kunk.singbox.model.RootRoutingArtifactValidator
import com.kunk.singbox.model.isRootSha256
import com.kunk.singbox.repository.RootGenerationStore
import com.kunk.singbox.repository.*
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.SystemProxyStatus
import com.google.gson.Gson
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun KunBoxRootService.installUidRefreshGuard(
    request: RootStartRequest,
    resolved: RootResolvedRouting,
    plan: RootAppRoutingPlan,
    candidate: RootResolvedRouting? = null
) {
    if (netfilterManager.hasGuard()) return
    val currentConfig = buildNetfilterConfig(request, resolved, plan)
    val candidateConfig = candidate?.let { buildNetfilterConfig(request, it, plan) }
    val applicationRanges = runCatching { RootUidResolver().applicationUidRanges() }
        .getOrElse { listOf(RootUidRange(10_000, Int.MAX_VALUE)) }
    netfilterManager.installGuard(
        RootFailClosedConfig(
            capturedUids = (currentConfig.capturedUids + candidateConfig?.capturedUids.orEmpty())
                .distinct()
                .sorted(),
            capturedUidRanges = (
                currentConfig.capturedUidRanges +
                    candidateConfig?.capturedUidRanges.orEmpty() +
                    applicationRanges
                ).distinct(),
            excludedUids = listOf(request.appUid),
            appUid = request.appUid,
            ipv4 = currentConfig.proxyIpv4 || currentConfig.blockIpv4 ||
                (candidateConfig?.proxyIpv4 == true) || (candidateConfig?.blockIpv4 == true),
            ipv6 = currentConfig.proxyIpv6 || currentConfig.blockIpv6 ||
                (candidateConfig?.proxyIpv6 == true) || (candidateConfig?.blockIpv6 == true)
        )
    ).getOrThrow()
}

@Suppress("CognitiveComplexMethod")
internal fun KunBoxRootService.startUidMonitor(runtimeSessionId: String) {
    uidMonitorJob?.cancel()
    uidMonitorJob = serviceScope.launch {
        while (currentCoroutineContext().isActive) {
            delay(KunBoxRootService.UID_REFRESH_INTERVAL_MS)
            val keepMonitoring = synchronized(runtimeLock) {
                if (!matchesRunningSession(runtimeSessionId)) return@synchronized false
                val request = activeStartRequest ?: return@synchronized false
                val artifacts = activeRoutingArtifacts ?: return@synchronized false
                val current = activeResolvedRouting ?: return@synchronized false
                val resolver = RootUidResolver()
                val currentResolved = runCatching {
                    resolver.resolveRouting(
                        artifacts.plan,
                        request.selfPackage,
                        request.appUid
                    )
                }.getOrElse { error ->
                    Log.w(KunBoxRootService.TAG, "Root UID monitor could not confirm the current snapshot", error)
                    null
                }
                val changed = currentResolved == null ||
                    currentResolved.resolvedPlanSha256 != current.resolvedPlanSha256
                if (changed) {
                    runCatching {
                        installUidRefreshGuard(request, current, artifacts.plan, currentResolved)
                    }
                        .onSuccess {
                            updateSnapshot(
                                phase = RootRuntimePhase.FAIL_CLOSED,
                                error = "Root UID snapshot changed; refresh required",
                                watchdogReady = true,
                                rulesInstalled = true
                            )
                        }
                        .onFailure { error ->
                            Log.e(KunBoxRootService.TAG, "Root UID monitor could not install fail-closed guard", error)
                            failUidMonitorSafely(error.message ?: "Root UID refresh guard failed")
                        }
                }
                !changed
            }
            if (!keepMonitoring) break
        }
    }
}

internal fun KunBoxRootService.failUidMonitorSafely(message: String) {
    stopUidMonitor()
    val artifacts = activeRoutingArtifacts
    runCatching { closeCommandServer(artifacts?.plan) }
    val cleanupError = cleanupRulesVerified()
    if (cleanupError == null) {
        failUnprotected(message)
    } else {
        failCleanup(cleanupError, "$message; cleanup failed: ${cleanupError.message}")
    }
}

internal fun KunBoxRootService.stopUidMonitor() {
    uidMonitorJob?.cancel()
    uidMonitorJob = null
}

@Suppress(
    "LongParameterList",
    "LongMethod",
    "CognitiveComplexMethod",
    "NestedBlockDepth",
    "ReturnCount"
)
internal fun KunBoxRootService.hotReloadLocked(
    configPath: String,
    runtimeSessionId: String,
    configFileSha256: String,
    sidecarFileSha256: String,
    sidecarJson: String,
    staticPlanSha256: String,
    appRoutingSha256: String,
    routingGeneration: Long
): RootRuntimeSnapshot {
    val reloadStartedAt = android.os.SystemClock.elapsedRealtime()
    if (!matchesRunningSession(runtimeSessionId)) return snapshot
    val previousRequest = activeStartRequest ?: return failRulesPresent("Root active request is unavailable")
    val previousArtifacts = activeRoutingArtifacts
        ?: return failRulesPresent("Root active artifacts are unavailable")
    val previousResolved = activeResolvedRouting
        ?: return failRulesPresent("Root active UID snapshot is unavailable")
    val previousPlan = activeNetfilterPlan
        ?: return failRulesPresent("Root active netfilter plan is unavailable")
    var guardInstalled = false
    var oldCoreStopped = false
    var inPlaceReloadAttempted = false
    var candidateArtifacts: RootRoutingArtifacts? = null
    return try {
        throwIfStopRequested(runtimeSessionId)
        val provisionalRequest = previousRequest.copy(
            configPath = configPath,
            runtimeSessionId = runtimeSessionId,
            configFileSha256 = configFileSha256,
            sidecarFileSha256 = sidecarFileSha256,
            sidecarJson = sidecarJson,
            staticPlanSha256 = staticPlanSha256,
            appRoutingSha256 = appRoutingSha256,
            routingGeneration = routingGeneration
        )
        val artifacts = readValidatedArtifacts(provisionalRequest)
        throwIfStopRequested(runtimeSessionId)
        candidateArtifacts = artifacts
        check(artifacts.plan.generation > previousArtifacts.plan.generation) {
            "Root hot reload generation is not newer than the active generation"
        }
        val request = provisionalRequest.copy(
            appMode = artifacts.plan.vpnAppMode,
            allowlist = artifacts.plan.allowlist.toSet(),
            blocklist = artifacts.plan.blocklist.toSet(),
            proxyIpv4 = artifacts.plan.proxyIpv4,
            proxyIpv6 = artifacts.plan.proxyIpv6,
            blockIpv4 = !artifacts.plan.proxyIpv4,
            blockIpv6 = !artifacts.plan.proxyIpv6
        )
        val previousNetfilterConfig = buildNetfilterConfig(previousRequest, previousResolved, previousArtifacts.plan)
        val candidateNetfilterConfig = buildNetfilterConfig(request, previousResolved, artifacts.plan)
        if (artifacts.plan.appRoutingSha256 == previousArtifacts.plan.appRoutingSha256 &&
            candidateNetfilterConfig == previousNetfilterConfig
        ) {
            inPlaceReloadAttempted = true
            reloadCommandServer(artifacts)
            val reloadedResolved = previousResolved.copy(
                resolvedPlanSha256 = RootAppRoutingCanonical.resolvedPlanSha256(
                    artifacts.plan,
                    previousResolved.routes
                )
            )
            activeRoutingArtifacts = artifacts
            activeResolvedRouting = reloadedResolved
            activeStartRequest = request
            logResolvedRouting(artifacts.plan, reloadedResolved)
            val runningSnapshot = updateSnapshot(
                phase = RootRuntimePhase.RUNNING,
                ruleRevision = snapshot.ruleRevision + 1,
                routingGeneration = artifacts.plan.generation,
                configFileSha256 = artifacts.plan.configFileSha256,
                sidecarFileSha256 = request.sidecarFileSha256,
                staticPlanSha256 = artifacts.plan.staticPlanSha256,
                appRoutingSha256 = artifacts.plan.appRoutingSha256,
                resolvedPlanSha256 = reloadedResolved.resolvedPlanSha256,
                resolvedUidCount = reloadedResolved.routes.size,
                watchdogReady = true,
                rulesInstalled = true,
                error = ""
            )
            Log.i(
                KunBoxRootService.TAG,
                "[ROOT_RELOAD] strategy=in_place duration_ms=" +
                    (android.os.SystemClock.elapsedRealtime() - reloadStartedAt)
            )
            runningSnapshot
        } else {
        inPlaceReloadAttempted = false
        val resolver = RootUidResolver()
        val uidSnapshot = resolver.captureSnapshot()
        val firstResolved = resolver.resolveRouting(artifacts.plan, request.selfPackage, request.appUid, uidSnapshot)
        throwIfStopRequested(runtimeSessionId)
        netfilterManager.beginOwnership(
            RootNetfilterOwnership.context(
                request.runtimeSessionId,
                artifacts.plan.generation,
                firstResolved.resolvedPlanSha256
            )
        ).getOrThrow()
        val previousConfig = buildNetfilterConfig(previousRequest, previousResolved, previousArtifacts.plan)
        val candidateConfig = buildNetfilterConfig(request, firstResolved, artifacts.plan)
        netfilterManager.installGuard(unionGuardConfig(previousConfig, candidateConfig)).getOrThrow()
        guardInstalled = true
        throwIfStopRequested(runtimeSessionId)
        updateSnapshot(
            phase = RootRuntimePhase.FAIL_CLOSED,
            watchdogReady = true,
            rulesInstalled = true,
            error = ""
        )
        oldCoreStopped = true
        closeCommandServer(previousArtifacts.plan)
        netfilterManager.cleanupActivePlanKeepingGuard().getOrThrow()
        throwIfStopRequested(runtimeSessionId)
        val candidatePlan = netfilterManager.stage(candidateConfig).getOrThrow()
        throwIfStopRequested(runtimeSessionId)
        activeNetfilterPlan = candidatePlan
        updateSnapshot(phase = RootRuntimePhase.CORE_STARTING)
        startCommandServer(request, artifacts)
        throwIfStopRequested(runtimeSessionId)
        updateSnapshot(phase = RootRuntimePhase.CORE_VERIFYING)
        verifyAllLaneListeners(artifacts.plan)
        throwIfStopRequested(runtimeSessionId)
        val secondResolved = resolver.resolveRouting(artifacts.plan, request.selfPackage, request.appUid, uidSnapshot)
        check(firstResolved.resolvedPlanSha256 == secondResolved.resolvedPlanSha256) {
            "Root UID snapshot changed during cold reload"
        }
        throwIfStopRequested(runtimeSessionId)
        netfilterManager.activateAndRemoveGuard(candidatePlan).getOrThrow()
        guardInstalled = false
        throwIfStopRequested(runtimeSessionId)
        activeRoutingArtifacts = artifacts
        activeResolvedRouting = secondResolved
        activeStartRequest = request
        logResolvedRouting(artifacts.plan, secondResolved)
        startUidMonitor(request.runtimeSessionId)
        pruneLockedArtifacts(artifacts.plan.generation)
        val runningSnapshot = updateSnapshot(
            phase = RootRuntimePhase.RUNNING,
            ruleRevision = snapshot.ruleRevision + 1,
            routingGeneration = artifacts.plan.generation,
            configFileSha256 = artifacts.plan.configFileSha256,
            sidecarFileSha256 = request.sidecarFileSha256,
            staticPlanSha256 = artifacts.plan.staticPlanSha256,
            appRoutingSha256 = artifacts.plan.appRoutingSha256,
            resolvedPlanSha256 = secondResolved.resolvedPlanSha256,
            resolvedUidCount = secondResolved.routes.size,
            watchdogReady = true,
            rulesInstalled = true,
            error = ""
        )
        Log.i(
            KunBoxRootService.TAG,
            "[ROOT_RELOAD] strategy=full duration_ms=" +
                (android.os.SystemClock.elapsedRealtime() - reloadStartedAt)
        )
        runningSnapshot
        }
    } catch (error: Exception) {
        Log.e(KunBoxRootService.TAG, "Root cold reload failed", error)
        if (stopWasRequested(runtimeSessionId, error)) {
            val cleanupError = rollbackLocked()
            if (cleanupError == null) markStopped() else failCleanup(
                cleanupError,
                "Root stop cleanup failed: ${cleanupError.message}"
            )
        } else {
            if (inPlaceReloadAttempted && !oldCoreStopped) {
                val rollbackError = runCatching { reloadCommandServer(previousArtifacts) }.exceptionOrNull()
                if (rollbackError != null) {
                    Log.e(KunBoxRootService.TAG, "Root in-place reload rollback failed", rollbackError)
                    runCatching { closeCommandServer(candidateArtifacts?.plan) }
                    oldCoreStopped = true
                }
            }
            restorePreviousAfterReload(
                previousRequest = previousRequest,
                previousArtifacts = previousArtifacts,
                previousResolved = previousResolved,
                previousPlan = previousPlan,
                oldCoreStopped = oldCoreStopped,
                guardInstalled = guardInstalled,
                candidateArtifacts = candidateArtifacts,
                reloadError = error
            )
        }
    }
}

internal fun KunBoxRootService.unionGuardConfig(
    previous: RootNetfilterConfig,
    candidate: RootNetfilterConfig
): RootFailClosedConfig {
    val previousExcluded = previous.excludedUids.toSet()
    val candidateExcluded = candidate.excludedUids.toSet()
    return RootFailClosedConfig(
        capturedUids = (previous.capturedUids + candidate.capturedUids).distinct().sorted(),
        capturedUidRanges = (previous.capturedUidRanges + candidate.capturedUidRanges).distinct(),
        excludedUids = previousExcluded.intersect(candidateExcluded).sorted(),
        appUid = previous.appUid,
        ipv4 = previous.proxyIpv4 || previous.blockIpv4 || candidate.proxyIpv4 || candidate.blockIpv4,
        ipv6 = previous.proxyIpv6 || previous.blockIpv6 || candidate.proxyIpv6 || candidate.blockIpv6
    )
}

@Suppress("LongParameterList", "LongMethod")
internal fun KunBoxRootService.restorePreviousAfterReload(
    previousRequest: RootStartRequest,
    previousArtifacts: RootRoutingArtifacts,
    previousResolved: RootResolvedRouting,
    previousPlan: RootNetfilterPlan,
    oldCoreStopped: Boolean,
    guardInstalled: Boolean,
    candidateArtifacts: RootRoutingArtifacts?,
    reloadError: Exception
): RootRuntimeSnapshot {
    val errorMessage = reloadError.message ?: "Root cold reload failed"
    if (!oldCoreStopped) {
        return runCatching {
            netfilterManager.beginOwnership(
                RootNetfilterOwnership.context(
                    previousRequest.runtimeSessionId,
                    previousArtifacts.plan.generation,
                    previousResolved.resolvedPlanSha256
                )
            ).getOrThrow()
            if (guardInstalled) netfilterManager.removeGuard().getOrThrow()
            activeNetfilterPlan = previousPlan
            activeRoutingArtifacts = previousArtifacts
            activeResolvedRouting = previousResolved
            activeStartRequest = previousRequest
            updateSnapshot(
                phase = RootRuntimePhase.RUNNING,
                routingGeneration = previousArtifacts.plan.generation,
                configFileSha256 = previousArtifacts.plan.configFileSha256,
                sidecarFileSha256 = previousRequest.sidecarFileSha256,
                staticPlanSha256 = previousArtifacts.plan.staticPlanSha256,
                appRoutingSha256 = previousArtifacts.plan.appRoutingSha256,
                resolvedPlanSha256 = previousResolved.resolvedPlanSha256,
                resolvedUidCount = previousResolved.routes.size,
                watchdogReady = true,
                rulesInstalled = true,
                error = errorMessage
            )
        }.getOrElse { restoreError ->
            failRulesPresent("$errorMessage; guard recovery failed: ${restoreError.message}")
        }
    }
    return try {
        closeCommandServer(candidateArtifacts?.plan)
        netfilterManager.cleanupActivePlanKeepingGuard().getOrThrow()
        netfilterManager.beginOwnership(
            RootNetfilterOwnership.context(
                previousRequest.runtimeSessionId,
                previousArtifacts.plan.generation,
                previousResolved.resolvedPlanSha256
            )
        ).getOrThrow()
        val previousConfig = buildNetfilterConfig(previousRequest, previousResolved, previousArtifacts.plan)
        val restoredPlan = netfilterManager.stage(previousConfig).getOrThrow()
        startCommandServer(previousRequest, previousArtifacts)
        verifyAllLaneListeners(previousArtifacts.plan)
        val verifiedResolved = RootUidResolver().resolveRouting(
            previousArtifacts.plan,
            previousRequest.selfPackage,
            previousRequest.appUid
        )
        check(verifiedResolved.resolvedPlanSha256 == previousResolved.resolvedPlanSha256) {
            "Root UID snapshot changed while restoring the previous generation"
        }
        netfilterManager.activateAndRemoveGuard(restoredPlan).getOrThrow()
        activeNetfilterPlan = restoredPlan
        activeRoutingArtifacts = previousArtifacts
        activeResolvedRouting = verifiedResolved
        activeStartRequest = previousRequest
        logResolvedRouting(previousArtifacts.plan, verifiedResolved)
        startUidMonitor(previousRequest.runtimeSessionId)
        pruneLockedArtifacts(previousArtifacts.plan.generation)
        updateSnapshot(
            phase = RootRuntimePhase.RUNNING,
            ruleRevision = snapshot.ruleRevision + 1,
            routingGeneration = previousArtifacts.plan.generation,
            configFileSha256 = previousArtifacts.plan.configFileSha256,
            sidecarFileSha256 = previousRequest.sidecarFileSha256,
            staticPlanSha256 = previousArtifacts.plan.staticPlanSha256,
            appRoutingSha256 = previousArtifacts.plan.appRoutingSha256,
            resolvedPlanSha256 = verifiedResolved.resolvedPlanSha256,
            resolvedUidCount = verifiedResolved.routes.size,
            watchdogReady = true,
            rulesInstalled = true,
            error = errorMessage
        )
    } catch (restoreError: Exception) {
        Log.e(KunBoxRootService.TAG, "Previous Root generation rollback failed", restoreError)
        val cleanupError = rollbackLocked()
        if (cleanupError == null) {
            failUnprotected("$errorMessage; rollback failed: ${restoreError.message}")
        } else {
            failCleanup(
                cleanupError,
                "$errorMessage; rollback failed: ${restoreError.message}; " +
                    "cleanup failed: ${cleanupError.message}"
            )
        }
    }
}

internal fun KunBoxRootService.stopLocked(runtimeSessionId: String): RootRuntimeSnapshot {
    stopUidMonitor()
    if (
        runtimeSessionId.isNotBlank() &&
        snapshot.runtimeSessionId.isNotBlank() &&
        runtimeSessionId != snapshot.runtimeSessionId
    ) {
        return snapshot
    }
    updateSnapshot(phase = RootRuntimePhase.CLEANING)
    val commandError = runCatching { closeCommandServer(activeRoutingArtifacts?.plan) }.exceptionOrNull()
    val cleanupError = cleanupRulesVerified()
    resourceGuard?.stop()
    return if (cleanupError == null && commandError == null) {
        stopRequestedSession.set("")
        markStopped()
    } else if (cleanupError != null) {
        failCleanup(cleanupError, cleanupError.message ?: "Root cleanup failed")
    } else {
        updateSnapshot(
            phase = RootRuntimePhase.FAILED_UNPROTECTED,
            error = commandError?.message ?: "Root CommandServer shutdown failed",
            rulesInstalled = false,
            watchdogReady = false
        )
    }
}

internal fun KunBoxRootService.rollbackLocked(): Throwable? {
    stopUidMonitor()
    val commandError = runCatching { closeCommandServer(activeRoutingArtifacts?.plan) }.exceptionOrNull()
    val cleanupError = cleanupRulesVerified()
    activeNetfilterPlan = null
    activeRoutingArtifacts = null
    activeResolvedRouting = null
    activeStartRequest = null
    resourceGuard?.stop()
    return cleanupError ?: commandError
}

internal fun KunBoxRootService.markStopped(): RootRuntimeSnapshot {
    stopRequestedSession.set("")
    activeNetfilterPlan = null
    activeRoutingArtifacts = null
    activeResolvedRouting = null
    activeStartRequest = null
    snapshot = RootRuntimeSnapshot(
        phase = RootRuntimePhase.STOPPED,
        generation = snapshot.generation + 1,
        rootPid = Process.myPid(),
        tproxyIpv4 = capabilityReport.tproxyIpv4,
        tproxyIpv6 = capabilityReport.tproxyIpv6
    )
    return snapshot
}

internal fun KunBoxRootService.handleWatchdogLost(runtimeSessionId: String) {
    synchronized(runtimeLock) {
        if (!matchesRunningSession(runtimeSessionId)) return
        stopUidMonitor()
        runCatching { closeCommandServer(activeRoutingArtifacts?.plan) }
        val cleanupError = cleanupRulesVerified()
        resourceGuard?.stop()
        if (cleanupError == null) {
            updateSnapshot(
                phase = RootRuntimePhase.FAILED_UNPROTECTED,
                error = "Root watchdog lease lost",
                rulesInstalled = false,
                watchdogReady = false
            )
        } else {
            failCleanup(cleanupError, cleanupError.message ?: "Root watchdog cleanup failed")
        }
    }
}

internal fun KunBoxRootService.handleIpv6PrivacyLost(runtimeSessionId: String, error: Throwable) {
    synchronized(runtimeLock) {
        if (!matchesRunningSession(runtimeSessionId)) return
        Log.e(KunBoxRootService.TAG, "Root IPv6 privacy guard failed", error)
        val stopped = stopLocked(runtimeSessionId)
        if (stopped.phase == RootRuntimePhase.STOPPED) {
            updateSnapshot(
                phase = RootRuntimePhase.FAILED_UNPROTECTED,
                error = "Root IPv6 privacy guard failed: ${error.message}",
                rulesInstalled = false,
                watchdogReady = false
            )
        }
    }
}

internal fun KunBoxRootService.matchesRunningSession(runtimeSessionId: String): Boolean =
    runtimeSessionId.isNotBlank() &&
        runtimeSessionId == snapshot.runtimeSessionId &&
        snapshot.phase == RootRuntimePhase.RUNNING

internal fun KunBoxRootService.startResourceGuard(runtimeSessionId: String) {
    resourceGuard?.close()
    resourceGuard = RootResourceGuard { fdCount, action ->
        val privacyError = ipv6PrivacyGuard.enforce().exceptionOrNull()
        if (privacyError != null) {
            handleIpv6PrivacyLost(runtimeSessionId, privacyError)
        } else {
            handleRootResourceSample(runtimeSessionId, fdCount, action)
        }
    }.also(RootResourceGuard::start)
}

internal fun KunBoxRootService.handleRootResourceSample(
    runtimeSessionId: String,
    fdCount: Int,
    action: RootResourceAction
) {
    synchronized(runtimeLock) {
        if (snapshot.runtimeSessionId != runtimeSessionId) return
        snapshot = snapshot.copy(rootFdCount = fdCount, generation = snapshot.generation + 1)
        if (action != RootResourceAction.STOP) return
        stopUidMonitor()
        runCatching { closeCommandServer(activeRoutingArtifacts?.plan) }
        val cleanupError = cleanupRulesVerified()
        resourceGuard?.stop()
        if (cleanupError == null) {
            updateSnapshot(
                phase = RootRuntimePhase.FAILED_UNPROTECTED,
                error = "Root file descriptor safety limit reached: $fdCount",
                rulesInstalled = false
            )
        } else {
            failCleanup(cleanupError, cleanupError.message ?: "Root resource cleanup failed")
        }
    }
}

@Suppress("LongMethod")
internal fun KunBoxRootService.readValidatedArtifacts(request: RootStartRequest): RootRoutingArtifacts {
    check(isRootSha256(request.configFileSha256)) { "Root config digest is missing or malformed" }
    check(isRootSha256(request.sidecarFileSha256)) { "Root sidecar digest is missing or malformed" }
    check(isRootSha256(request.staticPlanSha256)) { "Root static plan digest is missing or malformed" }
    check(isRootSha256(request.appRoutingSha256)) { "Root app routing digest is missing or malformed" }
    check(request.routingGeneration > 0L) { "Root routing generation is missing" }
    val rawConfigFile = File(request.configPath)
    validateGenerationConfigPath(rawConfigFile, request.routingGeneration)
    val configFile = rawConfigFile.canonicalFile
    check(configFile.isFile) { "Root config does not exist" }
    val configBytes = configFile.readBytes()
    val configContent = configBytes.toString(StandardCharsets.UTF_8)
    check(RootAppRoutingCanonical.sha256(configBytes) == request.configFileSha256) {
        "Root config file digest mismatch"
    }
    val sidecarFile = File(configFile.parentFile, "root-routing.json")
    val manifestFile = File(configFile.parentFile, "manifest.json")
    check(!Files.isSymbolicLink(sidecarFile.toPath()) && !Files.isSymbolicLink(manifestFile.toPath())) {
        "Root routing artifacts cannot be symbolic links"
    }
    check(sidecarFile.isFile && manifestFile.isFile) { "Root routing artifacts are incomplete" }
    val sidecarBytes = sidecarFile.readBytes()
    val sidecarJson = sidecarBytes.toString(StandardCharsets.UTF_8)
    check(sidecarJson == request.sidecarJson) { "Root sidecar AIDL bytes mismatch" }
    check(RootAppRoutingCanonical.sha256(sidecarBytes) == request.sidecarFileSha256) {
        "Root sidecar file digest mismatch"
    }
    val plan = parseRootRoutingPlan(sidecarJson)
    check(plan.generation == request.routingGeneration) { "Root routing generation mismatch" }
    check(plan.configFileSha256 == request.configFileSha256) { "Root plan config digest mismatch" }
    check(plan.staticPlanSha256 == request.staticPlanSha256) { "Root plan static digest mismatch" }
    check(plan.appRoutingSha256 == request.appRoutingSha256) { "Root plan app digest mismatch" }
    val manifestBytes = manifestFile.readBytes()
    val manifestJson = manifestBytes.toString(Charsets.UTF_8)
    val manifest = RootRoutingArtifactValidator.requireManifestJson(manifestJson)
    check(manifest.generation == request.routingGeneration) { "Root manifest generation mismatch" }
    check(manifest.configLength == configBytes.size.toLong()) { "Root manifest config length mismatch" }
    check(manifest.configFileSha256 == request.configFileSha256) { "Root manifest config digest mismatch" }
    check(manifest.sidecarLength == sidecarBytes.size.toLong()) { "Root manifest sidecar length mismatch" }
    check(manifest.sidecarFileSha256 == request.sidecarFileSha256) { "Root manifest sidecar digest mismatch" }
    check(manifest.staticPlanSha256 == request.staticPlanSha256) { "Root manifest static digest mismatch" }
    check(manifest.appRoutingSha256 == request.appRoutingSha256) { "Root manifest app digest mismatch" }
    val config = Gson().fromJson(configContent, com.kunk.singbox.model.SingBoxConfig::class.java)
        ?: error("Root config JSON is empty")
    val runtimeTags = config.outbounds.orEmpty().mapTo(mutableSetOf()) { it.tag } +
        config.endpoints.orEmpty().map { it.tag }
    val missingOutboundTags = plan.lanes
        .filter { it.targetKind == "OUTBOUND" && it.outboundTag !in runtimeTags }
        .map { it.outboundTag }
        .distinct()
        .sorted()
    Log.i(
        KunBoxRootService.TAG,
        "[ROOT_GENERATION] generation=${plan.generation} phase=validate " +
            "outboundCount=${runtimeTags.size} laneCount=${plan.lanes.size} " +
            "missingOutboundTags=${missingOutboundTags.joinToString(",")}"
    )
    com.kunk.singbox.repository.ConfigRepository.requireValidRootApplicationRoutes(config, plan)
    val locked = artifactSnapshotStore.lock(
        generation = plan.generation,
        configBytes = configBytes,
        sidecarBytes = sidecarBytes,
        manifestBytes = manifestBytes,
        configFileSha256 = request.configFileSha256,
        sidecarFileSha256 = request.sidecarFileSha256
    )
    return RootRoutingArtifacts(
        configContent = locked.configBytes.toString(StandardCharsets.UTF_8),
        configBytes = locked.configBytes,
        sidecarJson = locked.sidecarBytes.toString(StandardCharsets.UTF_8),
        sidecarBytes = locked.sidecarBytes,
        manifestBytes = locked.manifestBytes,
        plan = plan,
        manifest = manifest
    )
}

internal fun KunBoxRootService.validateGenerationConfigPath(configFile: File, generation: Long) {
    val filesRoot = filesDir.canonicalFile
    val generations = File(filesRoot, RootGenerationStore.GENERATIONS_DIR_NAME)
    val generationDirectory = File(generations, "generation_$generation")
    listOf(filesDir, generations, generationDirectory, configFile).forEach { file ->
        check(!Files.isSymbolicLink(file.toPath())) {
            "Root routing artifact path cannot be a symbolic link"
        }
    }
    configFile.parentFile?.let { parent ->
        check(!Files.isSymbolicLink(parent.toPath())) {
            "Root routing artifact directory cannot be a symbolic link"
        }
    }
    val expectedDirectory = RootGenerationStore.generationDirectory(filesRoot, generation).canonicalFile
    check(configFile.canonicalFile == File(expectedDirectory, "config.json").canonicalFile) {
        "Root config must come from its committed generation directory"
    }
}

internal fun KunBoxRootService.parseRootRoutingPlan(raw: String): RootAppRoutingPlan {
    return RootRoutingArtifactValidator.requireBoundPlanJson(raw)
}

internal fun KunBoxRootService.createServerHandler(): CommandServerHandler = object : CommandServerHandler {
    override fun serviceStop() {
        serviceScope.launch {
            synchronized(runtimeLock) { stopLocked(snapshot.runtimeSessionId) }
        }
    }

    override fun serviceReload() = Unit

    override fun getSystemProxyStatus(): SystemProxyStatus? = null

    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit

    override fun writeDebugMessage(message: String?) {
        if (!message.isNullOrBlank()) Log.d(KunBoxRootService.TAG, message)
    }
}

internal fun KunBoxRootService.failUnprotected(message: String): RootRuntimeSnapshot = updateSnapshot(
    phase = RootRuntimePhase.FAILED_UNPROTECTED,
    error = message,
    rulesInstalled = false,
    watchdogReady = false,
    startupTimings = encodeStartupTimings()
)

internal fun KunBoxRootService.failRulesPresent(message: String): RootRuntimeSnapshot = updateSnapshot(
    phase = RootRuntimePhase.FAILED_BLOCKED,
    error = message,
    rulesInstalled = true,
    watchdogReady = false,
    startupTimings = encodeStartupTimings()
)

internal fun KunBoxRootService.failCleanup(error: Throwable, message: String): RootRuntimeSnapshot =
    if (error.isNetfilterVerificationFailure()) {
        updateSnapshot(
            phase = RootRuntimePhase.FAILED_VERIFICATION,
            error = message,
            rulesInstalled = false,
            watchdogReady = false,
            startupTimings = encodeStartupTimings()
        )
    } else {
        failRulesPresent(message)
    }

internal fun Throwable.isNetfilterVerificationFailure(): Boolean =
    generateSequence(this) { it.cause }
        .any { it.message.orEmpty().contains("NETFILTER_VERIFICATION_FAILED") }

internal fun KunBoxRootService.cleanupRulesVerified(): Throwable? {
    val currentWatchdog = watchdog
    val cleanupError = netfilterManager.cleanup().exceptionOrNull()
    if (cleanupError != null) {
        val event = if (cleanupError.isNetfilterVerificationFailure()) {
            "cleanup_verification_failed"
        } else {
            "cleanup_harmful_state"
        }
        Log.e(KunBoxRootService.TAG, "[ROOT_NET] event=$event reason=${cleanupError.message}")
        // Keep the external watchdog alive when ownership cleanup is not
        // confirmed. Its stale-lease path is the last fail-closed guard.
        return cleanupError
    }
    val privacyRestoreError = ipv6PrivacyGuard.restore().exceptionOrNull()
    if (privacyRestoreError != null) {
        Log.e(KunBoxRootService.TAG, "Root IPv6 privacy state could not be restored", privacyRestoreError)
        return privacyRestoreError
    }
    val watchdogStopError = currentWatchdog?.stop(cleanupRules = false)?.exceptionOrNull()
    if (watchdogStopError != null) {
        Log.w(KunBoxRootService.TAG, "Root watchdog did not acknowledge stop after network cleanup", watchdogStopError)
    }
    currentWatchdog?.close()
    if (watchdog === currentWatchdog) watchdog = null
    netfilterOwned = false
    Log.i(KunBoxRootService.TAG, "[ROOT_NET] event=cleanup_verified remainingOwnedRules=0")
    return null
}

internal fun KunBoxRootService.updateSnapshot(
    phase: RootRuntimePhase,
    runtimeSessionId: String = snapshot.runtimeSessionId,
    ruleRevision: Long = snapshot.ruleRevision,
    routingGeneration: Long = snapshot.routingGeneration,
    tproxyIpv4: Boolean = snapshot.tproxyIpv4,
    tproxyIpv6: Boolean = snapshot.tproxyIpv6,
    configFileSha256: String = snapshot.configFileSha256,
    sidecarFileSha256: String = snapshot.sidecarFileSha256,
    staticPlanSha256: String = snapshot.staticPlanSha256,
    appRoutingSha256: String = snapshot.appRoutingSha256,
    resolvedPlanSha256: String = snapshot.resolvedPlanSha256,
    resolvedUidCount: Int = snapshot.resolvedUidCount,
    watchdogReady: Boolean = snapshot.watchdogReady,
    rulesInstalled: Boolean = snapshot.rulesInstalled,
    error: String = snapshot.error,
    startupTimings: String = snapshot.startupTimings
): RootRuntimeSnapshot {
    snapshot = snapshot.copy(
        phase = phase,
        runtimeSessionId = runtimeSessionId,
        generation = snapshot.generation + 1,
        ruleRevision = ruleRevision,
        routingGeneration = routingGeneration,
        rootPid = Process.myPid(),
        tproxyIpv4 = tproxyIpv4,
        tproxyIpv6 = tproxyIpv6,
        configFileSha256 = configFileSha256,
        sidecarFileSha256 = sidecarFileSha256,
        staticPlanSha256 = staticPlanSha256,
        appRoutingSha256 = appRoutingSha256,
        resolvedPlanSha256 = resolvedPlanSha256,
        resolvedUidCount = resolvedUidCount,
        watchdogReady = watchdogReady,
        rulesInstalled = rulesInstalled,
        error = error,
        startupTimings = startupTimings
    )
    return snapshot
}

internal fun KunBoxRootService.enforceCaller() {
    val callerUid = Binder.getCallingUid()
    check(callerUid == applicationInfo.uid) { "Unauthorized RootService caller UID: $callerUid" }
}
