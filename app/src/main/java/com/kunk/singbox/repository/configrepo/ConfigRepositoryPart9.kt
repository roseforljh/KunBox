@file:Suppress("UnusedImports", "TooManyFunctions", "LongMethod", "LargeClass", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeCons")

package com.kunk.singbox.repository

import android.content.Intent
import android.util.Log
import com.kunk.singbox.R
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.*
import com.kunk.singbox.repository.config.NodeLinkExporter
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.root.RootTransparentForegroundService
import com.kunk.singbox.service.manager.VpnStopInitiator
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal suspend fun ConfigRepository.getActiveConfig(): SingBoxConfig? = withContext(Dispatchers.IO) {
    val id = _activeProfileId.value ?: return@withContext null
    loadConfig(id)
}

internal fun ConfigRepository.getConfig(profileId: String): SingBoxConfig? {
    return loadConfig(profileId)
}

internal suspend fun ConfigRepository.readProfileConfigContent(profileId: String): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        require(_profiles.value.any { it.id == profileId }) { "Profile not found" }

        val configFile = File(configDir, "$profileId.json")
        if (configFile.exists()) {
            return@runCatching configFile.readText(Charsets.UTF_8)
        }

        val config = loadConfig(profileId) ?: throw IllegalStateException("Config not found")
        gson.toJson(config)
    }
}

internal suspend fun ConfigRepository.updateProfileConfigContent(profileId: String, content: String): Result<ProfileUi> =
    withContext(Dispatchers.IO) {
        runCatching {
            require(content.isNotBlank()) { context.getString(R.string.profiles_content_empty) }

            val existingProfile = _profiles.value.find { it.id == profileId }
                ?: throw IllegalArgumentException("Profile not found")
            val parsedConfig = gson.fromJson(content, SingBoxConfig::class.java)
                ?: throw IllegalArgumentException(context.getString(R.string.profiles_parse_failed))
            val deduplicatedConfig = deduplicateTags(parsedConfig)
            val nodes = extractNodesFromConfig(deduplicatedConfig, profileId, {})
            require(nodes.isNotEmpty()) { context.getString(R.string.profiles_parse_failed) }

            writeConfigFileOrThrow(profileId, deduplicatedConfig)
            cacheConfig(profileId, deduplicatedConfig)
            profileNodes[profileId] = nodes
            updateAllNodesAndGroups()
            if (_activeProfileId.value == profileId) {
                applyActiveProfileNodes(profileId, nodes)
            }

            val updatedProfile = existingProfile.copy(lastUpdated = System.currentTimeMillis())
            _profiles.update { list ->
                list.map { profile ->
                    if (profile.id == profileId) updatedProfile else profile
                }
            }
            saveProfiles()

            updatedProfile
        }
    }

internal fun ConfigRepository.resolveDnsStrategy(strategy: DnsStrategy, mode: IpVersionMode): String {
    return mode.resolveDnsStrategy(strategy)
}

/**
 * 直连 DNS 策略：双栈 + AUTO 时强制 ipv4_only。
 * prefer_ipv4 仍会返回 AAAA，无公网 IPv6 时 geosite-cn 直连会 network unreachable。
 * 已保存为 AUTO 的旧配置也会走此路径。
 */
internal fun ConfigRepository.resolveDirectDnsStrategy(strategy: DnsStrategy, mode: IpVersionMode): String {
    return ConfigRepository.resolveDirectDnsStrategy(strategy, mode)
}

internal fun ConfigRepository.logOutboundServerAddressStrategy(
    scope: String,
    strategy: DnsStrategy,
    ipVersionMode: IpVersionMode,
    resolvedStrategy: String
) {
    val message = ConfigRepository.buildOutboundServerAddressStrategyLog(
        scope = scope,
        strategy = strategy,
        ipVersionMode = ipVersionMode,
        resolvedStrategy = resolvedStrategy
    )
    Log.i(ConfigRepository.TAG, message)
    LogRepository.getInstance().addLog(message)
}

internal suspend fun ConfigRepository.getOutboundByNodeId(nodeId: String): Outbound? = withContext(Dispatchers.IO) {
    val node = _nodes.value.find { it.id == nodeId } ?: return@withContext null
    val config = loadConfig(node.sourceProfileId) ?: return@withContext null
    config.outbounds?.find { it.tag == node.name }
}

