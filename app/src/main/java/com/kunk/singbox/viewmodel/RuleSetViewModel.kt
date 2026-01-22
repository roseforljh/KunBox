package com.kunk.singbox.viewmodel

import com.kunk.singbox.R
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.kunk.singbox.model.HubRuleSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.Request
import com.kunk.singbox.utils.NetworkClient
import android.util.Log
import com.kunk.singbox.repository.RuleSetRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.model.GithubTreeResponse
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.ipc.SingBoxRemote
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

class RuleSetViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "RuleSetViewModel"
    }

    private val ruleSetRepository = RuleSetRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)

    // 监听 settings 变化，用于判断规则集是否已添加
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )

    private val _ruleSets = MutableStateFlow<List<HubRuleSet>>(emptyList())
    val ruleSets: StateFlow<List<HubRuleSet>> = _ruleSets.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * 检查规则集是否已添加到用户的规则集列表中
     * 这里检查的是用户配置中是否存在该规则集，而不是物理文件是否存在
     */
    fun isDownloaded(tag: String): Boolean {
        return settings.value.ruleSets.any { it.tag == tag }
    }

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val client = NetworkClient.client
    private val gson = Gson()

    init {
        // 自动加载逻辑优化：
        // 1. App 启动时，如果 VPN 没开，尝试直连加载
        // 2. 监听 VPN 状态，当 VPN 启动成功（连接建立）后，自动刷新（如果之前加载失败或为空）
        viewModelScope.launch {
            if (!SingBoxRemote.isRunning.value) {
                fetchRuleSets()
            }

            SingBoxRemote.isRunning.collectLatest { isRunning ->
                if (isRunning) {
                    // VPN 刚启动，网络环境可能正在切换 (TUN建立 -> 路由重置)
                    // 等待一段时间让 Socket 稳定，避免 "use of closed network connection"
                    delay(2000)

                    if (_ruleSets.value.isEmpty() || _error.value != null) {
                        Log.i(TAG, "VPN 已连接，自动重试加载规则集...")
                        fetchRuleSets()
                    }
                }
            }
        }
    }

    fun fetchRuleSets() {
        // 允许重复调用以支持重试，但要注意并发
        if (_isLoading.value) return

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null
            try {
                val sagerNetRules = fetchFromSagerNet()

                if (sagerNetRules.isEmpty()) {
                    Log.w(TAG, "Online results empty, using built-in rule sets")
                    // 确保一定有数据
                    val builtIn = getBuiltInRuleSets().sortedBy { it.name }
                    _ruleSets.value = builtIn
                } else {
                    _ruleSets.value = sagerNetRules.sortedBy { it.name }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch rule sets", e)
                _error.value = getApplication<Application>().getString(R.string.ruleset_update_network_error)
                // 即使失败，也加载内置规则集，保证页面不为空
                val current = _ruleSets.value
                if (current.isEmpty()) {
                    Log.w(TAG, "当前列表为空，加载内置规则集作为兜底")
                    _ruleSets.value = getBuiltInRuleSets().sortedBy { it.name }
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun getBuiltInRuleSets(): List<HubRuleSet> {
        val githubUrl = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set"
        // 使用镜像加速访问
        val baseUrl = "https://ghp.ci/$githubUrl"
        val commonRules = listOf(
            "google", "youtube", "twitter", "facebook", "instagram", "tiktok",
            "telegram", "whatsapp", "discord", "github", "microsoft", "apple",
            "amazon", "netflix", "spotify", "bilibili", "zhihu", "baidu",
            "tencent", "alibaba", "jd", "taobao", "weibo", "douyin",
            "cn", "geolocation-cn", "geolocation-!cn", "private", "category-ads-all"
        )
        return commonRules.map { name ->
            val fullName = if (name == "category-ads-all") "geosite-category-ads-all" else "geosite-$name"
            HubRuleSet(
                name = fullName,
                ruleCount = 0,
                tags = listOf("Built-in", "geosite"),
                description = "Commonly used rule sets",
                sourceUrl = "$baseUrl/$fullName.json",
                binaryUrl = "$baseUrl/$fullName.srs"
            )
        }
    }

    private fun fetchFromSagerNet(): List<HubRuleSet> {
        // 使用 ghp.ci 镜像代理 GitHub API 请求可能不合适，API 还是直接连，但下载链接用镜像
        val rawUrl = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set"
        val url = "https://api.github.com/repos/SagerNet/sing-geosite/git/trees/rule-set?recursive=1"
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "KunK-KunBox-App")
                .build()

            // 使用较短的超时时间，避免卡住太久
            val shortTimeoutClient = client.newBuilder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            shortTimeoutClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "[SagerNet] 请求失败! 状态码=${response.code}, 响应=$errorBody")
                    return emptyList()
                }

                val json = response.body?.string() ?: "{}"
                val treeResponse: GithubTreeResponse = gson.fromJson(json, GithubTreeResponse::class.java)
                    ?: return emptyList()

                val srsFiles = treeResponse.tree
                    .filter { it.type == "blob" && it.path.endsWith(".srs") }

                srsFiles.map { file ->
                    val fileName = file.path.substringAfterLast("/")
                    val nameWithoutExt = fileName.substringBeforeLast(".srs")
                    val sourcePath = file.path.replace(".srs", ".json")
                    HubRuleSet(
                        name = nameWithoutExt,
                        ruleCount = 0,
                        tags = listOf("Official", "geosite"),
                        description = "SagerNet Official Rule Set",
                        sourceUrl = "https://ghp.ci/$rawUrl/$sourcePath",
                        binaryUrl = "https://ghp.ci/$rawUrl/${file.path}"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[SagerNet] 发生异常: ${e.javaClass.simpleName} - ${e.message}", e)
            emptyList()
        }
    }
}
