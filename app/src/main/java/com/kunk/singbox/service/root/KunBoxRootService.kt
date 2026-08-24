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
import com.kunk.singbox.model.VpnAppMode
import com.kunk.singbox.repository.config.InboundBuilder
import com.topjohnwu.superuser.ipc.RootService
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SystemProxyStatus
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    val apkPath: String
)

class KunBoxRootService : RootService() {
    companion object {
        private const val TAG = "KunBoxRootService"

        init {
            if (Process.myUid() == 0) {
                Libbox.version()
            }
        }
    }

    private val runtimeLock = Any()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val netfilterManager = RootNetfilterManager()

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
        selinuxDomain = "unknown",
        error = "Root service not initialized"
    )

    private var commandServer: CommandServer? = null
    private var watchdog: RootWatchdogInstaller? = null
    private var resourceGuard: RootResourceGuard? = null
    private var netfilterOwned = false
    private val startupTimings = linkedMapOf<String, Long>()

    @Volatile
    private var startInProgress = false

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
            apkPath: String?
        ): Bundle {
            enforceCaller()
            require(appUid == applicationInfo.uid) { "Unexpected KunBox UID" }
            startInProgress = true
            return try {
                synchronized(runtimeLock) {
                    startLocked(RootStartRequest(
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
                        apkPath = apkPath.orEmpty()
                    )).toBundle()
                }
            } finally {
                startInProgress = false
            }
        }

        override fun hotReload(configPath: String?, runtimeSessionId: String?): Bundle {
            enforceCaller()
            return synchronized(runtimeLock) {
                hotReloadLocked(configPath.orEmpty(), runtimeSessionId.orEmpty()).toBundle()
            }
        }

        override fun stop(runtimeSessionId: String?): Bundle {
            enforceCaller()
            return synchronized(runtimeLock) {
                stopLocked(runtimeSessionId.orEmpty()).toBundle()
            }
        }

        override fun resetNetwork(): Boolean {
            enforceCaller()
            return synchronized(runtimeLock) {
                val server = commandServer ?: return@synchronized false
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
        if (startInProgress) {
            serviceScope.cancel()
            super.onDestroy()
            return
        }
        synchronized(runtimeLock) { stopLocked(snapshot.runtimeSessionId) }
        watchdog?.close()
        watchdog = null
        resourceGuard?.close()
        resourceGuard = null
        serviceScope.cancel()
        super.onDestroy()
    }

    @Suppress("ReturnCount")
    private fun startLocked(request: RootStartRequest): RootRuntimeSnapshot {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var phaseStartedAt = startedAt
        startupTimings.clear()
        validateStartRequest(request)?.let { return failUnprotected(it) }
        if (snapshot.phase != RootRuntimePhase.STOPPED) {
            val stopped = stopLocked(snapshot.runtimeSessionId)
            if (stopped.phase != RootRuntimePhase.STOPPED) return stopped
        }
        val staleRuntimePresent = File("/data/adb/kunbox/session").exists()
        val prepareError = netfilterManager.prepareForStart(staleRuntimePresent).exceptionOrNull()
        if (prepareError != null) {
            return failRulesPresent(prepareError.message ?: "Cannot clear stale Root rules")
        }
        logStartPhase("cleanup", phaseStartedAt)
        phaseStartedAt = android.os.SystemClock.elapsedRealtime()
        updateSnapshot(
            phase = RootRuntimePhase.CORE_STARTING,
            runtimeSessionId = request.runtimeSessionId,
            error = "",
            rulesInstalled = false,
            watchdogReady = false
        )

        return try {
            startCommandServer(request.configPath, request.forceConnectionOwnerRouting)
            logStartPhase("core", phaseStartedAt)
            phaseStartedAt = android.os.SystemClock.elapsedRealtime()
            startProtection(request)
            logStartPhase("protection", phaseStartedAt)
            logStartPhase("root_total", startedAt)
            updateSnapshot(
                phase = RootRuntimePhase.RUNNING,
                ruleRevision = snapshot.ruleRevision + 1,
                rulesInstalled = true,
                watchdogReady = true,
                startupTimings = encodeStartupTimings()
            ).also { startResourceGuard(request.runtimeSessionId) }
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
        else -> null
    }

    private fun startCommandServer(configPath: String, forceConnectionOwnerRouting: Boolean) {
        val configContent = readValidatedConfig(configPath)
        val platformInterface = RootPlatformInterface(
            context = this,
            serviceScope = serviceScope,
            forceConnectionOwnerRouting = forceConnectionOwnerRouting,
            serverProvider = { commandServer }
        ).delegate
        val server = Libbox.newCommandServer(createServerHandler(), platformInterface)
        server.start()
        commandServer = server
        server.startOrReloadService(configContent, OverrideOptions().apply { autoRedirect = false })
    }

    private fun startProtection(request: RootStartRequest) {
        var phaseStartedAt = android.os.SystemClock.elapsedRealtime()
        val rootWatchdog = RootWatchdogInstaller(this)
        watchdog = rootWatchdog
        rootWatchdog.start(request.runtimeSessionId, request.apkPath) {
            handleWatchdogLost(request.runtimeSessionId)
        }.getOrThrow()
        check(rootWatchdog.awaitReady()) { "Root watchdog did not acknowledge the lease" }
        logStartPhase("watchdog", phaseStartedAt)
        phaseStartedAt = android.os.SystemClock.elapsedRealtime()
        updateSnapshot(phase = RootRuntimePhase.RULES_STAGING, watchdogReady = true)

        val mode = VpnAppMode.valueOf(request.appMode)
        val uidSelection = RootUidResolver().resolveCapturedUids(
            mode = mode,
            allowlist = request.allowlist,
            blocklist = request.blocklist,
            selfPackage = request.selfPackage,
            selfUid = request.appUid
        )
        logStartPhase("uid_scope", phaseStartedAt)
        phaseStartedAt = android.os.SystemClock.elapsedRealtime()
        if (mode == VpnAppMode.ALLOWLIST) {
            check(uidSelection.capturedUids.isNotEmpty()) { "Root allowlist contains no installed applications" }
        }
        val proxyIpv6 = request.proxyIpv6 && capabilityReport.tproxyIpv6 && capabilityReport.redirectIpv6
        netfilterManager.apply(
            RootNetfilterConfig(
                capturedUids = uidSelection.capturedUids,
                capturedUidRanges = uidSelection.capturedRanges,
                excludedUids = uidSelection.excludedUids,
                appUid = request.appUid,
                proxyIpv4 = request.proxyIpv4,
                proxyIpv6 = proxyIpv6,
                blockIpv4 = request.blockIpv4,
                blockIpv6 = request.blockIpv6 || (request.proxyIpv6 && !proxyIpv6),
                blockQuic = request.blockQuic,
                redirectPortIpv4 = InboundBuilder.ROOT_REDIRECT_PORT_IPV4,
                redirectPortIpv6 = InboundBuilder.ROOT_REDIRECT_PORT_IPV6,
                tproxyPortIpv4 = InboundBuilder.ROOT_TPROXY_PORT_IPV4,
                tproxyPortIpv6 = InboundBuilder.ROOT_TPROXY_PORT_IPV6
            )
        ).getOrThrow()
        logStartPhase("netfilter", phaseStartedAt)
        netfilterOwned = true
    }

    private fun logStartPhase(phase: String, startedAt: Long) {
        val durationMs = android.os.SystemClock.elapsedRealtime() - startedAt
        startupTimings[phase] = durationMs
        Log.i(TAG, "[ROOT_START] phase=$phase duration_ms=$durationMs")
    }

    private fun encodeStartupTimings(): String = formatRootStartupTimings(startupTimings)

    private fun hotReloadLocked(configPath: String, runtimeSessionId: String): RootRuntimeSnapshot {
        if (!matchesRunningSession(runtimeSessionId)) return snapshot
        return try {
            val configContent = readValidatedConfig(configPath)
            commandServer?.startOrReloadService(configContent, OverrideOptions().apply { autoRedirect = false })
                ?: error("Root CommandServer is unavailable")
            updateSnapshot(phase = RootRuntimePhase.RUNNING, error = "")
        } catch (error: Exception) {
            updateSnapshot(
                phase = RootRuntimePhase.RUNNING,
                error = error.message ?: "Root hot reload failed"
            )
        }
    }

    private fun stopLocked(runtimeSessionId: String): RootRuntimeSnapshot {
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
        val cleanupError = cleanupRulesVerified()
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
        resourceGuard?.stop()
        return if (cleanupError == null) {
            markStopped()
        } else {
            updateSnapshot(
                phase = RootRuntimePhase.FAILED_RULES_PRESENT,
                error = cleanupError.message ?: "Root cleanup failed",
                rulesInstalled = true
            )
        }
    }

    private fun rollbackLocked(): Throwable? {
        val cleanupError = cleanupRulesVerified()
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
        resourceGuard?.stop()
        return cleanupError
    }

    private fun markStopped(): RootRuntimeSnapshot {
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
            val cleanupError = cleanupRulesVerified()
            runCatching { commandServer?.closeService() }
            runCatching { commandServer?.close() }
            commandServer = null
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
                        val cleanupError = cleanupRulesVerified()
                        runCatching { commandServer?.closeService() }
                        runCatching { commandServer?.close() }
                        commandServer = null
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

    private fun readValidatedConfig(configPath: String): String {
        val configFile = File(configPath).canonicalFile
        val dataDir = File(applicationInfo.dataDir).canonicalFile
        check(configFile.path.startsWith(dataDir.path + File.separator)) { "Config path is outside app data" }
        check(configFile.isFile) { "Root config does not exist" }
        return configFile.readText(Charsets.UTF_8)
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
        phase = RootRuntimePhase.FAILED_RULES_PRESENT,
        error = message,
        rulesInstalled = true,
        watchdogReady = false
    )

    private fun cleanupRulesVerified(): Throwable? {
        watchdog?.stop(cleanupRules = true)
        watchdog?.close()
        watchdog = null
        val cleanupError = netfilterManager.cleanup().exceptionOrNull()
        if (cleanupError == null) netfilterOwned = false
        return cleanupError
    }

    private fun updateSnapshot(
        phase: RootRuntimePhase,
        runtimeSessionId: String = snapshot.runtimeSessionId,
        ruleRevision: Long = snapshot.ruleRevision,
        tproxyIpv4: Boolean = snapshot.tproxyIpv4,
        tproxyIpv6: Boolean = snapshot.tproxyIpv6,
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
            rootPid = Process.myPid(),
            tproxyIpv4 = tproxyIpv4,
            tproxyIpv6 = tproxyIpv6,
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