internal fun ConfigRepository.getNodeById(nodeId: String): NodeUi? {
    _nodes.value.find { it.id == nodeId }?.let { return it }
    for ((_, nodes) in profileNodes) {
        nodes.find { it.id == nodeId }?.let { return it }
    }
    _allNodes.value.find { it.id == nodeId }?.let { return it }

    return null
}

@Suppress("ReturnCount")
internal fun ConfigRepository.getNodeByName(nodeName: String): NodeUi? {
    _nodes.value.find { it.name == nodeName }?.let { return it }
    for ((_, nodes) in profileNodes) {
        nodes.find { it.name == nodeName }?.let { return it }
    }
    _allNodes.value.find { it.name == nodeName }?.let { return it }

    return null
}

internal fun ConfigRepository.createNode(
    outbound: Outbound,
    targetProfileId: String? = null,
    newProfileName: String? = null) {
    var createdProfileId: String? = null
    try {
        val profileId: String
        val existingConfig: SingBoxConfig?
        var targetProfile: ProfileUi? = null
        val finalProfileName: String

        when {
            targetProfileId != null -> {
                targetProfile = _profiles.value.find { it.id == targetProfileId }
                if (targetProfile != null) {
                    profileId = targetProfileId
                    existingConfig = loadConfig(profileId)
                    finalProfileName = targetProfile.name
                } else {
                    profileId = UUID.randomUUID().toString()
                    existingConfig = null
                    finalProfileName = "Manual"
                    createdProfileId = profileId
                }
            }
            newProfileName != null -> {
                profileId = UUID.randomUUID().toString()
                existingConfig = null
                finalProfileName = newProfileName
                createdProfileId = profileId
            }
            else -> {
                val manualProfileName = "Manual"
                targetProfile = _profiles.value.find { it.name == manualProfileName && it.type == ProfileType.Imported }
                if (targetProfile != null) {
                    profileId = targetProfile.id
                    existingConfig = loadConfig(profileId)
                } else {
                    profileId = UUID.randomUUID().toString()
                    existingConfig = null
                    createdProfileId = profileId
                }
                finalProfileName = manualProfileName
            }
        }
        val newOutbounds = mutableListOf<Outbound>()
        existingConfig?.outbounds?.let { existing ->
            newOutbounds.addAll(existing.filter { it.type !in listOf("direct", "block", "dns") })
        }
        var finalTag = outbound.tag
        var counter = 1
        while (newOutbounds.any { it.tag == finalTag }) {
            finalTag = "${outbound.tag}_$counter"
            counter++
        }
        val finalOutbound = if (finalTag != outbound.tag) outbound.copy(tag = finalTag) else outbound
        newOutbounds.add(finalOutbound)
        if (newOutbounds.none { it.tag == "direct" }) {
            newOutbounds.add(Outbound(type = "direct", tag = "direct"))
        }
        val newConfig = deduplicateTags(
            ConfigRepository.buildConfigWithOutboundsPreservingProfileSettings(existingConfig, newOutbounds)
        )

        writeConfigFileOrThrow(profileId, newConfig)

        cacheConfig(profileId, newConfig)

        if (targetProfile == null) {
            targetProfile = ProfileUi(
                id = profileId,
                name = finalProfileName,
                type = ProfileType.Imported,
                url = null,
                lastUpdated = System.currentTimeMillis(),
                enabled = true,
                updateStatus = UpdateStatus.Idle
            )
            _profiles.update { it + targetProfile }
        } else {
            _profiles.update { list ->
                list.map { if (it.id == profileId) it.copy(lastUpdated = System.currentTimeMillis()) else it }
            }
        }

        scope.launch {
            val nodes = extractNodesFromConfig(newConfig, profileId)
            profileNodes[profileId] = nodes
            val addedNode = nodes.find { it.name == finalTag }
            if (ConfigRepository.shouldActivateCreatedNode(_activeProfileId.value)) {
                _activeProfileId.value = profileId
                applyActiveProfileNodes(profileId, nodes, addedNode?.id)
            } else if (_activeProfileId.value == profileId) {
                applyActiveProfileNodes(profileId, nodes)
            }
            updateAllNodesAndGroups()

            saveProfiles()
            Log.i(ConfigRepository.TAG, "Created node: $finalTag in profile $profileId")
        }
    } catch (e: Exception) {
        createdProfileId?.let { rollbackTransientProfileFile(it) }
        Log.e(ConfigRepository.TAG, "Failed to create node", e)
    }
}

