@file:Suppress("TooManyFunctions")

package com.kunk.singbox.service.root

import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.ParcelFileDescriptor
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.kunk.singbox.aidl.IRootSingBoxService
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.model.RootAppRoutingPlan
import com.kunk.singbox.model.RootRoutingManifest
import com.kunk.singbox.repository.config.InboundBuilder
import com.topjohnwu.superuser.ipc.RootService
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job

internal data class RootStartRequest(
    val configPath: String,
    val runtimeSessionId: String,
    val appMode: String,
    val allowlist: Set<String>,
    val blocklist: Set<String>,
    val selfPackage: String,
    val forceConnectionOwnerRouting: Boolean,
    val appUid: Int,
    val proxyIpv4: Boolean,
    val proxyIpv6: Boolean,
    val blockIpv4: Boolean,
    val blockIpv6: Boolean,
    val blockQuic: Boolean,
    val apkPath: String,
    val configFileSha256: String,
    val sidecarFileSha256: String,
    val sidecarJson: String,
    val staticPlanSha256: String,
    val appRoutingSha256: String,
    val routingGeneration: Long
)

internal class RootStopRequestedException : IllegalStateException("Root stop requested")

internal data class RootRoutingArtifacts(
    val configContent: String,
    val configBytes: ByteArray,
    val sidecarJson: String,
    val sidecarBytes: ByteArray,
    val manifestBytes: ByteArray,
    val plan: RootAppRoutingPlan,
    val manifest: RootRoutingManifest
)

@Suppress("LargeClass")
class KunBoxRootService : RootService() {
    companion object {
        internal const val TAG = "KunBoxRootService"
        internal const val UID_REFRESH_INTERVAL_MS = 30_000L

        init {
            if (Process.myUid() == 0) {
                Libbox.version()
            }
        }
    }

    internal val runtimeLock = Any()
    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val rootCommandExecutor = ProcessRootCommandExecutor()
    internal val netfilterManager = RootNetfilterManager(
        rootCommandExecutor,
        RootNetfilterOwnershipStore(rootCommandExecutor)
    )
    internal val artifactSnapshotStore = RootArtifactSnapshotStore()

    @Volatile
    internal var snapshot = RootRuntimeSnapshot(rootPid = Process.myPid())

    @Volatile
    internal var capabilityReport = RootCapabilityReport(
        rootUid = false,
        capNetAdmin = false,
        capNetRaw = false,
        ipCommand = false,
        iptables = false,
        ip6tables = false,
        tproxyIpv4 = false,
        tproxyIpv6 = false,
        redirectIpv4 = false,
        redirectIpv6 = false,
        ownerMatch = false,
        routeProtocol = false,
        selinuxDomain = "unknown",
        error = "Root service not initialized"
    )

    internal var commandServer: CommandServer? = null
    internal var watchdog: RootWatchdogInstaller? = null
    internal var resourceGuard: RootResourceGuard? = null
    internal var netfilterOwned = false
    internal var activeNetfilterPlan: RootNetfilterPlan? = null
    internal var activeRoutingArtifacts: RootRoutingArtifacts? = null
    internal var activeResolvedRouting: RootResolvedRouting? = null
    internal var activeStartRequest: RootStartRequest? = null
    internal var uidMonitorJob: Job? = null
    internal val startupTimings = linkedMapOf<String, Long>()

    internal val runtimeTransactions = AtomicInteger(0)
    internal val stopRequestedSession = AtomicReference("")

    @Volatile
    internal var destroying = false

