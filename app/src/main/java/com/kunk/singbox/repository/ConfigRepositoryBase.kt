package com.kunk.singbox.repository

import android.content.Context
import android.net.NetworkCapabilities
import com.google.gson.Gson
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.model.*
import com.kunk.singbox.utils.parser.Base64Parser
import com.kunk.singbox.utils.parser.NodeLinkParser
import com.kunk.singbox.utils.parser.SingBoxParser
import com.kunk.singbox.utils.parser.SubscriptionManager
import com.kunk.singbox.database.AppDatabase
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import okhttp3.Request
import com.kunk.singbox.utils.dns.DnsResolver
import com.kunk.singbox.utils.dns.DnsResolveStore
import com.tencent.mmkv.MMKV

data class SubscriptionAttemptTimeoutBudget(
    val connectTimeoutSeconds: Long,
    val readTimeoutSeconds: Long,
    val writeTimeoutSeconds: Long,
    val callTimeoutSeconds: Long
)

@Suppress("TooManyFunctions")
abstract class ConfigRepositoryBase(protected val context: Context) {
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

    protected val nodeSwitchInFlight = AtomicBoolean(false)

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

    // Virtual declarations keep split class logic callable across files.
    protected abstract fun getEffectiveTunStack(userSelected: TunStack): TunStack

    protected abstract fun getEffectiveTunMtu(settings: AppSettings): Int

    protected abstract fun getNetworkCapabilities(): NetworkCapabilities?

    protected abstract fun getClient(): okhttp3.OkHttpClient

    protected abstract fun getSubscriptionClient(timeoutBudget: SubscriptionAttemptTimeoutBudget): OkHttpClient

    protected abstract fun getProxyClient(): okhttp3.OkHttpClient?

    protected abstract fun getSubscriptionProxyClient(timeoutBudget: SubscriptionAttemptTimeoutBudget): OkHttpClient?

    protected abstract fun getRememberedSubscriptionUserAgent(url: String): String?

    protected abstract fun rememberSuccessfulSubscriptionUserAgent(url: String, userAgent: String)

    protected abstract fun buildSubscriptionUaHealthKey(host: String, userAgent: String, suffix: String): String

    protected abstract fun readSubscriptionUaFailureCount(key: String): Int

    protected abstract fun readSubscriptionUaBlockedUntil(key: String): Long

    protected abstract fun persistSubscriptionUaFailureCount(key: String, value: Int)

    protected abstract fun persistSubscriptionUaBlockedUntil(key: String, value: Long)

    protected abstract fun clearSubscriptionUaHealthKey(key: String, memoryCache: MutableMap<String, *>)

    protected abstract fun getCircuitBrokenUserAgents(host: String, nowMs: Long = System.currentTimeMillis()): Set<String>

    protected abstract fun clearSubscriptionUserAgentFailure(host: String, userAgent: String)

    protected abstract fun recordSubscriptionUserAgentFailure(
        host: String,
        userAgent: String,
        nowMs: Long = System.currentTimeMillis()
    )

    protected abstract fun buildSubscriptionUserAgents(url: String): List<String>

    abstract fun resolveNodeNameFromOutboundTag(tag: String?): String?

    protected abstract suspend fun awaitInitialProfilesLoaded()

    protected abstract fun loadProfileNodeMemory()

    protected abstract fun saveProfileNodeMemory(profileId: String, nodeId: String)

    protected abstract fun getProfileLastSelectedNode(profileId: String): String?

    protected abstract fun applyActiveProfileNodes(
        profileId: String,
        nodes: List<NodeUi>,
        targetNodeId: String? = null
    )

    protected abstract suspend fun loadProfileNodesWithLatency(profileId: String): List<NodeUi>?

    protected abstract fun loadConfig(profileId: String): SingBoxConfig?

    protected abstract fun cacheConfig(profileId: String, config: SingBoxConfig)

    protected abstract fun removeCachedConfig(profileId: String)

    protected abstract fun startConfigCacheCleanup()

    protected abstract fun cleanupExpiredCache(now: Long = System.currentTimeMillis())

    protected abstract fun saveProfiles()

    protected abstract fun saveProfilesImmediate()

    protected abstract suspend fun saveProfilesInternal()