internal fun ConfigRepository.removeOutboundFromConfig(config: SingBoxConfig, removedTag: String): SingBoxConfig {
    val outbounds = config.outbounds ?: return config
    val filteredOutbounds = outbounds
        .filter { it.tag != removedTag }
        .map { outbound ->
            when {
                outbound.outbounds?.contains(removedTag) == true -> {
                    val filteredRefs = outbound.outbounds.filter { it != removedTag }
                    outbound.copy(
                        outbounds = if (filteredRefs.isEmpty()) listOf("direct") else filteredRefs,
                        default = outbound.default?.takeIf { it != removedTag }
                    )
                }
                outbound.detour == removedTag -> {
                    outbound.copy(detour = null)
                }
                else -> outbound
            }
        }
    return config.copy(outbounds = filteredOutbounds)
}

internal suspend fun ConfigRepository.deleteNode(nodeId: String) = withContext(Dispatchers.IO) {
    val node = getNodeById(nodeId) ?: return@withContext
    val profileId = node.sourceProfileId
    val config = loadConfig(profileId) ?: return@withContext
    val newConfig = removeOutboundFromConfig(config, node.name)
    cacheConfig(profileId, newConfig)
    writeConfigFileOrThrow(profileId, newConfig)
    removeNodeLatencies(listOf(nodeId))
    nodeAutoSelectionMmkv.removeValueForKey(nodeId)
    NodeProtectionStore.removeNode(nodeId)

    val immediateNodes = (profileNodes[profileId] ?: _nodes.value)
        .filter { it.id != nodeId && it.name != node.name }
    applyDeletedNodeSnapshot(profileId, nodeId, immediateNodes)

    scope.launch {
        val newNodes = extractNodesFromConfig(newConfig, profileId)
        applyDeletedNodeSnapshot(profileId, nodeId, newNodes)
        saveProfiles()
    }
}

internal suspend fun ConfigRepository.removeNodeLatencies(nodeIds: Collection<String>) {
    val distinctNodeIds = nodeIds.distinct()
    if (distinctNodeIds.isEmpty()) return

    distinctNodeIds.forEach(savedNodeLatencies::remove)
    try {
        nodeLatencyDao.deleteByNodeIds(distinctNodeIds)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(ConfigRepository.TAG, "Failed to delete persisted node latencies", e)
    }
}

internal fun ConfigRepository.applyDeletedNodeSnapshot(profileId: String, deletedNodeId: String, nodes: List<NodeUi>) {
    profileNodes[profileId] = nodes
    updateAllNodesAndGroups()
    if (_activeProfileId.value != profileId) return

    _nodes.value = nodes
    if (_activeNodeId.value == deletedNodeId) {
        _activeNodeId.value = nodes.firstOrNull()?.id
    }
}

