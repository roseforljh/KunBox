@file:Suppress("TooManyFunctions", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "LongMethod", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeConst")

package com.kunk.singbox.repository

import android.content.Context
import com.google.gson.Gson
import com.kunk.singbox.SingBoxApplication
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.database.AppDatabase
import com.kunk.singbox.database.entity.ProfileEntity
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.*
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.utils.dns.DnsResolveStore
import com.kunk.singbox.utils.dns.DnsResolver
import com.kunk.singbox.utils.parser.Base64Parser
import com.kunk.singbox.utils.parser.NodeLinkParser
import com.kunk.singbox.utils.parser.SingBoxParser
import com.kunk.singbox.utils.parser.SubscriptionManager
import com.tencent.mmkv.MMKV
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

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
class ConfigRepository(internal val context: Context) {
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
        val activeNodeName: String? = null,
        val requestId: String = "",
        val configDigest: String = "",
        val appRoutingDigest: String = "",
        val rootRoutingSidecarPath: String = "",
        val rootRoutingManifestPath: String = "",
        val rootRoutingSidecarJson: String = "",
        val rootRoutingSidecarDigest: String = "",
        val rootRoutingStaticPlanDigest: String = "",
        val rootRoutingAppDigest: String = "",
        val rootRoutingGeneration: Long = 0L
    )

    internal data class OutboundSemanticTestInput(
        val mode: RuleSetOutboundMode?,
        val value: String?,
        val selectorTag: String,
        val outbounds: List<Outbound>,
        val profiles: List<ProfileEntity>,
        val nodeTagResolver: (String?) -> String?
    )

    internal val gson = Gson()

    internal val singBoxCore = SingBoxCore.getInstance(context)

    internal val settingsRepository = SettingsRepository.getInstance(context)

    internal val database = AppDatabase.getInstance(context)

    internal val profileDao = database.profileDao()

    internal val activeStateDao = database.activeStateDao()

    internal val nodeLatencyDao = database.nodeLatencyDao()

    @Volatile
    internal var cachedSettings: AppSettings? = null

    internal val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    internal val nodeLinkParser = NodeLinkParser(gson)

    internal val subscriptionManager = SubscriptionManager(listOf(
        SingBoxParser(gson),
        com.kunk.singbox.utils.parser.ClashYamlParser(),
        Base64Parser { nodeLinkParser.parse(it) }
    ))

    internal val dnsResolver = DnsResolver()

    internal val dnsResolveStore = DnsResolveStore.getInstance()

    internal val _profiles = MutableStateFlow<List<ProfileUi>>(emptyList())

    val profiles: StateFlow<List<ProfileUi>> = _profiles.asStateFlow()

    internal val _nodes = MutableStateFlow<List<NodeUi>>(emptyList())

    val nodes: StateFlow<List<NodeUi>> = _nodes.asStateFlow()

    internal val _allNodes = MutableStateFlow<List<NodeUi>>(emptyList())

    val allNodes: StateFlow<List<NodeUi>> = _allNodes.asStateFlow()

    internal val _activeProfileId = MutableStateFlow<String?>(null)

    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    internal val _activeNodeId = MutableStateFlow<String?>(null)

    val activeNodeId: StateFlow<String?> = _activeNodeId.asStateFlow()

    internal val maxConfigCacheSize = 10

    internal val configCache: MutableMap<String, SingBoxConfig> = Collections.synchronizedMap(
        object : LinkedHashMap<String, SingBoxConfig>(maxConfigCacheSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SingBoxConfig>?): Boolean {
                return size > maxConfigCacheSize
            }
        }
    )

    internal val configCacheAccessTimes = ConcurrentHashMap<String, Long>()

    internal val profileNodes = ConcurrentHashMap<String, List<NodeUi>>()

    internal val profileResetJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    internal val profileUpdateRuns = ConcurrentHashMap<String, Long>()

    internal val inFlightLatencyTests = ConcurrentHashMap<String, Deferred<Long>>()

    internal val savedNodeLatencies = ConcurrentHashMap<String, SavedNodeLatency>()
    @Volatile internal var saveProfilesJob: kotlinx.coroutines.Job? = null
    @Volatile internal var initialProfilesLoadJob: kotlinx.coroutines.Job? = null

    internal val saveDebounceMs = 300L

    internal val saveProfilesMutex = Mutex()

    internal val profileUpdateRunCounter = AtomicLong(0L)

    internal val allNodesUiActiveCount = AtomicInteger(0)
    @Volatile internal var allNodesLoadedForUi: Boolean = false

    @Volatile internal var lastTagToNodeName: Map<String, String> = emptyMap()
    @Volatile internal var lastRunOutboundTags: Set<String>? = null
    @Volatile internal var lastRunProfileId: String? = null
    @Volatile internal var lastConfigGenerationError: String? = null

    internal val nodeSwitchGate = NodeSwitchGate()

    internal val profileLastSelectedNode = ConcurrentHashMap<String, String>()

    internal val _profileAutoSelections = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val profileAutoSelections: StateFlow<Map<String, Boolean>> = _profileAutoSelections.asStateFlow()

    internal val profileNodeMemoryMmkv: MMKV by lazy {
        MMKV.mmkvWithID("profile_node_memory", MMKV.MULTI_PROCESS_MODE)
    }

    internal val profileAutoSelectionMmkv: MMKV by lazy {
        MMKV.mmkvWithID("profile_auto_selection", MMKV.MULTI_PROCESS_MODE)
    }

    internal val nodeAutoSelectionMmkv: MMKV by lazy {
        MMKV.mmkvWithID("node_auto_selection", MMKV.MULTI_PROCESS_MODE)
    }

    internal val subscriptionUaMemoryMmkv: MMKV by lazy {
        MMKV.mmkvWithID("subscription_ua_memory", MMKV.SINGLE_PROCESS_MODE)
    }

    internal val subscriptionUaFailureCountMemory = ConcurrentHashMap<String, Int>()

    internal val subscriptionUaBlockedUntilMemory = ConcurrentHashMap<String, Long>()

    internal val subscriptionUaHealthMmkv: MMKV by lazy {
        MMKV.mmkvWithID("subscription_ua_health", MMKV.SINGLE_PROCESS_MODE)
    }

    internal val configDir: File
        get() = File(context.filesDir, "configs").also { it.mkdirs() }

    internal val profilesFileJson: File
        get() = File(context.filesDir, "profiles.json")

    internal val isMainProcess: Boolean by lazy {
        (context.applicationContext as? SingBoxApplication)?.isMainProcess() == true
    }

    internal val clashYamlParser = com.kunk.singbox.utils.parser.ClashYamlParser()

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

    companion object {
        @Volatile
        internal var instance: ConfigRepository? = null

        fun getInstance(context: Context): ConfigRepository {
            return instance ?: synchronized(this) {
                instance ?: ConfigRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
