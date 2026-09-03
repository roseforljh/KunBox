@file:Suppress("UnusedImports", "TooManyFunctions", "LongMethod", "LargeClass", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeCons")

package com.kunk.singbox.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.kunk.singbox.R
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.database.entity.ActiveStateEntity
import com.kunk.singbox.database.entity.NodeLatencyEntity
import com.kunk.singbox.database.entity.ProfileEntity
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.*
import com.kunk.singbox.model.PingResultCode
import com.kunk.singbox.service.tun.VpnTunManager
import com.kunk.singbox.utils.NetworkClient
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

internal fun ConfigRepository.getEffectiveTunStack(userSelected: TunStack): TunStack {
    val model = Build.MODEL
    if (listOf("SM-G986U", "PJZ110").any { model.contains(it, ignoreCase = true) }) {
        Log.w(ConfigRepository.TAG, "Device $model detected, forcing GVISOR stack (ignoring user selection: ${userSelected.name})")
        return TunStack.GVISOR
    }

    return userSelected
}

internal fun ConfigRepository.getEffectiveTunMtu(settings: AppSettings): Int {
    val configuredMtu = settings.tunMtu
    if (!settings.tunMtuAuto) return configuredMtu

    return VpnTunManager.resolveAutoMtu(
        configuredMtu = configuredMtu,
        physicalMtu = getPhysicalNetworkMtu(),
        includesIpv6 = settings.ipVersionMode.includesIpv6
    )
}

@Suppress("DEPRECATION")
internal fun ConfigRepository.getPhysicalNetworkMtu(): Int? {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return null
    val physicalNetwork = cm.allNetworks.firstOrNull { network ->
        val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    } ?: cm.activeNetwork?.takeIf { network ->
        cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return physicalNetwork
        ?.let(cm::getLinkProperties)
        ?.mtu
        ?.takeIf { it > 0 }
}

internal fun ConfigRepository.getSubscriptionClient(timeoutBudget: SubscriptionAttemptTimeoutBudget): OkHttpClient {
    return NetworkClient.createClientWithoutRetry(
        connectTimeoutSeconds = timeoutBudget.connectTimeoutSeconds,
        readTimeoutSeconds = timeoutBudget.readTimeoutSeconds,
        writeTimeoutSeconds = timeoutBudget.writeTimeoutSeconds,
        callTimeoutSeconds = timeoutBudget.callTimeoutSeconds
    )
}

internal fun ConfigRepository.getSubscriptionProxyClient(timeoutBudget: SubscriptionAttemptTimeoutBudget): OkHttpClient? {
    val settings = cachedSettings ?: AppSettings()
    if (!VpnStateStore.getActive() || settings.proxyPort <= 0) {
        return null
    }
    return NetworkClient.createClientWithProxy(
        proxyPort = settings.proxyPort,
        connectTimeoutSeconds = timeoutBudget.connectTimeoutSeconds,
        readTimeoutSeconds = timeoutBudget.readTimeoutSeconds,
        writeTimeoutSeconds = timeoutBudget.writeTimeoutSeconds,
        callTimeoutSeconds = timeoutBudget.callTimeoutSeconds
    )
}

internal fun ConfigRepository.getRememberedSubscriptionUserAgent(url: String): String? {
    val host = ConfigRepository.extractSubscriptionHost(url) ?: return null
    return subscriptionUaMemoryMmkv.decodeString(host, null)
}

internal fun ConfigRepository.rememberSuccessfulSubscriptionUserAgent(url: String, userAgent: String) {
    val host = ConfigRepository.extractSubscriptionHost(url) ?: return
    subscriptionUaMemoryMmkv.encode(host, userAgent)
}

internal fun ConfigRepository.buildSubscriptionUaHealthKey(host: String, userAgent: String, suffix: String): String {
    return "$host|$userAgent|$suffix"
}

internal fun ConfigRepository.readSubscriptionUaFailureCount(key: String): Int {
    val memoryValue = subscriptionUaFailureCountMemory[key] ?: 0
    val persistedValue = runCatching {
        subscriptionUaHealthMmkv.decodeInt(key, memoryValue)
    }.getOrElse { e ->
        Log.w(ConfigRepository.TAG, "Failed to read subscription UA failure count for key=$key, using memory fallback", e)
        memoryValue
    }
    val effectiveValue = maxOf(memoryValue, persistedValue)
    if (effectiveValue > 0) {
        subscriptionUaFailureCountMemory[key] = effectiveValue
    } else {
        subscriptionUaFailureCountMemory.remove(key)
    }
    return effectiveValue
}

internal fun ConfigRepository.readSubscriptionUaBlockedUntil(key: String): Long {
    val memoryValue = subscriptionUaBlockedUntilMemory[key] ?: 0L
    val persistedValue = runCatching {
        subscriptionUaHealthMmkv.decodeLong(key, memoryValue)
    }.getOrElse { e ->
        Log.w(ConfigRepository.TAG, "Failed to read subscription UA blocked-until for key=$key, using memory fallback", e)
        memoryValue
    }
    val effectiveValue = maxOf(memoryValue, persistedValue)
    if (effectiveValue > 0L) {
        subscriptionUaBlockedUntilMemory[key] = effectiveValue
    } else {
        subscriptionUaBlockedUntilMemory.remove(key)
    }
    return effectiveValue
}

internal fun ConfigRepository.persistSubscriptionUaFailureCount(key: String, value: Int) {
    if (value > 0) {
        subscriptionUaFailureCountMemory[key] = value
    } else {
        subscriptionUaFailureCountMemory.remove(key)
    }
    runCatching {
        subscriptionUaHealthMmkv.encode(key, value)
    }.onFailure { e ->
        Log.w(ConfigRepository.TAG, "Failed to persist subscription UA failure count for key=$key", e)
    }
}

internal fun ConfigRepository.persistSubscriptionUaBlockedUntil(key: String, value: Long) {
    if (value > 0L) {
        subscriptionUaBlockedUntilMemory[key] = value
    } else {
        subscriptionUaBlockedUntilMemory.remove(key)
    }
    runCatching {
        subscriptionUaHealthMmkv.encode(key, value)
    }.onFailure { e ->
        Log.w(ConfigRepository.TAG, "Failed to persist subscription UA blocked-until for key=$key", e)
    }
}

internal fun ConfigRepository.clearSubscriptionUaHealthKey(key: String, memoryCache: MutableMap<String, *>) {
    when (memoryCache) {
        subscriptionUaFailureCountMemory -> subscriptionUaFailureCountMemory.remove(key)
        subscriptionUaBlockedUntilMemory -> subscriptionUaBlockedUntilMemory.remove(key)
    }
    runCatching {
        subscriptionUaHealthMmkv.removeValueForKey(key)
    }.onFailure { e ->
        Log.w(ConfigRepository.TAG, "Failed to clear subscription UA health key=$key", e)
    }
}

internal fun ConfigRepository.getCircuitBrokenUserAgents(host: String, nowMs: Long = System.currentTimeMillis()): Set<String> {
    return ConfigRepository.USER_AGENTS.filter { userAgent ->
        val blockedUntilKey = buildSubscriptionUaHealthKey(host, userAgent, "blocked_until")
        val blockedUntil = readSubscriptionUaBlockedUntil(blockedUntilKey)
        if (blockedUntil <= nowMs) {
            persistSubscriptionUaBlockedUntil(blockedUntilKey, 0L)
            false
        } else {
            true
        }
    }.toSet()
}

internal fun ConfigRepository.clearSubscriptionUserAgentFailure(host: String, userAgent: String) {
    val failureCountKey = buildSubscriptionUaHealthKey(host, userAgent, "fail_count")
    val blockedUntilKey = buildSubscriptionUaHealthKey(host, userAgent, "blocked_until")
    clearSubscriptionUaHealthKey(failureCountKey, subscriptionUaFailureCountMemory)
    clearSubscriptionUaHealthKey(blockedUntilKey, subscriptionUaBlockedUntilMemory)
}

internal fun ConfigRepository.recordSubscriptionUserAgentFailure(
    host: String,
    userAgent: String,
    nowMs: Long = System.currentTimeMillis()) {
    val failureCountKey = buildSubscriptionUaHealthKey(host, userAgent, "fail_count")
    val blockedUntilKey = buildSubscriptionUaHealthKey(host, userAgent, "blocked_until")
    val nextFailureCount = readSubscriptionUaFailureCount(failureCountKey) + 1
    persistSubscriptionUaFailureCount(failureCountKey, nextFailureCount)
    if (nextFailureCount >= ConfigRepository.SUBSCRIPTION_FAILURE_THRESHOLD) {
        persistSubscriptionUaBlockedUntil(blockedUntilKey, nowMs + ConfigRepository.SUBSCRIPTION_CIRCUIT_BREAKER_WINDOW_MS)
    }
}

internal fun ConfigRepository.buildSubscriptionUserAgents(url: String): List<String> {
    val rememberedUserAgent = getRememberedSubscriptionUserAgent(url)
    val host = ConfigRepository.extractSubscriptionHost(url)
        ?: return ConfigRepository.buildSubscriptionAttemptUserAgents(rememberedUserAgent, emptySet())
    val circuitBrokenUserAgents = getCircuitBrokenUserAgents(host)
    return ConfigRepository.buildSubscriptionAttemptUserAgents(rememberedUserAgent, circuitBrokenUserAgents)
}

internal fun ConfigRepository.resolveNodeNameFromOutboundTag(tag: String?): String? {
    if (tag.isNullOrBlank()) return null
    if (tag.equals("PROXY", ignoreCase = true)) return null
    return when (tag) {
        "direct" -> context.getString(R.string.outbound_tag_direct)
        "block" -> context.getString(R.string.outbound_tag_block)
        else -> {
            lastTagToNodeName[tag]
                ?: _allNodes.value.firstOrNull { it.name == tag }?.name
        }
    }
}

internal suspend fun ConfigRepository.awaitInitialProfilesLoaded() {
    initialProfilesLoadJob?.join()
}

internal fun ConfigRepository.loadProfileNodeMemory() {
    profileNodeMemoryMmkv.allKeys()?.forEach { profileId ->
        val nodeId = profileNodeMemoryMmkv.decodeString(profileId, null)
        if (!nodeId.isNullOrBlank()) {
            profileLastSelectedNode[profileId] = nodeId
        }
    }
}

internal fun ConfigRepository.loadProfileAutoSelections() {
    _profileAutoSelections.value = profileAutoSelectionMmkv.allKeys()
        .orEmpty()
        .associateWith { profileId -> profileAutoSelectionMmkv.decodeBool(profileId, false) }
        .filterValues { it }
}

internal fun ConfigRepository.saveProfileNodeMemory(profileId: String, nodeId: String) {
    profileLastSelectedNode[profileId] = nodeId
    profileNodeMemoryMmkv.encode(profileId, nodeId)
}

internal fun ConfigRepository.getProfileLastSelectedNode(profileId: String): String? {
    return profileNodeMemoryMmkv.decodeString(profileId, null)
        ?.takeIf { it.isNotBlank() }
        ?.also { profileLastSelectedNode[profileId] = it }
        ?: profileLastSelectedNode[profileId]
}

internal fun ConfigRepository.isProfileAutoSelectionEnabled(profileId: String?): Boolean {
    return !profileId.isNullOrBlank() && profileAutoSelectionMmkv.decodeBool(profileId, false)
}

internal fun ConfigRepository.isNodeAutoSelectionEligible(nodeId: String): Boolean {
    return nodeAutoSelectionMmkv.decodeBool(nodeId, true)
}

internal fun ConfigRepository.isNodeMeteredProtected(nodeId: String): Boolean {
    return NodeProtectionStore.isProtected(nodeId)
}

internal fun ConfigRepository.getLastConfigGenerationError(): String? = lastConfigGenerationError

internal fun ConfigRepository.getRuntimeNodeMappings(): Map<String, RuntimeNodeRef> {
    return NodeProtectionStore.runtimeMappings()
}

internal fun ConfigRepository.isMeteredNodeUseAuthorized(nodeId: String): Boolean {
    val node = getNodeById(nodeId) ?: return false
    return NodeProtectionStore.isUseAuthorized(
        nodeId = nodeId,
        activeNodeId = _activeNodeId.value,
        autoSelectionEnabled = isProfileAutoSelectionEnabled(node.sourceProfileId)
    )
}

internal fun ConfigRepository.saveNodeAutoSelectionEligibility(nodeId: String, eligible: Boolean): Boolean {
    return nodeAutoSelectionMmkv.encode(nodeId, eligible)
}

internal fun ConfigRepository.getProfileNodeMemorySnapshot(): Map<String, String> {
    return _profiles.value.mapNotNull { profile ->
        getProfileLastSelectedNode(profile.id)?.let { profile.id to it }
    }.toMap()
}

internal fun ConfigRepository.getProfileAutoSelectionSnapshot(): Map<String, Boolean> {
    return _profiles.value.associate { profile ->
        profile.id to isProfileAutoSelectionEnabled(profile.id)
    }
}

internal suspend fun ConfigRepository.replaceProfileSelectionState(
    nodeMemory: Map<String, String>,
    autoSelection: Map<String, Boolean>,
    allowedProfileIds: Set<String>,
    clearExisting: Boolean
) = withContext(Dispatchers.IO) {
    val existingProfileIds = _profiles.value.mapTo(mutableSetOf()) { it.id }
    val allowed = allowedProfileIds.intersect(existingProfileIds)
    val validNodeMemory = nodeMemory.mapNotNull { (profileId, nodeId) ->
        if (profileId !in allowed) return@mapNotNull null
        val validNodeIds = loadConfig(profileId)
            ?.let { extractNodesFromConfigSync(it, profileId) }
            .orEmpty()
            .mapTo(mutableSetOf()) { it.id }
        (profileId to nodeId).takeIf { nodeId in validNodeIds }
    }.toMap()

    if (clearExisting) {
        profileNodeMemoryMmkv.allKeys().orEmpty().forEach(profileNodeMemoryMmkv::removeValueForKey)
        profileAutoSelectionMmkv.allKeys().orEmpty().forEach(profileAutoSelectionMmkv::removeValueForKey)
        profileLastSelectedNode.clear()
    }
    validNodeMemory.forEach { (profileId, nodeId) ->
        check(profileNodeMemoryMmkv.encode(profileId, nodeId)) {
            "Failed to persist imported node selection for $profileId"
        }
        profileLastSelectedNode[profileId] = nodeId
    }
    autoSelection
        .filterKeys { it in allowed }
        .forEach { (profileId, enabled) ->
            check(profileAutoSelectionMmkv.encode(profileId, enabled)) {
                "Failed to persist imported automatic selection for $profileId"
            }
        }
    loadProfileAutoSelections()
}

internal fun ConfigRepository.saveProfileAutoSelection(profileId: String, enabled: Boolean): Boolean {
    val written = profileAutoSelectionMmkv.encode(profileId, enabled)
    if (written) {
        _profileAutoSelections.update { current ->
            if (enabled) current + (profileId to true) else current - profileId
        }
    }
    return written
}

internal fun ConfigRepository.persistMainProcessSelection(profileId: String, nodeId: String?, nodeName: String?) {
    if (!isMainProcess) return
    VpnStateStore.setSelectedNode(profileId, nodeId)
    VpnStateStore.setSelectedNodeLabel(nodeName)
}

internal fun ConfigRepository.applyActiveProfileNodes(
    profileId: String,
    nodes: List<NodeUi>,
    targetNodeId: String? = null) {
    _nodes.value = nodes
    val currentActiveId = _activeNodeId.value
    _activeNodeId.value = when {
        targetNodeId != null && nodes.any { it.id == targetNodeId } -> targetNodeId
        currentActiveId != null && nodes.any { it.id == currentActiveId } -> currentActiveId
        else -> {
            val rememberedNodeId = getProfileLastSelectedNode(profileId)
            when {
                rememberedNodeId != null && nodes.any { it.id == rememberedNodeId } -> rememberedNodeId
                nodes.isNotEmpty() -> nodes.minBy { it.id }.id.also { fallbackNodeId ->
                    saveProfileNodeMemory(profileId, fallbackNodeId)
                    LogRepository.getInstance().addAlwaysLog(
                        "INFO [CFG] profile_selection_fallback profile=$profileId node=$fallbackNodeId"
                    )
                }
                else -> null
            }
        }
    }
    val selectedName = _activeNodeId.value?.let { activeId ->
        nodes.find { it.id == activeId }?.name
    }
    persistMainProcessSelection(profileId, _activeNodeId.value, selectedName)
}

internal suspend fun ConfigRepository.loadProfileNodesWithLatency(profileId: String): List<NodeUi>? {
    val cfg = withContext(Dispatchers.IO) { loadConfig(profileId) } ?: return null
    val nodes = extractNodesFromConfig(cfg, profileId)
    return nodes.map { node ->
        val latency = savedLatencyMs(node.id)
        if (latency != null) node.copy(latencyMs = latency) else node
    }.also { profileNodes[profileId] = it }
}

internal fun ConfigRepository.loadConfig(profileId: String): SingBoxConfig? {
    configCache[profileId]?.let {
        configCacheAccessTimes[profileId] = System.currentTimeMillis()
        return it
    }

    val configFile = File(configDir, "$profileId.json")
    if (!configFile.exists()) return null

    return try {
        val configJson = configFile.readText()
        var config = gson.fromJson(configJson, SingBoxConfig::class.java)
        config = deduplicateTags(config)
        cacheConfig(profileId, config)
        config
    } catch (e: Exception) {
        Log.e(ConfigRepository.TAG, "Failed to load config for profile: $profileId", e)
        null
    }
}

internal fun ConfigRepository.cacheConfig(profileId: String, config: SingBoxConfig) {
    configCache[profileId] = config
    configCacheAccessTimes[profileId] = System.currentTimeMillis()
}

internal fun ConfigRepository.removeCachedConfig(profileId: String) {
    configCache.remove(profileId)
    configCacheAccessTimes.remove(profileId)
}

internal fun ConfigRepository.startConfigCacheCleanup() {
    scope.launch {
        while (isActive) {
            delay(ConfigRepository.CONFIG_CACHE_CLEANUP_INTERVAL_MINUTES * 60_000L)
            try {
                cleanupExpiredCache()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(ConfigRepository.TAG, "Failed to cleanup expired config cache", e)
            }
        }
    }
}

internal fun ConfigRepository.cleanupExpiredCache(now: Long = System.currentTimeMillis()) {
    synchronized(configCache) {
        val iterator = configCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val lastAccessTime = configCacheAccessTimes[entry.key] ?: now
            if (now - lastAccessTime > ConfigRepository.CONFIG_CACHE_EXPIRY_MS) {
                iterator.remove()
                configCacheAccessTimes.remove(entry.key)
            }
        }
    }
}

internal fun ConfigRepository.saveProfiles() {
    saveProfilesJob?.cancel()
    saveProfilesJob = scope.launch {
        delay(saveDebounceMs)
        saveProfilesInternal()
    }
}

internal fun ConfigRepository.saveProfilesImmediate() {
    saveProfilesJob?.cancel()
    saveProfilesJob = scope.launch {
        saveProfilesInternal()
    }
}

internal suspend fun ConfigRepository.saveProfilesInternal() {
    saveProfilesMutex.withLock {
        try {
            val startTime = System.currentTimeMillis()
            val profiles = _profiles.value
            val activeProfileId = _activeProfileId.value
            val activeNodeId = _activeNodeId.value
            try {
                activeStateDao.save(ActiveStateEntity(
                    id = 1,
                    activeProfileId = activeProfileId,
                    activeNodeId = activeNodeId
                ))
            } catch (e: Exception) {
                Log.e(ConfigRepository.TAG, "Failed to save active state", e)
            }

            val entities = profiles.mapIndexed { index, profile ->
                ProfileEntity.fromUiModel(profile, sortOrder = index)
            }
            profileDao.insertAll(entities)

            val elapsed = System.currentTimeMillis() - startTime
            Log.d(ConfigRepository.TAG, "Saved ${profiles.size} profiles to Room in ${elapsed}ms")
        } catch (e: Exception) {
            Log.e(ConfigRepository.TAG, "Failed to save profiles", e)
        }
    }
}

internal fun ConfigRepository.writeConfigFileOrThrow(profileId: String, config: SingBoxConfig) {
    val configFile = File(configDir, "$profileId.json")
    try {
        ConfigRepository.writeTextFileAtomically(configFile, gson.toJson(config))
    } catch (e: Exception) {
        Log.e(ConfigRepository.TAG, "Failed to write config file for profile: $profileId", e)
        throw IllegalStateException("Failed to write config for profile $profileId", e)
    }
}

internal fun ConfigRepository.beginProfileUpdateRun(profileId: String): Long {
    val runId = profileUpdateRunCounter.incrementAndGet()
    profileUpdateRuns[profileId] = runId
    return runId
}

internal fun ConfigRepository.updateProfileForCurrentRun(
    profileId: String,
    runId: Long,
    transform: (ProfileUi) -> ProfileUi
) {
    _profiles.update { profiles ->
        if (profileUpdateRuns[profileId] != runId) {
            return@update profiles
        }
        profiles.map { profile ->
            if (profile.id == profileId) transform(profile) else profile
        }
    }
}

internal fun ConfigRepository.setProfileUpdateStage(
    profileId: String,
    runId: Long,
    stage: SubscriptionUpdateStage?
) {
    ConfigRepository.setProfileUpdateStageIfCurrent(
        profilesState = _profiles,
        activeUpdateRuns = profileUpdateRuns,
        profileId = profileId,
        runId = runId,
        stage = stage
    )
}

internal fun ConfigRepository.parseDnsOverride(dnsOverride: String?): DnsConfig? {
    return try {
        ConfigRepository.parseDnsOverrideConfig(dnsOverride)?.let { config ->
            ConfigRepository.prepareDnsOverrideForRuntime(config).also { prepared ->
                if (prepared == null) {
                    Log.w(ConfigRepository.TAG, "DNS override is incompatible with sing-box 1.14, using base DNS")
                }
            }
        }
    } catch (e: Exception) {
        Log.w(ConfigRepository.TAG, "Failed to parse dnsOverride JSON, skipping", e)
        null
    }
}

internal suspend fun ConfigRepository.preResolveDomainsForProfileBestEffort(
    profileId: String,
    config: SingBoxConfig,
    dnsServer: String?
): Boolean {
    return runCatching {
        preResolveDomainsForProfile(profileId, config, dnsServer)
        true
    }.onFailure { error ->
        Log.w(ConfigRepository.TAG, "DNS pre-resolve failed for profile $profileId", error)
    }.getOrDefault(false)
}

internal fun ConfigRepository.rollbackTransientProfileFile(profileId: String) {
    if (_profiles.value.any { it.id == profileId }) {
        return
    }
    removeCachedConfig(profileId)
    profileNodes.remove(profileId)
    val configFile = File(configDir, "$profileId.json")
    if (configFile.exists() && !configFile.delete()) {
        Log.w(ConfigRepository.TAG, "Failed to delete transient profile config: ${configFile.absolutePath}")
    }
}

internal fun ConfigRepository.updateAllNodesAndGroups() {
    if (allNodesUiActiveCount.get() <= 0) {
        _allNodes.value = emptyList()
        return
    }

    val all = profileNodes.values.flatten()
    _allNodes.value = all
}

internal suspend fun ConfigRepository.loadAllNodesSnapshot(): List<NodeUi> = withContext(Dispatchers.IO) {
    val profiles = _profiles.value
    if (profiles.isEmpty()) return@withContext emptyList()
    profiles.map { p ->
        async {
            val cfg = loadConfig(p.id) ?: return@async emptyList()
            extractNodesFromConfig(cfg, p.id)
        }
    }.awaitAll().flatten()
}

internal fun ConfigRepository.setAllNodesUiActive(active: Boolean) {
    if (active) {
        val after = allNodesUiActiveCount.incrementAndGet()
        if (after == 1 && !allNodesLoadedForUi) {
            scope.launch {
                val profiles = _profiles.value
                for (p in profiles) {
                    val cfg = loadConfig(p.id) ?: continue
                    val nodes = extractNodesFromConfig(cfg, p.id)
                    val nodesWithLatency = nodes.map { node ->
                        val latency = savedLatencyMs(node.id)
                        if (latency != null) node.copy(latencyMs = latency) else node
                    }
                    profileNodes[p.id] = nodesWithLatency
                }
                updateAllNodesAndGroups()
                allNodesLoadedForUi = true
            }
        }
    } else {
        while (true) {
            val cur = allNodesUiActiveCount.get()
            if (cur <= 0) break
            if (allNodesUiActiveCount.compareAndSet(cur, cur - 1)) break
        }
        if (allNodesUiActiveCount.get() <= 0) {
            allNodesLoadedForUi = false
            val activeId = _activeProfileId.value
            val keep = activeId?.let { profileNodes[it] }
            profileNodes.clear()
            if (activeId != null && keep != null) {
                profileNodes[activeId] = keep
            }
            _allNodes.value = emptyList()
        }
    }
}

internal suspend fun ConfigRepository.updateLatencyInAllNodes(nodeId: String, latency: Long) {
    val latencyValue = normalizeLatencyValue(latency)
    val testedAt = System.currentTimeMillis()
    savedNodeLatencies[nodeId] = SavedNodeLatency(latencyValue, testedAt)
    _allNodes.update { list ->
        list.map {
            if (it.id == nodeId) it.copy(latencyMs = latencyValue) else it
        }
    }
    try {
        nodeLatencyDao.upsert(nodeId, latencyValue, testedAt)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(ConfigRepository.TAG, "Failed to persist latency for $nodeId", e)
    }
}

internal fun ConfigRepository.normalizeLatencyValue(latency: Long): Long {
    return when {
        latency > 0L -> latency
        latency == PingResultCode.UNAVAILABLE -> PingResultCode.UNAVAILABLE
        latency == PingResultCode.IPV6_ONLY -> PingResultCode.IPV6_ONLY
        latency == 0L -> PingResultCode.UNAVAILABLE
        else -> PingResultCode.FAILED_TIMEOUT
    }
}

internal fun ConfigRepository.recordLatencyResult(
    info: ConfigRepositoryNodeTestInfo,
    latency: Long,
    results: MutableMap<String, SavedNodeLatency>,
    onNodeComplete: ((nodeId: String, latencyMs: Long) -> Unit)?
) {
    val latencyValue = normalizeLatencyValue(latency)
    val savedLatency = SavedNodeLatency(latencyValue, System.currentTimeMillis())
    results[info.nodeId] = savedLatency
    // 测完单个节点立即刷新 UI，避免整批结束后才一次性显示
    applyLatencyResultsToMemory(mapOf(info.nodeId to savedLatency))
    onNodeComplete?.invoke(info.nodeId, latencyValue)
}

internal fun ConfigRepository.applyLatencyResultsToMemory(results: Map<String, SavedNodeLatency>) {
    if (results.isEmpty()) return
    savedNodeLatencies.putAll(results)
    val visibleResults = results.mapValues { it.value.latencyMs }
    _nodes.update { nodes -> ConfigRepository.applyLatencyResultsToNodes(nodes, visibleResults) }
    _allNodes.update { nodes -> ConfigRepository.applyLatencyResultsToNodes(nodes, visibleResults) }
    profileNodes.keys.forEach { profileId ->
        profileNodes.computeIfPresent(profileId) { _, nodes ->
            ConfigRepository.applyLatencyResultsToNodes(nodes, visibleResults)
        }
    }
}

internal fun ConfigRepository.savedLatencyMs(nodeId: String): Long? = savedNodeLatencies[nodeId]?.latencyMs

internal suspend fun ConfigRepository.applyLatencyResults(results: Map<String, SavedNodeLatency>) {
    if (results.isEmpty()) return
    // 内存已在 recordLatencyResult 逐节点写入，这里只落库
    try {
        nodeLatencyDao.insertAll(
            results.map { (nodeId, latency) ->
                NodeLatencyEntity(
                    nodeId = nodeId,
                    latencyMs = latency.latencyMs,
                    testedAt = latency.testedAt
                )
            }
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(ConfigRepository.TAG, "Failed to persist batch latency results", e)
    }
}

internal fun ConfigRepository.buildMeteredSafeLatencyOutbounds(
    profileId: String,
    config: SingBoxConfig,
    protectedNodeIds: Set<String>,
    allowedProtectedNodeId: String? = null
): List<Outbound> {
    // WireGuard 在 sing-box 1.13 仅为 endpoint；延迟 runtime 仍保留逻辑 outbound 供节点匹配与探测
    val rawOutbounds = ConfigRepository.buildLatencyRuntimeOutbounds(config) { outbound ->
        buildOutboundForRuntime(outbound)
    }
    return ConfigRepository.resolveLatencyRuntimeDetours(
        sourceProfileId = profileId,
        sourceOutbounds = rawOutbounds,
        isProtectedReference = { referenceProfileId, reference ->
            MeteredNodeConfigGuard.isProtectedNodeReference(
                sourceProfileId = referenceProfileId,
                reference = reference,
                protectedNodeIds = protectedNodeIds,
                allowedProtectedNodeId = allowedProtectedNodeId
            )
        }
    ) { detourProfileId ->
        loadConfig(detourProfileId)?.let { detourConfig ->
            ConfigRepository.buildLatencyRuntimeOutbounds(detourConfig) { outbound ->
                buildOutboundForRuntime(outbound)
            }
        }
    }
}

internal fun ConfigRepository.buildLatencyRuntimeContext(
    profileId: String,
    config: SingBoxConfig,
    settings: AppSettings,
    allowedProtectedNodeId: String? = null
): ConfigRepositoryLatencyRuntimeContext {
    val protectedNodeIds = NodeProtectionStore.protectedNodeIds()
    val detourResolvedOutbounds = buildMeteredSafeLatencyOutbounds(
        profileId = profileId,
        config = config,
        protectedNodeIds = protectedNodeIds,
        allowedProtectedNodeId = allowedProtectedNodeId
    )
    val dnsOverrideConfig = parseDnsOverride(_profiles.value.find { it.id == profileId }?.dnsOverride)
    val dnsProtectionViolations = dnsOverrideConfig?.let { overrideConfig ->
        MeteredNodeConfigGuard.findSourceConfigViolations(
            config = SingBoxConfig(dns = overrideConfig),
            sourceProfileId = profileId,
            protectedNodeIds = protectedNodeIds,
            includeGroupReferences = false
        )
    }.orEmpty()
    if (dnsProtectionViolations.isNotEmpty()) {
        LogRepository.getInstance().addAlwaysLog(
            "WARN [PROTECTION] 计费节点保护已跳过测速：" +
                dnsProtectionViolations.joinToString(separator = "；")
        )
        return ConfigRepositoryLatencyRuntimeContext(emptyList(), null)
    }
    val serverAddressStrategy = ConfigRepository.resolveOutboundServerAddressStrategy(
        settings.serverAddressStrategy,
        settings.ipVersionMode
    )
    logOutboundServerAddressStrategy(
        scope = "latency_runtime",
        strategy = settings.serverAddressStrategy,
        ipVersionMode = settings.ipVersionMode,
        resolvedStrategy = serverAddressStrategy
    )
    val defaultResolverOutbounds = ConfigRepository.applyDefaultOutboundDomainResolver(
        detourResolvedOutbounds,
        ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG,
        serverAddressStrategy
    )
    val profileResolverOutbounds = config.dns?.let { profileDns ->
        ConfigRepository.applyDnsOverrideDomainResolvers(defaultResolverOutbounds, profileDns)
    } ?: defaultResolverOutbounds
    val runtimeOutbounds = if (dnsOverrideConfig != null) {
        ConfigRepository.applyDnsOverrideDomainResolvers(profileResolverOutbounds, dnsOverrideConfig)
    } else {
        profileResolverOutbounds
    }
    val directDnsTags = ConfigRepository.resolveDnsOverrideDirectDnsServerTags(runtimeOutbounds, config.dns) +
        ConfigRepository.resolveDnsOverrideDirectDnsServerTags(runtimeOutbounds, dnsOverrideConfig)
    val dnsConfig = SingBoxCore.buildLatencyTestDnsConfig(
        settings = settings,
        outbounds = runtimeOutbounds,
        dnsOverride = dnsOverrideConfig
    ) { server ->
        ConfigRepository.sanitizeInjectedDnsServerForRuntime(
            server = server,
            routingMode = RoutingMode.GLOBAL_DIRECT,
            proxyDetourTag = "direct",
            directDnsServerTags = directDnsTags
        )
    }
    return ConfigRepositoryLatencyRuntimeContext(runtimeOutbounds, dnsConfig)
}

internal fun ConfigRepository.buildNodeTestInfos(nodes: List<NodeUi>, settings: AppSettings): List<ConfigRepositoryNodeTestInfo> {
    return ConfigRepository.buildNodeTestInfosFromContexts(nodes) { profileId ->
        loadConfig(profileId)?.let { config ->
            buildLatencyRuntimeContext(profileId, config, settings)
        }
    }
}

internal suspend fun ConfigRepository.testRegularOutboundsLatency(
    infos: List<ConfigRepositoryNodeTestInfo>,
    results: MutableMap<String, SavedNodeLatency>,
    onNodeComplete: ((nodeId: String, latencyMs: Long) -> Unit)?
) {
    if (infos.isEmpty()) return

    infos.groupBy { it.dnsConfig to it.allOutbounds }.forEach { (runtime, groupedInfos) ->
        val infoByTag = groupedInfos.associateBy { ConfigRepository.buildLatencyProbeTag(it.nodeId) }
        singBoxCore.testOutboundsLatency(
            outbounds = groupedInfos.map { info ->
                info.outbound.copy(tag = ConfigRepository.buildLatencyProbeTag(info.nodeId))
            },
            allOutbounds = runtime.second,
            dnsConfig = runtime.first
        ) { tag, latency ->
            val info = infoByTag[tag] ?: return@testOutboundsLatency
            recordLatencyResult(info, latency, results, onNodeComplete)
        }
    }
}