    protected abstract fun writeConfigFileOrThrow(profileId: String, config: SingBoxConfig)

    protected abstract fun beginProfileUpdateRun(profileId: String): Long

    protected abstract fun updateProfileForCurrentRun(
        profileId: String,
        runId: Long,
        transform: (ProfileUi) -> ProfileUi
    )

    protected abstract fun setProfileUpdateStage(
        profileId: String,
        runId: Long,
        stage: SubscriptionUpdateStage?
    )

    protected abstract fun parseDnsOverride(dnsOverride: String?): DnsConfig?

    protected abstract suspend fun preResolveDomainsForProfileBestEffort(
        profileId: String,
        config: SingBoxConfig,
        dnsServer: String?
    ): Boolean

    protected abstract fun rollbackTransientProfileFile(profileId: String)

    protected abstract fun updateAllNodesAndGroups()

    protected abstract suspend fun loadAllNodesSnapshot(): List<NodeUi>

    abstract fun setAllNodesUiActive(active: Boolean)

    protected abstract fun updateLatencyInAllNodes(nodeId: String, latency: Long)

    protected abstract suspend fun tcpLatencyFallback(outbound: Outbound): Long

    protected abstract suspend fun ipv6TcpLatencyFallback(outbound: Outbound): Long

    protected abstract fun normalizeLatencyValue(latency: Long): Long

    protected abstract fun resolveIpv6OnlyStatus(outbound: Outbound, latency: Long): Long

    protected abstract suspend fun prepareOfflineProbeOutbound(outbound: Outbound): Outbound

    protected abstract fun isLikelyIpv6OnlyDomain(server: String?): Boolean

    protected abstract fun applyLatencyResult(
        info: ConfigRepositoryNodeTestInfo,
        latency: Long,
        onNodeComplete: ((nodeId: String, latencyMs: Long) -> Unit)?
    )

    protected abstract fun buildLatencyRuntimeContext(
        profileId: String,
        config: SingBoxConfig,
        settings: AppSettings
    ): ConfigRepositoryLatencyRuntimeContext

    protected abstract fun buildNodeTestInfos(nodes: List<NodeUi>, settings: AppSettings): List<ConfigRepositoryNodeTestInfo>

    protected abstract suspend fun testRegularOutboundsLatency(
        infos: List<ConfigRepositoryNodeTestInfo>,
        concurrency: Int,
        onNodeComplete: ((nodeId: String, latencyMs: Long) -> Unit)?
    )

    protected abstract suspend fun testTcpFallbackOutboundsLatency(
        infos: List<ConfigRepositoryNodeTestInfo>,
        concurrency: Int,
        onNodeComplete: ((nodeId: String, latencyMs: Long) -> Unit)?
    )

    abstract fun reloadProfiles()

    protected abstract suspend fun loadSavedProfiles()

    protected abstract fun loadActiveProfileNodes(activeProfileId: String?, activeNodeId: String?)

    protected abstract fun cleanupLegacyProfileFiles()

    protected abstract fun parseTrafficString(value: String): Long

    protected abstract fun parseDateString(value: String): Long

    protected abstract fun parseExpireValue(raw: String): Long

    protected abstract fun parseSubscriptionUserInfo(header: String?, bodyDecoded: String? = null): ConfigRepository.SubscriptionUserInfo?

    protected abstract fun parseUserInfoFromOutbounds(outbounds: List<Outbound>?): ConfigRepository.SubscriptionUserInfo?

    protected abstract fun mergeUserInfo(primary: ConfigRepository.SubscriptionUserInfo?, fallback: ConfigRepository.SubscriptionUserInfo?): ConfigRepository.SubscriptionUserInfo?

    protected abstract fun logHtmlSubscriptionPage(userAgent: String, responseBody: String)

    protected abstract fun parseSubscriptionResponse(
        userAgent: String,
        contentType: String?,
        responseBody: String,
        subscriptionUserInfoHeader: String?
    ): ConfigRepositorySubscriptionAttemptResult

    protected abstract fun buildSubscriptionRequest(url: String, userAgent: String): Request

    protected abstract fun logSubscriptionAttempt(
        level: Int,
        message: String,
        context: ConfigRepositorySubscriptionAttemptContext,
        costMs: Long,
        extra: String? = null
    )

