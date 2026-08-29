@file:Suppress("UnusedImports", "TooManyFunctions", "LongMethod", "LargeClass", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeCons")

package com.kunk.singbox.repository

import android.util.Log
import com.kunk.singbox.R
import com.kunk.singbox.database.entity.ProfileEntity
import com.kunk.singbox.model.*
import com.kunk.singbox.model.PingResultCode
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal suspend fun ConfigRepository.deleteProfile(profileId: String) = withContext(Dispatchers.IO) {
    com.kunk.singbox.service.SubscriptionAutoUpdateWorker.cancel(context, profileId)
    val removedNodeIds = (profileNodes[profileId] ?: _allNodes.value.filter { it.sourceProfileId == profileId })
        .map { it.id }

    _profiles.update { list -> list.filter { it.id != profileId } }
    removeCachedConfig(profileId)
    dnsResolveStore.removeAllForProfile(profileId)
    profileNodes.remove(profileId)
    profileLastSelectedNode.remove(profileId)
    profileNodeMemoryMmkv.removeValueForKey(profileId)
    profileAutoSelectionMmkv.removeValueForKey(profileId)
    _profileAutoSelections.update { it - profileId }
    removeNodeLatencies(removedNodeIds)
    removedNodeIds.forEach(NodeProtectionStore::removeNode)
    updateAllNodesAndGroups()
    val configFile = File(configDir, "$profileId.json")
    if (configFile.exists() && !configFile.delete()) {
        Log.w(ConfigRepository.TAG, "Failed to delete profile config file: ${configFile.absolutePath}")
    }
    try {
        profileDao.deleteById(profileId)
    } catch (e: Exception) {
        Log.e(ConfigRepository.TAG, "Failed to delete profile from Room", e)
    }

    if (_activeProfileId.value == profileId) {
        val newActiveId = _profiles.value.firstOrNull()?.id
        _activeProfileId.value = newActiveId
        if (newActiveId != null) {
            setActiveProfile(newActiveId)
        } else {
            _nodes.value = emptyList()
            _activeNodeId.value = null
        }
    }
    saveProfiles()
}

internal suspend fun ConfigRepository.importProfileDirectly(profile: ProfileUi, config: SingBoxConfig) = withContext(Dispatchers.IO) {
    val deduplicatedConfig = deduplicateTags(config)
    val sortOrder = (profileDao.getMaxSortOrder() ?: -1) + 1
    val entity = ProfileEntity.fromUiModel(profile, sortOrder = sortOrder)
    val nodes = extractNodesFromConfigSync(deduplicatedConfig, profile.id)

    profileDao.insert(entity)
    cacheConfig(profile.id, deduplicatedConfig)
    profileNodes[profile.id] = nodes
    _profiles.update { list ->
        val filtered = list.filter { it.id != profile.id }
        filtered + profile
    }
    updateAllNodesAndGroups()
    if (_activeProfileId.value == null) {
        setActiveProfile(profile.id)
    }
}

internal fun ConfigRepository.toggleProfileEnabled(profileId: String) {
    var updatedProfile: ProfileUi? = null
    _profiles.update { list ->
        list.map {
            if (it.id == profileId) {
                it.copy(enabled = !it.enabled).also { profile ->
                    updatedProfile = profile
                }
            } else {
                it
            }
        }
    }
    saveProfiles()
    updatedProfile?.let { profile ->
        if (profile.type == ProfileType.Subscription) {
            if (profile.enabled && profile.autoUpdateInterval > 0) {
                com.kunk.singbox.service.SubscriptionAutoUpdateWorker.schedule(
                    context,
                    profile.id,
                    profile.autoUpdateInterval
                )
            } else {
                com.kunk.singbox.service.SubscriptionAutoUpdateWorker.cancel(context, profile.id)
            }
        }
    }
}

internal fun ConfigRepository.reorderProfiles(newProfiles: List<ProfileUi>) {
    _profiles.value = newProfiles
    saveProfiles()
}

internal fun ConfigRepository.updateProfileMetadata(
    profileId: String,
    newName: String,
    newUrl: String?,
    autoUpdateInterval: Int = 0,
    dnsPreResolve: Boolean = false,
    dnsServer: String? = null,
    dnsOverride: String? = null) {
    val normalizedAutoUpdateInterval =
        com.kunk.singbox.service.SubscriptionAutoUpdateWorker.normalizeIntervalMinutes(autoUpdateInterval)
    _profiles.update { list ->
        list.map {
            if (it.id == profileId) {
                it.copy(
                    name = newName,
                    url = newUrl,
                    autoUpdateInterval = normalizedAutoUpdateInterval,
                    dnsPreResolve = dnsPreResolve,
                    dnsServer = dnsServer,
                    dnsOverride = dnsOverride
                )
            } else {
                it
            }
        }
    }
    saveProfiles()
    com.kunk.singbox.service.SubscriptionAutoUpdateWorker.schedule(context, profileId, normalizedAutoUpdateInterval)
}

internal suspend fun ConfigRepository.testNodeLatency(nodeId: String): Long {
    val existing = inFlightLatencyTests[nodeId]
    if (existing != null) {
        return existing.await()
    }

    val deferred = CompletableDeferred<Long>()
    val prev = inFlightLatencyTests.putIfAbsent(nodeId, deferred)
    if (prev != null) {
        return prev.await()
    }

    try {
        val result = withContext(Dispatchers.IO) {
            run {
                try {
                    val node = _nodes.value.find { it.id == nodeId }
                        ?: _allNodes.value.find { it.id == nodeId }
                    if (node == null) {
                        Log.e(ConfigRepository.TAG, "Node not found: $nodeId")
                        return@withContext -1L
                    }
                    if (node.meteredProtected && !isMeteredNodeUseAuthorized(nodeId)) {
                        LogRepository.getInstance().addLog(
                            "WARN: 计费节点保护已阻止测速：${node.name}"
                        )
                        return@withContext PingResultCode.METERED_SELECTION_REQUIRED
                    }

                    val loadedConfig = loadConfig(node.sourceProfileId)
                    if (loadedConfig == null) {
                        Log.e(ConfigRepository.TAG, "Config not found for profile: ${node.sourceProfileId}")
                        return@withContext -1L
                    }
                    // endpoint-only WireGuard 归一为逻辑 outbound，避免测延迟时找不到节点
                    val config = ConfigRepository.normalizeWireGuardEndpointsForInternalUse(loadedConfig)

                    val rawOutbound = config.outbounds?.find { it.tag == node.name }
                    if (rawOutbound == null) {
                        Log.e(ConfigRepository.TAG, "Outbound not found: ${node.name}")
                        return@withContext -1L
                    }

                    val settings = settingsRepository.settings.first()
                    val runtimeContext = buildLatencyRuntimeContext(
                        profileId = node.sourceProfileId,
                        config = config,
                        settings = settings,
                        allowedProtectedNodeId = node.id.takeIf { node.meteredProtected }
                    )
                    val fixedOutbound = runtimeContext.outbounds.find { it.tag == rawOutbound.tag }
                    if (fixedOutbound == null) {
                        Log.e(ConfigRepository.TAG, "Outbound type removed: ${rawOutbound.type}")
                        return@withContext -1L
                    }
                    val allOutbounds = runtimeContext.outbounds
                    val latency = singBoxCore.testOutboundLatency(
                        fixedOutbound,
                        allOutbounds,
                        runtimeContext.dnsConfig
                    )

                    _nodes.update { list ->
                        list.map {
                            if (it.id == nodeId) {
                                it.copy(latencyMs = normalizeLatencyValue(latency))
                            } else {
                                it
                            }
                        }
                    }

                    profileNodes[node.sourceProfileId] = profileNodes[node.sourceProfileId]?.map {
                        if (it.id == nodeId) {
                            it.copy(latencyMs = normalizeLatencyValue(latency))
                        } else {
                            it
                        }
                    } ?: emptyList()
                    updateLatencyInAllNodes(nodeId, latency)

                    latency
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) {
                        throw e
                    }
                    Log.e(ConfigRepository.TAG, "Latency test error for $nodeId", e)
                    val nodeName = _nodes.value.find { it.id == nodeId }?.name
                        ?: _allNodes.value.find { it.id == nodeId }?.name
                    LogRepository.getInstance().addLog(context.getString(R.string.nodes_test_failed, nodeName ?: nodeId) + ": ${e.message}")
                    -1L
                }
            }
        }
        deferred.complete(result)
        return result
    } catch (e: CancellationException) {
        deferred.cancel(e)
        throw e
    } catch (e: Exception) {
        deferred.complete(-1L)
        return -1L
    } finally {
        inFlightLatencyTests.remove(nodeId, deferred)
    }
}

