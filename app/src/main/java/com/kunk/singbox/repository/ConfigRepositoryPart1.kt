package com.kunk.singbox.repository

import com.kunk.singbox.R
import android.content.Context
import android.os.Build
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.*
import com.kunk.singbox.model.PingResultCode
import com.kunk.singbox.utils.TcpPing
import com.kunk.singbox.database.entity.ProfileEntity
import com.kunk.singbox.database.entity.ActiveStateEntity
import com.kunk.singbox.database.entity.NodeLatencyEntity
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import com.kunk.singbox.utils.NetworkClient

@Suppress("TooManyFunctions")
abstract class ConfigRepositoryPart1(context: Context) : ConfigRepositoryBase(context) {
    protected override fun getEffectiveTunStack(userSelected: TunStack): TunStack {
        val model = Build.MODEL
        if (model.contains("SM-G986U", ignoreCase = true)) {
            Log.w(ConfigRepository.TAG, "Device $model detected, forcing GVISOR stack (ignoring user selection: ${userSelected.name})")
            return TunStack.GVISOR
        }

        return userSelected
    }

    protected override fun getEffectiveTunMtu(settings: AppSettings): Int {
        val configuredMtu = settings.tunMtu
        if (!settings.tunMtuAuto) return configuredMtu

        val caps = getNetworkCapabilities() ?: return configuredMtu

        // Throughput-first for Wi-Fi/Ethernet; conservative for cellular.
        // QUIC-based proxies + QUIC traffic = double encapsulation,
        // requiring higher MTU to avoid fragmentation blackholes.
        val recommendedMtu = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1480
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1480
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1400
            else -> configuredMtu
        }

