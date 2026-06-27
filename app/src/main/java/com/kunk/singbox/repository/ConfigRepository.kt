package com.kunk.singbox.repository

import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.kunk.singbox.R
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.database.AppDatabase
import com.kunk.singbox.database.entity.ActiveStateEntity
import com.kunk.singbox.database.entity.NodeLatencyEntity
import com.kunk.singbox.database.entity.ProfileEntity
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.*
import com.kunk.singbox.model.PingResultCode
import com.kunk.singbox.repository.config.InboundBuilder
import com.kunk.singbox.repository.config.NodeLinkExporter
import com.kunk.singbox.repository.config.OutboundFixer
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.utils.NetworkClient
import com.kunk.singbox.utils.TcpPing
import com.kunk.singbox.utils.dns.DnsResolveStore
import com.kunk.singbox.utils.dns.DnsResolver
import com.kunk.singbox.utils.parser.Base64Parser
import com.kunk.singbox.utils.parser.NodeLinkParser
import com.kunk.singbox.utils.parser.SingBoxParser
import com.kunk.singbox.utils.parser.SubscriptionManager
import com.tencent.mmkv.MMKV
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody

data class SubscriptionAttemptTimeoutBudget(
    val connectTimeoutSeconds: Long,
    val readTimeoutSeconds: Long,
    val writeTimeoutSeconds: Long,
    val callTimeoutSeconds: Long
)
@Suppress("TooManyFunctions", "LargeClass", "ProtectedMemberInFinalClass")
class ConfigRepository(protected val context: Context) {
    sealed class OutboundSemantic {
        object Direct : OutboundSemantic()
        object Block : OutboundSemantic()
        object Proxy : OutboundSemantic()
        data class RouteTag(val tag: String) : OutboundSemantic()
        data class FallbackProxy(val tag: String) : OutboundSemantic()
    }

    enum class RuleSetRuleType {
        IP,
        DOMAIN,
        MIXED,
        UNKNOWN
    }

    @Suppress("TooManyFunctions", "LargeClass")
    data class SubscriptionUserInfo(
        val upload: Long = 0,
        val download: Long = 0,
        val total: Long = 0,
        val expire: Long = 0
    )

    sealed class NodeSwitchResult {
        object Success : NodeSwitchResult()
        object NotRunning : NodeSwitchResult()
        data class Failed(val reason: String) : NodeSwitchResult()
    }

    data class ConfigGenerationResult(
        val path: String,
        val activeNodeTag: String?,
        val outboundTags: Set<String>
    )

    internal data class OutboundSemanticTestInput(
        val mode: RuleSetOutboundMode?,
        val value: String?,
        val selectorTag: String,
        val outbounds: List<Outbound>,
        val profiles: List<ProfileEntity>,
        val nodeTagResolver: (String?) -> String?
    )

    protected val gson = Gson()

    protected val singBoxCore = SingBoxCore.getInstance(context)

    protected val settingsRepository = SettingsRepository.getInstance(context)

    protected val database = AppDatabase.getInstance(context)

    protected val profileDao = database.profileDao()

    protected val activeStateDao = database.activeStateDao()

    protected val nodeLatencyDao = database.nodeLatencyDao()

    @Volatile
    protected var cachedSettings: AppSettings? = null

    protected val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    protected val nodeLinkParser = NodeLinkParser(gson)

    protected val subscriptionManager = SubscriptionManager(listOf(
        SingBoxParser(gson),
        com.kunk.singbox.utils.parser.ClashYamlParser(),
        Base64Parser { nodeLinkParser.parse(it) }
    ))

    protected val dnsResolver = DnsResolver()

    protected val dnsResolveStore = DnsResolveStore.getInstance()

    protected val _profiles = MutableStateFlow<List<ProfileUi>>(emptyList())

    val profiles: StateFlow<List<ProfileUi>> = _profiles.asStateFlow()

    protected val _nodes = MutableStateFlow<List<NodeUi>>(emptyList())

    val nodes: StateFlow<List<NodeUi>> = _nodes.asStateFlow()

    protected val _allNodes = MutableStateFlow<List<NodeUi>>(emptyList())

    val allNodes: StateFlow<List<NodeUi>> = _allNodes.asStateFlow()

    protected val _activeProfileId = MutableStateFlow<String?>(null)

    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    protected val _activeNodeId = MutableStateFlow<String?>(null)

    val activeNodeId: StateFlow<String?> = _activeNodeId.asStateFlow()

    protected val maxConfigCacheSize = 10