    internal val binder = object : IRootSingBoxService.Stub() {
        override fun getSnapshot(): Bundle {
            enforceCaller()
            return this@KunBoxRootService.snapshot.toBundle()
        }

        override fun getCapabilityReport(): Bundle {
            enforceCaller()
            return this@KunBoxRootService.capabilityReport.toBundle()
        }

        override fun openCommandConnection(): ParcelFileDescriptor? {
            enforceCaller()
            val socketPath = File(filesDir, "libbox_${Process.myPid()}/command.sock")
            if (!socketPath.exists()) return null
            return LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(socketPath.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                ParcelFileDescriptor.dup(socket.fileDescriptor)
            }
        }

        override fun start(
            configPath: String?,
            runtimeSessionId: String?,
            appMode: String?,
            allowlist: Array<out String>?,
            blocklist: Array<out String>?,
            selfPackage: String?,
            forceConnectionOwnerRouting: Boolean,
            appUid: Int,
            proxyIpv4: Boolean,
            proxyIpv6: Boolean,
            blockIpv4: Boolean,
            blockIpv6: Boolean,
            blockQuic: Boolean,
            apkPath: String?,
            configFileSha256: String?,
            sidecarFileSha256: String?,
            sidecarJson: String?,
            staticPlanSha256: String?,
            appRoutingSha256: String?,
            routingGeneration: Long
        ): Bundle {
            enforceCaller()
            require(appUid == applicationInfo.uid) { "Unexpected KunBox UID" }
            clearStopRequestForNewSession(runtimeSessionId.orEmpty())
            return runRuntimeTransaction {
                startLocked(
                    RootStartRequest(
                        configPath = configPath.orEmpty(),
                        runtimeSessionId = runtimeSessionId.orEmpty(),
                        appMode = appMode.orEmpty(),
                        allowlist = allowlist?.toSet().orEmpty(),
                        blocklist = blocklist?.toSet().orEmpty(),
                        selfPackage = selfPackage.orEmpty(),
                        forceConnectionOwnerRouting = forceConnectionOwnerRouting,
                        appUid = appUid,
                        proxyIpv4 = proxyIpv4,
                        proxyIpv6 = proxyIpv6,
                        blockIpv4 = blockIpv4,
                        blockIpv6 = blockIpv6,
                        blockQuic = blockQuic,
                        apkPath = apkPath.orEmpty(),
                        configFileSha256 = configFileSha256.orEmpty(),
                        sidecarFileSha256 = sidecarFileSha256.orEmpty(),
                        sidecarJson = sidecarJson.orEmpty(),
                        staticPlanSha256 = staticPlanSha256.orEmpty(),
                        appRoutingSha256 = appRoutingSha256.orEmpty(),
                        routingGeneration = routingGeneration
                    )
                ).toBundle()
            }
        }

        override fun hotReload(
            configPath: String?,
            runtimeSessionId: String?,
            configFileSha256: String?,
            sidecarFileSha256: String?,
            sidecarJson: String?,
            staticPlanSha256: String?,
            appRoutingSha256: String?,
            routingGeneration: Long
        ): Bundle {
            enforceCaller()
            clearStopRequestForNewSession(runtimeSessionId.orEmpty())
            return runRuntimeTransaction {
                hotReloadLocked(
                    configPath = configPath.orEmpty(),
                    runtimeSessionId = runtimeSessionId.orEmpty(),
                    configFileSha256 = configFileSha256.orEmpty(),
                    sidecarFileSha256 = sidecarFileSha256.orEmpty(),
                    sidecarJson = sidecarJson.orEmpty(),
                    staticPlanSha256 = staticPlanSha256.orEmpty(),
                    appRoutingSha256 = appRoutingSha256.orEmpty(),
                    routingGeneration = routingGeneration
                ).toBundle()
            }
        }

        override fun requestStop(runtimeSessionId: String?) {
            enforceCaller()
            runtimeSessionId.orEmpty().takeIf(String::isNotBlank)?.let(stopRequestedSession::set)
        }

        override fun stop(runtimeSessionId: String?): Bundle {
            enforceCaller()
            runtimeSessionId.orEmpty().takeIf(String::isNotBlank)?.let(stopRequestedSession::set)
            return runRuntimeTransaction {
                stopLocked(runtimeSessionId.orEmpty()).toBundle()
            }
        }

        override fun blockForUidRefresh(runtimeSessionId: String?): Bundle {
            enforceCaller()
            return runRuntimeTransaction {
                refreshUidRoutingLocked(runtimeSessionId.orEmpty()).toBundle()
            }
        }

        override fun resetNetwork(): Boolean {
            enforceCaller()
            return runRuntimeTransaction {
                val server = commandServer ?: return@runRuntimeTransaction false
                runCatching { server.resetNetwork() }.isSuccess
            }
        }
    }