internal suspend fun ConfigRepository.addSingleNode(
    link: String,
    targetProfileId: String? = null,
    newProfileName: String? = null): Result<NodeUi> = withContext(Dispatchers.IO) {
    var createdProfileId: String? = null
    try {
        val outbound = parseNodeLink(link.trim())
            ?: return@withContext Result.failure(Exception("Failed to parse node link"))

        val profileId: String
        val existingConfig: SingBoxConfig?
        var isNewProfile = false

        when {
            targetProfileId != null -> {
                val profile = _profiles.value.find { it.id == targetProfileId }
                if (profile == null) {
                    return@withContext Result.failure(Exception("Profile not found"))
                }
                profileId = targetProfileId
                existingConfig = loadConfig(profileId)
            }
            newProfileName != null -> {
                profileId = UUID.randomUUID().toString()
                existingConfig = null
                isNewProfile = true
                createdProfileId = profileId
            }
            else -> {
                val manualProfileName = "Manual"
                val manualProfile = _profiles.value.find { it.name == manualProfileName && it.type == ProfileType.Imported }
                if (manualProfile != null) {
                    profileId = manualProfile.id
                    existingConfig = loadConfig(profileId)
                } else {
                    profileId = UUID.randomUUID().toString()
                    existingConfig = null
                    isNewProfile = true
                    createdProfileId = profileId
                }
            }
        }

        val newOutbounds = mutableListOf<Outbound>()
        existingConfig?.outbounds?.let { existing ->
            newOutbounds.addAll(existing.filter { it.type !in listOf("direct", "block", "dns") })
        }

        var finalTag = outbound.tag
        var counter = 1
        while (newOutbounds.any { it.tag == finalTag }) {
            finalTag = "${outbound.tag}_$counter"
            counter++
        }
        val finalOutbound = if (finalTag != outbound.tag) outbound.copy(tag = finalTag) else outbound
        newOutbounds.add(finalOutbound)

        if (newOutbounds.none { it.tag == "direct" }) {
            newOutbounds.add(Outbound(type = "direct", tag = "direct"))
        }
        val newConfig = deduplicateTags(
            ConfigRepository.buildConfigWithOutboundsPreservingProfileSettings(existingConfig, newOutbounds)
        )

        writeConfigFileOrThrow(profileId, newConfig)

        cacheConfig(profileId, newConfig)
        val nodes = extractNodesFromConfig(newConfig, profileId)
        profileNodes[profileId] = nodes

        if (isNewProfile || existingConfig == null) {
            val profileName = newProfileName ?: "Manual"
            val newProfile = ProfileUi(
                id = profileId,
                name = profileName,
                type = ProfileType.Imported,
                url = null,
                lastUpdated = System.currentTimeMillis(),
                enabled = true,
                updateStatus = UpdateStatus.Idle
            )
            _profiles.update { it + newProfile }
        } else {
            _profiles.update { list ->
                list.map { if (it.id == profileId) it.copy(lastUpdated = System.currentTimeMillis()) else it }
            }
        }

        val addedNode = nodes.find { it.name == finalTag }
        if (ConfigRepository.shouldActivateCreatedNode(_activeProfileId.value)) {
            _activeProfileId.value = profileId
            applyActiveProfileNodes(profileId, nodes, addedNode?.id)
        } else if (_activeProfileId.value == profileId) {
            applyActiveProfileNodes(profileId, nodes)
        }
        updateAllNodesAndGroups()

        saveProfiles()

        Log.i(ConfigRepository.TAG, "Added single node: $finalTag to profile $profileId")

        Result.success(addedNode ?: nodes.last())
    } catch (e: Exception) {
        createdProfileId?.let { rollbackTransientProfileFile(it) }
        Log.e(ConfigRepository.TAG, "Failed to add single node", e)
        Result.failure(Exception(context.getString(R.string.nodes_add_failed) + ": ${e.message}"))
    }
}

internal suspend fun ConfigRepository.updateNode(
    nodeId: String,
    newOutbound: Outbound,
    autoSelectionEligible: Boolean = isNodeAutoSelectionEligible(nodeId),
    meteredProtected: Boolean = isNodeMeteredProtected(nodeId)
) = withContext(Dispatchers.IO) {
    val node = _nodes.value.find { it.id == nodeId } ?: return@withContext
    val profileId = node.sourceProfileId
    val config = loadConfig(profileId) ?: return@withContext
    val effectiveAutoSelectionEligible = autoSelectionEligible && !meteredProtected
    val (newConfig, previousEligibility, previousProtection) = persistUpdatedNodeConfig(
        node = node,
        config = config,
        newOutbound = newOutbound,
        autoSelectionEligible = effectiveAutoSelectionEligible,
        meteredProtected = meteredProtected
    )
    val refreshedNodes = refreshNodesAfterNodeMutation(
        profileId = profileId,
        oldNodeId = nodeId,
        newTag = newOutbound.tag,
        newConfig = newConfig
    )
    applyNodeAutoSelectionEligibilityChange(
        profileId = profileId,
        previousEligibility = previousEligibility,
        autoSelectionEligible = effectiveAutoSelectionEligible,
        previousProtection = previousProtection,
        meteredProtected = meteredProtected,
        refreshedNodes = refreshedNodes
    )
}

