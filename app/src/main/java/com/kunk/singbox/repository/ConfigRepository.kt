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
import com.google.gson.JsonPrimitive
import com.google.gson.reflect.TypeToken
import com.kunk.singbox.R
import com.kunk.singbox.SingBoxApplication
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
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.tun.VpnTunManager
import com.kunk.singbox.utils.NetworkClient
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
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

internal suspend fun runLatencyBatchAndApply(
    runBatch: suspend () -> Unit,
    applyResults: suspend () -> Unit
) {
    try {
        runBatch()
        applyResults()
    } catch (e: CancellationException) {
        withContext(NonCancellable) { applyResults() }
        throw e
    }
}

internal fun resolveRestoredProfileSelection(
    availableProfileIds: Set<String>,
    databaseProfileId: String?,
    databaseNodeId: String?,
    persistedProfileId: String?,
    persistedNodeId: String?
): Pair<String?, String?> {
    val usePersistedSelection = !persistedProfileId.isNullOrBlank() && persistedProfileId in availableProfileIds
    val profileId = if (usePersistedSelection) {
        persistedProfileId
    } else {
        databaseProfileId?.takeIf { it in availableProfileIds }
    }
    val nodeId = if (usePersistedSelection) {
        persistedNodeId?.takeIf { it.isNotBlank() }
            ?: databaseNodeId?.takeIf { databaseProfileId == profileId }
    } else {
        databaseNodeId
    }
    return profileId to nodeId
}

internal fun combineCustomProfileOutbounds(
    copiedOutbounds: List<Outbound>,
    addedOutbounds: List<Outbound>
): List<Outbound> {
    val nodeOutbounds = (copiedOutbounds + addedOutbounds)
        .filterNot { it.type == "direct" || it.type == "block" || it.type == "dns" }
    if (nodeOutbounds.isEmpty()) return emptyList()
    return nodeOutbounds + Outbound(type = "direct", tag = "direct")
}

internal fun shouldReloadRuntimeForManualSelection(
    currentProfileId: String,
    currentTags: Set<String>,
    baselineProfileId: String?,
    baselineTags: Set<String>?,
    isVpnStartingNotReady: Boolean
): Boolean {
    return isVpnStartingNotReady ||
        baselineProfileId.isNullOrBlank() ||
        baselineProfileId != currentProfileId ||
        baselineTags == null ||
        baselineTags != currentTags
}