internal suspend fun ConfigRepository.clearAllNodesLatency() = withContext(Dispatchers.IO) {
    savedNodeLatencies.clear()
    nodeLatencyDao.deleteAll()

    _nodes.update { list ->
        list.map { it.copy(latencyMs = null) }
    }

    // Update profileNodes map
    profileNodes.keys.forEach { profileId ->
        profileNodes[profileId] = profileNodes[profileId]?.map {
            it.copy(latencyMs = null)
        } ?: emptyList()
    }
    _allNodes.update { list ->
        list.map { it.copy(latencyMs = null) }
    }
}

internal suspend fun ConfigRepository.testAllNodesLatency(
    targetNodeIds: List<String>? = null,
    useAllNodes: Boolean = false,
    onNodeComplete: ((nodeId: String, latencyMs: Long) -> Unit)? = null) = withContext(Dispatchers.IO) {
    val sourceNodes = if (useAllNodes) _allNodes.value else _nodes.value
    val selectedNodes = if (targetNodeIds != null) {
        sourceNodes.filter { it.id in targetNodeIds }
    } else {
        sourceNodes
    }
    val nodes = selectedNodes.filterNot(NodeUi::meteredProtected)

    val settings = settingsRepository.settings.first()
    val testInfoList = buildNodeTestInfos(nodes, settings)

    if (testInfoList.isEmpty()) {
        Log.w(ConfigRepository.TAG, "No valid nodes to test")
        return@withContext
    }

    val results = ConcurrentHashMap<String, SavedNodeLatency>()
    runLatencyBatchAndApply(
        runBatch = {
            testRegularOutboundsLatency(testInfoList, results, onNodeComplete)
        },
        applyResults = {
            applyLatencyResults(results)
        }
    )
}