internal fun ConfigRepository.persistUpdatedNodeConfig(
    node: NodeUi,
    config: SingBoxConfig,
    newOutbound: Outbound,
    autoSelectionEligible: Boolean,
    meteredProtected: Boolean
): Triple<SingBoxConfig, Boolean, Boolean> {
    val profileId = node.sourceProfileId
    val previousEligibility = isNodeAutoSelectionEligible(node.id)
    val previousProtection = isNodeMeteredProtected(node.id)
    val previousAuthorization = NodeProtectionStore.manuallyAuthorizedNodeId()
    val updatedNodeId = ConfigRepository.stableNodeId(profileId, newOutbound.tag)
    check(saveNodeAutoSelectionEligibility(updatedNodeId, autoSelectionEligible)) {
        "Failed to persist automatic selection eligibility for ${node.name}"
    }
    check(NodeProtectionStore.setProtected(updatedNodeId, meteredProtected)) {
        "Failed to persist metered protection for ${node.name}"
    }
    NodeProtectionStore.authorizeManualNode(
        authorizationAfterProtectionUpdate(
            previousAuthorizedNodeId = previousAuthorization,
            oldNodeId = node.id,
            updatedNodeId = updatedNodeId,
            wasProtected = previousProtection,
            isProtected = meteredProtected
        )
    )
    val newOutbounds = config.outbounds?.map {
        if (it.tag == node.name) newOutbound else it
    }
    val newConfig = deduplicateTags(config.copy(outbounds = newOutbounds))
    try {
        cacheConfig(profileId, newConfig)
        writeConfigFileOrThrow(profileId, newConfig)
    } catch (e: Exception) {
        if (updatedNodeId == node.id) {
            saveNodeAutoSelectionEligibility(node.id, previousEligibility)
        } else {
            nodeAutoSelectionMmkv.removeValueForKey(updatedNodeId)
        }
        NodeProtectionStore.setProtected(node.id, previousProtection)
        if (updatedNodeId != node.id) NodeProtectionStore.removeNode(updatedNodeId)
        NodeProtectionStore.authorizeManualNode(previousAuthorization)
        throw e
    }
    if (updatedNodeId != node.id) {
        nodeAutoSelectionMmkv.removeValueForKey(node.id)
        NodeProtectionStore.removeNode(node.id)
    }
    return Triple(newConfig, previousEligibility, previousProtection)
}

@Suppress("LongParameterList", "CognitiveComplexMethod")
internal suspend fun ConfigRepository.applyNodeAutoSelectionEligibilityChange(
    profileId: String,
    previousEligibility: Boolean,
    autoSelectionEligible: Boolean,
    previousProtection: Boolean,
    meteredProtected: Boolean,
    refreshedNodes: List<NodeUi>
) {
    val protectionEnabled = !previousProtection && meteredProtected
    disableProfileAutoSelectionWithoutCandidates(profileId, autoSelectionEligible, refreshedNodes)
    if (protectionEnabled) {
        // 先清空旧配置建立的连接，再生成不含计费节点的新运行配置。
        resetRuntimeConnectionsForMeteredProtection()
        if (leaveNewlyProtectedActiveNode(refreshedNodes)) return
    }
    val settingsChanged = previousEligibility != autoSelectionEligible || previousProtection != meteredProtected
    if (!settingsChanged || !shouldReloadNodeSettingsChange()) return

    val generationResult = generateConfigFile()
    if (generationResult == null) {
        if (protectionEnabled) stopRuntimeForMeteredProtection()
        error(lastConfigGenerationError ?: "Failed to generate config after changing node policy")
    }
    try {
        requestFullRuntimeConfigReload(generationResult)
        lastRunOutboundTags = generationResult.outboundTags
        lastRunProfileId = _activeProfileId.value
    } catch (error: Exception) {
        if (protectionEnabled) stopRuntimeForMeteredProtection()
        throw error
    }
}

internal fun ConfigRepository.disableProfileAutoSelectionWithoutCandidates(
    profileId: String,
    autoSelectionEligible: Boolean,
    refreshedNodes: List<NodeUi>
) {
    if (autoSelectionEligible || refreshedNodes.any { it.autoSelectionEligible }) return
    if (!saveProfileAutoSelection(profileId, false)) {
        Log.e(ConfigRepository.TAG, "Failed to disable automatic selection for empty candidate set")
    }
}