internal fun isRuntimeSelectionConfirmed(
    snapshot: VpnStateStore.RuntimeStateSnapshot,
    previousGeneration: Long,
    expectedLabels: Set<String>
): Boolean {
    return !snapshot.manuallyStopped &&
        snapshot.generation > previousGeneration &&
        snapshot.stateOrdinal == ServiceState.RUNNING.ordinal &&
        snapshot.lastError.isBlank() &&
        expectedLabels.any { expected -> expected.trim().equals(snapshot.activeLabel.trim(), ignoreCase = true) }
}

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
        val outboundTags: Set<String>,
        val activeNodeName: String? = null
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

    protected val profileNodes = ConcurrentHashMap<String, List<NodeUi>>()

    protected val profileResetJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    protected val profileUpdateRuns = ConcurrentHashMap<String, Long>()

    protected val inFlightLatencyTests = ConcurrentHashMap<String, Deferred<Long>>()

    protected val savedNodeLatencies = ConcurrentHashMap<String, SavedNodeLatency>()
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
    @Volatile private var lastConfigGenerationError: String? = null

    private val nodeSwitchGate = NodeSwitchGate()

    protected val profileLastSelectedNode = ConcurrentHashMap<String, String>()

    private val _profileAutoSelections = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val profileAutoSelections: StateFlow<Map<String, Boolean>> = _profileAutoSelections.asStateFlow()

    protected val profileNodeMemoryMmkv: MMKV by lazy {
        MMKV.mmkvWithID("profile_node_memory", MMKV.MULTI_PROCESS_MODE)
    }

    protected val profileAutoSelectionMmkv: MMKV by lazy {
        MMKV.mmkvWithID("profile_auto_selection", MMKV.MULTI_PROCESS_MODE)
    }

    protected val nodeAutoSelectionMmkv: MMKV by lazy {
        MMKV.mmkvWithID("node_auto_selection", MMKV.MULTI_PROCESS_MODE)
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

    private val isMainProcess: Boolean by lazy {
        (context.applicationContext as? SingBoxApplication)?.isMainProcess() == true
    }

    init {
        startConfigCacheCleanup()
        initialProfilesLoadJob = scope.launch {
            loadProfileNodeMemory()
            loadProfileAutoSelections()
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

        return VpnTunManager.resolveAutoMtu(
            configuredMtu = configuredMtu,
            physicalMtu = getPhysicalNetworkMtu(),
            includesIpv6 = settings.ipVersionMode.includesIpv6
        )
    }

    @Suppress("DEPRECATION")
    protected fun getPhysicalNetworkMtu(): Int? {
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

    protected fun getSubscriptionClient(timeoutBudget: SubscriptionAttemptTimeoutBudget): OkHttpClient {
        return NetworkClient.createClientWithoutRetry(
            connectTimeoutSeconds = timeoutBudget.connectTimeoutSeconds,
            readTimeoutSeconds = timeoutBudget.readTimeoutSeconds,
            writeTimeoutSeconds = timeoutBudget.writeTimeoutSeconds,
            callTimeoutSeconds = timeoutBudget.callTimeoutSeconds
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

    protected fun loadProfileAutoSelections() {
        _profileAutoSelections.value = profileAutoSelectionMmkv.allKeys()
            .orEmpty()
            .associateWith { profileId -> profileAutoSelectionMmkv.decodeBool(profileId, false) }
            .filterValues { it }
    }

    protected fun saveProfileNodeMemory(profileId: String, nodeId: String) {
        profileLastSelectedNode[profileId] = nodeId
        profileNodeMemoryMmkv.encode(profileId, nodeId)
    }

    protected fun getProfileLastSelectedNode(profileId: String): String? {
        return profileNodeMemoryMmkv.decodeString(profileId, null)
            ?.takeIf { it.isNotBlank() }
            ?.also { profileLastSelectedNode[profileId] = it }
            ?: profileLastSelectedNode[profileId]
    }

    fun isProfileAutoSelectionEnabled(profileId: String?): Boolean {
        return !profileId.isNullOrBlank() && profileAutoSelectionMmkv.decodeBool(profileId, false)
    }

    fun isNodeAutoSelectionEligible(nodeId: String): Boolean {
        return nodeAutoSelectionMmkv.decodeBool(nodeId, true)
    }

    fun isNodeMeteredProtected(nodeId: String): Boolean {
        return NodeProtectionStore.isProtected(nodeId)
    }

    fun getLastConfigGenerationError(): String? = lastConfigGenerationError

    internal fun getRuntimeNodeMappings(): Map<String, RuntimeNodeRef> {
        return NodeProtectionStore.runtimeMappings()
    }

    internal fun isMeteredNodeUseAuthorized(nodeId: String): Boolean {
        val node = getNodeById(nodeId) ?: return false
        return NodeProtectionStore.isUseAuthorized(
            nodeId = nodeId,
            activeNodeId = _activeNodeId.value,
            autoSelectionEnabled = isProfileAutoSelectionEnabled(node.sourceProfileId)
        )
    }

    private fun saveNodeAutoSelectionEligibility(nodeId: String, eligible: Boolean): Boolean {
        return nodeAutoSelectionMmkv.encode(nodeId, eligible)
    }

    fun getProfileNodeMemorySnapshot(): Map<String, String> {
        return _profiles.value.mapNotNull { profile ->
            getProfileLastSelectedNode(profile.id)?.let { profile.id to it }
        }.toMap()
    }

    fun getProfileAutoSelectionSnapshot(): Map<String, Boolean> {
        return _profiles.value.associate { profile ->
            profile.id to isProfileAutoSelectionEnabled(profile.id)
        }
    }

    suspend fun replaceProfileSelectionState(
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

    private fun saveProfileAutoSelection(profileId: String, enabled: Boolean): Boolean {
        val written = profileAutoSelectionMmkv.encode(profileId, enabled)
        if (written) {
            _profileAutoSelections.update { current ->
                if (enabled) current + (profileId to true) else current - profileId
            }
        }
        return written
    }

    private fun persistMainProcessSelection(profileId: String, nodeId: String?, nodeName: String?) {
        if (!isMainProcess) return
        VpnStateStore.setSelectedNode(profileId, nodeId)
        VpnStateStore.setSelectedNodeLabel(nodeName)
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

    protected suspend fun loadProfileNodesWithLatency(profileId: String): List<NodeUi>? {
        val cfg = withContext(Dispatchers.IO) { loadConfig(profileId) } ?: return null
        val nodes = extractNodesFromConfig(cfg, profileId)
        return nodes.map { node ->
            val latency = savedLatencyMs(node.id)
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
        } catch (e: IllegalArgumentException) {
            throw e
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
        }.onFailure { error ->
            Log.w(ConfigRepository.TAG, "DNS pre-resolve failed for profile $profileId", error)
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

    protected suspend fun updateLatencyInAllNodes(nodeId: String, latency: Long) {
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

    protected fun normalizeLatencyValue(latency: Long): Long {
        return when {
            latency > 0L -> latency
            latency == PingResultCode.UNAVAILABLE -> PingResultCode.UNAVAILABLE
            latency == PingResultCode.IPV6_ONLY -> PingResultCode.IPV6_ONLY
            latency == 0L -> PingResultCode.UNAVAILABLE
            else -> PingResultCode.FAILED_TIMEOUT
        }
    }

    protected fun recordLatencyResult(
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

    protected fun applyLatencyResultsToMemory(results: Map<String, SavedNodeLatency>) {
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

    private fun savedLatencyMs(nodeId: String): Long? = savedNodeLatencies[nodeId]?.latencyMs

    protected suspend fun applyLatencyResults(results: Map<String, SavedNodeLatency>) {
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

    private fun buildMeteredSafeLatencyOutbounds(
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

    protected fun buildLatencyRuntimeContext(
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
        val runtimeOutbounds = if (dnsOverrideConfig != null) {
            ConfigRepository.applyDnsOverrideDomainResolvers(defaultResolverOutbounds, dnsOverrideConfig)
        } else {
            defaultResolverOutbounds
        }
        val directDnsTags = ConfigRepository.resolveDnsOverrideDirectDnsServerTags(runtimeOutbounds, dnsOverrideConfig)
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

    protected fun buildNodeTestInfos(nodes: List<NodeUi>, settings: AppSettings): List<ConfigRepositoryNodeTestInfo> {
        return ConfigRepository.buildNodeTestInfosFromContexts(nodes) { profileId ->
            loadConfig(profileId)?.let { config ->
                buildLatencyRuntimeContext(profileId, config, settings)
            }
        }
    }

    protected suspend fun testRegularOutboundsLatency(
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

    @Suppress("LongMethod")
    protected suspend fun loadSavedProfiles() {
        try {
            val startTime = System.currentTimeMillis()
            val profileEntities = profileDao.getAll()
            val activeState = activeStateDao.get()
            val latencyEntities = nodeLatencyDao.getAll()

            if (profileEntities.isNotEmpty()) {
                val profiles = profileEntities.map { it.toUiModel().copy(updateStatus = UpdateStatus.Idle) }
                val (restoredProfileId, restoredNodeId) = resolveRestoredProfileSelection(
                    availableProfileIds = profiles.mapTo(mutableSetOf()) { it.id },
                    databaseProfileId = activeState?.activeProfileId,
                    databaseNodeId = activeState?.activeNodeId,
                    persistedProfileId = VpnStateStore.getSelectedProfileId(),
                    persistedNodeId = VpnStateStore.getSelectedNodeId()
                )
                _profiles.value = profiles
                _activeProfileId.value = restoredProfileId
                savedNodeLatencies.clear()
                latencyEntities.forEach { entity ->
                    savedNodeLatencies[entity.nodeId] = SavedNodeLatency(entity.latencyMs, entity.testedAt)
                }

                val elapsed = System.currentTimeMillis() - startTime
                Log.i(ConfigRepository.TAG, "Loaded ${profiles.size} profiles from Room in ${elapsed}ms")
                loadActiveProfileNodes(restoredProfileId, restoredNodeId)
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
                val (restoredProfileId, restoredNodeId) = resolveRestoredProfileSelection(
                    availableProfileIds = profiles.mapTo(mutableSetOf()) { it.id },
                    databaseProfileId = savedData.activeProfileId,
                    databaseNodeId = savedData.activeNodeId,
                    persistedProfileId = VpnStateStore.getSelectedProfileId(),
                    persistedNodeId = VpnStateStore.getSelectedNodeId()
                )
                _profiles.value = profiles
                _activeProfileId.value = restoredProfileId

                savedNodeLatencies.clear()
                savedNodeLatencies.putAll(
                    savedData.nodeLatencies.mapValues { (_, latency) -> SavedNodeLatency(latency, testedAt = 0L) }
                )
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
                    NodeLatencyEntity(nodeId = nodeId, latencyMs = latency, testedAt = 0L)
                }
                if (latencies.isNotEmpty()) {
                    scope.launch { nodeLatencyDao.insertAll(latencies) }
                }

                val elapsed = System.currentTimeMillis() - startTime
                Log.i(ConfigRepository.TAG, "Migrated ${profiles.size} profiles to Room in ${elapsed}ms")
                loadActiveProfileNodes(restoredProfileId, restoredNodeId)
                cleanupLegacyProfileFiles()
            }
        } catch (e: Exception) {
            Log.e(ConfigRepository.TAG, "Failed to load saved profiles", e)
        }
    }

    protected suspend fun loadActiveProfileNodes(activeProfileId: String?, activeNodeId: String?) {
        if (activeProfileId == null) return
        val configFile = File(configDir, "$activeProfileId.json")
        if (!configFile.exists()) return

        try {
            val configJson = configFile.readText()
            val config = deduplicateTags(gson.fromJson(configJson, SingBoxConfig::class.java))
            val nodes = extractNodesFromConfig(config, activeProfileId)
            val nodesWithLatency = nodes.map { node ->
                val latency = savedLatencyMs(node.id)
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

    @Suppress("ReturnCount")
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

        ConfigRepository.findUnsupportedAndroidCapabilityInJson(responseBody)?.let { message ->
            val error = IllegalArgumentException(message)
            Log.w(ConfigRepository.TAG, message)
            return ConfigRepositorySubscriptionAttemptResult(
                shouldStopFallback = true,
                terminalError = error
            )
        }

        val parsedConfig = subscriptionManager.parse(responseBody)
        parsedConfig?.let(ConfigRepository::findUnsupportedAndroidCapability)?.let { message ->
            val error = IllegalArgumentException(message)
            Log.w(ConfigRepository.TAG, message)
            return ConfigRepositorySubscriptionAttemptResult(
                shouldStopFallback = true,
                terminalError = error
            )
        }
        val config = parsedConfig?.let(::deduplicateTags)
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
        val error = Exception(
            this.context.getString(R.string.subscription_import_http_error, responseCode, responseMessage)
        )
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

            val responseBody = ConfigRepository.readSubscriptionResponseBody(response.body)
            if (responseBody.isNullOrBlank()) {
                logSubscriptionAttempt(
                    level = Log.WARN,
                    message = "Empty subscription response",
                    context = context,
                    costMs = costMs
                )
                throw IllegalStateException(
                    this@ConfigRepository.context.getString(R.string.subscription_import_empty_response)
                )
            }

            onStageChanged(SubscriptionUpdateStage.Parsing)
            onProgress(this@ConfigRepository.context.getString(R.string.subscription_import_parsing))

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
                onProgress(
                    context.getString(
                        R.string.subscription_import_requesting,
                        index + 1,
                        userAgents.size
                    )
                )
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
            onProgress(context.getString(R.string.subscription_import_fetching))
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
                preResolveDomainsForProfileBestEffort(profileId, deduplicatedConfig, dnsServer)
            }
            onProgress(context.getString(R.string.profiles_import_success, nodes.size.toString()))

            Result.success(profile)
        } catch (e: Exception) {
            profileId?.let { rollbackTransientProfileFile(it) }
            Log.e(ConfigRepository.TAG, "Subscription import failed", e)
            val msg = when (e) {
                is java.net.SocketTimeoutException -> context.getString(R.string.subscription_import_timeout)
                is java.net.UnknownHostException -> context.getString(R.string.subscription_import_dns_failed)
                is javax.net.ssl.SSLHandshakeException -> context.getString(R.string.subscription_import_ssl_failed)
                else -> e.message ?: context.getString(R.string.subscription_import_failed_generic)
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

    protected suspend fun resolveCustomProfileOutbounds(
        selectedNodeIds: List<String>,
        additionalOutbounds: List<Outbound>
    ): Result<List<Outbound>> {
        val targetNodes = if (selectedNodeIds.isEmpty()) {
            emptyList()
        } else {
            loadSelectedCustomNodes(selectedNodeIds)
        }
        val copiedOutbounds = collectCustomOutbounds(targetNodes)
        val outbounds = combineCustomProfileOutbounds(copiedOutbounds, additionalOutbounds)
        return if (outbounds.isEmpty()) {
            val message = if (selectedNodeIds.isEmpty()) {
                context.getString(R.string.custom_profile_nodes_required)
            } else {
                context.getString(R.string.custom_profile_extract_failed)
            }
            Result.failure(Exception(message))
        } else {
            Result.success(outbounds)
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
        selectedNodeIds: List<String>,
        additionalOutbounds: List<Outbound> = emptyList()
    ): Result<ProfileUi> = withContext(Dispatchers.IO) {
        var profileId: String? = null
        try {
            val outbounds = resolveCustomProfileOutbounds(selectedNodeIds, additionalOutbounds).getOrElse { error ->
                return@withContext Result.failure(error)
            }

            val newConfig = com.kunk.singbox.model.SingBoxConfig(outbounds = outbounds)
            profileId = UUID.randomUUID().toString()
            val deduplicatedConfig = deduplicateTags(newConfig)
            val nodes = extractNodesFromConfig(deduplicatedConfig, profileId, {})
            if (nodes.isEmpty()) {
                return@withContext Result.failure(
                    Exception(context.getString(R.string.nodes_no_valid_found))
                )
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
            ConfigRepository.findUnsupportedAndroidCapabilityInJson(normalized)?.let { message ->
                return@withContext Result.failure(IllegalArgumentException(message))
            }
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
        val normalizedConfig = ConfigRepository.normalizeWireGuardEndpointsForInternalUse(config)
        val outbounds = normalizedConfig.outbounds ?: normalizedConfig.proxies ?: emptyList()
        return SingBoxConfig(outbounds = outbounds)
    }

    protected fun extractOutboundsFromJson(jsonContent: String): List<Outbound>? {
        val trimmed = jsonContent.trim()
        if (!trimmed.startsWith("{")) return null

        return try {
            val parsedConfig = gson.fromJson(trimmed, SingBoxConfig::class.java)
            val normalizedConfig = ConfigRepository.normalizeWireGuardEndpointsForInternalUse(parsedConfig)
            normalizedConfig.outbounds?.takeIf(List<Outbound>::isNotEmpty)
                ?: normalizedConfig.proxies?.takeIf(List<Outbound>::isNotEmpty)
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

    fun parseNodeLinkForCustomProfile(link: String): Result<Outbound> {
        val normalizedLink = link.trim()
        if (!NodeLinkParser.isSupportedLink(normalizedLink)) {
            return Result.failure(Exception(context.getString(R.string.nodes_unsupported_format)))
        }
        return runCatching {
            parseNodeLink(normalizedLink)
                ?: throw IllegalArgumentException(context.getString(R.string.nodes_add_failed))
        }
    }

    protected suspend fun extractNodesFromConfig(
        config: SingBoxConfig,
        profileId: String,
        onProgress: ((String) -> Unit)? = null): List<NodeUi> {
        val outbounds = ConfigRepository.normalizeWireGuardEndpointsForInternalUse(config).outbounds
            ?: return emptyList()
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
        val outbounds = ConfigRepository.normalizeWireGuardEndpointsForInternalUse(config).outbounds
            ?: return emptyList()
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

    /** 订阅信息位 / 占位地址，不当作可测节点。 */
    private fun isPlaceholderNodeServer(server: String): Boolean {
        return when {
            server.equals("localhost", ignoreCase = true) -> true
            server in PLACEHOLDER_NODE_SERVERS -> true
            else -> false
        }
    }

    protected fun createNodeUi(
        outbound: Outbound,
        profileId: String,
        nodeToGroup: Map<String, String>,
        trafficRepo: TrafficRepository
    ): NodeUi? {
        if (outbound.tag.isBlank()) return null

        // 订阅信息位（剩余流量/套餐到期）常写成 127.0.0.1，不当作可测节点
        val server = outbound.server?.trim().orEmpty()
        if (isPlaceholderNodeServer(server)) {
            return null
        }

        var group = nodeToGroup[outbound.tag] ?: "Default"
        if (group.contains("://") || group.length > 50) {
            group = "Default"
        }

        val id = ConfigRepository.stableNodeId(profileId, outbound.tag)
        val meteredProtected = isNodeMeteredProtected(id)

        return NodeUi(
            id = id,
            name = outbound.tag,
            protocol = outbound.type,
            group = group,
            latencyMs = savedLatencyMs(id),
            isFavorite = false,
            sourceProfileId = profileId,
            trafficUsed = trafficRepo.getMonthlyTotal(id),
            autoSelectionEligible = isNodeAutoSelectionEligible(id) && !meteredProtected,
            meteredProtected = meteredProtected,
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

    fun setActiveNodeIdOnly(nodeId: String) {
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

    protected fun nodeDisplayName(nodeId: String, fallbackNodes: List<NodeUi>): String? {
        return _nodes.value.find { it.id == nodeId }?.name
            ?: fallbackNodes.find { it.id == nodeId }?.name
    }

    suspend fun setActiveNode(nodeId: String): Boolean {
        val result = setActiveNodeWithResult(nodeId)
        return result is ConfigRepository.NodeSwitchResult.Success || result is ConfigRepository.NodeSwitchResult.NotRunning
    }

    /** 配置卡优先选择安全节点；无安全候选时按记忆节点或稳定顺序明确选中。 */
    @Suppress("ReturnCount")
    suspend fun setActiveProfileWithResult(profileId: String): ConfigRepository.NodeSwitchResult {
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
    suspend fun enableAutoSelectionWithResult(profileId: String): ConfigRepository.NodeSwitchResult {
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

    private suspend fun requestFullRuntimeConfigReload(result: ConfigRepository.ConfigGenerationResult) {
        val coreMode = VpnStateStore.getMode()
        val previousGeneration = VpnStateStore.getRuntimeStateSnapshot().generation
        requestRuntimeConfigReload(result.path, result.activeNodeTag, coreMode)
        check(awaitRuntimeRunningAfter(previousGeneration)) { "Timed out waiting for reloaded core" }
        if (result.activeNodeTag?.endsWith("#AUTO", ignoreCase = true) == true) {
            check(awaitConcreteRuntimeLabel()) { "Automatic group did not resolve to a concrete node" }
        }
    }

    private fun requestRuntimeConfigReload(
        configPath: String,
        activeNodeTag: String?,
        coreMode: VpnStateStore.CoreMode
    ) {
        val intent = if (coreMode == VpnStateStore.CoreMode.PROXY) {
            Intent(context, ProxyOnlyService::class.java).apply {
                action = ProxyOnlyService.ACTION_START
                putExtra("node_id", _activeNodeId.value)
                putExtra("outbound_tag", activeNodeTag)
                putExtra(SingBoxService.EXTRA_PENDING_NODE_NAME, "")
                putExtra(ProxyOnlyService.EXTRA_CONFIG_PATH, configPath)
            }
        } else {
            Intent(context, SingBoxService::class.java).apply {
                action = SingBoxService.ACTION_START
                putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
                putExtra("node_id", _activeNodeId.value)
                putExtra("outbound_tag", activeNodeTag)
                putExtra(SingBoxService.EXTRA_PENDING_NODE_NAME, "")
                putExtra(SingBoxService.EXTRA_CONFIG_PATH, configPath)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private suspend fun awaitRuntimeRunningAfter(previousGeneration: Long): Boolean {
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
    private suspend fun awaitRuntimeSelectionAfter(
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

    private fun resolveRunningOutboundTags(configContent: String?): Set<String>? {
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

    private suspend fun awaitConcreteRuntimeLabel(): Boolean {
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

    private suspend fun restorePreviousRuntimeConfig(
        previousRunningConfig: String?,
        previousCoreMode: VpnStateStore.CoreMode
    ) {
        if (previousRunningConfig == null) return
        val runningConfigFile = File(context.filesDir, "running_config.json")
        if (!restoreRunningConfigSnapshot(previousRunningConfig)) return
        if (!VpnStateStore.isManuallyStopped() && previousCoreMode != VpnStateStore.CoreMode.NONE) {
            VpnStateStore.setMode(previousCoreMode)
            val previousGeneration = VpnStateStore.getRuntimeStateSnapshot().generation
            requestRuntimeConfigReload(
                runningConfigFile.absolutePath,
                activeNodeTag = null,
                coreMode = previousCoreMode
            )
            if (!awaitRuntimeRunningAfter(previousGeneration)) {
                Log.e(ConfigRepository.TAG, "Failed to restore previous runtime config")
            }
        }
    }

    private fun restoreRunningConfigSnapshot(configContent: String): Boolean {
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
    private fun restoreAutoSelectionState(
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

    suspend fun setActiveNodeWithResult(nodeId: String): ConfigRepository.NodeSwitchResult {
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

                    // ... [Skipping comments for brevity in replacement]
                    runCatching {
                        val oldCacheDb = File(context.filesDir, "cache.db")
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
                            putExtra(SingBoxService.EXTRA_PENDING_NODE_NAME, node.name)
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

    private fun commitManualSelectionState(
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
    private fun restoreManualSelectionState(
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

    suspend fun syncActiveNodeFromProxySelection(proxyName: String?): Boolean {
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

    suspend fun deleteProfile(profileId: String) = withContext(Dispatchers.IO) {
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
                        if (node.meteredProtected && !isMeteredNodeUseAuthorized(nodeId)) {
                            LogRepository.getInstance().addLog(
                                "WARN: 计费节点保护已阻止测速：${node.name}"
                            )
                            return@withContext -1L
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

    suspend fun clearAllNodesLatency() = withContext(Dispatchers.IO) {
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

    suspend fun testAllNodesLatency(
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

    protected fun buildSubscriptionUpdateSuccessResult(
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
    suspend fun generateConfigFile(
        selectedProfileId: String? = null,
        selectedNodeId: String? = null,
        forceManualSelection: Boolean = false
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
            val log = buildRunLogConfig()
            val experimental = buildRunExperimentalConfig(sanitizedSettings)
            val inbounds = buildRunInbounds(sanitizedSettings)
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
                config.dns
            )
            val route = buildRunRoute(
                sanitizedSettings,
                outboundsContext.selectorTag,
                outboundsContext.outbounds,
                outboundsContext.nodeTagResolver,
                customRuleSets
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
            val configFile = File(context.filesDir, "running_config.json")
            val runtimeConfigContent = gson.toJson(stripInternalMetadata(runConfig))
            check(NodeProtectionStore.replaceRuntimeMappings(runtimeMappings, runtimeConfigContent)) {
                "无法持久化运行时节点映射，已阻止启动"
            }
            ConfigRepository.writeTextFileAtomically(configFile, runtimeConfigContent)
            logRunningConfigPath(configFile, resolvedTag, allTags.size)

            ConfigRepository.ConfigGenerationResult(
                path = configFile.absolutePath,
                activeNodeTag = resolvedTag,
                outboundTags = allTags,
                activeNodeName = activeNode?.name.takeIf { activeAutoTag == null }
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
    private fun buildRuntimeNodeMappings(
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
            Log.i(ConfigRepository.TAG, "Repaired legacy ECH subscription config for profile: ${profile.name}")
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
        val domains = config.outbounds.orEmpty()
            .mapNotNull { it.server }
            .filterNot(DnsResolver::isIpAddress)
            .distinct()
        if (domains.isEmpty()) return

        val results = dnsResolver.resolveBatch(
            domains = domains,
            dohServer = dnsServer ?: DnsResolver.DOH_CLOUDFLARE
        )
        dnsResolveStore.saveBatch(profileId, results)
    }

    protected fun applyDnsResolveToOutbound(profileId: String, outbound: Outbound): Outbound {
        val server = outbound.server ?: return outbound
        if (DnsResolver.isIpAddress(server)) return outbound
        return dnsResolveStore.getIp(profileId, server)?.let { outbound.copy(server = it) } ?: outbound
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
                ConfigRepository.applyCustomRuleMatcher(baseRule, rule.type, values)
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
        // 特定服务规则集必须排在国家/地区泛化规则前，避免 geolocation-!cn 抢先吃掉 openai/google 等规则
        val orderedRuleSets = ConfigRepository.sortRuleSetsForRouting(
            settings.ruleSets.filter { it.enabled && it.tag in validTags }
        )

        orderedRuleSets.forEach { ruleSet ->
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
            val inboundTags = ConfigRepository.normalizeRuleSetInboundTags(ruleSet.inbounds)

            rules.add(baseRule.copy(
                ruleSet = listOf(ruleSet.tag),
                inbound = inboundTags
            ))
        }

        return rules
    }

    protected fun resolvePackagesSharingUid(packageNames: List<String>): List<String> {
        return ConfigRepository.expandSharedUidPackageNames(
            packageNames = packageNames,
            resolveUid = { packageName ->
                runCatching { context.packageManager.getApplicationInfo(packageName, 0).uid }.getOrNull()
            },
            resolvePackages = { uid ->
                context.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
            }
        )
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

        settings.appRules
            .filter { ConfigRepository.shouldApplyCustomAndAppRules(settings.routingMode) && it.enabled }
            .forEach { rule ->
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

                rules.add(
                    baseRule.copy(
                        packageName = resolvePackagesSharingUid(listOf(rule.packageName))
                    )
                )
            }
        settings.appGroups
            .filter { ConfigRepository.shouldApplyCustomAndAppRules(settings.routingMode) && it.enabled }
            .forEach { group ->
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
                val packageNames = resolvePackagesSharingUid(group.apps.map { it.packageName })
                if (packageNames.isNotEmpty()) {
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

    protected fun getOrCreateClashApiSecret(): String {
        val secretFile = File(context.noBackupFilesDir, "clash_api.secret")
        val existing = runCatching {
            secretFile.takeIf(File::isFile)?.readText(Charsets.UTF_8)
        }.getOrNull()
        val secret = ConfigRepository.resolveClashApiSecret(existing) {
            buildString {
                repeat(2) { append(UUID.randomUUID().toString().replace("-", "")) }
            }
        }
        if (secret != existing?.trim()) {
            ConfigRepository.writeTextFileAtomically(secretFile, secret)
        }
        return secret
    }

    protected fun buildRunExperimentalConfig(settings: AppSettings): ExperimentalConfig {
        val singboxDataDir = File(context.filesDir, "singbox_data").also { it.mkdirs() }

        val clashApiPort = findAvailablePort(9090)
        val clashApi = ClashApiConfig(
            externalController = "127.0.0.1:$clashApiPort",
            secret = getOrCreateClashApiSecret(),
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
        val customDomainDnsRules = mutableListOf<DnsRule>()
        val appDnsRules = mutableListOf<DnsRule>()
        val ruleSetDnsRules = mutableListOf<DnsRule>()

        val profiles = _profiles.value
        val proxyDetourTag = outboundsContext.selectorTag
        val proxyServerTag = ConfigRepository.buildDynamicDnsServerTag(proxyDetourTag)
        val directServerTag = "local"

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

        val localDnsAddr = ConfigRepository.normalizeLocalDns(settings.localDns)
        val remoteDnsAddr = ConfigRepository.normalizeRemoteDns(settings.remoteDns)
        val bootstrapStrategy = resolveDnsStrategy(settings.serverAddressStrategy, settings.ipVersionMode)
        val bootstrapTag = "dns-bootstrap"
        dnsServers.add(
            ConfigRepository.buildBootstrapDnsServer(
                localDnsAddress = localDnsAddr,
                tag = bootstrapTag,
                domainStrategy = bootstrapStrategy
            )
        )

        val echQueryServerTag = bootstrapTag
        dnsRules.addAll(
            ConfigRepository.buildEchAwareHttpsSvcbDnsRules(
                blockQuic = settings.blockQuic,
                outbounds = outboundsContext.outbounds,
                echQueryServerTag = echQueryServerTag
            )
        )
        val localResolver = ConfigRepository.buildDnsResolverForAddress(localDnsAddr)
        val localServer = ConfigRepository.buildDnsServer(
            address = localDnsAddr,
            tag = "local",
            domainStrategy = resolveDirectDnsStrategy(settings.directDnsStrategy, settings.ipVersionMode),
            domainResolver = localResolver
        )
        dnsServers.add(localServer)
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
                bootstrapV4Tag = bootstrapTag,
                bootstrapV6Tag = bootstrapTag,
                bootstrapTag = bootstrapTag
            )
        )

        if (settings.fakeDnsEnabled) {
            dnsServers.add(ConfigRepository.buildFakeIpDnsServer(settings.fakeIpRange))
        }
        val customDomainRulesForDns = settings.customRules
            .filter { ConfigRepository.shouldApplyCustomAndAppRules(settings.routingMode) && it.enabled }
            .filter {
                it.type == RuleType.DOMAIN ||
                    it.type == RuleType.DOMAIN_SUFFIX ||
                    it.type == RuleType.DOMAIN_KEYWORD ||
                    it.type == RuleType.GEOSITE
            }
        val domainSemantics = mutableListOf<ConfigRepository.OutboundSemantic>()

        if (customDomainRulesForDns.isNotEmpty()) {
            val orderedRules = mutableListOf<Pair<DnsRule, ConfigRepository.OutboundSemantic>>()

            fun addDnsRuleForSemantic(rule: DnsRule, semantic: ConfigRepository.OutboundSemantic) {
                domainSemantics.add(semantic)
                orderedRules.add(rule to semantic)
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

                ConfigRepository.buildCustomDnsRuleMatcher(rule.type, values.distinct())
                    ?.let { addDnsRuleForSemantic(it, semantic) }
            }

            customDomainDnsRules.addAll(
                ConfigRepository.buildOrderedDnsRules(
                    entries = orderedRules,
                    fakeDnsEnabled = settings.fakeDnsEnabled,
                    directServerTag = directServerTag,
                    proxyServerTag = proxyServerTag
                )
            )
        }
        val validRuleSetTags = validRuleSets.mapNotNull { it.tag }.toSet()
        val orderedRuleSetRules = mutableListOf<Pair<DnsRule, ConfigRepository.OutboundSemantic>>()
        val ruleSetSemantics = mutableListOf<ConfigRepository.OutboundSemantic>()

        fun addRuleSetDnsRule(rule: DnsRule, semantic: ConfigRepository.OutboundSemantic) {
            ruleSetSemantics.add(semantic)
            orderedRuleSetRules.add(rule to semantic)
        }

        ConfigRepository.sortRuleSetsForRouting(
            settings.ruleSets.filter {
                ConfigRepository.shouldApplyRuleSetRules(settings.routingMode) && it.enabled
            }
        ).forEach { ruleSet ->
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
            addRuleSetDnsRule(
                DnsRule(
                    ruleSet = listOf(tag),
                    inbound = ConfigRepository.normalizeRuleSetInboundTags(ruleSet.inbounds)
                ),
                semantic
            )
        }

        ruleSetDnsRules.addAll(
            ConfigRepository.buildOrderedDnsRules(
                entries = orderedRuleSetRules,
                fakeDnsEnabled = settings.fakeDnsEnabled,
                directServerTag = directServerTag,
                proxyServerTag = proxyServerTag
            )
        )
        val orderedPackageRules = mutableListOf<Pair<DnsRule, ConfigRepository.OutboundSemantic>>()
        val packageSemantics = mutableListOf<ConfigRepository.OutboundSemantic>()

        fun addPackageDnsRule(rule: DnsRule, semantic: ConfigRepository.OutboundSemantic) {
            packageSemantics.add(semantic)
            orderedPackageRules.add(rule to semantic)
        }

        settings.appRules
            .filter { ConfigRepository.shouldApplyCustomAndAppRules(settings.routingMode) && it.enabled }
            .forEach { rule ->
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
                val packageNames = resolvePackagesSharingUid(listOf(rule.packageName))
                if (packageNames.isNotEmpty()) {
                    addPackageDnsRule(DnsRule(packageName = packageNames), semantic)
                }
            }
        settings.appGroups
            .filter { ConfigRepository.shouldApplyCustomAndAppRules(settings.routingMode) && it.enabled }
            .forEach { group ->
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
                val packageNames = resolvePackagesSharingUid(group.apps.map { it.packageName })
                if (packageNames.isNotEmpty()) {
                    addPackageDnsRule(DnsRule(packageName = packageNames), semantic)
                }
            }

        appDnsRules.addAll(
            ConfigRepository.buildOrderedDnsRules(
                entries = orderedPackageRules,
                fakeDnsEnabled = settings.fakeDnsEnabled,
                directServerTag = directServerTag,
                proxyServerTag = proxyServerTag
            )
        )
        dnsRules.addAll(
            ConfigRepository.mergeUserDnsRules(
                domainRules = customDomainDnsRules,
                appRules = appDnsRules,
                ruleSetRules = ruleSetDnsRules
            )
        )
        ConfigRepository.ensureDynamicRemoteDnsServers(
            dnsServers = dnsServers,
            semantics = domainSemantics + ruleSetSemantics + packageSemantics,
            remoteDnsAddr = remoteDnsAddr,
            remoteStrategy = remoteStrategy,
            remoteResolver = remoteResolver
        )

        dnsRules.addAll(ConfigRepository.buildOutboundDomainResolverDnsRules(outboundsContext.outbounds))

        val finalServer = resolveRunDnsFinalServer(
            routingMode = settings.routingMode,
            defaultRule = settings.defaultRule,
            fakeDnsEnabled = settings.fakeDnsEnabled,
            proxyServerTag = proxyServerTag
        )

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
                dnsRules.addAll(
                    ConfigRepository.buildFakeIpExcludeDnsRules(
                        values = fakeIpExcludeDomains,
                        serverTag = finalServer
                    )
                )
            }
        }

        val fakeIpConfig = if (settings.fakeDnsEnabled) {
            ConfigRepository.buildFakeIpConfig(settings.fakeIpRange)
        } else {
            null
        }

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

        dnsRules.addAll(
            ConfigRepository.buildDefaultDnsBlockRules(
                routingMode = settings.routingMode,
                defaultRule = settings.defaultRule
            )
        )
        dnsRules.addAll(ConfigRepository.buildTunFakeIpDnsRulesStatic(settings.fakeDnsEnabled))

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

    protected fun buildRunEndpoints(
        baseConfig: SingBoxConfig,
        activeProfileId: String,
        allNodes: List<NodeUi>,
        nodeTagMap: Map<String, String>,
        excludedOutboundTags: Set<String> = emptySet()
    ): List<Endpoint>? {
        val convertedEndpoints = mutableListOf<Endpoint>()
        baseConfig.outbounds.orEmpty()
            .filterNot { it.tag in excludedOutboundTags }
            .mapNotNullTo(convertedEndpoints) {
                ConfigRepository.convertWireGuardOutboundToEndpoint(it)
            }

        val sourceConfigs = mutableMapOf<String, SingBoxConfig?>()
        nodeTagMap.forEach { (nodeId, runtimeTag) ->
            val node = allNodes.firstOrNull { it.id == nodeId } ?: return@forEach
            val sourceConfig = if (node.sourceProfileId == activeProfileId) {
                baseConfig
            } else {
                sourceConfigs.getOrPut(node.sourceProfileId) { loadConfig(node.sourceProfileId) }
            } ?: return@forEach
            val sourceOutbound = sourceConfig.outbounds.orEmpty().firstOrNull { it.tag == node.name }
                ?: sourceConfig.outbounds.orEmpty().firstOrNull { it.tag.equals(node.name, ignoreCase = true) }
                ?: return@forEach
            ConfigRepository.convertWireGuardOutboundToEndpoint(sourceOutbound, runtimeTag)
                ?.let(convertedEndpoints::add)
        }

        return ConfigRepository.mergeRuntimeEndpoints(
            convertedEndpoints = convertedEndpoints,
            existingEndpoints = baseConfig.endpoints.orEmpty().filterNot { it.tag in excludedOutboundTags }
        ).takeIf(List<Endpoint>::isNotEmpty)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod", "NestedBlockDepth")
    protected fun buildRunOutbounds(
        baseConfig: SingBoxConfig,
        activeProfileId: String,
        activeNode: NodeUi?,
        settings: AppSettings,
        allNodes: List<NodeUi>,
        dnsPreResolve: Boolean = false,
        dnsOverrideConfig: DnsConfig? = null,
        activeProfileAutoSelectionEnabled: Boolean = isProfileAutoSelectionEnabled(activeProfileId)
    ): ConfigRepositoryRunOutboundsContext {
        fun profileAutoSelectionEnabled(profileId: String): Boolean {
            return if (profileId == activeProfileId) {
                activeProfileAutoSelectionEnabled
            } else {
                isProfileAutoSelectionEnabled(profileId)
            }
        }

        fun resolveNodeRefToId(value: String?): String? {
            if (value.isNullOrBlank()) return null
            val parts = value.split("::", limit = 2)
            if (parts.size == 2) {
                val refProfileId = parts[0]
                val nodeName = parts[1]
                return allNodes.firstOrNull {
                    it.sourceProfileId == refProfileId && it.name == nodeName
                }?.id
            }
            if (allNodes.any { it.id == value }) return value
            return allNodes.firstOrNull { it.sourceProfileId == activeProfileId && it.name == value }?.id
                ?: allNodes.firstOrNull { it.name == value }?.id
        }

        val protectedNodes = allNodes.filter(NodeUi::meteredProtected)
        val protectedNodeIds = NodeProtectionStore.protectedNodeIds()
        val explicitNodeReferences = buildList {
            if (ConfigRepository.shouldApplyCustomAndAppRules(settings.routingMode)) {
                settings.appRules
                    .filter { it.enabled && it.outboundMode == RuleSetOutboundMode.NODE }
                    .mapNotNullTo(this) { it.outboundValue }
                settings.appGroups
                    .filter { it.enabled && it.outboundMode == RuleSetOutboundMode.NODE }
                    .mapNotNullTo(this) { it.outboundValue }
                settings.customRules
                    .filter { it.enabled && it.outboundMode == RuleSetOutboundMode.NODE }
                    .mapNotNullTo(this) { it.outboundValue }
            }
            if (ConfigRepository.shouldApplyRuleSetRules(settings.routingMode)) {
                settings.ruleSets
                    .filter { it.enabled && it.outboundMode == RuleSetOutboundMode.NODE }
                    .mapNotNullTo(this) { it.outboundValue }
            }
        }
        val explicitlyRoutedProtectedNodeIds = explicitNodeReferences
            .mapNotNull(::resolveNodeRefToId)
            .filterTo(mutableSetOf(), protectedNodeIds::contains)
        val allowedProtectedNodeId = activeNode
            ?.takeIf { node ->
                node.meteredProtected && NodeProtectionStore.isUseAuthorized(
                    nodeId = node.id,
                    activeNodeId = activeNode.id,
                    autoSelectionEnabled = activeProfileAutoSelectionEnabled
                )
            }
            ?.id
        MeteredNodeConfigGuard.requireNoViolations(
            MeteredNodeConfigGuard.findSettingsViolations(
                settings = settings,
                nodes = allNodes,
                allowedProtectedNodeId = allowedProtectedNodeId
            )
        )
        MeteredNodeConfigGuard.requireNoViolations(
            MeteredNodeConfigGuard.findSourceConfigViolations(
                config = baseConfig,
                sourceProfileId = activeProfileId,
                protectedNodeIds = protectedNodeIds,
                includeGroupReferences = false
            )
        )
        dnsOverrideConfig?.let { overrideConfig ->
            MeteredNodeConfigGuard.requireNoViolations(
                MeteredNodeConfigGuard.findSourceConfigViolations(
                    config = SingBoxConfig(dns = overrideConfig),
                    sourceProfileId = activeProfileId,
                    protectedNodeIds = protectedNodeIds,
                    includeGroupReferences = false
                ).map { violation -> "DNS 覆盖：$violation" }
            )
        }
        val routeOnlyProtectedNodeIds = explicitlyRoutedProtectedNodeIds - setOfNotNull(allowedProtectedNodeId)
        val disallowedProtectedNodes = protectedNodes.filter {
            it.id != allowedProtectedNodeId && it.id !in explicitlyRoutedProtectedNodeIds
        }
        val disallowedProtectedNodeIds = disallowedProtectedNodes.mapTo(mutableSetOf(), NodeUi::id)
        val disallowedProtectedTags = disallowedProtectedNodes
            .filter { it.sourceProfileId == activeProfileId }
            .mapTo(mutableSetOf(), NodeUi::name)
        val routeOnlyProtectedTags = protectedNodes
            .filter { it.sourceProfileId == activeProfileId && it.id in routeOnlyProtectedNodeIds }
            .mapTo(mutableSetOf(), NodeUi::name)
        val excludedAutomaticTags = allNodes.asSequence()
            .filter {
                it.sourceProfileId == activeProfileId &&
                    (!isNodeAutoSelectionEligible(it.id) || it.meteredProtected)
            }
            .map { it.name }
            .toSet()
        val rawOutbounds = baseConfig.outbounds
            ?.let { ConfigRepository.filterAutomaticGroupCandidates(it, excludedAutomaticTags) }
            ?.let { MeteredNodeConfigGuard.removeDisallowedNodes(it, disallowedProtectedTags) }
            ?.let { MeteredNodeConfigGuard.removeGroupReferences(it, routeOnlyProtectedTags) }
        rawOutbounds?.let { sanitizedOutbounds ->
            MeteredNodeConfigGuard.requireNoViolations(
                MeteredNodeConfigGuard.findSourceConfigViolations(
                    config = SingBoxConfig(outbounds = sanitizedOutbounds),
                    sourceProfileId = activeProfileId,
                    protectedNodeIds = protectedNodeIds,
                    includeGroupReferences = true,
                    includeDeclaredNodes = false,
                    allowedProtectedNodeId = allowedProtectedNodeId
                )
            )
        }
        val runtimeEndpointTags = buildSet {
            baseConfig.endpoints.orEmpty().mapTo(this) { it.tag }
            rawOutbounds.orEmpty()
                .filter { it.type.equals("wireguard", ignoreCase = true) }
                .mapTo(this) { it.tag }
        }.filter(String::isNotBlank).toMutableSet()
        if (rawOutbounds.isNullOrEmpty()) {
            Log.w(ConfigRepository.TAG, "No outbounds found in base config, adding defaults")
        }

        val fixedOutbounds = rawOutbounds?.mapNotNull { outbound ->
            var processed = buildOutboundForRuntime(outbound) ?: return@mapNotNull null
            val server = processed.server?.trim().orEmpty()
            if (dnsPreResolve && ConfigRepository.shouldApplyDnsPreResolveToDomain(
                    server,
                    dnsOverrideConfig,
                    processed.tag
                )
            ) {
                processed = applyDnsResolveToOutbound(activeProfileId, processed)
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
        val requiredNodeIds = explicitlyRoutedProtectedNodeIds.toMutableSet()
        val requiredProfileIds = mutableSetOf<String>()
        settings.appRules
            .filter { ConfigRepository.shouldApplyCustomAndAppRules(settings.routingMode) && it.enabled }
            .forEach { rule ->
                when (rule.outboundMode) {
                    RuleSetOutboundMode.NODE -> resolveNodeRefToId(rule.outboundValue)?.let { requiredNodeIds.add(it) }
                    RuleSetOutboundMode.PROFILE -> rule.outboundValue?.let { requiredProfileIds.add(it) }
                    else -> {}
                }
            }
        settings.appGroups
            .filter { ConfigRepository.shouldApplyCustomAndAppRules(settings.routingMode) && it.enabled }
            .forEach { group ->
                when (group.outboundMode) {
                    RuleSetOutboundMode.NODE -> resolveNodeRefToId(group.outboundValue)?.let { requiredNodeIds.add(it) }
                    RuleSetOutboundMode.PROFILE -> group.outboundValue?.let { requiredProfileIds.add(it) }
                    else -> {}
                }
            }
        settings.ruleSets
            .filter { ConfigRepository.shouldApplyRuleSetRules(settings.routingMode) && it.enabled }
            .forEach { ruleSet ->
                when (ruleSet.outboundMode) {
                    RuleSetOutboundMode.NODE -> resolveNodeRefToId(ruleSet.outboundValue)
                        ?.let { requiredNodeIds.add(it) }
                    RuleSetOutboundMode.PROFILE -> ruleSet.outboundValue?.let { requiredProfileIds.add(it) }
                    else -> {}
                }
            }
        settings.customRules
            .filter { ConfigRepository.shouldApplyCustomAndAppRules(settings.routingMode) && it.enabled }
            .forEach { rule ->
                when (rule.outboundMode) {
                    RuleSetOutboundMode.NODE -> resolveNodeRefToId(rule.outboundValue)?.let { requiredNodeIds.add(it) }
                    RuleSetOutboundMode.PROFILE -> rule.outboundValue?.let { requiredProfileIds.add(it) }
                    else -> {}
                }
            }
        fixedOutbounds.mapNotNull { it.detour }.forEach { detourValue ->
            resolveNodeRefToId(detourValue)?.let { requiredNodeIds.add(it) }
        }
        if (activeProfileAutoSelectionEnabled) {
            requiredProfileIds.add(activeProfileId)
        }
        activeNode?.takeIf { it.id !in disallowedProtectedNodeIds }
            ?.let { requiredNodeIds.add(it.id) }
        requiredProfileIds.forEach { requiredProfileId ->
            allNodes.filter {
                it.sourceProfileId == requiredProfileId && it.id !in disallowedProtectedNodeIds
            }.forEach { node ->
                requiredNodeIds.add(node.id)
            }
        }
        val nodeTagMap = mutableMapOf<String, String>()
        val existingTags = (fixedOutbounds.map { it.tag } + runtimeEndpointTags).toMutableSet()
        Log.d(ConfigRepository.TAG, "buildRunOutbounds: activeProfileId=$activeProfileId, existingTags count=${existingTags.size}")
        Log.d(ConfigRepository.TAG, "  existingTags (first 10): ${existingTags.take(10)}")
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
            MeteredNodeConfigGuard.requireNoViolations(
                MeteredNodeConfigGuard.findSourceConfigViolations(
                    config = SingBoxConfig(outbounds = listOf(sourceOutbound)),
                    sourceProfileId = sourceProfileId,
                    protectedNodeIds = protectedNodeIds,
                    includeGroupReferences = false
                ).map { violation -> "跨配置节点「${node.name}」：$violation" }
            )
            var finalTag = sourceOutbound.tag
            if (existingTags.contains(finalTag)) {
                val suffix = sourceProfileId.take(4)
                finalTag = "${finalTag}_$suffix"
                if (existingTags.contains(finalTag)) {
                    finalTag = "${finalTag}_${UUID.randomUUID().toString().take(4)}"
                }
            }
            if (sourceOutbound.type.equals("wireguard", ignoreCase = true)) {
                runtimeEndpointTags.add(finalTag)
                existingTags.add(finalTag)
                nodeTagMap[nodeId] = finalTag
                return@forEach
            }

            var fixedSourceOutbound = buildOutboundForRuntime(sourceOutbound)
            if (fixedSourceOutbound == null) {
                Log.w(ConfigRepository.TAG, "Skipping removed outbound type: ${sourceOutbound.type} (${sourceOutbound.tag})")
                return@forEach
            }
            if (finalTag != fixedSourceOutbound.tag) {
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
            val availableProfileNodes = allNodes
                .filter { it.sourceProfileId == requiredProfileId }
            val isProfileAutoSelectionEnabled = profileAutoSelectionEnabled(requiredProfileId)
            val explicitlySelectedNodeId = activeNode
                ?.takeIf {
                    requiredProfileId == activeProfileId &&
                        !isProfileAutoSelectionEnabled &&
                        (!it.meteredProtected || it.id == allowedProtectedNodeId)
                }
                ?.id
            val storedNodeId = getProfileLastSelectedNode(requiredProfileId)
            val rememberedNodeId = storedNodeId?.takeIf { rememberedId ->
                availableProfileNodes.any { it.id == rememberedId }
            } ?: availableProfileNodes.minByOrNull { it.id }?.id?.also { fallbackNodeId ->
                saveProfileNodeMemory(requiredProfileId, fallbackNodeId)
                LogRepository.getInstance().addAlwaysLog(
                    "INFO [CFG] profile_selection_fallback profile=$requiredProfileId node=$fallbackNodeId"
                )
            }
            val profileNodes = availableProfileNodes
                .sortedWith(compareBy<NodeUi> { if (it.id == rememberedNodeId) 0 else 1 }.thenBy { it.id })
            val nodeIds = profileNodes.map { it.id }
            val nodeTags = nodeIds.mapNotNull { nodeTagMap[it] }.distinct()
            val eligibleNodeTags = profileNodes
                .filter {
                    (isNodeAutoSelectionEligible(it.id) && !it.meteredProtected) ||
                        it.id == explicitlySelectedNodeId
                }
                .mapNotNull { nodeTagMap[it.id] }
                .distinct()
            val profileName = _profiles.value.find { it.id == requiredProfileId }?.name ?: "Profile"
            val tag = ConfigRepository.buildProfileRouteTag(requiredProfileId, profileName)
            if (nodeTags.isNotEmpty()) {
                val routeGroupOutbounds = ConfigRepository.buildProfileRouteGroupOutbounds(
                    groupTag = tag,
                    nodeTags = nodeTags,
                    eligibleNodeTags = eligibleNodeTags,
                    testUrl = settings.latencyTestUrl,
                    autoSelectionEnabled = isProfileAutoSelectionEnabled,
                    preferredNodeTag = rememberedNodeId?.let { nodeTagMap[it] }
                )
                if (routeGroupOutbounds.isNotEmpty()) {
                    val generatedTags = routeGroupOutbounds.map { it.tag }.toSet()
                    fixedOutbounds.removeAll { it.tag in generatedTags }
                    fixedOutbounds.addAll(0, routeGroupOutbounds)
                }
            }
        }
        val routeOnlyRuntimeTags = routeOnlyProtectedNodeIds.mapNotNullTo(mutableSetOf()) { nodeTagMap[it] }
        val proxyTags = fixedOutbounds.filter {
            it.tag !in routeOnlyRuntimeTags && it.type in listOf(
                "vless", "vmess", "trojan", "shadowsocks",
                "hysteria2", "hysteria", "anytls", "tuic",
                "ssh", "shadowtls", "http", "socks", "naive"
            )
        }.map { it.tag }
            .plus(runtimeEndpointTags.filterNot(routeOnlyRuntimeTags::contains))
            .distinct()
            .toMutableList()
        val selectorTag = "PROXY"
        val activeProfileName = _profiles.value.find { it.id == activeProfileId }?.name ?: "Profile"
        val activeAutoTag = ConfigRepository.buildRouteGroupAutoTag(
            ConfigRepository.buildProfileRouteTag(activeProfileId, activeProfileName)
        ).takeIf { autoTag ->
            activeProfileAutoSelectionEnabled && fixedOutbounds.any { it.tag == autoTag }
        }
        if (activeAutoTag != null) {
            proxyTags.add(0, activeAutoTag)
        }
        if (proxyTags.isEmpty()) {
            proxyTags.add("direct")
        }

        val selectorDefault = activeAutoTag ?: activeNode
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
            // 手动切换只影响新连接，避免全量中断触发应用和核心同时重连。
            interruptExistConnections = false
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
                    ?: if (fixedOutbounds.any { it.tag == value } || value in runtimeEndpointTags) value else null
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

        val selectorSafeOutbounds = applySelectorSafeOutbounds(detourNormalizedOutbounds, runtimeEndpointTags)

        val finalTags = selectorSafeOutbounds.map { it.tag }.toSet() + runtimeEndpointTags
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
            nodeTagMap = nodeTagMap,
            disallowedProtectedTags = disallowedProtectedTags,
            explicitlyRoutedProtectedNodeIds = explicitlyRoutedProtectedNodeIds,
            routeOnlyProtectedNodeIds = routeOnlyProtectedNodeIds
        )
    }

    protected fun applySelectorSafeOutbounds(
        outbounds: List<Outbound>,
        additionalTags: Set<String> = emptySet()
    ): List<Outbound> {
        return ConfigRepository.sanitizeSelectorSafeOutbounds(outbounds, additionalTags)
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

    @Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod", "LongMethod")
    protected fun buildRunRoute(
        settings: AppSettings,
        selectorTag: String,
        outbounds: List<Outbound>,
        nodeTagResolver: (String?) -> String?,
        validRuleSets: List<RuleSetConfig>
    ): RouteConfig {
        val profileUis = _profiles.value
        val appRoutingRules = if (ConfigRepository.shouldApplyCustomAndAppRules(settings.routingMode)) {
            buildAppRoutingRules(
                settings = settings,
                defaultProxyTag = selectorTag,
                outbounds = outbounds,
                profiles = profileUis,
                nodeTagResolver = nodeTagResolver
            )
        } else {
            emptyList()
        }
        val customRuleSetRules = if (ConfigRepository.shouldApplyRuleSetRules(settings.routingMode)) {
            buildCustomRuleSetRules(
                settings = settings,
                defaultProxyTag = selectorTag,
                outbounds = outbounds,
                profiles = profileUis,
                nodeTagResolver = nodeTagResolver,
                validRuleSets = validRuleSets
            )
        } else {
            emptyList()
        }

        val quicRule = buildQuicBlockRule(settings)
        val multicastRejectRules = buildMulticastRejectRules(settings)
        val bypassLanRules = buildBypassLanRules(settings)
        val icmpEchoRules = buildIcmpEchoRules(settings)
        val customDomainRules = if (ConfigRepository.shouldApplyCustomAndAppRules(settings.routingMode)) {
            buildCustomDomainRules(
                settings = settings,
                defaultProxyTag = selectorTag,
                outbounds = outbounds,
                profiles = profileUis,
                nodeTagResolver = nodeTagResolver
            )
        } else {
            emptyList()
        }
        val defaultRuleCatchAll = buildDefaultRules(settings, selectorTag)
        val hijackDnsRule = ConfigRepository.buildHijackDnsRulesStatic()
        val baseRules = hijackDnsRule + quicRule + multicastRejectRules + icmpEchoRules
        val allRules = ConfigRepository.selectRunRouteRulesStatic(
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

    /**
     * 直连 DNS 策略：双栈 + AUTO 时强制 ipv4_only。
     * prefer_ipv4 仍会返回 AAAA，无公网 IPv6 时 geosite-cn 直连会 network unreachable。
     * 已保存为 AUTO 的旧配置也会走此路径。
     */
    protected fun resolveDirectDnsStrategy(strategy: DnsStrategy, mode: IpVersionMode): String {
        return ConfigRepository.resolveDirectDnsStrategy(strategy, mode)
    }

    protected fun logOutboundServerAddressStrategy(
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

    private suspend fun removeNodeLatencies(nodeIds: Collection<String>) {
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

    suspend fun updateNode(
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

    private fun persistUpdatedNodeConfig(
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
    private suspend fun applyNodeAutoSelectionEligibilityChange(
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

    private fun disableProfileAutoSelectionWithoutCandidates(
        profileId: String,
        autoSelectionEligible: Boolean,
        refreshedNodes: List<NodeUi>
    ) {
        if (autoSelectionEligible || refreshedNodes.any { it.autoSelectionEligible }) return
        if (!saveProfileAutoSelection(profileId, false)) {
            Log.e(ConfigRepository.TAG, "Failed to disable automatic selection for empty candidate set")
        }
    }

    private suspend fun leaveNewlyProtectedActiveNode(refreshedNodes: List<NodeUi>): Boolean {
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

    private fun resetRuntimeConnectionsForMeteredProtection() {
        val intent = when (VpnStateStore.getMode()) {
            VpnStateStore.CoreMode.PROXY -> Intent(context, ProxyOnlyService::class.java).apply {
                action = ProxyOnlyService.ACTION_RESET_CONNECTIONS
            }
            VpnStateStore.CoreMode.VPN -> Intent(context, SingBoxService::class.java).apply {
                action = SingBoxService.ACTION_RESET_CONNECTIONS
            }
            VpnStateStore.CoreMode.NONE -> return
        }
        context.startService(intent)
    }

    private fun stopRuntimeForMeteredProtection() {
        val intent = when (VpnStateStore.getMode()) {
            VpnStateStore.CoreMode.PROXY -> Intent(context, ProxyOnlyService::class.java).apply {
                action = ProxyOnlyService.ACTION_STOP
            }
            VpnStateStore.CoreMode.VPN -> Intent(context, SingBoxService::class.java).apply {
                action = SingBoxService.ACTION_STOP
            }
            VpnStateStore.CoreMode.NONE -> return
        }
        context.startService(intent)
        LogRepository.getInstance().addAlwaysLog(
            "WARN [METERED_GUARD] stopped runtime because protected-node paths could not be purged safely"
        )
    }

    private fun shouldReloadNodeSettingsChange(): Boolean {
        // 运行配置可能通过应用分流或 detour 引用其他配置中的节点。
        if (SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value) return true
        return VpnStateStore.getActive()
    }

    protected suspend fun refreshNodesAfterNodeMutation(
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

        private val PLACEHOLDER_NODE_SERVERS = setOf("127.0.0.1", "0.0.0.0", "::1")

        private const val TAILSCALE_UNSUPPORTED_MESSAGE =
            "Tailscale 不受支持：为控制 APK 体积，当前 Android 内核未编译 with_tailscale，" +
                "请移除 Tailscale endpoint 或 DNS server 后重试"
        private const val TOR_UNSUPPORTED_MESSAGE =
            "Tor 不受支持：当前 Android 内核未包含嵌入式 Tor，应用也未打包 Tor 可执行文件，" +
                "请移除 Tor outbound 后重试"

        internal fun findUnsupportedAndroidCapability(config: SingBoxConfig): String? {
            val configuredTypes = buildList {
                config.endpoints.orEmpty().mapTo(this) { it.type }
                config.outbounds.orEmpty().mapTo(this) { it.type }
                config.proxies.orEmpty().mapTo(this) { it.type }
                config.dns?.servers.orEmpty().mapTo(this) { it.type.orEmpty() }
            }
            return unsupportedAndroidCapabilityMessage(configuredTypes)
        }

        internal fun findUnsupportedAndroidCapabilityInJson(content: String): String? {
            val root = runCatching { JsonParser.parseString(content) }.getOrNull() ?: return null
            val configuredTypes = jsonConfigObjects(root).flatMap { it.configuredTypes() }
            return unsupportedAndroidCapabilityMessage(configuredTypes)
        }

        private fun unsupportedAndroidCapabilityMessage(configuredTypes: Iterable<String?>): String? {
            val normalizedTypes = configuredTypes.mapNotNull { type ->
                type?.trim()?.takeIf { it.isNotEmpty() }
            }
            return when {
                normalizedTypes.any { it.equals("tailscale", ignoreCase = true) } -> TAILSCALE_UNSUPPORTED_MESSAGE
                normalizedTypes.any { it.equals("tor", ignoreCase = true) } -> TOR_UNSUPPORTED_MESSAGE
                else -> null
            }
        }

        private fun jsonConfigObjects(root: com.google.gson.JsonElement): List<JsonObject> {
            return when {
                root.isJsonArray -> root.asJsonArray.mapNotNull { value ->
                    value.takeIf { it.isJsonObject }?.asJsonObject
                }
                root.isJsonObject -> listOf(root.asJsonObject)
                else -> emptyList()
            }
        }

        private fun JsonObject.configuredTypes(): List<String> {
            return buildList {
                configuredType()?.let { add(it) }
                listOf("endpoints", "outbounds", "proxies", "servers").forEach { key ->
                    addAll(arrayConfiguredTypes(key))
                }
                get("dns")
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.arrayConfiguredTypes("servers")
                    ?.let { addAll(it) }
            }
        }

        private fun JsonObject.configuredType(): String? {
            return get("type")?.takeIf { it.isJsonPrimitive }?.asString
        }

        private fun JsonObject.arrayConfiguredTypes(key: String): List<String> {
            return get(key)
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { value -> value.takeIf { it.isJsonObject }?.asJsonObject?.configuredType() }
                .orEmpty()
        }

        internal fun buildLatencyProbeTag(nodeId: String): String {
            return "latency-probe-$nodeId"
        }

        /** 延迟探测用：WireGuard 不走 OutboundFixer，仅规范化 peers 后保留逻辑 outbound。 */
        internal fun prepareLatencyRuntimeOutbound(
            outbound: Outbound,
            buildNonWireGuard: (Outbound) -> Outbound?
        ): Outbound? {
            if (outbound.type.equals("wireguard", ignoreCase = true)) {
                return outbound.copy(peers = normalizeWireGuardPeersForRuntime(outbound.peers))
            }
            return buildNonWireGuard(outbound)
        }

        internal fun buildLatencyRuntimeOutbounds(
            config: SingBoxConfig,
            buildNonWireGuard: (Outbound) -> Outbound?
        ): List<Outbound> {
            val normalized = normalizeWireGuardEndpointsForInternalUse(config)
            return normalized.outbounds.orEmpty().mapNotNull { outbound ->
                prepareLatencyRuntimeOutbound(outbound, buildNonWireGuard)
            }
        }

        internal fun resolveLatencyRuntimeDetours(
            sourceProfileId: String,
            sourceOutbounds: List<Outbound>,
            isProtectedReference: (sourceProfileId: String, reference: String) -> Boolean = { _, _ -> false },
            loadProfileOutbounds: (String) -> List<Outbound>?
        ): List<Outbound> {
            return LatencyRuntimeDetourResolver(
                sourceProfileId,
                sourceOutbounds,
                isProtectedReference,
                loadProfileOutbounds
            ).resolve()
        }

        private class LatencyRuntimeDetourResolver(
            private val sourceProfileId: String,
            private val sourceOutbounds: List<Outbound>,
            private val isProtectedReference: (sourceProfileId: String, reference: String) -> Boolean,
            private val loadProfileOutbounds: (String) -> List<Outbound>?
        ) {
            private val outboundsByProfile = mutableMapOf(sourceProfileId to sourceOutbounds)
            private val runtimeTags = sourceOutbounds.associate { outbound ->
                (sourceProfileId to outbound.tag) to outbound.tag
            }.toMutableMap()
            private val usedTags = sourceOutbounds.mapTo(mutableSetOf()) { it.tag }
            private val resolving = mutableSetOf<Pair<String, String>>()
            private val resolvedOutbounds = linkedMapOf<Pair<String, String>, Outbound>()
            private val blockedOutbounds = mutableSetOf<Pair<String, String>>()
            private val groupTypes = setOf("selector", "urltest", "url-test")

            fun resolve(): List<Outbound> {
                val sourceKeys = sourceOutbounds.map { sourceProfileId to it.tag }
                sourceKeys.forEach { resolveOutbound(it) }
                val sourceKeySet = sourceKeys.toSet()
                return buildList {
                    sourceKeys.mapNotNullTo(this) { resolvedOutbounds[it] }
                    resolvedOutbounds.forEach { (key, outbound) ->
                        if (key !in sourceKeySet) add(outbound)
                    }
                }
            }

            private fun outboundsFor(profileId: String): List<Outbound>? {
                outboundsByProfile[profileId]?.let { return it }
                return loadProfileOutbounds(profileId)?.also { outboundsByProfile[profileId] = it }
            }

            private fun resolveReference(profileId: String, reference: String): Pair<String, String>? {
                val parts = reference.split("::", limit = 2)
                val targetProfileId = if (parts.size == 2) parts[0] else profileId
                val targetTag = if (parts.size == 2) parts[1] else reference
                if (targetProfileId.isBlank() || targetTag.isBlank()) return null
                val targetExists = outboundsFor(targetProfileId)?.any { it.tag == targetTag } == true
                return (targetProfileId to targetTag).takeIf { targetExists }
            }

            private fun allocateRuntimeTag(key: Pair<String, String>): String {
                runtimeTags[key]?.let { return it }
                val (profileId, originalTag) = key
                var candidate = originalTag
                if (candidate in usedTags) {
                    val suffix = profileId.take(8).ifBlank { "profile" }
                    val base = "$originalTag#latency-$suffix"
                    candidate = base
                    var index = 2
                    while (candidate in usedTags) {
                        candidate = "$base-$index"
                        index++
                    }
                }
                runtimeTags[key] = candidate
                usedTags.add(candidate)
                return candidate
            }

            @Suppress("ReturnCount")
            private fun resolveOutbound(key: Pair<String, String>): String? {
                if (key in blockedOutbounds) return null
                val knownTag = resolvedOutbounds[key]?.tag ?: runtimeTags[key]?.takeIf { key in resolving }
                if (knownTag != null) return knownTag
                val (profileId, sourceTag) = key
                val source = outboundsFor(profileId)?.firstOrNull { it.tag == sourceTag } ?: return null
                if (isProtectedReference(profileId, sourceTag)) {
                    return blockOutbound(key)
                }
                val runtimeTag = allocateRuntimeTag(key)
                resolving.add(key)
                val detour = source.detour?.takeIf { it.isNotBlank() }
                if (detour != null && isProtectedReference(profileId, detour)) {
                    return blockOutbound(key)
                }
                val detourKey = detour?.let { resolveReference(profileId, it) }
                val resolvedDetour = detourKey?.let { resolveOutbound(it) } ?: detour
                if (detourKey in blockedOutbounds) {
                    return blockOutbound(key)
                }
                val groupSafeSource = resolveGroupReferences(profileId, source) ?: return blockOutbound(key)
                resolvedOutbounds[key] = groupSafeSource.copy(tag = runtimeTag, detour = resolvedDetour)
                resolving.remove(key)
                return runtimeTag
            }

            private fun resolveGroupReferences(profileId: String, source: Outbound): Outbound? {
                if (source.type.lowercase() !in groupTypes) return source

                fun resolveGroupReference(reference: String): String? {
                    if (isProtectedReference(profileId, reference)) return null
                    val targetKey = resolveReference(profileId, reference) ?: return reference
                    val runtimeTag = resolveOutbound(targetKey)
                    return runtimeTag?.takeUnless { targetKey in blockedOutbounds }
                }

                val candidates = source.outbounds.orEmpty().map { reference ->
                    resolveGroupReference(reference) ?: return null
                }
                val default = source.default?.let { reference ->
                    resolveGroupReference(reference) ?: return null
                }
                return source.copy(outbounds = candidates, default = default)
            }

            private fun blockOutbound(key: Pair<String, String>): String? {
                resolving.remove(key)
                resolvedOutbounds.remove(key)
                blockedOutbounds.add(key)
                return null
            }
        }

        internal fun buildNodeTestInfosFromContexts(
            nodes: List<NodeUi>,
            loadContext: (String) -> ConfigRepositoryLatencyRuntimeContext?
        ): List<ConfigRepositoryNodeTestInfo> {
            return nodes.groupBy { it.sourceProfileId }.flatMap { (profileId, profileNodes) ->
                val context = loadContext(profileId) ?: return@flatMap emptyList()
                val outboundsByTag = context.outbounds.associateBy { it.tag }
                profileNodes.mapNotNull { node ->
                    val outbound = outboundsByTag[node.name] ?: return@mapNotNull null
                    ConfigRepositoryNodeTestInfo(
                        outbound = outbound,
                        nodeId = node.id,
                        profileId = profileId,
                        dnsConfig = context.dnsConfig,
                        allOutbounds = context.outbounds
                    )
                }
            }
        }

        internal fun applyLatencyResultsToNodes(
            nodes: List<NodeUi>,
            results: Map<String, Long>
        ): List<NodeUi> {
            if (results.isEmpty()) return nodes
            return nodes.map { node ->
                val latency = results[node.id] ?: return@map node
                node.copy(latencyMs = latency)
            }
        }

        internal val ROUTE_GROUP_AUTO_TAG_SUFFIX = "#AUTO"

        private const val RUNTIME_RELOAD_POLL_INTERVAL_MS = 250L
        private const val RUNTIME_RELOAD_TIMEOUT_MS = 30_000L
        private const val MANUAL_HOT_SWITCH_CONFIRMATION_TIMEOUT_MS = 3_000L
        private const val AUTO_GROUP_RESOLUTION_TIMEOUT_MS = 10_000L

        internal fun resolveOutboundServerAddressStrategy(
            strategy: DnsStrategy,
            ipVersionMode: IpVersionMode
        ): String {
            return ipVersionMode.resolveDnsStrategy(strategy)
        }

        /**
         * 直连 DNS：双栈下 AUTO 映射为 ipv4_only，避免无 IPv6 出口时国内站 AAAA 直连失败。
         * 用户显式选择 PREFER_IPV4/PREFER_IPV6 等时仍按原规则解析。
         */
        internal fun resolveDirectDnsStrategy(
            strategy: DnsStrategy,
            ipVersionMode: IpVersionMode
        ): String {
            if (ipVersionMode == IpVersionMode.DUAL_STACK && strategy == DnsStrategy.AUTO) {
                return "ipv4_only"
            }
            return ipVersionMode.resolveDnsStrategy(strategy)
        }

        internal fun buildOutboundServerAddressStrategyLog(
            scope: String,
            strategy: DnsStrategy,
            ipVersionMode: IpVersionMode,
            resolvedStrategy: String
        ): String {
            return "INFO [CFG] outbound_server_domain_resolver scope=$scope " +
                "serverAddressStrategy=${strategy.name} " +
                "ipVersionMode=${ipVersionMode.name} " +
                "strategy=$resolvedStrategy"
        }

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

        internal fun shouldActivateCreatedNode(activeProfileId: String?): Boolean {
            return activeProfileId == null
        }

        internal fun resolveManualProfileTarget(
            nodes: List<NodeUi>,
            rememberedNodeId: String?,
            autoSelectionEnabled: Boolean
        ): NodeUi? {
            val candidates = nodes.filter { node ->
                !node.meteredProtected && (!autoSelectionEnabled || node.autoSelectionEligible)
            }
            return candidates.firstOrNull { it.id == rememberedNodeId }
                ?: candidates.minByOrNull(NodeUi::id)
                ?: nodes.firstOrNull { it.id == rememberedNodeId }
                ?: nodes.minByOrNull(NodeUi::id)
        }

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

        internal fun buildProfileRouteTag(profileId: String, profileName: String): String {
            val readableName = profileName.trim().ifBlank { "Profile" }
            return "P:$readableName#$profileId"
        }

        internal fun buildConfigWithOutboundsPreservingProfileSettings(
            existingConfig: SingBoxConfig?,
            outbounds: List<Outbound>
        ): SingBoxConfig {
            return existingConfig?.copy(outbounds = outbounds) ?: SingBoxConfig(outbounds = outbounds)
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

        internal fun sanitizeSelectorSafeOutbounds(
            outbounds: List<Outbound>,
            additionalTags: Set<String> = emptySet()
        ): List<Outbound> {
            val allOutboundTags = outbounds.map { it.tag }.toSet() + additionalTags
            return outbounds.map { outbound ->
                if (outbound.type == "selector" || outbound.type == "urltest" || outbound.type == "url-test") {
                    sanitizeSelectorLikeOutbound(outbound, allOutboundTags)
                } else {
                    outbound
                }
            }
        }

        internal fun filterAutomaticGroupCandidates(
            outbounds: List<Outbound>,
            excludedNodeTags: Set<String>
        ): List<Outbound> {
            if (excludedNodeTags.isEmpty()) return outbounds
            return outbounds.map { outbound ->
                if (outbound.type == "urltest" || outbound.type == "url-test") {
                    outbound.copy(outbounds = outbound.outbounds?.filterNot(excludedNodeTags::contains))
                } else {
                    outbound
                }
            }
        }

        @Suppress("CyclomaticComplexMethod")
        internal fun pruneUnreachableGroupOutbounds(
            outbounds: List<Outbound>,
            route: RouteConfig,
            dns: DnsConfig,
            endpoints: List<Endpoint> = emptyList()
        ): List<Outbound> {
            val groupTypes = setOf("selector", "urltest", "url-test")
            val groupsByTag = outbounds
                .filter { it.type in groupTypes }
                .associateBy { it.tag }
            if (groupsByTag.isEmpty()) return outbounds

            val reachableGroups = mutableSetOf<String>()
            val pendingGroups = mutableListOf<String>()
            fun enqueue(tag: String?) {
                if (!tag.isNullOrBlank() && tag in groupsByTag && reachableGroups.add(tag)) {
                    pendingGroups.add(tag)
                }
            }

            enqueue(route.finalOutbound)
            route.rules.orEmpty().forEach { enqueue(it.outbound) }
            route.ruleSet.orEmpty().forEach { enqueue(it.downloadDetour) }
            dns.servers.orEmpty().forEach { enqueue(it.detour) }
            outbounds.filter { it.type !in groupTypes }.forEach { enqueue(it.detour) }
            endpoints.forEach { enqueue(it.detour) }

            while (pendingGroups.isNotEmpty()) {
                val group = groupsByTag.getValue(pendingGroups.removeAt(pendingGroups.lastIndex))
                group.outbounds.orEmpty().forEach(::enqueue)
                enqueue(group.detour)
            }

            val pruned = outbounds.filter { it.type !in groupTypes || it.tag in reachableGroups }
            if (pruned.size != outbounds.size) {
                val removedTags = outbounds.asSequence()
                    .filter { it.type in groupTypes && it.tag !in reachableGroups }
                    .map { it.tag }
                    .toList()
                Log.i(TAG, "Pruned unreachable runtime groups: $removedTags")
            }
            return pruned
        }

        internal fun sanitizeSelectorLikeOutbound(outbound: Outbound, allOutboundTags: Set<String>): Outbound {
            val safeRefs = outbound.outbounds?.filter { allOutboundTags.contains(it) }.orEmpty()

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

        internal fun expandSharedUidPackageNames(
            packageNames: List<String>,
            resolveUid: (String) -> Int?,
            resolvePackages: (Int) -> List<String>
        ): List<String> {
            return packageNames.asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .flatMap { packageName ->
                    val uid = runCatching { resolveUid(packageName) }.getOrNull()
                    val sharedPackages = uid
                        ?.let { runCatching { resolvePackages(it) }.getOrDefault(emptyList()) }
                        .orEmpty()
                    (sharedPackages + packageName).asSequence()
                }
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .toList()
        }

        /** WireGuard 缺 allowed_ips 时隧道无路由，手填节点常见遗漏。 */
        internal val DEFAULT_WIREGUARD_ALLOWED_IPS = listOf("0.0.0.0/0", "::/0")

        internal fun normalizeWireGuardPeersForRuntime(peers: List<WireGuardPeer>?): List<WireGuardPeer>? {
            if (peers.isNullOrEmpty()) return peers
            return peers.map { peer ->
                if (!peer.allowedIps.isNullOrEmpty()) {
                    peer
                } else {
                    peer.copy(allowedIps = DEFAULT_WIREGUARD_ALLOWED_IPS)
                }
            }
        }

        internal fun convertWireGuardOutboundToEndpoint(
            outbound: Outbound,
            runtimeTag: String = outbound.tag
        ): Endpoint? {
            if (!outbound.type.equals("wireguard", ignoreCase = true)) return null
            return Endpoint(
                type = "wireguard",
                tag = runtimeTag,
                system = outbound.system,
                name = outbound.endpointName,
                mtu = outbound.mtu,
                address = outbound.localAddress,
                privateKey = outbound.privateKey?.firstOrNull(),
                listenPort = outbound.listenPort,
                peers = normalizeWireGuardPeersForRuntime(outbound.peers),
                udpTimeout = outbound.udpTimeout,
                workers = outbound.workers,
                detour = outbound.detour,
                bindInterface = outbound.bindInterface,
                inet4BindAddress = outbound.inet4BindAddress,
                inet6BindAddress = outbound.inet6BindAddress,
                bindAddressNoPort = outbound.bindAddressNoPort,
                protectPath = outbound.protectPath,
                routingMark = outbound.routingMark,
                reuseAddr = outbound.reuseAddr,
                netns = outbound.netns,
                connectTimeout = outbound.connectTimeout,
                tcpFastOpen = outbound.tcpFastOpen,
                tcpMultiPath = outbound.tcpMultiPath,
                disableTcpKeepAlive = outbound.disableTcpKeepAlive,
                tcpKeepAlive = outbound.tcpKeepAlive,
                tcpKeepAliveInterval = outbound.tcpKeepAliveInterval,
                udpFragment = outbound.udpFragment,
                networkStrategy = outbound.networkStrategy,
                networkType = outbound.networkType,
                fallbackNetworkType = outbound.fallbackNetworkType,
                fallbackDelay = outbound.fallbackDelay,
                domainStrategy = outbound.domainStrategy,
                domainResolver = outbound.domainResolver
            )
        }

        internal fun convertWireGuardEndpointToOutbound(endpoint: Endpoint): Outbound? {
            if (!endpoint.type.equals("wireguard", ignoreCase = true)) return null
            return Outbound(
                type = "wireguard",
                tag = endpoint.tag,
                system = endpoint.system,
                endpointName = endpoint.name,
                mtu = endpoint.mtu,
                localAddress = endpoint.address,
                privateKey = endpoint.privateKey?.let(::listOf),
                listenPort = endpoint.listenPort,
                peers = normalizeWireGuardPeersForRuntime(endpoint.peers),
                udpTimeout = endpoint.udpTimeout,
                workers = endpoint.workers,
                detour = endpoint.detour,
                bindInterface = endpoint.bindInterface,
                inet4BindAddress = endpoint.inet4BindAddress,
                inet6BindAddress = endpoint.inet6BindAddress,
                bindAddressNoPort = endpoint.bindAddressNoPort,
                protectPath = endpoint.protectPath,
                routingMark = endpoint.routingMark,
                reuseAddr = endpoint.reuseAddr,
                netns = endpoint.netns,
                connectTimeout = endpoint.connectTimeout,
                tcpFastOpen = endpoint.tcpFastOpen,
                tcpMultiPath = endpoint.tcpMultiPath,
                disableTcpKeepAlive = endpoint.disableTcpKeepAlive,
                tcpKeepAlive = endpoint.tcpKeepAlive,
                tcpKeepAliveInterval = endpoint.tcpKeepAliveInterval,
                udpFragment = endpoint.udpFragment,
                networkStrategy = endpoint.networkStrategy,
                networkType = endpoint.networkType,
                fallbackNetworkType = endpoint.fallbackNetworkType,
                fallbackDelay = endpoint.fallbackDelay,
                domainStrategy = endpoint.domainStrategy,
                domainResolver = endpoint.domainResolver
            )
        }

        internal fun normalizeWireGuardEndpointsForInternalUse(config: SingBoxConfig): SingBoxConfig {
            val wireGuardEndpoints = config.endpoints.orEmpty()
                .mapNotNull(::convertWireGuardEndpointToOutbound)
            if (wireGuardEndpoints.isEmpty()) return config

            val outboundsByTag = linkedMapOf<String, Outbound>()
            config.outbounds.orEmpty().forEach { outboundsByTag[it.tag] = it }
            wireGuardEndpoints.forEach { endpointOutbound ->
                val existing = outboundsByTag[endpointOutbound.tag]
                if (existing == null || existing.type.equals("wireguard", ignoreCase = true)) {
                    outboundsByTag[endpointOutbound.tag] = endpointOutbound
                }
            }
            val remainingEndpoints = config.endpoints.orEmpty()
                .filterNot { it.type.equals("wireguard", ignoreCase = true) }
                .takeIf(List<Endpoint>::isNotEmpty)
            return config.copy(
                endpoints = remainingEndpoints,
                outbounds = outboundsByTag.values.toList()
            )
        }

        internal fun mergeRuntimeEndpoints(
            convertedEndpoints: List<Endpoint>,
            existingEndpoints: List<Endpoint>
        ): List<Endpoint> {
            val byTag = linkedMapOf<String, Endpoint>()
            convertedEndpoints.filter { it.tag.isNotBlank() }.forEach { byTag[it.tag] = it }
            existingEndpoints.filter { it.tag.isNotBlank() }.forEach { byTag[it.tag] = it }
            return byTag.values.toList()
        }

        internal fun normalizeRuleSetInboundTags(inbounds: List<String>?): List<String>? {
            return inbounds.orEmpty()
                .map(String::trim)
                .filter(String::isNotBlank)
                .map {
                    when (it) {
                        "tun" -> "tun-in"
                        "mixed" -> "mixed-in"
                        else -> it
                    }
                }
                .distinct()
                .takeIf(List<String>::isNotEmpty)
        }

        internal fun mergeUserDnsRules(
            domainRules: List<DnsRule>,
            appRules: List<DnsRule>,
            ruleSetRules: List<DnsRule>
        ): List<DnsRule> = domainRules + appRules + ruleSetRules

        internal fun shouldApplyCustomAndAppRules(routingMode: RoutingMode): Boolean {
            return routingMode == RoutingMode.RULE
        }

        internal fun shouldApplyRuleSetRules(routingMode: RoutingMode): Boolean {
            return routingMode == RoutingMode.RULE
        }

        @Suppress("LongParameterList")
        internal fun selectRunRouteRulesStatic(
            settings: AppSettings,
            baseRules: List<RouteRule>,
            bypassLanRules: List<RouteRule>,
            customDomainRules: List<RouteRule>,
            appRoutingRules: List<RouteRule>,
            customRuleSetRules: List<RouteRule>,
            defaultRuleCatchAll: List<RouteRule>
        ): List<RouteRule> {
            return when (settings.routingMode) {
                RoutingMode.GLOBAL_PROXY -> baseRules
                RoutingMode.GLOBAL_DIRECT -> baseRules + listOf(RouteRule(outbound = "direct"))
                RoutingMode.RULE -> baseRules + bypassLanRules + customDomainRules + appRoutingRules +
                    customRuleSetRules + defaultRuleCatchAll
            }
        }

        internal fun applyCustomRuleMatcher(
            baseRule: RouteRule,
            type: RuleType,
            values: List<String>
        ): RouteRule? {
            return when (type) {
                RuleType.DOMAIN -> baseRule.copy(domain = values)
                RuleType.DOMAIN_SUFFIX -> baseRule.copy(domainSuffix = values)
                RuleType.DOMAIN_KEYWORD -> baseRule.copy(domainKeyword = values)
                RuleType.IP_CIDR -> baseRule.copy(ipCidr = values)
                RuleType.GEOIP -> baseRule.copy(geoip = values)
                RuleType.GEOSITE -> baseRule.copy(geosite = values)
                RuleType.PROCESS_NAME -> baseRule.copy(processName = values)
                RuleType.PORT -> {
                    val ports = values.mapNotNull { it.toIntOrNull()?.takeIf { port -> port in 1..65535 } }
                    val ranges = values.mapNotNull(::normalizePortRange)
                    if (ports.isEmpty() && ranges.isEmpty()) {
                        null
                    } else {
                        baseRule.copy(
                            port = ports.distinct().takeIf(List<Int>::isNotEmpty),
                            portRange = ranges.distinct().takeIf(List<String>::isNotEmpty)
                        )
                    }
                }
            }
        }

        private fun normalizePortRange(value: String): String? {
            val parts = value.trim().split(Regex("\\s*[:-]\\s*"), limit = 2)
            val start = parts.getOrNull(0)?.toIntOrNull()
            val end = parts.getOrNull(1)?.toIntOrNull()
            return when {
                parts.size != 2 || start == null || end == null -> null
                start !in 1..65535 || end !in start..65535 -> null
                else -> "$start:$end"
            }
        }

        internal fun buildFakeIpExcludeDnsRules(values: List<String>, serverTag: String): List<DnsRule> {
            val suffixLabels = setOf("arpa", "lan", "local", "localdomain")
            val exactDomains = mutableListOf<String>()
            val suffixDomains = mutableListOf<String>()
            values.forEach { rawValue ->
                val value = normalizeDnsRuleDomain(rawValue).removePrefix("*.").removePrefix(".")
                if (value.isBlank()) return@forEach
                val rawDomain = rawValue.trim()
                val explicitSuffix = rawDomain.startsWith("*.") || rawDomain.startsWith(".")
                if (explicitSuffix || value in suffixLabels) {
                    suffixDomains.add(value)
                } else {
                    exactDomains.add(value)
                }
            }
            return buildList {
                exactDomains.distinct().takeIf(List<String>::isNotEmpty)?.let {
                    add(DnsRule(domain = it, action = "route", server = serverTag))
                }
                suffixDomains.distinct().takeIf(List<String>::isNotEmpty)?.let {
                    add(DnsRule(domainSuffix = it, action = "route", server = serverTag))
                }
            }
        }

        internal fun buildDefaultDnsBlockRules(
            routingMode: RoutingMode,
            defaultRule: DefaultRule
        ): List<DnsRule> {
            return if (routingMode == RoutingMode.RULE && defaultRule == DefaultRule.BLOCK) {
                listOf(DnsRule(action = "predefined", rcode = JsonPrimitive("NOERROR")))
            } else {
                emptyList()
            }
        }

        internal fun resolveClashApiSecret(existing: String?, generator: () -> String): String {
            fun isValid(value: String): Boolean {
                return value.length >= 32 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
            }

            existing?.trim()?.takeIf(::isValid)?.let { return it }
            return generator().trim().also {
                require(isValid(it)) { "Generated Clash API secret is invalid" }
            }
        }

        // bootstrap 只认 localDns 的 IP；否则系统 DNS，避免用 remote 1.1.1.1 解域名
        internal fun buildBootstrapDnsServer(
            localDnsAddress: String,
            tag: String,
            domainStrategy: String?
        ): DnsServer {
            val numericLocalAddress = localDnsAddress.takeIf { address ->
                extractHostFromAddress(address)?.let(::isIpAddressValue) == true
            }
            return if (numericLocalAddress != null) {
                buildDnsServer(
                    address = numericLocalAddress,
                    tag = tag,
                    domainStrategy = domainStrategy
                ).copy(detour = null, domainResolver = null)
            } else {
                DnsServer(tag = tag, type = "local", domainStrategy = domainStrategy)
            }
        }

        internal fun buildProfileRouteGroupOutbounds(
            groupTag: String,
            nodeTags: List<String>,
            eligibleNodeTags: List<String> = nodeTags,
            testUrl: String,
            autoSelectionEnabled: Boolean = false,
            preferredNodeTag: String? = null
        ): List<Outbound> {
            val distinctNodeTags = nodeTags
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            val distinctEligibleNodeTags = eligibleNodeTags
                .map { it.trim() }
                .filter { it.isNotBlank() && it in distinctNodeTags }
                .distinct()
            if (distinctNodeTags.isEmpty() || distinctEligibleNodeTags.isEmpty()) {
                return emptyList()
            }

            val autoTag = buildRouteGroupAutoTag(groupTag)
            val preferred = preferredNodeTag?.takeIf { it in distinctEligibleNodeTags }
                ?: distinctEligibleNodeTags.first()
            val automaticSelectionAvailable = autoSelectionEnabled
            val selector = Outbound(
                type = "selector",
                tag = groupTag,
                outbounds = if (automaticSelectionAvailable) {
                    listOf(autoTag) + distinctEligibleNodeTags
                } else {
                    distinctEligibleNodeTags
                },
                default = if (automaticSelectionAvailable) autoTag else preferred,
                interruptExistConnections = false
            )
            if (!automaticSelectionAvailable) {
                return listOf(selector)
            }
            return listOf(
                Outbound(
                    type = "urltest",
                    tag = autoTag,
                    outbounds = distinctEligibleNodeTags,
                    url = AppSettings.requireLatencyTestUrl(testUrl),
                    interval = ROUTE_GROUP_AUTO_TEST_INTERVAL,
                    tolerance = ROUTE_GROUP_AUTO_TEST_TOLERANCE,
                    interruptExistConnections = false
                ),
                selector
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

            if (setOf(bootstrapV4Tag, bootstrapV6Tag, bootstrapTag).size == 1) {
                return listOf(
                    DnsRule(
                        domain = bootstrapDomains,
                        action = "route",
                        server = bootstrapTag
                    )
                )
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

        internal fun filterAppliedRemoteRuleSets(
            ruleSets: List<RuleSet>,
            validTags: Set<String>
        ): List<RuleSet> {
            return ruleSets.filter { ruleSet ->
                ruleSet.enabled && ruleSet.type == RuleSetType.REMOTE && ruleSet.tag in validTags
            }
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

        internal fun buildHijackDnsRulesStatic(): List<RouteRule> {
            // sing-box 1.13 的 sniff 是非终止动作，协议规则必须位于其后；TUN 53 端口保留前置劫持。
            return listOf(
                RouteRule(inbound = listOf("tun-in"), port = listOf(53), action = "hijack-dns"),
                RouteRule(inbound = listOf("tun-in", "mixed-in"), action = "sniff"),
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
            return selectRunRouteRulesStatic(
                settings = settings,
                baseRules = hijackDnsRule + quicRule + multicastRejectRules + icmpEchoRules,
                bypassLanRules = bypassLanRules,
                customDomainRules = emptyList(),
                appRoutingRules = emptyList(),
                customRuleSetRules = customRuleSetRules,
                defaultRuleCatchAll = defaultRuleCatchAll
            )
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

        /**
         * 规则集匹配顺序：特定服务 > 通用集 > 国家/地区泛化 > geolocation 泛化。
         * 同级保持用户拖拽顺序（stable sort），避免 geosite-geolocation-!cn 抢先吞掉 openai/google 等专项规则。
         */
        internal fun sortRuleSetsForRouting(ruleSets: List<RuleSet>): List<RuleSet> {
            return ruleSets.sortedBy { ruleSet ->
                val tag = ruleSet.tag.trim().lowercase()
                when {
                    tag.contains("geolocation-!cn") || tag.contains("geolocation_!cn") -> 200
                    tag.contains("geolocation-cn") || tag.contains("geolocation_cn") -> 199
                    tag.contains("!cn") -> 198
                    tag.matches(Regex("^geo(site|ip)-[a-z]{2}$")) -> 100
                    tag.contains("private") || tag.contains("category-ads") -> 50
                    else -> 0
                }
            }
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
            val orderedRuleSets = sortRuleSetsForRouting(
                settings.ruleSets.filter { it.enabled && it.tag in validTags }
            )

            orderedRuleSets.forEach { ruleSet ->
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
                val inboundTags = normalizeRuleSetInboundTags(ruleSet.inbounds)

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
                    val tag = buildProfileRouteTag(profileId, profileName)
                    if (outbounds.any { it.tag == tag }) {
                        ConfigRepository.OutboundSemantic.RouteTag(tag)
                    } else {
                        Log.w(TAG, "Profile selector tag '$tag' not found in outbounds, falling back to $selectorTag")
                        ConfigRepository.OutboundSemantic.FallbackProxy(selectorTag)
                    }
                }
            }
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

        internal fun needsLegacyEchDnsRepair(config: SingBoxConfig): Boolean {
            return config.outbounds.orEmpty().any { outbound ->
                val ech = outbound.tls?.ech ?: return@any false
                val hasEch = ech.enabled == true ||
                    !ech.queryServerName.isNullOrBlank() ||
                    !ech.config.isNullOrEmpty()
                hasEch && ech.dnsServer.isNullOrBlank() && ech.config.isNullOrEmpty()
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
                    .server
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
                val existingServer = existing?.server
                if (!existingServer.isNullOrBlank() && existingServer != DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG) {
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
                        rcode = JsonPrimitive("NOERROR")
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

        internal fun buildDnsRouteToDirect(
            serverTag: String,
            rule: DnsRule
        ): DnsRule {
            return rule.copy(action = "route", server = serverTag)
        }

        internal fun buildDnsRouteToNonDirect(
            fakeDnsEnabled: Boolean,
            serverTag: String,
            rule: DnsRule
        ): List<DnsRule> {
            fun dnsRouteTo(server: String, currentRule: DnsRule): DnsRule =
                currentRule.copy(action = "route", server = server)

            val routedRule = if (fakeDnsEnabled && serverTag == "fakeip-dns") {
                rule.copy(queryType = IP_DNS_QUERY_TYPES)
            } else {
                rule
            }
            return listOf(dnsRouteTo(serverTag, routedRule))
        }

        internal fun buildOrderedDnsRules(
            entries: List<Pair<DnsRule, OutboundSemantic>>,
            fakeDnsEnabled: Boolean,
            directServerTag: String,
            proxyServerTag: String
        ): List<DnsRule> = buildList {
            entries.forEach { (rule, semantic) ->
                if (semantic == OutboundSemantic.Block) {
                    add(rule.copy(action = "predefined", rcode = JsonPrimitive("NOERROR")))
                    return@forEach
                }
                val serverTag = dnsServerTagForSemantic(
                    semantic = semantic,
                    fakeDnsEnabled = fakeDnsEnabled,
                    directServerTag = directServerTag,
                    proxyServerTag = proxyServerTag
                ) ?: return@forEach
                if (serverTag == directServerTag) {
                    add(buildDnsRouteToDirect(serverTag, rule))
                } else {
                    addAll(buildDnsRouteToNonDirect(fakeDnsEnabled, serverTag, rule))
                }
            }
        }

        internal fun buildCustomDnsRuleMatcher(type: RuleType, values: List<String>): DnsRule? {
            return when (type) {
                RuleType.DOMAIN -> DnsRule(domain = values)
                RuleType.DOMAIN_SUFFIX -> DnsRule(domainSuffix = values)
                RuleType.DOMAIN_KEYWORD -> DnsRule(domainKeyword = values)
                RuleType.GEOSITE -> DnsRule(geosite = values)
                else -> null
            }
        }

        internal fun resolveProxyDnsDetourTagForTest(
            selectorTag: String,
            outbounds: List<Outbound> = emptyList()
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
                t in setOf("fakeip", "local", "hosts", "dhcp", "resolved")
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
            val usesLatestEndpoint = when (type) {
                null, "" -> false
                "local", "fakeip" -> true
                "dhcp" -> true
                "hosts" -> server.has("path") || server.has("predefined")
                "resolved" -> hasNonBlankString(server, "service")
                else -> hasNonBlankString(server, "server")
            }
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
            if (jsonString(rule, "type").equals("logical", ignoreCase = true)) {
                val nestedRules = rule.get("rules")
                if (nestedRules == null || !nestedRules.isJsonArray || nestedRules.asJsonArray.size() == 0) {
                    issues.add("DNS logical rule 缺少 rules")
                } else {
                    nestedRules.asJsonArray.forEach { nested ->
                        collectSingleDnsRuleCompatibilityIssues(
                            asJsonObjectOrNull(nested),
                            definedServerTags,
                            issues
                        )
                    }
                }
            }
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
            if (rule.has("rule_set_ipcidr_match_source")) {
                issues.add("DNS rule 使用旧拼写 rule_set_ipcidr_match_source")
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
            if (jsonString(rule, "type").equals("logical", ignoreCase = true)) {
                val nestedRules = rule.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray ?: return false
                return nestedRules.size() > 0 && nestedRules.all { nested ->
                    asJsonObjectOrNull(nested)?.let(::hasDnsRuleMatcher) == true
                }
            }
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
                "reverse_mapping",
                "cache_capacity",
                "client_subnet",
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
                "outbound",
                "ip_version",
                "network",
                "auth_user",
                "protocol",
                "client",
                "source_geoip",
                "geoip",
                "ip_cidr",
                "ip_is_private",
                "ip_accept_any",
                "interface_address",
                "network_interface_address",
                "default_interface_address",
                "source_ip_cidr",
                "source_ip_is_private",
                "source_port",
                "source_port_range",
                "port",
                "port_range",
                "process_name",
                "process_path",
                "process_path_regex",
                "user",
                "clash_mode",
                "network_type",
                "network_is_expensive",
                "network_is_constrained",
                "wifi_ssid",
                "wifi_bssid",
                "rule_set_ip_cidr_match_source",
                "rule_set_ip_cidr_accept_empty"
            )
        }

        internal fun parseDnsOverrideConfig(dnsOverride: String?): DnsConfig? {
            val trimmed = dnsOverride?.trim().orEmpty()
            if (trimmed.isBlank()) return null
            val config = Gson().fromJson(extractDnsOverrideJsonObject(trimmed), DnsConfig::class.java)
                ?: return null
            findUnsupportedAndroidCapability(SingBoxConfig(dns = config))?.let { message ->
                throw IllegalArgumentException(message)
            }
            return config
        }

        internal fun applyDnsOverride(
            baseConfig: DnsConfig,
            overrideConfig: DnsConfig,
            sanitizeServer: (DnsServer) -> DnsServer = { it }
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
                reverseMapping = overrideConfig.reverseMapping ?: baseConfig.reverseMapping,
                cacheCapacity = overrideConfig.cacheCapacity ?: baseConfig.cacheCapacity,
                clientSubnet = overrideConfig.clientSubnet ?: baseConfig.clientSubnet,
                fakeip = overrideConfig.fakeip ?: baseConfig.fakeip
            )
        }

        internal fun normalizeDnsOverrideRule(rule: DnsRule): DnsRule {
            if (!rule.action.isNullOrBlank() || rule.server.isNullOrBlank()) {
                return rule
            }
            return rule.copy(action = "route")
        }

        internal fun shouldApplyDnsPreResolveToDomain(
            domain: String,
            dnsOverride: DnsConfig?,
            outboundTag: String? = null
        ): Boolean {
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
            val type = rule.type?.trim().orEmpty()
            if (type.isNotBlank() && !type.equals("default", ignoreCase = true)) return false
            if (!rule.mode.isNullOrBlank() || !rule.rules.isNullOrEmpty() || rule.invert == true) return false

            return !dnsRuleHasUnsupportedListMatcher(rule) && !dnsRuleHasUnsupportedScalarMatcher(rule)
        }

        private fun dnsRuleHasUnsupportedListMatcher(rule: DnsRule): Boolean {
            return listOf(
                rule.geosite,
                rule.ruleSet,
                rule.inbound,
                rule.packageName,
                rule.network,
                rule.authUser,
                rule.protocol,
                rule.client,
                rule.sourceGeoip,
                rule.geoip,
                rule.ipCidr,
                rule.defaultInterfaceAddress,
                rule.sourceIpCidr,
                rule.sourcePort,
                rule.sourcePortRange,
                rule.port,
                rule.portRange,
                rule.processName,
                rule.processPath,
                rule.processPathRegex,
                rule.user,
                rule.networkType,
                rule.wifiSsid,
                rule.wifiBssid
            ).any { !it.isNullOrEmpty() }
        }

        private fun dnsRuleHasUnsupportedScalarMatcher(rule: DnsRule): Boolean {
            return listOf(
                !rule.userId.isNullOrEmpty(),
                !rule.interfaceAddress.isNullOrEmpty(),
                !rule.networkInterfaceAddress.isNullOrEmpty(),
                rule.ipVersion != null,
                rule.ipIsPrivate == true,
                rule.ipAcceptAny == true,
                rule.sourceIpIsPrivate == true,
                !rule.clashMode.isNullOrBlank(),
                rule.networkIsExpensive == true,
                rule.networkIsConstrained == true,
                rule.ruleSetIpCidrMatchSource == true,
                rule.ruleSetIpCidrAcceptEmpty == true
            ).any { it }
        }

        internal fun dnsRuleAppliesToAddressQuery(rule: DnsRule): Boolean {
            val queryTypes = rule.queryType.orEmpty()
                .map { it.trim().uppercase() }
                .filter { it.isNotBlank() }
            return queryTypes.isEmpty() || queryTypes.any {
                it == "A" || it == "AAAA" || it == "1" || it == "28"
            }
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
            return if (
                trimmed.isBlank() ||
                trimmed.equals(AppSettings.LEGACY_LOCAL_DNS, ignoreCase = true) ||
                trimmed.equals(AppSettings.LEGACY_DOMAIN_LOCAL_DNS, ignoreCase = true)
            ) {
                AppSettings.DEFAULT_LOCAL_DNS
            } else {
                trimmed
            }
        }

        internal fun isBareDnsDomain(value: String): Boolean {
            if (value.contains("://") || value.contains("/")) return false
            if (isIpAddressValue(value)) return false
            return value.contains('.')
        }

        internal fun normalizeRemoteDns(value: String?): String {
            val trimmed = value?.trim().orEmpty()
            return trimmed.ifBlank { AppSettings.DEFAULT_REMOTE_DNS }
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
                else -> throw IllegalArgumentException("Unsupported DNS server scheme: $scheme")
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

            parseBareDnsEndpoint(trimmed)?.let { (host, port) ->
                return DnsServer(
                    tag = tag,
                    type = "udp",
                    server = host,
                    serverPort = port,
                    domainResolver = domainResolver,
                    domainStrategy = domainStrategy,
                    detour = detour
                )
            }

            val uri = try {
                URI(trimmed)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid DNS server address: $trimmed", e)
            }

            val scheme = uri.scheme?.lowercase()
            val type = dnsServerTypeFromScheme(scheme)
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")
                ?: throw IllegalArgumentException("DNS server address has no host: $trimmed")
            val port = if (uri.port > 0) uri.port else if (scheme == null || scheme == "udp") 53 else null
            val path = uri.path?.takeIf { it.isNotBlank() && it != "/" }
            val server = if (shouldUseParsedDnsHost(scheme)) host else trimmed

            return DnsServer(
                tag = tag,
                type = type,
                server = server,
                serverPort = port,
                pathRaw = path,
                domainResolver = domainResolver,
                domainStrategy = domainStrategy,
                detour = detour
            )
        }

        private fun parseBareDnsEndpoint(address: String): Pair<String, Int>? {
            if (address.isBlank() || address.contains("://") || address.contains('/')) return null
            if (address.startsWith("[") && address.contains(']')) {
                val host = address.substringAfter('[').substringBefore(']').trim()
                val port = address.substringAfter(']', "").removePrefix(":").toIntOrNull() ?: 53
                return host.takeIf(String::isNotBlank)?.let { it to port }
            }
            if (address.count { it == ':' } > 1) {
                return address to 53
            }
            val host = address.substringBeforeLast(':').takeIf { address.contains(':') } ?: address
            val port = address.substringAfterLast(':').takeIf { address.contains(':') }?.toIntOrNull() ?: 53
            require(port in 1..65535) { "Invalid DNS server port: $address" }
            return host.trim().takeIf(String::isNotBlank)?.let { it to port }
        }

        fun getInstance(context: Context): ConfigRepository {
            return instance ?: synchronized(this) {
                instance ?: ConfigRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