    protected abstract fun logSubscriptionParseResult(
        attemptResult: ConfigRepositorySubscriptionAttemptResult,
        contentType: String?,
        context: ConfigRepositorySubscriptionAttemptContext,
        costMs: Long
    )

    protected abstract fun logSubscriptionFallbackStopped(
        context: ConfigRepositorySubscriptionAttemptContext,
        costMs: Long,
        reason: String
    )

    protected abstract fun executeSubscriptionAttempt(
        client: OkHttpClient,
        url: String,
        context: ConfigRepositorySubscriptionAttemptContext,
        onProgress: (String) -> Unit,
        onStageChanged: (SubscriptionUpdateStage) -> Unit
    ): ConfigRepositorySubscriptionAttemptResult

    protected abstract fun fetchAndParseSubscription(
        url: String,
        onProgress: (String) -> Unit = {},
        onStageChanged: (SubscriptionUpdateStage) -> Unit = {}
    ): ConfigRepositoryFetchResult?

    abstract suspend fun importFromSubscription(
        name: String,
        url: String,
        autoUpdateInterval: Int = 0,
        dnsPreResolve: Boolean = false,
        dnsServer: String? = null,
        dnsOverride: String? = null,
        onProgress: (String) -> Unit = {}
    ): Result<ProfileUi>

    protected abstract suspend fun loadSelectedCustomNodes(selectedNodeIds: List<String>): List<NodeUi>

    protected abstract fun collectCustomOutbounds(targetNodes: List<NodeUi>): List<com.kunk.singbox.model.Outbound>

    protected abstract fun buildCustomProfile(profileId: String, name: String): ProfileUi

    abstract suspend fun createCustomProfile(
        name: String,
        selectedNodeIds: List<String>
    ): Result<ProfileUi>

    abstract suspend fun importFromContent(
        name: String,
        content: String,
        profileType: ProfileType = ProfileType.Imported,
        onProgress: (String) -> Unit = {}
    ): Result<ProfileUi>

    protected abstract fun normalizeImportedContent(content: String): String

    protected abstract fun tryDecodeBase64(content: String): String?

    protected abstract fun extractOutboundsOnly(config: SingBoxConfig): SingBoxConfig

    protected abstract fun extractOutboundsFromJson(jsonContent: String): List<Outbound>?

    protected abstract fun sanitizeSubscriptionSnippet(content: String): String

    protected abstract fun parseClashYamlConfig(content: String): SingBoxConfig?

    protected abstract fun parseSubscriptionResponse(content: String): SingBoxConfig?

    protected abstract fun parseNodeLink(link: String): Outbound?

    protected abstract suspend fun extractNodesFromConfig(
        config: SingBoxConfig,
        profileId: String,
        onProgress: ((String) -> Unit)? = null
    ): List<NodeUi>

    protected abstract fun extractNodesFromConfigSync(
        config: SingBoxConfig,
        profileId: String
    ): List<NodeUi>

    protected abstract fun createNodeUi(
        outbound: Outbound,
        profileId: String,
        nodeToGroup: Map<String, String>,
        trafficRepo: TrafficRepository
    ): NodeUi?

    abstract suspend fun setActiveProfileAndWait(profileId: String, targetNodeId: String? = null)

    abstract fun setActiveProfile(profileId: String, targetNodeId: String? = null): List<NodeUi>?

    abstract fun setActiveNodeIdOnly(nodeId: String)

    protected abstract fun nodeDisplayName(nodeId: String, fallbackNodes: List<NodeUi>): String?

    abstract suspend fun setActiveNode(nodeId: String): Boolean

    abstract suspend fun setActiveNodeWithResult(nodeId: String): ConfigRepository.NodeSwitchResult

    abstract suspend fun syncActiveNodeFromProxySelection(proxyName: String?): Boolean

    abstract suspend fun deleteProfile(profileId: String)

    abstract suspend fun importProfileDirectly(profile: ProfileUi, config: SingBoxConfig)

    abstract fun toggleProfileEnabled(profileId: String)

    abstract fun reorderProfiles(newProfiles: List<ProfileUi>)

    abstract fun updateProfileMetadata(
        profileId: String,
        newName: String,
        newUrl: String?,
        autoUpdateInterval: Int = 0,
        dnsPreResolve: Boolean = false,
        dnsServer: String? = null,
        dnsOverride: String? = null
    )

