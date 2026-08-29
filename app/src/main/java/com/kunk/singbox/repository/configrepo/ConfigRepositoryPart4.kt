@file:Suppress("UnusedImports", "TooManyFunctions", "LongMethod", "LargeClass", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeCons")

package com.kunk.singbox.repository

import android.content.Intent
import android.os.Build
import android.util.Log
import com.kunk.singbox.R
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.*
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.root.RootTransparentForegroundService
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal suspend fun ConfigRepository.setActiveProfileAndWait(profileId: String, targetNodeId: String? = null) {
    val nodes = setActiveProfile(profileId, targetNodeId)
        ?: loadProfileNodesWithLatency(profileId)
    if (nodes != null && _activeProfileId.value == profileId) {
        applyActiveProfileNodes(profileId, nodes, targetNodeId)
        saveProfilesImmediate()
    }
}

internal fun ConfigRepository.setActiveProfile(profileId: String, targetNodeId: String? = null): List<NodeUi>? {
    val currentProfileId = _activeProfileId.value
    val currentNodeId = _activeNodeId.value
    if (currentProfileId != null && currentNodeId != null && currentProfileId != profileId) {
        saveProfileNodeMemory(currentProfileId, currentNodeId)
    }

    _activeProfileId.value = profileId
    val cached = profileNodes[profileId]
    val selectedNodeId = targetNodeId
        ?: cached?.let { nodes ->
            _activeNodeId.value?.takeIf { activeId -> nodes.any { it.id == activeId } }
                ?: getProfileLastSelectedNode(profileId)?.takeIf { rememberedId ->
                    nodes.any { it.id == rememberedId }
                }
                ?: nodes.firstOrNull()?.id
        }
        ?: getProfileLastSelectedNode(profileId)
    val selectedNodeName = cached?.firstOrNull { it.id == selectedNodeId }?.name
    persistMainProcessSelection(profileId, selectedNodeId, selectedNodeName)

    fun updateState(nodes: List<NodeUi>) {
        applyActiveProfileNodes(profileId, nodes, targetNodeId)
    }

    if (cached != null) {
        updateState(cached)
    } else {
        _nodes.value = emptyList()
        scope.launch {
            val cfg = loadConfig(profileId) ?: return@launch
            val nodes = extractNodesFromConfig(cfg, profileId)
            val nodesWithLatency = nodes.map { node ->
                val latency = savedLatencyMs(node.id)
                if (latency != null) node.copy(latencyMs = latency) else node
            }
            profileNodes[profileId] = nodesWithLatency

            updateState(nodesWithLatency)

            if (allNodesUiActiveCount.get() > 0) {
                updateAllNodesAndGroups()
            }
        }
    }
    saveProfilesImmediate()
    return cached
}

internal fun ConfigRepository.setActiveNodeIdOnly(nodeId: String) {
    check(!isNodeMeteredProtected(nodeId)) {
        "Automatic selection cannot activate a metered protected node: $nodeId"
    }
    if (NodeProtectionStore.manuallyAuthorizedNodeId() != nodeId) {
        NodeProtectionStore.clearManualAuthorization()
    }
    _activeNodeId.value = nodeId
    VpnStateStore.setSelectedNode(_activeProfileId.value, nodeId)
    _nodes.value.find { it.id == nodeId }?.name?.let { VpnStateStore.setSelectedNodeLabel(it) }
    _activeProfileId.value?.let { profileId ->
        saveProfileNodeMemory(profileId, nodeId)
    }
    saveProfilesImmediate()
}

internal fun ConfigRepository.nodeDisplayName(nodeId: String, fallbackNodes: List<NodeUi>): String? {
    return _nodes.value.find { it.id == nodeId }?.name
        ?: fallbackNodes.find { it.id == nodeId }?.name
}

internal suspend fun ConfigRepository.setActiveNode(nodeId: String): Boolean {
    val result = setActiveNodeWithResult(nodeId)
    return result is ConfigRepository.NodeSwitchResult.Success || result is ConfigRepository.NodeSwitchResult.NotRunning
}

/** 配置卡优先选择安全节点；无安全候选时按记忆节点或稳定顺序明确选中。 */
@Suppress("ReturnCount")
internal suspend fun ConfigRepository.setActiveProfileWithResult(profileId: String): ConfigRepository.NodeSwitchResult {
    awaitInitialProfilesLoaded()
    val profile = _profiles.value.find { it.id == profileId }
        ?: return ConfigRepository.NodeSwitchResult.Failed("Profile not found: $profileId")
    val nodes = profileNodes[profileId] ?: loadProfileNodesWithLatency(profileId).orEmpty()
    val autoSelectionEnabled = isProfileAutoSelectionEnabled(profileId)
    val rememberedNodeId = getProfileLastSelectedNode(profileId)
    val targetNode = ConfigRepository.resolveManualProfileTarget(
        nodes = nodes,
        rememberedNodeId = rememberedNodeId,
        autoSelectionEnabled = autoSelectionEnabled
    )
        ?: return ConfigRepository.NodeSwitchResult.Failed(
            context.getString(R.string.profiles_no_safe_node)
        )

    val manualResult = setActiveNodeWithResult(targetNode.id)
    if (manualResult is ConfigRepository.NodeSwitchResult.Failed) return manualResult
    if (!autoSelectionEnabled ||
        targetNode.meteredProtected ||
        !targetNode.autoSelectionEligible
    ) {
        return manualResult
    }
    return enableAutoSelectionWithResult(profileId)
}

@Suppress("LongMethod", "CognitiveComplexMethod")
internal suspend fun ConfigRepository.enableAutoSelectionWithResult(profileId: String): ConfigRepository.NodeSwitchResult {
    return nodeSwitchGate.run {
        val profile = _profiles.value.find { it.id == profileId }
            ?: return@run ConfigRepository.NodeSwitchResult.Failed("Profile not found: $profileId")
        val previousProfileId = _activeProfileId.value
        val previousNodeId = _activeNodeId.value
        val previousAutoSelection = isProfileAutoSelectionEnabled(profileId)
        val previousMeteredAuthorization = NodeProtectionStore.manuallyAuthorizedNodeId()
        val previousCoreMode = VpnStateStore.getMode()
        val runningConfigFile = File(context.filesDir, "running_config.json")
        val previousRunningConfig = withContext(Dispatchers.IO) {
            runningConfigFile.takeIf { it.exists() }?.readText()
        }

        if (previousProfileId != profileId) {
            setActiveProfileAndWait(profileId)
        }
        NodeProtectionStore.clearManualAuthorization()
        if (_nodes.value.isEmpty()) {
            restoreAutoSelectionState(
                profileId,
                previousAutoSelection,
                previousProfileId,
                previousNodeId,
                previousRunningConfig,
                previousMeteredAuthorization
            )
            return@run ConfigRepository.NodeSwitchResult.Failed("Profile has no available nodes: ${profile.name}")
        }
        if (_nodes.value.none { isNodeAutoSelectionEligible(it.id) && !it.meteredProtected }) {
            restoreAutoSelectionState(
                profileId,
                previousAutoSelection,
                previousProfileId,
                previousNodeId,
                previousRunningConfig,
                previousMeteredAuthorization
            )
            return@run ConfigRepository.NodeSwitchResult.Failed(
                "Profile has no nodes participating in automatic selection: ${profile.name}"
            )
        }
        if (!saveProfileAutoSelection(profileId, true)) {
            restoreAutoSelectionState(
                profileId,
                previousAutoSelection,
                previousProfileId,
                previousNodeId,
                previousRunningConfig,
                previousMeteredAuthorization
            )
            return@run ConfigRepository.NodeSwitchResult.Failed("Failed to persist automatic selection")
        }

        val remoteRunning = SingBoxRemote.isRunning.value ||
            SingBoxRemote.isStarting.value ||
            VpnStateStore.getActive()
        if (!remoteRunning) {
            saveProfilesImmediate()
            return@run ConfigRepository.NodeSwitchResult.NotRunning
        }

        val generationResult = generateConfigFile()
        if (generationResult == null) {
            restoreAutoSelectionState(
                profileId,
                previousAutoSelection,
                previousProfileId,
                previousNodeId,
                previousRunningConfig,
                previousMeteredAuthorization
            )
            return@run ConfigRepository.NodeSwitchResult.Failed(
                lastConfigGenerationError ?: "Failed to generate automatic selection config"
            )
        }

        runCatching {
            requestFullRuntimeConfigReload(generationResult)
            lastRunOutboundTags = generationResult.outboundTags
            lastRunProfileId = profileId
            saveProfilesImmediate()
        }.fold(
            onSuccess = { ConfigRepository.NodeSwitchResult.Success },
            onFailure = { error ->
                Log.e(ConfigRepository.TAG, "Failed to enable automatic selection", error)
                restoreAutoSelectionState(
                    profileId,
                    previousAutoSelection,
                    previousProfileId,
                    previousNodeId,
                    previousRunningConfig,
                    previousMeteredAuthorization
                )
                restorePreviousRuntimeConfig(previousRunningConfig, previousCoreMode)
                ConfigRepository.NodeSwitchResult.Failed(
                    error.message ?: "Failed to apply automatic selection config"
                )
            }
        )
    }
}

internal suspend fun ConfigRepository.requestFullRuntimeConfigReload(result: ConfigRepository.ConfigGenerationResult) {
    val coreMode = VpnStateStore.getMode()
    val previousGeneration = VpnStateStore.getRuntimeStateSnapshot().generation
    requestRuntimeConfigReload(result, coreMode)
    check(awaitRuntimeRunningAfter(previousGeneration)) { "Timed out waiting for reloaded core" }
    if (result.activeNodeTag?.endsWith("#AUTO", ignoreCase = true) == true) {
        check(awaitConcreteRuntimeLabel()) { "Automatic group did not resolve to a concrete node" }
    }
}

internal fun ConfigRepository.requestRuntimeConfigReload(
    result: ConfigRepository.ConfigGenerationResult,
    coreMode: VpnStateStore.CoreMode
) {
    val intent = if (coreMode == VpnStateStore.CoreMode.PROXY) {
        Intent(context, ProxyOnlyService::class.java).apply {
            action = ProxyOnlyService.ACTION_START
            putExtra("node_id", _activeNodeId.value)
            putExtra("outbound_tag", result.activeNodeTag)
            putExtra(SingBoxService.EXTRA_PENDING_NODE_NAME, "")
            putExtra(ProxyOnlyService.EXTRA_CONFIG_PATH, result.path)
        }
    } else if (coreMode == VpnStateStore.CoreMode.ROOT) {
        Intent(context, RootTransparentForegroundService::class.java).apply {
            action = RootTransparentForegroundService.ACTION_RESTART
            putExtra(RootTransparentForegroundService.EXTRA_CONFIG_PATH, result.path)
        }
    } else {
        Intent(context, SingBoxService::class.java).apply {
            action = SingBoxService.ACTION_START
            putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
            putExtra("node_id", _activeNodeId.value)
            putExtra("outbound_tag", result.activeNodeTag)
            putExtra(SingBoxService.EXTRA_PENDING_NODE_NAME, "")
            putExtra(SingBoxService.EXTRA_CONFIG_PATH, result.path)
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

internal suspend fun ConfigRepository.awaitRuntimeRunningAfter(previousGeneration: Long): Boolean {
    return withTimeoutOrNull(RUNTIME_RELOAD_TIMEOUT_MS) {
        var consecutiveRunningSnapshots = 0
        while (consecutiveRunningSnapshots < 2) {
            delay(RUNTIME_RELOAD_POLL_INTERVAL_MS)
            val snapshot = VpnStateStore.getRuntimeStateSnapshot()
            consecutiveRunningSnapshots = if (
                snapshot.generation > previousGeneration &&
                snapshot.stateOrdinal == ServiceState.RUNNING.ordinal &&
                snapshot.lastError.isBlank()
            ) {
                consecutiveRunningSnapshots + 1
            } else {
                0
            }
        }
        true
    } == true
}

@Suppress("ComplexCondition")
internal suspend fun ConfigRepository.awaitRuntimeSelectionAfter(
    previousGeneration: Long,
    expectedNodeName: String,
    expectedOutboundTag: String?,
    timeoutMs: Long = RUNTIME_RELOAD_TIMEOUT_MS
): Boolean {
    val expectedLabels = setOfNotNull(
        expectedNodeName.takeIf(String::isNotBlank),
        expectedOutboundTag?.takeIf(String::isNotBlank)
    )
    val confirmed = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val snapshot = VpnStateStore.getRuntimeStateSnapshot()
            if (snapshot.manuallyStopped ||
                (snapshot.generation > previousGeneration && snapshot.lastError.isNotBlank())
            ) {
                return@withTimeoutOrNull false
            }
            if (isRuntimeSelectionConfirmed(snapshot, previousGeneration, expectedLabels)) {
                return@withTimeoutOrNull true
            }
            delay(RUNTIME_RELOAD_POLL_INTERVAL_MS)
        }
        false
    }
    return confirmed ?: isRuntimeSelectionConfirmed(
        snapshot = VpnStateStore.getRuntimeStateSnapshot(),
        previousGeneration = previousGeneration,
        expectedLabels = expectedLabels
    )
}

internal fun ConfigRepository.resolveRunningOutboundTags(configContent: String?): Set<String>? {
    if (configContent.isNullOrBlank()) return null
    return runCatching {
        val config = gson.fromJson(configContent, SingBoxConfig::class.java)
        (config.outbounds.orEmpty().map { it.tag } + config.endpoints.orEmpty().map { it.tag })
            .filter(String::isNotBlank)
            .toSet()
            .takeIf(Set<String>::isNotEmpty)
    }.onFailure { error ->
        Log.w(ConfigRepository.TAG, "Failed to recover running outbound tags", error)
    }.getOrNull()
}

internal suspend fun ConfigRepository.awaitConcreteRuntimeLabel(): Boolean {
    return withTimeoutOrNull(AUTO_GROUP_RESOLUTION_TIMEOUT_MS) {
        while (true) {
            val label = VpnStateStore.getRuntimeStateSnapshot().activeLabel
            if (label.isNotBlank() && !label.endsWith("#AUTO", ignoreCase = true) &&
                resolveNodeNameFromOutboundTag(label) != null
            ) {
                break
            }
            delay(RUNTIME_RELOAD_POLL_INTERVAL_MS)
        }
        true
    } == true
}

internal suspend fun ConfigRepository.restorePreviousRuntimeConfig(
    previousRunningConfig: String?,
    previousCoreMode: VpnStateStore.CoreMode
) {
    if (previousRunningConfig == null) return
    val runningConfigFile = File(context.filesDir, "running_config.json")
    if (!restoreRunningConfigSnapshot(previousRunningConfig)) return
    if (!VpnStateStore.isManuallyStopped() && previousCoreMode != VpnStateStore.CoreMode.NONE) {
        VpnStateStore.setMode(previousCoreMode)
        val previousGeneration = VpnStateStore.getRuntimeStateSnapshot().generation
        val restoreResult = if (previousCoreMode == VpnStateStore.CoreMode.ROOT) {
            generateConfigFile()
        } else {
            ConfigRepository.ConfigGenerationResult(
                path = runningConfigFile.absolutePath,
                activeNodeTag = null,
                outboundTags = resolveRunningOutboundTags(previousRunningConfig).orEmpty(),
                configDigest = ConfigRepository.sha256(previousRunningConfig)
            )
        }
        if (restoreResult == null) {
            Log.e(ConfigRepository.TAG, "Failed to regenerate previous Root runtime config")
            return
        }
        requestRuntimeConfigReload(restoreResult, previousCoreMode)
        if (!awaitRuntimeRunningAfter(previousGeneration)) {
            Log.e(ConfigRepository.TAG, "Failed to restore previous runtime config")
        }
    }
}

internal fun ConfigRepository.restoreRunningConfigSnapshot(configContent: String): Boolean {
    return runCatching {
        ConfigRepository.writeTextFileAtomically(
            File(context.filesDir, "running_config.json"),
            configContent
        )
        check(NodeProtectionStore.replaceRuntimeMappings(emptyMap(), configContent)) {
            "Failed to restore runtime mapping fingerprint"
        }
        true
    }.onFailure { error ->
        Log.e(ConfigRepository.TAG, "Failed to restore previous running config snapshot", error)
    }.getOrDefault(false)
}

@Suppress("LongParameterList")
internal fun ConfigRepository.restoreAutoSelectionState(
    profileId: String,
    previousAutoSelection: Boolean,
    previousProfileId: String?,
    previousNodeId: String?,
    previousRunningConfig: String?,
    previousMeteredAuthorization: String?
) {
    saveProfileAutoSelection(profileId, previousAutoSelection)
    NodeProtectionStore.authorizeManualNode(previousMeteredAuthorization)
    if (previousProfileId != null) {
        setActiveProfile(previousProfileId, previousNodeId)
    }
    if (previousRunningConfig != null) {
        restoreRunningConfigSnapshot(previousRunningConfig)
    }
}

internal suspend fun ConfigRepository.setActiveNodeWithResult(nodeId: String): ConfigRepository.NodeSwitchResult {
    return nodeSwitchGate.run {
        val allNodesSnapshot = _allNodes.value.takeIf { it.isNotEmpty() } ?: loadAllNodesSnapshot()
        val previousProfileId = _activeProfileId.value
        val previousNodeId = _activeNodeId.value
        val previousMeteredAuthorization = NodeProtectionStore.manuallyAuthorizedNodeId()
        val previousTagToNodeName = lastTagToNodeName

        // Check for cross-profile switch
        val targetNode = allNodesSnapshot.find { it.id == nodeId }
            ?: return@run ConfigRepository.NodeSwitchResult.Failed("Target node not found: $nodeId")
        val remoteRunning = SingBoxRemote.isRunning.value ||
            SingBoxRemote.isStarting.value ||
            VpnStateStore.getActive()
        if (remoteRunning && targetNode.meteredProtected) {
            return@run ConfigRepository.NodeSwitchResult.Failed(
                context.getString(R.string.node_metered_hot_reload_unsupported)
            )
        }
        val targetProfileId = targetNode.sourceProfileId
        val previousTargetNodeId = getProfileLastSelectedNode(targetProfileId)
        val previousAutoSelection = isProfileAutoSelectionEnabled(targetProfileId)
        val previousCoreMode = VpnStateStore.getMode()
        val runningConfigFile = File(context.filesDir, "running_config.json")
        val previousRunningConfig = withContext(Dispatchers.IO) {
            runningConfigFile.takeIf { it.exists() }?.readText()
        }
        if (targetNode.sourceProfileId != _activeProfileId.value) {
            Log.i(ConfigRepository.TAG, "Cross-profile switch detected: ${_activeProfileId.value} -> ${targetNode.sourceProfileId}")

            // 2025-fix: Ensure profile is loaded synchronously before switching
            // This prevents race condition where _nodes is empty during generateConfigFile
            val profileId = targetNode.sourceProfileId
            withContext(Dispatchers.IO) {
                if (profileNodes[profileId] == null) {
                    Log.i(ConfigRepository.TAG, "Pre-loading profile nodes for $profileId")
                    loadConfig(profileId)?.let { cfg ->
                        val nodes = extractNodesFromConfig(cfg, profileId)
                        val nodesWithLatency = nodes.map { node ->
                            val latency = savedLatencyMs(node.id)
                            if (latency != null) node.copy(latencyMs = latency) else node
                        }
                        profileNodes[profileId] = nodesWithLatency
                    }
                }
            }
        }

        val manualSelectionToken = runCatching {
            NodeProtectionStore.beginManualSelection(nodeId)
        }.getOrElse { error ->
            return@run ConfigRepository.NodeSwitchResult.Failed(
                error.message ?: "Failed to stage manual selection"
            )
        }
        if (!remoteRunning) {
            return@run runCatching {
                commitManualSelectionState(
                    targetNode = targetNode,
                    allNodesSnapshot = allNodesSnapshot,
                    manualSelectionToken = manualSelectionToken
                )
                Log.i(ConfigRepository.TAG, "setActiveNodeWithResult: VPN not running, selection committed")
                ConfigRepository.NodeSwitchResult.NotRunning
            }.getOrElse { error ->
                NodeProtectionStore.cancelManualSelection(manualSelectionToken)
                restoreManualSelectionState(
                    targetProfileId,
                    previousTargetNodeId,
                    previousAutoSelection,
                    previousProfileId to previousNodeId,
                    previousRunningConfig,
                    previousMeteredAuthorization
                )
                ConfigRepository.NodeSwitchResult.Failed(
                    error.message ?: "Failed to persist manual selection"
                )
            }
        }

        if (profileNodes[targetProfileId].orEmpty().none { it.id == nodeId } &&
            allNodesSnapshot.none { it.id == nodeId }
        ) {
            NodeProtectionStore.cancelManualSelection(manualSelectionToken)
            restoreManualSelectionState(
                targetProfileId,
                previousTargetNodeId,
                previousAutoSelection,
                previousProfileId to previousNodeId,
                previousRunningConfig,
                previousMeteredAuthorization
            )
            return@run ConfigRepository.NodeSwitchResult.Failed("Target node not found: $nodeId")
        }

        withContext(Dispatchers.IO) {
            var node = _nodes.value.find { it.id == nodeId }
            if (node == null) {
                node = allNodesSnapshot.find { it.id == nodeId }
            }

            if (node == null) {
                val msg = "Target node not found: $nodeId"
                Log.w(ConfigRepository.TAG, msg)
                NodeProtectionStore.cancelManualSelection(manualSelectionToken)
                return@withContext ConfigRepository.NodeSwitchResult.Failed(msg)
            }

            try {
                val generationResult = generateConfigFile(
                    selectedProfileId = targetProfileId,
                    selectedNodeId = nodeId,
                    forceManualSelection = true
                )
                if (generationResult == null) {
                    val msg = lastConfigGenerationError
                        ?: context.getString(R.string.dashboard_config_generation_failed)
                    Log.e(ConfigRepository.TAG, msg)
                    lastTagToNodeName = previousTagToNodeName
                    NodeProtectionStore.cancelManualSelection(manualSelectionToken)
                    restoreManualSelectionState(
                        targetProfileId,
                        previousTargetNodeId,
                        previousAutoSelection,
                        previousProfileId to previousNodeId,
                        previousRunningConfig,
                        previousMeteredAuthorization
                    )
                    return@withContext ConfigRepository.NodeSwitchResult.Failed(msg)
                }

                runCatching {
                    val oldCacheDb = File(File(context.filesDir, "singbox_data"), "cache.db")
                    if (oldCacheDb.exists()) oldCacheDb.delete()
                }
                val currentTags = generationResult.outboundTags
                val currentProfileId = targetProfileId
                val baselineTags = lastRunOutboundTags
                    ?: resolveRunningOutboundTags(previousRunningConfig)
                val baselineProfileId = lastRunProfileId
                    ?: VpnStateStore.getSelectedProfileId().takeIf(String::isNotBlank)
                    ?: previousProfileId
                val profileChanged = baselineProfileId == null || baselineProfileId != currentProfileId
                val tagsActuallyChanged = baselineTags == null || baselineTags != currentTags
                val isVpnStartingNotReady = SingBoxRemote.isStarting.value && !SingBoxRemote.isRunning.value
                val tagsChanged = shouldReloadRuntimeForManualSelection(
                    currentProfileId = currentProfileId,
                    currentTags = currentTags,
                    baselineProfileId = baselineProfileId,
                    baselineTags = baselineTags,
                    isVpnStartingNotReady = isVpnStartingNotReady
                )

                Log.d(
                    ConfigRepository.TAG,
                    "Switch decision: profileChanged=$profileChanged " +
                        "(baseline=$baselineProfileId, cur=$currentProfileId), " +
                        "tagsActuallyChanged=$tagsActuallyChanged, " +
                        "isVpnStartingNotReady=$isVpnStartingNotReady, " +
                        "baselineTags=${baselineTags?.size ?: "missing"}, tagsChanged=$tagsChanged"
                )
                val coreMode = VpnStateStore.getMode()

                if (tagsChanged && remoteRunning) {
                    Log.i(ConfigRepository.TAG, "Sending PREPARE_RESTART before VPN restart")
                    if (!VpnStateStore.shouldTriggerPrepareRestart(1500L)) {
                        Log.d(ConfigRepository.TAG, "PREPARE_RESTART suppressed (sender throttle)")
                    } else {
                        val prepareIntent = if (coreMode == VpnStateStore.CoreMode.PROXY) {
                            Intent(context, ProxyOnlyService::class.java).apply {
                                action = ProxyOnlyService.ACTION_PREPARE_RESTART
                                putExtra(
                                    com.kunk.singbox.service.SingBoxService.EXTRA_PREPARE_RESTART_REASON,
                                    "ConfigRepository:switchNode"
                                )
                            }
                        } else if (coreMode == VpnStateStore.CoreMode.ROOT) {
                            null
                        } else {
                            Intent(context, SingBoxService::class.java).apply {
                                action = SingBoxService.ACTION_PREPARE_RESTART
                                putExtra(
                                    com.kunk.singbox.service.SingBoxService.EXTRA_PREPARE_RESTART_REASON,
                                    "ConfigRepository:switchNode"
                                )
                            }
                        }
                        prepareIntent?.let(context::startService)
                    }
                    delay(200)
                }

                val intent = if (coreMode == VpnStateStore.CoreMode.PROXY) {
                    Intent(context, ProxyOnlyService::class.java).apply {
                        if (tagsChanged) {
                            action = ProxyOnlyService.ACTION_START
                            Log.i(ConfigRepository.TAG, "Outbound tags changed (or first run), forcing RESTART/RELOAD")
                        } else {
                            action = ProxyOnlyService.ACTION_SWITCH_NODE
                            Log.i(ConfigRepository.TAG, "Outbound tags match, attempting HOT SWITCH")
                        }
                        putExtra("node_id", nodeId)
                        putExtra("outbound_tag", generationResult.activeNodeTag)
                        putExtra(SingBoxService.EXTRA_PENDING_NODE_NAME, node.name)
                        putExtra(ProxyOnlyService.EXTRA_CONFIG_PATH, generationResult.path)
                    }
                } else if (coreMode == VpnStateStore.CoreMode.ROOT) {
                    Intent(context, RootTransparentForegroundService::class.java).apply {
                        action = if (tagsChanged) {
                            RootTransparentForegroundService.ACTION_RESTART
                        } else {
                            RootTransparentForegroundService.ACTION_SWITCH_NODE
                        }
                        putExtra(RootTransparentForegroundService.EXTRA_CONFIG_PATH, generationResult.path)
                        putExtra(
                            RootTransparentForegroundService.EXTRA_OUTBOUND_TAG,
                            generationResult.activeNodeTag
                        )
                        putExtra(RootTransparentForegroundService.EXTRA_NODE_NAME, node.name)
                    }
                } else {
                    Intent(context, SingBoxService::class.java).apply {
                        if (tagsChanged) {
                            action = SingBoxService.ACTION_START
                            putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
                            Log.i(
                                ConfigRepository.TAG,
                                "Outbound tags changed (or first run), " +
                                    "forcing RESTART/RELOAD with CACHE CLEAN"
                            )
                        } else {
                            action = SingBoxService.ACTION_SWITCH_NODE
                            Log.i(ConfigRepository.TAG, "Outbound tags match, attempting HOT SWITCH")
                        }
                        putExtra("node_id", nodeId)
                        putExtra("outbound_tag", generationResult.activeNodeTag)
                        putExtra(SingBoxService.EXTRA_PENDING_NODE_NAME, node.name)
                        putExtra(SingBoxService.EXTRA_CONFIG_PATH, generationResult.path)
                    }
                }

                val previousRuntimeGeneration = VpnStateStore.getRuntimeStateSnapshot().generation
                // Service already running (VPN active). Use startService to avoid foreground-service timing constraints.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && tagsChanged) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                check(
                    awaitRuntimeSelectionAfter(
                        previousGeneration = previousRuntimeGeneration,
                        expectedNodeName = node.name,
                        expectedOutboundTag = generationResult.activeNodeTag,
                        timeoutMs = if (tagsChanged) {
                            RUNTIME_RELOAD_TIMEOUT_MS
                        } else {
                            MANUAL_HOT_SWITCH_CONFIRMATION_TIMEOUT_MS
                        }
                    )
                ) {
                    val actual = VpnStateStore.getRuntimeStateSnapshot().activeLabel
                    "Kernel selection confirmation failed: expected=${node.name}, actual=$actual"
                }

                commitManualSelectionState(
                    targetNode = targetNode,
                    allNodesSnapshot = allNodesSnapshot,
                    manualSelectionToken = manualSelectionToken
                )
                lastRunOutboundTags = currentTags
                lastRunProfileId = currentProfileId

                Log.i(
                    ConfigRepository.TAG,
                    "Confirmed switch for node: ${node.name} " +
                        "(Tag: ${generationResult.activeNodeTag}, Restart: $tagsChanged)"
                )
                ConfigRepository.NodeSwitchResult.Success
            } catch (e: Exception) {

                val msg = "Switch error: ${e.message ?: "unknown error"}"
                Log.e(ConfigRepository.TAG, "Error during hot switch", e)
                lastTagToNodeName = previousTagToNodeName
                NodeProtectionStore.cancelManualSelection(manualSelectionToken)
                restoreManualSelectionState(
                    targetProfileId,
                    previousTargetNodeId,
                    previousAutoSelection,
                    previousProfileId to previousNodeId,
                    previousRunningConfig,
                    previousMeteredAuthorization
                )
                restorePreviousRuntimeConfig(previousRunningConfig, previousCoreMode)
                ConfigRepository.NodeSwitchResult.Failed(msg)
            }
        }
    }
}

internal fun ConfigRepository.commitManualSelectionState(
    targetNode: NodeUi,
    allNodesSnapshot: List<NodeUi>,
    manualSelectionToken: String
) {
    val targetProfileId = targetNode.sourceProfileId
    check(saveProfileAutoSelection(targetProfileId, false)) {
        "Failed to persist manual selection mode"
    }
    NodeProtectionStore.commitManualSelection(
        token = manualSelectionToken,
        nodeId = targetNode.id,
        protected = isNodeMeteredProtected(targetNode.id)
    )

    val previousProfileId = _activeProfileId.value
    val previousNodeId = _activeNodeId.value
    if (previousProfileId != null && previousNodeId != null && previousProfileId != targetProfileId) {
        saveProfileNodeMemory(previousProfileId, previousNodeId)
    }

    val targetNodes = profileNodes[targetProfileId]
        ?: allNodesSnapshot.filter { it.sourceProfileId == targetProfileId }
    check(targetNodes.any { it.id == targetNode.id }) {
        "Target node disappeared before selection commit: ${targetNode.id}"
    }
    profileNodes[targetProfileId] = targetNodes
    _activeProfileId.value = targetProfileId
    applyActiveProfileNodes(targetProfileId, targetNodes, targetNode.id)
    saveProfileNodeMemory(targetProfileId, targetNode.id)
    VpnStateStore.setSelectedNodeLabel(targetNode.name)
    saveProfilesImmediate()
}

@Suppress("LongParameterList")
internal fun ConfigRepository.restoreManualSelectionState(
    targetProfileId: String,
    previousTargetNodeId: String?,
    previousAutoSelection: Boolean,
    previousSelection: Pair<String?, String?>,
    previousRunningConfig: String?,
    previousMeteredAuthorization: String?
) {
    saveProfileAutoSelection(targetProfileId, previousAutoSelection)
    NodeProtectionStore.authorizeManualNode(previousMeteredAuthorization)
    val (previousProfileId, previousNodeId) = previousSelection
    if (previousProfileId != null) {
        setActiveProfile(previousProfileId, previousNodeId)
    }
    if (previousTargetNodeId == null) {
        profileLastSelectedNode.remove(targetProfileId)
        profileNodeMemoryMmkv.removeValueForKey(targetProfileId)
    } else {
        saveProfileNodeMemory(targetProfileId, previousTargetNodeId)
    }
    if (previousRunningConfig != null) {
        restoreRunningConfigSnapshot(previousRunningConfig)
    }
}

internal suspend fun ConfigRepository.syncActiveNodeFromProxySelection(proxyName: String?): Boolean {
    if (proxyName.isNullOrBlank()) return false

    val activeProfileId = _activeProfileId.value ?: return false
    val candidates = _nodes.value
    val matched = candidates.firstOrNull { it.name == proxyName } ?: return false
    if (matched.sourceProfileId != activeProfileId) return false
    VpnStateStore.setSelectedNode(activeProfileId, matched.id)
    VpnStateStore.setSelectedNodeLabel(matched.name)
    if (_activeNodeId.value == matched.id) {
        saveProfileNodeMemory(activeProfileId, matched.id)
        return true
    }

    _activeNodeId.value = matched.id
    saveProfileNodeMemory(activeProfileId, matched.id)
    saveProfilesImmediate()
    Log.i(ConfigRepository.TAG, "Synced active node from service selection: $proxyName -> ${matched.id}")
    return true
}