internal suspend fun ConfigRepository.updateAllProfiles(): BatchUpdateResult = withContext(Dispatchers.IO) {
    val enabledProfiles = _profiles.value.filter { it.enabled && it.type == ProfileType.Subscription }

    if (enabledProfiles.isEmpty()) {
        return@withContext BatchUpdateResult()
    }
    val semaphore = Semaphore(3)
    val results = enabledProfiles.map { profile ->
        async {
            semaphore.withPermit {
                updateProfile(profile.id)
            }
        }
    }.awaitAll()

    BatchUpdateResult(
        successWithChanges = results.count { it is SubscriptionUpdateResult.SuccessWithChanges },
        successNoChanges = results.count { it is SubscriptionUpdateResult.SuccessNoChanges },
        failed = results.count { it is SubscriptionUpdateResult.Failed },
        details = results
    )
}

@Suppress("LongMethod", "CognitiveComplexMethod")
internal suspend fun ConfigRepository.updateProfile(profileId: String): SubscriptionUpdateResult {
    val profile = _profiles.value.find { it.id == profileId }
        ?: return SubscriptionUpdateResult.Failed("Unknown Profile", "Profile not found")

    if (profile.url.isNullOrBlank()) {
        return SubscriptionUpdateResult.Failed(profile.name, "Subscription URL is empty")
    }

    val updateRunId = beginProfileUpdateRun(profileId)
    profileResetJobs.remove(profileId)?.cancel()
    updateProfileForCurrentRun(profileId, updateRunId) {
        it.copy(
            updateStatus = UpdateStatus.Updating,
            updateStage = SubscriptionUpdateStage.Requesting
        )
    }

    val result = try {
        importFromSubscriptionUpdate(profile, updateRunId)
    } catch (e: Exception) {
        SubscriptionUpdateResult.Failed(profile.name, e.message ?: "Subscription update failed")
    }
    updateProfileForCurrentRun(profileId, updateRunId) {
        it.copy(
            updateStatus = if (result is SubscriptionUpdateResult.Failed) {
                UpdateStatus.Failed
            } else {
                UpdateStatus.Success
            },
            lastUpdated = if (result is SubscriptionUpdateResult.Failed) {
                it.lastUpdated
            } else {
                System.currentTimeMillis()
            },
            updateStage = when {
                result is SubscriptionUpdateResult.Failed -> null
                it.updateStage == SubscriptionUpdateStage.DnsBackground -> it.updateStage
                else -> null
            }
        )
    }
    val resetJob = scope.launch {
        kotlinx.coroutines.delay(2000)
        updateProfileForCurrentRun(profileId, updateRunId) {
            if (it.updateStatus == UpdateStatus.Updating) {
                it
            } else {
                it.copy(
                    updateStatus = UpdateStatus.Idle,
                    updateStage = it.updateStage.takeIf { stage ->
                        stage == SubscriptionUpdateStage.DnsBackground
                    }
                )
            }
        }
    }
    resetJob.invokeOnCompletion {
        profileResetJobs.remove(profileId, resetJob)
    }
    profileResetJobs[profileId] = resetJob

    return result
}