internal suspend fun ConfigRepository.leaveNewlyProtectedActiveNode(refreshedNodes: List<NodeUi>): Boolean {
    val activeNode = refreshedNodes.firstOrNull { it.id == _activeNodeId.value }
    if (activeNode?.meteredProtected != true) return false

    val fallbackNode = refreshedNodes.firstOrNull { !it.meteredProtected }
    if (fallbackNode == null) {
        stopRuntimeForMeteredProtection()
        return true
    }
    val result = setActiveNodeWithResult(fallbackNode.id)
    if (result is ConfigRepository.NodeSwitchResult.Failed) {
        stopRuntimeForMeteredProtection()
        error("Failed to leave newly protected node: ${result.reason}")
    }
    return true
}

internal fun ConfigRepository.resetRuntimeConnectionsForMeteredProtection() {
    val intent = when (VpnStateStore.getMode()) {
        VpnStateStore.CoreMode.PROXY -> Intent(context, ProxyOnlyService::class.java).apply {
            action = ProxyOnlyService.ACTION_RESET_CONNECTIONS
        }
        VpnStateStore.CoreMode.VPN -> Intent(context, SingBoxService::class.java).apply {
            action = SingBoxService.ACTION_RESET_CONNECTIONS
        }
        VpnStateStore.CoreMode.ROOT -> Intent(context, RootTransparentForegroundService::class.java).apply {
            action = RootTransparentForegroundService.ACTION_RESET_CONNECTIONS
        }
        VpnStateStore.CoreMode.NONE -> return
    }
    context.startService(intent)
}

internal fun ConfigRepository.stopRuntimeForMeteredProtection() {
    val intent = when (VpnStateStore.getMode()) {
        VpnStateStore.CoreMode.PROXY -> Intent(context, ProxyOnlyService::class.java).apply {
            action = ProxyOnlyService.ACTION_STOP
            putExtra(SingBoxService.EXTRA_STOP_INITIATOR, VpnStopInitiator.METERED_PROTECTION.wireValue)
        }
        VpnStateStore.CoreMode.VPN -> Intent(context, SingBoxService::class.java).apply {
            action = SingBoxService.ACTION_STOP
            putExtra(SingBoxService.EXTRA_STOP_INITIATOR, VpnStopInitiator.METERED_PROTECTION.wireValue)
        }
        VpnStateStore.CoreMode.ROOT -> Intent(context, RootTransparentForegroundService::class.java).apply {
            action = RootTransparentForegroundService.ACTION_STOP
        }
        VpnStateStore.CoreMode.NONE -> return
    }
    context.startService(intent)
    LogRepository.getInstance().addAlwaysLog(
        "WARN [METERED_GUARD] stopped runtime because protected-node paths could not be purged safely"
    )
}

internal fun ConfigRepository.filterVpnCapturedPackages(settings: AppSettings, packageNames: List<String>): List<String> {
    if (!ConfigRepository.shouldFilterCapturedPackages(settings)) {
        return packageNames.map(String::trim).filter(String::isNotBlank).distinct()
    }
    val policy = PerAppVpnPolicy.from(settings)
    val selectedPackages = when (policy.mode) {
        VpnAppMode.ALL -> emptySet()
        VpnAppMode.ALLOWLIST -> policy.allowlist
        VpnAppMode.BLOCKLIST -> policy.blocklist
    }
    val selectedUids = selectedPackages.mapNotNullTo(mutableSetOf()) { packageName ->
        runCatching { context.packageManager.getApplicationInfo(packageName, 0).uid }.getOrNull()
    }
    return packageNames.asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .filterNot { it == context.packageName }
        .filter { packageName ->
            val uid = runCatching {
                context.packageManager.getApplicationInfo(packageName, 0).uid
            }.getOrNull()
            when (policy.mode) {
                VpnAppMode.ALL -> true
                VpnAppMode.ALLOWLIST -> packageName in policy.allowlist || uid in selectedUids
                VpnAppMode.BLOCKLIST -> packageName !in policy.blocklist && uid !in selectedUids
            }
        }
        .distinct()
        .toList()
}

internal fun ConfigRepository.shouldReloadNodeSettingsChange(): Boolean {
    // 运行配置可能通过应用分流或 detour 引用其他配置中的节点。
    if (SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value) return true
    return VpnStateStore.getActive()
}