    protected val configCache: MutableMap<String, SingBoxConfig> = Collections.synchronizedMap(
        object : LinkedHashMap<String, SingBoxConfig>(maxConfigCacheSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SingBoxConfig>?): Boolean {
                return size > maxConfigCacheSize
            }
        }
    )

    protected val configCacheAccessTimes = ConcurrentHashMap<String, Long>()

    protected val cacheCleanupScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "config-cache-cleanup").apply {
                isDaemon = true
            }
        }

    protected val profileNodes = ConcurrentHashMap<String, List<NodeUi>>()

    protected val profileResetJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    protected val profileUpdateRuns = ConcurrentHashMap<String, Long>()

    protected val inFlightLatencyTests = ConcurrentHashMap<String, Deferred<Long>>()

    protected val savedNodeLatencies = ConcurrentHashMap<String, Long>()
    @Volatile protected var saveProfilesJob: kotlinx.coroutines.Job? = null
    @Volatile protected var initialProfilesLoadJob: kotlinx.coroutines.Job? = null

    protected val saveDebounceMs = 300L

    protected val saveProfilesMutex = Mutex()

    protected val profileUpdateRunCounter = AtomicLong(0L)

    protected val allNodesUiActiveCount = AtomicInteger(0)
    @Volatile protected var allNodesLoadedForUi: Boolean = false

    @Volatile protected var lastTagToNodeName: Map<String, String> = emptyMap()
    @Volatile protected var lastRunOutboundTags: Set<String>? = null
    @Volatile protected var lastRunProfileId: String? = null

    private val nodeSwitchGate = NodeSwitchGate()

    protected val profileLastSelectedNode = ConcurrentHashMap<String, String>()

    protected val profileNodeMemoryMmkv: MMKV by lazy {
        MMKV.mmkvWithID("profile_node_memory", MMKV.SINGLE_PROCESS_MODE)
    }

    protected val subscriptionUaMemoryMmkv: MMKV by lazy {
        MMKV.mmkvWithID("subscription_ua_memory", MMKV.SINGLE_PROCESS_MODE)
    }

    protected val subscriptionUaFailureCountMemory = ConcurrentHashMap<String, Int>()

    protected val subscriptionUaBlockedUntilMemory = ConcurrentHashMap<String, Long>()

    protected val subscriptionUaHealthMmkv: MMKV by lazy {
        MMKV.mmkvWithID("subscription_ua_health", MMKV.SINGLE_PROCESS_MODE)
    }

    protected val configDir: File
        get() = File(context.filesDir, "configs").also { it.mkdirs() }

    protected val profilesFileJson: File
        get() = File(context.filesDir, "profiles.json")

    init {
        startConfigCacheCleanup()
        initialProfilesLoadJob = scope.launch {
            loadProfileNodeMemory()
            loadSavedProfiles()
        }
        scope.launch {
            settingsRepository.settings.collect { settings ->
                cachedSettings = settings
            }
        }
    }

    protected val clashYamlParser = com.kunk.singbox.utils.parser.ClashYamlParser()

    protected fun getEffectiveTunStack(userSelected: TunStack): TunStack {
        val model = Build.MODEL
        if (model.contains("SM-G986U", ignoreCase = true)) {
            Log.w(ConfigRepository.TAG, "Device $model detected, forcing GVISOR stack (ignoring user selection: ${userSelected.name})")
            return TunStack.GVISOR
        }

        return userSelected
    }

    protected fun getEffectiveTunMtu(settings: AppSettings): Int {
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
    protected fun getNetworkCapabilities(): NetworkCapabilities? {
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

    protected fun getClient(): okhttp3.OkHttpClient {
        val settings = cachedSettings ?: AppSettings()
        val timeout = settings.subscriptionUpdateTimeout.toLong()

        return NetworkClient.createClientWithoutRetry(
            connectTimeoutSeconds = timeout,
            readTimeoutSeconds = timeout,
            writeTimeoutSeconds = timeout
        )
    }

    protected fun getSubscriptionClient(timeoutBudget: SubscriptionAttemptTimeoutBudget): OkHttpClient {
        return NetworkClient.createClientWithoutRetry(
            connectTimeoutSeconds = timeoutBudget.connectTimeoutSeconds,
            readTimeoutSeconds = timeoutBudget.readTimeoutSeconds,
            writeTimeoutSeconds = timeoutBudget.writeTimeoutSeconds,
            callTimeoutSeconds = timeoutBudget.callTimeoutSeconds
        )
    }

    protected fun getProxyClient(): okhttp3.OkHttpClient? {
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

    protected fun getSubscriptionProxyClient(timeoutBudget: SubscriptionAttemptTimeoutBudget): OkHttpClient? {
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

    protected fun getRememberedSubscriptionUserAgent(url: String): String? {
        val host = ConfigRepository.extractSubscriptionHost(url) ?: return null
        return subscriptionUaMemoryMmkv.decodeString(host, null)
    }

    protected fun rememberSuccessfulSubscriptionUserAgent(url: String, userAgent: String) {
        val host = ConfigRepository.extractSubscriptionHost(url) ?: return
        subscriptionUaMemoryMmkv.encode(host, userAgent)
    }

    protected fun buildSubscriptionUaHealthKey(host: String, userAgent: String, suffix: String): String {
        return "$host|$userAgent|$suffix"
    }

    protected fun readSubscriptionUaFailureCount(key: String): Int {
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

    protected fun readSubscriptionUaBlockedUntil(key: String): Long {
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

    protected fun persistSubscriptionUaFailureCount(key: String, value: Int) {
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

    protected fun persistSubscriptionUaBlockedUntil(key: String, value: Long) {
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

    protected fun clearSubscriptionUaHealthKey(key: String, memoryCache: MutableMap<String, *>) {
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

    protected fun getCircuitBrokenUserAgents(host: String, nowMs: Long = System.currentTimeMillis()): Set<String> {
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

    protected fun clearSubscriptionUserAgentFailure(host: String, userAgent: String) {
        val failureCountKey = buildSubscriptionUaHealthKey(host, userAgent, "fail_count")
        val blockedUntilKey = buildSubscriptionUaHealthKey(host, userAgent, "blocked_until")
        clearSubscriptionUaHealthKey(failureCountKey, subscriptionUaFailureCountMemory)
        clearSubscriptionUaHealthKey(blockedUntilKey, subscriptionUaBlockedUntilMemory)
    }

    protected fun recordSubscriptionUserAgentFailure(
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

    protected fun buildSubscriptionUserAgents(url: String): List<String> {
        val rememberedUserAgent = getRememberedSubscriptionUserAgent(url)
        val host = ConfigRepository.extractSubscriptionHost(url)
            ?: return ConfigRepository.buildSubscriptionAttemptUserAgents(rememberedUserAgent, emptySet())
        val circuitBrokenUserAgents = getCircuitBrokenUserAgents(host)
        return ConfigRepository.buildSubscriptionAttemptUserAgents(rememberedUserAgent, circuitBrokenUserAgents)
    }

    fun resolveNodeNameFromOutboundTag(tag: String?): String? {
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

    protected suspend fun awaitInitialProfilesLoaded() {
        initialProfilesLoadJob?.join()
    }

    protected fun loadProfileNodeMemory() {
        profileNodeMemoryMmkv.allKeys()?.forEach { profileId ->
            val nodeId = profileNodeMemoryMmkv.decodeString(profileId, null)
            if (!nodeId.isNullOrBlank()) {
                profileLastSelectedNode[profileId] = nodeId
            }
        }
    }

    protected fun saveProfileNodeMemory(profileId: String, nodeId: String) {
        profileLastSelectedNode[profileId] = nodeId
        profileNodeMemoryMmkv.encode(profileId, nodeId)
    }

    protected fun getProfileLastSelectedNode(profileId: String): String? {
        return profileLastSelectedNode[profileId]
    }

    protected fun applyActiveProfileNodes(
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

    protected suspend fun loadProfileNodesWithLatency(profileId: String): List<NodeUi>? {
        val cfg = withContext(Dispatchers.IO) { loadConfig(profileId) } ?: return null
        val nodes = extractNodesFromConfig(cfg, profileId)
        return nodes.map { node ->
            val latency = savedNodeLatencies[node.id]
            if (latency != null) node.copy(latencyMs = latency) else node
        }.also { profileNodes[profileId] = it }
    }

    protected fun loadConfig(profileId: String): SingBoxConfig? {
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

    protected fun cacheConfig(profileId: String, config: SingBoxConfig) {
        configCache[profileId] = config
        configCacheAccessTimes[profileId] = System.currentTimeMillis()
    }

    protected fun removeCachedConfig(profileId: String) {
        configCache.remove(profileId)
        configCacheAccessTimes.remove(profileId)
    }

    protected fun startConfigCacheCleanup() {
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

    protected fun cleanupExpiredCache(now: Long = System.currentTimeMillis()) {
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

    protected fun saveProfiles() {
        saveProfilesJob?.cancel()
        saveProfilesJob = scope.launch {
            delay(saveDebounceMs)
            saveProfilesInternal()
        }
    }

    protected fun saveProfilesImmediate() {
        saveProfilesJob?.cancel()
        saveProfilesJob = scope.launch {
            saveProfilesInternal()
        }
    }

    protected suspend fun saveProfilesInternal() {
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

    protected fun writeConfigFileOrThrow(profileId: String, config: SingBoxConfig) {
        val configFile = File(configDir, "$profileId.json")
        try {
            ConfigRepository.writeTextFileAtomically(configFile, gson.toJson(config))
        } catch (e: Exception) {
            Log.e(ConfigRepository.TAG, "Failed to write config file for profile: $profileId", e)
            throw IllegalStateException("Failed to write config for profile $profileId", e)
        }
    }

    protected fun beginProfileUpdateRun(profileId: String): Long {
        val runId = profileUpdateRunCounter.incrementAndGet()
        profileUpdateRuns[profileId] = runId
        return runId
    }

    protected fun updateProfileForCurrentRun(
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

    protected fun setProfileUpdateStage(
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

    protected fun parseDnsOverride(dnsOverride: String?): DnsConfig? {
        return try {
            ConfigRepository.parseDnsOverrideConfig(dnsOverride)
        } catch (e: Exception) {
            Log.w(ConfigRepository.TAG, "Failed to parse dnsOverride JSON, skipping", e)
            null
        }
    }

    protected suspend fun preResolveDomainsForProfileBestEffort(
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

    protected fun rollbackTransientProfileFile(profileId: String) {
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

    protected fun updateAllNodesAndGroups() {
        if (allNodesUiActiveCount.get() <= 0) {
            _allNodes.value = emptyList()
            return
        }

        val all = profileNodes.values.flatten()
        _allNodes.value = all
    }

    protected suspend fun loadAllNodesSnapshot(): List<NodeUi> = withContext(Dispatchers.IO) {
        val profiles = _profiles.value
        if (profiles.isEmpty()) return@withContext emptyList()
        profiles.map { p ->
            async {
                val cfg = loadConfig(p.id) ?: return@async emptyList()
                extractNodesFromConfig(cfg, p.id)
            }
        }.awaitAll().flatten()
    }

    fun setAllNodesUiActive(active: Boolean) {
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

    protected fun updateLatencyInAllNodes(nodeId: String, latency: Long) {
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

    protected suspend fun tcpLatencyFallback(outbound: Outbound): Long {
        if (!LatencyProbePolicy.shouldUseTcpFallbackAfterProtocolFailure(outbound)) return -1L
        val host = outbound.server?.trim().orEmpty()
        if (host.isBlank()) return -1L
        val port = outbound.serverPort ?: 443
        val timeout = settingsRepository.settings.first().latencyTestTimeout
        return TcpPing.connect(host = host, port = port, timeout = timeout)
    }

    protected fun normalizeLatencyValue(latency: Long): Long {
        return when {
            latency > 0L -> latency
            latency == PingResultCode.UNAVAILABLE -> PingResultCode.UNAVAILABLE
            latency == PingResultCode.IPV6_ONLY -> PingResultCode.IPV6_ONLY
            latency == 0L -> PingResultCode.UNAVAILABLE
            else -> PingResultCode.FAILED_TIMEOUT
        }
    }

    protected fun resolveIpv6OnlyStatus(outbound: Outbound, latency: Long): Long {
        val normalized = normalizeLatencyValue(latency)
        if (normalized != PingResultCode.UNAVAILABLE) return normalized
        if (!isLikelyIpv6OnlyDomain(outbound.server)) return normalized
        return PingResultCode.IPV6_ONLY
    }

    protected suspend fun prepareOfflineProbeOutbound(outbound: Outbound): Outbound {
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
    protected fun isLikelyIpv6OnlyDomain(server: String?): Boolean {
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

    protected fun applyLatencyResult(
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

    protected fun buildLatencyRuntimeContext(
        profileId: String,
        config: SingBoxConfig,
        settings: AppSettings
    ): ConfigRepositoryLatencyRuntimeContext {
        val rawOutbounds = config.outbounds.orEmpty().mapNotNull { buildOutboundForRuntime(it) }
        val dnsOverrideConfig = parseDnsOverride(_profiles.value.find { it.id == profileId }?.dnsOverride)
        val serverAddressStrategy = resolveDnsStrategy(settings.serverAddressStrategy, settings.ipVersionMode)
        val defaultResolverOutbounds = ConfigRepository.applyDefaultOutboundDomainResolver(
            rawOutbounds,
            ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG,
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

    protected fun buildNodeTestInfos(nodes: List<NodeUi>, settings: AppSettings): List<ConfigRepositoryNodeTestInfo> {
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
    protected suspend fun testRegularOutboundsLatency(
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
                            val fallback = tcpLatencyFallback(probeOutbound)
                            if (fallback > 0L) fallback else resolveIpv6OnlyStatus(probeOutbound, latency)
                        }
                        applyLatencyResult(info, finalLatency, onNodeComplete)
                    }
                }
            }.awaitAll()
        }
    }

    protected suspend fun testTcpFallbackOutboundsLatency(
        infos: List<ConfigRepositoryNodeTestInfo>,
        concurrency: Int,
        onNodeComplete: ((nodeId: String, latencyMs: Long) -> Unit)?
    ) {
        coroutineScope {
            if (infos.isEmpty()) return@coroutineScope

            val semaphore = Semaphore(concurrency)
            infos.map { info ->
                async {
                    semaphore.withPermit {
                        val latency = tcpLatencyFallback(info.outbound)
                        applyLatencyResult(info, latency, onNodeComplete)
                    }
                }
            }.awaitAll()
        }
    }

    fun reloadProfiles() {
        scope.launch {
            loadSavedProfiles()
        }
    }

    protected suspend fun loadSavedProfiles() {
        try {
            val startTime = System.currentTimeMillis()
            val profileEntities = profileDao.getAll()
            val activeState = activeStateDao.get()
            val latencyEntities = nodeLatencyDao.getAll()

            if (profileEntities.isNotEmpty()) {
                val profiles = profileEntities.map { it.toUiModel().copy(updateStatus = UpdateStatus.Idle) }
                _profiles.value = profiles
                _activeProfileId.value = activeState?.activeProfileId
                savedNodeLatencies.clear()
                latencyEntities.forEach { savedNodeLatencies[it.nodeId] = it.latencyMs }

                val elapsed = System.currentTimeMillis() - startTime
                Log.i(ConfigRepository.TAG, "Loaded ${profiles.size} profiles from Room in ${elapsed}ms")
                loadActiveProfileNodes(activeState?.activeProfileId, activeState?.activeNodeId)
                cleanupLegacyProfileFiles()
                return
            }
            val savedData: SavedProfilesData? = if (profilesFileJson.exists()) {
                Log.i(ConfigRepository.TAG, "Migrating profiles from JSON to Room...")
                val json = profilesFileJson.readText()
                gson.fromJson<SavedProfilesData>(json, ConfigRepository.TYPE_SAVED_PROFILES_DATA)
            } else {
                null
            }

            if (savedData != null) {
                val profiles = savedData.profiles.map { it.copy(updateStatus = UpdateStatus.Idle) }
                _profiles.value = profiles
                _activeProfileId.value = savedData.activeProfileId

                savedNodeLatencies.clear()
                savedNodeLatencies.putAll(savedData.nodeLatencies)
                val entities = profiles.mapIndexed { index, profile ->
                    ProfileEntity.fromUiModel(profile, sortOrder = index)
                }
                profileDao.insertAll(entities)
                if (savedData.activeProfileId != null || savedData.activeNodeId != null) {
                    activeStateDao.save(ActiveStateEntity(
                        id = 1,
                        activeProfileId = savedData.activeProfileId,
                        activeNodeId = savedData.activeNodeId
                    ))
                }
                val latencies = savedData.nodeLatencies.map { (nodeId, latency) ->
                    NodeLatencyEntity(nodeId = nodeId, latencyMs = latency)
                }
                if (latencies.isNotEmpty()) {
                    scope.launch { nodeLatencyDao.insertAll(latencies) }
                }

                val elapsed = System.currentTimeMillis() - startTime
                Log.i(ConfigRepository.TAG, "Migrated ${profiles.size} profiles to Room in ${elapsed}ms")
                loadActiveProfileNodes(savedData.activeProfileId, savedData.activeNodeId)
                cleanupLegacyProfileFiles()
            }
        } catch (e: Exception) {
            Log.e(ConfigRepository.TAG, "Failed to load saved profiles", e)
        }
    }

    protected fun loadActiveProfileNodes(activeProfileId: String?, activeNodeId: String?) {
        if (activeProfileId == null) return
        val configFile = File(configDir, "$activeProfileId.json")
        if (!configFile.exists()) return

        scope.launch {
            try {
                val configJson = configFile.readText()
                val config = gson.fromJson(configJson, SingBoxConfig::class.java)
                val nodes = extractNodesFromConfig(config, activeProfileId)
                val nodesWithLatency = nodes.map { node ->
                    val latency = savedNodeLatencies[node.id]
                    if (latency != null) node.copy(latencyMs = latency) else node
                }
                profileNodes[activeProfileId] = nodesWithLatency
                cacheConfig(activeProfileId, config)
                if (activeProfileId == _activeProfileId.value) {
                    applyActiveProfileNodes(activeProfileId, nodesWithLatency, activeNodeId)
                }
                if (allNodesUiActiveCount.get() > 0) {
                    updateAllNodesAndGroups()
                }
            } catch (e: Exception) {
                Log.e(ConfigRepository.TAG, "Failed to load config for profile: $activeProfileId", e)
            }
        }
    }

    protected fun cleanupLegacyProfileFiles() {
        scope.launch {
            try {
                if (profilesFileJson.exists()) {
                    profilesFileJson.delete()
                    Log.i(ConfigRepository.TAG, "Deleted legacy JSON profiles file")
                }
            } catch (e: Exception) {
                Log.w(ConfigRepository.TAG, "Failed to cleanup legacy profile files", e)
            }
        }
    }

    protected fun parseTrafficString(value: String): Long {
        val trimmed = value.trim().uppercase()
        val match = ConfigRepository.REGEX_TRAFFIC.find(trimmed) ?: return 0L

        val (numStr, unit) = match.destructured
        val num = numStr.toDoubleOrNull() ?: return 0L

        val multiplier = when (unit) {
            "K" -> 1024L
            "M" -> 1024L * 1024
            "G" -> 1024L * 1024 * 1024
            "T" -> 1024L * 1024 * 1024 * 1024
            "P" -> 1024L * 1024 * 1024 * 1024 * 1024
            else -> 1L
        }

        return (num * multiplier).toLong()
    }

    protected fun parseDateString(value: String): Long {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            (sdf.parse(value.trim())?.time ?: 0L) / 1000 // Convert to seconds
        } catch (e: Exception) {
            0L
        }
    }

    protected fun parseExpireValue(raw: String): Long {
        val normalized = raw.trim().trim('"', '\'')
        if (normalized.isBlank()) return 0L
        val lower = normalized.lowercase()
        if (lower.contains("never") || lower.contains("permanent") || lower.contains("forever") || lower.contains("unlimited")) {
            return -1L
        }
        return if (normalized.contains("-")) {
            parseDateString(normalized)
        } else {
            normalized.toLongOrNull() ?: 0L
        }
    }

    protected fun parseSubscriptionUserInfo(header: String?, bodyDecoded: String?): ConfigRepository.SubscriptionUserInfo? {
        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L
        var found = false
        var totalSpecified = false

        fun isUnlimitedValue(raw: String): Boolean {
            val normalized = raw.trim().lowercase()
            return normalized == "unlimited" || normalized == "infinite" || normalized == "infinity" || normalized == "inf" || normalized == "INF"
        }

        fun parseTrafficValue(raw: String): Long {
            val normalized = raw.trim().trim('"', '\'')
            return normalized.toLongOrNull() ?: parseTrafficString(normalized)
        }

        fun applyKeyValue(key: String, rawValue: String) {
            when (key.lowercase()) {
                "upload" -> {
                    upload = parseTrafficValue(rawValue)
                    found = true
                }
                "download" -> {
                    download = parseTrafficValue(rawValue)
                    found = true
                }
                "total" -> {
                    totalSpecified = true
                    total = if (isUnlimitedValue(rawValue)) -1L else parseTrafficValue(rawValue)
                    found = true
                }
                "expire" -> {
                    expire = parseExpireValue(rawValue)
                    found = true
                }
            }
        }

        fun parseKeyValuePairs(text: String) {
            ConfigRepository.REGEX_KV_PAIRS.findAll(text).forEach { match ->
                applyKeyValue(match.groupValues[1], match.groupValues[2])
            }
        }

        fun parseHeaderLike(text: String) {
            text.split(",", ";").forEach { part ->
                val kv = part.trim().split("=", ":", limit = 2)
                if (kv.size == 2) {
                    applyKeyValue(kv[0].trim(), kv[1].trim())
                }
            }
        }
        if (!header.isNullOrBlank()) {
            try {
                parseHeaderLike(header)
            } catch (e: Exception) {
                Log.w(ConfigRepository.TAG, "Failed to parse Subscription-Userinfo header: $header", e)
            }
        }
        if (bodyDecoded != null && (!found || total == 0L)) {
            try {
                val userInfoIndex = bodyDecoded.indexOf("subscription-userinfo", ignoreCase = true)
                val userInfoAltIndex = if (userInfoIndex >= 0) userInfoIndex else bodyDecoded.indexOf("subscription_userinfo", ignoreCase = true)
                if (userInfoAltIndex >= 0) {
                    val endIndex = (userInfoAltIndex + 800).coerceAtMost(bodyDecoded.length)
                    val snippet = bodyDecoded.substring(userInfoAltIndex, endIndex)
                    val inlineMatch = ConfigRepository.REGEX_SUBSCRIPTION_USERINFO.find(snippet)
                    if (inlineMatch != null) {
                        parseHeaderLike(inlineMatch.groupValues[1])
                    }
                    parseKeyValuePairs(snippet)
                }

                val firstLine = bodyDecoded.lines().firstOrNull()?.trim()
                if (firstLine != null && (firstLine.startsWith("STATUS=") || firstLine.contains("TOT:") || firstLine.contains("Expires:"))) {
                    val totalMatch = ConfigRepository.REGEX_TOTAL.find(firstLine)
                    if (totalMatch != null) {
                        totalSpecified = true
                        total = parseTrafficString(totalMatch.groupValues[1])
                        found = true
                    }
                    val expireMatch = ConfigRepository.REGEX_EXPIRE_DATE.find(firstLine)
                    if (expireMatch != null) {
                        expire = parseDateString(expireMatch.groupValues[1])
                        found = true
                    }
                    var usedAccumulator = 0L
                    val parts = firstLine.substringAfter("STATUS=").split(",")
                    parts.forEach { part ->
                        if (part.contains("TOT:")) return@forEach
                        if (part.contains("Expires:")) return@forEach
                        val match = ConfigRepository.REGEX_TRAFFIC_VALUE.find(part)
                        if (match != null) {
                            usedAccumulator += parseTrafficString(match.groupValues[1])
                            found = true
                        }
                    }

                    if (usedAccumulator > 0) {
                        download = usedAccumulator
                        upload = 0
                    }
                }
            } catch (e: Exception) {
                Log.w(ConfigRepository.TAG, "Failed to parse info from body: ${bodyDecoded.take(100)}", e)
            }
        }

        if (!found) return null
        if (totalSpecified && total <= 0L) {
            total = -1L
        }
        return ConfigRepository.SubscriptionUserInfo(upload, download, total, expire)
    }

    protected fun parseUserInfoFromOutbounds(outbounds: List<Outbound>?): ConfigRepository.SubscriptionUserInfo? {
        if (outbounds.isNullOrEmpty()) return null
        var remainingBytes: Long? = null
        var expireValue: Long? = null

        outbounds.forEach { outbound ->
            val tag = outbound.tag
            if (remainingBytes == null) {
                val match = ConfigRepository.REGEX_REMAINING.find(tag)
                if (match != null) {
                    remainingBytes = parseTrafficString(match.groupValues[2])
                }
            }
            if (expireValue == null) {
                val match = ConfigRepository.REGEX_EXPIRE.find(tag)
                if (match != null) {
                    expireValue = parseExpireValue(match.groupValues[2])
                }
            }
        }

        if (remainingBytes == null && expireValue == null) return null
        return ConfigRepository.SubscriptionUserInfo(
            upload = 0,
            download = remainingBytes ?: 0,
            total = if (remainingBytes != null) -2L else 0L,
            expire = expireValue ?: 0L
        )
    }

    protected fun mergeUserInfo(primary: ConfigRepository.SubscriptionUserInfo?, fallback: ConfigRepository.SubscriptionUserInfo?): ConfigRepository.SubscriptionUserInfo? {
        if (primary == null) return fallback
        if (fallback == null) return primary
        return ConfigRepository.SubscriptionUserInfo(
            upload = if (primary.upload > 0) primary.upload else fallback.upload,
            download = if (primary.download > 0) primary.download else fallback.download,
            total = if (primary.total != 0L) primary.total else fallback.total,
            expire = if (primary.expire != 0L) primary.expire else fallback.expire
        )
    }

    protected fun logHtmlSubscriptionPage(userAgent: String, responseBody: String) {
        val extractedUrl = ConfigRepository.extractSubscriptionUrlFromHtml(responseBody)
        if (!extractedUrl.isNullOrBlank()) {
            Log.i(
                ConfigRepository.TAG,
                "Subscription endpoint returned HTML info page with UA '$userAgent', " +
                    "embedded subscription URL: $extractedUrl"
            )
        } else {
            Log.w(ConfigRepository.TAG, "Subscription endpoint returned HTML info page with UA '$userAgent'")
        }
    }

    protected fun parseSubscriptionResponse(
        userAgent: String,
        contentType: String?,
        responseBody: String,
        subscriptionUserInfoHeader: String?
    ): ConfigRepositorySubscriptionAttemptResult {
        val isHtmlInfoPage = ConfigRepository.looksLikeHtmlSubscriptionPage(contentType, responseBody)
        if (ConfigRepository.shouldStopSubscriptionFallback(looksLikeHtmlInfoPage = isHtmlInfoPage)) {
            logHtmlSubscriptionPage(userAgent, responseBody)
            return ConfigRepositorySubscriptionAttemptResult(shouldStopFallback = true)
        }

        val config = subscriptionManager.parse(responseBody)
        if (config == null || config.outbounds.isNullOrEmpty()) {
            Log.w(ConfigRepository.TAG, "Failed to parse subscription response with UA '$userAgent'")
            return ConfigRepositorySubscriptionAttemptResult()
        }

        val headerUserInfo = parseSubscriptionUserInfo(subscriptionUserInfoHeader, responseBody)
        val outboundUserInfo = parseUserInfoFromOutbounds(config.outbounds)
        val userInfo = mergeUserInfo(headerUserInfo, outboundUserInfo)
        return ConfigRepositorySubscriptionAttemptResult(fetchResult = ConfigRepositoryFetchResult(config, userInfo))
    }

    protected fun buildSubscriptionRequest(url: String, userAgent: String): Request {
        return Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/yaml,text/yaml,text/plain,application/json,*/*")
            .build()
    }

    protected fun logSubscriptionAttempt(
        level: Int,
        message: String,
        context: ConfigRepositorySubscriptionAttemptContext,
        costMs: Long,
        extra: String? = null) {
        val logMessage = buildString {
            append(message)
            append(": host=")
            append(context.host)
            append(", ua='")
            append(context.userAgent)
            append("', cached=")
            append(context.isRemembered)
            append(", cost=")
            append(costMs)
            append("ms")
            if (!extra.isNullOrBlank()) {
                append(", ")
                append(extra)
            }
        }
        when (level) {
            Log.INFO -> Log.i(ConfigRepository.TAG, logMessage)
            Log.WARN -> Log.w(ConfigRepository.TAG, logMessage)
            else -> Log.d(ConfigRepository.TAG, logMessage)
        }
    }

    protected fun logSubscriptionParseResult(
        attemptResult: ConfigRepositorySubscriptionAttemptResult,
        contentType: String?,
        context: ConfigRepositorySubscriptionAttemptContext,
        costMs: Long
    ) {
        val message = when {
            attemptResult.fetchResult != null -> "Subscription request succeeded"
            attemptResult.shouldStopFallback -> "Subscription response stopped further fallback"
            else -> "Subscription response unusable"
        }
        val level = if (attemptResult.fetchResult != null) Log.INFO else Log.WARN
        logSubscriptionAttempt(
            level = level,
            message = message,
            context = context,
            costMs = costMs,
            extra = "contentType='$contentType'"
        )
    }

    protected fun logSubscriptionFallbackStopped(
        context: ConfigRepositorySubscriptionAttemptContext,
        costMs: Long,
        reason: String
    ) {
        logSubscriptionAttempt(
            level = Log.WARN,
            message = "Stopping remaining User-Agent fallbacks",
            context = context,
            costMs = costMs,
            extra = reason
        )
    }

    private fun handleUnsuccessfulSubscriptionResponse(
        responseCode: Int,
        responseMessage: String,
        context: ConfigRepositorySubscriptionAttemptContext,
        costMs: Long
    ): ConfigRepositorySubscriptionAttemptResult {
        val shouldStopFallback = ConfigRepository.shouldStopSubscriptionFallback(httpStatusCode = responseCode)
        val error = Exception("HTTP $responseCode: $responseMessage")
        logSubscriptionAttempt(
            level = Log.WARN,
            message = if (shouldStopFallback) {
                "Subscription request hit terminal response"
            } else {
                "Subscription request failed"
            },
            context = context,
            costMs = costMs,
            extra = "code=$responseCode"
        )
        if (!shouldStopFallback) {
            throw error
        }
        return ConfigRepositorySubscriptionAttemptResult(shouldStopFallback = true, terminalError = error)
    }

    protected fun executeSubscriptionAttempt(
        client: OkHttpClient,
        url: String,
        context: ConfigRepositorySubscriptionAttemptContext,
        onProgress: (String) -> Unit,
        onStageChanged: (SubscriptionUpdateStage) -> Unit
    ): ConfigRepositorySubscriptionAttemptResult {
        val startedAt = System.currentTimeMillis()
        val request = buildSubscriptionRequest(url, context.userAgent)

        client.newCall(request).execute().use { response ->
            val costMs = System.currentTimeMillis() - startedAt
            if (!response.isSuccessful) {
                return handleUnsuccessfulSubscriptionResponse(
                    responseCode = response.code,
                    responseMessage = response.message,
                    context = context,
                    costMs = costMs
                )
            }

            val responseBody = response.body?.let { ConfigRepository.readSubscriptionResponseBody(it) }
            if (responseBody.isNullOrBlank()) {
                logSubscriptionAttempt(
                    level = Log.WARN,
                    message = "Empty subscription response",
                    context = context,
                    costMs = costMs
                )
                throw Exception("Subscription response body is empty")
            }

            onStageChanged(SubscriptionUpdateStage.Parsing)
            onProgress("Parsing subscription response...")

            val contentType = response.header("Content-Type")
            val attemptResult = parseSubscriptionResponse(
                userAgent = context.userAgent,
                contentType = contentType,
                responseBody = responseBody,
                subscriptionUserInfoHeader = response.header("Subscription-Userinfo")
            )
            val profileTitle = response.header("profile-title")
            val contentDisposition = response.header("Content-Disposition")
            val subName = SubscriptionManager.parseSubscriptionNameFromHeader(profileTitle, contentDisposition)

            val finalAttemptResult = if (subName != null && attemptResult.fetchResult != null) {
                attemptResult.copy(
                    fetchResult = attemptResult.fetchResult.copy(subscriptionName = subName)
                )
            } else {
                attemptResult
            }
            logSubscriptionParseResult(finalAttemptResult, contentType, context, costMs)
            return finalAttemptResult
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod", "LoopWithTooManyJumpStatements")
    protected fun fetchAndParseSubscription(
        url: String,
        onProgress: (String) -> Unit = {},
        onStageChanged: (SubscriptionUpdateStage) -> Unit = {}): ConfigRepositoryFetchResult? {
        var lastError: Exception? = null
        val host = ConfigRepository.extractSubscriptionHost(url) ?: "unknown"
        val rememberedUserAgent = getRememberedSubscriptionUserAgent(url)
        val userAgents = buildSubscriptionUserAgents(url)
        val totalBudgetSeconds = ConfigRepository.resolveSubscriptionUpdateBudgetSeconds(
            (cachedSettings ?: AppSettings()).subscriptionUpdateTimeout
        )
        val startedAt = System.currentTimeMillis()

        for ((index, userAgent) in userAgents.withIndex()) {
            val elapsedMs = System.currentTimeMillis() - startedAt
            val timeoutBudget = ConfigRepository.resolveSubscriptionAttemptTimeoutBudget(totalBudgetSeconds, elapsedMs)
            if (timeoutBudget == null) {
                Log.w(
                    ConfigRepository.TAG,
                    "Subscription request budget exhausted: host=$host, " +
                        "attempts=$index/${userAgents.size}, budget=${totalBudgetSeconds}s"
                )
                break
            }

            try {
                onStageChanged(SubscriptionUpdateStage.Requesting)
                onProgress("Trying subscription request with User-Agent (${index + 1}/${userAgents.size})...")
                val attemptContext = ConfigRepositorySubscriptionAttemptContext(
                    host = host,
                    userAgent = userAgent,
                    isRemembered = rememberedUserAgent.equals(userAgent, ignoreCase = true)
                )
                val attemptResult = executeSubscriptionAttempt(
                    client = getSubscriptionProxyClient(timeoutBudget) ?: getSubscriptionClient(timeoutBudget),
                    url = url,
                    context = attemptContext,
                    onProgress = onProgress,
                    onStageChanged = onStageChanged
                )
                val fetchResult = attemptResult.fetchResult
                if (fetchResult != null) {
                    rememberSuccessfulSubscriptionUserAgent(url, userAgent)
                    clearSubscriptionUserAgentFailure(host, userAgent)
                    return fetchResult
                }
                if (attemptResult.shouldStopFallback) {
                    lastError = attemptResult.terminalError
                    logSubscriptionFallbackStopped(
                        context = attemptContext,
                        costMs = System.currentTimeMillis() - startedAt,
                        reason = attemptResult.terminalError?.message ?: "html_info_page"
                    )
                    break
                }
            } catch (e: Exception) {
                lastError = e
                if (ConfigRepository.shouldRecordSubscriptionNetworkFailure(e)) {
                    recordSubscriptionUserAgentFailure(host, userAgent)
                }
                Log.w(
                    ConfigRepository.TAG,
                    "Subscription fetch error: host=$host, ua='$userAgent', " +
                        "cached=${rememberedUserAgent.equals(userAgent, ignoreCase = true)}, error=${e.message}"
                )
                if (index == userAgents.lastIndex) {
                    throw e
                }
            }
        }

        lastError?.let {
            Log.e(ConfigRepository.TAG, "All User-Agent attempts failed", it)
            throw it
        }
        return null
    }
    @Suppress("LongMethod", "CognitiveComplexMethod", "CyclomaticComplexMethod")
    suspend fun importFromSubscription(
        name: String,
        url: String,
        autoUpdateInterval: Int = 0,
        dnsPreResolve: Boolean = false,
        dnsServer: String? = null,
        dnsOverride: String? = null,
        onProgress: (String) -> Unit = {}): Result<ProfileUi> = withContext(Dispatchers.IO) {
        var profileId: String? = null
        val normalizedAutoUpdateInterval =
            com.kunk.singbox.service.SubscriptionAutoUpdateWorker.normalizeIntervalMinutes(autoUpdateInterval)
        try {
            onProgress("Fetching subscription content...")
            val fetchResult = try {
                fetchAndParseSubscription(url, onProgress)
            } catch (e: Exception) {
                Log.e(ConfigRepository.TAG, "Subscription fetch failed", e)
                return@withContext Result.failure(e)
            }

            if (fetchResult == null) {
                return@withContext Result.failure(Exception(context.getString(R.string.profiles_parse_failed)))
            }

            val config = fetchResult.config
            val userInfo = fetchResult.userInfo

            val defaultQrName = context.getString(R.string.profiles_qrcode_subscription)
            val finalName = resolveSubscriptionProfileName(name, defaultQrName, fetchResult.subscriptionName)

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
                name = finalName,
                type = ProfileType.Subscription,
                url = url,
                lastUpdated = System.currentTimeMillis(),
                enabled = true,
                autoUpdateInterval = normalizedAutoUpdateInterval,
                updateStatus = UpdateStatus.Idle,
                expireDate = userInfo?.expire ?: 0,
                totalTraffic = userInfo?.total ?: 0,
                usedTraffic = (userInfo?.upload ?: 0) + (userInfo?.download ?: 0),
                dnsPreResolve = dnsPreResolve,
                dnsServer = dnsServer,
                dnsOverride = dnsOverride
            )
            cacheConfig(profileId, deduplicatedConfig)
            profileNodes[profileId] = nodes
            updateAllNodesAndGroups()
            _profiles.update { it + profile }
            saveProfiles()
            if (_activeProfileId.value == null) {
                setActiveProfile(profileId)
            }
            if (normalizedAutoUpdateInterval > 0) {
                com.kunk.singbox.service.SubscriptionAutoUpdateWorker.schedule(
                    context,
                    profileId,
                    normalizedAutoUpdateInterval
                )
            }
            if (dnsPreResolve) {
                onProgress("Pre-resolving domains for imported profile...")
                preResolveDomainsForProfileBestEffort(profileId, deduplicatedConfig, dnsServer)
            }

            onProgress(context.getString(R.string.profiles_import_success, nodes.size.toString()))

            Result.success(profile)
        } catch (e: Exception) {
            profileId?.let { rollbackTransientProfileFile(it) }
            Log.e(ConfigRepository.TAG, "Subscription import failed", e)
            val msg = when (e) {
                is java.net.SocketTimeoutException -> "Connection timeout, please check your network"
                is java.net.UnknownHostException -> "Failed to resolve domain, please check the link"
                is javax.net.ssl.SSLHandshakeException -> "SSL certificate validation failed"
                else -> e.message ?: context.getString(R.string.profiles_import_failed)
            }
            Result.failure(Exception(msg))
        }
    }

    protected suspend fun loadSelectedCustomNodes(selectedNodeIds: List<String>): List<NodeUi> {
        val allCurrentNodes = _allNodes.value.takeIf { it.isNotEmpty() } ?: loadAllNodesSnapshot()
        val nodeById = allCurrentNodes.associateBy { it.id }
        return selectedNodeIds.mapNotNull { nodeById[it] }
    }

    protected fun collectCustomOutbounds(targetNodes: List<NodeUi>): List<com.kunk.singbox.model.Outbound> {
        return targetNodes.mapNotNull { node ->
            val sourceConfig = getConfig(node.sourceProfileId) ?: return@mapNotNull null
            sourceConfig.outbounds?.find { it.tag == node.name }
                ?: sourceConfig.outbounds?.find { it.tag.equals(node.name, ignoreCase = true) }
        }
    }

    protected fun buildCustomProfile(profileId: String, name: String): ProfileUi {
        return ProfileUi(
            id = profileId,
            name = name,
            type = ProfileType.Custom,
            url = null,
            lastUpdated = System.currentTimeMillis(),
            enabled = true,
            updateStatus = UpdateStatus.Idle
        )
    }

    suspend fun createCustomProfile(
        name: String,
        selectedNodeIds: List<String>
    ): Result<ProfileUi> = withContext(Dispatchers.IO) {
        var profileId: String? = null
        try {
            val targetNodes = loadSelectedCustomNodes(selectedNodeIds)
            if (targetNodes.isEmpty()) {
                return@withContext Result.failure(Exception("No nodes selected or found"))
            }

            val outbounds = collectCustomOutbounds(targetNodes)
            if (outbounds.isEmpty()) {
                return@withContext Result.failure(Exception("Failed to extract any outbound data"))
            }

            val newConfig = com.kunk.singbox.model.SingBoxConfig(outbounds = outbounds)
            profileId = UUID.randomUUID().toString()
            val deduplicatedConfig = deduplicateTags(newConfig)
            val nodes = extractNodesFromConfig(deduplicatedConfig, profileId, {})
            if (nodes.isEmpty()) {
                return@withContext Result.failure(Exception("Failed to process extracted nodes"))
            }

            writeConfigFileOrThrow(profileId, deduplicatedConfig)

            val profile = buildCustomProfile(profileId, name)
            cacheConfig(profileId, deduplicatedConfig)
            profileNodes[profileId] = nodes
            updateAllNodesAndGroups()
            _profiles.update { it + profile }
            saveProfiles()

            if (_activeProfileId.value == null) {
                setActiveProfile(profileId)
            }

            Result.success(profile)
        } catch (e: Exception) {
            profileId?.let { rollbackTransientProfileFile(it) }
            Log.e(ConfigRepository.TAG, "Failed to create custom profile", e)
            Result.failure(e)
        }
    }

    protected fun resolveSubscriptionProfileName(
        currentName: String,
        defaultQrName: String,
        subscriptionName: String?
    ): String {
        if (subscriptionName.isNullOrBlank()) {
            return currentName
        }
        return if (isDefaultSubscriptionProfileName(currentName, defaultQrName)) {
            subscriptionName
        } else {
            currentName
        }
    }

    private fun isDefaultSubscriptionProfileName(currentName: String, defaultQrName: String): Boolean {
        return currentName.isBlank() ||
            currentName == defaultQrName ||
            currentName == "扫码订阅" ||
            currentName == "QR Code Subscription"
    }

    suspend fun importFromContent(
        name: String,
        content: String,
        profileType: ProfileType = ProfileType.Imported,
        onProgress: (String) -> Unit = {}): Result<ProfileUi> = withContext(Dispatchers.IO) {
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

    protected fun normalizeImportedContent(content: String): String {
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

    protected fun tryDecodeBase64(content: String): String? {
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

    protected fun extractOutboundsOnly(config: SingBoxConfig): SingBoxConfig {
        val outbounds = config.outbounds ?: config.proxies ?: emptyList()
        return SingBoxConfig(outbounds = outbounds)
    }

    protected fun extractOutboundsFromJson(jsonContent: String): List<Outbound>? {
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

    protected fun sanitizeSubscriptionSnippet(content: String): String {
        val snippet = content.take(200)
        return ConfigRepository.REGEX_SANITIZE_UUID.replace(
            ConfigRepository.REGEX_SANITIZE_PASSWORD.replace(
                ConfigRepository.REGEX_SANITIZE_TOKEN.replace(snippet, "token=***"),
                "password=***"
            ),
            "uuid=***"
        )
    }

    protected fun parseClashYamlConfig(content: String): SingBoxConfig? {
        return if (clashYamlParser.canParse(content)) {
            clashYamlParser.parse(content)
        } else {
            null
        }
    }

    protected fun parseSubscriptionResponse(content: String): SingBoxConfig? {
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

    protected fun parseNodeLink(link: String): Outbound? {
        return nodeLinkParser.parse(link)
    }

    protected suspend fun extractNodesFromConfig(
        config: SingBoxConfig,
        profileId: String,
        onProgress: ((String) -> Unit)? = null): List<NodeUi> {
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

    protected fun extractNodesFromConfigSync(
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

    protected fun createNodeUi(
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

    suspend fun setActiveProfileAndWait(profileId: String, targetNodeId: String? = null) {
        val nodes = setActiveProfile(profileId, targetNodeId)
            ?: loadProfileNodesWithLatency(profileId)
        if (nodes != null && _activeProfileId.value == profileId) {
            applyActiveProfileNodes(profileId, nodes, targetNodeId)
            saveProfilesImmediate()
        }
    }

    fun setActiveProfile(profileId: String, targetNodeId: String? = null): List<NodeUi>? {
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

    fun setActiveNodeIdOnly(nodeId: String) {
        _activeNodeId.value = nodeId
        _nodes.value.find { it.id == nodeId }?.name?.let { VpnStateStore.setSelectedNodeLabel(it) }
        _activeProfileId.value?.let { profileId ->
            saveProfileNodeMemory(profileId, nodeId)
        }
        saveProfilesImmediate()
    }

    protected fun nodeDisplayName(nodeId: String, fallbackNodes: List<NodeUi>): String? {
        return _nodes.value.find { it.id == nodeId }?.name
            ?: fallbackNodes.find { it.id == nodeId }?.name
    }

    suspend fun setActiveNode(nodeId: String): Boolean {
        val result = setActiveNodeWithResult(nodeId)
        return result is ConfigRepository.NodeSwitchResult.Success || result is ConfigRepository.NodeSwitchResult.NotRunning
    }

    suspend fun setActiveNodeWithResult(nodeId: String): ConfigRepository.NodeSwitchResult {
        return nodeSwitchGate.run {
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
            _activeProfileId.value?.let { profileId ->
                saveProfileNodeMemory(profileId, nodeId)
            }
            nodeDisplayName(nodeId, allNodesSnapshot)?.let { VpnStateStore.setSelectedNodeLabel(it) }
            saveProfilesImmediate()

            val remoteRunning = SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value
            if (!remoteRunning) {
                Log.i(ConfigRepository.TAG, "setActiveNodeWithResult: VPN not running, skip hot switch")
                return@run ConfigRepository.NodeSwitchResult.NotRunning
            }

            withContext(Dispatchers.IO) {
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
        }
    }

    suspend fun syncActiveNodeFromProxySelection(proxyName: String?): Boolean {
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

    suspend fun deleteProfile(profileId: String) = withContext(Dispatchers.IO) {
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

    suspend fun importProfileDirectly(profile: ProfileUi, config: SingBoxConfig) = withContext(Dispatchers.IO) {
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

    fun toggleProfileEnabled(profileId: String) {
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

    fun reorderProfiles(newProfiles: List<ProfileUi>) {
        _profiles.value = newProfiles
        saveProfiles()
    }

    fun updateProfileMetadata(
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

    suspend fun testNodeLatency(nodeId: String): Long {
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

                        val config = loadConfig(node.sourceProfileId)
                        if (config == null) {
                            Log.e(ConfigRepository.TAG, "Config not found for profile: ${node.sourceProfileId}")
                            return@withContext -1L
                        }

                        val rawOutbound = config.outbounds?.find { it.tag == node.name }
                        if (rawOutbound == null) {
                            Log.e(ConfigRepository.TAG, "Outbound not found: ${node.name}")
                            return@withContext -1L
                        }

                        val settings = settingsRepository.settings.first()
                        val runtimeContext = buildLatencyRuntimeContext(node.sourceProfileId, config, settings)
                        val fixedOutbound = runtimeContext.outbounds.find { it.tag == rawOutbound.tag }
                        if (fixedOutbound == null) {
                            Log.e(ConfigRepository.TAG, "Outbound type removed: ${rawOutbound.type}")
                            return@withContext -1L
                        }
                        val allOutbounds = runtimeContext.outbounds
                        val probeOutbound = prepareOfflineProbeOutbound(fixedOutbound)
                        val latency = singBoxCore.testOutboundLatency(
                            probeOutbound,
                            allOutbounds,
                            runtimeContext.dnsConfig
                        )
                        val finalLatency = if (latency > 0) {
                            latency
                        } else {
                            val fallback = tcpLatencyFallback(probeOutbound)
                            if (fallback > 0) {
                                fallback
                            } else {
                                resolveIpv6OnlyStatus(probeOutbound, latency)
                            }
                        }

                        _nodes.update { list ->
                            list.map {
                                if (it.id == nodeId) {
                                    it.copy(latencyMs = normalizeLatencyValue(finalLatency))
                                } else {
                                    it
                                }
                            }
                        }

                        profileNodes[node.sourceProfileId] = profileNodes[node.sourceProfileId]?.map {
                            if (it.id == nodeId) {
                                it.copy(latencyMs = normalizeLatencyValue(finalLatency))
                            } else {
                                it
                            }
                        } ?: emptyList()
                        updateLatencyInAllNodes(nodeId, finalLatency)
                        saveProfiles()

                        finalLatency
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
        } catch (e: Exception) {
            deferred.complete(-1L)
            return -1L
        } finally {
            inFlightLatencyTests.remove(nodeId, deferred)
        }
    }

    suspend fun clearAllNodesLatency() = withContext(Dispatchers.IO) {
        savedNodeLatencies.clear()

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

    suspend fun testAllNodesLatency(
        targetNodeIds: List<String>? = null,
        useAllNodes: Boolean = false,
        onNodeComplete: ((nodeId: String, latencyMs: Long) -> Unit)? = null) = withContext(Dispatchers.IO) {
        val sourceNodes = if (useAllNodes) _allNodes.value else _nodes.value
        val nodes = if (targetNodeIds != null) {
            sourceNodes.filter { it.id in targetNodeIds }
        } else {
            sourceNodes
        }

        val settings = settingsRepository.settings.first()
        val testInfoList = buildNodeTestInfos(nodes, settings)

        if (testInfoList.isEmpty()) {
            Log.w(ConfigRepository.TAG, "No valid nodes to test")
            return@withContext
        }

        val (tcpFallbackInfos, regularInfos) = testInfoList.partition {
            LatencyProbePolicy.shouldUseTcpFallback(it.outbound)
        }

        val concurrency = settings.latencyTestConcurrency.coerceIn(1, 20)

        coroutineScope {
            val regularJob = async {
                testRegularOutboundsLatency(regularInfos, concurrency, onNodeComplete)
            }
            val tcpFallbackJob = async {
                testTcpFallbackOutboundsLatency(tcpFallbackInfos, concurrency, onNodeComplete)
            }

            regularJob.await()
            tcpFallbackJob.await()
        }

        saveProfiles()
    }

    suspend fun updateAllProfiles(): BatchUpdateResult = withContext(Dispatchers.IO) {
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
    suspend fun updateProfile(profileId: String): SubscriptionUpdateResult {
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
                    it.updateStage?.isBackground == true -> it.updateStage
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
                        updateStage = it.updateStage?.takeIf(SubscriptionUpdateStage::isBackground)
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
    protected suspend fun importFromSubscriptionUpdate(
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
                _nodes.value = newNodes
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
            val updateResult = buildSubscriptionUpdateSuccessResult(
                profileName = profile.name,
                addedNodes = addedNodes,
                removedNodes = removedNodes,
                totalCount = newNodes.size,
                dnsMovedToBackground = ConfigRepository.launchSubscriptionDnsPreResolve(
                    scope = scope,
                    profileId = profile.id,
                    enabled = profile.dnsPreResolve,
                    updateRunId = updateRunId,
                    onStarted = {
                        setProfileUpdateStage(profile.id, updateRunId, SubscriptionUpdateStage.DnsBackground)
                    },
                    onFinished = {
                        setProfileUpdateStage(profile.id, updateRunId, null)
                    }
                ) {
                    preResolveDomainsForProfileBestEffort(profile.id, deduplicatedConfig, profile.dnsServer)
                } != null
            )

            updateResult
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

    protected fun buildSubscriptionUpdateSuccessResult(
        profileName: String,
        addedNodes: Set<String>,
        removedNodes: Set<String>,
        totalCount: Int,
        dnsMovedToBackground: Boolean
    ): SubscriptionUpdateResult {
        return if (addedNodes.isNotEmpty() || removedNodes.isNotEmpty()) {
            SubscriptionUpdateResult.SuccessWithChanges(
                profileName = profileName,
                addedCount = addedNodes.size,
                removedCount = removedNodes.size,
                totalCount = totalCount,
                dnsMovedToBackground = dnsMovedToBackground
            )
        } else {
            SubscriptionUpdateResult.SuccessNoChanges(
                profileName = profileName,
                totalCount = totalCount,
                dnsMovedToBackground = dnsMovedToBackground
            )
        }
    }

    suspend fun generateConfigFile(): ConfigRepository.ConfigGenerationResult? = withContext(Dispatchers.IO) {
        try {
            awaitInitialProfilesLoaded()
            val activeId = _activeProfileId.value
                ?: activeStateDao.get()?.activeProfileId
                ?: return@withContext null
            val activeProfile = _profiles.value.find { it.id == activeId }
            val config = loadConfigWithLegacyEchRepair(activeProfile, activeId) ?: return@withContext null
            val activeNodeId = _activeNodeId.value
                ?: activeStateDao.get()?.activeNodeId

            val allNodesSnapshot = _allNodes.value.takeIf { it.isNotEmpty() } ?: loadAllNodesSnapshot()
            val activeNode = _nodes.value.find { it.id == activeNodeId }
                ?: allNodesSnapshot.find { it.id == activeNodeId }
            val sanitizedSettings = settingsRepository.settings.first()
            val log = buildRunLogConfig()
            val experimental = buildRunExperimentalConfig(sanitizedSettings)
            val inbounds = buildRunInbounds(sanitizedSettings)
            val customRuleSets = buildCustomRuleSets(sanitizedSettings)

            val dnsOverrideConfig = parseDnsOverride(activeProfile?.dnsOverride)
            val rawOutboundsContext = buildRunOutbounds(
                config, activeNode, sanitizedSettings, allNodesSnapshot,
                activeProfile?.dnsPreResolve ?: false, activeId, dnsOverrideConfig
            )
            val serverAddressStrategy = resolveDnsStrategy(
                sanitizedSettings.serverAddressStrategy,
                sanitizedSettings.ipVersionMode
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
            val dns = buildRunDns(
                sanitizedSettings,
                customRuleSets,
                outboundsContext,
                dnsOverrideConfig,
                config.dns
            )
            val route = buildRunRoute(
                sanitizedSettings,
                outboundsContext.selectorTag,
                outboundsContext.outbounds,
                outboundsContext.nodeTagResolver,
                customRuleSets
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
                outbounds = outboundsContext.outbounds
            )

            val validation = singBoxCore.validateConfig(stripInternalMetadata(runConfig))
            validation.exceptionOrNull()?.let { e ->
                val msg = e.cause?.message ?: e.message ?: "unknown error"
                Log.e(ConfigRepository.TAG, "Config pre-validation failed: $msg", e)
                throw Exception("Config validation failed: $msg", e)
            }
            val allTags = runConfig.outbounds?.map { it.tag }?.toSet() ?: emptySet()
            val candidateTag = activeNodeId?.let { outboundsContext.nodeTagMap[it] }
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
            val configFile = File(context.filesDir, "running_config.json")
            ConfigRepository.writeTextFileAtomically(configFile, gson.toJson(stripInternalMetadata(runConfig)))
            logRunningConfigPath(configFile, resolvedTag, allTags.size)

            ConfigRepository.ConfigGenerationResult(configFile.absolutePath, resolvedTag, allTags)
        } catch (e: Exception) {
            Log.e(ConfigRepository.TAG, "Failed to generate config file", e)
            null
        }
    }

    private fun logRunningConfigPath(configFile: File, activeNodeTag: String?, outboundCount: Int) {
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

    protected fun buildOutboundForRuntime(outbound: Outbound): Outbound? =
        OutboundFixer.buildForRuntime(context, outbound)

    protected fun loadConfigWithLegacyEchRepair(profile: ProfileUi?, profileId: String): SingBoxConfig? {
        val config = loadConfig(profileId) ?: return null
        val subscriptionUrl = profile?.url?.takeIf { it.isNotBlank() } ?: return config
        if (!ConfigRepository.needsLegacyEchDnsRepair(config)) return config

        val repairedConfig = fetchAndParseSubscription(subscriptionUrl)?.config ?: return config
        val deduplicatedConfig = deduplicateTags(repairedConfig)
        if (ConfigRepository.needsLegacyEchDnsRepair(deduplicatedConfig)) return config

        runCatching {
            writeConfigFileOrThrow(profileId, deduplicatedConfig)
            cacheConfig(profileId, deduplicatedConfig)
            val repairedNodes = extractNodesFromConfigSync(deduplicatedConfig, profileId)
            profileNodes[profileId] = repairedNodes
            updateAllNodesAndGroups()
            if (_activeProfileId.value == profileId) {
                _nodes.value = repairedNodes
            }
            Log.i(ConfigRepository.TAG, "Repaired legacy ECH subscription config for profile: ${profile?.name ?: profileId}")
        }.onFailure { e ->
            Log.w(ConfigRepository.TAG, "Failed to persist repaired ECH subscription config for profile: $profileId", e)
        }
        return deduplicatedConfig
    }

    protected fun stripInternalMetadata(config: SingBoxConfig): SingBoxConfig {
        return config.copy(
            outbounds = config.outbounds?.map { stripInternalMetadata(it) },
            proxies = config.proxies?.map { stripInternalMetadata(it) }
        )
    }

    protected fun stripInternalMetadata(outbound: Outbound): Outbound {
        val tls = outbound.tls ?: return outbound
        val ech = tls.ech ?: return outbound
        return outbound.copy(tls = tls.copy(ech = ech.copy(dnsServer = null)))
    }

    protected suspend fun preResolveDomainsForProfile(
        profileId: String,
        config: SingBoxConfig,
        dnsServer: String?
    ) {
        val outbounds = config.outbounds ?: return
        val domains = outbounds.mapNotNull { outbound ->
            val server = outbound.server ?: return@mapNotNull null
            if (DnsResolver.isIpAddress(server)) return@mapNotNull null
            server
        }.distinct()

        if (domains.isEmpty()) {
            Log.d(ConfigRepository.TAG, "No domains to pre-resolve for profile $profileId")
            return
        }

        Log.d(ConfigRepository.TAG, "Pre-resolving ${domains.size} domains for profile $profileId")

        val results = dnsResolver.resolveBatch(
            domains = domains,
            dohServer = dnsServer ?: DnsResolver.DOH_CLOUDFLARE
        )

        val savedCount = dnsResolveStore.saveBatch(profileId, results)
        Log.d(ConfigRepository.TAG, "Pre-resolved and saved $savedCount domains for profile $profileId")
    }

    protected fun applyDnsResolveToOutbound(profileId: String, outbound: Outbound): Outbound {
        val server = outbound.server ?: return outbound
        if (DnsResolver.isIpAddress(server)) return outbound

        val resolvedIp = dnsResolveStore.getIp(profileId, server)
        return if (resolvedIp != null) {
            Log.d(ConfigRepository.TAG, "Applying DNS resolve: $server -> $resolvedIp")
            outbound.copy(server = resolvedIp)
        } else {
            outbound
        }
    }

    protected fun detectValidRuleSetFileFormat(file: File, tag: String): String? {
        if (!file.exists() || file.length() == 0L) {
            Log.w(ConfigRepository.TAG, "Rule set file not found or empty: $tag (${file.absolutePath})")
            return null
        }

        return try {
            val sample = readRuleSetSample(file)
            if (sample.isEmpty()) {
                Log.w(ConfigRepository.TAG, "Rule set file header is empty, ignoring: $tag")
                return null
            }

            if (!isLikelyTextRuleSet(sample)) {
                if (validateBinaryRuleSet(file, tag)) "binary" else null
            } else {
                if (validateTextRuleSet(file, tag, readRuleSetInspectionText(file, sample))) "source" else null
            }
        } catch (e: Exception) {
            Log.w(ConfigRepository.TAG, "Failed to validate rule set file: $tag", e)
            null
        }
    }

    protected fun readRuleSetSample(file: File): ByteArray {
        return file.inputStream().use { input ->
            val buffer = ByteArray(ConfigRepository.RULE_SET_SNIFF_BYTES)
            val read = input.read(buffer)
            if (read > 0) buffer.copyOf(read) else ByteArray(0)
        }
    }

    protected fun isLikelyTextRuleSet(sample: ByteArray): Boolean {
        if (sample.any { it == 0.toByte() }) return false
        val printableBytes = sample.count { byte ->
            val code = byte.toInt() and 0xff
            code == 9 || code == 10 || code == 13 || code in 32..126
        }
        return printableBytes >= sample.size * 3 / 4
    }

    protected fun readRuleSetInspectionText(file: File, sample: ByteArray): String {
        return if (file.length() <= ConfigRepository.RULE_SET_TEXT_PARSE_LIMIT_BYTES) {
            file.readText()
        } else {
            sample.toString(Charsets.UTF_8)
        }
    }

    protected fun validateBinaryRuleSet(file: File, tag: String): Boolean {
        val sample = readRuleSetSample(file)
        if (file.length() >= ConfigRepository.RULE_SET_MIN_SIZE_BYTES && hasRuleSetBinaryMagic(sample)) {
            return true
        }
        Log.w(ConfigRepository.TAG, "Rule set binary file is not a valid .srs file, ignoring: $tag (${file.length()} bytes)")
        return false
    }

    protected fun hasRuleSetBinaryMagic(sample: ByteArray): Boolean {
        if (sample.size < ConfigRepository.RULE_SET_BINARY_MAGIC.length) return false
        return sample[0] == 'S'.code.toByte() &&
            sample[1] == 'R'.code.toByte() &&
            sample[2] == 'S'.code.toByte()
    }

    protected fun validateTextRuleSet(file: File, tag: String, inspectionText: String): Boolean {
        val trimmed = inspectionText.trim()
        val validTextRuleSet = when {
            trimmed.startsWith("{") || trimmed.startsWith("[") -> isValidRuleSetJson(trimmed)
            else -> isValidRuleSetStructuredText(trimmed)
        }
        return when {
            trimmed.isEmpty() -> {
                Log.w(ConfigRepository.TAG, "Rule set text content is blank, ignoring: $tag")
                false
            }

            ConfigRepository.looksLikeHtmlSubscriptionPage(contentType = null, body = trimmed) -> {
                Log.e(ConfigRepository.TAG, "Rule set file appears to be HTML, ignoring: $tag")
                false
            }

            validTextRuleSet -> true
            else -> rejectUnrecognizedRuleSetText(file, tag, trimmed)
        }
    }

    protected fun rejectUnrecognizedRuleSetText(file: File, tag: String, trimmed: String): Boolean {
        if (file.length() < ConfigRepository.RULE_SET_MIN_SIZE_BYTES) {
            Log.w(ConfigRepository.TAG, "Rule set text file too small, ignoring: $tag (${file.length()} bytes)")
            return false
        }
        if (ConfigRepository.REGEX_RULE_SET_ERROR_TEXT.containsMatchIn(trimmed.lineSequence().firstOrNull().orEmpty())) {
            Log.e(ConfigRepository.TAG, "Rule set file looks like an error response, ignoring: $tag")
            return false
        }

        Log.w(ConfigRepository.TAG, "Rule set file content not recognized, ignoring: $tag (${file.length()} bytes)")
        return false
    }

    protected fun isValidRuleSetJson(content: String): Boolean {
        return runCatching {
            val element = JsonParser.parseString(content)
            when {
                element.isJsonArray -> element.asJsonArray.size() > 0
                !element.isJsonObject -> false
                else -> {
                    val obj = element.asJsonObject
                    obj.has("rules") ||
                        obj.has("rule_set") ||
                        obj.has("payload") ||
                        obj.has("type") ||
                        obj.has("version") ||
                        ConfigRepository.REGEX_RULE_SET_JSON_KEYS.containsMatchIn(content)
                }
            }
        }.getOrDefault(false)
    }

    protected fun isValidRuleSetStructuredText(content: String): Boolean {
        val lines = content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .take(8)
            .toList()
        if (lines.isEmpty()) return false
        if (ConfigRepository.REGEX_RULE_SET_ERROR_TEXT.containsMatchIn(lines.first())) {
            return false
        }
        return lines.any { ConfigRepository.REGEX_RULE_SET_TEXT_LINE.containsMatchIn(it) }
    }

    protected fun buildCustomRuleSets(settings: AppSettings): List<RuleSetConfig> {
        val ruleSetRepo = RuleSetRepository.getInstance(context)

        val rules = settings.ruleSets.filter { it.enabled }.map { ruleSet ->
            if (ruleSet.type == RuleSetType.REMOTE) {
                val localPath = ruleSetRepo.getRuleSetPath(ruleSet.tag)
                val file = File(localPath)
                val detectedFormat = detectValidRuleSetFileFormat(file, ruleSet.tag)
                if (detectedFormat != null) {
                    RuleSetConfig(
                        tag = ruleSet.tag,
                        type = "local",
                        format = detectedFormat,
                        path = localPath
                    )
                } else null
            } else {
                val file = File(ruleSet.path)
                val detectedFormat = detectValidRuleSetFileFormat(file, ruleSet.tag)
                if (detectedFormat != null) {
                    RuleSetConfig(
                        tag = ruleSet.tag,
                        type = "local",
                        format = detectedFormat,
                        path = ruleSet.path
                    )
                } else {
                    Log.w(ConfigRepository.TAG, "Local rule set file not found: ${ruleSet.tag} (${ruleSet.path})")
                    null
                }
            }
        }.filterNotNull().toMutableList()

        return rules
    }

    internal fun getAppliedRemoteRuleSets(settings: AppSettings): List<RuleSet> {
        val validTags = buildCustomRuleSets(settings)
            .mapNotNull { it.tag }
            .toSet()
        return ConfigRepository.filterAppliedRemoteRuleSets(settings.ruleSets, validTags)
    }

    protected fun buildCustomDomainRules(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>,
        profiles: List<ProfileUi>,
        nodeTagResolver: (String?) -> String?
    ): List<RouteRule> {
        fun splitValues(raw: String): List<String> {
            return raw
                .split("\n", "\r", ",", ";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        val rules = settings.customRules
            .filter { it.enabled }
            .filter {
                it.type == RuleType.DOMAIN ||
                    it.type == RuleType.DOMAIN_SUFFIX ||
                    it.type == RuleType.DOMAIN_KEYWORD
            }
            .mapNotNull { rule ->
                val values = splitValues(rule.value)
                if (values.isEmpty()) return@mapNotNull null

                val semantic = ConfigRepository.resolveOutboundSemantic(
                    mode = ConfigRepository.resolveCustomRuleOutboundMode(rule.outboundMode, rule.outbound),
                    value = rule.outboundValue,
                    context = ConfigRepositoryOutboundSemanticContext(
                        selectorTag = defaultProxyTag,
                        outbounds = outbounds,
                        profiles = profiles,
                        nodeTagResolver = nodeTagResolver
                    )
                )
                val baseRule = ConfigRepository.toRouteRule(semantic, defaultProxyTag)
                Log.d(
                    ConfigRepository.TAG,
                    "CustomDomainRule: type=${rule.type}, value=${rule.value}, mode=${rule.outboundMode}, " +
                        "outboundValue=${rule.outboundValue}, resolved=${baseRule.outbound}"
                )
                when (rule.type) {
                    RuleType.DOMAIN -> baseRule.copy(domain = values)
                    RuleType.DOMAIN_SUFFIX -> baseRule.copy(domainSuffix = values)
                    RuleType.DOMAIN_KEYWORD -> baseRule.copy(domainKeyword = values)
                    else -> null
                }
            }
        return rules
    }

    @Suppress("LongParameterList")
    protected fun buildCustomRuleSetRules(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>,
        profiles: List<ProfileUi>,
        nodeTagResolver: (String?) -> String?,
        validRuleSets: List<RuleSetConfig>
    ): List<RouteRule> {
        val rules = mutableListOf<RouteRule>()

        val validTags = validRuleSets.mapNotNull { it.tag }.toSet()
        val sortedRuleSets = ConfigRepository.sortRuleSetsForDnsAndRoutePriority(
            settings.ruleSets.filter { it.enabled && it.tag in validTags }
        )

        sortedRuleSets.forEach { ruleSet ->
            val semantic = ConfigRepository.resolveOutboundSemantic(
                mode = ConfigRepository.resolveRuleSetOutboundMode(ruleSet.outboundMode),
                value = ruleSet.outboundValue,
                context = ConfigRepositoryOutboundSemanticContext(
                    selectorTag = defaultProxyTag,
                    outbounds = outbounds,
                    profiles = profiles,
                    nodeTagResolver = nodeTagResolver
                )
            )
            val baseRule = ConfigRepository.toRouteRule(semantic, defaultProxyTag)
            val inboundTags: List<String>? = when {
                ruleSet.inbounds.isNullOrEmpty() -> null
                else -> ruleSet.inbounds.map {
                    when (it) {
                        "tun" -> "tun-in"
                        "mixed" -> "mixed-in"
                        else -> it
                    }
                }
            }

            rules.add(baseRule.copy(
                ruleSet = listOf(ruleSet.tag),
                inbound = inboundTags
            ))
        }

        return rules
    }

    @Suppress("LongMethod")
    protected fun buildAppRoutingRules(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>,
        profiles: List<ProfileUi>,
        nodeTagResolver: (String?) -> String?
    ): List<RouteRule> {
        val rules = mutableListOf<RouteRule>()

        fun resolveUidByPackageName(pkg: String): Int? {
            return try {
                context.packageManager.getApplicationInfo(pkg, 0).uid
            } catch (_: Exception) {
                null
            }
        }

        settings.appRules.filter { it.enabled }.forEach { rule ->
            val semantic = ConfigRepository.resolveOutboundSemantic(
                mode = ConfigRepository.resolveAppRuleOutboundMode(rule.outboundMode),
                value = rule.outboundValue,
                context = ConfigRepositoryOutboundSemanticContext(
                    selectorTag = defaultProxyTag,
                    outbounds = outbounds,
                    profiles = profiles,
                    nodeTagResolver = nodeTagResolver
                )
            )
            val baseRule = ConfigRepository.toRouteRule(semantic, defaultProxyTag)

            val uid = resolveUidByPackageName(rule.packageName)
            if (uid != null && uid > 0) {
                rules.add(
                    baseRule.copy(
                        userId = listOf(uid)
                    )
                )
            }

            rules.add(
                baseRule.copy(
                    packageName = listOf(rule.packageName)
                )
            )
        }
        settings.appGroups.filter { it.enabled }.forEach { group ->
            val semantic = ConfigRepository.resolveOutboundSemantic(
                mode = ConfigRepository.resolveAppGroupOutboundMode(group.outboundMode),
                value = group.outboundValue,
                context = ConfigRepositoryOutboundSemanticContext(
                    selectorTag = defaultProxyTag,
                    outbounds = outbounds,
                    profiles = profiles,
                    nodeTagResolver = nodeTagResolver
                )
            )
            val baseRule = ConfigRepository.toRouteRule(semantic, defaultProxyTag)
            val packageNames = group.apps.map { it.packageName }
            if (packageNames.isNotEmpty()) {
                val uids = packageNames.mapNotNull { resolveUidByPackageName(it) }.filter { it > 0 }.distinct()
                if (uids.isNotEmpty()) {
                    rules.add(
                        baseRule.copy(
                            userId = uids
                        )
                    )
                }

                rules.add(
                    baseRule.copy(
                        packageName = packageNames
                    )
                )
            }
        }

        return rules
    }

    protected fun buildRunLogConfig(): LogConfig {
        return LogConfig(
            level = "info",
            timestamp = true
        )
    }

    protected fun buildRunExperimentalConfig(settings: AppSettings): ExperimentalConfig {
        val singboxDataDir = File(context.filesDir, "singbox_data").also { it.mkdirs() }

        val clashApiPort = findAvailablePort(9090)
        val clashApi = ClashApiConfig(
            externalController = "127.0.0.1:$clashApiPort",
            defaultMode = "rule"
        )

        return ExperimentalConfig(
            cacheFile = CacheFileConfig(
                enabled = true,
                path = File(singboxDataDir, "cache.db").absolutePath,
                storeFakeip = settings.fakeDnsEnabled
            ),
            clashApi = clashApi
        )
    }

    protected fun buildRunInbounds(settings: AppSettings): List<Inbound> =
        InboundBuilder.build(
            settings.copy(tunMtu = getEffectiveTunMtu(settings)),
            getEffectiveTunStack(settings.tunStack)
        )

    protected fun resolveRunDnsFinalServer(
        routingMode: RoutingMode,
        defaultRule: DefaultRule,
        fakeDnsEnabled: Boolean,
        proxyServerTag: String,
        stableRemoteServerTag: String = "remote",
        directServerTag: String = "local"): String {
        return when (routingMode) {
            RoutingMode.GLOBAL_PROXY -> stableRemoteServerTag
            RoutingMode.GLOBAL_DIRECT -> directServerTag
            RoutingMode.RULE -> when (defaultRule) {
                DefaultRule.PROXY -> proxyServerTag
                DefaultRule.DIRECT -> directServerTag
                DefaultRule.BLOCK -> if (fakeDnsEnabled) stableRemoteServerTag else proxyServerTag
            }
        }
    }

    @Suppress(
        "LongMethod",
        "CyclomaticComplexMethod",
        "CognitiveComplexMethod",
        "NestedBlockDepth"
    )
    protected fun buildRunDns(
        settings: AppSettings,
        validRuleSets: List<RuleSetConfig>,
        outboundsContext: ConfigRepositoryRunOutboundsContext,
        dnsOverride: DnsConfig? = null,
        originalDns: DnsConfig? = null): DnsConfig {
        val dnsServers = mutableListOf<DnsServer>()
        val dnsRules = mutableListOf<DnsRule>()

        val profiles = _profiles.value
        val proxyDetourTag = resolveCurrentProxyDnsDetourTag(outboundsContext.selectorTag, outboundsContext.outbounds)
        val proxyServerTag = ConfigRepository.buildDynamicDnsServerTag(proxyDetourTag)
        val proxyFinalServerTag = proxyServerTag
        val directServerTag = "local"

        fun dnsRouteTo(server: String, rule: DnsRule): DnsRule =
            rule.copy(action = "route", server = server)

        fun dnsRouteToDirect(server: String, rule: DnsRule): DnsRule =
            ConfigRepository.buildDnsRouteToDirect(server, directServerTag, rule)

        fun dnsReject(rule: DnsRule): DnsRule = rule.copy(action = "predefined", rcode = "NOERROR")

        fun parseDomainList(input: String): List<String> {
            return input
                .split("\n", "\r", ";")
                .flatMap { rawEntry ->
                    val entry = rawEntry.trim()
                    when {
                        entry.isEmpty() -> emptyList()
                        entry.contains(",") && !entry.contains(".") -> {
                            entry.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        }

                        else -> listOf(entry)
                    }
                }
                .distinct()
        }

        fun dnsRouteToProxy(rule: DnsRule): List<DnsRule> {
            return ConfigRepository.buildDnsRouteToNonDirect(settings.fakeDnsEnabled, proxyServerTag, rule)
        }

        fun dnsRouteToNonDirect(server: String, rule: DnsRule): List<DnsRule> {
            return ConfigRepository.buildDnsRouteToNonDirect(settings.fakeDnsEnabled, server, rule)
        }

        fun outboundModeOf(
            ruleOutboundMode: RuleSetOutboundMode?,
            fallbackOutbound: OutboundTag?
        ): RuleSetOutboundMode {
            return ruleOutboundMode
                ?: when (fallbackOutbound) {
                    OutboundTag.DIRECT -> RuleSetOutboundMode.DIRECT
                    OutboundTag.BLOCK -> RuleSetOutboundMode.BLOCK
                    OutboundTag.PROXY -> RuleSetOutboundMode.PROXY
                    null -> RuleSetOutboundMode.PROXY
                }
        }
        val echQueryServerTag = "dns-bootstrap"
        dnsRules.addAll(
            ConfigRepository.buildEchAwareHttpsSvcbDnsRules(
                blockQuic = settings.blockQuic,
                outbounds = outboundsContext.outbounds,
                echQueryServerTag = echQueryServerTag
            )
        )
        val bootstrapStrategy = resolveDnsStrategy(settings.serverAddressStrategy, settings.ipVersionMode)
        val bootstrapV4Tag = "dns-bootstrap-v4"
        val bootstrapV6Tag = "dns-bootstrap-v6"

        // sing-box 1.13+: 不设 detour 即为直连，显式设 detour="direct" 会报
        // "detour to an empty direct outbound makes no sense"
        dnsServers.add(
            DnsServer(
                tag = bootstrapV4Tag,
                type = "https",
                server = "223.5.5.5",
                domainStrategy = bootstrapStrategy
            )
        )
        dnsServers.add(
            DnsServer(
                tag = bootstrapV6Tag,
                type = "https",
                server = "2606:4700:4700::1111",
                domainStrategy = "prefer_ipv6"
            )
        )
        dnsServers.add(
            DnsServer(
                tag = "dns-bootstrap",
                type = "https",
                server = "1.12.12.12",
                domainStrategy = bootstrapStrategy
            )
        )

        val localDnsAddr = ConfigRepository.normalizeLocalDns(settings.localDns)
        val localResolver = ConfigRepository.buildDnsResolverForAddress(localDnsAddr)
        val localServer = ConfigRepository.buildDnsServer(
            address = localDnsAddr,
            tag = "local",
            domainStrategy = resolveDnsStrategy(settings.directDnsStrategy, settings.ipVersionMode),
            domainResolver = localResolver
        )
        dnsServers.add(localServer)
        val remoteDnsAddr = ConfigRepository.normalizeRemoteDns(settings.remoteDns)
        val remoteResolver = ConfigRepository.buildDnsResolverForAddress(remoteDnsAddr)
        val remoteDetour = if (settings.routingMode != RoutingMode.GLOBAL_DIRECT) proxyDetourTag else null
        val remoteServer = ConfigRepository.buildDnsServer(
            address = remoteDnsAddr,
            tag = "remote",
            detour = remoteDetour,
            domainStrategy = resolveDnsStrategy(settings.remoteDnsStrategy, settings.ipVersionMode),
            domainResolver = remoteResolver
        )
        dnsServers.add(remoteServer)
        val remoteStrategy = resolveDnsStrategy(settings.remoteDnsStrategy, settings.ipVersionMode)
        ConfigRepository.ensureDynamicRemoteDnsServers(
            dnsServers = dnsServers,
            semantics = listOf(ConfigRepository.OutboundSemantic.RouteTag(proxyDetourTag)),
            remoteDnsAddr = remoteDnsAddr,
            remoteStrategy = remoteStrategy,
            remoteResolver = remoteResolver
        )
        val bootstrapDnsAddresses = listOf(localDnsAddr, remoteDnsAddr)

        dnsRules.addAll(
            ConfigRepository.buildBootstrapDnsRules(
                serverAddresses = bootstrapDnsAddresses,
                bootstrapV4Tag = bootstrapV4Tag,
                bootstrapV6Tag = bootstrapV6Tag,
                bootstrapTag = "dns-bootstrap"
            )
        )

        if (settings.fakeDnsEnabled) {
            dnsServers.add(ConfigRepository.buildFakeIpDnsServer(settings.fakeIpRange))
        }
        val customDomainRulesForDns = settings.customRules
            .filter { it.enabled }
            .filter {
                it.type == RuleType.DOMAIN ||
                    it.type == RuleType.DOMAIN_SUFFIX ||
                    it.type == RuleType.DOMAIN_KEYWORD
            }
        val domainSemantics = mutableListOf<ConfigRepository.OutboundSemantic>()

        if (settings.routingMode != RoutingMode.GLOBAL_DIRECT) {
            dnsRules.addAll(
                ConfigRepository.buildGoogleConnectivityDnsRules(
                    fakeDnsEnabled = settings.fakeDnsEnabled,
                    proxyServerTag = proxyServerTag
                )
            )
        }

        if (customDomainRulesForDns.isNotEmpty()) {
            val dnsRulesByServer = linkedMapOf<String, MutableList<DnsRule>>()

            fun addDnsRuleForSemantic(rule: DnsRule, semantic: ConfigRepository.OutboundSemantic) {
                domainSemantics.add(semantic)
                when (semantic) {
                    ConfigRepository.OutboundSemantic.Block -> dnsRules.add(dnsReject(rule))
                    else -> {
                        val serverTag = ConfigRepository.dnsServerTagForSemantic(
                            semantic,
                            settings.fakeDnsEnabled,
                            directServerTag,
                            proxyServerTag
                        ) ?: return
                        dnsRulesByServer.getOrPut(serverTag) { mutableListOf() }.add(rule)
                    }
                }
            }

            customDomainRulesForDns.forEach { rule ->
                val values = rule.value
                    .split("\n", "\r", ",", ";")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                if (values.isEmpty()) return@forEach

                val semantic = ConfigRepository.resolveOutboundSemantic(
                    mode = ConfigRepository.resolveCustomRuleOutboundMode(rule.outboundMode, rule.outbound),
                    value = rule.outboundValue,
                    context = ConfigRepositoryOutboundSemanticContext(
                        selectorTag = outboundsContext.selectorTag,
                        outbounds = outboundsContext.outbounds,
                        profiles = profiles,
                        nodeTagResolver = outboundsContext.nodeTagResolver
                    )
                )

                when (rule.type) {
                    RuleType.DOMAIN -> {
                        addDnsRuleForSemantic(DnsRule(domain = values.distinct()), semantic)
                    }
                    RuleType.DOMAIN_SUFFIX -> {
                        addDnsRuleForSemantic(DnsRule(domainSuffix = values.distinct()), semantic)
                    }
                    RuleType.DOMAIN_KEYWORD -> {
                        addDnsRuleForSemantic(DnsRule(domainKeyword = values.distinct()), semantic)
                    }
                    else -> {}
                }
            }

            dnsRulesByServer.forEach { (serverTag, rulesForServer) ->
                rulesForServer.forEach { rule ->
                    if (serverTag == "fakeip-dns") {
                        dnsRules.addAll(dnsRouteToProxy(rule))
                    } else if (serverTag == directServerTag) {
                        dnsRules.add(dnsRouteToDirect(serverTag, rule))
                    } else {
                        dnsRules.addAll(dnsRouteToNonDirect(serverTag, rule))
                    }
                }
            }
        }
        val validRuleSetTags = validRuleSets.mapNotNull { it.tag }.toSet()
        val dnsRuleSetRulesByServer = linkedMapOf<String, MutableList<DnsRule>>()
        val ruleSetSemantics = mutableListOf<ConfigRepository.OutboundSemantic>()

        fun addRuleSetDnsRule(rule: DnsRule, semantic: ConfigRepository.OutboundSemantic) {
            ruleSetSemantics.add(semantic)
            when (semantic) {
                ConfigRepository.OutboundSemantic.Block -> dnsRules.add(dnsReject(rule))
                else -> {
                    val serverTag = ConfigRepository.dnsServerTagForSemantic(
                        semantic,
                        settings.fakeDnsEnabled,
                        directServerTag,
                        proxyServerTag
                    ) ?: return
                    dnsRuleSetRulesByServer.getOrPut(serverTag) {
                        mutableListOf()
                    }.add(rule)
                }
            }
        }

        ConfigRepository.sortRuleSetsForDnsAndRoutePriority(settings.ruleSets.filter { it.enabled })
            .forEach { ruleSet ->
                val tag = ruleSet.tag
                if (tag.isBlank() || tag !in validRuleSetTags) return@forEach

                val ruleSetConfig = validRuleSets.find { it.tag == tag }
                val ruleSetPath = ruleSetConfig?.path ?: return@forEach
                val ruleSetFile = File(ruleSetPath)
                val ruleType = ConfigRepository.detectRuleSetRuleTypeStatic(ruleSetFile, tag)

                // Only add domain-based or mixed rulesets to DNS rules.
                // Pure IP rulesets (like GeoIP) should only be used in Route rules.
                if (ruleType == ConfigRepository.RuleSetRuleType.IP) {
                    Log.d(ConfigRepository.TAG, "Skipping IP-only ruleset in DNS rules: $tag")
                    return@forEach
                }

                val semantic = ConfigRepository.resolveOutboundSemantic(
                    mode = ConfigRepository.resolveRuleSetOutboundMode(ruleSet.outboundMode),
                    value = ruleSet.outboundValue,
                    context = ConfigRepositoryOutboundSemanticContext(
                        selectorTag = outboundsContext.selectorTag,
                        outbounds = outboundsContext.outbounds,
                        profiles = profiles,
                        nodeTagResolver = outboundsContext.nodeTagResolver
                    )
                )
                addRuleSetDnsRule(DnsRule(ruleSet = listOf(tag)), semantic)
            }

        dnsRuleSetRulesByServer.forEach { (serverTag, rulesForServer) ->
            rulesForServer.forEach { rule ->
                if (serverTag == "fakeip-dns") {
                    dnsRules.addAll(dnsRouteToProxy(rule))
                } else if (serverTag == directServerTag) {
                    dnsRules.add(dnsRouteToDirect(serverTag, rule))
                } else {
                    dnsRules.addAll(dnsRouteToNonDirect(serverTag, rule))
                }
            }
        }
        val packageRulesByServer = linkedMapOf<String, MutableList<DnsRule>>()
        val packageSemantics = mutableListOf<ConfigRepository.OutboundSemantic>()
        val packageBlockRules = mutableListOf<DnsRule>()

        fun addPackageDnsRule(rule: DnsRule, semantic: ConfigRepository.OutboundSemantic) {
            packageSemantics.add(semantic)
            when (semantic) {
                ConfigRepository.OutboundSemantic.Block -> packageBlockRules.add(rule)
                else -> {
                    val serverTag = ConfigRepository.dnsServerTagForSemantic(
                        semantic,
                        settings.fakeDnsEnabled,
                        directServerTag,
                        proxyServerTag
                    ) ?: return
                    packageRulesByServer.getOrPut(serverTag) { mutableListOf() }.add(rule)
                }
            }
        }

        settings.appRules.filter { it.enabled }.forEach { rule ->
            val semantic = ConfigRepository.resolveOutboundSemantic(
                mode = ConfigRepository.resolveAppRuleOutboundMode(rule.outboundMode),
                value = rule.outboundValue,
                context = ConfigRepositoryOutboundSemanticContext(
                    selectorTag = outboundsContext.selectorTag,
                    outbounds = outboundsContext.outbounds,
                    profiles = profiles,
                    nodeTagResolver = outboundsContext.nodeTagResolver
                )
            )
            addPackageDnsRule(DnsRule(packageName = listOf(rule.packageName)), semantic)
        }
        settings.appGroups.filter { it.enabled }.forEach { group ->
            val semantic = ConfigRepository.resolveOutboundSemantic(
                mode = ConfigRepository.resolveAppGroupOutboundMode(group.outboundMode),
                value = group.outboundValue,
                context = ConfigRepositoryOutboundSemanticContext(
                    selectorTag = outboundsContext.selectorTag,
                    outbounds = outboundsContext.outbounds,
                    profiles = profiles,
                    nodeTagResolver = outboundsContext.nodeTagResolver
                )
            )
            group.apps.forEach { addPackageDnsRule(DnsRule(packageName = listOf(it.packageName)), semantic) }
        }

        // We keep both package_name and user_id matching for robustness.
        // (sing-box docs mark user_id as Linux-only, but some Android clients still accept it via platform integration)
        fun resolveUids(pkgs: List<String>): List<Int> {
            return pkgs.mapNotNull {
                try {
                    context.packageManager.getApplicationInfo(it, 0).uid
                } catch (_: Exception) {
                    null
                }
            }.distinct()
        }

        val directPkgs = packageRulesByServer[directServerTag]
            .orEmpty()
            .flatMap { it.packageName.orEmpty() }
            .distinct()
            .filter { it.isNotBlank() }
        val proxyPkgs = packageRulesByServer[proxyServerTag]
            .orEmpty()
            .flatMap { it.packageName.orEmpty() }
            .distinct()
            .filter { it.isNotBlank() }
        val blockPkgs = packageBlockRules
            .flatMap { it.packageName.orEmpty() }
            .distinct()
            .filter { it.isNotBlank() }

        packageRulesByServer.forEach { (serverTag, rulesForServer) ->
            if (serverTag == directServerTag || serverTag == proxyServerTag) {
                return@forEach
            }
            rulesForServer.forEach { rule ->
                dnsRules.addAll(dnsRouteToNonDirect(serverTag, rule))
            }
        }

        if (blockPkgs.isNotEmpty()) {
            dnsRules.add(
                dnsReject(DnsRule(packageName = blockPkgs, userId = resolveUids(blockPkgs)))
            )
        }
        if (proxyPkgs.isNotEmpty()) {
            dnsRules.addAll(
                dnsRouteToProxy(DnsRule(packageName = proxyPkgs, userId = resolveUids(proxyPkgs)))
            )
        }
        if (directPkgs.isNotEmpty()) {
            dnsRules.add(
                dnsRouteToDirect(
                    directServerTag,
                    DnsRule(packageName = directPkgs, userId = resolveUids(directPkgs))
                )
            )
        }
        ConfigRepository.ensureDynamicRemoteDnsServers(
            dnsServers = dnsServers,
            semantics = domainSemantics + ruleSetSemantics + packageSemantics,
            remoteDnsAddr = remoteDnsAddr,
            remoteStrategy = remoteStrategy,
            remoteResolver = remoteResolver
        )

        dnsRules.addAll(ConfigRepository.buildOutboundDomainResolverDnsRules(outboundsContext.outbounds))

        if (settings.fakeDnsEnabled) {
            val fakeIpExcludeDomains = buildList {
                parseDomainList(settings.fakeIpExcludeDomains).forEach { add(it) }
                val defaultExcludes = settings.fakeDnsExcludedDomains
                    .takeIf { it.isNotBlank() }
                    ?.split("\n", "\r")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: AppSettings.DEFAULT_FAKE_DNS_EXCLUDED_DOMAINS
                        .split("\n", "\r")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                defaultExcludes.filter { it !in this }.forEach { add(it) }
            }.distinct()

            if (fakeIpExcludeDomains.isNotEmpty()) {
                dnsRules.add(dnsRouteTo(proxyFinalServerTag, DnsRule(domain = fakeIpExcludeDomains)))
            }
        }
        dnsRules.add(ConfigRepository.buildNonIpDnsFallbackRule(proxyServerTag))
        dnsRules.addAll(ConfigRepository.buildTunFakeIpDnsRulesStatic(settings.fakeDnsEnabled))

        val fakeIpConfig = if (settings.fakeDnsEnabled) {
            ConfigRepository.buildFakeIpConfig(settings.fakeIpRange)
        } else {
            null
        }

        val finalServer = resolveRunDnsFinalServer(
            routingMode = settings.routingMode,
            defaultRule = settings.defaultRule,
            fakeDnsEnabled = settings.fakeDnsEnabled,
            proxyServerTag = proxyServerTag
        )
        val directOverrideDnsServerTags = ConfigRepository.resolveDnsOverrideDirectDnsServerTags(outboundsContext.outbounds, dnsOverride)

        fun sanitizeDnsServer(server: DnsServer): DnsServer {
            return ConfigRepository.sanitizeInjectedDnsServerForRuntime(
                server,
                settings.routingMode,
                proxyDetourTag,
                directOverrideDnsServerTags
            )
        }

        // 追加订阅原始配置中的 DNS servers 和 rules
        if (originalDns != null) {
            originalDns.servers?.forEach { server ->
                if (server.tag != null && dnsServers.none { it.tag == server.tag }) {
                    dnsServers.add(sanitizeDnsServer(server))
                }
            }
            originalDns.rules?.forEach { rule ->
                dnsRules.add(rule)
            }
        }

        val baseDnsConfig = DnsConfig(
            servers = dnsServers,
            rules = dnsRules,
            finalServer = finalServer,
            strategy = resolveDnsStrategy(settings.dnsStrategy, settings.ipVersionMode),
            disableCache = !settings.dnsCacheEnabled,
            independentCache = false,
            fakeip = fakeIpConfig
        )

        return if (dnsOverride != null) {
            ConfigRepository.applyDnsOverride(baseDnsConfig, dnsOverride, ::sanitizeDnsServer)
        } else {
            baseDnsConfig
        }
    }

    protected fun resolveCurrentProxyDnsDetourTag(selectorTag: String, outbounds: List<Outbound>): String {
        fun resolve(tag: String): String {
            val outbound = outbounds.firstOrNull { it.tag == tag } ?: return tag
            return when (outbound.type) {
                "selector" -> resolve(outbound.default ?: outbound.outbounds?.firstOrNull() ?: tag)
                "urltest", "url-test" -> resolve(outbound.outbounds?.firstOrNull() ?: tag)
                else -> tag
            }
        }
        return resolve(selectorTag)
    }

    protected fun buildRunOutbounds(
        baseConfig: SingBoxConfig,
        activeNode: NodeUi?,
        settings: AppSettings,
        allNodes: List<NodeUi>,
        dnsPreResolve: Boolean = false,
        profileId: String? = null,
        dnsOverrideConfig: DnsConfig? = null): ConfigRepositoryRunOutboundsContext {
        val rawOutbounds = baseConfig.outbounds
        if (rawOutbounds.isNullOrEmpty()) {
            Log.w(ConfigRepository.TAG, "No outbounds found in base config, adding defaults")
        }

        val fixedOutbounds = rawOutbounds?.mapNotNull { outbound ->
            var processed = buildOutboundForRuntime(outbound) ?: return@mapNotNull null
            if (dnsPreResolve && profileId != null) {
                val server = processed.server?.trim().orEmpty()
                if (ConfigRepository.shouldApplyDnsPreResolveToDomain(server, dnsOverrideConfig, processed.tag)) {
                    processed = applyDnsResolveToOutbound(profileId, processed)
                } else {
                    Log.d(ConfigRepository.TAG, "Skip DNS pre-resolve for DNS override matched node domain: $server")
                }
            }
            if (singBoxCore.validateOutbound(stripInternalMetadata(processed))) {
                processed
            } else {
                Log.w(ConfigRepository.TAG, "Skipping invalid outbound: ${outbound.tag} (type=${outbound.type})")
                null
            }
        }?.toMutableList() ?: mutableListOf()

        if (fixedOutbounds.none { it.tag == "direct" }) {
            fixedOutbounds.add(Outbound(type = "direct", tag = "direct"))
        }
        val activeProfileId = _activeProfileId.value
        val requiredNodeIds = mutableSetOf<String>()
        val requiredProfileIds = mutableSetOf<String>()

        fun resolveNodeRefToId(value: String?): String? {
            if (value.isNullOrBlank()) return null
            val parts = value.split("::", limit = 2)
            if (parts.size == 2) {
                val refProfileId = parts[0]
                val nodeName = parts[1]
                return allNodes.firstOrNull { it.sourceProfileId == refProfileId && it.name == nodeName }?.id
            }
            if (allNodes.any { it.id == value }) return value
            val node = if (activeProfileId != null) {
                allNodes.firstOrNull { it.sourceProfileId == activeProfileId && it.name == value }
                    ?: allNodes.firstOrNull { it.name == value }
            } else {
                allNodes.firstOrNull { it.name == value }
            }
            return node?.id
        }
        settings.appRules.filter { it.enabled }.forEach { rule ->
            when (rule.outboundMode) {
                RuleSetOutboundMode.NODE -> resolveNodeRefToId(rule.outboundValue)?.let { requiredNodeIds.add(it) }
                RuleSetOutboundMode.PROFILE -> rule.outboundValue?.let { requiredProfileIds.add(it) }
                else -> {}
            }
        }
        settings.appGroups.filter { it.enabled }.forEach { group ->
            when (group.outboundMode) {
                RuleSetOutboundMode.NODE -> resolveNodeRefToId(group.outboundValue)?.let { requiredNodeIds.add(it) }
                RuleSetOutboundMode.PROFILE -> group.outboundValue?.let { requiredProfileIds.add(it) }
                else -> {}
            }
        }
        settings.ruleSets.filter { it.enabled }.forEach { ruleSet ->
            when (ruleSet.outboundMode) {
                RuleSetOutboundMode.NODE -> resolveNodeRefToId(ruleSet.outboundValue)?.let { requiredNodeIds.add(it) }
                RuleSetOutboundMode.PROFILE -> ruleSet.outboundValue?.let { requiredProfileIds.add(it) }
                else -> {}
            }
        }
        settings.customRules.filter { it.enabled }.forEach { rule ->
            when (rule.outboundMode) {
                RuleSetOutboundMode.NODE -> resolveNodeRefToId(rule.outboundValue)?.let { requiredNodeIds.add(it) }
                RuleSetOutboundMode.PROFILE -> rule.outboundValue?.let { requiredProfileIds.add(it) }
                else -> {}
            }
        }
        fixedOutbounds.mapNotNull { it.detour }.forEach { detourValue ->
            resolveNodeRefToId(detourValue)?.let { requiredNodeIds.add(it) }
        }
        activeNode?.let { requiredNodeIds.add(it.id) }
        requiredProfileIds.forEach { requiredProfileId ->
            allNodes.filter { it.sourceProfileId == requiredProfileId }.forEach { node ->
                requiredNodeIds.add(node.id)
            }
        }
        val nodeTagMap = mutableMapOf<String, String>()
        val existingTags = fixedOutbounds.map { it.tag }.toMutableSet()
        Log.d(ConfigRepository.TAG, "buildRunOutbounds: activeProfileId=$activeProfileId, existingTags count=${existingTags.size}")
        Log.d(ConfigRepository.TAG, "  existingTags (first 10): ${existingTags.take(10)}")
        if (activeProfileId != null) {
            val profileNodes = allNodes.filter { it.sourceProfileId == activeProfileId }
            Log.d(ConfigRepository.TAG, "  profileNodes count=${profileNodes.size}")
            profileNodes.forEach { node ->
                if (existingTags.contains(node.name)) {
                    nodeTagMap[node.id] = node.name
                } else {
                    val fuzzyMatch = existingTags.find { it.equals(node.name, ignoreCase = true) }
                    if (fuzzyMatch != null) {
                        nodeTagMap[node.id] = fuzzyMatch
                        Log.w(ConfigRepository.TAG, "  Fuzzy matched node '${node.name}' to tag '$fuzzyMatch'")
                    } else {
                        Log.w(ConfigRepository.TAG, "  WARNING: Node '${node.name}' (id=${node.id.take(8)}) not found in existingTags!")
                    }
                }
            }
        }
        requiredNodeIds.forEach { nodeId ->
            if (nodeTagMap.containsKey(nodeId)) return@forEach

            val node = allNodes.find { it.id == nodeId }
            if (node == null) {
                Log.w(ConfigRepository.TAG, "Cross-profile node not found in allNodes: nodeId=$nodeId")
                return@forEach
            }
            val sourceProfileId = node.sourceProfileId
            if (sourceProfileId == activeProfileId) {
                Log.w(ConfigRepository.TAG, "Cross-profile node belongs to activeProfile but not in outbounds: ${node.name}")
                return@forEach
            }
            val sourceConfig = loadConfig(sourceProfileId)
            if (sourceConfig == null) {
                Log.e(ConfigRepository.TAG, "Failed to load source config for cross-profile node: profileId=$sourceProfileId, nodeName=${node.name}")
                return@forEach
            }
            val sourceOutbound = sourceConfig.outbounds?.find { it.tag == node.name }
                ?: sourceConfig.outbounds?.find { it.tag.equals(node.name, ignoreCase = true) }
                ?: sourceConfig.outbounds?.find {
                    it.tag.replace(ConfigRepository.REGEX_WHITESPACE_DASH, "").equals(
                        node.name.replace(ConfigRepository.REGEX_WHITESPACE_DASH, ""),
                        ignoreCase = true
                    )
                }

            if (sourceOutbound == null) {
                Log.e(ConfigRepository.TAG, "Cross-profile outbound not found: nodeName=${node.name}, profileId=$sourceProfileId, available tags: ${sourceConfig.outbounds?.map { it.tag }?.take(10)}")
                return@forEach
            }
            var fixedSourceOutbound = buildOutboundForRuntime(sourceOutbound)
            if (fixedSourceOutbound == null) {
                Log.w(ConfigRepository.TAG, "Skipping removed outbound type: ${sourceOutbound.type} (${sourceOutbound.tag})")
                return@forEach
            }
            var finalTag = fixedSourceOutbound.tag
            if (existingTags.contains(finalTag)) {
                val suffix = sourceProfileId.take(4)
                finalTag = "${finalTag}_$suffix"
                if (existingTags.contains(finalTag)) {
                    finalTag = "${finalTag}_${java.util.UUID.randomUUID().toString().take(4)}"
                }
                fixedSourceOutbound = fixedSourceOutbound.copy(tag = finalTag)
            }
            if (!singBoxCore.validateOutbound(stripInternalMetadata(fixedSourceOutbound))) {
                Log.w(ConfigRepository.TAG, "Skipping invalid cross-profile outbound: ${node.name} (type=${sourceOutbound.type})")
                return@forEach
            }
            fixedOutbounds.add(fixedSourceOutbound)
            existingTags.add(finalTag)
            nodeTagMap[nodeId] = finalTag
        }
        requiredProfileIds.forEach { requiredProfileId ->
            val profileNodes = allNodes.filter { it.sourceProfileId == requiredProfileId }
            val nodeIds = profileNodes.map { it.id }
            val nodeTags = nodeIds.mapNotNull { nodeTagMap[it] }.distinct()
            val profileName = _profiles.value.find { it.id == requiredProfileId }?.name ?: "Profile_$requiredProfileId"
            val tag = "P:$profileName"
            val selectorDefault = ConfigRepository.resolveProfileSelectorDefault(
                nodeIds = nodeIds,
                nodeTagMap = nodeTagMap,
                rememberedNodeId = getProfileLastSelectedNode(requiredProfileId),
                savedNodeLatencies = savedNodeLatencies
            )

            if (nodeTags.isNotEmpty()) {
                val routeGroupOutbounds = ConfigRepository.buildProfileRouteGroupOutbounds(
                    groupTag = tag,
                    nodeTags = nodeTags
                )
                if (routeGroupOutbounds.isNotEmpty()) {
                    val generatedTags = routeGroupOutbounds.map { it.tag }.toSet()
                    fixedOutbounds.removeAll { it.tag in generatedTags }
                    fixedOutbounds.addAll(0, routeGroupOutbounds)
                }
            }
        }
        val proxyTags = fixedOutbounds.filter {
            it.type in listOf(
                "vless", "vmess", "trojan", "shadowsocks",
                "hysteria2", "hysteria", "anytls", "tuic",
                "wireguard", "ssh", "shadowtls", "http", "socks", "naive"
            )
        }.map { it.tag }.toMutableList()
        val selectorTag = "PROXY"
        if (proxyTags.isEmpty()) {
            proxyTags.add("direct")
        }

        val selectorDefault = activeNode
            ?.let { nodeTagMap[it.id] ?: it.name }
            ?.takeIf { it in proxyTags }
            ?: proxyTags.firstOrNull()
        if (activeNode != null) {
            val mappedTag = nodeTagMap[activeNode.id]
            Log.d(ConfigRepository.TAG, "Selector default: activeNode=${activeNode.name}, id=${activeNode.id}, mappedTag=$mappedTag, selectorDefault=$selectorDefault, inProxyTags=${selectorDefault in proxyTags}")
            if (mappedTag == null && activeNode.name !in proxyTags) {
                Log.w(ConfigRepository.TAG, "WARNING: Active node not in nodeTagMap and name not in proxyTags! Node may not be selected correctly.")
                Log.w(ConfigRepository.TAG, "  Available proxyTags (first 10): ${proxyTags.take(10)}")
                Log.w(ConfigRepository.TAG, "  nodeTagMap keys (first 10): ${nodeTagMap.keys.take(10)}")
            }
        }

        val selectorOutbound = Outbound(
            type = "selector",
            tag = selectorTag,
            outbounds = proxyTags,
            default = selectorDefault,
            interruptExistConnections = true
        )
        val existingProxyIndexes = fixedOutbounds.withIndex()
            .filter { it.value.tag == selectorTag }
            .map { it.index }
        if (existingProxyIndexes.isNotEmpty()) {
            existingProxyIndexes.asReversed().forEach { idx ->
                fixedOutbounds.removeAt(idx)
            }
        }
        fixedOutbounds.add(0, selectorOutbound)
        val nodeTagResolver: (String?) -> String? = { value ->
            if (value.isNullOrBlank()) {
                null
            } else {
                nodeTagMap[value]
                    ?: resolveNodeRefToId(value)?.let { nodeTagMap[it] }
                    ?: if (fixedOutbounds.any { it.tag == value }) value else null
            }
        }

        // Final safety check:
        // 1) Normalize detour node refs to runtime tag
        // 2) Filter out non-existent references in Selector/URLTest
        // 3) Validate detour target exists (or clear detour)
        val detourNormalizedOutbounds = fixedOutbounds.map { outbound ->
            val detourValue = outbound.detour
            if (detourValue.isNullOrBlank()) return@map outbound
            val mappedDetourTag = nodeTagResolver(detourValue)
            if (mappedDetourTag != null && mappedDetourTag != detourValue) {
                outbound.copy(detour = mappedDetourTag)
            } else {
                outbound
            }
        }

        val selectorSafeOutbounds = applySelectorSafeOutbounds(detourNormalizedOutbounds)

        val finalTags = selectorSafeOutbounds.map { it.tag }.toSet()
        val safeOutbounds = selectorSafeOutbounds.map { outbound ->
            val detourTag = outbound.detour
            if (detourTag.isNullOrBlank()) return@map outbound

            val isInvalidDetour = detourTag == outbound.tag || detourTag !in finalTags
            if (isInvalidDetour) {
                Log.w(ConfigRepository.TAG, "Cleared invalid detour for ${outbound.tag}: detour=$detourTag")
                outbound.copy(detour = null)
            } else {
                outbound
            }
        }

        return ConfigRepositoryRunOutboundsContext(
            outbounds = safeOutbounds,
            selectorTag = selectorTag,
            nodeTagResolver = nodeTagResolver,
            nodeTagMap = nodeTagMap
        )
    }

    protected fun applySelectorSafeOutbounds(outbounds: List<Outbound>): List<Outbound> {
        return ConfigRepository.sanitizeSelectorSafeOutbounds(outbounds)
    }

    protected fun buildQuicBlockRule(settings: AppSettings): List<RouteRule> {
        return if (settings.blockQuic) {
            listOf(
                RouteRule(protocolRaw = listOf("quic"), action = "reject")
            )
        } else {
            emptyList()
        }
    }

    protected fun buildBypassLanRules(settings: AppSettings): List<RouteRule> {
        return ConfigRepository.buildBypassLanRulesStatic(settings)
    }

    protected fun buildMulticastRejectRules(settings: AppSettings): List<RouteRule> {
        return ConfigRepository.buildMulticastRejectRulesStatic(settings)
    }

    protected fun buildIcmpEchoRules(settings: AppSettings): List<RouteRule> {
        if (!settings.icmpEchoRoutingEnabled) return emptyList()

        return when (settings.routingMode) {
            RoutingMode.GLOBAL_DIRECT -> listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
            RoutingMode.GLOBAL_PROXY -> {
                Log.w(ConfigRepository.TAG, "ICMP echo proxy outbound is limited; fallback to direct routing")
                listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
            }
            RoutingMode.RULE -> when (settings.defaultRule) {
                DefaultRule.DIRECT -> listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
                DefaultRule.BLOCK -> listOf(RouteRule(networkRaw = listOf("icmp"), action = "reject"))
                DefaultRule.PROXY -> {
                    Log.w(ConfigRepository.TAG, "ICMP echo with PROXY default rule falls back to direct routing")
                    listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
                }
            }
        }
    }

    protected fun buildDefaultRules(settings: AppSettings, selectorTag: String): List<RouteRule> {
        return when (settings.defaultRule) {
            DefaultRule.DIRECT -> listOf(RouteRule(outbound = "direct"))
            DefaultRule.BLOCK -> listOf(RouteRule(action = "reject"))
            DefaultRule.PROXY -> listOf(RouteRule(outbound = selectorTag))
        }
    }

    private fun buildGoogleConnectivityRouteRules(settings: AppSettings, selectorTag: String): List<RouteRule> {
        return if (settings.routingMode == RoutingMode.GLOBAL_DIRECT) {
            emptyList()
        } else {
            listOf(ConfigRepository.buildGoogleConnectivityRouteRule(selectorTag))
        }
    }

    @Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod", "LongParameterList")
    protected fun selectRunRouteRules(
        settings: AppSettings,
        baseRules: List<RouteRule>,
        bypassLanRules: List<RouteRule>,
        customDomainRules: List<RouteRule>,
        appRoutingRules: List<RouteRule>,
        customRuleSetRules: List<RouteRule>,
        defaultRuleCatchAll: List<RouteRule>
    ): List<RouteRule> {
        return when (settings.routingMode) {
            RoutingMode.GLOBAL_PROXY -> baseRules + customRuleSetRules
            RoutingMode.GLOBAL_DIRECT -> baseRules + listOf(RouteRule(outbound = "direct"))
            RoutingMode.RULE -> baseRules + bypassLanRules + customDomainRules + appRoutingRules +
                customRuleSetRules + defaultRuleCatchAll
        }
    }

    protected fun normalizeRunRouteRules(allRules: List<RouteRule>): List<RouteRule> {
        return allRules.map { rule ->
            if (rule.outbound == "block") {
                // sing-box 1.13.0+: "block" outbound removed, use "reject" action
                rule.copy(outbound = null, action = "reject")
            } else if (rule.action == "reject" && !rule.outbound.isNullOrBlank()) {
                rule.copy(outbound = null)
            } else if (!rule.outbound.isNullOrBlank() && rule.action.isNullOrBlank()) {
                rule.copy(action = "route")
            } else {
                rule
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod")
    protected fun buildRunRoute(
        settings: AppSettings,
        selectorTag: String,
        outbounds: List<Outbound>,
        nodeTagResolver: (String?) -> String?,
        validRuleSets: List<RuleSetConfig>
    ): RouteConfig {
        val hasAppRouting = settings.appRules.any { it.enabled } || settings.appGroups.any { it.enabled }

        val profileUis = _profiles.value
        val appRoutingRules = buildAppRoutingRules(
            settings = settings,
            defaultProxyTag = selectorTag,
            outbounds = outbounds,
            profiles = profileUis,
            nodeTagResolver = nodeTagResolver
        )
        val customRuleSetRules = buildCustomRuleSetRules(
            settings = settings,
            defaultProxyTag = selectorTag,
            outbounds = outbounds,
            profiles = profileUis,
            nodeTagResolver = nodeTagResolver,
            validRuleSets = validRuleSets
        )

        val quicRule = buildQuicBlockRule(settings)
        val multicastRejectRules = buildMulticastRejectRules(settings)
        val bypassLanRules = buildBypassLanRules(settings)
        val icmpEchoRules = buildIcmpEchoRules(settings)
        val customDomainRules = buildCustomDomainRules(
            settings = settings,
            defaultProxyTag = selectorTag,
            outbounds = outbounds,
            profiles = profileUis,
            nodeTagResolver = nodeTagResolver
        )
        val defaultRuleCatchAll = buildDefaultRules(settings, selectorTag)
        val hijackDnsRule = ConfigRepository.buildHijackDnsRulesStatic()
        val sniffRule = listOf(RouteRule(inbound = listOf("tun-in", "mixed-in"), action = "sniff"))
        val googleConnectivityRule = buildGoogleConnectivityRouteRules(settings, selectorTag)

        val baseRules = hijackDnsRule + sniffRule + quicRule + multicastRejectRules + icmpEchoRules +
            googleConnectivityRule
        val allRules = selectRunRouteRules(
            settings = settings,
            baseRules = baseRules,
            bypassLanRules = bypassLanRules,
            customDomainRules = customDomainRules,
            appRoutingRules = appRoutingRules,
            customRuleSetRules = customRuleSetRules,
            defaultRuleCatchAll = defaultRuleCatchAll
        )

        val bootstrapStrategy = resolveDnsStrategy(settings.serverAddressStrategy, settings.ipVersionMode)
        val defaultResolverTag = "dns-bootstrap"

        val normalizedRules = normalizeRunRouteRules(allRules)

        return RouteConfig(
            ruleSet = validRuleSets,
            rules = normalizedRules,
            finalOutbound = selectorTag,
            findProcess = hasAppRouting,
            autoDetectInterface = true,
            defaultDomainResolver = DomainResolveConfig(
                server = defaultResolverTag,
                strategy = bootstrapStrategy
            )
        )
    }

    suspend fun getActiveConfig(): SingBoxConfig? = withContext(Dispatchers.IO) {
        val id = _activeProfileId.value ?: return@withContext null
        loadConfig(id)
    }

    fun getConfig(profileId: String): SingBoxConfig? {
        return loadConfig(profileId)
    }

    suspend fun readProfileConfigContent(profileId: String): Result<String> = withContext(Dispatchers.IO) {
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

    suspend fun updateProfileConfigContent(profileId: String, content: String): Result<ProfileUi> =
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

    protected fun resolveDnsStrategy(strategy: DnsStrategy, mode: IpVersionMode): String {
        return mode.resolveDnsStrategy(strategy)
    }

    suspend fun getOutboundByNodeId(nodeId: String): Outbound? = withContext(Dispatchers.IO) {
        val node = _nodes.value.find { it.id == nodeId } ?: return@withContext null
        val config = loadConfig(node.sourceProfileId) ?: return@withContext null
        config.outbounds?.find { it.tag == node.name }
    }

    fun getNodeById(nodeId: String): NodeUi? {
        _nodes.value.find { it.id == nodeId }?.let { return it }
        for ((_, nodes) in profileNodes) {
            nodes.find { it.id == nodeId }?.let { return it }
        }
        _allNodes.value.find { it.id == nodeId }?.let { return it }

        return null
    }

    @Suppress("ReturnCount")
    fun getNodeByName(nodeName: String): NodeUi? {
        _nodes.value.find { it.name == nodeName }?.let { return it }
        for ((_, nodes) in profileNodes) {
            nodes.find { it.name == nodeName }?.let { return it }
        }
        _allNodes.value.find { it.name == nodeName }?.let { return it }

        return null
    }

    fun createNode(
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

    protected fun removeOutboundFromConfig(config: SingBoxConfig, removedTag: String): SingBoxConfig {
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

    suspend fun deleteNode(nodeId: String) = withContext(Dispatchers.IO) {
        val node = getNodeById(nodeId) ?: return@withContext
        val profileId = node.sourceProfileId
        val config = loadConfig(profileId) ?: return@withContext
        val newConfig = removeOutboundFromConfig(config, node.name)
        cacheConfig(profileId, newConfig)
        writeConfigFileOrThrow(profileId, newConfig)

        val immediateNodes = (profileNodes[profileId] ?: _nodes.value)
            .filter { it.id != nodeId && it.name != node.name }
        applyDeletedNodeSnapshot(profileId, nodeId, immediateNodes)

        scope.launch {
            val newNodes = extractNodesFromConfig(newConfig, profileId)
            applyDeletedNodeSnapshot(profileId, nodeId, newNodes)
            saveProfiles()
        }
    }

    protected fun applyDeletedNodeSnapshot(profileId: String, deletedNodeId: String, nodes: List<NodeUi>) {
        profileNodes[profileId] = nodes
        updateAllNodesAndGroups()
        if (_activeProfileId.value != profileId) return

        _nodes.value = nodes
        if (_activeNodeId.value == deletedNodeId) {
            _activeNodeId.value = nodes.firstOrNull()?.id
        }
    }

    suspend fun addSingleNode(
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

    suspend fun renameNode(nodeId: String, newName: String) = withContext(Dispatchers.IO) {
        val node = _nodes.value.find { it.id == nodeId } ?: return@withContext
        val profileId = node.sourceProfileId
        val config = loadConfig(profileId) ?: return@withContext
        val newOutbounds = config.outbounds?.map {
            if (it.tag == node.name) it.copy(tag = newName) else it
        }
        var newConfig = config.copy(outbounds = newOutbounds)
        newConfig = deduplicateTags(newConfig)
        cacheConfig(profileId, newConfig)
        writeConfigFileOrThrow(profileId, newConfig)

        refreshNodesAfterNodeMutation(
            profileId = profileId,
            oldNodeId = nodeId,
            newTag = newName,
            newConfig = newConfig
        )
    }

    suspend fun updateNode(nodeId: String, newOutbound: Outbound) = withContext(Dispatchers.IO) {
        val node = _nodes.value.find { it.id == nodeId } ?: return@withContext
        val profileId = node.sourceProfileId
        val config = loadConfig(profileId) ?: return@withContext
        val newOutbounds = config.outbounds?.map {
            if (it.tag == node.name) newOutbound else it
        }
        var newConfig = config.copy(outbounds = newOutbounds)
        newConfig = deduplicateTags(newConfig)
        cacheConfig(profileId, newConfig)
        writeConfigFileOrThrow(profileId, newConfig)

        refreshNodesAfterNodeMutation(
            profileId = profileId,
            oldNodeId = nodeId,
            newTag = newOutbound.tag,
            newConfig = newConfig
        )
    }

    protected fun refreshNodesAfterNodeMutation(
        profileId: String,
        oldNodeId: String,
        newTag: String,
        newConfig: SingBoxConfig
    ) {
        val oldNodes = profileNodes[profileId] ?: _nodes.value
        val latencyById = oldNodes.associate { it.id to it.latencyMs }
        val updatedNodeId = ConfigRepository.stableNodeId(profileId, newTag)
        val originalLatency = oldNodes.find { it.id == oldNodeId }?.latencyMs
        scope.launch {
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
        }
    }

    protected fun mergeMutatedNodeLatencies(
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

    protected fun applyMutatedActiveNode(
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

    suspend fun exportNode(nodeId: String): String? = withContext(Dispatchers.IO) {
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

    protected fun deduplicateTags(config: SingBoxConfig): SingBoxConfig {
        val outbounds = config.outbounds ?: return config
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

        return config.copy(outbounds = newOutbounds)
    }

    protected fun findAvailablePort(startPort: Int): Int {
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

    fun cleanup() {
        scope.cancel()
        cacheCleanupScheduler.shutdownNow()
        ConfigRepository.nodeIdCache.clear()
        configCache.clear()
        configCacheAccessTimes.clear()
        profileNodes.clear()
        savedNodeLatencies.clear()
        inFlightLatencyTests.clear()
        Log.i(ConfigRepository.TAG, "ConfigRepository cleanup completed")
    }

    protected fun isIpAddress(address: String?): Boolean {
        return ConfigRepository.isIpAddressValue(address)
    }

    companion object {

        internal val TAG = "ConfigRepository"

        internal val ROUTE_GROUP_AUTO_TAG_SUFFIX = "#AUTO"

        internal val ROUTE_GROUP_AUTO_TEST_URL = "https://www.gstatic.com/generate_204"

        internal val googleConnectivityCheckDomains = listOf(
            "connectivitycheck.gstatic.com",
            "www.gstatic.com"
        )

        internal val ROUTE_GROUP_AUTO_TEST_INTERVAL = "10m"

        internal val ROUTE_GROUP_AUTO_TEST_TOLERANCE = 50

        internal val PARALLEL_CONCURRENCY = 8

        internal val SUBSCRIPTION_FAILURE_THRESHOLD = 1

        internal val SUBSCRIPTION_CIRCUIT_BREAKER_WINDOW_MS = 10 * 60 * 1000L

        internal val SUBSCRIPTION_RESPONSE_MAX_BYTES = 1024 * 1024L

        internal val CONFIG_CACHE_EXPIRY_MS = 30 * 60 * 1000L

        internal val CONFIG_CACHE_CLEANUP_INTERVAL_MINUTES = 30L

        internal val REGEX_TRAFFIC = Regex("([\\d.]+)\\s*([KMGTPE]?)B?")

        internal val REGEX_KV_PAIRS =
            Regex("(?i)\\b(upload|download|total|expire)\\b\\s*[:=]\\s*\"?([^,;\\s\\n\\r}]+)\"?")

        internal val REGEX_SUBSCRIPTION_USERINFO = Regex("(?i)subscription[-_]userinfo\\s*[:=]\\s*\"?([^\"\\n\\r]+)\"?")

        internal val REGEX_TOTAL = Regex("TOT:([\\d.]+[KMGTPE]?)B?")

        internal val REGEX_EXPIRE_DATE = Regex("Expires:(\\d{4}-\\d{2}-\\d{2})")

        internal val REGEX_TRAFFIC_VALUE = Regex("([\\d.]+[KMGTPE]?)B?")

        internal val REGEX_REMAINING =
            Regex("(?i)(remaining|balance)\\s*[:=]?\\s*([\\d.]+\\s*[KMGTPE]?)\\s*B?")

        internal val REGEX_EXPIRE = Regex("(?i)(expiry|expires?|expire)\\s*[:=]?\\s*([^\\s,;]+)")

        internal val REGEX_SANITIZE_UUID = Regex("(?i)uuid\\s*[:=]\\s*[^\\\\n]+")

        internal val REGEX_SANITIZE_PASSWORD = Regex("(?i)password\\s*[:=]\\s*[^\\\\n]+")

        internal val REGEX_SANITIZE_TOKEN = Regex("(?i)token\\s*[:=]\\s*[^\\\\n]+")

        internal val REGEX_WHITESPACE_DASH = Regex("[\\s\\-_]")

        internal val REGEX_RULE_SET_JSON_KEYS = Regex("\"(version|rules|rule_set|type|tag|path|url|payload)\"\\s*:")

        internal val REGEX_RULE_SET_TEXT_LINE = Regex(
            "^(payload:|rules:|type:|version:|mode:|tag:|-\\s+|" +
                "[a-z0-9*._-]+\\.[a-z]{2,}|[a-z0-9*._-]+/[a-z0-9*._/-]+|[0-9a-f:.]+/[0-9]{1,3})",
            RegexOption.IGNORE_CASE
        )

        internal val REGEX_RULE_SET_ERROR_TEXT = Regex(
            "^(error|forbidden|not found|404|403|401|429|500|access denied|" +
                "invalid request|too many requests|rate limit|rate limited)\\b",
            RegexOption.IGNORE_CASE
        )

        internal val RULE_SET_BINARY_MAGIC = "SRS"

        internal val RULE_SET_MIN_SIZE_BYTES = 10L

        internal val RULE_SET_SNIFF_BYTES = 512

        internal val RULE_SET_TEXT_PARSE_LIMIT_BYTES = 256 * 1024L

        internal val RULE_SET_IP_THRESHOLD = 0.6

        internal val IP_DNS_QUERY_TYPES = listOf("A", "AAAA")

        internal val NON_IP_DNS_QUERY_TYPES = listOf("HTTPS", "SVCB")

        internal val REGEX_IP_CIDR = Regex("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}" +
            "(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)/([0-9]|[1-2][0-9]|3[0-2])\$")

        internal val REGEX_DOMAIN_LINE = Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\." +
            "[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*\\.[a-zA-Z]{2,}\$")

        internal val TYPE_SAVED_PROFILES_DATA = object : TypeToken<SavedProfilesData>() {}.type

        internal val TYPE_OUTBOUND_LIST = object : TypeToken<List<Outbound>>() {}.type

        internal val MAX_NODE_ID_CACHE_SIZE = 2000

        internal val nodeIdCache: MutableMap<String, String> = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(MAX_NODE_ID_CACHE_SIZE, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                    return size > MAX_NODE_ID_CACHE_SIZE
                }
            }
        )

        internal val REGEX_HTML_SUBSCRIPTION_INPUT = Regex(
            """<input[^>]+id=["']sub_url["'][^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        internal val REGEX_HTML_INPUT_VALUE = Regex(
            """value=["']([^"']+)["']""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        internal val USER_AGENTS = listOf(
            "ClashMeta/1.18.0",
            "Clash.Meta/1.18.0",
            "Clash/1.18.0",
            "sing-box/1.13.1",
            "sing-box/1.13.0",
            "SFA/1.13.0",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        internal val DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG = "dns-bootstrap"

        internal fun shouldActivateCreatedNodeForTest(activeProfileId: String?): Boolean {
            return shouldActivateCreatedNode(activeProfileId)
        }

        internal fun shouldActivateCreatedNode(activeProfileId: String?): Boolean {
            return activeProfileId == null
        }

        internal val CLOUDFLARE_DOH_IPS = setOf(
            "1.1.1.1",
            "1.0.0.1",
            "2606:4700:4700::1111",
            "2606:4700:4700::1001"
        )

        internal var instance: ConfigRepository? = null

        fun stableNodeId(profileId: String, outboundTag: String): String {
            val key = "$profileId|$outboundTag"
            synchronized(nodeIdCache) {
                nodeIdCache[key]?.let { return it }
                val id = UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
                nodeIdCache[key] = id
                return id
            }
        }

        internal fun buildRouteGroupAutoTag(groupTag: String): String {
            return "$groupTag$ROUTE_GROUP_AUTO_TAG_SUFFIX"
        }

        internal fun buildProfileRouteGroupOutboundsForTest(
            groupTag: String,
            nodeTags: List<String>
        ): List<Outbound> {
            return buildProfileRouteGroupOutbounds(
                groupTag = groupTag,
                nodeTags = nodeTags
            )
        }

        internal fun applySelectorSafeOutboundsForTest(outbounds: List<Outbound>): List<Outbound> {
            return sanitizeSelectorSafeOutbounds(outbounds)
        }

        internal fun buildConfigWithOutboundsPreservingProfileSettings(
            existingConfig: SingBoxConfig?,
            outbounds: List<Outbound>
        ): SingBoxConfig {
            return existingConfig?.copy(outbounds = outbounds) ?: SingBoxConfig(outbounds = outbounds)
        }

        internal fun writeTextFileAtomicallyForTest(targetFile: File, content: String) {
            writeTextFileAtomically(targetFile, content)
        }

        internal fun subscriptionResponseMaxBytesForTest(): Long {
            return SUBSCRIPTION_RESPONSE_MAX_BYTES
        }

        internal fun isSubscriptionContentLengthTooLargeForTest(contentLength: Long): Boolean {
            return isSubscriptionContentLengthTooLarge(contentLength)
        }

        internal fun isSubscriptionContentLengthTooLarge(contentLength: Long): Boolean {
            return contentLength > SUBSCRIPTION_RESPONSE_MAX_BYTES
        }

        internal fun readSubscriptionResponseBody(responseBody: ResponseBody): String {
            val contentLength = responseBody.contentLength()
            require(!isSubscriptionContentLengthTooLarge(contentLength)) {
                "Subscription response body is too large: $contentLength bytes"
            }

            val charset = responseBody.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            val output = ByteArrayOutputStream(
                contentLength.takeIf { it in 0..SUBSCRIPTION_RESPONSE_MAX_BYTES }?.toInt() ?: 8192
            )
            var totalBytes = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

            responseBody.byteStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    totalBytes += read
                    require(totalBytes <= SUBSCRIPTION_RESPONSE_MAX_BYTES) {
                        "Subscription response body exceeds $SUBSCRIPTION_RESPONSE_MAX_BYTES bytes"
                    }
                    output.write(buffer, 0, read)
                }
            }

            return output.toString(charset.name())
        }

        internal fun writeTextFileAtomically(targetFile: File, content: String) {
            targetFile.parentFile?.mkdirs()
            val tempFile = createSiblingTempFile(targetFile)

            try {
                tempFile.writeText(content, Charsets.UTF_8)
                moveTempFileIntoPlace(tempFile, targetFile)
            } finally {
                if (tempFile.isFile && !tempFile.delete()) {
                    Log.w(TAG, "Failed to delete config temp file: ${tempFile.absolutePath}")
                }
            }
        }

        internal fun createSiblingTempFile(targetFile: File): File {
            targetFile.parentFile?.mkdirs()
            val prefix = "${targetFile.name.take(64)}.".takeIf { it.length >= 3 } ?: "tmp."
            return File.createTempFile(prefix, ".tmp", targetFile.parentFile)
        }

        internal fun moveTempFileIntoPlace(tempFile: File, targetFile: File) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                moveTempFileWithFileApi(tempFile, targetFile)
                return
            }

            try {
                moveTempFileWithNio(tempFile, targetFile, atomic = true)
            } catch (_: IOException) {
                moveTempFileWithNio(tempFile, targetFile, atomic = false)
            }
        }

        @TargetApi(Build.VERSION_CODES.O)
        private fun moveTempFileWithNio(tempFile: File, targetFile: File, atomic: Boolean) {
            if (atomic) {
                Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } else {
                Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        }

        private fun moveTempFileWithFileApi(tempFile: File, targetFile: File) {
            if (targetFile.exists() && !targetFile.delete()) {
                throw IOException("Failed to delete old config file: ${targetFile.absolutePath}")
            }
            if (tempFile.renameTo(targetFile)) return

            tempFile.copyTo(targetFile, overwrite = true)
            if (!tempFile.delete()) {
                Log.w(TAG, "Failed to delete moved config temp file: ${tempFile.absolutePath}")
            }
        }

        internal fun sanitizeSelectorSafeOutbounds(outbounds: List<Outbound>): List<Outbound> {
            val allOutboundTags = outbounds.map { it.tag }.toSet()
            return outbounds.map { outbound ->
                if (outbound.type == "selector" || outbound.type == "urltest" || outbound.type == "url-test") {
                    sanitizeSelectorLikeOutbound(outbound, allOutboundTags)
                } else {
                    outbound
                }
            }
        }

        internal fun sanitizeSelectorLikeOutbound(outbound: Outbound, allOutboundTags: Set<String>): Outbound {
            val validRefs = outbound.outbounds?.filter { allOutboundTags.contains(it) } ?: emptyList()
            val safeRefs = if (validRefs.isEmpty()) listOf("direct") else validRefs

            if (safeRefs.size != (outbound.outbounds?.size ?: 0)) {
                Log.w(TAG, "Filtered invalid refs in ${outbound.tag}: ${outbound.outbounds} -> $safeRefs")
            }

            return if (outbound.type == "selector") {
                val currentDefault = outbound.default
                val safeDefault = if (currentDefault != null && safeRefs.contains(currentDefault)) {
                    currentDefault
                } else {
                    safeRefs.firstOrNull()
                }
                outbound.copy(outbounds = safeRefs, default = safeDefault)
            } else {
                outbound.copy(outbounds = safeRefs, default = null)
            }
        }

        internal fun buildProfileRouteGroupOutbounds(
            groupTag: String,
            nodeTags: List<String>
        ): List<Outbound> {
            val distinctNodeTags = nodeTags
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            if (distinctNodeTags.isEmpty()) {
                return emptyList()
            }

            val autoTag = buildRouteGroupAutoTag(groupTag)
            return listOf(
                Outbound(
                    type = "urltest",
                    tag = autoTag,
                    outbounds = distinctNodeTags,
                    url = ROUTE_GROUP_AUTO_TEST_URL,
                    interval = ROUTE_GROUP_AUTO_TEST_INTERVAL,
                    tolerance = ROUTE_GROUP_AUTO_TEST_TOLERANCE,
                    interruptExistConnections = false
                ),
                Outbound(
                    type = "selector",
                    tag = groupTag,
                    outbounds = listOf(autoTag, "PROXY"),
                    default = autoTag,
                    interruptExistConnections = false
                )
            )
        }

        internal fun buildBootstrapDnsRules(
            serverAddresses: List<String>,
            bootstrapV4Tag: String,
            bootstrapV6Tag: String,
            bootstrapTag: String
        ): List<DnsRule> {
            val bootstrapDomains = serverAddresses
                .mapNotNull { extractHostFromAddress(it) }
                .map { it.trim() }
                .filter { it.isNotEmpty() && !isIpAddressValue(it) && !it.equals("local", ignoreCase = true) }
                .distinct()

            if (bootstrapDomains.isEmpty()) {
                return emptyList()
            }

            return listOf(
                DnsRule(
                    domain = bootstrapDomains,
                    queryType = listOf("A"),
                    action = "route",
                    server = bootstrapV4Tag
                ),
                DnsRule(
                    domain = bootstrapDomains,
                    queryType = listOf("AAAA"),
                    action = "route",
                    server = bootstrapV6Tag
                ),
                DnsRule(
                    domain = bootstrapDomains,
                    action = "route",
                    server = bootstrapTag
                )
            )
        }

        internal fun isIpAddressValue(address: String?): Boolean {
            if (address.isNullOrBlank()) return false
            return (address.count { it == '.' } == 3 &&
                address.all { it.isDigit() || it == '.' }) ||
                address.contains(":")
        }

        @Suppress("ReturnCount")
        internal fun extractHostFromAddress(address: String): String? {
            val trimmed = address.trim()
            if (trimmed.isEmpty()) return null

            extractHostByUri(trimmed)?.let { return it }
            extractHostByUri("dns://$trimmed")?.let { return it }

            if (trimmed.startsWith("[") && trimmed.contains("]")) {
                return trimmed.substringAfter('[').substringBefore(']')
            }

            val colonCount = trimmed.count { it == ':' }
            if (colonCount == 1 && !trimmed.contains('/')) {
                return trimmed.substringBefore(':').takeIf { it.isNotBlank() }
            }

            return trimmed
        }

        internal fun extractHostByUri(address: String): String? {
            return try {
                val uri = URI(address)
                uri.host
            } catch (_: Exception) {
                null
            }
        }

        internal fun extractSubscriptionUrlFromHtml(html: String): String? {
            return REGEX_HTML_SUBSCRIPTION_INPUT.find(html)
                ?.value
                ?.let { inputTag -> REGEX_HTML_INPUT_VALUE.find(inputTag)?.groupValues?.getOrNull(1) }
                ?.trim()
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }

        @Suppress("UnusedParameter")
        internal fun looksLikeHtmlSubscriptionPage(contentType: String?, body: String): Boolean {
            val trimmed = body.trimStart()
            if (!trimmed.startsWith("<")) {
                return false
            }

            return trimmed.startsWith("<!DOCTYPE html>", ignoreCase = true) ||
                trimmed.startsWith("<html", ignoreCase = true) ||
                trimmed.startsWith("<head", ignoreCase = true) ||
                trimmed.startsWith("<body", ignoreCase = true) ||
                trimmed.startsWith("<meta", ignoreCase = true) ||
                trimmed.startsWith("<title", ignoreCase = true)
        }

        internal fun extractSubscriptionHost(url: String): String? {
            return runCatching { URI(url).host?.lowercase() }.getOrNull()
        }

        internal fun looksLikeSubscriptionUrlForImport(content: String): Boolean {
            val trimmed = content.trim()
            if (!trimmed.startsWith("http://", ignoreCase = true) &&
                !trimmed.startsWith("https://", ignoreCase = true)
            ) {
                return false
            }

            return runCatching {
                val uri = URI(trimmed)
                val path = uri.rawPath.orEmpty()
                val query = uri.rawQuery.orEmpty()
                val hasUserInfo = !uri.userInfo.isNullOrBlank()
                val hasName = !uri.fragment.isNullOrBlank()
                val hasProxyOnlyPort = uri.port > 0 && path.isBlank() && query.isBlank()
                !hasUserInfo && !hasName && !hasProxyOnlyPort
            }.getOrDefault(false)
        }

        internal fun prioritizeUserAgents(preferredUserAgent: String?): List<String> {
            if (preferredUserAgent.isNullOrBlank()) return USER_AGENTS
            return buildList {
                add(preferredUserAgent)
                USER_AGENTS.forEach { userAgent ->
                    if (!userAgent.equals(preferredUserAgent, ignoreCase = true)) {
                        add(userAgent)
                    }
                }
            }
        }

        internal fun buildSubscriptionAttemptUserAgents(
            preferredUserAgent: String?,
            circuitBrokenUserAgents: Set<String>
        ): List<String> {
            return filterCircuitBrokenUserAgents(
                userAgents = prioritizeUserAgents(preferredUserAgent),
                circuitBrokenUserAgents = circuitBrokenUserAgents
            )
        }

        internal fun filterCircuitBrokenUserAgents(
            userAgents: List<String>,
            circuitBrokenUserAgents: Set<String>
        ): List<String> {
            if (circuitBrokenUserAgents.isEmpty()) return userAgents
            val available = userAgents.filterNot { userAgent ->
                circuitBrokenUserAgents.any { blocked ->
                    blocked.equals(userAgent, ignoreCase = true)
                }
            }
            return if (available.isNotEmpty()) available else userAgents
        }

        internal fun shouldRecordSubscriptionNetworkFailure(exception: Exception): Boolean {
            if (exception is ConnectException || exception is SocketTimeoutException) {
                return true
            }
            val message = exception.message.orEmpty().lowercase()
            return "failed to connect" in message || "timeout" in message
        }

        internal fun shouldStopSubscriptionFallback(
            httpStatusCode: Int? = null,
            looksLikeHtmlInfoPage: Boolean = false): Boolean {
            return looksLikeHtmlInfoPage || httpStatusCode == 429
        }

        internal fun resolveSubscriptionUpdateBudgetSeconds(configuredTimeoutSeconds: Int): Long {
            return configuredTimeoutSeconds.takeIf { it > 0 }?.toLong()
                ?: AppSettings().subscriptionUpdateTimeout.toLong()
        }

        internal fun resolveSubscriptionAttemptTimeoutBudget(
            totalBudgetSeconds: Long,
            elapsedMs: Long
        ): SubscriptionAttemptTimeoutBudget? {
            val safeTotalBudgetSeconds = totalBudgetSeconds.coerceAtLeast(1L)
            val remainingMs = (safeTotalBudgetSeconds * 1000L) - elapsedMs
            if (remainingMs <= 0L) return null

            val remainingSeconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
            return SubscriptionAttemptTimeoutBudget(
                connectTimeoutSeconds = remainingSeconds,
                readTimeoutSeconds = remainingSeconds,
                writeTimeoutSeconds = remainingSeconds,
                callTimeoutSeconds = remainingSeconds
            )
        }

        internal fun resolveAppRuleOutboundMode(mode: RuleSetOutboundMode?): RuleSetOutboundMode {
            return mode ?: RuleSetOutboundMode.PROXY
            // 有意设计: 自定义规则通常是代理规则，直连为例外
            // 符合"代理优先"的用户心智模型
        }

        internal fun resolveAppGroupOutboundMode(mode: RuleSetOutboundMode?): RuleSetOutboundMode {
            return mode ?: RuleSetOutboundMode.DIRECT
            // 有意设计: AppGroup 主要用于需要直连的本地应用（游戏、支付等）
            // 如需代理，用户应显式配置
        }

        internal fun resolveRuleSetOutboundMode(mode: RuleSetOutboundMode?): RuleSetOutboundMode {
            return mode ?: RuleSetOutboundMode.PROXY
        }

        internal fun resolveCustomRuleOutboundMode(
            mode: RuleSetOutboundMode?,
            oldOutbound: OutboundTag
        ): RuleSetOutboundMode {
            if (mode != null) return mode
            return when (oldOutbound) {
                OutboundTag.DIRECT -> RuleSetOutboundMode.DIRECT
                OutboundTag.PROXY -> RuleSetOutboundMode.PROXY
                OutboundTag.BLOCK -> RuleSetOutboundMode.BLOCK
            }
        }

        internal fun resolveRouteModeForRuleSetForTest(ruleSet: RuleSet): RuleSetOutboundMode {
            return resolveRuleSetOutboundMode(ruleSet.outboundMode)
        }

        internal fun resolveRouteModeForAppGroupForTest(group: AppGroup): RuleSetOutboundMode {
            return resolveAppGroupOutboundMode(group.outboundMode)
        }

        internal fun resolveRouteModeForCustomRuleForTest(rule: CustomRule): RuleSetOutboundMode {
            return resolveCustomRuleOutboundMode(rule.outboundMode, rule.outbound)
        }

        internal fun filterAppliedRemoteRuleSets(
            ruleSets: List<RuleSet>,
            validTags: Set<String>
        ): List<RuleSet> {
            return ruleSets.filter { ruleSet ->
                ruleSet.enabled && ruleSet.type == RuleSetType.REMOTE && ruleSet.tag in validTags
            }
        }

        internal fun filterAppliedRemoteRuleSetsForTest(
            ruleSets: List<RuleSet>,
            validTags: Set<String>
        ): List<RuleSet> {
            return filterAppliedRemoteRuleSets(ruleSets, validTags)
        }

        internal fun detectRuleSetRuleTypeForTest(
            file: java.io.File,
            tag: String = ""
        ): ConfigRepository.RuleSetRuleType {
            return detectRuleSetRuleTypeFromFile(file, tag)
        }

        fun detectRuleSetRuleTypeStatic(
            file: java.io.File,
            tag: String = ""
        ): ConfigRepository.RuleSetRuleType {
            return detectRuleSetRuleTypeFromFile(file, tag)
        }

        internal fun detectRuleSetRuleTypeFromFile(
            file: java.io.File,
            tag: String = ""
        ): ConfigRepository.RuleSetRuleType {
            val tagRuleType = detectRuleSetRuleTypeFromTag(tag)
            if (tagRuleType != ConfigRepository.RuleSetRuleType.UNKNOWN) return tagRuleType
            if (!file.exists() || file.length() < RULE_SET_MIN_SIZE_BYTES) {
                return ConfigRepository.RuleSetRuleType.UNKNOWN
            }
            return try {
                val sample = readRuleSetSampleFromFile(file)
                detectRuleSetRuleTypeFromSample(sample)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to detect rule set type: ${file.name}", e)
                ConfigRepository.RuleSetRuleType.UNKNOWN
            }
        }

        internal fun detectRuleSetRuleTypeFromTag(tag: String): ConfigRepository.RuleSetRuleType {
            val normalizedTag = tag.trim().lowercase()
            return when {
                normalizedTag.startsWith("geosite-") || normalizedTag.contains("geosite") -> ConfigRepository.RuleSetRuleType.DOMAIN
                normalizedTag.startsWith("geoip-") || normalizedTag.contains("geoip") -> ConfigRepository.RuleSetRuleType.IP
                else -> ConfigRepository.RuleSetRuleType.UNKNOWN
            }
        }

        internal fun detectRuleSetRuleTypeFromSample(sample: ByteArray): ConfigRepository.RuleSetRuleType {
            if (sample.isEmpty()) return ConfigRepository.RuleSetRuleType.UNKNOWN
            if (!isLikelyTextRuleSetFromBytes(sample)) return ConfigRepository.RuleSetRuleType.UNKNOWN
            return detectRuleTypeFromTextContent(sample.toString(Charsets.UTF_8))
        }

        internal fun detectRuleTypeFromTextContent(text: String): ConfigRepository.RuleSetRuleType {
            val lines = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
                .take(100)
                .toList()

            if (lines.isEmpty()) return ConfigRepository.RuleSetRuleType.UNKNOWN

            var ipLineCount = 0
            var domainLineCount = 0

            for (line in lines) {
                when {
                    isIpRuleLineContent(line) -> ipLineCount++
                    isDomainRuleLineContent(line) -> domainLineCount++
                }
            }

            val total = ipLineCount + domainLineCount
            if (total == 0) return ConfigRepository.RuleSetRuleType.UNKNOWN

            val ipRatio = ipLineCount.toFloat() / total
            val domainRatio = domainLineCount.toFloat() / total

            return when {
                ipRatio >= RULE_SET_IP_THRESHOLD -> ConfigRepository.RuleSetRuleType.IP
                domainRatio >= RULE_SET_IP_THRESHOLD -> ConfigRepository.RuleSetRuleType.DOMAIN
                ipRatio > 0 && domainRatio > 0 -> ConfigRepository.RuleSetRuleType.MIXED
                else -> ConfigRepository.RuleSetRuleType.UNKNOWN
            }
        }

        internal fun isIpRuleLineContent(line: String): Boolean {
            if (REGEX_IP_CIDR.matches(line)) return true
            if (isLikelyIpv6Cidr(line)) return true
            return isIpRuleWithPrefix(line)
        }

        internal fun isLikelyIpv6Cidr(line: String): Boolean {
            if (!line.contains("/") || !line.contains(":") || line.contains(".")) return false
            val ipPart = line.substringBefore("/")
            return !ipPart.contains(" ") && ipPart.length <= 45 && ipPart.count { it == ':' } >= 1
        }

        internal fun isIpRuleWithPrefix(line: String): Boolean {
            val prefixes = listOf("ip-cidr:", "ip:", "geoip:")
            for (prefix in prefixes) {
                if (line.startsWith(prefix, ignoreCase = true)) {
                    val content = line.removePrefix(prefix).trim()
                    if (REGEX_IP_CIDR.matches(content)) return true
                    if (content.contains(":") && content.contains("/") && !content.contains(".")) return true
                }
            }
            return false
        }

        internal fun isDomainRuleLineContent(line: String): Boolean {
            if (REGEX_DOMAIN_LINE.matches(line)) {
                return true
            }
            val domainPrefixes = listOf("domain:", "geosite:", "domain-keyword:", "domain-suffix:", "domain-regex:")
            for (prefix in domainPrefixes) {
                if (line.startsWith(prefix, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }

        internal fun readRuleSetSampleFromFile(file: java.io.File): ByteArray {
            return file.inputStream().use { input ->
                val buffer = ByteArray(RULE_SET_SNIFF_BYTES)
                val read = input.read(buffer)
                if (read > 0) buffer.copyOf(read) else ByteArray(0)
            }
        }

        internal fun isLikelyTextRuleSetFromBytes(sample: ByteArray): Boolean {
            if (sample.any { it == 0.toByte() }) return false
            val printableBytes = sample.count { byte ->
                val code = byte.toInt() and 0xff
                code == 9 || code == 10 || code == 13 || code in 32..126
            }
            return printableBytes >= sample.size * 3 / 4
        }

        internal fun launchSubscriptionDnsPreResolve(
            scope: CoroutineScope,
            profileId: String,
            enabled: Boolean,
            updateRunId: Long? = null,
            onStarted: () -> Unit = {},
            onFinished: () -> Unit = {},
            preResolve: suspend () -> Boolean
        ): Job? {
            if (!enabled) {
                return null
            }

            val updateRunSuffix = updateRunId?.let { ", run=$it" }.orEmpty()

            Log.d(
                TAG,
                "Subscription update main flow finished for profile $profileId; " +
                    "scheduling background DNS pre-resolve$updateRunSuffix"
            )
            return scope.launch {
                onStarted()
                Log.d(TAG, "Background DNS pre-resolve started for profile $profileId$updateRunSuffix")
                val success = runCatching { preResolve() }
                    .getOrElse { e ->
                        Log.w(TAG, "Background DNS pre-resolve crashed for profile $profileId$updateRunSuffix", e)
                        false
                    }
                if (success) {
                    Log.d(TAG, "Background DNS pre-resolve completed for profile $profileId$updateRunSuffix")
                } else {
                    Log.d(TAG, "Background DNS pre-resolve failed for profile $profileId$updateRunSuffix")
                }
                onFinished()
            }
        }

        internal fun setProfileUpdateStageIfCurrent(
            profilesState: MutableStateFlow<List<ProfileUi>>,
            activeUpdateRuns: Map<String, Long>,
            profileId: String,
            runId: Long,
            stage: SubscriptionUpdateStage?
        ) {
            profilesState.update { profiles ->
                if (activeUpdateRuns[profileId] != runId) {
                    return@update profiles
                }
                profiles.map { profile ->
                    if (profile.id == profileId) profile.copy(updateStage = stage) else profile
                }
            }
        }

        internal fun resolveSubscriptionUpdateStage(
            stageName: String?
        ): SubscriptionUpdateStage? {
            return when (stageName) {
                "requesting" -> SubscriptionUpdateStage.Requesting
                "parsing" -> SubscriptionUpdateStage.Parsing
                "saving" -> SubscriptionUpdateStage.Saving
                "dns_background" -> SubscriptionUpdateStage.DnsBackground
                else -> null
            }
        }

        internal fun toRouteRule(semantic: ConfigRepository.OutboundSemantic, selectorTag: String): RouteRule {
            return when (semantic) {
                ConfigRepository.OutboundSemantic.Direct -> RouteRule(outbound = "direct")
                ConfigRepository.OutboundSemantic.Block -> RouteRule(action = "reject")
                ConfigRepository.OutboundSemantic.Proxy -> RouteRule(outbound = selectorTag)
                is ConfigRepository.OutboundSemantic.RouteTag -> RouteRule(outbound = semantic.tag)
                is ConfigRepository.OutboundSemantic.FallbackProxy -> RouteRule(outbound = semantic.tag)
            }
        }

        internal fun buildRunRouteRulesForTest(
            settings: AppSettings,
            selectorTag: String,
            outbounds: List<Outbound>,
            profiles: List<ProfileEntity>,
            validRuleSets: List<RuleSetConfig>,
            nodeTagResolver: (String?) -> String? = { null }): List<RouteRule> {
            val profileUis = profiles.map { it.toUiModel() }
            return buildRunRouteRules(
                settings = settings,
                selectorTag = selectorTag,
                outbounds = outbounds,
                profiles = profileUis,
                nodeTagResolver = nodeTagResolver,
                validRuleSets = validRuleSets
            )
        }

        internal fun resolveDnsStrategyForTest(strategy: DnsStrategy, mode: IpVersionMode): String {
            return mode.resolveDnsStrategy(strategy)
        }

        internal fun buildBypassLanRulesForTest(settings: AppSettings): List<RouteRule> {
            return buildBypassLanRulesStatic(settings)
        }

        internal fun buildMulticastRejectRulesForTest(settings: AppSettings): List<RouteRule> {
            return buildMulticastRejectRulesStatic(settings)
        }

        internal fun buildHijackDnsRulesForTest(): List<RouteRule> {
            return buildHijackDnsRulesStatic()
        }

        internal fun buildHijackDnsRulesStatic(): List<RouteRule> {
            return listOf(
                RouteRule(inbound = listOf("tun-in"), port = listOf(53), action = "hijack-dns"),
                RouteRule(protocolRaw = listOf("dns"), action = "hijack-dns"),
                RouteRule(port = listOf(853), action = "reject")
            )
        }

        @Suppress("LongParameterList")
        internal fun buildRunRouteRules(
            settings: AppSettings,
            selectorTag: String,
            outbounds: List<Outbound>,
            profiles: List<ProfileUi>,
            nodeTagResolver: (String?) -> String?,
            validRuleSets: List<RuleSetConfig>
        ): List<RouteRule> {
            val customRuleSetRules = buildCustomRuleSetRulesStatic(
                settings = settings,
                defaultProxyTag = selectorTag,
                outbounds = outbounds,
                profiles = profiles,
                nodeTagResolver = nodeTagResolver,
                validRuleSets = validRuleSets
            )
            val quicRule = buildQuicBlockRuleStatic(settings)
            val multicastRejectRules = buildMulticastRejectRulesStatic(settings)
            val bypassLanRules = buildBypassLanRulesStatic(settings)
            val icmpEchoRules = buildIcmpEchoRulesStatic(settings)
            val defaultRuleCatchAll = buildDefaultRulesStatic(settings, selectorTag)
            val hijackDnsRule = buildHijackDnsRulesStatic()
            val sniffRule = listOf(RouteRule(inbound = listOf("tun-in", "mixed-in"), action = "sniff"))
            val googleConnectivityRule = listOf(buildGoogleConnectivityRouteRule(selectorTag))

            return when (settings.routingMode) {
                RoutingMode.GLOBAL_PROXY ->
                    hijackDnsRule + sniffRule + quicRule + multicastRejectRules + icmpEchoRules +
                        googleConnectivityRule + customRuleSetRules
                RoutingMode.GLOBAL_DIRECT ->
                    hijackDnsRule + sniffRule + quicRule + multicastRejectRules + icmpEchoRules +
                        listOf(RouteRule(outbound = "direct"))
                RoutingMode.RULE -> {
                    hijackDnsRule + sniffRule + quicRule + multicastRejectRules + bypassLanRules + icmpEchoRules +
                        googleConnectivityRule + customRuleSetRules + defaultRuleCatchAll
                }
            }
        }

        internal fun buildQuicBlockRuleStatic(settings: AppSettings): List<RouteRule> {
            return if (settings.blockQuic) {
                listOf(RouteRule(protocolRaw = listOf("quic"), action = "reject"))
            } else {
                emptyList()
            }
        }

        internal fun buildIcmpEchoRulesStatic(settings: AppSettings): List<RouteRule> {
            if (!settings.icmpEchoRoutingEnabled) return emptyList()

            return when (settings.routingMode) {
                RoutingMode.GLOBAL_DIRECT -> listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
                RoutingMode.GLOBAL_PROXY -> listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
                RoutingMode.RULE -> when (settings.defaultRule) {
                    DefaultRule.DIRECT -> listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
                    DefaultRule.BLOCK -> listOf(RouteRule(networkRaw = listOf("icmp"), action = "reject"))
                    DefaultRule.PROXY -> listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
                }
            }
        }

        internal fun buildDefaultRulesStatic(settings: AppSettings, selectorTag: String): List<RouteRule> {
            return when (settings.defaultRule) {
                DefaultRule.DIRECT -> listOf(RouteRule(outbound = "direct"))
                DefaultRule.BLOCK -> listOf(RouteRule(action = "reject"))
                DefaultRule.PROXY -> listOf(RouteRule(outbound = selectorTag))
            }
        }

        internal fun buildGoogleConnectivityRouteRule(selectorTag: String): RouteRule {
            return RouteRule(
                domain = googleConnectivityCheckDomains,
                outbound = selectorTag
            )
        }

        internal fun buildGoogleConnectivityRouteRuleForTest(selectorTag: String): RouteRule {
            return buildGoogleConnectivityRouteRule(selectorTag)
        }

        internal fun buildCustomRuleSetRulesStatic(
            settings: AppSettings,
            defaultProxyTag: String,
            outbounds: List<Outbound>,
            profiles: List<ProfileUi>,
            nodeTagResolver: (String?) -> String? = { null },
            validRuleSets: List<RuleSetConfig>
        ): List<RouteRule> {
            val rules = mutableListOf<RouteRule>()
            val validTags = validRuleSets.mapNotNull { it.tag }.toSet()
            val sortedRuleSets = sortRuleSetsForDnsAndRoutePriority(
                settings.ruleSets.filter { it.enabled && it.tag in validTags }
            )

            sortedRuleSets.forEach { ruleSet ->
                val semantic = resolveOutboundSemantic(
                    mode = resolveRuleSetOutboundMode(ruleSet.outboundMode),
                    value = ruleSet.outboundValue,
                    context = ConfigRepositoryOutboundSemanticContext(
                        selectorTag = defaultProxyTag,
                        outbounds = outbounds,
                        profiles = profiles,
                        nodeTagResolver = nodeTagResolver
                    )
                )
                val baseRule = toRouteRule(semantic, defaultProxyTag)
                val inboundTags = ruleSet.inbounds?.takeIf { it.isNotEmpty() }?.map {
                    when (it) {
                        "tun" -> "tun-in"
                        "mixed" -> "mixed-in"
                        else -> it
                    }
                }

                rules.add(
                    baseRule.copy(
                        ruleSet = listOf(ruleSet.tag),
                        inbound = inboundTags
                    )
                )
            }

            return rules
        }

        internal fun buildBypassLanRulesStatic(settings: AppSettings): List<RouteRule> {
            return if (settings.bypassLan) {
                listOf(RouteRule(ipIsPrivate = true, outbound = "direct"))
            } else {
                emptyList()
            }
        }

        internal fun buildMulticastRejectRulesStatic(settings: AppSettings): List<RouteRule> {
            val cidrs = mutableListOf<String>()
            if (settings.ipVersionMode.includesIpv4) cidrs.add("224.0.0.0/3")
            if (settings.ipVersionMode.includesIpv6) cidrs.add("ff00::/8")
            return if (cidrs.isEmpty()) {
                emptyList()
            } else {
                listOf(RouteRule(ipCidr = cidrs, action = "reject"))
            }
        }

        internal fun resolveOutboundSemantic(
            mode: RuleSetOutboundMode?,
            value: String?,
            context: ConfigRepositoryOutboundSemanticContext
        ): ConfigRepository.OutboundSemantic {
            val selectorTag = context.selectorTag
            val outbounds = context.outbounds
            val profiles = context.profiles
            val nodeTagResolver = context.nodeTagResolver
            return when (mode ?: RuleSetOutboundMode.PROXY) {
                RuleSetOutboundMode.DIRECT -> ConfigRepository.OutboundSemantic.Direct
                RuleSetOutboundMode.BLOCK -> ConfigRepository.OutboundSemantic.Block
                RuleSetOutboundMode.PROXY -> ConfigRepository.OutboundSemantic.Proxy
                RuleSetOutboundMode.NODE -> {
                    val resolvedTag = nodeTagResolver(value)
                    if (resolvedTag != null) {
                        ConfigRepository.OutboundSemantic.RouteTag(resolvedTag)
                    } else {
                        Log.w(TAG, "Node ID '$value' not resolved to any tag, falling back to $selectorTag")
                        ConfigRepository.OutboundSemantic.FallbackProxy(selectorTag)
                    }
                }
                RuleSetOutboundMode.PROFILE -> {
                    val profileId = value
                    if (profileId.isNullOrBlank()) {
                        Log.w(TAG, "Profile ID is null or blank, falling back to $selectorTag")
                        return ConfigRepository.OutboundSemantic.FallbackProxy(selectorTag)
                    }
                    val profileName = profiles.find { it.id == profileId }?.name
                    if (profileName == null) {
                        Log.w(TAG, "Profile with ID '$profileId' not found, falling back to $selectorTag")
                        return ConfigRepository.OutboundSemantic.FallbackProxy(selectorTag)
                    }
                    val tag = "P:$profileName"
                    if (outbounds.any { it.tag == tag }) {
                        ConfigRepository.OutboundSemantic.RouteTag(tag)
                    } else {
                        Log.w(TAG, "Profile selector tag '$tag' not found in outbounds, falling back to $selectorTag")
                        ConfigRepository.OutboundSemantic.FallbackProxy(selectorTag)
                    }
                }
            }
        }

        internal fun toRouteRuleForTest(semantic: ConfigRepository.OutboundSemantic, selectorTag: String): RouteRule {
            return toRouteRule(semantic, selectorTag)
        }

        internal fun resolveOutboundSemanticForTest(input: OutboundSemanticTestInput): ConfigRepository.OutboundSemantic {
            val profileUis = input.profiles.map { it.toUiModel() }
            return resolveOutboundSemantic(
                input.mode,
                input.value,
                ConfigRepositoryOutboundSemanticContext(
                    input.selectorTag,
                    input.outbounds,
                    profileUis,
                    input.nodeTagResolver
                )
            )
        }

        internal fun resolveProfileSelectorDefault(
            nodeIds: List<String>,
            nodeTagMap: Map<String, String>,
            rememberedNodeId: String?,
            savedNodeLatencies: Map<String, Long>
        ): String? {
            val candidateTags = nodeIds.mapNotNull { nodeTagMap[it] }.distinct()
            val bestLatencyTag = nodeIds.asSequence()
                .mapNotNull { nodeId ->
                    val tag = nodeTagMap[nodeId] ?: return@mapNotNull null
                    val latency = savedNodeLatencies[nodeId] ?: return@mapNotNull null
                    if (latency <= 0) {
                        return@mapNotNull null
                    }
                    tag to latency
                }
                .minByOrNull { it.second }
                ?.first

            val rememberedTag = rememberedNodeId
                ?.let { nodeTagMap[it] }
                ?.takeIf { it in candidateTags }

            return when {
                candidateTags.isEmpty() -> null
                bestLatencyTag != null -> bestLatencyTag
                rememberedTag != null -> rememberedTag
                else -> candidateTags.firstOrNull()
            }
        }

        internal fun buildDynamicDnsServerTag(detourTag: String): String {
            val normalized = detourTag
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifBlank { "tag" }
                .take(24)
            val hash = detourTag.toByteArray(Charsets.UTF_8)
                .fold(0x811c9dc5.toInt()) { acc, byte ->
                    (acc xor byte.toInt()) * 0x01000193
                }
                .toUInt()
                .toString(16)
                .padStart(8, '0')
            return "dns-remote-$normalized-$hash"
        }

        internal fun ensureDynamicRemoteDnsServers(
            dnsServers: MutableList<DnsServer>,
            semantics: List<ConfigRepository.OutboundSemantic>,
            remoteDnsAddr: String,
            remoteStrategy: String?,
            remoteResolver: DomainResolveConfig?
        ) {
            semantics.asSequence()
                .filterIsInstance<ConfigRepository.OutboundSemantic.RouteTag>()
                .map { it.tag }
                .distinct()
                .forEach { detourTag ->
                    val serverTag = buildDynamicDnsServerTag(detourTag)
                    if (dnsServers.none { it.tag == serverTag }) {
                        dnsServers.add(
                            buildDynamicRemoteDnsServer(
                                detourTag = detourTag,
                                remoteDnsAddr = remoteDnsAddr,
                                remoteStrategy = remoteStrategy,
                                remoteResolver = remoteResolver
                            )
                        )
                    }
                }
        }

        internal fun buildDynamicDnsServersForTest(
            semantics: List<ConfigRepository.OutboundSemantic>,
            remoteDnsAddr: String,
            remoteStrategy: String?,
            remoteResolver: DomainResolveConfig?
        ): List<DnsServer> {
            val servers = mutableListOf<DnsServer>()
            ensureDynamicRemoteDnsServers(servers, semantics, remoteDnsAddr, remoteStrategy, remoteResolver)
            return servers
        }

        internal fun buildDynamicRemoteDnsServerForTest(
            detourTag: String,
            remoteDnsAddr: String,
            remoteStrategy: String?,
            remoteResolver: DomainResolveConfig?
        ): DnsServer {
            return buildDynamicRemoteDnsServer(
                detourTag = detourTag,
                remoteDnsAddr = remoteDnsAddr,
                remoteStrategy = remoteStrategy,
                remoteResolver = remoteResolver
            )
        }

        internal fun buildDynamicRemoteDnsServer(
            detourTag: String,
            remoteDnsAddr: String,
            remoteStrategy: String?,
            remoteResolver: DomainResolveConfig?
        ): DnsServer {
            return buildDnsServer(
                address = remoteDnsAddr,
                tag = buildDynamicDnsServerTag(detourTag),
                detour = detourTag,
                domainStrategy = remoteStrategy,
                domainResolver = remoteResolver
            )
        }

        internal fun resolveActiveEchDnsServerForTest(activeTag: String, outbounds: List<Outbound>): String? {
            return resolveActiveEchDnsServer(activeTag, outbounds)
        }

        internal fun resolveActiveEchDnsServer(activeTag: String, outbounds: List<Outbound>): String? {
            val activeOutbound = outbounds.firstOrNull { it.tag == activeTag }
            val activeDnsServer = activeOutbound
                ?.tls
                ?.ech
                ?.dnsServer
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            if (activeDnsServer != null) return activeDnsServer

            if (activeOutbound?.tls?.ech?.enabled != true) return null

            val candidates = outbounds
                .mapNotNull { it.tls?.ech?.dnsServer?.trim()?.takeIf(String::isNotBlank) }
                .distinct()
            return candidates.singleOrNull()
        }

        internal fun needsLegacyEchDnsRepairForTest(config: SingBoxConfig): Boolean {
            return needsLegacyEchDnsRepair(config)
        }

        internal fun needsLegacyEchDnsRepair(config: SingBoxConfig): Boolean {
            return config.outbounds.orEmpty().any { outbound ->
                val ech = outbound.tls?.ech
                val hasEch = ech?.enabled == true ||
                    !ech?.queryServerName.isNullOrBlank() ||
                    !ech?.config.isNullOrEmpty()
                hasEch && ech?.dnsServer.isNullOrBlank() && ech?.config.isNullOrEmpty()
            }
        }

        internal fun resolveFakeIpRanges(fakeIpRange: String?): ConfigRepositoryFakeIpRanges {
            val ranges = fakeIpRange.orEmpty()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val inet4Range = ranges.firstOrNull { it.contains(".") } ?: "198.18.0.0/15"
            val inet6Range = ranges.firstOrNull { it.contains(":") } ?: "fc00::/18"
            return ConfigRepositoryFakeIpRanges(inet4Range = inet4Range, inet6Range = inet6Range)
        }

        internal fun buildFakeIpDnsServer(fakeIpRange: String?): DnsServer {
            val ranges = resolveFakeIpRanges(fakeIpRange)
            return DnsServer(
                tag = "fakeip-dns",
                type = "fakeip",
                inet4Range = ranges.inet4Range,
                inet6Range = ranges.inet6Range
            )
        }

        internal fun buildFakeIpDnsServerForTest(fakeIpRange: String?): DnsServer {
            return buildFakeIpDnsServer(fakeIpRange)
        }

        internal fun buildFakeIpConfig(fakeIpRange: String?): DnsFakeIpConfig {
            val ranges = resolveFakeIpRanges(fakeIpRange)
            return DnsFakeIpConfig(
                enabled = true,
                inet4Range = ranges.inet4Range,
                inet6Range = ranges.inet6Range
            )
        }

        internal fun dnsServerTagForSemantic(
            semantic: ConfigRepository.OutboundSemantic,
            fakeDnsEnabled: Boolean,
            directServerTag: String = "local",
            proxyServerTag: String = if (fakeDnsEnabled) "fakeip-dns" else "remote"): String? {
            return when (semantic) {
                ConfigRepository.OutboundSemantic.Direct -> directServerTag
                ConfigRepository.OutboundSemantic.Block -> {
                    Log.d(TAG, "DNS rule for Block semantic, skipping DNS server assignment")
                    null
                }
                ConfigRepository.OutboundSemantic.Proxy -> proxyServerTag
                is ConfigRepository.OutboundSemantic.FallbackProxy -> proxyServerTag
                is ConfigRepository.OutboundSemantic.RouteTag -> buildDynamicDnsServerTag(semantic.tag)
            }
        }

        internal fun dnsServerTagForSemanticForTest(
            semantic: ConfigRepository.OutboundSemantic,
            fakeDnsEnabled: Boolean,
            directServerTag: String = "local",
            proxyServerTag: String = if (fakeDnsEnabled) "fakeip-dns" else "remote"): String? {
            return dnsServerTagForSemantic(semantic, fakeDnsEnabled, directServerTag, proxyServerTag)
        }

        internal fun resolveDnsServerTagForRuleSemanticForTest(
            semantic: ConfigRepository.OutboundSemantic,
            fakeDnsEnabled: Boolean,
            directServerTag: String = "local",
            proxyServerTag: String = if (fakeDnsEnabled) "fakeip-dns" else "remote"): String? {
            return when (semantic) {
                ConfigRepository.OutboundSemantic.Direct -> directServerTag
                ConfigRepository.OutboundSemantic.Block -> null
                ConfigRepository.OutboundSemantic.Proxy -> proxyServerTag
                is ConfigRepository.OutboundSemantic.FallbackProxy -> proxyServerTag
                is ConfigRepository.OutboundSemantic.RouteTag -> buildDynamicDnsServerTag(semantic.tag)
            }
        }

        internal fun buildDnsRouteToProxyForTest(
            fakeDnsEnabled: Boolean,
            proxyServerTag: String,
            rule: DnsRule
        ): List<DnsRule> {
            fun dnsRouteTo(server: String, currentRule: DnsRule): DnsRule =
                currentRule.copy(action = "route", server = server)

            if (!fakeDnsEnabled) {
                return listOf(dnsRouteTo(proxyServerTag, rule))
            }
            return listOf(dnsRouteTo(proxyServerTag, rule.copy(queryType = IP_DNS_QUERY_TYPES)))
        }

        internal fun buildDnsRouteToNonDirectForTest(
            fakeDnsEnabled: Boolean,
            serverTag: String,
            rule: DnsRule
        ): List<DnsRule> {
            return buildDnsRouteToNonDirect(fakeDnsEnabled, serverTag, rule)
        }

        internal fun buildNonIpDnsFallbackRuleForTest(serverTag: String): DnsRule {
            return buildNonIpDnsFallbackRule(serverTag)
        }

        internal fun buildDnsRouteToDirectForTest(rule: DnsRule): DnsRule {
            return buildDnsRouteToDirect("local", "local", rule)
        }

        internal fun sortRuleSetsForDnsAndRoutePriorityForTest(ruleSets: List<RuleSet>): List<RuleSet> {
            return sortRuleSetsForDnsAndRoutePriority(ruleSets)
        }

        internal fun buildGoogleConnectivityDnsRulesForTest(
            fakeDnsEnabled: Boolean,
            proxyServerTag: String
        ): List<DnsRule> {
            return buildGoogleConnectivityDnsRules(fakeDnsEnabled, proxyServerTag)
        }

        internal fun buildQuicBlockRuleForTest(settings: AppSettings): List<RouteRule> {
            return if (settings.blockQuic) {
                listOf(
                    RouteRule(protocolRaw = listOf("quic"), action = "reject")
                )
            } else {
                emptyList()
            }
        }

        internal fun buildTunFakeIpDnsRulesForTest(fakeDnsEnabled: Boolean): List<DnsRule> {
            return buildTunFakeIpDnsRulesStatic(fakeDnsEnabled)
        }

        internal fun buildOutboundDomainResolverDnsRulesForTest(outbounds: List<Outbound>): List<DnsRule> {
            return buildOutboundDomainResolverDnsRulesForRuntime(outbounds)
        }

        internal fun buildOutboundDomainResolverDnsRulesForRuntime(outbounds: List<Outbound>): List<DnsRule> {
            return buildOutboundDomainResolverDnsRules(outbounds)
        }

        internal fun applyDefaultOutboundDomainResolverForTest(
            outbounds: List<Outbound>,
            defaultResolverTag: String,
            defaultResolverStrategy: String? = null): List<Outbound> {
            return applyDefaultOutboundDomainResolver(outbounds, defaultResolverTag, defaultResolverStrategy)
        }

        internal fun buildEchDnsRulesForTest(outbounds: List<Outbound>, serverTag: String): List<DnsRule> {
            return buildEchDnsRules(outbounds, serverTag)
        }

        internal fun buildEchAwareHttpsSvcbDnsRulesForTest(
            blockQuic: Boolean,
            outbounds: List<Outbound>,
            echQueryServerTag: String
        ): List<DnsRule> {
            return buildEchAwareHttpsSvcbDnsRules(blockQuic, outbounds, echQueryServerTag)
        }

        internal fun buildTunFakeIpDnsRulesStatic(fakeDnsEnabled: Boolean): List<DnsRule> {
            if (!fakeDnsEnabled) return emptyList()
            return listOf(
                DnsRule(
                    queryType = listOf("A", "AAAA"),
                    inbound = listOf("tun-in"),
                    action = "route",
                    server = "fakeip-dns"
                )
            )
        }

        internal fun buildOutboundDomainResolverDnsRules(outbounds: List<Outbound>): List<DnsRule> {
            val domainToResolver = linkedMapOf<String, DomainResolveConfig>()
            outbounds.forEach { outbound ->
                val domain = outbound.server
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && !isIpAddressValue(it) }
                    ?.let { normalizeDnsRuleDomain(it) }
                    ?: return@forEach
                val resolver = outbound.domainResolver ?: return@forEach
                val resolverServer = resolver
                    ?.server
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && it != "fakeip-dns" }
                    ?: return@forEach
                domainToResolver.putIfAbsent(domain, resolver.copy(server = resolverServer))
            }
            return domainToResolver.map { (domain, resolver) ->
                DnsRule(
                    domain = listOf(domain),
                    queryType = IP_DNS_QUERY_TYPES,
                    action = "route",
                    server = resolver.server,
                    strategy = resolver.strategy,
                    disableCache = resolver.disableCache,
                    rewriteTtl = resolver.rewriteTtl,
                    clientSubnet = resolver.clientSubnet
                )
            }
        }

        internal fun applyDefaultOutboundDomainResolver(
            outbounds: List<Outbound>,
            defaultResolverTag: String,
            defaultResolverStrategy: String? = null): List<Outbound> {
            return outbounds.map { outbound ->
                val server = outbound.server?.trim().orEmpty()
                if (server.isBlank() || isIpAddressValue(server)) return@map outbound

                val existing = outbound.domainResolver
                if (!existing?.server.isNullOrBlank() && existing?.server != DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG) {
                    return@map outbound
                }

                outbound.copy(
                    domainResolver = (existing ?: DomainResolveConfig()).copy(
                        server = defaultResolverTag,
                        strategy = existing?.strategy ?: defaultResolverStrategy
                    )
                )
            }
        }

        internal fun buildEchAwareHttpsSvcbDnsRules(
            blockQuic: Boolean,
            outbounds: List<Outbound>,
            echQueryServerTag: String
        ): List<DnsRule> {
            val rules = buildEchDnsRules(outbounds, echQueryServerTag).toMutableList()
            val hasEchOutbound = outbounds.any { it.tls?.ech?.enabled == true }
            if (blockQuic && hasEchOutbound) {
                rules.add(
                    DnsRule(
                        queryType = listOf("HTTPS", "SVCB"),
                        action = "predefined",
                        rcode = "NOERROR"
                    )
                )
            }
            return rules
        }

        internal fun buildEchDnsRules(outbounds: List<Outbound>, serverTag: String): List<DnsRule> {
            val queryServerNames = outbounds
                .mapNotNull { it.tls?.ech?.queryServerName?.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            if (queryServerNames.isEmpty()) {
                return emptyList()
            }
            return listOf(
                DnsRule(
                    action = "route",
                    domain = queryServerNames,
                    queryType = listOf("HTTPS", "SVCB"),
                    server = serverTag
                )
            )
        }

        internal fun buildNonIpDnsFallbackRule(serverTag: String): DnsRule {
            return DnsRule(
                action = "route",
                queryType = NON_IP_DNS_QUERY_TYPES,
                server = serverTag
            )
        }

        internal fun buildDnsRouteToDirect(
            serverTag: String,
            directServerTag: String,
            rule: DnsRule
        ): DnsRule {
            val routeRule = if (serverTag == directServerTag && rule.queryType == null) {
                rule.copy(queryType = IP_DNS_QUERY_TYPES)
            } else {
                rule
            }
            return routeRule.copy(action = "route", server = serverTag)
        }

        internal fun buildDnsRouteToNonDirect(
            fakeDnsEnabled: Boolean,
            serverTag: String,
            rule: DnsRule
        ): List<DnsRule> {
            fun dnsRouteTo(server: String, currentRule: DnsRule): DnsRule =
                currentRule.copy(action = "route", server = server)

            if (!fakeDnsEnabled) {
                return listOf(dnsRouteTo(serverTag, rule))
            }
            return listOf(dnsRouteTo(serverTag, rule.copy(queryType = IP_DNS_QUERY_TYPES)))
        }

        internal fun buildGoogleConnectivityDnsRules(
            fakeDnsEnabled: Boolean,
            proxyServerTag: String
        ): List<DnsRule> {
            return buildDnsRouteToNonDirect(
                fakeDnsEnabled = fakeDnsEnabled,
                serverTag = proxyServerTag,
                rule = DnsRule(domain = googleConnectivityCheckDomains)
            )
        }

        internal fun sortRuleSetsForDnsAndRoutePriority(ruleSets: List<RuleSet>): List<RuleSet> {
            return ruleSets.sortedWith(
                compareBy(
                    { ruleSet ->
                        when {
                            ruleSet.tag == "geolocation-!cn" -> 200
                            ruleSet.tag == "geolocation-cn" -> 199
                            ruleSet.tag == "!cn" || ruleSet.tag.endsWith("-!cn") -> 198
                            ruleSet.tag.matches(Regex("^geo(site|ip)-[a-z]{2}$")) -> 100
                            else -> 0
                        }
                    },
                    { ruleSet ->
                        when (resolveRuleSetOutboundMode(ruleSet.outboundMode)) {
                            RuleSetOutboundMode.NODE -> 0
                            RuleSetOutboundMode.PROXY -> 1
                            RuleSetOutboundMode.DIRECT -> 2
                            RuleSetOutboundMode.BLOCK -> 3
                            RuleSetOutboundMode.PROFILE -> 1
                        }
                    }
                )
            )
        }

        internal fun resolveProxyDnsDetourTagForTest(
            selectorTag: String,
            outbounds: List<Outbound>
        ): String {
            fun resolveCurrent(tag: String): String {
                val outbound = outbounds.firstOrNull { it.tag == tag } ?: return tag
                return when (outbound.type) {
                    "selector" -> resolveCurrent(outbound.default ?: outbound.outbounds?.firstOrNull() ?: tag)
                    "urltest", "url-test" -> resolveCurrent(outbound.outbounds?.firstOrNull() ?: tag)
                    else -> tag
                }
            }
            return resolveCurrent(selectorTag)
        }

        internal fun resolveRunDnsFinalServerForTest(
            routingMode: RoutingMode,
            defaultRule: DefaultRule,
            fakeDnsEnabled: Boolean,
            proxyServerTag: String,
            stableRemoteServerTag: String = "remote",
            directServerTag: String = "local"): String {
            return when (routingMode) {
                RoutingMode.GLOBAL_PROXY -> stableRemoteServerTag
                RoutingMode.GLOBAL_DIRECT -> directServerTag
                RoutingMode.RULE -> when (defaultRule) {
                    DefaultRule.PROXY -> proxyServerTag
                    DefaultRule.DIRECT -> directServerTag
                    DefaultRule.BLOCK -> if (fakeDnsEnabled) stableRemoteServerTag else proxyServerTag
                }
            }
        }

        internal fun sanitizeInjectedDnsServerForTest(
            server: DnsServer,
            routingMode: RoutingMode,
            proxyDetourTag: String,
            directDnsServerTags: Set<String> = emptySet()): DnsServer {
            return sanitizeInjectedDnsServerForRuntime(server, routingMode, proxyDetourTag, directDnsServerTags)
        }

        internal fun sanitizeInjectedDnsServerForRuntime(
            server: DnsServer,
            routingMode: RoutingMode,
            proxyDetourTag: String,
            directDnsServerTags: Set<String> = emptySet()): DnsServer {
            val normalizedServer = normalizeInjectedDnsServer(server)
            val serverTag = normalizedServer.tag?.trim().orEmpty()
            val t = normalizedServer.type?.lowercase().orEmpty()
            val shouldKeepDirect = routingMode == RoutingMode.GLOBAL_DIRECT ||
                (serverTag.isNotBlank() && serverTag in directDnsServerTags)
            val shouldPreserve = shouldKeepDirect ||
                !normalizedServer.detour.isNullOrBlank() ||
                t == "fakeip" ||
                t == "local" ||
                t == "dhcp"
            return if (shouldPreserve) normalizedServer else normalizedServer.copy(detour = proxyDetourTag)
        }

        internal fun normalizeInjectedDnsServer(server: DnsServer): DnsServer {
            val tag = server.tag?.trim().orEmpty()
            val address = server.address?.trim().orEmpty()
            val hasNewEndpoint = !server.type.isNullOrBlank() || !server.server.isNullOrBlank()
            if (tag.isBlank() || address.isBlank() || hasNewEndpoint) {
                return server
            }

            val domainResolver = server.domainResolver ?: server.addressResolver
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { DomainResolveConfig(server = it) }
            return buildDnsServer(
                address = address,
                tag = tag,
                detour = server.detour,
                domainStrategy = server.domainStrategy ?: server.strategy,
                domainResolver = domainResolver
            ).copy(
                udpFragment = server.udpFragment,
                networkStrategy = server.networkStrategy,
                networkType = server.networkType,
                fallbackNetworkType = server.fallbackNetworkType,
                fallbackDelay = server.fallbackDelay,
                inet4Range = server.inet4Range,
                inet6Range = server.inet6Range,
                headers = server.headers,
                tls = server.tls
            )
        }

        internal fun applyDnsOverrideForTest(
            baseConfig: DnsConfig,
            overrideConfig: DnsConfig,
            sanitizeServer: (DnsServer) -> DnsServer = { it }): DnsConfig {
            return applyDnsOverride(baseConfig, overrideConfig, sanitizeServer)
        }

        internal fun parseDnsOverrideForTest(dnsOverride: String?): DnsConfig? {
            return parseDnsOverrideConfig(dnsOverride)
        }

        internal fun buildDnsOverrideCompatibilityWarningForTest(dnsOverride: String?): String? {
            return buildDnsOverrideCompatibilityWarning(dnsOverride)
        }

        fun buildDnsOverrideCompatibilityWarning(dnsOverride: String?): String? {
            val trimmed = dnsOverride?.trim().orEmpty()
            if (trimmed.isBlank()) return null

            val dnsObject = parseDnsOverrideObjectForWarning(trimmed)
            return when {
                dnsObject == null -> "DNS 覆写无法解析，请检查 JSON 格式；KunBox 无法保证兼容。"
                !hasDnsOverrideShape(dnsObject) -> "DNS 覆写无法解析，请使用包含 dns/servers/rules 的 JSON 对象。"
                else -> formatDnsOverrideCompatibilityWarning(collectDnsOverrideCompatibilityIssues(dnsObject))
            }
        }

        internal fun parseDnsOverrideObjectForWarning(dnsOverride: String): JsonObject? {
            return try {
                extractDnsOverrideJsonObject(dnsOverride)
            } catch (_: Exception) {
                null
            }
        }

        internal fun hasDnsOverrideShape(dnsObject: JsonObject): Boolean {
            return dnsOverrideKeys().any { dnsObject.has(it) }
        }

        internal fun collectDnsOverrideCompatibilityIssues(dnsObject: JsonObject): Set<String> {
            val issues = linkedSetOf<String>()
            val definedServerTags = knownDnsServerTags().toMutableSet()
            collectDnsServerCompatibilityIssues(dnsObject, definedServerTags, issues)
            collectDnsRuleCompatibilityIssues(dnsObject, definedServerTags, issues)
            collectDnsTopLevelCompatibilityIssues(dnsObject, issues)
            return issues
        }

        internal fun collectDnsServerCompatibilityIssues(
            dnsObject: JsonObject,
            definedServerTags: MutableSet<String>,
            issues: MutableSet<String>
        ) {
            val servers = dnsObject.get("servers") ?: return
            if (!servers.isJsonArray) {
                issues.add("dns.servers 不是数组")
                return
            }
            val overrideTags = linkedSetOf<String>()
            servers.asJsonArray.forEach { element ->
                collectSingleDnsServerCompatibilityIssues(
                    asJsonObjectOrNull(element),
                    definedServerTags,
                    overrideTags,
                    issues
                )
            }
        }

        internal fun collectSingleDnsServerCompatibilityIssues(
            server: JsonObject?,
            definedServerTags: MutableSet<String>,
            overrideTags: MutableSet<String>,
            issues: MutableSet<String>
        ) {
            if (server == null) {
                issues.add("servers 中存在非对象项")
                return
            }
            collectDnsServerTagIssues(server, definedServerTags, overrideTags, issues)
            collectDnsServerLegacyFieldIssues(server, issues)
            collectDnsServerEndpointIssues(server, issues)
        }

        internal fun collectDnsServerTagIssues(
            server: JsonObject,
            definedServerTags: MutableSet<String>,
            overrideTags: MutableSet<String>,
            issues: MutableSet<String>
        ) {
            val tag = jsonString(server, "tag")
            if (tag.isNullOrBlank()) {
                issues.add("DNS server 缺少 tag")
                return
            }
            if (!overrideTags.add(tag)) {
                issues.add("DNS server tag 重复: $tag")
            }
            definedServerTags.add(tag)
        }

        internal fun collectDnsServerLegacyFieldIssues(server: JsonObject, issues: MutableSet<String>) {
            if (server.has("address")) issues.add("DNS server 使用旧字段 address")
            if (server.has("address_resolver")) issues.add("DNS server 使用旧字段 address_resolver")
            if (server.has("address_strategy")) issues.add("DNS server 使用旧字段 address_strategy")
        }

        internal fun collectDnsServerEndpointIssues(server: JsonObject, issues: MutableSet<String>) {
            val tag = jsonString(server, "tag") ?: return
            val type = jsonString(server, "type")?.lowercase()
            val endpointOptional = type in dnsServerTypesWithoutEndpoint()
            val usesLatestEndpoint = !type.isNullOrBlank() && (endpointOptional || hasNonBlankString(server, "server"))
            if (!usesLatestEndpoint) {
                issues.add("DNS server 缺少最新格式 type/server: $tag")
            }
        }

        internal fun collectDnsRuleCompatibilityIssues(
            dnsObject: JsonObject,
            definedServerTags: Set<String>,
            issues: MutableSet<String>
        ) {
            val rules = dnsObject.get("rules") ?: return
            if (!rules.isJsonArray) {
                issues.add("dns.rules 不是数组")
                return
            }
            rules.asJsonArray.forEach { element ->
                collectSingleDnsRuleCompatibilityIssues(asJsonObjectOrNull(element), definedServerTags, issues)
            }
        }

        internal fun collectSingleDnsRuleCompatibilityIssues(
            rule: JsonObject?,
            definedServerTags: Set<String>,
            issues: MutableSet<String>
        ) {
            if (rule == null) {
                issues.add("rules 中存在非对象项")
                return
            }
            collectDnsRuleActionIssues(rule, definedServerTags, issues)
            collectDnsRuleLegacyFieldIssues(rule, issues)
            if (!hasDnsRuleMatcher(rule)) {
                issues.add("DNS rule 存在没有匹配条件的全局规则")
            }
        }

        internal fun collectDnsRuleActionIssues(
            rule: JsonObject,
            definedServerTags: Set<String>,
            issues: MutableSet<String>
        ) {
            val server = jsonString(rule, "server")
            val action = jsonString(rule, "action")
            if (!server.isNullOrBlank() && action.isNullOrBlank()) {
                issues.add("DNS rule 缺少最新格式 action")
            }
            if (action.equals("route", ignoreCase = true) && server.isNullOrBlank()) {
                issues.add("DNS route 规则缺少 server")
            }
            if (!server.isNullOrBlank() && server !in definedServerTags) {
                issues.add("DNS rule 引用了未定义 server: $server")
            }
        }

        internal fun collectDnsRuleLegacyFieldIssues(rule: JsonObject, issues: MutableSet<String>) {
            if (rule.has("outbound")) {
                issues.add("DNS rule 使用已废弃 outbound 匹配")
            }
            if (dnsRuleAddressFilterKeys().any { rule.has(it) }) {
                issues.add("DNS rule 使用旧地址过滤字段")
            }
        }

        internal fun collectDnsTopLevelCompatibilityIssues(dnsObject: JsonObject, issues: MutableSet<String>) {
            if (dnsObject.has("independent_cache")) {
                issues.add("dns.independent_cache 已不再推荐")
            }
        }

        internal fun formatDnsOverrideCompatibilityWarning(issues: Set<String>): String? {
            if (issues.isEmpty()) return null
            return "DNS 覆写使用了旧版 sing-box 格式或存在兼容风险：" +
                issues.take(5).joinToString("；") +
                "。KunBox 会尝试兼容，但建议改为最新 sing-box DNS 格式。"
        }

        internal fun extractDnsOverrideJsonObject(dnsOverride: String): JsonObject? {
            val root = JsonParser.parseString(dnsOverride)
            if (!root.isJsonObject) return null
            val rootObject = root.asJsonObject
            return rootObject
                .get("dns")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?: rootObject
        }

        internal fun jsonString(obj: JsonObject, key: String): String? {
            val element = obj.get(key) ?: return null
            if (!element.isJsonPrimitive) return null
            return element.asJsonPrimitive.takeIf { it.isString }?.asString?.trim()?.takeIf { it.isNotBlank() }
        }

        internal fun hasNonBlankString(obj: JsonObject, key: String): Boolean {
            return !jsonString(obj, key).isNullOrBlank()
        }

        internal fun hasDnsRuleMatcher(rule: JsonObject): Boolean {
            return dnsRuleMatcherKeys().any { key -> rule.has(key) && !rule.get(key).isJsonNull }
        }

        internal fun asJsonObjectOrNull(element: com.google.gson.JsonElement): JsonObject? {
            return element.takeIf { it.isJsonObject }?.asJsonObject
        }

        internal fun dnsOverrideKeys(): Set<String> {
            return setOf(
                "servers",
                "rules",
                "final",
                "strategy",
                "disable_cache",
                "disable_expire",
                "independent_cache",
                "fakeip"
            )
        }

        internal fun knownDnsServerTags(): Set<String> {
            return setOf(
                "local",
                "remote",
                "fakeip-dns",
                "dns-bootstrap",
                "dns-bootstrap-v4",
                "dns-bootstrap-v6",
                "dns-backup"
            )
        }

        internal fun dnsServerTypesWithoutEndpoint(): Set<String> {
            return setOf("local", "fakeip", "dhcp")
        }

        internal fun dnsRuleMatcherKeys(): Set<String> {
            return setOf(
                "domain",
                "domain_suffix",
                "domain_keyword",
                "domain_regex",
                "geosite",
                "rule_set",
                "query_type",
                "inbound",
                "package_name",
                "user_id",
                "outbound"
            )
        }

        internal fun dnsRuleAddressFilterKeys(): Set<String> {
            return setOf(
                "ip_cidr",
                "source_ip_cidr",
                "source_geoip",
                "rule_set_ip_cidr_match_source",
                "rule_set_ip_cidr_accept_empty"
            )
        }

        internal fun parseDnsOverrideConfig(dnsOverride: String?): DnsConfig? {
            val trimmed = dnsOverride?.trim().orEmpty()
            if (trimmed.isBlank()) return null
            return Gson().fromJson(extractDnsOverrideJsonObject(trimmed), DnsConfig::class.java)
        }

        internal fun applyDnsOverride(
            baseConfig: DnsConfig,
            overrideConfig: DnsConfig,
            sanitizeServer: (DnsServer) -> DnsServer
        ): DnsConfig {
            val servers = baseConfig.servers.orEmpty().toMutableList()
            overrideConfig.servers.orEmpty().forEach { server ->
                val tag = server.tag
                if (!tag.isNullOrBlank()) {
                    val sanitizedServer = sanitizeServer(server)
                    val existingIndex = servers.indexOfFirst { it.tag == tag }
                    if (existingIndex >= 0) {
                        servers[existingIndex] = sanitizedServer
                    } else {
                        servers.add(sanitizedServer)
                    }
                }
            }

            val rules = baseConfig.rules.orEmpty().toMutableList()
            val overrideRules = overrideConfig.rules.orEmpty().map { normalizeDnsOverrideRule(it) }
            if (overrideRules.isNotEmpty()) {
                rules.addAll(0, overrideRules)
            }

            return baseConfig.copy(
                servers = servers,
                rules = rules,
                finalServer = overrideConfig.finalServer?.takeIf { it.isNotBlank() } ?: baseConfig.finalServer,
                strategy = overrideConfig.strategy?.takeIf { it.isNotBlank() } ?: baseConfig.strategy,
                disableCache = overrideConfig.disableCache ?: baseConfig.disableCache,
                disableExpire = overrideConfig.disableExpire ?: baseConfig.disableExpire,
                independentCache = overrideConfig.independentCache ?: baseConfig.independentCache,
                fakeip = overrideConfig.fakeip ?: baseConfig.fakeip
            )
        }

        internal fun normalizeDnsOverrideRule(rule: DnsRule): DnsRule {
            if (!rule.action.isNullOrBlank() || rule.server.isNullOrBlank()) {
                return rule
            }
            return rule.copy(action = "route")
        }

        internal fun applyDnsOverrideDomainResolversForTest(
            outbounds: List<Outbound>,
            overrideConfig: DnsConfig
        ): List<Outbound> {
            return applyDnsOverrideDomainResolvers(outbounds, overrideConfig)
        }

        internal fun resolveDnsOverrideDirectDnsServerTagsForTest(
            outbounds: List<Outbound>,
            overrideConfig: DnsConfig?
        ): Set<String> {
            return resolveDnsOverrideDirectDnsServerTags(outbounds, overrideConfig)
        }

        internal fun shouldApplyDnsPreResolveToDomainForTest(
            domain: String,
            dnsOverride: DnsConfig?,
            outboundTag: String? = null): Boolean {
            return shouldApplyDnsPreResolveToDomain(domain, dnsOverride, outboundTag)
        }

        internal fun shouldApplyDnsPreResolveToDomain(
            domain: String,
            dnsOverride: DnsConfig?,
            outboundTag: String? = null): Boolean {
            val normalizedDomain = domain.trim()
            if (normalizedDomain.isBlank() || isIpAddressValue(normalizedDomain) || dnsOverride == null) {
                return true
            }
            return dnsOverride.rules.orEmpty()
                .map { normalizeDnsOverrideRule(it) }
                .none { rule ->
                    buildDomainResolverForMatchedDnsOverrideRule(
                        domain = normalizedDomain,
                        outboundTag = outboundTag,
                        rule = rule
                    ) != null
                }
        }

        internal fun applyDnsOverrideDomainResolvers(
            outbounds: List<Outbound>,
            overrideConfig: DnsConfig
        ): List<Outbound> {
            val rules = overrideConfig.rules.orEmpty().map { normalizeDnsOverrideRule(it) }
            if (rules.isEmpty()) return outbounds

            return outbounds.map { outbound ->
                val server = outbound.server?.trim().orEmpty()
                if (server.isBlank() || isIpAddressValue(server)) {
                    return@map outbound
                }
                val resolver = rules.firstNotNullOfOrNull { rule ->
                    buildDomainResolverForMatchedDnsOverrideRule(
                        domain = server,
                        outboundTag = outbound.tag,
                        rule = rule
                    )
                } ?: return@map outbound
                outbound.copy(
                    domainResolver = resolver.copy(
                        strategy = resolver.strategy ?: outbound.domainResolver?.strategy
                    )
                )
            }
        }

        internal fun resolveDnsOverrideDirectDnsServerTags(
            outbounds: List<Outbound>,
            overrideConfig: DnsConfig?
        ): Set<String> {
            val rules = overrideConfig?.rules.orEmpty().map { normalizeDnsOverrideRule(it) }
            if (rules.isEmpty()) return emptySet()

            val directTags = linkedSetOf<String>()
            outbounds.forEach { outbound ->
                val server = outbound.server?.trim().orEmpty()
                if (server.isBlank() || isIpAddressValue(server)) return@forEach
                rules.forEach { rule ->
                    val resolver = buildDomainResolverForMatchedDnsOverrideRule(
                        domain = server,
                        outboundTag = outbound.tag,
                        rule = rule
                    )
                    val resolverTag = resolver?.server?.trim().orEmpty()
                    if (resolverTag.isNotBlank()) {
                        directTags.add(resolverTag)
                    }
                }
            }
            return directTags
        }

        internal fun buildDomainResolverForMatchedDnsOverrideRule(
            domain: String,
            outboundTag: String?,
            rule: DnsRule
        ): DomainResolveConfig? {
            val server = rule.server?.trim()?.takeIf { it.isNotBlank() }
            val matches = server != null &&
                rule.action.equals("route", ignoreCase = true) &&
                dnsRuleAppliesToAddressQuery(rule) &&
                dnsRuleCanResolveOutboundDomain(domain, outboundTag, rule)
            return if (matches) {
                DomainResolveConfig(
                    server = server,
                    strategy = rule.strategy,
                    disableCache = rule.disableCache,
                    rewriteTtl = rule.rewriteTtl,
                    clientSubnet = rule.clientSubnet
                )
            } else {
                null
            }
        }

        internal fun dnsRuleCanResolveOutboundDomain(
            domain: String,
            outboundTag: String?,
            rule: DnsRule
        ): Boolean {
            if (!dnsRuleMatchesOutbound(outboundTag, rule)) {
                return false
            }
            if (dnsRuleHasDomainMatcher(rule)) {
                return dnsRuleMatchesDomain(domain, rule)
            }
            return dnsRuleHasNoUnsupportedOutboundDomainMatcher(rule)
        }

        internal fun dnsRuleMatchesOutbound(outboundTag: String?, rule: DnsRule): Boolean {
            val outbounds = dnsRuleOutboundValues(rule)
            if (outbounds.isEmpty()) return true
            if (outbounds.any { it.equals("any", ignoreCase = true) }) return true
            return outboundTag?.let { tag -> outbounds.any { it == tag } } == true
        }

        internal fun dnsRuleOutboundValues(rule: DnsRule): List<String> {
            return when (val raw = rule.outboundRaw) {
                is String -> listOf(raw)
                is List<*> -> raw.mapNotNull { it?.toString() }
                else -> emptyList()
            }.map { it.trim() }.filter { it.isNotBlank() }
        }

        internal fun dnsRuleHasDomainMatcher(rule: DnsRule): Boolean {
            return rule.domain.orEmpty().any { it.isNotBlank() } ||
                rule.domainSuffix.orEmpty().any { it.isNotBlank() } ||
                rule.domainKeyword.orEmpty().any { it.isNotBlank() } ||
                rule.domainRegex.orEmpty().any { it.isNotBlank() }
        }

        internal fun dnsRuleHasNoUnsupportedOutboundDomainMatcher(rule: DnsRule): Boolean {
            return rule.geosite.orEmpty().none { it.isNotBlank() } &&
                rule.ruleSet.orEmpty().none { it.isNotBlank() } &&
                rule.inbound.orEmpty().none { it.isNotBlank() } &&
                rule.packageName.orEmpty().none { it.isNotBlank() } &&
                rule.userId.orEmpty().isEmpty()
        }

        internal fun dnsRuleAppliesToAddressQuery(rule: DnsRule): Boolean {
            val queryTypes = rule.queryType.orEmpty()
                .map { it.trim().uppercase() }
                .filter { it.isNotBlank() }
            return queryTypes.isEmpty() || queryTypes.any { it == "A" || it == "AAAA" }
        }

        internal fun dnsRuleMatchesDomain(domain: String, rule: DnsRule): Boolean {
            val normalizedDomain = domain.trim().trimEnd('.').lowercase()
            val exactMatch = rule.domain.orEmpty().any { normalizeDnsRuleDomain(it) == normalizedDomain }
            val suffixMatch = rule.domainSuffix.orEmpty().any { suffix ->
                val normalizedSuffix = normalizeDnsRuleDomain(suffix).removePrefix(".")
                normalizedDomain == normalizedSuffix || normalizedDomain.endsWith(".$normalizedSuffix")
            }
            val keywordMatch = rule.domainKeyword.orEmpty().any { keyword ->
                keyword.trim().lowercase().takeIf { it.isNotBlank() }?.let { normalizedDomain.contains(it) } == true
            }
            val regexMatch = rule.domainRegex.orEmpty().any { pattern ->
                runCatching { Regex(pattern).containsMatchIn(domain) }.getOrDefault(false)
            }

            return normalizedDomain.isNotBlank() && (exactMatch || suffixMatch || keywordMatch || regexMatch)
        }

        internal fun normalizeDnsRuleDomain(value: String): String {
            return value.trim().trimEnd('.').lowercase()
        }

        internal fun normalizeLocalDns(value: String?): String {
            val trimmed = value?.trim().orEmpty()
            return when {
                trimmed.isBlank() -> AppSettings.DEFAULT_LOCAL_DNS
                trimmed.equals(AppSettings.LEGACY_LOCAL_DNS, ignoreCase = true) -> AppSettings.DEFAULT_LOCAL_DNS
                isBareDnsDomain(trimmed) -> AppSettings.DEFAULT_LOCAL_DNS
                else -> trimmed
            }
        }

        internal fun isBareDnsDomain(value: String): Boolean {
            if (value.contains("://") || value.contains("/")) return false
            if (isIpAddressValue(value)) return false
            return value.contains('.')
        }

        internal fun normalizeRemoteDns(value: String?): String {
            val trimmed = value?.trim().orEmpty()
            val remoteDns = trimmed.ifBlank { "https://dns.google/dns-query" }
            return normalizeCloudflareIpDohAddress(remoteDns)
        }

        internal fun normalizeCloudflareIpDohAddress(address: String): String {
            val uri = runCatching { URI(address.trim()) }.getOrNull() ?: return address
            val scheme = uri.scheme?.lowercase() ?: return address
            val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return address
            if (scheme !in setOf("https", "h3") || host !in CLOUDFLARE_DOH_IPS) return address
            val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/dns-query"
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            return "$scheme://cloudflare-dns.com$path$query"
        }

        internal fun buildDnsResolverForAddress(address: String): DomainResolveConfig? {
            val trimmed = address.trim()
            if (trimmed.equals("local", ignoreCase = true)) {
                return null
            }
            val host = extractHostFromAddress(trimmed)?.trim().orEmpty()
            if (host.isEmpty() || isIpAddressValue(host)) {
                return null
            }
            return DomainResolveConfig(server = "dns-bootstrap")
        }

        internal fun buildSpecialDnsServerOrNull(
            trimmed: String,
            tag: String,
            detour: String?,
            domainStrategy: String?,
            domainResolver: DomainResolveConfig?
        ): DnsServer? {
            val type = when {
                trimmed.equals("local", ignoreCase = true) -> "local"
                trimmed.equals("fakeip", ignoreCase = true) -> "fakeip"
                else -> null
            }
            return type?.let {
                DnsServer(
                    tag = tag,
                    type = it,
                    domainResolver = domainResolver,
                    domainStrategy = domainStrategy,
                    detour = detour
                )
            }
        }

        internal fun dnsServerTypeFromScheme(scheme: String?): String {
            return when (scheme) {
                "https" -> "https"
                "h3" -> "h3"
                "tls" -> "tls"
                "quic" -> "quic"
                "tcp" -> "tcp"
                "udp" -> "udp"
                "dhcp" -> "dhcp"
                null -> "udp"
                else -> "udp"
            }
        }

        internal fun shouldUseParsedDnsHost(scheme: String?): Boolean {
            return scheme == null || scheme in setOf("https", "h3", "tls", "quic", "tcp", "udp", "dhcp")
        }

        internal fun buildDnsServer(
            address: String,
            tag: String,
            detour: String? = null,
            domainStrategy: String? = null,
            domainResolver: DomainResolveConfig? = null): DnsServer {
            val trimmed = address.trim()
            buildSpecialDnsServerOrNull(trimmed, tag, detour, domainStrategy, domainResolver)?.let {
                return it
            }

            val uri = try {
                URI(trimmed)
            } catch (_: Exception) {
                return DnsServer(
                    tag = tag,
                    type = "udp",
                    server = trimmed,
                    serverPort = 53,
                    domainResolver = domainResolver,
                    domainStrategy = domainStrategy,
                    detour = detour
                )
            }

            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: trimmed
            val port = if (uri.port > 0) uri.port else if (scheme == null || scheme == "udp") 53 else null
            val path = uri.path?.takeIf { it.isNotBlank() && it != "/" }

            val type = dnsServerTypeFromScheme(scheme)
            val server = if (shouldUseParsedDnsHost(scheme)) host else trimmed

            return DnsServer(
                tag = tag,
                type = type,
                server = server,
                serverPort = port,
                path = path,
                domainResolver = domainResolver,
                domainStrategy = domainStrategy,
                detour = detour
            )
        }

        fun getInstance(context: Context): ConfigRepository {
            return instance ?: synchronized(this) {
                instance ?: ConfigRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