    override fun onCreate() {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        super.onCreate()
        check(Process.myUid() == 0) { "KunBoxRootService is not running as root" }
        SingBoxCore.ensureLibboxSetup(this)
        capabilityReport = RootCapabilityProbe().probe()
        logStartPhase("capability", startedAt)
        updateSnapshot(
            phase = RootRuntimePhase.STOPPED,
            error = capabilityReport.error,
            tproxyIpv4 = capabilityReport.tproxyIpv4,
            tproxyIpv6 = capabilityReport.tproxyIpv6
        )
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        destroying = true
        uidMonitorJob?.cancel()
        uidMonitorJob = null
        val activeTransactions = runtimeTransactions.get()
        if (rootDestroyRequiresCleanup(snapshot, activeTransactions)) {
            runCatching { synchronized(runtimeLock) { stopLocked(snapshot.runtimeSessionId) } }
                .onFailure { error -> Log.e(TAG, "Root service destruction cleanup failed", error) }
        } else if (activeTransactions > 0) {
            // Avoid blocking RootService destruction behind a stuck libbox or
            // kernel transaction. Closing the lease below delegates cleanup to
            // the external watchdog, which remains the fail-closed owner.
            Log.w(TAG, "Root service destroyed during an active transaction; watchdog will clean up")
        } else {
            Log.i(TAG, "Root service destruction cleanup skipped for terminal phase=${snapshot.phase}")
        }
        watchdog?.close()
        if (snapshot.phase != RootRuntimePhase.FAILED_BLOCKED) watchdog = null
        resourceGuard?.close()
        resourceGuard = null
        serviceScope.cancel()
        super.onDestroy()
    }

