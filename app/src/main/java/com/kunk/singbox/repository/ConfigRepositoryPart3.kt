package com.kunk.singbox.repository

import com.kunk.singbox.R
import android.content.Intent
import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import com.google.gson.JsonParser
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.*
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.database.entity.ProfileEntity
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Suppress("TooManyFunctions")
abstract class ConfigRepositoryPart3(context: Context) : ConfigRepositoryPart2(context) {
    override suspend fun importFromContent(
        name: String,
        content: String,
        profileType: ProfileType,
        onProgress: (String) -> Unit): Result<ProfileUi> = withContext(Dispatchers.IO) {
        var profileId: String? = null
        try {
            onProgress(context.getString(R.string.common_loading))

            val normalized = normalizeImportedContent(content)
            if (ConfigRepository.looksLikeSubscriptionUrlForImport(normalized)) {
                return@withContext importFromSubscription(
                    name = name,
                    url = normalized,
                    onProgress = onProgress
                )
            }

            val config = subscriptionManager.parse(normalized)
                ?: return@withContext Result.failure(Exception(context.getString(R.string.profiles_parse_failed)))

            onProgress(context.getString(R.string.profiles_extracting_nodes, 0, 0))

            profileId = UUID.randomUUID().toString()
            val deduplicatedConfig = deduplicateTags(config)
            val nodes = extractNodesFromConfig(deduplicatedConfig, profileId, onProgress)

            if (nodes.isEmpty()) {
                return@withContext Result.failure(Exception(context.getString(R.string.nodes_no_valid_found)))
            }

            writeConfigFileOrThrow(profileId, deduplicatedConfig)

            val profile = ProfileUi(
                id = profileId,
                name = name,
                type = profileType,
                url = null,
                lastUpdated = System.currentTimeMillis(),
                enabled = true,
                updateStatus = UpdateStatus.Idle
            )

            cacheConfig(profileId, deduplicatedConfig)
            profileNodes[profileId] = nodes
            updateAllNodesAndGroups()

            _profiles.update { it + profile }
            saveProfiles()

            if (_activeProfileId.value == null) {
                setActiveProfile(profileId)
            }

            onProgress(context.getString(R.string.profiles_import_success, nodes.size.toString()))

            Result.success(profile)
        } catch (e: Exception) {
            profileId?.let { rollbackTransientProfileFile(it) }
            Log.e(ConfigRepository.TAG, "Failed to import profile from content", e)
            Result.failure(e)
        }
    }

    protected override fun normalizeImportedContent(content: String): String {
        val trimmed = content.trim().trimStart('\uFEFF')
        val lines = trimmed.lines().toMutableList()

        fun isFenceLine(line: String): Boolean {
            val t = line.trim()
            if (t.startsWith("```")) return true
            return t.length >= 2 && t.all { it == '`' }
        }

        if (lines.isNotEmpty() && isFenceLine(lines.first())) {
            lines.removeAt(0)
        }
        if (lines.isNotEmpty() && isFenceLine(lines.last())) {
            lines.removeAt(lines.lastIndex)
        }

        return lines.joinToString("\n").trim()
    }