internal suspend fun ConfigRepository.refreshNodesAfterNodeMutation(
    profileId: String,
    oldNodeId: String,
    newTag: String,
    newConfig: SingBoxConfig
): List<NodeUi> {
    val oldNodes = profileNodes[profileId] ?: _nodes.value
    val latencyById = oldNodes.associate { it.id to it.latencyMs }
    val updatedNodeId = ConfigRepository.stableNodeId(profileId, newTag)
    val originalLatency = oldNodes.find { it.id == oldNodeId }?.latencyMs
    val newNodes = extractNodesFromConfig(newConfig, profileId)
    val mergedNodes = mergeMutatedNodeLatencies(
        newNodes = newNodes,
        latencyById = latencyById,
        updatedNodeId = updatedNodeId,
        originalLatency = originalLatency
    )
    profileNodes[profileId] = mergedNodes
    updateAllNodesAndGroups()
    applyMutatedActiveNode(profileId, oldNodeId, newTag, mergedNodes)
    saveProfiles()
    return mergedNodes
}

internal fun ConfigRepository.mergeMutatedNodeLatencies(
    newNodes: List<NodeUi>,
    latencyById: Map<String, Long?>,
    updatedNodeId: String,
    originalLatency: Long?
): List<NodeUi> {
    return newNodes.map { nodeItem ->
        val storedLatency = latencyById[nodeItem.id]
            ?: if (nodeItem.id == updatedNodeId) originalLatency else null
        if (storedLatency != null) nodeItem.copy(latencyMs = storedLatency) else nodeItem
    }
}

internal fun ConfigRepository.applyMutatedActiveNode(
    profileId: String,
    oldNodeId: String,
    newTag: String,
    mergedNodes: List<NodeUi>
) {
    if (_activeProfileId.value != profileId) return

    _nodes.value = mergedNodes
    if (_activeNodeId.value != oldNodeId) return

    val newNode = mergedNodes.find { it.name == newTag }
    if (newNode != null) {
        _activeNodeId.value = newNode.id
    }
}

internal suspend fun ConfigRepository.exportNode(nodeId: String): String? = withContext(Dispatchers.IO) {
    val node = _nodes.value.find { it.id == nodeId } ?: run {
        Log.e(ConfigRepository.TAG, "exportNode: Node not found in UI list: $nodeId")
        return@withContext null
    }

    val config = loadConfig(node.sourceProfileId) ?: run {
        Log.e(ConfigRepository.TAG, "exportNode: Config not found for profile: ${node.sourceProfileId}")
        return@withContext null
    }

    val outbound = config.outbounds?.find { it.tag == node.name } ?: run {
        Log.e(ConfigRepository.TAG, "exportNode: Outbound not found in config with tag: ${node.name}")
        return@withContext null
    }

    NodeLinkExporter.export(outbound, gson)
}

internal fun ConfigRepository.deduplicateTags(config: SingBoxConfig): SingBoxConfig {
    ConfigRepository.findUnsupportedAndroidCapability(config)?.let { message ->
        throw IllegalArgumentException(message)
    }
    val normalizedConfig = ConfigRepository.normalizeWireGuardEndpointsForInternalUse(config)
    val outbounds = normalizedConfig.outbounds
    if (outbounds == null) return normalizedConfig
    val seenTags = mutableSetOf<String>()

    val newOutbounds = outbounds.map { outbound ->
        var tag = outbound.tag
        if (tag.isBlank()) {
            tag = "unnamed"
        }

        var newTag = tag
        var counter = 1
        while (seenTags.contains(newTag)) {
            newTag = "${tag}_$counter"
            counter++
        }

        seenTags.add(newTag)

        if (newTag != outbound.tag) {
            outbound.copy(tag = newTag)
        } else {
            outbound
        }
    }

    return normalizedConfig.copy(outbounds = newOutbounds)
}

internal fun ConfigRepository.findAvailablePort(startPort: Int): Int {
    for (port in startPort until startPort + 100) {
        try {
            java.net.ServerSocket(port).use {
                return port
            }
        } catch (_: Exception) {
        }
    }
    return startPort
}

internal fun ConfigRepository.cleanup() {
    scope.cancel()
    ConfigRepository.nodeIdCache.clear()
    configCache.clear()
    configCacheAccessTimes.clear()
    profileNodes.clear()
    savedNodeLatencies.clear()
    inFlightLatencyTests.clear()
    Log.i(ConfigRepository.TAG, "ConfigRepository cleanup completed")
}

internal fun ConfigRepository.isIpAddress(address: String?): Boolean {
    return ConfigRepository.isIpAddressValue(address)
}