    internal inline fun <T> runRuntimeTransaction(block: () -> T): T {
        runtimeTransactions.incrementAndGet()
        return try {
            synchronized(runtimeLock) {
                check(!destroying) { "Root service is being destroyed" }
                block()
            }
        } finally {
            runtimeTransactions.decrementAndGet()
        }
    }

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    internal fun startLocked(request: RootStartRequest): RootRuntimeSnapshot {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var phaseStartedAt = startedAt
        startupTimings.clear()
        rootCommandExecutor.resetXtablesWaitMetrics()
        validateStartRequest(request)?.let { return failUnprotected(it) }
        throwIfStopRequested(request.runtimeSessionId)
        if (snapshot.phase != RootRuntimePhase.STOPPED) {
            val stopped = stopLocked(snapshot.runtimeSessionId)
            if (stopped.phase != RootRuntimePhase.STOPPED) return stopped
        }
        val cleanupSupport = watchdog ?: RootWatchdogInstaller(this).also { watchdog = it }
        cleanupSupport.installScripts().getOrElse { error ->
            return failUnprotected(error.message ?: "Cannot install Root cleanup support")
        }
        val staleRuntimePresent = File("/data/adb/kunbox/session").exists()
        val prepareError = netfilterManager.prepareForStart(staleRuntimePresent).exceptionOrNull()
        logStartPhase("legacy_cleanup_ms", phaseStartedAt)
        phaseStartedAt = android.os.SystemClock.elapsedRealtime()
        if (prepareError != null) {
            logStartPhase("total_ms", startedAt)
            return failCleanup(
                prepareError,
                prepareError.message ?: "Cannot clear stale Root rules"
            )
        }
        throwIfStopRequested(request.runtimeSessionId)
        return try {
            updateSnapshot(
                phase = RootRuntimePhase.VALIDATING_PLAN,
                runtimeSessionId = request.runtimeSessionId,
                error = "",
                rulesInstalled = false,
                watchdogReady = false,
                configFileSha256 = request.configFileSha256,
                sidecarFileSha256 = request.sidecarFileSha256,
                staticPlanSha256 = request.staticPlanSha256,
                appRoutingSha256 = request.appRoutingSha256,
                resolvedPlanSha256 = "",
                resolvedUidCount = 0
            )
            val artifacts = readValidatedArtifacts(request)
            throwIfStopRequested(request.runtimeSessionId)
            updateSnapshot(phase = RootRuntimePhase.VALIDATING_PLAN, routingGeneration = artifacts.plan.generation)
            check(artifacts.plan.vpnAppMode == request.appMode) { "Root app capture mode mismatch" }
            check(artifacts.plan.allowlist.toSet() == request.allowlist) { "Root allowlist does not match sidecar" }
            check(artifacts.plan.blocklist.toSet() == request.blocklist) { "Root blocklist does not match sidecar" }
            check(artifacts.plan.ipVersionMode in resolveAllowedIpVersionModes(request)) {
                "Root IP version mode mismatch"
            }
            activeRoutingArtifacts = artifacts
            activeStartRequest = request
            val resolver = RootUidResolver()
            updateSnapshot(phase = RootRuntimePhase.UID_SNAPSHOT_1)
            val firstResolved = resolver.resolveRouting(artifacts.plan, request.selfPackage, request.appUid)
            throwIfStopRequested(request.runtimeSessionId)
            activeResolvedRouting = firstResolved
            netfilterManager.beginOwnership(
                RootNetfilterOwnership.context(
                    request.runtimeSessionId,
                    artifacts.plan.generation,
                    firstResolved.resolvedPlanSha256
                )
            ).getOrThrow()
            netfilterManager.checkReservedStateAvailable().getOrThrow()
            logStartPhase("uid_snapshot_1", phaseStartedAt)
            phaseStartedAt = android.os.SystemClock.elapsedRealtime()
            val netfilterConfig = buildNetfilterConfig(request, firstResolved)
            val guardConfig = RootFailClosedConfig(
                capturedUids = netfilterConfig.capturedUids,
                capturedUidRanges = netfilterConfig.capturedUidRanges,
                excludedUids = netfilterConfig.excludedUids,
                appUid = request.appUid,
                ipv4 = netfilterConfig.proxyIpv4 || netfilterConfig.blockIpv4,
                ipv6 = netfilterConfig.proxyIpv6 || netfilterConfig.blockIpv6
            )
            startWatchdog(request)
            val guardResult = netfilterManager.installGuard(guardConfig)
            logStartPhase("guard_ms", phaseStartedAt)
            phaseStartedAt = android.os.SystemClock.elapsedRealtime()
            guardResult.getOrThrow()
            throwIfStopRequested(request.runtimeSessionId)
            updateSnapshot(phase = RootRuntimePhase.FAIL_CLOSED, watchdogReady = true, rulesInstalled = true)
            val candidateResult = netfilterManager.stage(netfilterConfig)
            logStartPhase("rules_staging_ms", phaseStartedAt)
            phaseStartedAt = android.os.SystemClock.elapsedRealtime()
            val candidatePlan = candidateResult.getOrThrow()
            throwIfStopRequested(request.runtimeSessionId)
            activeNetfilterPlan = candidatePlan
            updateSnapshot(phase = RootRuntimePhase.RULES_STAGING, watchdogReady = true, rulesInstalled = true)
            updateSnapshot(phase = RootRuntimePhase.CORE_STARTING)
            val coreResult = runCatching { startCommandServer(request, artifacts) }
            logStartPhase("core_ms", phaseStartedAt)
            phaseStartedAt = android.os.SystemClock.elapsedRealtime()
            coreResult.getOrThrow()
            throwIfStopRequested(request.runtimeSessionId)
            updateSnapshot(phase = RootRuntimePhase.CORE_VERIFYING)
            verifyAllLaneListeners(artifacts.plan)
            throwIfStopRequested(request.runtimeSessionId)
            logStartPhase("core_verify", phaseStartedAt)
            phaseStartedAt = android.os.SystemClock.elapsedRealtime()
            updateSnapshot(phase = RootRuntimePhase.UID_SNAPSHOT_2)
            val secondResolved = resolver.resolveRouting(artifacts.plan, request.selfPackage, request.appUid)
            check(firstResolved.resolvedPlanSha256 == secondResolved.resolvedPlanSha256) {
                "Root UID snapshot changed during startup"
            }
            throwIfStopRequested(request.runtimeSessionId)
            activeResolvedRouting = secondResolved
            logResolvedRouting(artifacts.plan, secondResolved)
            updateSnapshot(
                phase = RootRuntimePhase.RULES_ACTIVATING,
                resolvedPlanSha256 = secondResolved.resolvedPlanSha256,
                resolvedUidCount = secondResolved.routes.size
            )
            netfilterManager.activate(candidatePlan).getOrThrow()
            throwIfStopRequested(request.runtimeSessionId)
            netfilterManager.removeGuard().getOrThrow()
            throwIfStopRequested(request.runtimeSessionId)
            netfilterOwned = true
            logStartPhase("total_ms", startedAt)
            val runningSnapshot = updateSnapshot(
                phase = RootRuntimePhase.RUNNING,
                ruleRevision = snapshot.ruleRevision + 1,
                rulesInstalled = true,
                watchdogReady = true,
                startupTimings = encodeStartupTimings()
            )
            startResourceGuard(request.runtimeSessionId)
            startUidMonitor(request.runtimeSessionId)
            pruneLockedArtifacts(artifacts.plan.generation)
            runningSnapshot
        } catch (error: Exception) {
            Log.e(TAG, "Root runtime start failed", error)
            val cleanupError = rollbackLocked()
            if ("total_ms" !in startupTimings) logStartPhase("total_ms", startedAt)
            if (error is RootStopRequestedException && cleanupError == null) {
                markStopped()
            } else if (cleanupError == null) {
                failUnprotected(error.message ?: "Root runtime start failed")
            } else {
                failCleanup(cleanupError, cleanupError.message ?: "Root startup rollback failed")
            }
        }
    }

