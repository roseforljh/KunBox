package com.kunk.singbox.repository

import com.kunk.singbox.R
import android.content.Intent
import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.model.*
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.utils.parser.Base64Parser
import com.kunk.singbox.utils.parser.NodeLinkParser
import com.kunk.singbox.utils.parser.SingBoxParser
import com.kunk.singbox.repository.config.OutboundFixer
import com.kunk.singbox.repository.config.InboundBuilder
import com.kunk.singbox.utils.parser.SubscriptionManager
import com.kunk.singbox.repository.TrafficRepository
import com.kunk.singbox.database.AppDatabase
import com.kunk.singbox.database.entity.ProfileEntity
import com.kunk.singbox.database.entity.ActiveStateEntity
import com.kunk.singbox.database.entity.NodeLatencyEntity
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import com.kunk.singbox.utils.NetworkClient
import com.kunk.singbox.utils.StringBuilderPool
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import com.tencent.mmkv.MMKV

/**
 * 配置仓库 - 负责获取、解析和存储配置
 */
class ConfigRepository(private val context: Context) {
    
    companion object {
        private const val TAG = "ConfigRepository"

        // 并行处理的默认并发数
        private const val PARALLEL_CONCURRENCY = 8

        // 预编译的 Regex 常量 - 避免重复编译
        private val REGEX_TRAFFIC = Regex("([\\d.]+)\\s*([KMGTPE]?)B?")
        private val REGEX_KV_PAIRS = Regex("(?i)\\b(upload|download|total|expire)\\b\\s*[:=]\\s*\"?([^,;\\s\\n\\r}]+)\"?")
        private val REGEX_SUBSCRIPTION_USERINFO = Regex("(?i)subscription[-_]userinfo\\s*[:=]\\s*\"?([^\"\\n\\r]+)\"?")
        private val REGEX_TOTAL = Regex("TOT:([\\d.]+[KMGTPE]?)B?")
        private val REGEX_EXPIRE_DATE = Regex("Expires:(\\d{4}-\\d{2}-\\d{2})")
        private val REGEX_TRAFFIC_VALUE = Regex("([\\d.]+[KMGTPE]?)B?")
        private val REGEX_REMAINING = Regex("(?i)(剩余流量|流量剩余|remaining|balance)\\s*[:：]?\\s*([\\d.]+\\s*[KMGTPE]?)\\s*B?")
        private val REGEX_EXPIRE = Regex("(?i)(套餐到期|到期|expiry|expire)\\s*[:：]?\\s*([^\\s,;]+)")
        private val REGEX_SANITIZE_UUID = Regex("(?i)uuid\\s*[:=]\\s*[^\\\\n]+")
        private val REGEX_SANITIZE_PASSWORD = Regex("(?i)password\\s*[:=]\\s*[^\\\\n]+")
        private val REGEX_SANITIZE_TOKEN = Regex("(?i)token\\s*[:=]\\s*[^\\\\n]+")
        private val REGEX_FLAG_EMOJI = Regex("[\\uD83C][\\uDDE6-\\uDDFF][\\uD83C][\\uDDE6-\\uDDFF]")
        private val REGEX_INTERVAL_DIGITS = Regex("^\\d+$")
        private val REGEX_INTERVAL_DECIMAL = Regex("^\\d+\\.\\d+$")
        private val REGEX_INTERVAL_UNIT = Regex("^\\d+[smhd]$", RegexOption.IGNORE_CASE)
        private val REGEX_IPV4 = Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$")
        private val REGEX_IPV6 = Regex("^[0-9a-fA-F:]+$")
        private val REGEX_ED_PARAM_START = Regex("""\?ed=\d+(&|$)""")
        private val REGEX_ED_PARAM_MID = Regex("""&ed=\d+""")
        private val REGEX_ED_EXTRACT = Regex("""[?&]ed=(\d+)""")
        private val REGEX_SS_OLD_FORMAT = Regex("(.+):(.+)@(.+):(\\d+)")
        private val REGEX_WHITESPACE_DASH = Regex("[\\s\\-_]")

        private val TYPE_SAVED_PROFILES_DATA = object : TypeToken<SavedProfilesData>() {}.type
        private val TYPE_OUTBOUND_LIST = object : TypeToken<List<Outbound>>() {}.type

        // 预编译的地区检测规则 - 避免每次调用都编译 Regex
        private data class RegionRule(
            val flag: String,
            val chineseKeywords: List<String>,
            val englishKeywords: List<String>,
            val wordBoundaryKeywords: List<String> // 需要词边界匹配的短代码
        )

        private val REGION_RULES = listOf(
            RegionRule("🇭🇰", listOf("香港"), listOf("hong kong"), listOf("hk")),
            RegionRule("🇹🇼", listOf("台湾"), listOf("taiwan"), listOf("tw")),
            RegionRule("🇯🇵", listOf("日本"), listOf("japan", "tokyo"), listOf("jp")),
            RegionRule("🇸🇬", listOf("新加坡"), listOf("singapore"), listOf("sg")),
            RegionRule("🇺🇸", listOf("美国"), listOf("united states", "america"), listOf("us", "usa")),
            RegionRule("🇰🇷", listOf("韩国"), listOf("korea"), listOf("kr")),
            RegionRule("🇬🇧", listOf("英国"), listOf("britain", "england"), listOf("uk", "gb")),
            RegionRule("🇩🇪", listOf("德国"), listOf("germany"), listOf("de")),
            RegionRule("🇫🇷", listOf("法国"), listOf("france"), listOf("fr")),
            RegionRule("🇨🇦", listOf("加拿大"), listOf("canada"), listOf("ca")),
            RegionRule("🇦🇺", listOf("澳大利亚"), listOf("australia"), listOf("au")),
            RegionRule("🇷🇺", listOf("俄罗斯"), listOf("russia"), listOf("ru")),
            RegionRule("🇮🇳", listOf("印度"), listOf("india"), listOf("in")),
            RegionRule("🇧🇷", listOf("巴西"), listOf("brazil"), listOf("br")),
            RegionRule("🇳🇱", listOf("荷兰"), listOf("netherlands"), listOf("nl")),
            RegionRule("🇹🇷", listOf("土耳其"), listOf("turkey"), listOf("tr")),
            RegionRule("🇦🇷", listOf("阿根廷"), listOf("argentina"), listOf("ar")),
            RegionRule("🇲🇾", listOf("马来西亚"), listOf("malaysia"), listOf("my")),
            RegionRule("🇹🇭", listOf("泰国"), listOf("thailand"), listOf("th")),
            RegionRule("🇻🇳", listOf("越南"), listOf("vietnam"), listOf("vn")),
            RegionRule("🇵🇭", listOf("菲律宾"), listOf("philippines"), listOf("ph")),
            RegionRule("🇮🇩", listOf("印尼"), listOf("indonesia"), listOf("id"))
        )

        // 预编译词边界 Regex Map
        private val WORD_BOUNDARY_REGEX_MAP: Map<String, Regex> = REGION_RULES
            .flatMap { it.wordBoundaryKeywords }
            .associateWith { word -> Regex("(^|[^a-z])${Regex.escape(word)}([^a-z]|$)") }

        // LRU 缓存大小限制，防止节点数量过多时内存膨胀
        private const val MAX_REGION_FLAG_CACHE_SIZE = 2000
        private const val MAX_NODE_ID_CACHE_SIZE = 2000

        // 地区检测缓存 - 使用 LRU 淘汰机制
        private val regionFlagCache: MutableMap<String, String> = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(MAX_REGION_FLAG_CACHE_SIZE, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                    return size > MAX_REGION_FLAG_CACHE_SIZE
                }
            }
        )

        // stableNodeId 缓存 - 使用 LRU 淘汰机制
        private val nodeIdCache: MutableMap<String, String> = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(MAX_NODE_ID_CACHE_SIZE, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                    return size > MAX_NODE_ID_CACHE_SIZE
                }
            }
        )

        /**
         * 生成稳定的节点 ID（基于 profileId 和 outboundTag 的 UUID）
         * 使用缓存避免重复计算
         */
        fun stableNodeId(profileId: String, outboundTag: String): String {
            val key = "$profileId|$outboundTag"
            synchronized(nodeIdCache) {
                nodeIdCache[key]?.let { return it }
                val id = StringBuilderPool.use { sb ->
                    sb.append(profileId).append('|').append(outboundTag)
                    java.util.UUID.nameUUIDFromBytes(sb.toString().toByteArray(Charsets.UTF_8)).toString()
                }
                nodeIdCache[key] = id
                return id
            }
        }

        // User-Agent 列表，按优先级排序
        private val USER_AGENTS = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36", // Browser - 优先尝试获取通用 Base64 订阅，以绕过服务端的客户端过滤
            "ClashMeta/1.18.0",             // ClashMeta - 次选
            "sing-box/1.10.0",              // Sing-box
            "Clash.Meta/1.18.0",
            "Clash/1.18.0",
            "SFA/1.10.0"
        )
        
        @Volatile
        private var instance: ConfigRepository? = null
        
        fun getInstance(context: Context): ConfigRepository {
            return instance ?: synchronized(this) {
                instance ?: ConfigRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val gson = Gson()
    private val singBoxCore = SingBoxCore.getInstance(context)
    private val settingsRepository = SettingsRepository.getInstance(context)

    // Room 数据库
    private val database = AppDatabase.getInstance(context)
    private val profileDao = database.profileDao()
    private val activeStateDao = database.activeStateDao()
    private val nodeLatencyDao = database.nodeLatencyDao()

    /**
     * 缓存的设置值 - 避免在同步方法中使用 runBlocking
     *
     * 优化说明:
     * - 通过 StateFlow 订阅设置变化，自动更新缓存
     * - getClient() 等同步方法可直接读取缓存，无需阻塞
     */
    @Volatile
    private var cachedSettings: AppSettings? = null

    /**
     * 获取实际使用的 TUN 栈模式
     * 针对特定不支持 System 模式的设备强制使用 gVisor
     * 否则返回用户选择的模式
     */
    private fun getEffectiveTunStack(userSelected: TunStack): TunStack {
        // 针对特定不支持 System 模式的设备强制使用 gVisor
        // 这些设备在 System 模式下会报错 "bind forwarder to interface: operation not permitted"
        val model = Build.MODEL
        if (model.contains("SM-G986U", ignoreCase = true)) {
            Log.w(TAG, "Device $model detected, forcing GVISOR stack (ignoring user selection: ${userSelected.name})")
            return TunStack.GVISOR
        }

        return userSelected
    }

    // client 改为动态获取，以支持可配置的超时
    // 使用不带重试的 Client，避免订阅获取时超时时间被重试机制延长
    // 当 VPN 运行时，使用代理客户端让请求走 sing-box 代理
    // 优化: 使用缓存的 settings 值，避免 runBlocking 阻塞
    private fun getClient(): okhttp3.OkHttpClient {
        val settings = cachedSettings ?: AppSettings() // 使用缓存，无阻塞；首次为空时使用默认值
        val timeout = settings.subscriptionUpdateTimeout.toLong()

        // 检测 VPN 是否正在运行，如果是则使用代理
        val isVpnRunning = SingBoxRemote.isRunning.value
        return if (isVpnRunning) {
            val proxyPort = settings.proxyPort
            Log.d(TAG, "VPN is running, using proxy on port $proxyPort for subscription fetch")
            NetworkClient.createClientWithProxy(proxyPort, timeout, timeout, timeout)
        } else {
            NetworkClient.createClientWithoutRetry(timeout, timeout, timeout)
        }
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 节点链接解析器 - 复用实例避免重复创建
    private val nodeLinkParser = NodeLinkParser(gson)

    private val subscriptionManager = SubscriptionManager(listOf(
        SingBoxParser(gson),
        com.kunk.singbox.utils.parser.ClashYamlParser(),
        Base64Parser { nodeLinkParser.parse(it) }
    ))

    private val _profiles = MutableStateFlow<List<ProfileUi>>(emptyList())
    val profiles: StateFlow<List<ProfileUi>> = _profiles.asStateFlow()
    
    private val _nodes = MutableStateFlow<List<NodeUi>>(emptyList())
    val nodes: StateFlow<List<NodeUi>> = _nodes.asStateFlow()

    private val _allNodes = MutableStateFlow<List<NodeUi>>(emptyList())
    val allNodes: StateFlow<List<NodeUi>> = _allNodes.asStateFlow()

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()
    
    private val _activeNodeId = MutableStateFlow<String?>(null)
    val activeNodeId: StateFlow<String?> = _activeNodeId.asStateFlow()
    
    private val maxConfigCacheSize = 10
    // 使用 LinkedHashMap 实现 LRU 缓存，线程安全
    private val configCache: MutableMap<String, SingBoxConfig> = Collections.synchronizedMap(
        object : LinkedHashMap<String, SingBoxConfig>(maxConfigCacheSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SingBoxConfig>?): Boolean {
                return size > maxConfigCacheSize
            }
        }
    )
    private val profileNodes = ConcurrentHashMap<String, List<NodeUi>>()
    private val profileResetJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val inFlightLatencyTests = ConcurrentHashMap<String, Deferred<Long>>()

    // 保存从持久化存储加载的延时数据，用于在 setAllNodesUiActive 时恢复
    private val savedNodeLatencies = ConcurrentHashMap<String, Long>()

    // saveProfiles 防抖
    @Volatile private var saveProfilesJob: kotlinx.coroutines.Job? = null
    private val saveDebounceMs = 300L

    private val allNodesUiActiveCount = AtomicInteger(0)
    @Volatile private var allNodesLoadedForUi: Boolean = false
    
    @Volatile private var lastTagToNodeName: Map<String, String> = emptyMap()
    // 缓存上一次运行的配置中的 Outbound Tags 集合，用于判断是否需要重启 VPN
    @Volatile private var lastRunOutboundTags: Set<String>? = null
    // 缓存上一次运行的配置 ID，用于判断是否跨配置切换
    @Volatile private var lastRunProfileId: String? = null

    // 配置级别的节点选择记忆 - 记录每个配置上次选中的节点
    private val profileLastSelectedNode = ConcurrentHashMap<String, String>()
    private val profileNodeMemoryMmkv: MMKV by lazy {
        MMKV.mmkvWithID("profile_node_memory", MMKV.SINGLE_PROCESS_MODE)
    }

    fun resolveNodeNameFromOutboundTag(tag: String?): String? {
        if (tag.isNullOrBlank()) return null
        if (tag.equals("PROXY", ignoreCase = true)) return null
        return when (tag) {
            "direct" -> context.getString(R.string.outbound_tag_direct)
            "block" -> context.getString(R.string.outbound_tag_block)
            "dns-out" -> "DNS"
            else -> {
                lastTagToNodeName[tag]
                    ?: _allNodes.value.firstOrNull { it.name == tag }?.name
            }
        }
    }
    
    private val configDir: File
        get() = File(context.filesDir, "configs").also { it.mkdirs() }

    // JSON 格式的配置文件（用于旧数据迁移）
    private val profilesFileJson: File
        get() = File(context.filesDir, "profiles.json")

    init {
        loadProfileNodeMemory()
        loadSavedProfiles()

        // 订阅设置变化，自动更新缓存 (性能优化: 避免 getClient 中使用 runBlocking)
        scope.launch {
            settingsRepository.settings.collect { settings ->
                cachedSettings = settings
            }
        }
    }

    private fun loadProfileNodeMemory() {
        profileNodeMemoryMmkv.allKeys()?.forEach { profileId ->
            val nodeId = profileNodeMemoryMmkv.decodeString(profileId, null)
            if (!nodeId.isNullOrBlank()) {
                profileLastSelectedNode[profileId] = nodeId
            }
        }
    }

    private fun saveProfileNodeMemory(profileId: String, nodeId: String) {
        profileLastSelectedNode[profileId] = nodeId
        profileNodeMemoryMmkv.encode(profileId, nodeId)
    }

    private fun getProfileLastSelectedNode(profileId: String): String? {
        return profileLastSelectedNode[profileId]
    }
    
    private fun loadConfig(profileId: String): SingBoxConfig? {
        configCache[profileId]?.let { return it }

        val configFile = File(configDir, "$profileId.json")
        if (!configFile.exists()) return null

        return try {
            val configJson = configFile.readText()
            var config = gson.fromJson(configJson, SingBoxConfig::class.java)
            config = deduplicateTags(config)
            cacheConfig(profileId, config)
            config
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config for profile: $profileId", e)
            null
        }
    }

    private fun cacheConfig(profileId: String, config: SingBoxConfig) {
        configCache[profileId] = config
    }

    private fun removeCachedConfig(profileId: String) {
        configCache.remove(profileId)
    }

    /**
     * 保存配置 - 使用防抖机制，合并短时间内的多次调用
     */
    private fun saveProfiles() {
        saveProfilesJob?.cancel()
        saveProfilesJob = scope.launch {
            delay(saveDebounceMs)
            saveProfilesInternal()
        }
    }

    /**
     * 立即保存配置 - 跳过防抖，用于关键操作
     */
    private fun saveProfilesImmediate() {
        saveProfilesJob?.cancel()
        scope.launch {
            saveProfilesInternal()
        }
    }

    private fun saveProfilesInternal() {
        try {
            val startTime = System.currentTimeMillis()
            val profiles = _profiles.value
            val activeProfileId = _activeProfileId.value
            val activeNodeId = _activeNodeId.value

            // 收集所有节点的延迟数据
            val latencies = mutableMapOf<String, Long>()
            profileNodes.values.flatten().forEach { node ->
                node.latencyMs?.let { latencies[node.id] = it }
            }

            // 同步保存活跃状态 - 确保节点切换后立即持久化，防止应用被杀后丢失
            try {
                activeStateDao.saveSync(ActiveStateEntity(
                    id = 1,
                    activeProfileId = activeProfileId,
                    activeNodeId = activeNodeId
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save active state synchronously", e)
            }

            // 异步保存其他数据到 Room 数据库
            scope.launch {
                try {
                    // 保存 Profiles
                    val entities = profiles.mapIndexed { index, profile ->
                        ProfileEntity.fromUiModel(profile, sortOrder = index)
                    }
                    profileDao.insertAll(entities)

                    // 保存延时数据
                    if (latencies.isNotEmpty()) {
                        val latencyEntities = latencies.map { (nodeId, latency) ->
                            NodeLatencyEntity(nodeId = nodeId, latencyMs = latency)
                        }
                        nodeLatencyDao.insertAll(latencyEntities)
                    }

                    val elapsed = System.currentTimeMillis() - startTime
                    Log.d(TAG, "Saved ${profiles.size} profiles to Room in ${elapsed}ms")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save profiles to Room", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save profiles", e)
        }
    }
    
    private fun updateAllNodesAndGroups() {
        if (allNodesUiActiveCount.get() <= 0) {
            _allNodes.value = emptyList()
            return
        }

        val all = profileNodes.values.flatten()
        _allNodes.value = all
    }

    /**
     * 加载所有节点快照
     *
     * 优化说明:
     * - 改为 suspend 函数，移除 runBlocking 阻塞
     * - 使用协程并行处理多个配置文件，提升性能
     */
    private suspend fun loadAllNodesSnapshot(): List<NodeUi> = withContext(Dispatchers.IO) {
        val profiles = _profiles.value
        if (profiles.isEmpty()) return@withContext emptyList()

        // 并行提取各配置的节点
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
                        // 恢复已保存的延时数据
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

    private fun updateLatencyInAllNodes(nodeId: String, latency: Long) {
        val latencyValue = if (latency > 0) latency else -1L
        savedNodeLatencies[nodeId] = latencyValue
        _allNodes.update { list ->
            list.map {
                if (it.id == nodeId) it.copy(latencyMs = latencyValue) else it
            }
        }
        // 持久化到 Room 数据库
        scope.launch {
            try {
                nodeLatencyDao.upsert(nodeId, latencyValue)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist latency for $nodeId", e)
            }
        }
    }

    private fun updateNodeLatencyFull(nodeId: String, latency: Long) {
        val latencyValue = if (latency > 0) latency else -1L
        _nodes.update { list ->
            list.map { if (it.id == nodeId) it.copy(latencyMs = latencyValue) else it }
        }
        val node = _nodes.value.find { it.id == nodeId }
        if (node != null) {
            profileNodes[node.sourceProfileId] = profileNodes[node.sourceProfileId]?.map {
                if (it.id == nodeId) it.copy(latencyMs = latencyValue) else it
            } ?: emptyList()
        }
        updateLatencyInAllNodes(nodeId, latency)
    }

    /**
     * 重新加载所有保存的配置
     * 用于导入数据后刷新内存状态
     */
    fun reloadProfiles() {
        loadSavedProfiles()
    }

    private fun loadSavedProfiles() {
        try {
            val startTime = System.currentTimeMillis()

            // 1. 优先从 Room 数据库加载
            val profileEntities = profileDao.getAllSync()
            val activeState = activeStateDao.getSync()
            val latencyEntities = nodeLatencyDao.getAllSync()

            if (profileEntities.isNotEmpty()) {
                // 从 Room 加载成功
                val profiles = profileEntities.map { it.toUiModel().copy(updateStatus = UpdateStatus.Idle) }
                _profiles.value = profiles
                _activeProfileId.value = activeState?.activeProfileId

                // 恢复延时数据
                savedNodeLatencies.clear()
                latencyEntities.forEach { savedNodeLatencies[it.nodeId] = it.latencyMs }

                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "Loaded ${profiles.size} profiles from Room in ${elapsed}ms")

                // 加载活跃配置的节点
                loadActiveProfileNodes(activeState?.activeProfileId, activeState?.activeNodeId)

                // 清理旧的 JSON 文件
                cleanupLegacyProfileFiles()
                return
            }

            // 2. Room 为空，尝试从 JSON 迁移
            val savedData: SavedProfilesData? = if (profilesFileJson.exists()) {
                Log.i(TAG, "Migrating profiles from JSON to Room...")
                val json = profilesFileJson.readText()
                gson.fromJson<SavedProfilesData>(json, TYPE_SAVED_PROFILES_DATA)
            } else {
                null
            }

            if (savedData != null) {
                // 迁移到 Room
                val profiles = savedData.profiles.map { it.copy(updateStatus = UpdateStatus.Idle) }
                _profiles.value = profiles
                _activeProfileId.value = savedData.activeProfileId

                savedNodeLatencies.clear()
                savedNodeLatencies.putAll(savedData.nodeLatencies)

                // 保存到 Room (同步)
                val entities = profiles.mapIndexed { index, profile ->
                    ProfileEntity.fromUiModel(profile, sortOrder = index)
                }
                profileDao.insertAllSync(entities)

                // 保存活跃状态
                if (savedData.activeProfileId != null || savedData.activeNodeId != null) {
                    activeStateDao.saveSync(ActiveStateEntity(
                        id = 1,
                        activeProfileId = savedData.activeProfileId,
                        activeNodeId = savedData.activeNodeId
                    ))
                }

                // 保存延时数据
                val latencies = savedData.nodeLatencies.map { (nodeId, latency) ->
                    NodeLatencyEntity(nodeId = nodeId, latencyMs = latency)
                }
                if (latencies.isNotEmpty()) {
                    scope.launch { nodeLatencyDao.insertAll(latencies) }
                }

                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "Migrated ${profiles.size} profiles to Room in ${elapsed}ms")

                // 加载活跃配置的节点
                loadActiveProfileNodes(savedData.activeProfileId, savedData.activeNodeId)

                // 删除旧文件
                cleanupLegacyProfileFiles()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load saved profiles", e)
        }
    }

    /**
     * 加载活跃配置的节点
     */
    private fun loadActiveProfileNodes(activeProfileId: String?, activeNodeId: String?) {
        if (activeProfileId == null) return
        val configFile = File(configDir, "${activeProfileId}.json")
        if (!configFile.exists()) return

        scope.launch {
            try {
                val configJson = configFile.readText()
                val config = gson.fromJson(configJson, SingBoxConfig::class.java)
                val nodes = extractNodesFromConfig(config, activeProfileId)
                // 恢复延迟数据
                val nodesWithLatency = nodes.map { node ->
                    val latency = savedNodeLatencies[node.id]
                    if (latency != null) node.copy(latencyMs = latency) else node
                }
                profileNodes[activeProfileId] = nodesWithLatency
                cacheConfig(activeProfileId, config)

                // 更新 UI 状态
                if (activeProfileId == _activeProfileId.value) {
                    _nodes.value = nodesWithLatency
                    _activeNodeId.value = when {
                        !activeNodeId.isNullOrBlank() && nodesWithLatency.any { it.id == activeNodeId } -> activeNodeId
                        nodesWithLatency.isNotEmpty() -> nodesWithLatency.first().id
                        else -> null
                    }
                }
                if (allNodesUiActiveCount.get() > 0) {
                    updateAllNodesAndGroups()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load config for profile: $activeProfileId", e)
            }
        }
    }

    /**
     * 清理旧的 JSON 配置文件
     */
    private fun cleanupLegacyProfileFiles() {
        scope.launch {
            try {
                if (profilesFileJson.exists()) {
                    profilesFileJson.delete()
                    Log.i(TAG, "Deleted legacy JSON profiles file")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cleanup legacy profile files", e)
            }
        }
    }
    
    /**
     * 从订阅 URL 导入配置
     */
    data class SubscriptionUserInfo(
        val upload: Long = 0,
        val download: Long = 0,
        val total: Long = 0,
        val expire: Long = 0
    )

    private data class FetchResult(
        val config: SingBoxConfig,
        val userInfo: SubscriptionUserInfo?
    )

    /**
     * 解析流量字符串 (支持 B, KB, MB, GB, TB, PB)
     */
    private fun parseTrafficString(value: String): Long {
        val trimmed = value.trim().uppercase()
        val match = REGEX_TRAFFIC.find(trimmed) ?: return 0L
        
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

    /**
     * 解析日期字符串 (yyyy-MM-dd)
     */
    private fun parseDateString(value: String): Long {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            (sdf.parse(value.trim())?.time ?: 0L) / 1000 // Convert to seconds
        } catch (e: Exception) {
            0L
        }
    }

    private fun parseExpireValue(raw: String): Long {
        val normalized = raw.trim().trim('"', '\'')
        if (normalized.isBlank()) return 0L
        val lower = normalized.lowercase()
        if (lower.contains("长期") || lower.contains("永久") || lower.contains("无限") || lower.contains("never")) {
            return -1L
        }
        return if (normalized.contains("-")) {
            parseDateString(normalized)
        } else {
            normalized.toLongOrNull() ?: 0L
        }
    }

    /**
     * 解析 Subscription-Userinfo 头或 Body 中的状态信息
     * 支持标准 Header 格式和常见的 Body 文本格式 (如 STATUS=...)
     */
    private fun parseSubscriptionUserInfo(header: String?, bodyDecoded: String? = null): SubscriptionUserInfo? {
        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L
        var found = false
        var totalSpecified = false

        fun isUnlimitedValue(raw: String): Boolean {
            val normalized = raw.trim().lowercase()
            return normalized == "unlimited" || normalized == "infinite" || normalized == "infinity" || normalized == "inf" || normalized == "∞"
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
            REGEX_KV_PAIRS.findAll(text).forEach { match ->
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

        // 1. 尝试解析 Header
        if (!header.isNullOrBlank()) {
            try {
                parseHeaderLike(header)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse Subscription-Userinfo header: $header", e)
            }
        }

        // 2. 如果 Header 没有完整信息，尝试从 Body 解析
        // 格式示例: STATUS=🚀:0.12GB,🚀:37.95GB,TOT:100GB🗓Expires:2026-01-02
        if (bodyDecoded != null && (!found || total == 0L)) {
            try {
                val userInfoIndex = bodyDecoded.indexOf("subscription-userinfo", ignoreCase = true)
                val userInfoAltIndex = if (userInfoIndex >= 0) userInfoIndex else bodyDecoded.indexOf("subscription_userinfo", ignoreCase = true)
                if (userInfoAltIndex >= 0) {
                    val endIndex = (userInfoAltIndex + 800).coerceAtMost(bodyDecoded.length)
                    val snippet = bodyDecoded.substring(userInfoAltIndex, endIndex)
                    val inlineMatch = REGEX_SUBSCRIPTION_USERINFO.find(snippet)
                    if (inlineMatch != null) {
                        parseHeaderLike(inlineMatch.groupValues[1])
                    }
                    parseKeyValuePairs(snippet)
                }

                val firstLine = bodyDecoded.lines().firstOrNull()?.trim()
                if (firstLine != null && (firstLine.startsWith("STATUS=") || firstLine.contains("TOT:") || firstLine.contains("Expires:"))) {
                    // 解析 TOT:
                    val totalMatch = REGEX_TOTAL.find(firstLine)
                    if (totalMatch != null) {
                        totalSpecified = true
                        total = parseTrafficString(totalMatch.groupValues[1])
                        found = true
                    }

                    // 解析 Expires:
                    val expireMatch = REGEX_EXPIRE_DATE.find(firstLine)
                    if (expireMatch != null) {
                        expire = parseDateString(expireMatch.groupValues[1])
                        found = true
                    }

                    // 解析已用流量 (Upload/Download)
                    // 假设除此之外的流量数据都是已用流量，或者匹配特定图标/格式
                    // 示例中的已用流量是两个 🚀: value，分别对应 up/down 或已用
                    // 我们简单地提取所有类似 X:ValueGB 的格式，除了 TOT
                    // 我们重新策略：
                    // 如果有 upload/download 关键字更好。如果没有，尝试解析所有数字。
                    // 针对 specific case: 🚀:0.12GB,🚀:37.95GB
                    // 匹配所有非 TOT 的流量
                    var usedAccumulator = 0L
                    val parts = firstLine.substringAfter("STATUS=").split(",")
                    parts.forEach { part ->
                        if (part.contains("TOT:")) return@forEach
                        if (part.contains("Expires:")) return@forEach

                        // 提取流量值
                        val match = REGEX_TRAFFIC_VALUE.find(part)
                        if (match != null) {
                            usedAccumulator += parseTrafficString(match.groupValues[1])
                            found = true
                        }
                    }

                    if (usedAccumulator > 0) {
                        // 我们不知道哪个是 up 哪个是 down，暂且全部算作 download，或者平分
                        download = usedAccumulator
                        upload = 0
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse info from body: ${bodyDecoded.take(100)}", e)
            }
        }

        if (!found) return null
        if (totalSpecified && total <= 0L) {
            total = -1L
        }
        return SubscriptionUserInfo(upload, download, total, expire)
    }

    private fun parseUserInfoFromOutbounds(outbounds: List<Outbound>?): SubscriptionUserInfo? {
        if (outbounds.isNullOrEmpty()) return null
        var remainingBytes: Long? = null
        var expireValue: Long? = null

        outbounds.forEach { outbound ->
            val tag = outbound.tag
            if (remainingBytes == null) {
                val match = REGEX_REMAINING.find(tag)
                if (match != null) {
                    remainingBytes = parseTrafficString(match.groupValues[2])
                }
            }
            if (expireValue == null) {
                val match = REGEX_EXPIRE.find(tag)
                if (match != null) {
                    expireValue = parseExpireValue(match.groupValues[2])
                }
            }
        }

        if (remainingBytes == null && expireValue == null) return null
        return SubscriptionUserInfo(
            upload = 0,
            download = remainingBytes ?: 0,
            total = if (remainingBytes != null) -2L else 0L,
            expire = expireValue ?: 0L
        )
    }

    private fun mergeUserInfo(primary: SubscriptionUserInfo?, fallback: SubscriptionUserInfo?): SubscriptionUserInfo? {
        if (primary == null) return fallback
        if (fallback == null) return primary
        return SubscriptionUserInfo(
            upload = if (primary.upload > 0) primary.upload else fallback.upload,
            download = if (primary.download > 0) primary.download else fallback.download,
            total = if (primary.total != 0L) primary.total else fallback.total,
            expire = if (primary.expire != 0L) primary.expire else fallback.expire
        )
    }

    /**
     * 使用多种 User-Agent 尝试获取订阅内容
     * 如果解析失败，依次尝试其他 UA
     *
     * @param url 订阅链接
     * @param onProgress 进度回调
     * @return 解析成功的配置及用户信息，如果所有尝试都失败则返回 null
     */
    private fun fetchAndParseSubscription(
        url: String,
        onProgress: (String) -> Unit = {}
    ): FetchResult? {
        var lastError: Exception? = null
        
        for ((index, userAgent) in USER_AGENTS.withIndex()) {
            try {
                onProgress("尝试获取订阅 (${index + 1}/${USER_AGENTS.size})...")
                
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/yaml,text/yaml,text/plain,application/json,*/*")
                    .build()

                var parsedConfig: SingBoxConfig? = null
                var userInfo: SubscriptionUserInfo? = null

                getClient().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Request failed with UA '$userAgent': HTTP ${response.code}")
                        if (index == USER_AGENTS.lastIndex) {
                            throw Exception("HTTP ${response.code}: ${response.message}")
                        }
                        return@use
                    }

                    val responseBody = response.body?.string()
                    if (responseBody.isNullOrBlank()) {
                        Log.w(TAG, "Empty response with UA '$userAgent'")
                        if (index == USER_AGENTS.lastIndex) {
                            throw Exception("服务器返回空内容")
                        }
                        return@use
                    }

                    // 尝试从 Header 或 Body 解析 UserInfo
                    // 先尝试解码 Body 以便检查内容
                    val decodedBody = tryDecodeBase64(responseBody) ?: responseBody
                    userInfo = parseSubscriptionUserInfo(response.header("Subscription-Userinfo"), decodedBody)

                    val contentType = response.header("Content-Type") ?: ""

                    onProgress("正在解析配置...")

                    val config = parseSubscriptionResponse(responseBody)
                    if (config != null && config.outbounds != null && config.outbounds.isNotEmpty()) {
                        parsedConfig = config
                    } else {
                        Log.w(TAG, "Failed to parse response with UA '$userAgent'")
                        if (index == USER_AGENTS.lastIndex) {
                            // 如果是最后一次尝试，且内容不为空但无法解析，则可能是格式问题
                            // 但也有可能是网络截断等问题，这里我们记录为解析失败
                            // 让外层决定是否抛出异常（外层通过返回值 null 判断）
                        }
                    }
                }

                if (parsedConfig != null) {
                    Log.i(TAG, "Successfully parsed subscription with UA '$userAgent', got ${parsedConfig!!.outbounds?.size ?: 0} outbounds")
                    val mergedUserInfo = mergeUserInfo(userInfo, parseUserInfoFromOutbounds(parsedConfig!!.outbounds))
                    return FetchResult(parsedConfig!!, mergedUserInfo)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error with UA '$userAgent': ${e.message}")
                lastError = e
                // 如果是最后一次尝试，重新抛出异常以便上层捕获详细信息
                if (index == USER_AGENTS.lastIndex) {
                    throw e
                }
            }
        }
        
        // 理论上不会执行到这里，因为最后一次尝试会抛出异常
        lastError?.let { Log.e(TAG, "All User-Agents failed", it) }
        return null
    }

    private fun sanitizeSubscriptionSnippet(body: String, maxLen: Int = 220): String {
        var s = body
            .replace("\r", "")
            .replace("\n", "\\n")
            .trim()
        if (s.length > maxLen) s = s.substring(0, maxLen)

        s = s.replace(REGEX_SANITIZE_UUID, "uuid:***")
        s = s.replace(REGEX_SANITIZE_PASSWORD, "password:***")
        s = s.replace(REGEX_SANITIZE_TOKEN, "token:***")
        return s
    }

    private fun parseClashYamlConfig(content: String): SingBoxConfig? {
        return com.kunk.singbox.utils.parser.ClashYamlParser().parse(content)
    }
    
    /**
     * 从订阅 URL 导入配置
     */
    suspend fun importFromSubscription(
        name: String,
        url: String,
        autoUpdateInterval: Int = 0,
        onProgress: (String) -> Unit = {}
    ): Result<ProfileUi> = withContext(Dispatchers.IO) {
        try {
            onProgress("正在获取订阅...")
            
            // 使用智能 User-Agent 切换策略获取订阅
            val fetchResult = try {
                fetchAndParseSubscription(url, onProgress)
            } catch (e: Exception) {
                // 捕获 fetchAndParseSubscription 抛出的具体网络异常
                Log.e(TAG, "Subscription fetch failed", e)
                return@withContext Result.failure(e)
            }

            if (fetchResult == null) {
                return@withContext Result.failure(Exception(context.getString(R.string.profiles_parse_failed)))
            }
            
            val config = fetchResult.config
            val userInfo = fetchResult.userInfo

            onProgress(context.getString(R.string.profiles_extracting_nodes, 0, 0))
            
            val profileId = UUID.randomUUID().toString()
            val deduplicatedConfig = deduplicateTags(config)
            val nodes = extractNodesFromConfig(deduplicatedConfig, profileId, onProgress)
            
            if (nodes.isEmpty()) {
                return@withContext Result.failure(Exception("No valid nodes found")) // TODO: add to strings.xml
            }
            
            // 保存配置文件
            val configFile = File(configDir, "$profileId.json")
            configFile.writeText(gson.toJson(deduplicatedConfig))
            
            // 创建配置
            val profile = ProfileUi(
                id = profileId,
                name = name,
                type = ProfileType.Subscription,
                url = url,
                lastUpdated = System.currentTimeMillis(),
                enabled = true,
                autoUpdateInterval = autoUpdateInterval,
                updateStatus = UpdateStatus.Idle,
                expireDate = userInfo?.expire ?: 0,
                totalTraffic = userInfo?.total ?: 0,
                usedTraffic = (userInfo?.upload ?: 0) + (userInfo?.download ?: 0)
            )
            
            // 保存到内存
            cacheConfig(profileId, deduplicatedConfig)
            profileNodes[profileId] = nodes
            updateAllNodesAndGroups()
            
            // 更新状态
            _profiles.update { it + profile }
            saveProfiles()
            
            // 如果是第一个配置，自动激活
            if (_activeProfileId.value == null) {
                setActiveProfile(profileId)
            }
            
            // 调度自动更新任务
            if (autoUpdateInterval > 0) {
                com.kunk.singbox.service.SubscriptionAutoUpdateWorker.schedule(context, profileId, autoUpdateInterval)
            }
            
            onProgress(context.getString(R.string.profiles_import_success, nodes.size.toString()))
            
            Result.success(profile)
        } catch (e: Exception) {
            Log.e(TAG, "Subscription import failed", e)
            // 确保抛出的异常信息对用户友好
            val msg = when(e) {
                is java.net.SocketTimeoutException -> "Connection timeout, please check your network"
                is java.net.UnknownHostException -> "Failed to resolve domain, please check the link"
                is javax.net.ssl.SSLHandshakeException -> "SSL certificate validation failed"
                else -> e.message ?: context.getString(R.string.profiles_import_failed)
            }
            Result.failure(Exception(msg))
        }
    }

    suspend fun importFromContent(
        name: String,
        content: String,
        profileType: ProfileType = ProfileType.Imported,
        onProgress: (String) -> Unit = {}
    ): Result<ProfileUi> = withContext(Dispatchers.IO) {
        try {
            onProgress(context.getString(R.string.common_loading))

            val normalized = normalizeImportedContent(content)
            val config = subscriptionManager.parse(normalized)
                ?: return@withContext Result.failure(Exception(context.getString(R.string.profiles_parse_failed)))

            onProgress(context.getString(R.string.profiles_extracting_nodes, 0, 0))

            val profileId = UUID.randomUUID().toString()
            val deduplicatedConfig = deduplicateTags(config)
            val nodes = extractNodesFromConfig(deduplicatedConfig, profileId, onProgress)

            if (nodes.isEmpty()) {
                return@withContext Result.failure(Exception("No valid nodes found")) // TODO: add to strings.xml
            }

            val configFile = File(configDir, "$profileId.json")
            configFile.writeText(gson.toJson(deduplicatedConfig))

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
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun normalizeImportedContent(content: String): String {
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

    private fun tryDecodeBase64(content: String): String? {
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
                Log.v(TAG, "Base64 decode attempt failed with flags=$flags", e)
            }
        }
        return null
    }
    
    /**
     * 从配置中只提取节点信息，忽略规则配置
     * 防止因 sing-box 规则版本更新导致解析失败
     */
    private fun extractOutboundsOnly(config: SingBoxConfig): SingBoxConfig {
        val outbounds = config.outbounds ?: config.proxies ?: emptyList()
        return SingBoxConfig(outbounds = outbounds)
    }

    /**
     * 从 JSON 字符串中宽松提取 outbounds 节点列表
     * 只解析 outbounds/proxies 字段，忽略其他可能不兼容的字段（如 route、dns 等）
     * 防止因 sing-box 规则版本更新导致整体解析失败
     */
    private fun extractOutboundsFromJson(jsonContent: String): List<Outbound>? {
        val trimmed = jsonContent.trim()
        if (!trimmed.startsWith("{")) return null

        return try {
            val jsonObject = JsonParser.parseString(trimmed).asJsonObject

            // 优先尝试 outbounds 字段
            val outboundsElement = jsonObject.get("outbounds") ?: jsonObject.get("proxies")
            if (outboundsElement != null && outboundsElement.isJsonArray) {
                val outbounds: List<Outbound> = gson.fromJson(outboundsElement, TYPE_OUTBOUND_LIST)
                if (outbounds.isNotEmpty()) {
                    return outbounds
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "extractOutboundsFromJson failed: ${e.message}")
            null
        }
    }

    private fun parseSubscriptionResponse(content: String): SingBoxConfig? {
        val normalizedContent = normalizeImportedContent(content)

        // 1. 尝试直接解析为 sing-box JSON (只提取节点信息，使用宽松解析避免规则字段不兼容)
        try {
            val outbounds = extractOutboundsFromJson(normalizedContent)
            if (outbounds != null && outbounds.isNotEmpty()) {
                return SingBoxConfig(outbounds = outbounds)
            } else {
                Log.w(TAG, "Parsed as JSON but outbounds/proxies is empty/null. content snippet: ${sanitizeSubscriptionSnippet(normalizedContent)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract outbounds from JSON: ${e.message}")
            // 继续尝试其他格式
        }

        // 1.5 尝试解析 Clash YAML
        try {
            val yamlConfig = parseClashYamlConfig(normalizedContent)
            if (yamlConfig?.outbounds != null && yamlConfig.outbounds.isNotEmpty()) {
                return extractOutboundsOnly(yamlConfig)
            }
        } catch (_: Exception) {
        }

        // 2. 尝试 Base64 解码后解析
        try {
            val decoded = tryDecodeBase64(normalizedContent)
            if (decoded.isNullOrBlank()) {
                throw IllegalStateException("base64 decode failed")
            }

            // 尝试解析解码后的内容为 JSON (使用宽松解析只提取节点)
            try {
                val outbounds = extractOutboundsFromJson(decoded)
                if (outbounds != null && outbounds.isNotEmpty()) {
                    return SingBoxConfig(outbounds = outbounds)
                } else {
                    Log.w(TAG, "Parsed decoded Base64 as JSON but outbounds is empty/null")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract outbounds from decoded Base64 JSON: ${e.message}")
            }

            try {
                val yamlConfig = parseClashYamlConfig(decoded)
                if (yamlConfig?.outbounds != null && yamlConfig.outbounds.isNotEmpty()) {
                    return extractOutboundsOnly(yamlConfig)
                }
            } catch (_: Exception) {
            }

        } catch (e: Exception) {
            // 继续尝试其他格式
        }
        
        // 3. 尝试解析为节点链接列表 (每行一个链接)
        try {
            val lines = normalizedContent.trim().lines().filter { it.isNotBlank() }
            if (lines.isNotEmpty()) {
                // 尝试 Base64 解码整体
                val decoded = tryDecodeBase64(normalizedContent) ?: normalizedContent
                
                val decodedLines = decoded.trim().lines().filter { it.isNotBlank() }
                val outbounds = mutableListOf<Outbound>()
                
                for (line in decodedLines) {
                    val cleanedLine = line.trim()
                        .removePrefix("- ")
                        .removePrefix("• ")
                        .trim()
                        .trim('`', '"', '\'')
                    val outbound = parseNodeLink(cleanedLine)
                    if (outbound != null) {
                        outbounds.add(outbound)
                    }
                }
                
                if (outbounds.isNotEmpty()) {
                    // 创建一个包含这些节点的配置
                    return SingBoxConfig(
                        outbounds = outbounds
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }

    /**
     * 解析单个节点链接 - 委托给 NodeLinkParser
     */
    private fun parseNodeLink(link: String): Outbound? {
        return nodeLinkParser.parse(link)
    }

    /**
     * 从配置中提取节点 - 使用协程并行处理提升性能
     */
    private suspend fun extractNodesFromConfig(
        config: SingBoxConfig,
        profileId: String,
        onProgress: ((String) -> Unit)? = null
    ): List<NodeUi> = withContext(Dispatchers.Default) {
        val outbounds = config.outbounds ?: return@withContext emptyList()
        val trafficRepo = TrafficRepository.getInstance(context)

        // 收集所有 selector 和 urltest 的 outbounds 作为分组
        val groupOutbounds = outbounds.filter {
            it.type == "selector" || it.type == "urltest"
        }

        // 创建节点到分组的映射
        val nodeToGroup = mutableMapOf<String, String>()
        groupOutbounds.forEach { group ->
            group.outbounds?.forEach { nodeName ->
                nodeToGroup[nodeName] = group.tag
            }
        }

        // 过滤出代理节点
        val proxyTypes = setOf(
            "shadowsocks", "vmess", "vless", "trojan",
            "hysteria", "hysteria2", "tuic", "wireguard",
            "shadowtls", "ssh", "anytls", "http", "socks"
        )

        // 收集所有被其他 outbound 作为 detour 引用的 tag（这些是辅助 outbound，不应显示为独立节点）
        val detourTags = outbounds.mapNotNull { it.detour }.toSet()

        val validOutbounds = outbounds.filter { 
            it.type in proxyTypes && it.tag !in detourTags
        }
        if (validOutbounds.isEmpty()) return@withContext emptyList()

        val total = validOutbounds.size
        val completed = AtomicInteger(0)
        val semaphore = Semaphore(PARALLEL_CONCURRENCY)

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

    /**
     * 从配置中提取节点 - 同步版本，用于导入场景
     */
    private fun extractNodesFromConfigSync(
        config: SingBoxConfig,
        profileId: String
    ): List<NodeUi> {
        val outbounds = config.outbounds ?: return emptyList()
        val trafficRepo = TrafficRepository.getInstance(context)

        // 收集所有 selector 和 urltest 的 outbounds 作为分组
        val groupOutbounds = outbounds.filter {
            it.type == "selector" || it.type == "urltest"
        }

        // 创建节点到分组的映射
        val nodeToGroup = mutableMapOf<String, String>()
        groupOutbounds.forEach { group ->
            group.outbounds?.forEach { nodeName ->
                nodeToGroup[nodeName] = group.tag
            }
        }

        // 过滤出代理节点
        val proxyTypes = setOf(
            "shadowsocks", "vmess", "vless", "trojan",
            "hysteria", "hysteria2", "tuic", "wireguard",
            "shadowtls", "ssh", "anytls", "http", "socks"
        )

        // 收集所有被其他 outbound 作为 detour 引用的 tag
        val detourTags = outbounds.mapNotNull { it.detour }.toSet()

        val validOutbounds = outbounds.filter {
            it.type in proxyTypes && it.tag !in detourTags
        }
        if (validOutbounds.isEmpty()) return emptyList()

        return validOutbounds.mapNotNull { outbound ->
            createNodeUi(outbound, profileId, nodeToGroup, trafficRepo)
        }
    }

    /**
     * 创建单个节点 UI 对象
     */
    private fun createNodeUi(
        outbound: Outbound,
        profileId: String,
        nodeToGroup: Map<String, String>,
        trafficRepo: TrafficRepository
    ): NodeUi? {
        if (outbound.tag.isBlank()) return null

        var group = nodeToGroup[outbound.tag] ?: "Default"

        // 校验分组名是否为有效名称 (避免链接被当作分组名)
        if (group.contains("://") || group.length > 50) {
            group = "未分组"
        }

        var regionFlag = detectRegionFlag(outbound.tag)

        // 如果从名称无法识别地区，尝试更深层次的信息挖掘
        if (regionFlag == "🌐" || regionFlag.isBlank()) {
            // 1. 尝试 SNI (通常 CDN 节点会使用 SNI 指向真实域名)
            val sni = outbound.tls?.serverName
            if (!sni.isNullOrBlank()) {
                val sniRegion = detectRegionFlag(sni)
                if (sniRegion != "🌐" && sniRegion.isNotBlank()) regionFlag = sniRegion
            }

            // 2. 尝试 Host (WS/HTTP Host)
            if ((regionFlag == "🌐" || regionFlag.isBlank())) {
                val host = outbound.transport?.headers?.get("Host")
                    ?: outbound.transport?.host?.firstOrNull()
                if (!host.isNullOrBlank()) {
                    val hostRegion = detectRegionFlag(host)
                    if (hostRegion != "🌐" && hostRegion.isNotBlank()) regionFlag = hostRegion
                }
            }

            // 3. 最后尝试服务器地址 (可能是 CDN IP，准确度较低，作为兜底)
            if ((regionFlag == "🌐" || regionFlag.isBlank()) && !outbound.server.isNullOrBlank()) {
                val serverRegion = detectRegionFlag(outbound.server)
                if (serverRegion != "🌐" && serverRegion.isNotBlank()) regionFlag = serverRegion
            }
        }

        val finalRegionFlag = regionFlag
        val id = stableNodeId(profileId, outbound.tag)

        return NodeUi(
            id = id,
            name = outbound.tag,
            protocol = outbound.type,
            group = group,
            regionFlag = finalRegionFlag,
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
    
    /**
     * 检测字符串是否包含国旗 Emoji
     */
    private fun containsFlagEmoji(str: String): Boolean {
        // 匹配区域指示符符号 (Regional Indicator Symbols) U+1F1E6..U+1F1FF
        // 两个区域指示符符号组成一个国旗
        // Java/Kotlin 中，这些字符是代理对 (Surrogate Pairs)
        // U+1F1E6 是 \uD83C\uDDE6
        // U+1F1FF 是 \uD83C\uDDFF
        // 正则表达式匹配两个连续的区域指示符
        return REGEX_FLAG_EMOJI.containsMatchIn(str)
    }

    /**
     * 根据节点名称检测地区标志
     *
     * 使用预编译规则和缓存优化性能
     */
    private fun detectRegionFlag(name: String): String {
        // 先查缓存
        regionFlagCache[name]?.let { return it }

        val lowerName = name.lowercase()

        for (rule in REGION_RULES) {
            // 1. 检查中文关键词 (直接 contains)
            if (rule.chineseKeywords.any { lowerName.contains(it) }) {
                regionFlagCache[name] = rule.flag
                return rule.flag
            }

            // 2. 检查英文关键词 (直接 contains)
            if (rule.englishKeywords.any { lowerName.contains(it) }) {
                regionFlagCache[name] = rule.flag
                return rule.flag
            }

            // 3. 检查需要词边界的短代码 (使用预编译 Regex)
            if (rule.wordBoundaryKeywords.any { word ->
                WORD_BOUNDARY_REGEX_MAP[word]?.containsMatchIn(lowerName) == true
            }) {
                regionFlagCache[name] = rule.flag
                return rule.flag
            }
        }

        regionFlagCache[name] = "🌐"
        return "🌐"
    }
    
    fun setActiveProfile(profileId: String, targetNodeId: String? = null) {
        val currentProfileId = _activeProfileId.value
        val currentNodeId = _activeNodeId.value
        if (currentProfileId != null && currentNodeId != null && currentProfileId != profileId) {
            saveProfileNodeMemory(currentProfileId, currentNodeId)
        }

        _activeProfileId.value = profileId
        val cached = profileNodes[profileId]

        fun updateState(nodes: List<NodeUi>) {
            _nodes.value = nodes

            val currentActiveId = _activeNodeId.value

            if (targetNodeId != null && nodes.any { it.id == targetNodeId }) {
                _activeNodeId.value = targetNodeId
            } else if (currentActiveId != null && nodes.any { it.id == currentActiveId }) {
                // keep current
            } else {
                val rememberedNodeId = getProfileLastSelectedNode(profileId)
                if (rememberedNodeId != null && nodes.any { it.id == rememberedNodeId }) {
                    _activeNodeId.value = rememberedNodeId
                } else if (nodes.isNotEmpty()) {
                    _activeNodeId.value = nodes.first().id
                }
            }
        }

        if (cached != null) {
            updateState(cached)
        } else {
            _nodes.value = emptyList()
            scope.launch {
                val cfg = loadConfig(profileId) ?: return@launch
                val nodes = extractNodesFromConfig(cfg, profileId)
                // 从 savedNodeLatencies 恢复延迟数据
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
    }
    
    sealed class NodeSwitchResult {
        object Success : NodeSwitchResult()
        object NotRunning : NodeSwitchResult()
        data class Failed(val reason: String) : NodeSwitchResult()
    }

    fun setActiveNodeIdOnly(nodeId: String) {
        _activeNodeId.value = nodeId
        _activeProfileId.value?.let { profileId ->
            saveProfileNodeMemory(profileId, nodeId)
        }
        saveProfilesImmediate()
    }

    suspend fun setActiveNode(nodeId: String): Boolean {
        val result = setActiveNodeWithResult(nodeId)
        return result is NodeSwitchResult.Success || result is NodeSwitchResult.NotRunning
    }

    suspend fun setActiveNodeWithResult(nodeId: String): NodeSwitchResult {
        val allNodesSnapshot = _allNodes.value.takeIf { it.isNotEmpty() } ?: loadAllNodesSnapshot()

        // Check for cross-profile switch
        val targetNode = allNodesSnapshot.find { it.id == nodeId }
        if (targetNode != null && targetNode.sourceProfileId != _activeProfileId.value) {
            Log.i(TAG, "Cross-profile switch detected: ${_activeProfileId.value} -> ${targetNode.sourceProfileId}")

            // 2025-fix: Ensure profile is loaded synchronously before switching
            // This prevents race condition where _nodes is empty during generateConfigFile
            val profileId = targetNode.sourceProfileId
            withContext(Dispatchers.IO) {
                if (profileNodes[profileId] == null) {
                    Log.i(TAG, "Pre-loading profile nodes for $profileId")
                    loadConfig(profileId)?.let { cfg ->
                        val nodes = extractNodesFromConfig(cfg, profileId)
                        // 从 savedNodeLatencies 恢复延迟数据
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
        saveProfilesImmediate()

        val remoteRunning = SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value
        if (!remoteRunning) {
            Log.i(TAG, "setActiveNodeWithResult: VPN not running, skip hot switch")
            return NodeSwitchResult.NotRunning
        }
        
        return withContext(Dispatchers.IO) {
            // 尝试从当前配置查找节点
            var node = _nodes.value.find { it.id == nodeId }
            
            // 如果找不到，尝试从所有节点查找（支持跨配置切换）
            if (node == null) {
                node = allNodesSnapshot.find { it.id == nodeId }
            }

            if (node == null) {
                val msg = "Target node not found: $nodeId"
                Log.w(TAG, msg)
                return@withContext NodeSwitchResult.Failed(msg)
            }
            
            try {
                val generationResult = generateConfigFile()
                if (generationResult == null) {
                    val msg = context.getString(R.string.dashboard_config_generation_failed)
                    Log.e(TAG, msg)
                    return@withContext NodeSwitchResult.Failed(msg)
                }

                // ... [Skipping comments for brevity in replacement]
                
                // 修正 cache.db 清理逻辑
                // 注意：这里删除可能不生效，因为 Service 进程关闭时可能会再次写入 cache.db
                // 因此我们在 Service 进程启动时增加了一个 EXTRA_CLEAN_CACHE 参数来确保删除
                runCatching {
                    // 兼容清理旧位置
                    val oldCacheDb = File(context.filesDir, "cache.db")
                    if (oldCacheDb.exists()) oldCacheDb.delete()
                }

                // 检查是否需要重启服务：如果 Outbound 列表发生了变化（例如跨配置切换、增删节点），
                // 或者当前配置 ID 发生了变化（跨配置切换），则必须重启 VPN 以加载新的配置文件。
                val currentTags = generationResult.outboundTags
                val currentProfileId = _activeProfileId.value
                
                // 2025-fix: 改进 profileChanged 判断逻辑
                // 问题：当 App 重启后 lastRunProfileId 为 null，但 VPN 已在运行时，
                // 跨配置切换不会触发重启，导致热切换使用旧配置中的 selector
                // 修复：如果 VPN 已在运行但 lastRunProfileId 为 null，视为首次切换，需要重启以确保配置同步
                val isFirstSwitchWhileRunning = lastRunProfileId == null && remoteRunning
                val profileChanged = (lastRunProfileId != null && lastRunProfileId != currentProfileId) || isFirstSwitchWhileRunning
                
                // 2025-fix-v5: 统一的重启判断逻辑
                // 需要重启 VPN 的场景：
                // 1. outboundTags 实际发生变化（节点列表不同）
                // 2. profileChanged（跨配置切换，即使 tags 相同也需要重启，因为 sing-box 核心中的 selector 不包含新节点）
                // 3. VPN 正在启动中（核心还没准备好接受热切换）
                // 4. lastRunOutboundTags 为 null（首次运行或 App 重启后状态丢失）
                val tagsActuallyChanged = lastRunOutboundTags != null && lastRunOutboundTags != currentTags
                val isVpnStartingNotReady = SingBoxRemote.isStarting.value && !SingBoxRemote.isRunning.value
                val needsConfigReload = lastRunOutboundTags == null && remoteRunning

                val tagsChanged = tagsActuallyChanged || profileChanged || isVpnStartingNotReady || needsConfigReload

                Log.d(TAG, "Switch decision: profileChanged=$profileChanged (last=$lastRunProfileId, cur=$currentProfileId, firstSwitch=$isFirstSwitchWhileRunning), tagsActuallyChanged=$tagsActuallyChanged, isVpnStartingNotReady=$isVpnStartingNotReady, needsConfigReload=$needsConfigReload, tagsChanged=$tagsChanged")
                
                // 更新缓存（在判断之后更新，确保下次能正确比较）
                lastRunOutboundTags = currentTags
                lastRunProfileId = currentProfileId


                val coreMode = VpnStateStore.getMode()

                // ⭐ 2025-fix: 跨配置切换预清理机制
                // 如果需要重启 VPN (tagsChanged=true)，先发送预清理信号让 Service 关闭现有连接
                // 这样应用（如 Telegram）能立即感知网络中断，而不是在旧连接上等待超时
                if (tagsChanged && remoteRunning) {
                    Log.i(TAG, "Sending PREPARE_RESTART before VPN restart")
                    val prepareIntent = if (coreMode == VpnStateStore.CoreMode.PROXY) {
                        Intent(context, ProxyOnlyService::class.java).apply {
                            action = ProxyOnlyService.ACTION_PREPARE_RESTART
                        }
                    } else {
                        Intent(context, SingBoxService::class.java).apply {
                            action = SingBoxService.ACTION_PREPARE_RESTART
                        }
                    }
                    context.startService(prepareIntent)
                    // 2025-fix-v2: 简化后的预清理只需等待网络广播发送
                    // 底层网络断开(立即) + 等待应用收到广播(100ms) + 缓冲(50ms)
                    // 注意: 不再需要等待 closeAllConnectionsImmediate，sing-box restart 会自动处理
                    delay(200)
                }

                val intent = if (coreMode == VpnStateStore.CoreMode.PROXY) {
                    Intent(context, ProxyOnlyService::class.java).apply {
                        if (tagsChanged) {
                            action = ProxyOnlyService.ACTION_START
                            Log.i(TAG, "Outbound tags changed (or first run), forcing RESTART/RELOAD")
                        } else {
                            action = ProxyOnlyService.ACTION_SWITCH_NODE
                            Log.i(TAG, "Outbound tags match, attempting HOT SWITCH")
                        }
                        putExtra("node_id", nodeId)
                        putExtra("outbound_tag", generationResult.activeNodeTag)
                        putExtra(ProxyOnlyService.EXTRA_CONFIG_PATH, generationResult.path)
                    }
                } else {
                    Intent(context, SingBoxService::class.java).apply {
                        if (tagsChanged) {
                            action = SingBoxService.ACTION_START
                            putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
                            Log.i(TAG, "Outbound tags changed (or first run), forcing RESTART/RELOAD with CACHE CLEAN")
                        } else {
                            action = SingBoxService.ACTION_SWITCH_NODE
                            Log.i(TAG, "Outbound tags match, attempting HOT SWITCH")
                        }
                        putExtra("node_id", nodeId)
                        putExtra("outbound_tag", generationResult.activeNodeTag)
                        putExtra(SingBoxService.EXTRA_CONFIG_PATH, generationResult.path)
                    }
                }

                // Service already running (VPN active). Use startService to avoid foreground-service timing constraints.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && tagsChanged) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }

                Log.i(TAG, "Requested switch for node: ${node.name} (Tag: ${generationResult.activeNodeTag}, Restart: $tagsChanged)")
                NodeSwitchResult.Success
            } catch (e: Exception) {

                val msg = "Switch error: ${e.message ?: "unknown error"}"
                Log.e(TAG, "Error during hot switch", e)
                NodeSwitchResult.Failed(msg)
            }
        }
    }

    suspend fun syncActiveNodeFromProxySelection(proxyName: String?): Boolean {
        if (proxyName.isNullOrBlank()) return false

        val activeProfileId = _activeProfileId.value ?: return false
        val candidates = _nodes.value
        val matched = candidates.firstOrNull { it.name == proxyName } ?: return false
        if (matched.sourceProfileId != activeProfileId) return false
        if (_activeNodeId.value == matched.id) return true

        _activeNodeId.value = matched.id
        Log.i(TAG, "Synced active node from service selection: $proxyName -> ${matched.id}")
        return true
    }
    
    fun deleteProfile(profileId: String) {
        // 取消自动更新任务
        com.kunk.singbox.service.SubscriptionAutoUpdateWorker.cancel(context, profileId)

        _profiles.update { list -> list.filter { it.id != profileId } }
        removeCachedConfig(profileId)
        profileNodes.remove(profileId)
        updateAllNodesAndGroups()

        // 删除配置文件
        File(configDir, "$profileId.json").delete()

        // 从 Room 删除
        scope.launch {
            try {
                profileDao.deleteById(profileId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete profile from Room", e)
            }
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

    /**
     * 直接导入配置到 Room 数据库
     * 用于数据恢复/导入场景，保持原始 Profile ID
     */
    fun importProfileDirectly(profile: ProfileUi, config: SingBoxConfig) {
        val deduplicatedConfig = deduplicateTags(config)

        // 缓存配置
        cacheConfig(profile.id, deduplicatedConfig)

        // 提取节点
        val nodes = extractNodesFromConfigSync(deduplicatedConfig, profile.id)
        profileNodes[profile.id] = nodes

        // 更新内存状态
        _profiles.update { list ->
            // 移除同 ID 的旧配置
            val filtered = list.filter { it.id != profile.id }
            filtered + profile
        }
        updateAllNodesAndGroups()

        // 保存到 Room
        scope.launch {
            try {
                val sortOrder = profileDao.getMaxSortOrder() ?: 0
                val entity = ProfileEntity.fromUiModel(profile, sortOrder = sortOrder + 1)
                profileDao.insert(entity)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save imported profile to Room", e)
            }
        }

        // 如果是第一个配置，自动激活
        if (_activeProfileId.value == null) {
            setActiveProfile(profile.id)
        }
    }

    fun toggleProfileEnabled(profileId: String) {
        _profiles.update { list ->
            list.map {
                if (it.id == profileId) it.copy(enabled = !it.enabled) else it
            }
        }
        saveProfiles()
    }

    fun updateProfileMetadata(profileId: String, newName: String, newUrl: String?, autoUpdateInterval: Int = 0) {
        _profiles.update { list ->
            list.map {
                if (it.id == profileId) {
                    it.copy(name = newName, url = newUrl, autoUpdateInterval = autoUpdateInterval)
                } else {
                    it
                }
            }
        }
        saveProfiles()
        
        // 调度或取消自动更新任务
        com.kunk.singbox.service.SubscriptionAutoUpdateWorker.schedule(context, profileId, autoUpdateInterval)
    }

    /**
     * 测试单个节点的延迟（真正通过代理测试）
     * @param nodeId 节点 ID
     * @return 延迟时间（毫秒），-1 表示测试失败
     */
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

        val settings = SettingsRepository.getInstance(context).settings.first()
        val totalTimeoutMs = settings.latencyTestTimeout.toLong()

        try {
            val result = withContext(Dispatchers.IO) {
                withTimeoutOrNull(totalTimeoutMs) {
                    try {
                        // 优先从 _nodes 查找，找不到则从 _allNodes 查找（支持非活跃配置的节点）
                        val node = _nodes.value.find { it.id == nodeId }
                            ?: _allNodes.value.find { it.id == nodeId }
                        if (node == null) {
                            Log.e(TAG, "Node not found: $nodeId")
                            return@withTimeoutOrNull -1L
                        }

                        val config = loadConfig(node.sourceProfileId)
                        if (config == null) {
                            Log.e(TAG, "Config not found for profile: ${node.sourceProfileId}")
                            return@withTimeoutOrNull -1L
                        }

                        val outbound = config.outbounds?.find { it.tag == node.name }
                        if (outbound == null) {
                            Log.e(TAG, "Outbound not found: ${node.name}")
                            return@withTimeoutOrNull -1L
                        }

                        val fixedOutbound = buildOutboundForRuntime(outbound)
                        val allOutbounds = config.outbounds?.map { buildOutboundForRuntime(it) } ?: emptyList()
                        val latency = singBoxCore.testOutboundLatency(fixedOutbound, allOutbounds)

                        _nodes.update { list ->
                            list.map {
                                if (it.id == nodeId) it.copy(latencyMs = if (latency > 0) latency else -1L) else it
                            }
                        }

                        profileNodes[node.sourceProfileId] = profileNodes[node.sourceProfileId]?.map {
                            if (it.id == nodeId) it.copy(latencyMs = if (latency > 0) latency else -1L) else it
                        } ?: emptyList()
                        updateLatencyInAllNodes(nodeId, latency)
                        saveProfiles()

                        latency
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) {
                            throw e
                        }
                        Log.e(TAG, "Latency test error for $nodeId", e)
                        val nodeName = _nodes.value.find { it.id == nodeId }?.name
                            ?: _allNodes.value.find { it.id == nodeId }?.name
                        LogRepository.getInstance().addLog(context.getString(R.string.nodes_test_failed, nodeName ?: nodeId) + ": ${e.message}")
                        -1L
                    }
                } ?: run {
                    Log.w(TAG, "Latency test timeout for $nodeId (${totalTimeoutMs}ms)")
                    updateNodeLatencyFull(nodeId, -1L)
                    -1L
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

    /**
     * 批量测试所有节点的延迟
     * 使用并发方式提高效率
     */
    suspend fun clearAllNodesLatency() = withContext(Dispatchers.IO) {
        // 清除已保存的延时数据
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
        onNodeComplete: ((nodeId: String, latencyMs: Long) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        // 根据 useAllNodes 参数决定使用 _nodes 还是 _allNodes
        val sourceNodes = if (useAllNodes) _allNodes.value else _nodes.value
        val nodes = if (targetNodeIds != null) {
            sourceNodes.filter { it.id in targetNodeIds }
        } else {
            sourceNodes
        }

        data class NodeTestInfo(
            val outbound: Outbound,
            val nodeId: String,
            val profileId: String
        )

        val testInfoList = nodes.mapNotNull { node ->
            val config = loadConfig(node.sourceProfileId) ?: return@mapNotNull null
            val outbound = config.outbounds?.find { it.tag == node.name } ?: return@mapNotNull null
            NodeTestInfo(buildOutboundForRuntime(outbound), node.id, node.sourceProfileId)
        }

        if (testInfoList.isEmpty()) {
            Log.w(TAG, "No valid nodes to test")
            return@withContext
        }

        val outbounds = testInfoList.map { it.outbound }
        val tagToInfo = testInfoList.associateBy { it.outbound.tag }

        singBoxCore.testOutboundsLatency(outbounds) { tag, latency ->
            val info = tagToInfo[tag] ?: return@testOutboundsLatency
            val latencyValue = if (latency > 0) latency else -1L
            
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

        saveProfiles()
    }

    suspend fun updateAllProfiles(): BatchUpdateResult = withContext(Dispatchers.IO) {
        val enabledProfiles = _profiles.value.filter { it.enabled && it.type == ProfileType.Subscription }

        if (enabledProfiles.isEmpty()) {
            return@withContext BatchUpdateResult()
        }

        // 并行更新所有订阅，限制并发数为 3
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
    
    suspend fun updateProfile(profileId: String): SubscriptionUpdateResult {
        val profile = _profiles.value.find { it.id == profileId }
            ?: return SubscriptionUpdateResult.Failed("未知配置", "配置不存在")
        
        if (profile.url.isNullOrBlank()) {
            return SubscriptionUpdateResult.Failed(profile.name, "无订阅链接")
        }
        
        _profiles.update { list ->
            list.map {
                if (it.id == profileId) it.copy(updateStatus = UpdateStatus.Updating) else it
            }
        }
        
        val result = try {
            importFromSubscriptionUpdate(profile)
        } catch (e: Exception) {
            SubscriptionUpdateResult.Failed(profile.name, e.message ?: "未知错误")
        }

        // 更新状态为 Success/Failed
        _profiles.update { list ->
            list.map {
                if (it.id == profileId) it.copy(
                    updateStatus = if (result is SubscriptionUpdateResult.Failed) UpdateStatus.Failed else UpdateStatus.Success,
                    lastUpdated = if (result is SubscriptionUpdateResult.Failed) it.lastUpdated else System.currentTimeMillis()
                ) else it
            }
        }

        // 异步延迟重置状态，不阻塞当前方法返回
        profileResetJobs.remove(profileId)?.cancel()
        val resetJob = scope.launch {
            kotlinx.coroutines.delay(2000)
            _profiles.update { list ->
                list.map {
                    if (it.id == profileId && it.updateStatus != UpdateStatus.Updating) {
                        it.copy(updateStatus = UpdateStatus.Idle)
                    } else {
                        it
                    }
                }
            }
        }
        resetJob.invokeOnCompletion {
            profileResetJobs.remove(profileId, resetJob)
        }
        profileResetJobs[profileId] = resetJob
        
        return result
    }
    
    private suspend fun importFromSubscriptionUpdate(profile: ProfileUi): SubscriptionUpdateResult = withContext(Dispatchers.IO) {
        try {
            // 获取旧的节点列表用于比较
            val oldNodes = profileNodes[profile.id] ?: emptyList()
            val oldNodeNames = oldNodes.map { it.name }.toSet()
            
            // 使用智能 User-Agent 切换策略获取订阅
            val fetchResult = fetchAndParseSubscription(profile.url!!) { /* 静默更新，不显示进度 */ }
                ?: return@withContext SubscriptionUpdateResult.Failed(profile.name, "无法解析配置")
            
            val config = fetchResult.config
            val userInfo = fetchResult.userInfo

            val deduplicatedConfig = deduplicateTags(config)
            val newNodes = extractNodesFromConfig(deduplicatedConfig, profile.id)
            val newNodeNames = newNodes.map { it.name }.toSet()
            
            // 计算变化
            val addedNodes = newNodeNames - oldNodeNames
            val removedNodes = oldNodeNames - newNodeNames
            
            // 更新存储
            val configFile = File(configDir, "${profile.id}.json")
            configFile.writeText(gson.toJson(deduplicatedConfig))
            
            cacheConfig(profile.id, deduplicatedConfig)
            profileNodes[profile.id] = newNodes
            updateAllNodesAndGroups()
            
            // 如果是当前活跃配置，更新节点列表
            if (_activeProfileId.value == profile.id) {
                _nodes.value = newNodes
            }

            // 更新用户信息
            _profiles.update { list ->
                list.map {
                    if (it.id == profile.id) {
                        it.copy(
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
            
            // 返回结果
            if (addedNodes.isNotEmpty() || removedNodes.isNotEmpty()) {
                SubscriptionUpdateResult.SuccessWithChanges(
                    profileName = profile.name,
                    addedCount = addedNodes.size,
                    removedCount = removedNodes.size,
                    totalCount = newNodes.size
                )
            } else {
                SubscriptionUpdateResult.SuccessNoChanges(
                    profileName = profile.name,
                    totalCount = newNodes.size
                )
            }
        } catch (e: Exception) {
            SubscriptionUpdateResult.Failed(profile.name, e.message ?: "未知错误")
        }
    }
    
    data class ConfigGenerationResult(
        val path: String,
        val activeNodeTag: String?,
        val outboundTags: Set<String>
    )

    /**
     * 生成用于 VPN 服务的配置文件
     * @return 配置文件路径和当前活跃节点的 Tag
     */
    suspend fun generateConfigFile(): ConfigGenerationResult? = withContext(Dispatchers.IO) {
        try {
            val activeId = _activeProfileId.value 
                ?: activeStateDao.getSync()?.activeProfileId 
                ?: return@withContext null
            val config = loadConfig(activeId) ?: return@withContext null
            
            // 优先使用内存中的值，若为空则从数据库同步读取（解决异步加载竞态问题）
            val activeNodeId = _activeNodeId.value 
                ?: activeStateDao.getSync()?.activeNodeId
            
            val allNodesSnapshot = _allNodes.value.takeIf { it.isNotEmpty() } ?: loadAllNodesSnapshot()
            val activeNode = _nodes.value.find { it.id == activeNodeId }
                ?: allNodesSnapshot.find { it.id == activeNodeId }
            
            // 获取当前设置
            val settings = settingsRepository.settings.first()

            // 构建完整的运行配置
            val log = buildRunLogConfig()
            val experimental = buildRunExperimentalConfig(settings)
            val inbounds = buildRunInbounds(settings)
            
            // 先构建有效的规则集列表，供 DNS 和 Route 模块共用
            val customRuleSets = buildCustomRuleSets(settings)
            
            val dns = buildRunDns(settings, customRuleSets)

            val outboundsContext = buildRunOutbounds(config, activeNode, settings, allNodesSnapshot)
            val route = buildRunRoute(settings, outboundsContext.selectorTag, outboundsContext.outbounds, outboundsContext.nodeTagResolver, customRuleSets)

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

            val validation = singBoxCore.validateConfig(runConfig)
            validation.exceptionOrNull()?.let { e ->
                val msg = e.cause?.message ?: e.message ?: "unknown error"
                Log.e(TAG, "Config pre-validation failed: $msg", e)
                throw Exception("Config validation failed: $msg", e)
            }
            
            // 写入临时配置文件
            val configFile = File(context.filesDir, "running_config.json")
            configFile.writeText(gson.toJson(runConfig))
            
            // 收集所有 Outbound 的 tag
            val allTags = runConfig.outbounds?.map { it.tag }?.toSet() ?: emptySet()
            
            // 解析当前选中的节点在运行配置中的实际 Tag
            // 关键修复：确保 resolvedTag 指向一个实际存在的 outbound
            val candidateTag = activeNodeId?.let { outboundsContext.nodeTagMap[it] }
                ?: activeNode?.name
            
            val resolvedTag = if (candidateTag != null && allTags.contains(candidateTag)) {
                candidateTag
            } else {
                // 跨配置节点加载失败，回退到 PROXY selector 的 default
                val proxySelector = runConfig.outbounds?.find { it.tag == "PROXY" }
                val fallback = proxySelector?.default ?: proxySelector?.outbounds?.firstOrNull()
                if (candidateTag != null) {
                    Log.w(TAG, "Selected node tag '$candidateTag' not found in outbounds, falling back to: $fallback")
                }
                fallback
            }

            ConfigGenerationResult(configFile.absolutePath, resolvedTag, allTags)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate config file", e)
            null
        }
    }

    /**
     * 运行时修复 Outbound 配置 - 委托给 OutboundFixer
     */
    private fun fixOutboundForRuntime(outbound: Outbound): Outbound = OutboundFixer.fix(outbound)

    private fun buildOutboundForRuntime(outbound: Outbound): Outbound = OutboundFixer.buildForRuntime(outbound)

    /**
     * 构建自定义规则集配置
     */
    private fun buildCustomRuleSets(settings: AppSettings): List<RuleSetConfig> {
        val ruleSetRepo = RuleSetRepository.getInstance(context)

        val rules = settings.ruleSets.map { ruleSet ->
            if (ruleSet.type == RuleSetType.REMOTE) {
                // 远程规则集：使用预下载的本地缓存
                val localPath = ruleSetRepo.getRuleSetPath(ruleSet.tag)
                val file = File(localPath)
                if (file.exists() && file.length() > 0) {
                    // 简单的文件头检查 (SRS magic: 0x73, 0x72, 0x73, 0x0A or similar, but sing-box is flexible)
                    // 如果文件太小或者内容明显不对（比如 HTML 错误页），则跳过
                    // 这里我们假设小于 100 字节的文件可能是无效的，或者是下载错误
                    if (file.length() < 10) {
                        Log.w(TAG, "Rule set file too small, ignoring: ${ruleSet.tag} (${file.length()} bytes)")
                        return@map null
                    }
                    
                    // 检查文件头是否为 HTML (下载错误常见情况)
                    try {
                        val header = file.inputStream().use { input ->
                            val buffer = ByteArray(64) // 读取更多字节以防前导空格
                            val read = input.read(buffer)
                            if (read > 0) String(buffer, 0, read) else ""
                        }
                        val trimmedHeader = header.trim()
                        if (trimmedHeader.startsWith("<!DOCTYPE html", ignoreCase = true) ||
                            trimmedHeader.startsWith("<html", ignoreCase = true) ||
                            trimmedHeader.startsWith("{")) { // 也是为了防止 JSON 错误信息
                            Log.e(TAG, "Rule set file appears to be invalid (HTML/JSON), ignoring: ${ruleSet.tag}")
                            // 删除无效文件以便下次重新下载
                            file.delete()
                            return@map null
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to inspect rule set file header: ${ruleSet.tag}", e)
                    }

                    RuleSetConfig(
                        tag = ruleSet.tag,
                        type = "local",
                        format = ruleSet.format,
                        path = localPath
                    )
                } else {
                    Log.w(TAG, "Rule set file not found or empty: ${ruleSet.tag} ($localPath)")
                    null
                }
            } else {
                // 本地规则集：直接使用用户指定的路径
                val file = File(ruleSet.path)
                if (file.exists() && file.length() > 0) {
                    RuleSetConfig(
                        tag = ruleSet.tag,
                        type = "local",
                        format = ruleSet.format,
                        path = ruleSet.path
                    )
                } else {
                    Log.w(TAG, "Local rule set file not found: ${ruleSet.tag} (${ruleSet.path})")
                    null
                }
            }
        }.filterNotNull().toMutableList()

        if (settings.blockAds) {
            val adBlockTag = "geosite-category-ads-all"
            val adBlockPath = ruleSetRepo.getRuleSetPath(adBlockTag)
            val adBlockFile = File(adBlockPath)

            if (!adBlockFile.exists() || adBlockFile.length() == 0L) {
                try {
                    context.assets.open("rulesets/$adBlockTag.srs").use { input ->
                        adBlockFile.parentFile?.mkdirs()
                        adBlockFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy built-in ad block rule set", e)
                }
            }

            if (adBlockFile.exists() && adBlockFile.length() > 0 && rules.none { it.tag == adBlockTag }) {
                rules.add(
                    RuleSetConfig(
                        tag = adBlockTag,
                        type = "local",
                        format = "binary",
                        path = adBlockPath
                    )
                )
            }
        }

        return rules
    }

    private fun buildCustomDomainRules(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>,
        nodeTagResolver: (String?) -> String?
    ): List<RouteRule> {
        fun splitValues(raw: String): List<String> {
            return raw
                .split("\n", "\r", ",", "，")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        fun resolveOutboundTag(mode: RuleSetOutboundMode?, value: String?): String {
            return when (mode ?: RuleSetOutboundMode.PROXY) {
                RuleSetOutboundMode.DIRECT -> "direct"
                RuleSetOutboundMode.BLOCK -> "block"
                RuleSetOutboundMode.PROXY -> defaultProxyTag
                RuleSetOutboundMode.NODE -> {
                    val resolvedTag = nodeTagResolver(value)
                    if (resolvedTag != null) resolvedTag else defaultProxyTag
                }
                RuleSetOutboundMode.PROFILE -> {
                    val profileId = value
                    val profileName = _profiles.value.find { it.id == profileId }?.name ?: "Profile_$profileId"
                    val tag = "P:$profileName"
                    if (outbounds.any { it.tag == tag }) tag else defaultProxyTag
                }
            }
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

                val mode = rule.outboundMode ?: when (rule.outbound) {
                    OutboundTag.DIRECT -> RuleSetOutboundMode.DIRECT
                    OutboundTag.BLOCK -> RuleSetOutboundMode.BLOCK
                    OutboundTag.PROXY -> RuleSetOutboundMode.PROXY
                }

                val outbound = resolveOutboundTag(mode, rule.outboundValue)
                Log.d(TAG, "CustomDomainRule: type=${rule.type}, value=${rule.value}, mode=$mode, outboundValue=${rule.outboundValue}, resolved=$outbound")
                when (rule.type) {
                    RuleType.DOMAIN -> RouteRule(domain = values, outbound = outbound)
                    RuleType.DOMAIN_SUFFIX -> RouteRule(domainSuffix = values, outbound = outbound)
                    RuleType.DOMAIN_KEYWORD -> RouteRule(domainKeyword = values, outbound = outbound)
                    else -> null
                }
            }
        Log.d(TAG, "buildCustomDomainRules: generated ${rules.size} rules")
        return rules
    }

    /**
     * 构建自定义规则集路由规则
     */
    private fun buildCustomRuleSetRules(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>,
        nodeTagResolver: (String?) -> String?,
        validRuleSets: List<RuleSetConfig>
    ): List<RouteRule> {
        val rules = mutableListOf<RouteRule>()

        // 记录所有可用的 outbound tags，用于调试
        val availableTags = outbounds.map { it.tag }
        
        val validTags = validRuleSets.mapNotNull { it.tag }.toSet()
        
        // 对规则集进行排序：更具体的规则应该排在前面
        // 优先级：单节点/分组 > 代理 > 直连 > 拦截
        // 同时，特定服务的规则（如 google, youtube）应该优先于泛化规则（如 geolocation-!cn）
        // 并且只处理有效的规则集
        val sortedRuleSets = settings.ruleSets.filter { it.enabled && it.tag in validTags }.sortedWith(
            compareBy(
                // 泛化规则排后面（如 geolocation-!cn, geolocation-cn）
                { ruleSet ->
                    when {
                        ruleSet.tag.contains("geolocation-!cn") -> 100
                        ruleSet.tag.contains("geolocation-cn") -> 99
                        ruleSet.tag.contains("!cn") -> 98
                        else -> 0
                    }
                },
                // 单节点模式的规则优先
                { ruleSet ->
                    when (ruleSet.outboundMode) {
                        RuleSetOutboundMode.NODE -> 0
                        RuleSetOutboundMode.PROXY -> 1
                        RuleSetOutboundMode.DIRECT -> 2
                        RuleSetOutboundMode.BLOCK -> 3
                        RuleSetOutboundMode.PROFILE -> 1
                        null -> 4
                    }
                }
            )
        )
        
        
        sortedRuleSets.forEach { ruleSet ->
            
            val outboundTag = when (ruleSet.outboundMode ?: RuleSetOutboundMode.DIRECT) {
                RuleSetOutboundMode.DIRECT -> "direct"
                RuleSetOutboundMode.BLOCK -> "block"
                RuleSetOutboundMode.PROXY -> defaultProxyTag
                RuleSetOutboundMode.NODE -> {
                    val resolvedTag = nodeTagResolver(ruleSet.outboundValue)
                    if (resolvedTag != null) {
                         resolvedTag
                    } else {
                         Log.w(TAG, "Node ID '${ruleSet.outboundValue}' not resolved to any tag, falling back to $defaultProxyTag")
                         defaultProxyTag
                    }
                }
                RuleSetOutboundMode.PROFILE -> {
                     val profileId = ruleSet.outboundValue
                     val profileName = _profiles.value.find { it.id == profileId }?.name ?: "Profile_$profileId"
                     val tag = "P:$profileName"
                     if (outbounds.any { it.tag == tag }) {
                         tag
                     } else {
                         defaultProxyTag
                     }
                }
            }

            // 处理入站限制
            val inboundTags = if (ruleSet.inbounds.isNullOrEmpty()) {
                null
            } else {
                // 将简化的 "tun", "mixed" 映射为实际的 inbound tag
                ruleSet.inbounds.map {
                    when(it) {
                        "tun" -> "tun-in"
                        "mixed" -> "mixed-in" // 假设有这个 inbound
                        else -> it
                    }
                }
            }

            rules.add(RouteRule(
                ruleSet = listOf(ruleSet.tag),
                outbound = outboundTag,
                inbound = inboundTags
            ))
            
        }

        return rules
    }

    /**
     * 构建应用分流路由规则
     */
    private fun buildAppRoutingRules(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>,
        nodeTagResolver: (String?) -> String?
    ): List<RouteRule> {
        val rules = mutableListOf<RouteRule>()

        fun resolveUidByPackageName(pkg: String): Int {
            return try {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                info.uid
            } catch (_: Exception) {
                0
            }
        }
        
        fun resolveOutboundTag(mode: RuleSetOutboundMode?, value: String?): String {
            return when (mode ?: RuleSetOutboundMode.DIRECT) {
                RuleSetOutboundMode.DIRECT -> "direct"
                RuleSetOutboundMode.BLOCK -> "block"
                RuleSetOutboundMode.PROXY -> defaultProxyTag
                RuleSetOutboundMode.NODE -> {
                    val resolvedTag = nodeTagResolver(value)
                    if (resolvedTag != null) resolvedTag else defaultProxyTag
                }
                RuleSetOutboundMode.PROFILE -> {
                    val profileId = value
                    val profileName = _profiles.value.find { it.id == profileId }?.name ?: "Profile_$profileId"
                    val tag = "P:$profileName"
                    if (outbounds.any { it.tag == tag }) tag else defaultProxyTag
                }
            }
        }

        // 1. 处理应用规则（单个应用）
        settings.appRules.filter { it.enabled }.forEach { rule ->
            val outboundTag = resolveOutboundTag(rule.outboundMode, rule.outboundValue)

            val uid = resolveUidByPackageName(rule.packageName)
            if (uid > 0) {
                rules.add(
                    RouteRule(
                        userId = listOf(uid),
                        outbound = outboundTag
                    )
                )
            }

            rules.add(
                RouteRule(
                    packageName = listOf(rule.packageName),
                    outbound = outboundTag
                )
            )
            
        }
        
        // 2. 处理应用分组
        settings.appGroups.filter { it.enabled }.forEach { group ->
            val outboundTag = resolveOutboundTag(group.outboundMode, group.outboundValue)
            
            // 将分组中的所有应用包名添加到一条规则中
            val packageNames = group.apps.map { it.packageName }
            if (packageNames.isNotEmpty()) {
                val uids = packageNames.map { resolveUidByPackageName(it) }.filter { it > 0 }.distinct()
                if (uids.isNotEmpty()) {
                    rules.add(
                        RouteRule(
                            userId = uids,
                            outbound = outboundTag
                        )
                    )
                }

                rules.add(
                    RouteRule(
                        packageName = packageNames,
                        outbound = outboundTag
                    )
                )
                
            }
        }

        return rules
    }

    private fun buildRunLogConfig(): LogConfig {
        return LogConfig(
            level = "warn",
            timestamp = true
        )
    }

    private fun buildRunExperimentalConfig(settings: AppSettings): ExperimentalConfig {
        // 使用 filesDir 而非 cacheDir，确保 FakeIP 缓存不会被系统清理
        val singboxDataDir = File(context.filesDir, "singbox_data").also { it.mkdirs() }

        // 启用 Clash API 提供额外的保活机制
        // 这会定期发送心跳，防止长连接应用（Telegram等）的TCP连接被NAT设备超时关闭
        // 自动查找可用端口，避免端口冲突导致启动失败
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


    private fun buildRunInbounds(settings: AppSettings): List<Inbound> =
        InboundBuilder.build(settings, getEffectiveTunStack(settings.tunStack))

    private fun buildRunDns(settings: AppSettings, validRuleSets: List<RuleSetConfig>): DnsConfig {
        // 添加 DNS 配置
        val dnsServers = mutableListOf<DnsServer>()
        val dnsRules = mutableListOf<DnsRule>()
        
        // 关键：代理节点服务器域名必须使用直连 DNS 解析，避免循环依赖
        // outbound: ["any"] 匹配所有 outbound 服务器的域名
        dnsRules.add(
            DnsRule(
                outboundRaw = listOf("any"),
                server = "dns-bootstrap"
            )
        )

        // 0. Bootstrap DNS (必须是 IP，用于解析其他 DoH/DoT 域名)
        // 使用多个 IP 以提高可靠性
        // 使用用户配置的服务器地址策略
        val bootstrapStrategy = mapDnsStrategy(settings.serverAddressStrategy) ?: "ipv4_only"
        dnsServers.add(
            DnsServer(
                tag = "dns-bootstrap",
                address = "223.5.5.5", // AliDNS IP
                detour = "direct",
                strategy = bootstrapStrategy
            )
        )
        dnsServers.add(
            DnsServer(
                tag = "dns-bootstrap-backup",
                address = "119.29.29.29", // DNSPod IP
                detour = "direct",
                strategy = bootstrapStrategy
            )
        )
        // 也可以使用一个多地址的 Server (如果内核支持)
        
        // 1. 本地 DNS
        val localDnsAddr = settings.localDns.takeIf { it.isNotBlank() } ?: "https://dns.alidns.com/dns-query"
        dnsServers.add(
            DnsServer(
                tag = "local",
                address = localDnsAddr,
                detour = "direct",
                strategy = mapDnsStrategy(settings.directDnsStrategy),
                addressResolver = "dns-bootstrap"
            )
        )

        // 2. 远程 DNS (走代理)
        val remoteDnsAddr = settings.remoteDns.takeIf { it.isNotBlank() } ?: "https://dns.google/dns-query"
        dnsServers.add(
            DnsServer(
                tag = "remote",
                address = remoteDnsAddr,
                detour = "PROXY",
                strategy = mapDnsStrategy(settings.remoteDnsStrategy),
                addressResolver = "dns-bootstrap" // 必须指定解析器
            )
        )

        if (settings.fakeDnsEnabled) {
            dnsServers.add(
                DnsServer(
                    tag = "fakeip-dns",
                    address = "fakeip"
                )
            )
        }

        // 3. 备用公共 DNS (直接连接，用于 bootstrap 和兜底)
        dnsServers.add(
            DnsServer(
                tag = "google-dns",
                address = "8.8.8.8",
                detour = "direct"
            )
        )
        dnsServers.add(
            DnsServer(
                tag = "cloudflare-dns",
                address = "1.1.1.1",
                detour = "direct"
            )
        )

        // 4. 备用国内 DNS
        dnsServers.add(
            DnsServer(
                tag = "dnspod",
                address = "119.29.29.29",
                detour = "direct",
                strategy = mapDnsStrategy(settings.directDnsStrategy)
            )
        )

        // 自定义域名规则的 DNS 处理（优先级最高，必须在规则集之前）
        val customDomainRulesForDns = settings.customRules.filter { it.enabled }.filter {
            it.type == RuleType.DOMAIN || it.type == RuleType.DOMAIN_SUFFIX || it.type == RuleType.DOMAIN_KEYWORD
        }
        Log.d(TAG, "buildRunDns: customDomainRulesForDns.size=${customDomainRulesForDns.size}, fakeDnsEnabled=${settings.fakeDnsEnabled}")

        if (customDomainRulesForDns.isNotEmpty()) {
            val proxyDomains = mutableListOf<String>()
            val proxySuffixes = mutableListOf<String>()
            val directDomains = mutableListOf<String>()
            val directSuffixes = mutableListOf<String>()

            customDomainRulesForDns.forEach { rule ->
                val values = rule.value.split("\n", "\r", ",", "，").map { it.trim() }.filter { it.isNotEmpty() }
                val isProxy = when (rule.outboundMode ?: when (rule.outbound) {
                    OutboundTag.DIRECT -> RuleSetOutboundMode.DIRECT
                    OutboundTag.BLOCK -> RuleSetOutboundMode.BLOCK
                    OutboundTag.PROXY -> RuleSetOutboundMode.PROXY
                }) {
                    RuleSetOutboundMode.DIRECT, RuleSetOutboundMode.BLOCK -> false
                    else -> true
                }
                Log.d(TAG, "DNS rule: type=${rule.type}, value=${rule.value}, outboundMode=${rule.outboundMode}, isProxy=$isProxy")

                when (rule.type) {
                    RuleType.DOMAIN -> if (isProxy) proxyDomains.addAll(values) else directDomains.addAll(values)
                    RuleType.DOMAIN_SUFFIX -> if (isProxy) proxySuffixes.addAll(values) else directSuffixes.addAll(values)
                    RuleType.DOMAIN_KEYWORD -> if (isProxy) proxySuffixes.addAll(values) else directSuffixes.addAll(values)
                    else -> {}
                }
            }

            val proxyServerTag = if (settings.fakeDnsEnabled) "fakeip-dns" else "remote"
            Log.d(TAG, "DNS custom domains: proxyDomains=$proxyDomains, proxySuffixes=$proxySuffixes, directDomains=$directDomains, directSuffixes=$directSuffixes, server=$proxyServerTag")
            if (proxyDomains.isNotEmpty()) {
                dnsRules.add(DnsRule(domain = proxyDomains.distinct(), server = proxyServerTag))
            }
            if (proxySuffixes.isNotEmpty()) {
                dnsRules.add(DnsRule(domainSuffix = proxySuffixes.distinct(), server = proxyServerTag))
            }
            if (directDomains.isNotEmpty()) {
                dnsRules.add(DnsRule(domain = directDomains.distinct(), server = "local"))
            }
            if (directSuffixes.isNotEmpty()) {
                dnsRules.add(DnsRule(domainSuffix = directSuffixes.distinct(), server = "local"))
            }
        }

        // 规则集的 DNS 处理（在自定义规则之后）
        val proxyRuleSets = mutableListOf<String>()
        val possibleProxyTags = listOf(
            "geosite-geolocation-!cn", "geosite-google", "geosite-openai", 
            "geosite-youtube", "geosite-telegram", "geosite-github", 
            "geosite-twitter", "geosite-netflix", "geosite-apple",
            "geosite-facebook", "geosite-instagram", "geosite-tiktok",
            "geosite-disney", "geosite-microsoft", "geosite-amazon"
        )
        possibleProxyTags.forEach { tag ->
            if (validRuleSets.any { it.tag == tag }) proxyRuleSets.add(tag)
        }

        if (proxyRuleSets.isNotEmpty()) {
            if (settings.fakeDnsEnabled) {
                dnsRules.add(
                    DnsRule(
                        ruleSet = proxyRuleSets,
                        server = "fakeip-dns"
                    )
                )
            } else {
                dnsRules.add(
                    DnsRule(
                        ruleSet = proxyRuleSets,
                        server = "remote"
                    )
                )
            }
        }

        val directRuleSets = mutableListOf<String>()
        if (validRuleSets.any { it.tag == "geosite-cn" }) directRuleSets.add("geosite-cn")
        
        if (directRuleSets.isNotEmpty()) {
            dnsRules.add(
                DnsRule(
                    ruleSet = directRuleSets,
                    server = "local"
                )
            )
        }
        
        // 5. 应用特定 DNS 规则（确保应用分流的应用 DNS 走正确的服务器）
        val appPackagesForDns = (settings.appRules.filter { it.enabled }.map { it.packageName } +
                settings.appGroups.filter { it.enabled }.flatMap { it.apps.map { it.packageName } }).distinct()
        
        if (appPackagesForDns.isNotEmpty()) {
            val serverTag = if (settings.fakeDnsEnabled) "fakeip-dns" else "remote"
            val uids = appPackagesForDns.map {
                try {
                    context.packageManager.getApplicationInfo(it, 0).uid
                } catch (_: Exception) {
                    0
                }
            }.filter { it > 0 }.distinct()

            if (uids.isNotEmpty()) {
                dnsRules.add(
                    0,
                    DnsRule(
                        userId = uids,
                        server = serverTag
                    )
                )
            }

            dnsRules.add(
                if (uids.isNotEmpty()) 1 else 0,
                DnsRule(
                    packageName = appPackagesForDns,
                    server = serverTag
                )
            )
        }
        
        // Fake DNS 兜底
        if (settings.fakeDnsEnabled) {
            dnsRules.add(
                DnsRule(
                    queryType = listOf("A", "AAAA"),
                    server = "fakeip-dns"
                )
            )
        }

        val fakeIpConfig = if (settings.fakeDnsEnabled) {
            // 解析用户配置的 fakeIpRange，支持同时指定 IPv4 和 IPv6 范围
            // 格式: "198.18.0.0/15" 或 "198.18.0.0/15,fc00::/18"
            val fakeIpRanges = settings.fakeIpRange.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val inet4Range = fakeIpRanges.firstOrNull { it.contains(".") } ?: "198.18.0.0/15"
            val inet6Range = fakeIpRanges.firstOrNull { it.contains(":") } ?: "fc00::/18"
            
            DnsFakeIpConfig(
                enabled = true,
                inet4Range = inet4Range,
                inet6Range = inet6Range
            )
        } else {
            null
        }

        return DnsConfig(
            servers = dnsServers,
            rules = dnsRules,
            finalServer = "local", // 兜底使用本地 DNS
            strategy = mapDnsStrategy(settings.dnsStrategy),
            disableCache = !settings.dnsCacheEnabled,
            independentCache = true,
            fakeip = fakeIpConfig
        )
    }

    private data class RunOutboundsContext(
        val outbounds: List<Outbound>,
        val selectorTag: String,
        val nodeTagResolver: (String?) -> String?,
        val nodeTagMap: Map<String, String>
    )

    private fun buildRunOutbounds(
        baseConfig: SingBoxConfig,
        activeNode: NodeUi?,
        settings: AppSettings,
        allNodes: List<NodeUi>
    ): RunOutboundsContext {
        val rawOutbounds = baseConfig.outbounds
        if (rawOutbounds.isNullOrEmpty()) {
            Log.w(TAG, "No outbounds found in base config, adding defaults")
        }

        val fixedOutbounds = rawOutbounds?.map { outbound ->
            buildOutboundForRuntime(outbound)
        }?.toMutableList() ?: mutableListOf()

        if (fixedOutbounds.none { it.tag == "direct" }) {
            fixedOutbounds.add(Outbound(type = "direct", tag = "direct"))
        }
        if (fixedOutbounds.none { it.tag == "block" }) {
            fixedOutbounds.add(Outbound(type = "block", tag = "block"))
        }
        if (fixedOutbounds.none { it.tag == "dns-out" }) {
            fixedOutbounds.add(Outbound(type = "dns", tag = "dns-out"))
        }

        // --- 处理跨配置节点引用 ---
        val activeProfileId = _activeProfileId.value
        val requiredNodeIds = mutableSetOf<String>()
        val requiredProfileIds = mutableSetOf<String>()

        fun resolveNodeRefToId(value: String?): String? {
            if (value.isNullOrBlank()) return null
            val parts = value.split("::", limit = 2)
            if (parts.size == 2) {
                val profileId = parts[0]
                val nodeName = parts[1]
                return allNodes.firstOrNull { it.sourceProfileId == profileId && it.name == nodeName }?.id
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

        // 收集所有规则中引用的节点 ID 和配置 ID
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
        // 收集自定义域名规则中引用的节点
        settings.customRules.filter { it.enabled }.forEach { rule ->
            when (rule.outboundMode) {
                RuleSetOutboundMode.NODE -> resolveNodeRefToId(rule.outboundValue)?.let { requiredNodeIds.add(it) }
                RuleSetOutboundMode.PROFILE -> rule.outboundValue?.let { requiredProfileIds.add(it) }
                else -> {}
            }
        }

        // 确保当前选中的节点始终可用
        activeNode?.let { requiredNodeIds.add(it.id) }

        // 将所需配置中的所有节点 ID 也加入到 requiredNodeIds
        requiredProfileIds.forEach { profileId ->
            allNodes.filter { it.sourceProfileId == profileId }.forEach { node ->
                requiredNodeIds.add(node.id)
            }
        }

        // 建立 NodeID -> OutboundTag 的映射
        val nodeTagMap = mutableMapOf<String, String>()
        val existingTags = fixedOutbounds.map { it.tag }.toMutableSet()
        
        // 2025-fix: 调试日志，帮助定位节点映射问题
        Log.d(TAG, "buildRunOutbounds: activeProfileId=$activeProfileId, existingTags count=${existingTags.size}")
        Log.d(TAG, "  existingTags (first 10): ${existingTags.take(10)}")

        // 1. 先映射当前配置中的节点
        if (activeProfileId != null) {
            val profileNodes = allNodes.filter { it.sourceProfileId == activeProfileId }
            Log.d(TAG, "  profileNodes count=${profileNodes.size}")
            profileNodes.forEach { node ->
                if (existingTags.contains(node.name)) {
                    nodeTagMap[node.id] = node.name
                } else {
                    // 2025-fix: 尝试模糊匹配
                    val fuzzyMatch = existingTags.find { it.equals(node.name, ignoreCase = true) }
                    if (fuzzyMatch != null) {
                        nodeTagMap[node.id] = fuzzyMatch
                        Log.w(TAG, "  Fuzzy matched node '${node.name}' to tag '$fuzzyMatch'")
                    } else {
                        Log.w(TAG, "  ⚠️ Node '${node.name}' (id=${node.id.take(8)}) not found in existingTags!")
                    }
                }
            }
        }

        // 2. 处理需要引入的外部节点
        requiredNodeIds.forEach { nodeId ->
            if (nodeTagMap.containsKey(nodeId)) return@forEach // 已经在当前配置中

            val node = allNodes.find { it.id == nodeId }
            if (node == null) {
                Log.w(TAG, "Cross-profile node not found in allNodes: nodeId=$nodeId")
                return@forEach
            }
            val sourceProfileId = node.sourceProfileId

            // 如果是当前配置但没找到tag(可能改名了?), 跳过
            if (sourceProfileId == activeProfileId) {
                Log.w(TAG, "Cross-profile node belongs to activeProfile but not in outbounds: ${node.name}")
                return@forEach
            }

            // 加载外部配置
            val sourceConfig = loadConfig(sourceProfileId)
            if (sourceConfig == null) {
                Log.e(TAG, "Failed to load source config for cross-profile node: profileId=$sourceProfileId, nodeName=${node.name}")
                return@forEach
            }
            
            // 尝试多种方式匹配 outbound
            val sourceOutbound = sourceConfig.outbounds?.find { it.tag == node.name }
                ?: sourceConfig.outbounds?.find { it.tag.equals(node.name, ignoreCase = true) }
                ?: sourceConfig.outbounds?.find {
                    // 尝试模糊匹配：去除空格和特殊字符后比较
                    it.tag.replace(REGEX_WHITESPACE_DASH, "").equals(
                        node.name.replace(REGEX_WHITESPACE_DASH, ""),
                        ignoreCase = true
                    )
                }
            
            if (sourceOutbound == null) {
                Log.e(TAG, "Cross-profile outbound not found: nodeName=${node.name}, profileId=$sourceProfileId, available tags: ${sourceConfig.outbounds?.map { it.tag }?.take(10)}")
                return@forEach
            }

            // 运行时修复
            var fixedSourceOutbound = buildOutboundForRuntime(sourceOutbound)

            // 处理标签冲突
            var finalTag = fixedSourceOutbound.tag
            if (existingTags.contains(finalTag)) {
                // 冲突，生成新标签: Name_ProfileSuffix
                val suffix = sourceProfileId.take(4)
                finalTag = "${finalTag}_$suffix"
                // 如果还冲突 (极小概率), 再加随机
                if (existingTags.contains(finalTag)) {
                    finalTag = "${finalTag}_${java.util.UUID.randomUUID().toString().take(4)}"
                }
                fixedSourceOutbound = fixedSourceOutbound.copy(tag = finalTag)
            }

            // 添加到 outbounds
            fixedOutbounds.add(fixedSourceOutbound)
            existingTags.add(finalTag)
            nodeTagMap[nodeId] = finalTag

        }

        // 3. 处理需要的配置 (Create Profile selectors)
        requiredProfileIds.forEach { profileId ->
            val profileNodes = allNodes.filter { it.sourceProfileId == profileId }
            val nodeTags = profileNodes.mapNotNull { nodeTagMap[it.id] }
            val profileName = _profiles.value.find { it.id == profileId }?.name ?: "Profile_$profileId"
            val tag = "P:$profileName" // 使用 P: 前缀区分配置选择器

            if (nodeTags.isNotEmpty()) {
                val existingIndex = fixedOutbounds.indexOfFirst { it.tag == tag }
                if (existingIndex < 0) {
                    val newSelector = Outbound(
                        type = "selector",
                        tag = tag,
                        outbounds = nodeTags.distinct(),
                        default = nodeTags.firstOrNull(),
                        interruptExistConnections = false
                    )
                    fixedOutbounds.add(0, newSelector)
                }
            }
        }

        // 收集所有代理节点名称 (包括新添加的外部节点)
        // 2025-fix: 扩展支持的协议列表，防止 wireguard/ssh/shadowtls/http/socks 等被排除在 PROXY 组之外
        val proxyTags = fixedOutbounds.filter {
            it.type in listOf(
                "vless", "vmess", "trojan", "shadowsocks",
                "hysteria2", "hysteria", "anytls", "tuic",
                "wireguard", "ssh", "shadowtls", "http", "socks"
            )
        }.map { it.tag }.toMutableList()

        // 创建一个主 Selector
        val selectorTag = "PROXY"

        // 确保代理列表不为空，否则 Selector/URLTest 会崩溃
        if (proxyTags.isEmpty()) {
            proxyTags.add("direct")
        }

        val selectorDefault = activeNode
            ?.let { nodeTagMap[it.id] ?: it.name }
            ?.takeIf { it in proxyTags }
            ?: proxyTags.firstOrNull()
        
        // 2025-fix: 调试日志，帮助定位 selector default 设置问题
        if (activeNode != null) {
            val mappedTag = nodeTagMap[activeNode.id]
            Log.d(TAG, "Selector default: activeNode=${activeNode.name}, id=${activeNode.id}, mappedTag=$mappedTag, selectorDefault=$selectorDefault, inProxyTags=${selectorDefault in proxyTags}")
            if (mappedTag == null && activeNode.name !in proxyTags) {
                Log.w(TAG, "⚠️ Active node not in nodeTagMap and name not in proxyTags! Node may not be selected correctly.")
                Log.w(TAG, "  Available proxyTags (first 10): ${proxyTags.take(10)}")
                Log.w(TAG, "  nodeTagMap keys (first 10): ${nodeTagMap.keys.take(10)}")
            }
        }

        val selectorOutbound = Outbound(
            type = "selector",
            tag = selectorTag,
            outbounds = proxyTags,
            default = selectorDefault, // 设置默认选中项（确保存在于 outbounds 中）
            interruptExistConnections = true // 切换节点时断开现有连接，确保立即生效
        )

        // 避免重复 tag：订阅配置通常已自带 PROXY selector
        // 若已存在同 tag outbound，直接替换（并删除多余重复项）
        val existingProxyIndexes = fixedOutbounds.withIndex()
            .filter { it.value.tag == selectorTag }
            .map { it.index }
        if (existingProxyIndexes.isNotEmpty()) {
            existingProxyIndexes.asReversed().forEach { idx ->
                fixedOutbounds.removeAt(idx)
            }
        }

        // 将 Selector 添加到 outbounds 列表的最前面（或者合适的位置）
        fixedOutbounds.add(0, selectorOutbound)


        // 定义节点标签解析器
        val nodeTagResolver: (String?) -> String? = { value ->
            if (value.isNullOrBlank()) {
                null
            } else {
                nodeTagMap[value]
                    ?: resolveNodeRefToId(value)?.let { nodeTagMap[it] }
                    ?: if (fixedOutbounds.any { it.tag == value }) value else null
            }
        }

        // Final safety check: Filter out non-existent references in Selector/URLTest
        val allOutboundTags = fixedOutbounds.map { it.tag }.toSet()
        val safeOutbounds = fixedOutbounds.map { outbound ->
            if (outbound.type == "selector" || outbound.type == "urltest" || outbound.type == "url-test") {
                val validRefs = outbound.outbounds?.filter { allOutboundTags.contains(it) } ?: emptyList()
                val safeRefs = if (validRefs.isEmpty()) listOf("direct") else validRefs

                if (safeRefs.size != (outbound.outbounds?.size ?: 0)) {
                    Log.w(TAG, "Filtered invalid refs in ${outbound.tag}: ${outbound.outbounds} -> $safeRefs")
                }
                
                // Ensure default is valid
                val currentDefault = outbound.default
                val safeDefault = if (currentDefault != null && safeRefs.contains(currentDefault)) {
                    currentDefault
                } else {
                    safeRefs.firstOrNull()
                }
                
                outbound.copy(outbounds = safeRefs, default = safeDefault)
            } else {
                outbound
            }
        }

        return RunOutboundsContext(
            outbounds = safeOutbounds,
            selectorTag = selectorTag,
            nodeTagResolver = nodeTagResolver,
            nodeTagMap = nodeTagMap
        )
    }

    private fun buildRunRoute(
        settings: AppSettings,
        selectorTag: String,
        outbounds: List<Outbound>,
        nodeTagResolver: (String?) -> String?,
        validRuleSets: List<RuleSetConfig>
    ): RouteConfig {
        // 构建应用分流规则
        val appRoutingRules = buildAppRoutingRules(settings, selectorTag, outbounds, nodeTagResolver)

        // 构建自定义规则集路由规则（只针对有效的规则集）
        val customRuleSetRules = buildCustomRuleSetRules(settings, selectorTag, outbounds, nodeTagResolver, validRuleSets)

        val quicRule = if (settings.blockQuic) {
            listOf(RouteRule(protocolRaw = listOf("quic"), outbound = "block"))
        } else {
            emptyList()
        }

        // 局域网绕过规则
        val bypassLanRules = if (settings.bypassLan) {
            listOf(
                RouteRule(
                    ipCidr = listOf(
                        "10.0.0.0/8",
                        "172.16.0.0/12",
                        "192.168.0.0/16",
                        "fc00::/7",
                        "127.0.0.0/8",
                        "::1/128"
                    ),
                    outbound = "direct"
                )
            )
        } else {
            emptyList()
        }

        val adBlockEnabled = settings.blockAds && validRuleSets.any { it.tag == "geosite-category-ads-all" }
        val adBlockRules = if (adBlockEnabled) {
            listOf(RouteRule(ruleSet = listOf("geosite-category-ads-all"), outbound = "block"))
        } else {
            emptyList()
        }

        val customDomainRules = buildCustomDomainRules(settings, selectorTag, outbounds, nodeTagResolver)

        val defaultRuleCatchAll = when (settings.defaultRule) {
            DefaultRule.DIRECT -> listOf(RouteRule(outbound = "direct"))
            DefaultRule.BLOCK -> listOf(RouteRule(outbound = "block"))
            DefaultRule.PROXY -> listOf(RouteRule(outbound = selectorTag))
        }

        // sing-box 1.10+ 需要显式添加 sniff action 规则来启用协议嗅探
        // 这是域名分流生效的关键：通过嗅探 TLS/HTTP 协议获取原始域名
        val sniffRule = listOf(RouteRule(action = "sniff"))
        
        // DNS 劫持规则：将 DNS 流量重定向到 sing-box DNS 模块处理
        val hijackDnsRule = listOf(RouteRule(protocolRaw = listOf("dns"), action = "hijack-dns"))

        val allRules = when (settings.routingMode) {
            RoutingMode.GLOBAL_PROXY -> sniffRule + hijackDnsRule + adBlockRules
            RoutingMode.GLOBAL_DIRECT -> sniffRule + hijackDnsRule + adBlockRules + listOf(RouteRule(outbound = "direct"))
            RoutingMode.RULE -> {
                sniffRule + hijackDnsRule + adBlockRules + quicRule + bypassLanRules + customDomainRules + appRoutingRules + customRuleSetRules + defaultRuleCatchAll
            }
        }

        // 记录所有生成的路由规则
        allRules.forEachIndexed { index, rule ->
            val ruleDesc = buildString {
                rule.protocolRaw?.let { append("protocol=$it ") }
                rule.ruleSet?.let { append("ruleSet=$it ") }
                rule.packageName?.let { append("pkg=$it ") }
                rule.domain?.let { append("domain=$it ") }
                rule.inbound?.let { append("inbound=$it ") }
                append("-> ${rule.outbound}")
            }
        }

        return RouteConfig(
            ruleSet = validRuleSets,
            rules = allRules,
            finalOutbound = selectorTag, // 路由指向 Selector
            findProcess = true,
            autoDetectInterface = true
        )
    }
    
    /**
     * 获取当前活跃配置的原始 JSON
     */
    fun getActiveConfig(): SingBoxConfig? {
        val id = _activeProfileId.value ?: return null
        return loadConfig(id)
    }
    
    /**
     * 获取指定配置的原始 JSON
     */
    fun getConfig(profileId: String): SingBoxConfig? {
        return loadConfig(profileId)
    }
    
    private fun mapDnsStrategy(strategy: DnsStrategy): String? {
        return when (strategy) {
            DnsStrategy.AUTO -> null
            DnsStrategy.PREFER_IPV4 -> "prefer_ipv4"
            DnsStrategy.PREFER_IPV6 -> "prefer_ipv6"
            DnsStrategy.ONLY_IPV4 -> "ipv4_only"
            DnsStrategy.ONLY_IPV6 -> "ipv6_only"
        }
    }

    /**
     * 根据设置中的 IP 地址解析并修复 Outbound
     */
    fun getOutboundByNodeId(nodeId: String): Outbound? {
        val node = _nodes.value.find { it.id == nodeId } ?: return null
        val config = loadConfig(node.sourceProfileId) ?: return null
        return config.outbounds?.find { it.tag == node.name }
    }
    
    /**
     * 根据节点ID获取NodeUi
     * 优先从当前配置的节点中查找，如果找不到则从所有已加载的配置中查找
     */
    fun getNodeById(nodeId: String): NodeUi? {
        // 首先在当前配置的节点中查找
        _nodes.value.find { it.id == nodeId }?.let { return it }

        // 如果当前配置中没有，尝试从所有已加载的配置中查找
        // 这样可以确保即使配置切换时也能正确显示节点名称
        for ((_, nodes) in profileNodes) {
            nodes.find { it.id == nodeId }?.let { return it }
        }

        // 最后尝试从 allNodes 中查找（如果已加载）
        _allNodes.value.find { it.id == nodeId }?.let { return it }

        return null
    }
    
    /**
     * 删除节点
     */
    /**
     * 手动创建节点（从空白 Outbound 创建）
     * @param outbound 要创建的节点
     * @param targetProfileId 目标配置ID（如指定则添加到该配置）
     * @param newProfileName 新配置名称（如指定则创建新配置并添加）
     */
    fun createNode(
        outbound: Outbound,
        targetProfileId: String? = null,
        newProfileName: String? = null
    ) {
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
                    }
                }
                newProfileName != null -> {
                    profileId = UUID.randomUUID().toString()
                    existingConfig = null
                    finalProfileName = newProfileName
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
                    }
                    finalProfileName = manualProfileName
                }
            }

            // 2. 合并或创建 outbounds
            val newOutbounds = mutableListOf<Outbound>()
            existingConfig?.outbounds?.let { existing ->
                newOutbounds.addAll(existing.filter { it.type !in listOf("direct", "block", "dns") })
            }

            // 检查是否有同名节点，如有则添加后缀
            var finalTag = outbound.tag
            var counter = 1
            while (newOutbounds.any { it.tag == finalTag }) {
                finalTag = "${outbound.tag}_$counter"
                counter++
            }
            val finalOutbound = if (finalTag != outbound.tag) outbound.copy(tag = finalTag) else outbound
            newOutbounds.add(finalOutbound)

            // 添加系统 outbounds
            if (newOutbounds.none { it.tag == "direct" }) {
                newOutbounds.add(Outbound(type = "direct", tag = "direct"))
            }
            if (newOutbounds.none { it.tag == "block" }) {
                newOutbounds.add(Outbound(type = "block", tag = "block"))
            }
            if (newOutbounds.none { it.tag == "dns-out" }) {
                newOutbounds.add(Outbound(type = "dns", tag = "dns-out"))
            }

            val newConfig = deduplicateTags(SingBoxConfig(outbounds = newOutbounds))

            val configFile = File(configDir, "$profileId.json")
            configFile.writeText(gson.toJson(newConfig))

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

            setActiveProfile(profileId)

            // 优化: 使用协程提取节点，避免 runBlocking 阻塞
            // 将节点提取和后续处理放在协程中异步执行
            scope.launch {
                val nodes = extractNodesFromConfig(newConfig, profileId)
                profileNodes[profileId] = nodes

                // 更新 UI 状态
                if (_activeProfileId.value == profileId) {
                    _nodes.value = nodes
                }
                updateAllNodesAndGroups()

                // 选中新节点
                val addedNode = nodes.find { it.name == finalTag }
                if (addedNode != null) {
                    _activeNodeId.value = addedNode.id
                }

                saveProfiles()
                Log.i(TAG, "Created node: $finalTag in profile $profileId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create node", e)
        }
    }

    fun deleteNode(nodeId: String) {
        val node = _nodes.value.find { it.id == nodeId } ?: return
        val profileId = node.sourceProfileId
        val config = loadConfig(profileId) ?: return

        // 过滤掉要删除的节点
        val newOutbounds = config.outbounds?.filter { it.tag != node.name }
        val newConfig = config.copy(outbounds = newOutbounds)

        // 更新内存中的配置
        cacheConfig(profileId, newConfig)

        // 保存文件
        try {
            val configFile = File(configDir, "$profileId.json")
            configFile.writeText(gson.toJson(newConfig))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 优化: 使用协程提取节点，避免 runBlocking 阻塞
        scope.launch {
            val newNodes = extractNodesFromConfig(newConfig, profileId)
            profileNodes[profileId] = newNodes
            updateAllNodesAndGroups()

            // 如果是当前活跃配置，更新UI状态
            if (_activeProfileId.value == profileId) {
                _nodes.value = newNodes

                // 如果删除的是当前选中节点，重置选中
                if (_activeNodeId.value == nodeId) {
                    _activeNodeId.value = newNodes.firstOrNull()?.id
                }
            }

            saveProfiles()
        }
    }

    /**
     * 添加单个节点到指定配置
     *
     * @param link 节点链接（vmess://, vless://, ss://, etc）
     * @param targetProfileId 目标配置ID，null则创建新配置或使用默认配置
     * @param newProfileName 新配置名称，当targetProfileId为null时使用
     * @return 成功返回添加的节点，失败返回错误信息
     */
    suspend fun addSingleNode(
        link: String,
        targetProfileId: String? = null,
        newProfileName: String? = null
    ): Result<NodeUi> = withContext(Dispatchers.IO) {
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
            if (newOutbounds.none { it.tag == "block" }) {
                newOutbounds.add(Outbound(type = "block", tag = "block"))
            }
            if (newOutbounds.none { it.tag == "dns-out" }) {
                newOutbounds.add(Outbound(type = "dns", tag = "dns-out"))
            }
            
            val newConfig = deduplicateTags(SingBoxConfig(outbounds = newOutbounds))
            
            val configFile = File(configDir, "$profileId.json")
            configFile.writeText(gson.toJson(newConfig))
            
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
            
            updateAllNodesAndGroups()
            
            setActiveProfile(profileId)
            val addedNode = nodes.find { it.name == finalTag }
            if (addedNode != null) {
                _activeNodeId.value = addedNode.id
            }
            
            saveProfiles()
            
            Log.i(TAG, "Added single node: $finalTag to profile $profileId")
            
            Result.success(addedNode ?: nodes.last())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add single node", e)
            Result.failure(Exception(context.getString(R.string.nodes_add_failed) + ": ${e.message}"))
        }
    }

    /**
     * 重命名节点
     */
    fun renameNode(nodeId: String, newName: String) {
        val node = _nodes.value.find { it.id == nodeId } ?: return
        val profileId = node.sourceProfileId
        val config = loadConfig(profileId) ?: return

        // 更新对应节点的 tag
        val newOutbounds = config.outbounds?.map {
            if (it.tag == node.name) it.copy(tag = newName) else it
        }
        var newConfig = config.copy(outbounds = newOutbounds)
        newConfig = deduplicateTags(newConfig)

        // 更新内存中的配置
        cacheConfig(profileId, newConfig)

        // 保存文件
        try {
            val configFile = File(configDir, "$profileId.json")
            configFile.writeText(gson.toJson(newConfig))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val oldNodes = profileNodes[profileId] ?: _nodes.value
        val latencyById = oldNodes.associate { it.id to it.latencyMs }
        val updatedNodeId = stableNodeId(profileId, newName)
        val originalLatency = oldNodes.find { it.id == nodeId }?.latencyMs

        // 优化: 使用协程提取节点，避免 runBlocking 阻塞
        scope.launch {
            val newNodes = extractNodesFromConfig(newConfig, profileId)
            val mergedNodes = newNodes.map { nodeItem ->
                val storedLatency = latencyById[nodeItem.id]
                    ?: if (nodeItem.id == updatedNodeId) originalLatency else null
                if (storedLatency != null) nodeItem.copy(latencyMs = storedLatency) else nodeItem
            }
            profileNodes[profileId] = mergedNodes
            updateAllNodesAndGroups()

            // 如果是当前活跃配置，更新UI状态
            if (_activeProfileId.value == profileId) {
                _nodes.value = mergedNodes

                // 如果重命名的是当前选中节点，更新 activeNodeId
                if (_activeNodeId.value == nodeId) {
                    val newNode = mergedNodes.find { it.name == newName }
                    if (newNode != null) {
                        _activeNodeId.value = newNode.id
                    }
                }
            }

            saveProfiles()
        }
    }

    /**
     * 更新节点配置
     */
    fun updateNode(nodeId: String, newOutbound: Outbound) {
        val node = _nodes.value.find { it.id == nodeId } ?: return
        val profileId = node.sourceProfileId
        val config = loadConfig(profileId) ?: return

        // 更新对应节点
        // 注意：这里假设 newOutbound.tag 已经包含了可能的新名称
        val newOutbounds = config.outbounds?.map {
            if (it.tag == node.name) newOutbound else it
        }
        var newConfig = config.copy(outbounds = newOutbounds)
        newConfig = deduplicateTags(newConfig)

        // 更新内存中的配置
        cacheConfig(profileId, newConfig)

        // 先保存文件（同步操作，确保数据持久化）
        try {
            val configFile = File(configDir, "$profileId.json")
            configFile.writeText(gson.toJson(newConfig))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 保存延迟数据供协程使用
        val oldNodes = profileNodes[profileId] ?: _nodes.value
        val latencyById = oldNodes.associate { it.id to it.latencyMs }
        val updatedNodeId = stableNodeId(profileId, newOutbound.tag)
        val originalLatency = oldNodes.find { it.id == nodeId }?.latencyMs
        val isActiveProfile = _activeProfileId.value == profileId
        val isActiveNode = _activeNodeId.value == nodeId
        val newTag = newOutbound.tag

        // 异步提取节点列表和更新 UI
        scope.launch {
            val newNodes = extractNodesFromConfig(newConfig, profileId)
            val mergedNodes = newNodes.map { nodeItem ->
                val storedLatency = latencyById[nodeItem.id]
                    ?: if (nodeItem.id == updatedNodeId) originalLatency else null
                if (storedLatency != null) nodeItem.copy(latencyMs = storedLatency) else nodeItem
            }
            profileNodes[profileId] = mergedNodes
            updateAllNodesAndGroups()

            // 如果是当前活跃配置，更新UI状态
            if (isActiveProfile) {
                _nodes.value = mergedNodes

                // 如果更新的是当前选中节点，尝试恢复选中状态
                if (isActiveNode) {
                    val newNode = mergedNodes.find { it.name == newTag }
                    if (newNode != null) {
                        _activeNodeId.value = newNode.id
                    }
                }
            }

            saveProfiles()
        }
    }

    /**
     * 导出节点链接
     */
    fun exportNode(nodeId: String): String? {
        val node = _nodes.value.find { it.id == nodeId } ?: run {
            Log.e(TAG, "exportNode: Node not found in UI list: $nodeId")
            return null
        }

        val config = loadConfig(node.sourceProfileId) ?: run {
            Log.e(TAG, "exportNode: Config not found for profile: ${node.sourceProfileId}")
            return null
        }

        val outbound = config.outbounds?.find { it.tag == node.name } ?: run {
            Log.e(TAG, "exportNode: Outbound not found in config with tag: ${node.name}")
            return null
        }


        val link = when (outbound.type) {
            "vless" -> generateVLessLink(outbound)
            "vmess" -> generateVMessLink(outbound)
            "shadowsocks" -> generateShadowsocksLink(outbound)
            "trojan" -> generateTrojanLink(outbound)
            "hysteria2" -> {
                val hy2 = generateHysteria2Link(outbound)
                hy2
            }
            "hysteria" -> generateHysteriaLink(outbound)
            "anytls" -> generateAnyTLSLink(outbound)
            "tuic" -> generateTuicLink(outbound)
            else -> {
                Log.e(TAG, "exportNode: Unsupported type ${outbound.type}")
                null
            }
        }

        return link?.takeIf { it.isNotBlank() }
    }

    private fun encodeUrlComponent(value: String): String {
        return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun formatServerHost(server: String): String {
        val s = server.trim()
        return if (s.contains(":") && !s.startsWith("[") && !s.endsWith("]")) {
            "[$s]"
        } else {
            s
        }
    }

    private fun buildOptionalQuery(params: List<String>): String {
        val query = params.filter { it.isNotBlank() }.joinToString("&")
        return if (query.isNotEmpty()) "?$query" else ""
    }

    private fun generateVLessLink(outbound: Outbound): String {
        val uuid = outbound.uuid ?: return ""
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 443
        val params = mutableListOf<String>()
        
        params.add("type=${outbound.transport?.type ?: "tcp"}")
        params.add("encryption=none")
        
        outbound.flow?.let { params.add("flow=$it") }
        
        if (outbound.tls?.enabled == true) {
            if (outbound.tls.reality?.enabled == true) {
                 params.add("security=reality")
                 outbound.tls.reality.publicKey?.let { params.add("pbk=${encodeUrlComponent(it)}") }
                 outbound.tls.reality.shortId?.let { params.add("sid=${encodeUrlComponent(it)}") }
                 outbound.tls.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
            } else {
                 params.add("security=tls")
                 outbound.tls.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
            }
            outbound.tls.utls?.fingerprint?.let { params.add("fp=${encodeUrlComponent(it)}") }
            if (outbound.tls.insecure == true) {
                params.add("allowInsecure=1")
            }
            outbound.tls.alpn?.let { 
                if (it.isNotEmpty()) params.add("alpn=${encodeUrlComponent(it.joinToString(","))}") 
            }
        } else {
             // params.add("security=none") // default is none
        }
        
        outbound.packetEncoding?.let { params.add("packetEncoding=$it") }
        
        // Transport specific
        when (outbound.transport?.type) {
            "ws" -> {
                val host = outbound.transport.headers?.get("Host") 
                    ?: outbound.transport.host?.firstOrNull()
                host?.let { params.add("host=${encodeUrlComponent(it)}") }
                
                var path = outbound.transport.path ?: "/"
                // Handle early data (ed)
                outbound.transport.maxEarlyData?.let { ed ->
                    if (ed != 0) { // Only add if not 0, though usually it's 2048 or something
                        val separator = if (path.contains("?")) "&" else "?"
                        path = "$path${separator}ed=$ed"
                    }
                }
                
                params.add("path=${encodeUrlComponent(path)}") 
            }
            "grpc" -> {
                outbound.transport.serviceName?.let { 
                    params.add("serviceName=${encodeUrlComponent(it)}") 
                }
                params.add("mode=gun")
            }
            "http", "h2" -> {
                 outbound.transport.path?.let { params.add("path=${encodeUrlComponent(it)}") }
                 outbound.transport.host?.firstOrNull()?.let { params.add("host=${encodeUrlComponent(it)}") }
            }
        }

        val name = encodeUrlComponent(outbound.tag)
        val queryPart = buildOptionalQuery(params)
        return "vless://$uuid@$server:$port${queryPart}#$name"
    }

    private fun generateVMessLink(outbound: Outbound): String {
        // Simple implementation for VMess
        try {
            val json = VMessLinkConfig(
                v = "2",
                ps = outbound.tag,
                add = outbound.server,
                port = outbound.serverPort?.toString(),
                id = outbound.uuid,
                aid = "0", // sing-box 只支持 alterId=0
                scy = outbound.security,
                net = outbound.transport?.type ?: "tcp",
                type = "none",
                host = outbound.transport?.headers?.get("Host") ?: outbound.transport?.host?.firstOrNull() ?: "",
                path = outbound.transport?.path ?: "",
                tls = if (outbound.tls?.enabled == true) "tls" else "",
                sni = outbound.tls?.serverName ?: "",
                alpn = outbound.tls?.alpn?.joinToString(","),
                fp = outbound.tls?.utls?.fingerprint
            )
            val jsonStr = gson.toJson(json)
            val base64 = Base64.encodeToString(jsonStr.toByteArray(), Base64.NO_WRAP)
            return "vmess://$base64"
        } catch (e: Exception) {
            return ""
        }
    }

    private fun generateShadowsocksLink(outbound: Outbound): String {
        val method = outbound.method ?: return ""
        val password = outbound.password ?: return ""
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: return ""
        val userInfo = "$method:$password"
        val encodedUserInfo = Base64.encodeToString(userInfo.toByteArray(), Base64.NO_WRAP)
        val serverPart = "$server:$port"
        val name = encodeUrlComponent(outbound.tag)
        return "ss://$encodedUserInfo@$serverPart#$name"
    }
    
    private fun generateTrojanLink(outbound: Outbound): String {
         val password = encodeUrlComponent(outbound.password ?: "")
         val server = outbound.server?.let { formatServerHost(it) } ?: return ""
         val port = outbound.serverPort ?: 443
         val name = encodeUrlComponent(outbound.tag)
         
         val params = mutableListOf<String>()
         if (outbound.tls?.enabled == true) {
             params.add("security=tls")
             outbound.tls.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
             if (outbound.tls.insecure == true) params.add("allowInsecure=1")
         }

         val queryPart = buildOptionalQuery(params)
         return "trojan://$password@$server:$port${queryPart}#$name"
    }

    private fun generateHysteria2Link(outbound: Outbound): String {
         val password = encodeUrlComponent(outbound.password ?: "")
         val server = outbound.server?.let { formatServerHost(it) } ?: return ""
         val port = outbound.serverPort ?: 443
         val name = encodeUrlComponent(outbound.tag)
         
         val params = mutableListOf<String>()
         
         outbound.tls?.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
         if (outbound.tls?.insecure == true) params.add("insecure=1")
         
         outbound.obfs?.let { obfs ->
             obfs.type?.let { params.add("obfs=${encodeUrlComponent(it)}") }
             obfs.password?.let { params.add("obfs-password=${encodeUrlComponent(it)}") }
         }

         val queryPart = buildOptionalQuery(params)
         return "hysteria2://$password@$server:$port${queryPart}#$name"
    }

    private fun generateHysteriaLink(outbound: Outbound): String {
         val server = outbound.server?.let { formatServerHost(it) } ?: return ""
         val port = outbound.serverPort ?: 443
         val name = encodeUrlComponent(outbound.tag)
         
         val params = mutableListOf<String>()
         outbound.authStr?.let { params.add("auth=${encodeUrlComponent(it)}") }
         outbound.upMbps?.let { params.add("upmbps=$it") }
         outbound.downMbps?.let { params.add("downmbps=$it") }
         
         outbound.tls?.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
         if (outbound.tls?.insecure == true) params.add("insecure=1")
         outbound.tls?.alpn?.let { 
             if (it.isNotEmpty()) params.add("alpn=${encodeUrlComponent(it.joinToString(","))}") 
         }
         
         outbound.obfs?.let { obfs ->
             obfs.type?.let { params.add("obfs=${encodeUrlComponent(it)}") }
         }

         val queryPart = buildOptionalQuery(params)
         return "hysteria://$server:$port${queryPart}#$name"
    }
    
    /**
     * 生成 AnyTLS 链接
     */
    private fun generateAnyTLSLink(outbound: Outbound): String {
        val password = encodeUrlComponent(outbound.password ?: "")
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 443
        val name = encodeUrlComponent(outbound.tag)
        
        val params = mutableListOf<String>()
        
        outbound.tls?.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
        if (outbound.tls?.insecure == true) params.add("insecure=1")
        outbound.tls?.alpn?.let {
            if (it.isNotEmpty()) params.add("alpn=${encodeUrlComponent(it.joinToString(","))}")
        }
        outbound.tls?.utls?.fingerprint?.let { params.add("fp=${encodeUrlComponent(it)}") }
        
        outbound.idleSessionCheckInterval?.let { params.add("idle_session_check_interval=$it") }
        outbound.idleSessionTimeout?.let { params.add("idle_session_timeout=$it") }
        outbound.minIdleSession?.let { params.add("min_idle_session=$it") }
        
        val queryPart = buildOptionalQuery(params)
        return "anytls://$password@$server:$port${queryPart}#$name"
    }
    
    /**
     * 生成 TUIC 链接
     */
    private fun generateTuicLink(outbound: Outbound): String {
        val uuid = outbound.uuid ?: ""
        val password = encodeUrlComponent(outbound.password ?: "")
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 443
        val name = encodeUrlComponent(outbound.tag)
        
        val params = mutableListOf<String>()
        
        outbound.congestionControl?.let { params.add("congestion_control=${encodeUrlComponent(it)}") }
        outbound.udpRelayMode?.let { params.add("udp_relay_mode=${encodeUrlComponent(it)}") }
        if (outbound.zeroRttHandshake == true) params.add("reduce_rtt=1")
        
        outbound.tls?.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
        if (outbound.tls?.insecure == true) params.add("allow_insecure=1")
        outbound.tls?.alpn?.let {
            if (it.isNotEmpty()) params.add("alpn=${encodeUrlComponent(it.joinToString(","))}")
        }
        outbound.tls?.utls?.fingerprint?.let { params.add("fp=${encodeUrlComponent(it)}") }
        
        val queryPart = buildOptionalQuery(params)
        return "tuic://$uuid:$password@$server:$port${queryPart}#$name"
    }

    /**
     * 去除重复的 outbound tag
     */
    private fun deduplicateTags(config: SingBoxConfig): SingBoxConfig {
        val outbounds = config.outbounds ?: return config
        val seenTags = mutableSetOf<String>()
        
        val newOutbounds = outbounds.map { outbound ->
            var tag = outbound.tag
            // 处理空 tag
            if (tag.isBlank()) {
                tag = "unnamed"
            }
            
            var newTag = tag
            var counter = 1
            
            // 如果 tag 已经存在，则添加后缀直到不冲突
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
    
    /**
     * 查找可用端口，从指定端口开始尝试
     * 如果指定端口被占用，尝试下一个端口，最多尝试100次
     */
    private fun findAvailablePort(startPort: Int): Int {
        for (port in startPort until startPort + 100) {
            try {
                java.net.ServerSocket(port).use { 
                    return port 
                }
            } catch (_: Exception) {
                // 端口被占用，尝试下一个
            }
        }
        // 如果都失败，返回原始端口（让 sing-box 报错）
        return startPort
    }

    /**
     * 清理资源，取消协程 scope
     * 
     * 注意：由于 ConfigRepository 是单例且生命周期与 Application 相同，
     * 通常不需要手动调用此方法。此方法主要用于：
     * 1. 测试场景中清理资源
     * 2. 极端内存压力下的紧急清理
     */
    fun cleanup() {
        scope.cancel()
        regionFlagCache.clear()
        nodeIdCache.clear()
        configCache.clear()
        profileNodes.clear()
        savedNodeLatencies.clear()
        inFlightLatencyTests.clear()
        Log.i(TAG, "ConfigRepository cleanup completed")
    }
}