    abstract suspend fun testNodeLatency(nodeId: String): Long

    abstract suspend fun clearAllNodesLatency()

    abstract suspend fun testAllNodesLatency(
        targetNodeIds: List<String>? = null,
        useAllNodes: Boolean = false,
        onNodeComplete: ((nodeId: String, latencyMs: Long) -> Unit)? = null
    )

    abstract suspend fun updateAllProfiles(): BatchUpdateResult

    abstract suspend fun updateProfile(profileId: String): SubscriptionUpdateResult

    protected abstract suspend fun importFromSubscriptionUpdate(
        profile: ProfileUi,
        updateRunId: Long
    ): SubscriptionUpdateResult

    protected abstract fun buildSubscriptionUpdateSuccessResult(
        profileName: String,
        addedNodes: Set<String>,
        removedNodes: Set<String>,
        totalCount: Int,
        dnsMovedToBackground: Boolean
    ): SubscriptionUpdateResult

    abstract suspend fun generateConfigFile(): ConfigRepository.ConfigGenerationResult?

    protected abstract fun buildOutboundForRuntime(outbound: Outbound): Outbound?

    protected abstract fun loadConfigWithLegacyEchRepair(profile: ProfileUi?, profileId: String): SingBoxConfig?

    protected abstract fun stripInternalMetadata(config: SingBoxConfig): SingBoxConfig

    protected abstract fun stripInternalMetadata(outbound: Outbound): Outbound

    protected abstract suspend fun preResolveDomainsForProfile(
        profileId: String,
        config: SingBoxConfig,
        dnsServer: String?
    )

    protected abstract fun applyDnsResolveToOutbound(profileId: String, outbound: Outbound): Outbound

    protected abstract fun detectValidRuleSetFileFormat(file: File, tag: String): String?

    protected abstract fun readRuleSetSample(file: File): ByteArray

    protected abstract fun isLikelyTextRuleSet(sample: ByteArray): Boolean

    protected abstract fun readRuleSetInspectionText(file: File, sample: ByteArray): String

    protected abstract fun validateBinaryRuleSet(file: File, tag: String): Boolean

    protected abstract fun hasRuleSetBinaryMagic(sample: ByteArray): Boolean

    protected abstract fun validateTextRuleSet(file: File, tag: String, inspectionText: String): Boolean

    protected abstract fun rejectUnrecognizedRuleSetText(file: File, tag: String, trimmed: String): Boolean

    protected abstract fun isValidRuleSetJson(content: String): Boolean

    protected abstract fun isValidRuleSetStructuredText(content: String): Boolean

    protected abstract fun buildCustomRuleSets(settings: AppSettings): List<RuleSetConfig>

    internal abstract fun getAppliedRemoteRuleSets(settings: AppSettings): List<RuleSet>

    protected abstract fun buildCustomDomainRules(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>,
        profiles: List<ProfileUi>,
        nodeTagResolver: (String?) -> String?
    ): List<RouteRule>

    protected abstract fun buildCustomRuleSetRules(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>,
        profiles: List<ProfileUi>,
        nodeTagResolver: (String?) -> String?,
        validRuleSets: List<RuleSetConfig>
    ): List<RouteRule>

    protected abstract fun buildAppRoutingRules(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>,
        profiles: List<ProfileUi>,
        nodeTagResolver: (String?) -> String?
    ): List<RouteRule>

    protected abstract fun buildRunLogConfig(): LogConfig

    protected abstract fun buildRunExperimentalConfig(settings: AppSettings): ExperimentalConfig

    protected abstract fun buildRunInbounds(settings: AppSettings): List<Inbound>

    protected abstract fun resolveRunDnsFinalServer(
        routingMode: RoutingMode,
        defaultRule: DefaultRule,
        fakeDnsEnabled: Boolean,
        proxyServerTag: String,
        stableRemoteServerTag: String = "remote",
        directServerTag: String = "local"
    ): String

    protected abstract fun buildRunDns(
        settings: AppSettings,
        validRuleSets: List<RuleSetConfig>,
        outboundsContext: ConfigRepositoryRunOutboundsContext,
        dnsOverride: DnsConfig? = null,
        originalDns: DnsConfig? = null
    ): DnsConfig