    internal fun validateStartRequest(request: RootStartRequest): String? = when {
        runCatching { UUID.fromString(request.runtimeSessionId) }.isFailure -> "Invalid runtime session ID"
        !capabilityReport.supported -> capabilityReport.error
        !request.proxyIpv4 && request.proxyIpv6 &&
            (!capabilityReport.tproxyIpv6 || !capabilityReport.redirectIpv6) ->
            "Complete IPv6 transparent proxy support is unavailable"
        request.apkPath.isBlank() -> "APK path is empty"
        request.selfPackage.isBlank() -> "Self package is empty"
        request.configPath.isBlank() -> "Root config path is empty"
        request.sidecarJson.isBlank() -> "Root routing sidecar is empty"
        else -> null
    }

    internal fun clearStopRequestForNewSession(runtimeSessionId: String) {
        val stoppedSession = stopRequestedSession.get()
        if (stoppedSession.isNotBlank() && stoppedSession != runtimeSessionId) {
            stopRequestedSession.compareAndSet(stoppedSession, "")
        }
    }

    internal fun throwIfStopRequested(runtimeSessionId: String) {
        if (runtimeSessionId.isNotBlank() && stopRequestedSession.get() == runtimeSessionId) {
            throw RootStopRequestedException()
        }
    }

    internal fun resolveAllowedIpVersionModes(request: RootStartRequest): Set<String> = when {
        request.proxyIpv6 && !request.proxyIpv4 -> setOf("IPV6_ONLY")
        request.proxyIpv4 && !request.proxyIpv6 -> setOf("IPV4_ONLY")
        else -> setOf("DUAL_STACK", "PREFER_IPV6")
    }

    internal fun startCommandServer(
        request: RootStartRequest,
        artifacts: RootRoutingArtifacts
    ) {
        check(commandServer == null) { "Root CommandServer is already active" }
        val platformInterface = RootPlatformInterface(
            context = this,
            serviceScope = serviceScope,
            forceConnectionOwnerRouting = request.forceConnectionOwnerRouting,
            serverProvider = { commandServer }
        ).delegate
        val server = Libbox.newCommandServer(createServerHandler(), platformInterface)
        server.start()
        commandServer = server
        server.startOrReloadService(artifacts.configContent, OverrideOptions().apply { autoRedirect = false })
    }