        // Auto MTU should never be more aggressive than user-configured MTU.
        return minOf(configuredMtu, recommendedMtu)
    }

    @Suppress("DEPRECATION")
    protected override fun getNetworkCapabilities(): NetworkCapabilities? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null

        val physicalCaps = cm.allNetworks
            .asSequence()
            .mapNotNull { cm.getNetworkCapabilities(it) }
            .firstOrNull {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        return physicalCaps ?: cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    }

    protected override fun getClient(): okhttp3.OkHttpClient {
        val settings = cachedSettings ?: AppSettings()
        val timeout = settings.subscriptionUpdateTimeout.toLong()

        return NetworkClient.createClientWithoutRetry(
            connectTimeoutSeconds = timeout,
            readTimeoutSeconds = timeout,
            writeTimeoutSeconds = timeout
        )
    }

    protected override fun getSubscriptionClient(timeoutBudget: SubscriptionAttemptTimeoutBudget): OkHttpClient {
        return NetworkClient.createClientWithoutRetry(
            connectTimeoutSeconds = timeoutBudget.connectTimeoutSeconds,
            readTimeoutSeconds = timeoutBudget.readTimeoutSeconds,
            writeTimeoutSeconds = timeoutBudget.writeTimeoutSeconds,
            callTimeoutSeconds = timeoutBudget.callTimeoutSeconds
        )
    }

    protected override fun getProxyClient(): okhttp3.OkHttpClient? {
        val settings = cachedSettings ?: AppSettings()
        if (!com.kunk.singbox.ipc.VpnStateStore.getActive() || settings.proxyPort <= 0) {
            return null
        }
        val timeout = settings.subscriptionUpdateTimeout.toLong()
        return NetworkClient.createClientWithProxy(
            proxyPort = settings.proxyPort,
            connectTimeoutSeconds = timeout,
            readTimeoutSeconds = timeout,
            writeTimeoutSeconds = timeout
        )
    }

    protected override fun getSubscriptionProxyClient(timeoutBudget: SubscriptionAttemptTimeoutBudget): OkHttpClient? {
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

    protected override fun getRememberedSubscriptionUserAgent(url: String): String? {
        val host = ConfigRepository.extractSubscriptionHost(url) ?: return null
        return subscriptionUaMemoryMmkv.decodeString(host, null)
    }

    protected override fun rememberSuccessfulSubscriptionUserAgent(url: String, userAgent: String) {
        val host = ConfigRepository.extractSubscriptionHost(url) ?: return
        subscriptionUaMemoryMmkv.encode(host, userAgent)
    }

    protected override fun buildSubscriptionUaHealthKey(host: String, userAgent: String, suffix: String): String {
        return "$host|$userAgent|$suffix"
    }

    protected override fun readSubscriptionUaFailureCount(key: String): Int {
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

    protected override fun readSubscriptionUaBlockedUntil(key: String): Long {
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

    protected override fun persistSubscriptionUaFailureCount(key: String, value: Int) {
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

    protected override fun persistSubscriptionUaBlockedUntil(key: String, value: Long) {
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

    protected override fun clearSubscriptionUaHealthKey(key: String, memoryCache: MutableMap<String, *>) {
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

    protected override fun getCircuitBrokenUserAgents(host: String, nowMs: Long): Set<String> {
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

    protected override fun clearSubscriptionUserAgentFailure(host: String, userAgent: String) {
        val failureCountKey = buildSubscriptionUaHealthKey(host, userAgent, "fail_count")
        val blockedUntilKey = buildSubscriptionUaHealthKey(host, userAgent, "blocked_until")
        clearSubscriptionUaHealthKey(failureCountKey, subscriptionUaFailureCountMemory)
        clearSubscriptionUaHealthKey(blockedUntilKey, subscriptionUaBlockedUntilMemory)
    }

    protected override fun recordSubscriptionUserAgentFailure(
        host: String,
        userAgent: String,
        nowMs: Long) {
        val failureCountKey = buildSubscriptionUaHealthKey(host, userAgent, "fail_count")
        val blockedUntilKey = buildSubscriptionUaHealthKey(host, userAgent, "blocked_until")
        val nextFailureCount = readSubscriptionUaFailureCount(failureCountKey) + 1
        persistSubscriptionUaFailureCount(failureCountKey, nextFailureCount)
        if (nextFailureCount >= ConfigRepository.SUBSCRIPTION_FAILURE_THRESHOLD) {
            persistSubscriptionUaBlockedUntil(blockedUntilKey, nowMs + ConfigRepository.SUBSCRIPTION_CIRCUIT_BREAKER_WINDOW_MS)
        }
    }

    protected override fun buildSubscriptionUserAgents(url: String): List<String> {
        val rememberedUserAgent = getRememberedSubscriptionUserAgent(url)
        val host = ConfigRepository.extractSubscriptionHost(url)
            ?: return ConfigRepository.buildSubscriptionAttemptUserAgents(rememberedUserAgent, emptySet())
        val circuitBrokenUserAgents = getCircuitBrokenUserAgents(host)
        return ConfigRepository.buildSubscriptionAttemptUserAgents(rememberedUserAgent, circuitBrokenUserAgents)
    }

    override fun resolveNodeNameFromOutboundTag(tag: String?): String? {
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

    protected override suspend fun awaitInitialProfilesLoaded() {
        initialProfilesLoadJob?.join()
    }

    protected override fun loadProfileNodeMemory() {
        profileNodeMemoryMmkv.allKeys()?.forEach { profileId ->
            val nodeId = profileNodeMemoryMmkv.decodeString(profileId, null)
            if (!nodeId.isNullOrBlank()) {
                profileLastSelectedNode[profileId] = nodeId
            }
        }
    }

    protected override fun saveProfileNodeMemory(profileId: String, nodeId: String) {
        profileLastSelectedNode[profileId] = nodeId
        profileNodeMemoryMmkv.encode(profileId, nodeId)
    }

    protected override fun getProfileLastSelectedNode(profileId: String): String? {
        return profileLastSelectedNode[profileId]
    }

    protected override fun applyActiveProfileNodes(
        profileId: String,
        nodes: List<NodeUi>,
        targetNodeId: String?) {
        _nodes.value = nodes
        val currentActiveId = _activeNodeId.value
        _activeNodeId.value = when {
            targetNodeId != null && nodes.any { it.id == targetNodeId } -> targetNodeId
            currentActiveId != null && nodes.any { it.id == currentActiveId } -> currentActiveId
            else -> {
                val rememberedNodeId = getProfileLastSelectedNode(profileId)
                when {
                    rememberedNodeId != null && nodes.any { it.id == rememberedNodeId } -> rememberedNodeId
                    nodes.isNotEmpty() -> nodes.first().id
                    else -> null
                }
            }
        }
        val selectedName = _activeNodeId.value?.let { activeId ->
            nodes.find { it.id == activeId }?.name
        }
        VpnStateStore.setSelectedNodeLabel(selectedName)
    }

    protected override suspend fun loadProfileNodesWithLatency(profileId: String): List<NodeUi>? {
        val cfg = withContext(Dispatchers.IO) { loadConfig(profileId) } ?: return null
        val nodes = extractNodesFromConfig(cfg, profileId)
        return nodes.map { node ->
            val latency = savedNodeLatencies[node.id]
            if (latency != null) node.copy(latencyMs = latency) else node
        }.also { profileNodes[profileId] = it }
    }

    protected override fun loadConfig(profileId: String): SingBoxConfig? {
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

    protected override fun cacheConfig(profileId: String, config: SingBoxConfig) {
        configCache[profileId] = config
        configCacheAccessTimes[profileId] = System.currentTimeMillis()
    }

    protected override fun removeCachedConfig(profileId: String) {
        configCache.remove(profileId)
        configCacheAccessTimes.remove(profileId)
    }

    protected override fun startConfigCacheCleanup() {
        cacheCleanupScheduler.scheduleWithFixedDelay(
            {
                try {
                    cleanupExpiredCache()
                } catch (e: Exception) {
                    Log.e(ConfigRepository.TAG, "Failed to cleanup expired config cache", e)
                }
            },
            ConfigRepository.CONFIG_CACHE_CLEANUP_INTERVAL_MINUTES,
            ConfigRepository.CONFIG_CACHE_CLEANUP_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        )
    }

    protected override fun cleanupExpiredCache(now: Long) {
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

    protected override fun saveProfiles() {
        saveProfilesJob?.cancel()
        saveProfilesJob = scope.launch {
            delay(saveDebounceMs)
            saveProfilesInternal()
        }
    }

    protected override fun saveProfilesImmediate() {
        saveProfilesJob?.cancel()
        saveProfilesJob = scope.launch {
            saveProfilesInternal()
        }
    }

    protected override suspend fun saveProfilesInternal() {
        saveProfilesMutex.withLock {
            try {
                val startTime = System.currentTimeMillis()
                val profiles = _profiles.value
                val activeProfileId = _activeProfileId.value
                val activeNodeId = _activeNodeId.value
                val latencies = mutableMapOf<String, Long>()
                profileNodes.values.flatten().forEach { node ->
                    node.latencyMs?.let { latencies[node.id] = it }
                }
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
                if (latencies.isNotEmpty()) {
                    val latencyEntities = latencies.map { (nodeId, latency) ->
                        NodeLatencyEntity(nodeId = nodeId, latencyMs = latency)
                    }
                    nodeLatencyDao.insertAll(latencyEntities)
                }

                val elapsed = System.currentTimeMillis() - startTime
                Log.d(ConfigRepository.TAG, "Saved ${profiles.size} profiles to Room in ${elapsed}ms")
            } catch (e: Exception) {
                Log.e(ConfigRepository.TAG, "Failed to save profiles", e)
            }
        }
    }

    protected override fun writeConfigFileOrThrow(profileId: String, config: SingBoxConfig) {
        val configFile = File(configDir, "$profileId.json")
        try {
            ConfigRepository.writeTextFileAtomically(configFile, gson.toJson(config))
        } catch (e: Exception) {
            Log.e(ConfigRepository.TAG, "Failed to write config file for profile: $profileId", e)
            throw IllegalStateException("Failed to write config for profile $profileId", e)
        }
    }

    protected override fun beginProfileUpdateRun(profileId: String): Long {
        val runId = profileUpdateRunCounter.incrementAndGet()
        profileUpdateRuns[profileId] = runId
        return runId
    }

    protected override fun updateProfileForCurrentRun(
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

    protected override fun setProfileUpdateStage(
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

    protected override fun parseDnsOverride(dnsOverride: String?): DnsConfig? {
        return try {
            ConfigRepository.parseDnsOverrideConfig(dnsOverride)
        } catch (e: Exception) {
            Log.w(ConfigRepository.TAG, "Failed to parse dnsOverride JSON, skipping", e)
            null
        }
    }

    protected override suspend fun preResolveDomainsForProfileBestEffort(
        profileId: String,
        config: SingBoxConfig,
        dnsServer: String?
    ): Boolean {
        return runCatching {
            preResolveDomainsForProfile(profileId, config, dnsServer)
            true
        }.onFailure { e ->
            when (e) {
                is java.net.UnknownHostException -> {
                    Log.d(ConfigRepository.TAG, "DNS pre-resolve failed (unknown host) for profile $profileId: ${e.message}")
                }
                is java.net.SocketTimeoutException -> {
                    Log.d(ConfigRepository.TAG, "DNS pre-resolve timed out for profile $profileId")
                }
                is java.io.IOException -> {
                    Log.w(ConfigRepository.TAG, "DNS pre-resolve I/O error for profile $profileId", e)
                }
                else -> {
                    Log.e(ConfigRepository.TAG, "DNS pre-resolve failed for profile $profileId", e)
                }
            }
        }.getOrDefault(false)
    }

    protected override fun rollbackTransientProfileFile(profileId: String) {
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

    protected override fun updateAllNodesAndGroups() {
        if (allNodesUiActiveCount.get() <= 0) {
            _allNodes.value = emptyList()
            return
        }

        val all = profileNodes.values.flatten()
        _allNodes.value = all
    }

    protected override suspend fun loadAllNodesSnapshot(): List<NodeUi> = withContext(Dispatchers.IO) {
        val profiles = _profiles.value
        if (profiles.isEmpty()) return@withContext emptyList()
        profiles.map { p ->
            async {
                val cfg = loadConfig(p.id) ?: return@async emptyList()
                extractNodesFromConfig(cfg, p.id)
            }
        }.awaitAll().flatten()
    }

    override fun setAllNodesUiActive(active: Boolean) {
        if (active) {
            val after = allNodesUiActiveCount.incrementAndGet()
            if (after == 1 && !allNodesLoadedForUi) {
                scope.launch {
                    val profiles = _profiles.value
                    for (p in profiles) {
                        val cfg = loadConfig(p.id) ?: continue
                        val nodes = extractNodesFromConfig(cfg, p.id)
                        val nodesWithLatency = nodes.map { node ->
                            val latency = savedNodeLatencies[node.id]
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

    protected override fun updateLatencyInAllNodes(nodeId: String, latency: Long) {
        val latencyValue = normalizeLatencyValue(latency)
        savedNodeLatencies[nodeId] = latencyValue
        _allNodes.update { list ->
            list.map {
                if (it.id == nodeId) it.copy(latencyMs = latencyValue) else it
            }
        }
        scope.launch {
            try {
                nodeLatencyDao.upsert(nodeId, latencyValue)
            } catch (e: Exception) {
                Log.w(ConfigRepository.TAG, "Failed to persist latency for $nodeId", e)
            }
        }
    }

    protected override suspend fun tcpLatencyFallback(outbound: Outbound): Long {
        if (!LatencyProbePolicy.shouldUseTcpFallback(outbound)) return -1L
        val host = outbound.server?.trim().orEmpty()
        if (host.isBlank()) return -1L
        val port = outbound.serverPort ?: 443
        val timeout = settingsRepository.settings.first().latencyTestTimeout
        return TcpPing.connect(host = host, port = port, timeout = timeout)
    }

    protected override suspend fun ipv6TcpLatencyFallback(outbound: Outbound): Long {
        val host = outbound.server?.trim().orEmpty()
        if (host.isBlank()) return -1L
        val port = outbound.serverPort ?: 443
        val timeout = settingsRepository.settings.first().latencyTestTimeout
        return TcpPing.connect(host = host, port = port, timeout = timeout)
    }

    protected override fun normalizeLatencyValue(latency: Long): Long {
        return when {
            latency > 0L -> latency
            latency == PingResultCode.UNAVAILABLE -> PingResultCode.UNAVAILABLE
            latency == PingResultCode.IPV6_ONLY -> PingResultCode.IPV6_ONLY
            latency == 0L -> PingResultCode.UNAVAILABLE
            else -> PingResultCode.FAILED_TIMEOUT
        }
    }

    protected override fun resolveIpv6OnlyStatus(outbound: Outbound, latency: Long): Long {
        val normalized = normalizeLatencyValue(latency)
        if (normalized != PingResultCode.UNAVAILABLE) return normalized
        if (!isLikelyIpv6OnlyDomain(outbound.server)) return normalized
        return PingResultCode.IPV6_ONLY
    }

    protected override suspend fun prepareOfflineProbeOutbound(outbound: Outbound): Outbound {
        val host = outbound.server?.trim().orEmpty()
        if (host.isBlank() || isIpAddress(host)) return outbound
        return withContext(Dispatchers.IO) {
            val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return@withContext outbound
            val hasV4 = addresses.any { it is Inet4Address }
            val v6 = addresses.firstOrNull { it is Inet6Address } as? Inet6Address ?: return@withContext outbound
            if (hasV4) {
                outbound
            } else {
                val literal = v6.hostAddress?.substringBefore('%')
                if (literal.isNullOrBlank()) outbound else outbound.copy(server = literal)
            }
        }
    }

    @Suppress("ReturnCount")
    protected override fun isLikelyIpv6OnlyDomain(server: String?): Boolean {
        val host = server?.trim().orEmpty()
        if (host.isBlank()) return false
        if (isIpAddress(host)) return false
        return runCatching {
            val addresses = InetAddress.getAllByName(host)
            val hasV6 = addresses.any { it is Inet6Address }
            val hasV4 = addresses.any { it is Inet4Address }
            hasV6 && !hasV4
        }.getOrDefault(false)
    }

    protected override fun applyLatencyResult(
        info: ConfigRepositoryNodeTestInfo,
        latency: Long,
        onNodeComplete: ((nodeId: String, latencyMs: Long) -> Unit)?
    ) {
        val latencyValue = normalizeLatencyValue(latency)

        _nodes.update { list ->
            list.map {
                if (it.id == info.nodeId) it.copy(latencyMs = latencyValue) else it
            }
        }

        profileNodes[info.profileId] = profileNodes[info.profileId]?.map {
            if (it.id == info.nodeId) it.copy(latencyMs = latencyValue) else it
        } ?: emptyList()

        updateLatencyInAllNodes(info.nodeId, latency)
        onNodeComplete?.invoke(info.nodeId, latencyValue)
    }

    protected override fun buildLatencyRuntimeContext(
        profileId: String,
        config: SingBoxConfig,
        settings: AppSettings
    ): ConfigRepositoryLatencyRuntimeContext {
        val rawOutbounds = config.outbounds.orEmpty().mapNotNull { buildOutboundForRuntime(it) }
        val dnsOverrideConfig = parseDnsOverride(_profiles.value.find { it.id == profileId }?.dnsOverride)
        val serverAddressStrategy = resolveDnsStrategy(settings.serverAddressStrategy, settings.ipVersionMode)
        val defaultResolverOutbounds = ConfigRepository.applyDefaultOutboundDomainResolver(
            rawOutbounds,
            "local",
            serverAddressStrategy
        )
        val runtimeOutbounds = if (dnsOverrideConfig != null) {
            ConfigRepository.applyDnsOverrideDomainResolvers(defaultResolverOutbounds, dnsOverrideConfig)
        } else {
            defaultResolverOutbounds
        }
        val directDnsTags = ConfigRepository.resolveDnsOverrideDirectDnsServerTags(runtimeOutbounds, dnsOverrideConfig)
        val dnsConfig = SingBoxCore.buildLatencyTestDnsConfigForRuntime(
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

    protected override fun buildNodeTestInfos(nodes: List<NodeUi>, settings: AppSettings): List<ConfigRepositoryNodeTestInfo> {
        val runtimeContexts = mutableMapOf<String, ConfigRepositoryLatencyRuntimeContext>()
        return nodes.mapNotNull { node ->
            val config = loadConfig(node.sourceProfileId) ?: return@mapNotNull null
            val runtimeContext = runtimeContexts.getOrPut(node.sourceProfileId) {
                buildLatencyRuntimeContext(node.sourceProfileId, config, settings)
            }
            val runtimeOutbound = runtimeContext.outbounds.find { it.tag == node.name } ?: return@mapNotNull null
            ConfigRepositoryNodeTestInfo(runtimeOutbound, node.id, node.sourceProfileId, runtimeContext.dnsConfig)
        }
    }

    @Suppress("CognitiveComplexMethod")
    protected override suspend fun testRegularOutboundsLatency(
        infos: List<ConfigRepositoryNodeTestInfo>,
        concurrency: Int,
        onNodeComplete: ((nodeId: String, latencyMs: Long) -> Unit)?
    ) {
        coroutineScope {
            if (infos.isEmpty()) return@coroutineScope

            val infosByDnsConfig = infos.groupBy { it.dnsConfig }
            val initialResults = ConcurrentHashMap<String, Long>()

            infosByDnsConfig.forEach { (dnsConfig, groupedInfos) ->
                val preparedInfoPairs = groupedInfos.map { info ->
                    info to prepareOfflineProbeOutbound(info.outbound)
                }
                val infoByTag = preparedInfoPairs.associate { (info, outbound) -> outbound.tag to Pair(info, outbound) }

                singBoxCore.testOutboundsLatency(preparedInfoPairs.map { it.second }, dnsConfig) { tag, latency ->
                    initialResults[tag] = latency
                    if (latency > 0L) {
                        val pair = infoByTag[tag] ?: return@testOutboundsLatency
                        applyLatencyResult(pair.first, latency, onNodeComplete)
                    }
                }
            }

            val fallbackSemaphore = Semaphore(concurrency)
            infos.map { info ->
                async {
                    fallbackSemaphore.withPermit {
                        val probeOutbound = prepareOfflineProbeOutbound(info.outbound)
                        val latency = initialResults[probeOutbound.tag] ?: -1L
                        if (latency > 0L) return@withPermit

                        val finalLatency = if (latency > 0L) {
                            latency
                        } else {
                            val fallback = ipv6TcpLatencyFallback(probeOutbound)
                            if (fallback > 0L) fallback else resolveIpv6OnlyStatus(probeOutbound, latency)
                        }
                        applyLatencyResult(info, finalLatency, onNodeComplete)
                    }
                }
            }.awaitAll()
        }
    }
}