    protected abstract fun resolveCurrentProxyDnsDetourTag(selectorTag: String, outbounds: List<Outbound>): String

    protected abstract fun buildRunOutbounds(
        baseConfig: SingBoxConfig,
        activeNode: NodeUi?,
        settings: AppSettings,
        allNodes: List<NodeUi>,
        dnsPreResolve: Boolean = false,
        profileId: String? = null,
        dnsOverrideConfig: DnsConfig? = null
    ): ConfigRepositoryRunOutboundsContext

    protected abstract fun applySelectorSafeOutbounds(outbounds: List<Outbound>): List<Outbound>

    protected abstract fun buildQuicBlockRule(settings: AppSettings): List<RouteRule>

    protected abstract fun buildBypassLanRules(settings: AppSettings): List<RouteRule>

    protected abstract fun buildMulticastRejectRules(settings: AppSettings): List<RouteRule>

    protected abstract fun buildIcmpEchoRules(settings: AppSettings): List<RouteRule>

    protected abstract fun buildDefaultRules(settings: AppSettings, selectorTag: String): List<RouteRule>

    protected abstract fun selectRunRouteRules(
        settings: AppSettings,
        baseRules: List<RouteRule>,
        bypassLanRules: List<RouteRule>,
        customDomainRules: List<RouteRule>,
        appRoutingRules: List<RouteRule>,
        customRuleSetRules: List<RouteRule>,
        defaultRuleCatchAll: List<RouteRule>
    ): List<RouteRule>

    protected abstract fun normalizeRunRouteRules(allRules: List<RouteRule>): List<RouteRule>

    protected abstract fun buildRunRoute(
        settings: AppSettings,
        selectorTag: String,
        outbounds: List<Outbound>,
        nodeTagResolver: (String?) -> String?,
        validRuleSets: List<RuleSetConfig>
    ): RouteConfig

    abstract suspend fun getActiveConfig(): SingBoxConfig?

    abstract fun getConfig(profileId: String): SingBoxConfig?

    abstract suspend fun readProfileConfigContent(profileId: String): Result<String>

    abstract suspend fun updateProfileConfigContent(profileId: String, content: String): Result<ProfileUi>

    protected abstract fun resolveDnsStrategy(strategy: DnsStrategy, mode: IpVersionMode): String

    abstract suspend fun getOutboundByNodeId(nodeId: String): Outbound?

    abstract fun getNodeById(nodeId: String): NodeUi?

    abstract fun getNodeByName(nodeName: String): NodeUi?

    abstract fun createNode(
        outbound: Outbound,
        targetProfileId: String? = null,
        newProfileName: String? = null
    )

    protected abstract fun removeOutboundFromConfig(config: SingBoxConfig, removedTag: String): SingBoxConfig

    abstract suspend fun deleteNode(nodeId: String)

    protected abstract fun applyDeletedNodeSnapshot(profileId: String, deletedNodeId: String, nodes: List<NodeUi>)

    abstract suspend fun addSingleNode(
        link: String,
        targetProfileId: String? = null,
        newProfileName: String? = null
    ): Result<NodeUi>

    abstract suspend fun renameNode(nodeId: String, newName: String)

    abstract suspend fun updateNode(nodeId: String, newOutbound: Outbound)

    protected abstract fun refreshNodesAfterNodeMutation(
        profileId: String,
        oldNodeId: String,
        newTag: String,
        newConfig: SingBoxConfig
    )

    protected abstract fun mergeMutatedNodeLatencies(
        newNodes: List<NodeUi>,
        latencyById: Map<String, Long?>,
        updatedNodeId: String,
        originalLatency: Long?
    ): List<NodeUi>

    protected abstract fun applyMutatedActiveNode(
        profileId: String,
        oldNodeId: String,
        newTag: String,
        mergedNodes: List<NodeUi>
    )

    abstract suspend fun exportNode(nodeId: String): String?

    protected abstract fun deduplicateTags(config: SingBoxConfig): SingBoxConfig

    protected abstract fun findAvailablePort(startPort: Int): Int

    abstract fun cleanup()

    protected abstract fun isIpAddress(address: String?): Boolean
}