    internal fun closeCommandServer(plan: RootAppRoutingPlan?) {
        val server = commandServer
        if (server != null) {
            runCatching { server.closeService() }
                .onFailure { Log.w(TAG, "Root core service close reported an error", it) }
            runCatching { server.close() }
                .onFailure { Log.w(TAG, "Root CommandServer close reported an error", it) }
            commandServer = null
        }
        plan?.let(::awaitListenersAbsent)
        awaitCommandSocketAbsent()
    }

    internal fun awaitListenersAbsent(plan: RootAppRoutingPlan, timeoutMs: Long = 1_000L) {
        val expectation = listenerExpectation(plan)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        while (System.nanoTime() < deadline) {
            failure = RootListenerVerifier().verifyAbsent(expectation).exceptionOrNull()
            if (failure == null) return
            val remaining = deadline - System.nanoTime()
            if (remaining > 0L) latch.await(
                minOf(remaining, TimeUnit.MILLISECONDS.toNanos(25L)),
                TimeUnit.NANOSECONDS
            )
        }
        throw IllegalStateException("Root listeners remained after CommandServer close", failure)
    }

    internal fun awaitCommandSocketAbsent(timeoutMs: Long = 1_000L) {
        val socketPath = File(filesDir, "libbox_${Process.myPid()}/command.sock")
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val latch = CountDownLatch(1)
        while (socketPath.exists() && System.nanoTime() < deadline) {
            val remaining = deadline - System.nanoTime()
            if (remaining > 0L) latch.await(
                minOf(remaining, TimeUnit.MILLISECONDS.toNanos(25L)),
                TimeUnit.NANOSECONDS
            )
        }
        check(!socketPath.exists()) { "Root command socket remained after CommandServer close" }
    }

    internal fun startWatchdog(request: RootStartRequest) {
        val rootWatchdog = watchdog ?: RootWatchdogInstaller(this)
        watchdog = rootWatchdog
        rootWatchdog.start(request.runtimeSessionId, request.apkPath) {
            handleWatchdogLost(request.runtimeSessionId)
        }.getOrThrow()
        check(rootWatchdog.awaitReady()) { "Root watchdog did not acknowledge the lease" }
        logStartPhase("watchdog", android.os.SystemClock.elapsedRealtime())
    }

    internal fun buildNetfilterConfig(
        request: RootStartRequest,
        resolved: RootResolvedRouting,
        plan: RootAppRoutingPlan = activeRoutingArtifacts?.plan ?: error("Root routing plan is unavailable")
    ): RootNetfilterConfig {
        val proxyIpv6 = request.proxyIpv6 && capabilityReport.tproxyIpv6 && capabilityReport.redirectIpv6
        val lanes = resolved.laneUids.map { (laneId, uids) ->
            val lane = requireNotNull(plan.lanes.firstOrNull { it.laneId == laneId }) {
                "Resolved Root lane is missing from the routing plan: $laneId"
            }
            RootNetfilterLane(
                laneId = lane.laneId,
                slot = lane.slot,
                uids = uids,
                redirectPortIpv4 = lane.tcpPortIpv4,
                redirectPortIpv6 = lane.tcpPortIpv6,
                tproxyPortIpv4 = lane.udpPortIpv4,
                tproxyPortIpv6 = lane.udpPortIpv6,
                markIpv4 = lane.markIpv4,
                markIpv6 = lane.markIpv6,
                priorityIpv4 = lane.priorityIpv4,
                priorityIpv6 = lane.priorityIpv6
            )
        }
        return RootNetfilterConfig(
            capturedUids = resolved.selection.capturedUids,
            capturedUidRanges = resolved.selection.capturedRanges,
            excludedUids = resolved.selection.excludedUids,
            appUid = request.appUid,
            proxyIpv4 = request.proxyIpv4,
            proxyIpv6 = proxyIpv6,
            blockIpv4 = request.blockIpv4,
            blockIpv6 = request.blockIpv6 || (request.proxyIpv6 && !proxyIpv6),
            blockQuic = request.blockQuic,
            redirectPortIpv4 = InboundBuilder.ROOT_REDIRECT_PORT_IPV4,
            redirectPortIpv6 = InboundBuilder.ROOT_REDIRECT_PORT_IPV6,
            tproxyPortIpv4 = InboundBuilder.ROOT_TPROXY_PORT_IPV4,
            tproxyPortIpv6 = InboundBuilder.ROOT_TPROXY_PORT_IPV6,
            lanes = lanes
        )
    }