@Suppress("LongMethod", "CognitiveComplexMethod")
internal suspend fun ConfigRepository.importFromSubscriptionUpdate(
    profile: ProfileUi,
    updateRunId: Long
): SubscriptionUpdateResult = withContext(Dispatchers.IO) {
    var previousConfigText: String? = null
    try {
        val oldNodes = profileNodes[profile.id] ?: emptyList()
        val oldNodeNames = oldNodes.map { it.name }.toSet()
        val profileUrl = profile.url
        if (profileUrl.isNullOrBlank()) {
            return@withContext SubscriptionUpdateResult.Failed(profile.name, "Subscription URL is empty")
        }

        val fetchResult = fetchAndParseSubscription(
            url = profileUrl,
            onProgress = {},
            onStageChanged = { stage -> setProfileUpdateStage(profile.id, updateRunId, stage) }
        )
            ?: return@withContext SubscriptionUpdateResult.Failed(profile.name, "Failed to fetch subscription")

        val config = fetchResult.config
        val userInfo = fetchResult.userInfo

        val deduplicatedConfig = deduplicateTags(config)
        val newNodes = extractNodesFromConfig(deduplicatedConfig, profile.id)
        val newNodeNames = newNodes.map { it.name }.toSet()
        val addedNodes = newNodeNames - oldNodeNames
        val removedNodes = oldNodeNames - newNodeNames
        setProfileUpdateStage(profile.id, updateRunId, SubscriptionUpdateStage.Saving)
        previousConfigText = File(configDir, "${profile.id}.json")
            .takeIf { it.exists() }
            ?.readText()
        writeConfigFileOrThrow(profile.id, deduplicatedConfig)

        cacheConfig(profile.id, deduplicatedConfig)
        profileNodes[profile.id] = newNodes
        updateAllNodesAndGroups()
        if (_activeProfileId.value == profile.id) {
            applyActiveProfileNodes(profile.id, newNodes)
        }
        val defaultQrName = context.getString(R.string.profiles_qrcode_subscription)
        val finalName = resolveSubscriptionProfileName(
            currentName = profile.name,
            defaultQrName = defaultQrName,
            subscriptionName = fetchResult.subscriptionName
        )

        _profiles.update { list ->
            list.map {
                if (it.id == profile.id) {
                    it.copy(
                        name = finalName,
                        expireDate = userInfo?.expire ?: it.expireDate,
                        totalTraffic = userInfo?.total ?: it.totalTraffic,
                        usedTraffic = if (userInfo != null) (userInfo.upload + userInfo.download) else it.usedTraffic
                    )
                } else {
                    it
                }
            }
        }

        saveProfiles()
        if (profile.dnsPreResolve) {
            scope.launch {
                setProfileUpdateStage(profile.id, updateRunId, SubscriptionUpdateStage.DnsBackground)
                val success = preResolveDomainsForProfileBestEffort(
                    profile.id,
                    deduplicatedConfig,
                    profile.dnsServer
                )
                Log.d(
                    ConfigRepository.TAG,
                    "Background DNS pre-resolve for ${profile.id}, run=$updateRunId: success=$success"
                )
                setProfileUpdateStage(profile.id, updateRunId, null)
            }
        }
        buildSubscriptionUpdateSuccessResult(
            profileName = profile.name,
            addedNodes = addedNodes,
            removedNodes = removedNodes,
            totalCount = newNodes.size
        )
    } catch (e: Exception) {
        previousConfigText?.let { oldText ->
            runCatching {
                val oldConfig = gson.fromJson(oldText, SingBoxConfig::class.java)
                ConfigRepository.writeTextFileAtomically(File(configDir, "${profile.id}.json"), oldText)
                cacheConfig(profile.id, oldConfig)
                profileNodes[profile.id] = extractNodesFromConfigSync(oldConfig, profile.id)
                updateAllNodesAndGroups()
            }.onFailure { restoreError ->
                Log.e(ConfigRepository.TAG, "Failed to restore previous config after subscription update failure", restoreError)
            }
        }
        SubscriptionUpdateResult.Failed(profile.name, e.message ?: "Subscription update failed")
    }
}

