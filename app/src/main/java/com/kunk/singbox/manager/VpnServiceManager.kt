package com.kunk.singbox.manager

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.DataPlaneStatus
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.PerAppVpnPolicy
import com.kunk.singbox.model.TrafficCaptureMode
import com.kunk.singbox.repository.*
import com.kunk.singbox.repository.NodeProtectionStore
import com.kunk.singbox.repository.RootGenerationMarker
import com.kunk.singbox.repository.RootGenerationStore
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.root.RootTransparentForegroundService
import com.kunk.singbox.service.manager.VpnStopInitiator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@Suppress("TooManyFunctions")
object VpnServiceManager {
    private const val TAG = "VpnServiceManager"

    data class StartCommand(
        val serviceClass: Class<*>,
        val action: String,
        val configPath: String? = null,
        val requestId: String? = null,
        val cleanCache: Boolean = false
    )

    @Volatile
    private var cachedCaptureMode: TrafficCaptureMode? = null

    @Volatile
    private var lastTunCheckTime: Long = 0L

    private const val CACHE_VALIDITY_MS = 5_000L
    private const val PER_APP_APPLY_TIMEOUT_MS = 10_000L
    private const val PER_APP_APPLY_POLL_MS = 100L
    private val restartHandler = Handler(Looper.getMainLooper())
    private val perAppApplyMutex = Mutex()
    private val perAppApplyGeneration = AtomicLong(0L)

    @Volatile
    private var pendingRestartTask: Runnable? = null

    @Volatile
    private var pendingRestartVersion: Long = 0L

    fun isRunning(): Boolean {
        val persistedActive = VpnStateStore.getActive()
        val pending = VpnStateStore.getPending()

        if (pending.isNotEmpty()) {
            return persistedActive || pending == "starting" || pending == "stopping"
        }

        if (persistedActive) {
            return true
        }

        return SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value
    }

    fun isStarting(): Boolean {
        return SingBoxRemote.isStarting.value
    }

    internal fun newCandidateRequestId(mode: TrafficCaptureMode): String? {
        return if (mode == TrafficCaptureMode.ROOT_TRANSPARENT) UUID.randomUUID().toString() else null
    }

    fun toggleVpn(context: Context): Result<Unit> {
        return if (isRunning()) {
            stopVpn(context, VpnStopInitiator.USER_UI)
        } else {
            startVpn(context)
        }
    }

    fun startVpn(context: Context): Result<Unit> {
        return startVpn(context, captureMode(context))
    }

    fun buildStartCommand(
        tunMode: Boolean,
        configPath: String? = null,
        requestId: String? = null,
        cleanCache: Boolean = false
    ): StartCommand = buildStartCommand(
        mode = if (tunMode) TrafficCaptureMode.VPN else TrafficCaptureMode.PROXY_ONLY,
        configPath = configPath,
        requestId = requestId,
        cleanCache = cleanCache
    )

    fun buildStartCommand(
        mode: TrafficCaptureMode,
        configPath: String? = null,
        requestId: String? = null,
        cleanCache: Boolean = false
    ): StartCommand {
        return when (mode) {
            TrafficCaptureMode.VPN -> {
                StartCommand(
                    serviceClass = SingBoxService::class.java,
                    action = SingBoxService.ACTION_START,
                    configPath = configPath,
                    requestId = requestId,
                    cleanCache = cleanCache
                )
            }
            TrafficCaptureMode.ROOT_TRANSPARENT -> StartCommand(
                serviceClass = RootTransparentForegroundService::class.java,
                action = RootTransparentForegroundService.ACTION_START,
                configPath = configPath,
                requestId = requestId,
                cleanCache = false
            )
            TrafficCaptureMode.PROXY_ONLY -> {
                StartCommand(
                    serviceClass = ProxyOnlyService::class.java,
                    action = ProxyOnlyService.ACTION_START,
                    configPath = configPath,
                    requestId = requestId,
                    cleanCache = cleanCache
                )
            }
        }
    }

    internal fun shouldDispatchStopToService(
        activeMode: VpnStateStore.CoreMode,
        serviceMode: VpnStateStore.CoreMode
    ): Boolean {
        return activeMode != VpnStateStore.CoreMode.NONE && activeMode == serviceMode
    }