    internal fun verifyAllLaneListeners(plan: RootAppRoutingPlan) {
        RootListenerVerifier().verify(listenerExpectation(plan), Process.myPid()).getOrThrow()
    }

    internal fun listenerExpectation(plan: RootAppRoutingPlan): RootListenerExpectation =
        RootListenerExpectation(
            ipv4 = if (plan.proxyIpv4) {
                setOf(InboundBuilder.ROOT_REDIRECT_PORT_IPV4) + plan.lanes.map { it.tcpPortIpv4 }
            } else emptySet(),
            ipv6 = if (plan.proxyIpv6) {
                setOf(InboundBuilder.ROOT_REDIRECT_PORT_IPV6) + plan.lanes.map { it.tcpPortIpv6 }
            } else emptySet(),
            udpIpv4 = if (plan.proxyIpv4) {
                setOf(InboundBuilder.ROOT_TPROXY_PORT_IPV4) + plan.lanes.map { it.udpPortIpv4 }
            } else emptySet(),
            udpIpv6 = if (plan.proxyIpv6) {
                setOf(InboundBuilder.ROOT_TPROXY_PORT_IPV6) + plan.lanes.map { it.udpPortIpv6 }
            } else emptySet()
        )

    internal fun logStartPhase(phase: String, startedAt: Long) {
        val durationMs = android.os.SystemClock.elapsedRealtime() - startedAt
        startupTimings[phase] = durationMs
        Log.i(TAG, "[ROOT_START] phase=$phase duration_ms=$durationMs")
    }

    internal fun encodeStartupTimings(): String {
        startupTimings["xtables_wait_ms"] = rootCommandExecutor.currentXtablesWaitMs()
        return formatRootStartupTimings(startupTimings)
    }

    internal fun logResolvedRouting(plan: RootAppRoutingPlan, resolved: RootResolvedRouting) {
        val lanes = plan.lanes.associateBy { it.laneId }
        resolved.routes.filter { it.laneId in lanes }.forEach { route ->
            val lane = lanes.getValue(route.laneId)
            val inboundTags = lane.inboundTags(plan.proxyIpv4, plan.proxyIpv6).joinToString("|")
            Log.i(
                TAG,
                "[APP_ROUTE] [ROOT_NET] generation=${plan.generation} operation=route_uid " +
                    "revision=${plan.policyRevision} " +
                    "uid=${route.uid} user=${route.userId} package=${route.packageName} " +
                    "lane=${lane.laneId} inbound=$inboundTags " +
                    "outbound=${lane.outboundTag.ifBlank { lane.routeAction }} chain=KBX_OUT/KBX_RED"
            )
        }
        Log.i(
            TAG,
            "[APP_ROUTE] runtime=${plan.generation} resolved=${resolved.resolvedPlanSha256} " +
                "lanes=${resolved.laneUids.size} routes=${resolved.routes.size}"
        )
    }