internal fun ConfigRepository.buildSubscriptionUpdateSuccessResult(
    profileName: String,
    addedNodes: Set<String>,
    removedNodes: Set<String>,
    totalCount: Int
): SubscriptionUpdateResult {
    return if (addedNodes.isNotEmpty() || removedNodes.isNotEmpty()) {
        SubscriptionUpdateResult.SuccessWithChanges(
            profileName = profileName,
            addedCount = addedNodes.size,
            removedCount = removedNodes.size,
            totalCount = totalCount
        )
    } else {
        SubscriptionUpdateResult.SuccessNoChanges(
            profileName = profileName,
            totalCount = totalCount
        )
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
internal suspend fun ConfigRepository.generateConfigFile(
    selectedProfileId: String? = null,
    selectedNodeId: String? = null,
    forceManualSelection: Boolean = false,
    candidateRequestId: String? = null
): ConfigRepository.ConfigGenerationResult? = withContext(Dispatchers.IO) {
    lastConfigGenerationError = null
    try {
        settingsRepository.reloadFromStorage()
        awaitInitialProfilesLoaded()
        val activeId = selectedProfileId?.takeIf { it.isNotBlank() }
            ?: _activeProfileId.value
            ?: activeStateDao.get()?.activeProfileId
            ?: return@withContext null
        val activeProfile = _profiles.value.find { it.id == activeId }
        val config = loadConfigWithLegacyEchRepair(activeProfile, activeId) ?: return@withContext null
        ConfigRepository.findUnsupportedAndroidCapability(config)?.let { message ->
            throw IllegalArgumentException(message)
        }
        val activeNodeId = selectedNodeId?.takeIf { it.isNotBlank() }
            ?: _activeNodeId.value
            ?: activeStateDao.get()?.activeNodeId
        val activeProfileAutoSelectionEnabled =
            !forceManualSelection && isProfileAutoSelectionEnabled(activeId)

        val allNodesSnapshot = _allNodes.value.takeIf { it.isNotEmpty() } ?: loadAllNodesSnapshot()
        val activeNode = _nodes.value.find { it.id == activeNodeId }
            ?: allNodesSnapshot.find { it.id == activeNodeId }
        val sanitizedSettings = settingsRepository.settings.first()
        val telegramCapturedPackages = filterVpnCapturedPackages(
            sanitizedSettings,
            listOf("org.telegram.messenger")
        )
        val appRouteScopeTrace = "[APP_ROUTE_TRACE] scope mode=${sanitizedSettings.routingMode} " +
            "capture=${sanitizedSettings.resolvedTrafficCaptureMode()} " +
            "vpnAppMode=${sanitizedSettings.vpnAppMode} " +
            "telegramCaptured=${telegramCapturedPackages.isNotEmpty()} " +
            "telegramPackages=${telegramCapturedPackages.joinToString("|")} " +
            "appRules=${sanitizedSettings.appRules.count { it.enabled }} " +
            "appGroups=${sanitizedSettings.appGroups.count { it.enabled }}"
        Log.i(ConfigRepository.TAG, appRouteScopeTrace)
        LogRepository.getInstance().addAlwaysLog("INFO $appRouteScopeTrace")
        if (activeNode?.meteredProtected == true &&
            !NodeProtectionStore.isUseAuthorized(
                nodeId = activeNode.id,
                activeNodeId = activeNodeId,
                autoSelectionEnabled = activeProfileAutoSelectionEnabled
            ) &&
            !activeProfileAutoSelectionEnabled
        ) {
            throw MeteredNodeConfigurationException(
                listOf("受保护节点「${activeNode.name}」尚未经过本次手动选择授权")
            )
        }
        val log = buildRunLogConfig(sanitizedSettings)
        val experimental = buildRunExperimentalConfig(sanitizedSettings)
        val customRuleSets = buildCustomRuleSets(sanitizedSettings)

        val dnsOverrideConfig = parseDnsOverride(activeProfile?.dnsOverride)
        val rawOutboundsContext = buildRunOutbounds(
            config,
            activeId,
            activeNode,
            sanitizedSettings,
            allNodesSnapshot,
            activeProfile?.dnsPreResolve ?: false,
            dnsOverrideConfig,
            activeProfileAutoSelectionEnabled
        )
        val serverAddressStrategy = ConfigRepository.resolveOutboundServerAddressStrategy(
            sanitizedSettings.serverAddressStrategy,
            sanitizedSettings.ipVersionMode
        )
        logOutboundServerAddressStrategy(
            scope = "run_config",
            strategy = sanitizedSettings.serverAddressStrategy,
            ipVersionMode = sanitizedSettings.ipVersionMode,
            resolvedStrategy = serverAddressStrategy
        )
        val defaultResolverOutbounds = ConfigRepository.applyDefaultOutboundDomainResolver(
            rawOutboundsContext.outbounds,
            ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG,
            serverAddressStrategy
        )
        val outboundsContext = rawOutboundsContext.copy(
            outbounds = if (dnsOverrideConfig != null) {
                ConfigRepository.applyDnsOverrideDomainResolvers(defaultResolverOutbounds, dnsOverrideConfig)
            } else {
                defaultResolverOutbounds
            }
        )
        val rootRoutingPlan = if (
            sanitizedSettings.resolvedTrafficCaptureMode() == TrafficCaptureMode.ROOT_TRANSPARENT
        ) {
            buildRootAppRoutingPlan(
                settings = sanitizedSettings,
                outboundsContext = outboundsContext,
                generation = RootGenerationStore.nextGeneration(context.filesDir)
            )
        } else {
            null
        }
        val inbounds = buildRunInbounds(sanitizedSettings, rootRoutingPlan)
        val endpoints = buildRunEndpoints(
            baseConfig = config,
            activeProfileId = activeId,
            allNodes = allNodesSnapshot,
            nodeTagMap = outboundsContext.nodeTagMap,
            excludedOutboundTags = outboundsContext.disallowedProtectedTags
        )
        val dns = buildRunDns(
            sanitizedSettings,
            customRuleSets,
            outboundsContext,
            dnsOverrideConfig,
            config.dns,
            rootRoutingPlan
        )
        val route = buildRunRoute(
            sanitizedSettings,
            outboundsContext.selectorTag,
            outboundsContext.outbounds,
            outboundsContext.ruleNodeTagResolver,
            customRuleSets,
            rootRoutingPlan
        )
        val runtimeOutbounds = ConfigRepository.pruneUnreachableGroupOutbounds(
            outbounds = outboundsContext.outbounds,
            route = route,
            dns = dns,
            endpoints = endpoints.orEmpty()
        )

        lastTagToNodeName = outboundsContext.nodeTagMap.mapNotNull { (nodeId, tag) ->
            val name = allNodesSnapshot.firstOrNull { it.id == nodeId }?.name
            if (name.isNullOrBlank() || tag.isBlank()) null else (tag to name)
        }.toMap()

        val runConfig = config.copy(
            log = log,
            experimental = experimental,
            inbounds = inbounds,
            dns = dns,
            route = route,
            endpoints = endpoints,
            outbounds = runtimeOutbounds
        )

        val runtimeMappings = buildRuntimeNodeMappings(
            activeProfileId = activeId,
            baseConfig = config,
            runtimeOutbounds = runtimeOutbounds,
            runtimeEndpoints = endpoints.orEmpty(),
            nodeTagMap = outboundsContext.nodeTagMap,
            allNodes = allNodesSnapshot,
            explicitlyRoutedProtectedNodeIds = outboundsContext.explicitlyRoutedProtectedNodeIds
        )
        val routeOnlyProtectedTags = runtimeMappings
            .filterValues { it.nodeId in outboundsContext.routeOnlyProtectedNodeIds }
            .keys
        MeteredNodeConfigGuard.requireNoViolations(
            MeteredNodeConfigGuard.findExplicitRouteScopeViolations(
                config = runConfig,
                protectedTags = routeOnlyProtectedTags
            )
        )
        val unauthorizedRuntimeNodes = MeteredNodeConfigGuard.findUnauthorizedRuntimeNodes(
            mappings = runtimeMappings,
            protectedNodeIds = NodeProtectionStore.protectedNodeIds(),
            selectedNodeId = activeNodeId,
            manuallyAuthorizedNodeId = activeNodeId?.let(NodeProtectionStore::authorizedManualNodeId)
        )

        MeteredNodeConfigGuard.requireNoViolations(
            MeteredNodeConfigGuard.findConfigViolations(
                config = runConfig,
                protectedTags = outboundsContext.disallowedProtectedTags + unauthorizedRuntimeNodes.keys,
                includeGroupReferences = true
            )
        )

        ConfigRepository.requireValidApplicationRoutes(
            route = runConfig.route,
            availableTags = runtimeOutbounds.mapTo(mutableSetOf(), Outbound::tag) +
                endpoints.orEmpty().map(Endpoint::tag)
        )
        rootRoutingPlan?.let { ConfigRepository.requireValidRootApplicationRoutes(runConfig, it) }

        val validation = singBoxCore.validateConfig(stripInternalMetadata(runConfig))
        validation.exceptionOrNull()?.let { e ->
            val msg = e.cause?.message ?: e.message ?: "unknown error"
            Log.e(ConfigRepository.TAG, "Config pre-validation failed: $msg", e)
            throw Exception("Config validation failed: $msg", e)
        }
        val allTags = runConfig.outbounds.orEmpty().map { it.tag }.toSet() +
            runConfig.endpoints.orEmpty().map { it.tag }
        val activeProfileName = _profiles.value.find { it.id == activeId }?.name ?: "Profile"
        val activeAutoTag = ConfigRepository.buildRouteGroupAutoTag(
            ConfigRepository.buildProfileRouteTag(activeId, activeProfileName)
        ).takeIf { tag -> activeProfileAutoSelectionEnabled && tag in allTags }
        val candidateTag = activeAutoTag
            ?: activeNodeId?.let { outboundsContext.nodeTagMap[it] }
            ?: activeNode?.name

        val resolvedTag = when {
            candidateTag == null -> {
                val proxySelector = runConfig.outbounds?.find { it.tag == "PROXY" }
                proxySelector?.default ?: proxySelector?.outbounds?.firstOrNull()
            }
            allTags.contains(candidateTag) -> candidateTag
            else -> {
                Log.e(ConfigRepository.TAG, "Selected node tag '$candidateTag' not found in runtime outbounds, aborting switch")
                throw IllegalStateException("Selected node is not available in runtime outbounds: $candidateTag")
            }
        }
        val runtimeConfigContent = gson.toJson(stripInternalMetadata(runConfig))
        val requestId = candidateRequestId.orEmpty()
        if (requestId.isNotBlank()) {
            require(requestId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
                "Invalid candidate config request ID"
            }
        }
        val rootGenerationDir = rootRoutingPlan?.let { plan ->
            RootGenerationStore.generationDirectory(context.filesDir, plan.generation).also { directory ->
                check(!Files.isSymbolicLink(directory.toPath())) {
                    "Root routing generation directory cannot be a symbolic link"
                }
                check(!directory.exists() || directory.isDirectory) {
                    "Root routing generation path is not a directory"
                }
                check(!directory.exists() && directory.mkdirs()) {
                    "Cannot create Root routing generation directory"
                }
            }
        }
        val configFile = rootGenerationDir?.let { File(it, "config.json") } ?: if (requestId.isBlank()) {
            File(context.filesDir, "running_config.json")
        } else {
            File(context.filesDir, "running_config_candidate_$requestId.json")
        }
        if (requestId.isBlank()) {
            check(NodeProtectionStore.replaceRuntimeMappings(runtimeMappings, runtimeConfigContent)) {
                "无法持久化运行时节点映射，已阻止启动"
            }
        }
        ConfigRepository.writeTextFileAtomically(configFile, runtimeConfigContent)
        val configDigest = ConfigRepository.sha256(runtimeConfigContent)
        val boundRootPlan = rootRoutingPlan?.copy(configFileSha256 = configDigest)
        val sidecarFile = rootGenerationDir?.let { File(it, "root-routing.json") }
        val manifestFile = rootGenerationDir?.let { File(it, "manifest.json") }
        val sidecarJson = boundRootPlan?.let(gson::toJson).orEmpty()
        val sidecarDigest = sidecarJson.takeIf(String::isNotEmpty)?.let(ConfigRepository::sha256).orEmpty()
        if (boundRootPlan != null && sidecarFile != null && manifestFile != null) {
            try {
                check(boundRootPlan.staticPlanSha256 == RootAppRoutingCanonical.staticPlanSha256(boundRootPlan)) {
                    "Root static plan changed while binding config"
                }
                check(boundRootPlan.appRoutingSha256 == RootAppRoutingCanonical.appRoutingSha256(boundRootPlan)) {
                    "Root app routing plan changed while binding config"
                }
                ConfigRepository.writeTextFileAtomically(sidecarFile, sidecarJson)
                check(RootRoutingArtifactValidator.requireBoundPlanJson(sidecarJson) == boundRootPlan) {
                    "Root sidecar serialization changed the routing plan"
                }
                val manifest = RootRoutingManifest(
                    generation = boundRootPlan.generation,
                    configLength = runtimeConfigContent.toByteArray(Charsets.UTF_8).size.toLong(),
                    configFileSha256 = configDigest,
                    sidecarLength = sidecarJson.toByteArray(Charsets.UTF_8).size.toLong(),
                    sidecarFileSha256 = sidecarDigest,
                    staticPlanSha256 = boundRootPlan.staticPlanSha256,
                    appRoutingSha256 = boundRootPlan.appRoutingSha256
                )
                val manifestJson = gson.toJson(manifest)
                RootRoutingArtifactValidator.requireManifestJson(manifestJson)
                ConfigRepository.writeTextFileAtomically(manifestFile, manifestJson)
            } catch (error: Exception) {
                runCatching {
                    RootGenerationStore.deleteGeneration(context.filesDir, boundRootPlan.generation)
                }.onFailure(error::addSuppressed)
                throw error
            }
        }
        if (requestId.isNotBlank()) {
            if (!NodeProtectionStore.stageRuntimeMappings(requestId, runtimeMappings, runtimeConfigContent)) {
                runCatching {
                    rootRoutingPlan?.let { plan ->
                        RootGenerationStore.deleteGeneration(context.filesDir, plan.generation)
                    } ?: configFile.delete()
                }
                throw IllegalStateException("无法暂存候选配置的运行时节点映射，已阻止启动")
            }
        }
        logRunningConfigPath(configFile, resolvedTag, allTags.size)

        ConfigRepository.ConfigGenerationResult(
            path = configFile.absolutePath,
            activeNodeTag = resolvedTag,
            outboundTags = allTags,
            activeNodeName = activeNode?.name.takeIf { activeAutoTag == null },
            requestId = requestId,
            configDigest = configDigest,
            appRoutingDigest = ConfigRepository.appRoutingDigest(sanitizedSettings),
            rootRoutingSidecarPath = sidecarFile?.absolutePath.orEmpty(),
            rootRoutingManifestPath = manifestFile?.absolutePath.orEmpty(),
            rootRoutingSidecarJson = sidecarJson,
            rootRoutingSidecarDigest = sidecarDigest,
            rootRoutingStaticPlanDigest = boundRootPlan?.staticPlanSha256.orEmpty(),
            rootRoutingAppDigest = boundRootPlan?.appRoutingSha256.orEmpty(),
            rootRoutingGeneration = boundRootPlan?.generation ?: 0L
        )
    } catch (e: Exception) {
        lastConfigGenerationError = e.message ?: "配置生成失败"
        Log.e(ConfigRepository.TAG, "Failed to generate config file", e)
        LogRepository.getInstance().addAlwaysLog(
            "ERROR [CFG] ${lastConfigGenerationError.orEmpty()}"
        )
        null
    }
}

@Suppress("LongParameterList")
internal fun ConfigRepository.buildRuntimeNodeMappings(
    activeProfileId: String,
    baseConfig: SingBoxConfig,
    runtimeOutbounds: List<Outbound>,
    runtimeEndpoints: List<Endpoint>,
    nodeTagMap: Map<String, String>,
    allNodes: List<NodeUi>,
    explicitlyRoutedProtectedNodeIds: Set<String>
): Map<String, RuntimeNodeRef> {
    val physicalTypes = setOf(
        "shadowsocks", "vmess", "vless", "trojan", "hysteria", "hysteria2",
        "tuic", "wireguard", "shadowtls", "ssh", "anytls", "naive", "http", "socks"
    )
    val runtimePhysicalTags = runtimeOutbounds
        .filter { it.type.lowercase() in physicalTypes }
        .mapTo(mutableSetOf(), Outbound::tag)
        .apply { runtimeEndpoints.mapTo(this, Endpoint::tag) }
    val nodesById = allNodes.associateBy(NodeUi::id)
    val result = linkedMapOf<String, RuntimeNodeRef>()

    nodeTagMap.forEach { (nodeId, runtimeTag) ->
        if (runtimeTag !in runtimePhysicalTags) return@forEach
        val node = nodesById[nodeId]
        result[runtimeTag] = RuntimeNodeRef(
            nodeId = nodeId,
            nodeName = node?.name ?: runtimeTag,
            meteredProtected = NodeProtectionStore.isProtected(nodeId),
            explicitRouteAuthorized = nodeId in explicitlyRoutedProtectedNodeIds
        )
    }
    baseConfig.outbounds.orEmpty()
        .filter { it.type.lowercase() in physicalTypes && it.tag in runtimePhysicalTags }
        .forEach { outbound ->
            if (result.containsKey(outbound.tag)) return@forEach
            val nodeId = ConfigRepository.stableNodeId(activeProfileId, outbound.tag)
            result[outbound.tag] = RuntimeNodeRef(
                nodeId = nodeId,
                nodeName = outbound.tag,
                meteredProtected = NodeProtectionStore.isProtected(nodeId),
                explicitRouteAuthorized = nodeId in explicitlyRoutedProtectedNodeIds
            )
        }
    baseConfig.endpoints.orEmpty()
        .filter { it.tag in runtimePhysicalTags }
        .forEach { endpoint ->
            if (result.containsKey(endpoint.tag)) return@forEach
            val nodeId = ConfigRepository.stableNodeId(activeProfileId, endpoint.tag)
            result[endpoint.tag] = RuntimeNodeRef(
                nodeId = nodeId,
                nodeName = endpoint.tag,
                meteredProtected = NodeProtectionStore.isProtected(nodeId),
                explicitRouteAuthorized = nodeId in explicitlyRoutedProtectedNodeIds
            )
        }
    return result
}

internal fun ConfigRepository.logRunningConfigPath(configFile: File, activeNodeTag: String?, outboundCount: Int) {
    val logRepo = LogRepository.getInstance()
    if (!logRepo.isEnabled()) return

    val exportDir = context.getExternalFilesDir(null)?.let { File(it, "exports").absolutePath }
        ?: "(unavailable)"
    logRepo.addLog(
        "INFO [CFG] running_config.json generated: path=${configFile.absolutePath}, " +
            "size=${configFile.length()} bytes, activeNodeTag=${activeNodeTag ?: "(none)"}, " +
            "outbounds=$outboundCount"
    )
    logRepo.addLog("INFO [CFG] running_config export dir: $exportDir")
}