    suspend fun applyPerAppRuleChangeIfRunning(
        context: Context,
        expectedRevision: Long? = null
    ): Result<Boolean> {
        val appContext = context.applicationContext
        val desiredPolicy = PerAppVpnPolicy.from(SettingsRepository.getInstance(appContext).settings.value)
        val targetRevision = expectedRevision ?: desiredPolicy.revision
        val targetDigest = desiredPolicy.digest()
        val targetRoutingDigest = ConfigRepository.appRoutingDigest(
            SettingsRepository.getInstance(appContext).settings.value
        )
        val requestGeneration = perAppApplyGeneration.incrementAndGet()
        return perAppApplyMutex.withLock {
            if (requestGeneration != perAppApplyGeneration.get()) return@withLock Result.success(false)
            applyPerAppRuleChangeLocked(
                appContext = appContext,
                targetRevision = targetRevision,
                targetDigest = targetDigest,
                targetRoutingDigest = targetRoutingDigest,
                requestGeneration = requestGeneration
            )
        }
    }

    @Suppress("LongMethod", "CognitiveComplexMethod")
    private suspend fun applyPerAppRuleChangeLocked(
        appContext: Context,
        targetRevision: Long,
        targetDigest: String,
        targetRoutingDigest: String,
        requestGeneration: Long
    ): Result<Boolean> {
        if (!isPerAppRuntimeReady()) return Result.success(false)

        val runtimeMode = VpnStateStore.getMode()
        val runningConfigFile = File(appContext.filesDir, "running_config.json")
        val previousRootMarker = if (runtimeMode == VpnStateStore.CoreMode.ROOT) {
            RootGenerationStore.readCurrentStrict(appContext.filesDir)
        } else {
            null
        }
        val previousConfig = runCatching {
            if (previousRootMarker != null) {
                RootGenerationStore.configFile(appContext.filesDir, previousRootMarker).readText()
            } else {
                runningConfigFile.takeIf(File::isFile)?.readText()
            }
        }.getOrNull()
        var candidatePath: String? = null
        var restartDispatched = false
        return try {
            val generation = generatePerAppConfig(appContext)
            candidatePath = generation.path
            if (!isPerAppRuntimeReady()) {
                discardCandidateConfig(generation.path)
                return Result.success(false)
            }
            dispatchPerAppRestart(appContext, generation, targetRevision)
            restartDispatched = true
            val confirmed = awaitPerAppPolicyConfirmation(
                targetRevision,
                targetDigest,
                targetRoutingDigest,
                generation,
                requestGeneration,
                runtimeMode
            )
            if (!confirmed && requestGeneration == perAppApplyGeneration.get()) {
                throw IllegalStateException("Per-app VPN policy apply was not confirmed")
            }
            if (confirmed) {
                promoteCandidateConfig(appContext, generation)
            } else {
                discardCandidateConfig(generation.path)
            }
            Result.success(confirmed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply per-app VPN policy", e)
            if (restartDispatched && !previousConfig.isNullOrBlank()) {
                rollbackPerAppConfig(
                    appContext,
                    runtimeMode,
                    previousConfig,
                    previousRootMarker,
                    candidatePath
                )
            } else {
                discardCandidateConfig(candidatePath)
            }
            Result.failure(e)
        }
    }

    private fun discardCandidateConfig(path: String?) {
        path?.takeIf(String::isNotBlank)?.let { candidatePath ->
            val candidate = File(candidatePath)
            val prefix = "running_config_candidate_"
            val requestId = candidate.name
                .takeIf { it.startsWith(prefix) && it.endsWith(".json") }
                ?.removePrefix(prefix)
                ?.removeSuffix(".json")
            requestId?.let(NodeProtectionStore::discardStagedRuntimeMappings)
            val rootFilesDir = runCatching { resolveFilesDir(candidate) }.getOrNull()
            val rootGeneration = rootFilesDir?.let { filesDir ->
                RootGenerationStore.generationForConfigPath(filesDir, candidate.absolutePath)
            }
            runCatching {
                if (rootGeneration != null) {
                    val current = RootGenerationStore.readCurrent(rootFilesDir)
                    val lastGood = RootGenerationStore.readLastGood(rootFilesDir)
                    if (current?.generation != rootGeneration && lastGood?.generation != rootGeneration) {
                        RootGenerationStore.deleteGeneration(rootFilesDir, rootGeneration)
                    }
                } else {
                    candidate.delete()
                }
            }
        }
    }

    private fun isPerAppRuntimeReady(): Boolean =
        isRunning() &&
            !VpnStateStore.isManuallyStopped() &&
            VpnStateStore.getPending().isBlank() &&
            VpnStateStore.getMode() in setOf(VpnStateStore.CoreMode.VPN, VpnStateStore.CoreMode.ROOT)

    private suspend fun generatePerAppConfig(appContext: Context): ConfigRepository.ConfigGenerationResult {
        val requestId = UUID.randomUUID().toString()
        return ConfigRepository.getInstance(appContext)
            .generateConfigFile(candidateRequestId = requestId)
            ?.takeIf { it.path.isNotBlank() && it.configDigest.isNotBlank() && it.appRoutingDigest.isNotBlank() }
            ?: throw IllegalStateException("Failed to generate config for per-app rule change")
    }

    private fun dispatchPerAppRestart(
        appContext: Context,
        generation: ConfigRepository.ConfigGenerationResult,
        targetRevision: Long
    ) {
        if (VpnStateStore.getMode() == VpnStateStore.CoreMode.ROOT) {
            appContext.startService(Intent(appContext, RootTransparentForegroundService::class.java).apply {
                action = RootTransparentForegroundService.ACTION_RESTART
                putExtra(RootTransparentForegroundService.EXTRA_CONFIG_PATH, generation.path)
                putExtra(RootTransparentForegroundService.EXTRA_APP_ROUTE_REQUEST_ID, generation.requestId)
            })
            return
        }
        appContext.startService(Intent(appContext, SingBoxService::class.java).apply {
            action = SingBoxService.ACTION_FULL_RESTART
            putExtra(SingBoxService.EXTRA_CONFIG_PATH, generation.path)
            putExtra(SingBoxService.EXTRA_PER_APP_RULE_RESTART, true)
            putExtra(SingBoxService.EXTRA_PER_APP_POLICY_REVISION, targetRevision)
            putExtra(SingBoxService.EXTRA_APP_ROUTE_REQUEST_ID, generation.requestId)
            putExtra(SingBoxService.EXTRA_CONFIG_DIGEST, generation.configDigest)
            putExtra(SingBoxService.EXTRA_APP_ROUTING_DIGEST, generation.appRoutingDigest)
        })
    }

    @Suppress("LongParameterList", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod")
    private suspend fun awaitPerAppPolicyConfirmation(
        targetRevision: Long,
        targetDigest: String,
        targetRoutingDigest: String,
        generation: ConfigRepository.ConfigGenerationResult,
        requestGeneration: Long,
        runtimeMode: VpnStateStore.CoreMode
    ): Boolean = withTimeoutOrNull<Boolean>(PER_APP_APPLY_TIMEOUT_MS) {
        while (true) {
            if (requestGeneration != perAppApplyGeneration.get()) return@withTimeoutOrNull false
            val applied = VpnStateStore.getAppliedPerAppPolicy()
            val runtime = VpnStateStore.getRuntimeStateSnapshot()
            val rootReady = runtimeMode != VpnStateStore.CoreMode.ROOT || (
                runtime.stateOrdinal == com.kunk.singbox.service.ServiceState.RUNNING.ordinal &&
                    runtime.readiness.status == DataPlaneStatus.READY &&
                    runtime.readiness.rootRoutingGeneration == generation.rootRoutingGeneration &&
                    runtime.readiness.rootConfigSha256 == generation.configDigest &&
                    runtime.readiness.rootSidecarSha256 == generation.rootRoutingSidecarDigest &&
                    runtime.readiness.rootStaticPlanSha256 == generation.rootRoutingStaticPlanDigest &&
                    runtime.readiness.rootAppRoutingSha256 == generation.rootRoutingAppDigest &&
                    runtime.readiness.rootResolvedPlanSha256 == applied.resolvedPlanSha256
                )
            if (applied.revision == targetRevision &&
                applied.digest == targetDigest &&
                applied.requestId == generation.requestId &&
                applied.configDigest == generation.configDigest &&
                applied.appRoutingDigest == targetRoutingDigest &&
                (runtimeMode != VpnStateStore.CoreMode.ROOT ||
                    (applied.sidecarFileSha256 == generation.rootRoutingSidecarDigest &&
                        applied.staticPlanSha256 == generation.rootRoutingStaticPlanDigest &&
                        applied.rootRoutingAppSha256 == generation.rootRoutingAppDigest &&
                        applied.resolvedPlanSha256.isNotBlank() &&
                        applied.runtimeGeneration == generation.rootRoutingGeneration &&
                        applied.rootRuntimeSessionId.isNotBlank())) &&
                rootReady
            ) {
                break
            }
            if (VpnStateStore.isManuallyStopped()) return@withTimeoutOrNull false
            val currentMode = VpnStateStore.getMode()
            if (currentMode != VpnStateStore.CoreMode.NONE && currentMode != runtimeMode) {
                return@withTimeoutOrNull false
            }
            delay(PER_APP_APPLY_POLL_MS)
        }
        true
    } ?: false

    private fun promoteCandidateConfig(
        appContext: Context,
        generation: ConfigRepository.ConfigGenerationResult
    ) {
        if (generation.rootRoutingGeneration > 0L) {
            val marker = RootGenerationStore.marker(
                generation.rootRoutingGeneration,
                generation.configDigest,
                generation.rootRoutingSidecarDigest,
                generation.rootRoutingStaticPlanDigest,
                generation.rootRoutingAppDigest
            )
            check(RootGenerationStore.readCurrentStrict(appContext.filesDir) == marker) {
                "Applied Root candidate generation was not committed by the runtime"
            }
            check(RootGenerationStore.cacheMatchesCurrent(appContext.filesDir, "running_config.json", marker)) {
                "Applied Root candidate cache does not match the committed generation"
            }
            Log.i(TAG, "[APP_ROUTE_TX] promoted Root request=${generation.requestId}")
            return
        }
        val content = File(generation.path).readText(Charsets.UTF_8)
        check(ConfigRepository.sha256(content) == generation.configDigest) {
            "Applied candidate config changed before promotion"
        }
        ConfigRepository.writeTextFileAtomically(File(appContext.filesDir, "running_config.json"), content)
        ConfigRepository.writeTextFileAtomically(File(appContext.filesDir, "last_good_running_config.json"), content)
        if (generation.path != File(appContext.filesDir, "running_config.json").absolutePath) {
            runCatching { File(generation.path).delete() }
        }
        Log.i(TAG, "[APP_ROUTE_TX] promoted request=${generation.requestId}")
    }

    private fun rollbackPerAppConfig(
        appContext: Context,
        runtimeMode: VpnStateStore.CoreMode,
        previousConfig: String,
        previousRootMarker: RootGenerationMarker?,
        candidatePath: String?
    ) {
        runCatching {
            val runningConfig = File(appContext.filesDir, "running_config.json")
            ConfigRepository.writeTextFileAtomically(runningConfig, previousConfig)
            check(NodeProtectionStore.replaceRuntimeMappings(emptyMap(), previousConfig)) {
                "无法恢复上一配置的运行时节点映射"
            }
            discardCandidateConfig(candidatePath)
            val intent = when (runtimeMode) {
                VpnStateStore.CoreMode.ROOT -> Intent(
                    appContext,
                    RootTransparentForegroundService::class.java
                ).apply {
                    action = RootTransparentForegroundService.ACTION_RESTART
                    val marker = previousRootMarker ?: error("Previous Root generation is unavailable")
                    check(RootGenerationStore.restorePrevious(appContext.filesDir, marker)) {
                        "无法恢复上一 Root 代次"
                    }
                    RootGenerationStore.restoreCompatibilityCaches(appContext.filesDir, marker)
                    putExtra(
                        RootTransparentForegroundService.EXTRA_CONFIG_PATH,
                        RootGenerationStore.configFile(appContext.filesDir, marker).absolutePath
                    )
                }
                VpnStateStore.CoreMode.VPN -> Intent(appContext, SingBoxService::class.java).apply {
                    action = SingBoxService.ACTION_FULL_RESTART
                    putExtra(SingBoxService.EXTRA_CONFIG_PATH, runningConfig.absolutePath)
                }
                else -> return
            }
            appContext.startService(intent)
            Log.w(TAG, "[APP_ROUTE_TX] candidate failed, rollback dispatched")
        }.onFailure { rollbackError ->
            Log.e(TAG, "[APP_ROUTE_TX] rollback failed", rollbackError)
            VpnStateStore.setLastError("应用分流应用失败，恢复上一配置也失败，请重新启动 VPN")
        }
    }

    private fun resolveFilesDir(config: File): File {
        val generationDirectory = config.parentFile ?: error("Root generation config has no parent")
        val generationsDirectory = generationDirectory.parentFile ?: error("Root generations directory is missing")
        check(generationsDirectory.name == RootGenerationStore.GENERATIONS_DIR_NAME)
        return generationsDirectory.parentFile ?: error("App files directory is missing")
    }

    fun startVpn(context: Context, mode: TrafficCaptureMode): Result<Unit> =
        startVpn(
            context,
            mode,
            configPath = null,
            requestId = null,
            cleanCache = false,
            pendingNodeName = null
        )

    fun startVpn(
        context: Context,
        mode: TrafficCaptureMode,
        configPath: String?,
        requestId: String? = null,
        cleanCache: Boolean,
        pendingNodeName: String? = null
    ): Result<Unit> {
        Log.d(TAG, "startVpn: mode=$mode")

        if (VpnStateStore.getPending() == "stopping") {
            return Result.failure(IllegalStateException("VPN stop is still in progress"))
        }

        val command = buildStartCommand(mode, configPath, requestId, cleanCache)
        val appContext = context.applicationContext
        val previousMode = VpnStateStore.getMode()
        val previousOwner = VpnStateStore.getStopOwnerMode()
        val targetMode = mode.toCoreMode()
        val intent = Intent(appContext, command.serviceClass).apply {
            action = command.action
            command.configPath?.let { putExtra(SingBoxService.EXTRA_CONFIG_PATH, it) }
            command.requestId?.takeIf(String::isNotBlank)?.let {
                putExtra(SingBoxService.EXTRA_APP_ROUTE_REQUEST_ID, it)
            }
            pendingNodeName?.let { putExtra(SingBoxService.EXTRA_PENDING_NODE_NAME, it) }
            if (command.cleanCache) {
                putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
            }
        }

        return runCatching {
            VpnStateStore.setStopOwnerMode(targetMode)
            VpnStateStore.setMode(targetMode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
            Unit
        }
            .onFailure { error ->
                if (!VpnStateStore.getActive() && VpnStateStore.getMode() == targetMode) {
                    VpnStateStore.setMode(previousMode)
                    previousOwner?.let(VpnStateStore::setStopOwnerMode)
                        ?: VpnStateStore.clearStopOwnerMode()
                }
                Log.e(TAG, "Failed to start VPN service", error)
            }
    }

    private fun TrafficCaptureMode.toCoreMode(): VpnStateStore.CoreMode = when (this) {
        TrafficCaptureMode.VPN -> VpnStateStore.CoreMode.VPN
        TrafficCaptureMode.ROOT_TRANSPARENT -> VpnStateStore.CoreMode.ROOT
        TrafficCaptureMode.PROXY_ONLY -> VpnStateStore.CoreMode.PROXY
    }

    private fun resolveActiveMode(): VpnStateStore.CoreMode {
        return when {
            RootTransparentForegroundService.isRunning || RootTransparentForegroundService.isStarting ->
                VpnStateStore.CoreMode.ROOT
            ProxyOnlyService.isRunning || ProxyOnlyService.isStarting -> VpnStateStore.CoreMode.PROXY
            SingBoxService.isRunning || SingBoxService.isStarting -> VpnStateStore.CoreMode.VPN
            else -> VpnStateStore.getMode()
        }
    }

    private fun resolveStopOwnerMode(): VpnStateStore.CoreMode {
        return VpnStateStore.getStopOwnerMode() ?: resolveActiveMode()
    }

    fun stopVpn(context: Context, initiator: VpnStopInitiator): Result<Unit> {
        Log.d(TAG, "stopVpn: initiator=${initiator.wireValue}")

        return runCatching {
            val appContext = context.applicationContext
            val activeMode = resolveStopOwnerMode()
            if (activeMode == VpnStateStore.CoreMode.NONE) {
                throw IllegalStateException("No active VPN stop owner")
            }
            VpnStateStore.setPending("stopping")
            val stopResults = buildList {
                if (shouldDispatchStopToService(activeMode, VpnStateStore.CoreMode.VPN)) {
                    add(runCatching {
                        appContext.startService(Intent(appContext, SingBoxService::class.java).apply {
                            action = SingBoxService.ACTION_STOP
                            putExtra(SingBoxService.EXTRA_STOP_INITIATOR, initiator.wireValue)
                        })
                    })
                }
                if (shouldDispatchStopToService(activeMode, VpnStateStore.CoreMode.PROXY)) {
                    add(runCatching {
                        appContext.startService(Intent(appContext, ProxyOnlyService::class.java).apply {
                            action = ProxyOnlyService.ACTION_STOP
                            putExtra(SingBoxService.EXTRA_STOP_INITIATOR, initiator.wireValue)
                        })
                    })
                }
                if (shouldDispatchStopToService(activeMode, VpnStateStore.CoreMode.ROOT)) {
                    add(runCatching {
                        appContext.startService(Intent(appContext, RootTransparentForegroundService::class.java).apply {
                            action = RootTransparentForegroundService.ACTION_STOP
                            putExtra(SingBoxService.EXTRA_STOP_INITIATOR, initiator.wireValue)
                        })
                    })
                }
            }
            if (stopResults.none { it.isSuccess }) {
                VpnStateStore.setPending("")
                throw stopResults.firstNotNullOfOrNull { it.exceptionOrNull() }
                    ?: IllegalStateException("Failed to send stop commands")
            }
            Unit
        }
            .onFailure { Log.e(TAG, "Failed to stop VPN service", it) }
    }

    fun forceStop(context: Context): Result<Unit> {
        Log.e(TAG, "forceStop: dispatching emergency stop")
        return runCatching {
            val appContext = context.applicationContext
            val activeMode = resolveStopOwnerMode()
            if (activeMode == VpnStateStore.CoreMode.NONE) {
                throw IllegalStateException("No active VPN stop owner")
            }
            VpnStateStore.setPending("stopping")
            val stopResults = buildList {
                if (shouldDispatchStopToService(activeMode, VpnStateStore.CoreMode.VPN)) {
                    add(runCatching {
                        appContext.startService(Intent(appContext, SingBoxService::class.java).apply {
                            action = SingBoxService.ACTION_FORCE_STOP
                        })
                    })
                }
                if (shouldDispatchStopToService(activeMode, VpnStateStore.CoreMode.PROXY)) {
                    add(runCatching {
                        appContext.startService(Intent(appContext, ProxyOnlyService::class.java).apply {
                            action = ProxyOnlyService.ACTION_FORCE_STOP
                        })
                    })
                }
                if (shouldDispatchStopToService(activeMode, VpnStateStore.CoreMode.ROOT)) {
                    add(runCatching {
                        appContext.startService(Intent(appContext, RootTransparentForegroundService::class.java).apply {
                            action = RootTransparentForegroundService.ACTION_FORCE_STOP
                        })
                    })
                }
            }
            if (stopResults.none { it.isSuccess }) {
                VpnStateStore.setPending("")
                throw stopResults.firstNotNullOfOrNull { it.exceptionOrNull() }
                    ?: IllegalStateException("Failed to send force stop commands")
            }
            Unit
        }.onFailure { Log.e(TAG, "Failed to force stop VPN service", it) }
    }

    fun restartVpn(context: Context) {
        Log.d(TAG, "restartVpn")

        val appContext = context.applicationContext
        val currentMode = captureMode(appContext)
        val version = pendingRestartVersion + 1L
        pendingRestartVersion = version

        pendingRestartTask?.let { restartHandler.removeCallbacks(it) }
        stopVpn(appContext, VpnStopInitiator.RESTART)

        val restartTask = Runnable {
            if (pendingRestartVersion != version) return@Runnable
            pendingRestartTask = null
            startVpn(appContext, currentMode)
        }
        pendingRestartTask = restartTask
        restartHandler.postDelayed(restartTask, 500)
    }

    private fun captureMode(context: Context? = null): TrafficCaptureMode {
        val now = System.currentTimeMillis()
        val cached = cachedCaptureMode

        if (cached != null && (now - lastTunCheckTime) < CACHE_VALIDITY_MS) {
            return cached
        }

        if (context != null) {
            val mode = SettingsRepository
                .getInstance(context.applicationContext)
                .settings
                .value
                .resolvedTrafficCaptureMode()

            cachedCaptureMode = mode
            lastTunCheckTime = now

            return mode
        }

        return cached ?: TrafficCaptureMode.VPN
    }

    fun refreshTunSetting(context: Context) {
        val mode = SettingsRepository
            .getInstance(context.applicationContext)
            .settings
            .value
            .resolvedTrafficCaptureMode()

        cachedCaptureMode = mode
        lastTunCheckTime = System.currentTimeMillis()

        Log.d(TAG, "refreshTunSetting: mode=$mode")
    }
}