    internal fun pruneLockedArtifacts(activeGeneration: Long) {
        runCatching { artifactSnapshotStore.pruneGenerations(setOf(activeGeneration)) }
            .onFailure { Log.w(TAG, "Cannot prune stale Root-owned snapshots", it) }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    internal fun refreshUidRoutingLocked(runtimeSessionId: String): RootRuntimeSnapshot {
        if (runtimeSessionId.isBlank() || runtimeSessionId != snapshot.runtimeSessionId) return snapshot
        if (snapshot.phase !in setOf(
                RootRuntimePhase.RUNNING,
                RootRuntimePhase.FAIL_CLOSED,
                RootRuntimePhase.FAILED_BLOCKED
            )
        ) {
            return snapshot
        }
        val request = activeStartRequest ?: return failRulesPresent("Root active request is unavailable")
        val artifacts = activeRoutingArtifacts ?: return failRulesPresent("Root active artifacts are unavailable")
        val previousResolved = activeResolvedRouting
            ?: return failRulesPresent("Root active UID snapshot is unavailable")
        val startedFromRunning = snapshot.phase == RootRuntimePhase.RUNNING
        return try {
            throwIfStopRequested(runtimeSessionId)
            installUidRefreshGuard(request, previousResolved, artifacts.plan)
            throwIfStopRequested(runtimeSessionId)
            updateSnapshot(
                phase = RootRuntimePhase.FAIL_CLOSED,
                watchdogReady = true,
                rulesInstalled = true,
                error = "Root UID snapshot refresh in progress"
            )
            val resolver = RootUidResolver()
            updateSnapshot(phase = RootRuntimePhase.UID_SNAPSHOT_1)
            val firstResolved = resolver.resolveRouting(artifacts.plan, request.selfPackage, request.appUid)
            throwIfStopRequested(runtimeSessionId)
            if (startedFromRunning && firstResolved.resolvedPlanSha256 == previousResolved.resolvedPlanSha256) {
                netfilterManager.removeGuard().getOrThrow()
                startUidMonitor(request.runtimeSessionId)
                return updateSnapshot(
                    phase = RootRuntimePhase.RUNNING,
                    watchdogReady = true,
                    rulesInstalled = true,
                    error = ""
                )
            }
            closeCommandServer(artifacts.plan)
            netfilterManager.cleanupActivePlanKeepingGuard().getOrThrow()
            throwIfStopRequested(runtimeSessionId)
            activeNetfilterPlan = null
            netfilterManager.beginOwnership(
                RootNetfilterOwnership.context(
                    request.runtimeSessionId,
                    artifacts.plan.generation,
                    firstResolved.resolvedPlanSha256
                )
            ).getOrThrow()
            val candidateConfig = buildNetfilterConfig(request, firstResolved, artifacts.plan)
            updateSnapshot(phase = RootRuntimePhase.RULES_STAGING)
            val candidatePlan = netfilterManager.stage(candidateConfig).getOrThrow()
            throwIfStopRequested(runtimeSessionId)
            activeNetfilterPlan = candidatePlan
            updateSnapshot(phase = RootRuntimePhase.CORE_STARTING)
            startCommandServer(request, artifacts)
            throwIfStopRequested(runtimeSessionId)
            updateSnapshot(phase = RootRuntimePhase.CORE_VERIFYING)
            verifyAllLaneListeners(artifacts.plan)
            throwIfStopRequested(runtimeSessionId)
            updateSnapshot(phase = RootRuntimePhase.UID_SNAPSHOT_2)
            val secondResolved = resolver.resolveRouting(artifacts.plan, request.selfPackage, request.appUid)
            check(firstResolved.resolvedPlanSha256 == secondResolved.resolvedPlanSha256) {
                "Root UID snapshot changed during refresh"
            }
            throwIfStopRequested(runtimeSessionId)
            updateSnapshot(phase = RootRuntimePhase.RULES_ACTIVATING)
            netfilterManager.activate(candidatePlan).getOrThrow()
            throwIfStopRequested(runtimeSessionId)
            netfilterManager.removeGuard().getOrThrow()
            throwIfStopRequested(runtimeSessionId)
            activeResolvedRouting = secondResolved
            netfilterOwned = true
            logResolvedRouting(artifacts.plan, secondResolved)
            startUidMonitor(request.runtimeSessionId)
            updateSnapshot(
                phase = RootRuntimePhase.RUNNING,
                ruleRevision = snapshot.ruleRevision + 1,
                resolvedPlanSha256 = secondResolved.resolvedPlanSha256,
                resolvedUidCount = secondResolved.routes.size,
                watchdogReady = true,
                rulesInstalled = true,
                error = ""
            )
        } catch (error: Exception) {
            Log.e(TAG, "Root UID routing refresh failed", error)
            val cleanupError = rollbackLocked()
            if (error is RootStopRequestedException && cleanupError == null) {
                markStopped()
            } else if (cleanupError == null) {
                failUnprotected(error.message ?: "Root UID routing refresh failed")
            } else {
                failCleanup(
                    cleanupError,
                    "${error.message ?: "Root UID routing refresh failed"}; " +
                        "cleanup failed: ${cleanupError.message}"
                )
            }
        }
    }
}