    protected override fun tryDecodeBase64(content: String): String? {
        val s = content.trim().trimStart('\uFEFF')
        if (s.isBlank()) return null
        val candidates = arrayOf(
            Base64.DEFAULT,
            Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        for (flags in candidates) {
            try {
                val decoded = Base64.decode(s, flags)
                val text = String(decoded)
                if (text.isNotBlank()) return text
            } catch (e: Exception) {
                Log.v(ConfigRepository.TAG, "Base64 decode attempt failed with flags=$flags", e)
            }
        }
        return null
    }

    protected override fun extractOutboundsOnly(config: SingBoxConfig): SingBoxConfig {
        val outbounds = config.outbounds ?: config.proxies ?: emptyList()
        return SingBoxConfig(outbounds = outbounds)
    }

    protected override fun extractOutboundsFromJson(jsonContent: String): List<Outbound>? {
        val trimmed = jsonContent.trim()
        if (!trimmed.startsWith("{")) return null

        return try {
            val jsonObject = JsonParser.parseString(trimmed).asJsonObject
            val outboundsElement = jsonObject.get("outbounds") ?: jsonObject.get("proxies")
            if (outboundsElement != null && outboundsElement.isJsonArray) {
                val outbounds: List<Outbound> = gson.fromJson(outboundsElement, ConfigRepository.TYPE_OUTBOUND_LIST)
                if (outbounds.isNotEmpty()) {
                    return outbounds
                }
            }
            null
        } catch (e: Exception) {
            Log.w(ConfigRepository.TAG, "extractOutboundsFromJson failed: ${e.message}")
            null
        }
    }

    protected override fun sanitizeSubscriptionSnippet(content: String): String {
        val snippet = content.take(200)
        return ConfigRepository.REGEX_SANITIZE_UUID.replace(
            ConfigRepository.REGEX_SANITIZE_PASSWORD.replace(
                ConfigRepository.REGEX_SANITIZE_TOKEN.replace(snippet, "token=***"),
                "password=***"
            ),
            "uuid=***"
        )
    }

    protected override fun parseClashYamlConfig(content: String): SingBoxConfig? {
        return if (clashYamlParser.canParse(content)) {
            clashYamlParser.parse(content)
        } else {
            null
        }
    }

    protected override fun parseSubscriptionResponse(content: String): SingBoxConfig? {
        val normalizedContent = normalizeImportedContent(content)
        try {
            val outbounds = extractOutboundsFromJson(normalizedContent)
            if (outbounds != null && outbounds.isNotEmpty()) {
                return SingBoxConfig(outbounds = outbounds)
            } else {
                Log.w(ConfigRepository.TAG, "Parsed as JSON but outbounds/proxies is empty/null. content snippet: ${sanitizeSubscriptionSnippet(normalizedContent)}")
            }
        } catch (e: Exception) {
            Log.w(ConfigRepository.TAG, "Failed to extract outbounds from JSON: ${e.message}")
        }
        try {
            val yamlConfig = parseClashYamlConfig(normalizedContent)
            if (yamlConfig?.outbounds != null && yamlConfig.outbounds.isNotEmpty()) {
                return extractOutboundsOnly(yamlConfig)
            }
        } catch (_: Exception) {
        }
        try {
            val decoded = tryDecodeBase64(normalizedContent)
            if (decoded.isNullOrBlank()) {
                throw IllegalStateException("base64 decode failed")
            }
            try {
                val outbounds = extractOutboundsFromJson(decoded)
                if (outbounds != null && outbounds.isNotEmpty()) {
                    return SingBoxConfig(outbounds = outbounds)
                } else {
                    Log.w(ConfigRepository.TAG, "Parsed decoded Base64 as JSON but outbounds is empty/null")
                }
            } catch (e: Exception) {
                Log.w(ConfigRepository.TAG, "Failed to extract outbounds from decoded Base64 JSON: ${e.message}")
            }

            try {
                val yamlConfig = parseClashYamlConfig(decoded)
                if (yamlConfig?.outbounds != null && yamlConfig.outbounds.isNotEmpty()) {
                    return extractOutboundsOnly(yamlConfig)
                }
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
        }
        try {
            val lines = normalizedContent.trim().lines().filter { it.isNotBlank() }
            if (lines.isNotEmpty()) {
                val decoded = tryDecodeBase64(normalizedContent) ?: normalizedContent

                val decodedLines = decoded.trim().lines().filter { it.isNotBlank() }
                val outbounds = mutableListOf<Outbound>()

                for (line in decodedLines) {
                    val cleanedLine = line.trim()
                        .removePrefix("- ")
                        .removePrefix("\"")
                        .trim()
                        .trim('`', '"', '\'')
                    val outbound = parseNodeLink(cleanedLine)
                    if (outbound != null) {
                        outbounds.add(outbound)
                    }
                }

                if (outbounds.isNotEmpty()) {
                    return SingBoxConfig(
                        outbounds = outbounds
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(ConfigRepository.TAG, "Failed to parse subscription response as node links", e)
        }

        return null
    }

    protected override fun parseNodeLink(link: String): Outbound? {
        return nodeLinkParser.parse(link)
    }

    protected override suspend fun extractNodesFromConfig(
        config: SingBoxConfig,
        profileId: String,
        onProgress: ((String) -> Unit)?): List<NodeUi> {
        val outbounds = config.outbounds ?: return emptyList()
        val trafficRepo = withContext(Dispatchers.IO) {
            TrafficRepository.getInstance(context)
        }
        return withContext(Dispatchers.Default) {
            val groupOutbounds = outbounds.filter {
                it.type == "selector" || it.type == "urltest"
            }
            val nodeToGroup = mutableMapOf<String, String>()
            groupOutbounds.forEach { group ->
                group.outbounds?.forEach { nodeName ->
                    nodeToGroup[nodeName] = group.tag
                }
            }
            val proxyTypes = setOf(
                "shadowsocks", "vmess", "vless", "trojan",
                "hysteria", "hysteria2", "tuic", "wireguard",
                "shadowtls", "ssh", "anytls", "naive", "http", "socks"
            )
            val detourTags = outbounds.mapNotNull { it.detour }.toSet()

            val validOutbounds = outbounds.filter {
                it.type in proxyTypes && it.tag !in detourTags
            }
            if (validOutbounds.isEmpty()) return@withContext emptyList()

            val total = validOutbounds.size
            val completed = AtomicInteger(0)
            val semaphore = Semaphore(ConfigRepository.PARALLEL_CONCURRENCY)

            val deferredNodes = validOutbounds.map { outbound ->
                async {
                    semaphore.withPermit {
                        val node = createNodeUi(outbound, profileId, nodeToGroup, trafficRepo)
                        val done = completed.incrementAndGet()
                        if (done % 100 == 0 || done == total) {
                            onProgress?.invoke(context.getString(R.string.profiles_extracting_nodes, done, total))
                        }
                        node
                    }
                }
            }

            deferredNodes.awaitAll().filterNotNull()
        }
    }

    protected override fun extractNodesFromConfigSync(
        config: SingBoxConfig,
        profileId: String
    ): List<NodeUi> {
        val outbounds = config.outbounds ?: return emptyList()
        val trafficRepo = TrafficRepository.getInstance(context)
        val groupOutbounds = outbounds.filter {
            it.type == "selector" || it.type == "urltest"
        }
        val nodeToGroup = mutableMapOf<String, String>()
        groupOutbounds.forEach { group ->
            group.outbounds?.forEach { nodeName ->
                nodeToGroup[nodeName] = group.tag
            }
        }
        val proxyTypes = setOf(
            "shadowsocks", "vmess", "vless", "trojan",
            "hysteria", "hysteria2", "tuic", "wireguard",
            "shadowtls", "ssh", "anytls", "naive", "http", "socks"
        )
        val detourTags = outbounds.mapNotNull { it.detour }.toSet()

        val validOutbounds = outbounds.filter {
            it.type in proxyTypes && it.tag !in detourTags
        }
        if (validOutbounds.isEmpty()) return emptyList()

        return validOutbounds.mapNotNull { outbound ->
            createNodeUi(outbound, profileId, nodeToGroup, trafficRepo)
        }
    }

    protected override fun createNodeUi(
        outbound: Outbound,
        profileId: String,
        nodeToGroup: Map<String, String>,
        trafficRepo: TrafficRepository
    ): NodeUi? {
        if (outbound.tag.isBlank()) return null

        var group = nodeToGroup[outbound.tag] ?: "Default"
        if (group.contains("://") || group.length > 50) {
            group = "Default"
        }

        val id = ConfigRepository.stableNodeId(profileId, outbound.tag)

        return NodeUi(
            id = id,
            name = outbound.tag,
            protocol = outbound.type,
            group = group,
            latencyMs = null,
            isFavorite = false,
            sourceProfileId = profileId,
            trafficUsed = trafficRepo.getMonthlyTotal(id),
            tags = buildList {
                outbound.tls?.let {
                    if (it.enabled == true) add("TLS")
                    it.reality?.let { r -> if (r.enabled == true) add("Reality") }
                }
                outbound.transport?.type?.let { add(it.uppercase()) }
            }
        )
    }

    override suspend fun setActiveProfileAndWait(profileId: String, targetNodeId: String?) {
        val nodes = setActiveProfile(profileId, targetNodeId)
            ?: loadProfileNodesWithLatency(profileId)
        if (nodes != null && _activeProfileId.value == profileId) {
            applyActiveProfileNodes(profileId, nodes, targetNodeId)
            saveProfilesImmediate()
        }
    }

    override fun setActiveProfile(profileId: String, targetNodeId: String?): List<NodeUi>? {
        val currentProfileId = _activeProfileId.value
        val currentNodeId = _activeNodeId.value
        if (currentProfileId != null && currentNodeId != null && currentProfileId != profileId) {
            saveProfileNodeMemory(currentProfileId, currentNodeId)
        }

        _activeProfileId.value = profileId
        val cached = profileNodes[profileId]

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
                    val latency = savedNodeLatencies[node.id]
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

    override fun setActiveNodeIdOnly(nodeId: String) {
        _activeNodeId.value = nodeId
        _nodes.value.find { it.id == nodeId }?.name?.let { VpnStateStore.setSelectedNodeLabel(it) }
        _activeProfileId.value?.let { profileId ->
            saveProfileNodeMemory(profileId, nodeId)
        }
        saveProfilesImmediate()
    }

    protected override fun nodeDisplayName(nodeId: String, fallbackNodes: List<NodeUi>): String? {
        return _nodes.value.find { it.id == nodeId }?.name
            ?: fallbackNodes.find { it.id == nodeId }?.name
    }

    override suspend fun setActiveNode(nodeId: String): Boolean {
        val result = setActiveNodeWithResult(nodeId)
        return result is ConfigRepository.NodeSwitchResult.Success || result is ConfigRepository.NodeSwitchResult.NotRunning
    }

    override suspend fun setActiveNodeWithResult(nodeId: String): ConfigRepository.NodeSwitchResult {
        if (!nodeSwitchInFlight.compareAndSet(false, true)) {
            Log.i(ConfigRepository.TAG, "setActiveNodeWithResult: switch already in-flight, skip duplicate request for $nodeId")
            return ConfigRepository.NodeSwitchResult.Success
        }

        try {
            val allNodesSnapshot = _allNodes.value.takeIf { it.isNotEmpty() } ?: loadAllNodesSnapshot()

            // Check for cross-profile switch
            val targetNode = allNodesSnapshot.find { it.id == nodeId }
            if (targetNode != null && targetNode.sourceProfileId != _activeProfileId.value) {
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
                                val latency = savedNodeLatencies[node.id]
                                if (latency != null) node.copy(latencyMs = latency) else node
                            }
                            profileNodes[profileId] = nodesWithLatency
                        }
                    }
                }

                setActiveProfile(targetNode.sourceProfileId, nodeId)
            }

            _activeNodeId.value = nodeId
            nodeDisplayName(nodeId, allNodesSnapshot)?.let { VpnStateStore.setSelectedNodeLabel(it) }
            saveProfilesImmediate()

            val remoteRunning = SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value
            if (!remoteRunning) {
                Log.i(ConfigRepository.TAG, "setActiveNodeWithResult: VPN not running, skip hot switch")
                return ConfigRepository.NodeSwitchResult.NotRunning
            }

            return withContext(Dispatchers.IO) {
                var node = _nodes.value.find { it.id == nodeId }
                if (node == null) {
                    node = allNodesSnapshot.find { it.id == nodeId }
                }

                if (node == null) {
                    val msg = "Target node not found: $nodeId"
                    Log.w(ConfigRepository.TAG, msg)
                    return@withContext ConfigRepository.NodeSwitchResult.Failed(msg)
                }

                try {
                    val generationResult = generateConfigFile()
                    if (generationResult == null) {
                        val msg = context.getString(R.string.dashboard_config_generation_failed)
                        Log.e(ConfigRepository.TAG, msg)
                        return@withContext ConfigRepository.NodeSwitchResult.Failed(msg)
                    }

                    // ... [Skipping comments for brevity in replacement]
                    runCatching {
                        val oldCacheDb = File(context.filesDir, "cache.db")
                        if (oldCacheDb.exists()) oldCacheDb.delete()
                    }
                    val currentTags = generationResult.outboundTags
                    val currentProfileId = _activeProfileId.value
                    val isFirstSwitchWhileRunning = lastRunProfileId == null && remoteRunning
                    val profileChanged = (lastRunProfileId != null && lastRunProfileId != currentProfileId) || isFirstSwitchWhileRunning
                    val tagsActuallyChanged = lastRunOutboundTags != null && lastRunOutboundTags != currentTags
                    val isVpnStartingNotReady = SingBoxRemote.isStarting.value && !SingBoxRemote.isRunning.value
                    val needsConfigReload = lastRunOutboundTags == null && remoteRunning

                    val tagsChanged = tagsActuallyChanged ||
                        profileChanged ||
                        isVpnStartingNotReady ||
                        needsConfigReload

                    Log.d(
                        ConfigRepository.TAG,
                        "Switch decision: profileChanged=$profileChanged " +
                            "(last=$lastRunProfileId, cur=$currentProfileId, " +
                            "firstSwitch=$isFirstSwitchWhileRunning), " +
                            "tagsActuallyChanged=$tagsActuallyChanged, " +
                            "isVpnStartingNotReady=$isVpnStartingNotReady, " +
                            "needsConfigReload=$needsConfigReload, tagsChanged=$tagsChanged"
                    )
                    lastRunOutboundTags = currentTags
                    lastRunProfileId = currentProfileId

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
                            } else {
                                Intent(context, SingBoxService::class.java).apply {
                                    action = SingBoxService.ACTION_PREPARE_RESTART
                                    putExtra(
                                        com.kunk.singbox.service.SingBoxService.EXTRA_PREPARE_RESTART_REASON,
                                        "ConfigRepository:switchNode"
                                    )
                                }
                            }
                            context.startService(prepareIntent)
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
                            node?.name?.let { putExtra(SingBoxService.EXTRA_PENDING_NODE_NAME, it) }
                            putExtra(ProxyOnlyService.EXTRA_CONFIG_PATH, generationResult.path)
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
                            node?.name?.let { putExtra(SingBoxService.EXTRA_PENDING_NODE_NAME, it) }
                            putExtra(SingBoxService.EXTRA_CONFIG_PATH, generationResult.path)
                        }
                    }

                    // Service already running (VPN active). Use startService to avoid foreground-service timing constraints.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && tagsChanged) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }

                    Log.i(ConfigRepository.TAG, "Requested switch for node: ${node.name} (Tag: ${generationResult.activeNodeTag}, Restart: $tagsChanged)")
                    ConfigRepository.NodeSwitchResult.Success
                } catch (e: Exception) {

                    val msg = "Switch error: ${e.message ?: "unknown error"}"
                    Log.e(ConfigRepository.TAG, "Error during hot switch", e)
                    ConfigRepository.NodeSwitchResult.Failed(msg)
                }
            }
        } finally {
            nodeSwitchInFlight.set(false)
        }
    }

    override suspend fun syncActiveNodeFromProxySelection(proxyName: String?): Boolean {
        if (proxyName.isNullOrBlank()) return false

        val activeProfileId = _activeProfileId.value ?: return false
        val candidates = _nodes.value
        val matched = candidates.firstOrNull { it.name == proxyName } ?: return false
        if (matched.sourceProfileId != activeProfileId) return false
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

    override suspend fun deleteProfile(profileId: String) = withContext(Dispatchers.IO) {
        com.kunk.singbox.service.SubscriptionAutoUpdateWorker.cancel(context, profileId)

        _profiles.update { list -> list.filter { it.id != profileId } }
        removeCachedConfig(profileId)
        dnsResolveStore.removeAllForProfile(profileId)
        profileNodes.remove(profileId)
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

    override suspend fun importProfileDirectly(profile: ProfileUi, config: SingBoxConfig) = withContext(Dispatchers.IO) {
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

    override fun toggleProfileEnabled(profileId: String) {
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

    override fun reorderProfiles(newProfiles: List<ProfileUi>) {
        _profiles.value = newProfiles
        saveProfiles()
    }

    override fun updateProfileMetadata(
        profileId: String,
        newName: String,
        newUrl: String?,
        autoUpdateInterval: Int,
        dnsPreResolve: Boolean,
        dnsServer: String?,
        dnsOverride: String?) {
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
}
