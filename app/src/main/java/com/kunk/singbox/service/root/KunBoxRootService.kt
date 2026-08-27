package com.kunk.singbox.service.root

import android.content.Intent
import android.os.Binder
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
import com.kunk.singbox.model.RootAppRoutingCanonical
import com.kunk.singbox.model.RootRoutingArtifactValidator
import com.kunk.singbox.model.RootRoutingManifest
import com.kunk.singbox.model.isRootSha256
import com.kunk.singbox.repository.config.InboundBuilder
import com.kunk.singbox.repository.RootGenerationStore
import com.topjohnwu.superuser.ipc.RootService
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SystemProxyStatus
import com.google.gson.Gson
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private data class RootStartRequest(
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

private data class RootRoutingArtifacts(
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
        private const val TAG = "KunBoxRootService"
        private const val UID_REFRESH_INTERVAL_MS = 30_000L

        init {
            if (Process.myUid() == 0) {
                Libbox.version()
            }
        }
    }

    private val runtimeLock = Any()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rootCommandExecutor = ProcessRootCommandExecutor()
    private val netfilterManager = RootNetfilterManager(
        rootCommandExecutor,
        RootNetfilterOwnershipStore(rootCommandExecutor)
    )
    private val artifactSnapshotStore = RootArtifactSnapshotStore()

    @Volatile
    private var snapshot = RootRuntimeSnapshot(rootPid = Process.myPid())

    @Volatile
    private var capabilityReport = RootCapabilityReport(
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

    private var commandServer: CommandServer? = null
    private var watchdog: RootWatchdogInstaller? = null
    private var resourceGuard: RootResourceGuard? = null
    private var netfilterOwned = false
    private var activeNetfilterPlan: RootNetfilterPlan? = null
    private var activeRoutingArtifacts: RootRoutingArtifacts? = null
    private var activeResolvedRouting: RootResolvedRouting? = null
    private var activeStartRequest: RootStartRequest? = null
    private var uidMonitorJob: Job? = null
    private val startupTimings = linkedMapOf<String, Long>()

    private val runtimeTransactions = AtomicInteger(0)

    @Volatile
    private var destroying = false

    private val binder = object : IRootSingBoxService.Stub() {
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

        override fun stop(runtimeSessionId: String?): Bundle {
            enforceCaller()
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
        if (runtimeTransactions.get() == 0) {
            runCatching { synchronized(runtimeLock) { stopLocked(snapshot.runtimeSessionId) } }
                .onFailure { error -> Log.e(TAG, "Root service destruction cleanup failed", error) }
        } else {
            // Avoid blocking RootService destruction behind a stuck libbox or
            // kernel transaction. Closing the lease below delegates cleanup to
            // the external watchdog, which remains the fail-closed owner.
            Log.w(TAG, "Root service destroyed during an active transaction; watchdog will clean up")
        }
        watchdog?.close()
        if (snapshot.phase != RootRuntimePhase.FAILED_BLOCKED) watchdog = null
        resourceGuard?.close()
        resourceGuard = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private inline fun <T> runRuntimeTransaction(block: () -> T): T {
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
    private fun startLocked(request: RootStartRequest): RootRuntimeSnapshot {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var phaseStartedAt = startedAt
        startupTimings.clear()
        validateStartRequest(request)?.let { return failUnprotected(it) }
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
        if (prepareError != null) {
            return failRulesPresent(prepareError.message ?: "Cannot clear stale Root rules")
        }
        logStartPhase("cleanup", phaseStartedAt)
        phaseStartedAt = android.os.SystemClock.elapsedRealtime()
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
            netfilterManager.installGuard(guardConfig).getOrThrow()
            updateSnapshot(phase = RootRuntimePhase.FAIL_CLOSED, watchdogReady = true, rulesInstalled = true)
            logStartPhase("fail_closed", phaseStartedAt)
            phaseStartedAt = android.os.SystemClock.elapsedRealtime()
            val candidatePlan = netfilterManager.stage(netfilterConfig).getOrThrow()
            activeNetfilterPlan = candidatePlan
            updateSnapshot(phase = RootRuntimePhase.RULES_STAGING, watchdogReady = true, rulesInstalled = true)
            logStartPhase("rules_staging", phaseStartedAt)
            phaseStartedAt = android.os.SystemClock.elapsedRealtime()
            updateSnapshot(phase = RootRuntimePhase.CORE_STARTING)
            startCommandServer(request, artifacts)
            logStartPhase("core", phaseStartedAt)
            phaseStartedAt = android.os.SystemClock.elapsedRealtime()
            updateSnapshot(phase = RootRuntimePhase.CORE_VERIFYING)
            verifyAllLaneListeners(artifacts.plan)
            logStartPhase("core_verify", phaseStartedAt)
            phaseStartedAt = android.os.SystemClock.elapsedRealtime()
            updateSnapshot(phase = RootRuntimePhase.UID_SNAPSHOT_2)
            val secondResolved = resolver.resolveRouting(artifacts.plan, request.selfPackage, request.appUid)
            check(firstResolved.resolvedPlanSha256 == secondResolved.resolvedPlanSha256) {
                "Root UID snapshot changed during startup"
            }
            activeResolvedRouting = secondResolved
            logResolvedRouting(artifacts.plan, secondResolved)
            updateSnapshot(
                phase = RootRuntimePhase.RULES_ACTIVATING,
                resolvedPlanSha256 = secondResolved.resolvedPlanSha256,
                resolvedUidCount = secondResolved.routes.size
            )
            netfilterManager.activate(candidatePlan).getOrThrow()
            netfilterManager.removeGuard().getOrThrow()
            netfilterOwned = true
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
            logStartPhase("root_total", startedAt)
            runningSnapshot
        } catch (error: Exception) {
            Log.e(TAG, "Root runtime start failed", error)
            val cleanupError = rollbackLocked()
            if (cleanupError == null) {
                failUnprotected(error.message ?: "Root runtime start failed")
            } else {
                failRulesPresent(cleanupError.message ?: "Root startup rollback failed")
            }
        }
    }

    private fun validateStartRequest(request: RootStartRequest): String? = when {
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

    private fun resolveAllowedIpVersionModes(request: RootStartRequest): Set<String> = when {
        request.proxyIpv6 && !request.proxyIpv4 -> setOf("IPV6_ONLY")
        request.proxyIpv4 && !request.proxyIpv6 -> setOf("IPV4_ONLY")
        else -> setOf("DUAL_STACK", "PREFER_IPV6")
    }

    private fun startCommandServer(
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

    private fun closeCommandServer(plan: RootAppRoutingPlan?) {
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

    private fun awaitListenersAbsent(plan: RootAppRoutingPlan, timeoutMs: Long = 1_000L) {
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

    private fun awaitCommandSocketAbsent(timeoutMs: Long = 1_000L) {
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

    private fun startWatchdog(request: RootStartRequest) {
        val rootWatchdog = watchdog ?: RootWatchdogInstaller(this)
        watchdog = rootWatchdog
        rootWatchdog.start(request.runtimeSessionId, request.apkPath) {
            handleWatchdogLost(request.runtimeSessionId)
        }.getOrThrow()
        check(rootWatchdog.awaitReady()) { "Root watchdog did not acknowledge the lease" }
        logStartPhase("watchdog", android.os.SystemClock.elapsedRealtime())
    }

    private fun buildNetfilterConfig(
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

    private fun verifyAllLaneListeners(plan: RootAppRoutingPlan) {
        RootListenerVerifier().verify(listenerExpectation(plan), Process.myPid()).getOrThrow()
    }

    private fun listenerExpectation(plan: RootAppRoutingPlan): RootListenerExpectation =
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

    private fun logStartPhase(phase: String, startedAt: Long) {
        val durationMs = android.os.SystemClock.elapsedRealtime() - startedAt
        startupTimings[phase] = durationMs
        Log.i(TAG, "[ROOT_START] phase=$phase duration_ms=$durationMs")
    }

    private fun encodeStartupTimings(): String = formatRootStartupTimings(startupTimings)

    private fun logResolvedRouting(plan: RootAppRoutingPlan, resolved: RootResolvedRouting) {
        val lanes = plan.lanes.associateBy { it.laneId }
        resolved.routes.filter { it.laneId in lanes }.forEach { route ->
            val lane = lanes.getValue(route.laneId)
            val inboundTags = lane.inboundTags(plan.proxyIpv4, plan.proxyIpv6).joinToString("|")
            Log.i(
                TAG,
                "[APP_ROUTE] runtime=${plan.generation} revision=${plan.policyRevision} " +
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

    private fun pruneLockedArtifacts(activeGeneration: Long) {
        runCatching { artifactSnapshotStore.pruneGenerations(setOf(activeGeneration)) }
            .onFailure { Log.w(TAG, "Cannot prune stale Root-owned snapshots", it) }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun refreshUidRoutingLocked(runtimeSessionId: String): RootRuntimeSnapshot {
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
            installUidRefreshGuard(request, previousResolved, artifacts.plan)
            updateSnapshot(
                phase = RootRuntimePhase.FAIL_CLOSED,
                watchdogReady = true,
                rulesInstalled = true,
                error = "Root UID snapshot refresh in progress"
            )
            val resolver = RootUidResolver()
            updateSnapshot(phase = RootRuntimePhase.UID_SNAPSHOT_1)
            val firstResolved = resolver.resolveRouting(artifacts.plan, request.selfPackage, request.appUid)
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
            activeNetfilterPlan = candidatePlan
            updateSnapshot(phase = RootRuntimePhase.CORE_STARTING)
            startCommandServer(request, artifacts)
            updateSnapshot(phase = RootRuntimePhase.CORE_VERIFYING)
            verifyAllLaneListeners(artifacts.plan)
            updateSnapshot(phase = RootRuntimePhase.UID_SNAPSHOT_2)
            val secondResolved = resolver.resolveRouting(artifacts.plan, request.selfPackage, request.appUid)
            check(firstResolved.resolvedPlanSha256 == secondResolved.resolvedPlanSha256) {
                "Root UID snapshot changed during refresh"
            }
            updateSnapshot(phase = RootRuntimePhase.RULES_ACTIVATING)
            netfilterManager.activate(candidatePlan).getOrThrow()
            netfilterManager.removeGuard().getOrThrow()
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
            runCatching { closeCommandServer(artifacts.plan) }
            runCatching { netfilterManager.cleanupActivePlanKeepingGuard().getOrThrow() }
            activeNetfilterPlan = null
            if (netfilterManager.hasGuard()) {
                updateSnapshot(
                    phase = RootRuntimePhase.FAILED_BLOCKED,
                    error = error.message ?: "Root UID routing refresh failed",
                    watchdogReady = true,
                    rulesInstalled = true
                )
            } else {
                failUnprotected(error.message ?: "Root UID routing refresh failed")
            }
        }
    }

    private fun installUidRefreshGuard(
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
    private fun startUidMonitor(runtimeSessionId: String) {
        uidMonitorJob?.cancel()
        uidMonitorJob = serviceScope.launch {
            while (currentCoroutineContext().isActive) {
                delay(UID_REFRESH_INTERVAL_MS)
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
                        Log.w(TAG, "Root UID monitor could not confirm the current snapshot", error)
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
                                Log.e(TAG, "Root UID monitor could not install fail-closed guard", error)
                                failUidMonitorSafely(error.message ?: "Root UID refresh guard failed")
                            }
                    }
                    !changed
                }
                if (!keepMonitoring) break
            }
        }
    }

    private fun failUidMonitorSafely(message: String) {
        stopUidMonitor()
        val artifacts = activeRoutingArtifacts
        runCatching { closeCommandServer(artifacts?.plan) }
        val cleanupError = cleanupRulesVerified()
        if (cleanupError == null) {
            failUnprotected(message)
        } else {
            failRulesPresent("$message; cleanup failed: ${cleanupError.message}")
        }
    }

    private fun stopUidMonitor() {
        uidMonitorJob?.cancel()
        uidMonitorJob = null
    }

    @Suppress("LongParameterList", "LongMethod")
    private fun hotReloadLocked(
        configPath: String,
        runtimeSessionId: String,
        configFileSha256: String,
        sidecarFileSha256: String,
        sidecarJson: String,
        staticPlanSha256: String,
        appRoutingSha256: String,
        routingGeneration: Long
    ): RootRuntimeSnapshot {
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
        var candidateArtifacts: RootRoutingArtifacts? = null
        return try {
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
            val resolver = RootUidResolver()
            val firstResolved = resolver.resolveRouting(artifacts.plan, request.selfPackage, request.appUid)
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
            updateSnapshot(
                phase = RootRuntimePhase.FAIL_CLOSED,
                watchdogReady = true,
                rulesInstalled = true,
                error = ""
            )
            oldCoreStopped = true
            closeCommandServer(previousArtifacts.plan)
            netfilterManager.cleanupActivePlanKeepingGuard().getOrThrow()
            val candidatePlan = netfilterManager.stage(candidateConfig).getOrThrow()
            activeNetfilterPlan = candidatePlan
            updateSnapshot(phase = RootRuntimePhase.CORE_STARTING)
            startCommandServer(request, artifacts)
            updateSnapshot(phase = RootRuntimePhase.CORE_VERIFYING)
            verifyAllLaneListeners(artifacts.plan)
            val secondResolved = resolver.resolveRouting(artifacts.plan, request.selfPackage, request.appUid)
            check(firstResolved.resolvedPlanSha256 == secondResolved.resolvedPlanSha256) {
                "Root UID snapshot changed during cold reload"
            }
            netfilterManager.activate(candidatePlan).getOrThrow()
            netfilterManager.removeGuard().getOrThrow()
            guardInstalled = false
            activeRoutingArtifacts = artifacts
            activeResolvedRouting = secondResolved
            activeStartRequest = request
            logResolvedRouting(artifacts.plan, secondResolved)
            startUidMonitor(request.runtimeSessionId)
            pruneLockedArtifacts(artifacts.plan.generation)
            updateSnapshot(
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
        } catch (error: Exception) {
            Log.e(TAG, "Root cold reload failed", error)
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

    private fun unionGuardConfig(
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
    private fun restorePreviousAfterReload(
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
            netfilterManager.activate(restoredPlan).getOrThrow()
            netfilterManager.removeGuard().getOrThrow()
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
            Log.e(TAG, "Previous Root generation rollback failed", restoreError)
            updateSnapshot(
                phase = RootRuntimePhase.FAILED_BLOCKED,
                error = "$errorMessage; rollback failed: ${restoreError.message}",
                watchdogReady = true,
                rulesInstalled = true
            )
        }
    }

    private fun stopLocked(runtimeSessionId: String): RootRuntimeSnapshot {
        stopUidMonitor()
        if (snapshot.phase == RootRuntimePhase.STOPPED && snapshot.runtimeSessionId.isBlank()) {
            return snapshot
        }
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
            markStopped()
        } else if (cleanupError != null) {
            updateSnapshot(
                phase = RootRuntimePhase.FAILED_BLOCKED,
                error = cleanupError.message ?: "Root cleanup failed",
                rulesInstalled = true
            )
        } else {
            updateSnapshot(
                phase = RootRuntimePhase.FAILED_UNPROTECTED,
                error = commandError?.message ?: "Root CommandServer shutdown failed",
                rulesInstalled = false,
                watchdogReady = false
            )
        }
    }

    private fun rollbackLocked(): Throwable? {
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

    private fun markStopped(): RootRuntimeSnapshot {
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

    private fun handleWatchdogLost(runtimeSessionId: String) {
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
                failRulesPresent(cleanupError.message ?: "Root watchdog cleanup failed")
            }
        }
    }

    private fun matchesRunningSession(runtimeSessionId: String): Boolean =
        runtimeSessionId.isNotBlank() &&
            runtimeSessionId == snapshot.runtimeSessionId &&
            snapshot.phase == RootRuntimePhase.RUNNING

    private fun startResourceGuard(runtimeSessionId: String) {
        resourceGuard?.close()
        resourceGuard = RootResourceGuard { fdCount, action ->
            synchronized(runtimeLock) {
                if (snapshot.runtimeSessionId == runtimeSessionId) {
                    snapshot = snapshot.copy(rootFdCount = fdCount, generation = snapshot.generation + 1)
                    if (action == RootResourceAction.STOP) {
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
                            failRulesPresent(cleanupError.message ?: "Root resource cleanup failed")
                        }
                    }
                }
            }
        }.also(RootResourceGuard::start)
    }

    @Suppress("LongMethod")
    private fun readValidatedArtifacts(request: RootStartRequest): RootRoutingArtifacts {
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

    private fun validateGenerationConfigPath(configFile: File, generation: Long) {
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

    private fun parseRootRoutingPlan(raw: String): RootAppRoutingPlan {
        return RootRoutingArtifactValidator.requireBoundPlanJson(raw)
    }

    private fun createServerHandler(): CommandServerHandler = object : CommandServerHandler {
        override fun serviceStop() {
            serviceScope.launch {
                synchronized(runtimeLock) { stopLocked(snapshot.runtimeSessionId) }
            }
        }

        override fun serviceReload() = Unit

        override fun getSystemProxyStatus(): SystemProxyStatus? = null

        override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit

        override fun writeDebugMessage(message: String?) {
            if (!message.isNullOrBlank()) Log.d(TAG, message)
        }
    }

    private fun failUnprotected(message: String): RootRuntimeSnapshot = updateSnapshot(
        phase = RootRuntimePhase.FAILED_UNPROTECTED,
        error = message,
        rulesInstalled = false,
        watchdogReady = false
    )

    private fun failRulesPresent(message: String): RootRuntimeSnapshot = updateSnapshot(
        phase = RootRuntimePhase.FAILED_BLOCKED,
        error = message,
        rulesInstalled = true,
        watchdogReady = false
    )

    private fun cleanupRulesVerified(): Throwable? {
        val currentWatchdog = watchdog
        val cleanupError = netfilterManager.cleanup().exceptionOrNull()
        if (cleanupError != null) {
            // Keep the external watchdog alive when ownership cleanup is not
            // confirmed. Its stale-lease path is the last fail-closed guard.
            return cleanupError
        }
        val watchdogStopError = currentWatchdog?.stop(cleanupRules = false)?.exceptionOrNull()
        if (watchdogStopError != null) return watchdogStopError
        currentWatchdog?.close()
        if (watchdog === currentWatchdog) watchdog = null
        netfilterOwned = false
        return null
    }

    private fun updateSnapshot(
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

    private fun enforceCaller() {
        val callerUid = Binder.getCallingUid()
        check(callerUid == applicationInfo.uid) { "Unauthorized RootService caller UID: $callerUid" }
    }
}
