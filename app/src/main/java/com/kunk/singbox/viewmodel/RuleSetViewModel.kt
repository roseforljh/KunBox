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
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.utils.NetworkClient
import android.util.Log
import com.kunk.singbox.repository.RuleSetRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.model.GithubTreeResponse
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.ipc.SingBoxRemote
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

class RuleSetViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "RuleSetViewModel"
    }

    private val ruleSetRepository = RuleSetRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)
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

    /**`r`n     * Parse SagerNet rule-set index and convert to HubRuleSet list.`r`n     */
    fun isDownloaded(tag: String): Boolean {
        return settings.value.ruleSets.any { it.tag == tag }
    }

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val gson = Gson()

    init {
        viewModelScope.launch {
            if (!SingBoxRemote.isRunning.value) {
                fetchRuleSets()
            }

            SingBoxRemote.isRunning.collectLatest { isRunning ->
                if (isRunning) {
                    delay(2000)

                    if (_ruleSets.value.isEmpty() || _error.value != null) {
                        Log.i(TAG, "VPN is running, reloading config before downloading rule set...")
                        fetchRuleSets()
                    }
                }
            }
        }
    }

    fun fetchRuleSets() {
        if (_isLoading.value) return

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null
            try {
                val currentSettings = settingsRepository.settings.first()
                val sagerNetGeositeRules = fetchGeositeFromSagerNet(currentSettings)
                val sagerNetGeoipRules = fetchGeoipFromSagerNet(currentSettings)

                if (sagerNetGeositeRules.isEmpty() && sagerNetGeoipRules.isEmpty()) {
                    Log.w(TAG, "Online results empty, using built-in rule sets")
                    val builtIn = getBuiltInRuleSets().sortedBy { it.name }
                    _ruleSets.value = builtIn
                } else {
                    _ruleSets.value = (sagerNetGeositeRules + sagerNetGeoipRules).sortedBy { it.name }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch rule sets", e)
                _error.value = getApplication<Application>().getString(R.string.ruleset_update_network_error)
                val current = _ruleSets.value
                if (current.isEmpty()) {
                    Log.w(TAG, "Fetch failed and cache is empty, fallback to built-in rule sets")
                    _ruleSets.value = getBuiltInRuleSets().sortedBy { it.name }
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun getBuiltInRuleSets(): List<HubRuleSet> {
        val geositeUrl = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set"
        val geositeBaseUrl = "https://ghp.ci/$geositeUrl"
        val geositeRules = listOf(
            "google", "youtube", "twitter", "facebook", "instagram", "tiktok",
            "telegram", "whatsapp", "discord", "github", "microsoft", "apple",
            "amazon", "netflix", "spotify", "bilibili", "zhihu", "baidu",
            "tencent", "alibaba", "jd", "taobao", "weibo", "douyin",
            "cn", "geolocation-cn", "geolocation-!cn", "private", "category-ads-all"
        )

        val geoipUrl = "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set"
        val geoipBaseUrl = "https://ghp.ci/$geoipUrl"
        val geoipRules = listOf(
            "cn", "us", "ru", "jp", "kr", "sg", "hk", "tw",
            "apple", "google", "microsoft", "facebook", "twitter", "amazon", "cloudflare"
        )

        val geositeRuleSets = geositeRules.map { name ->
            val fullName = if (name == "category-ads-all") "geosite-category-ads-all" else "geosite-$name"
            HubRuleSet(
                name = fullName,
                ruleCount = 0,
                tags = listOf("Built-in", "geosite"),
                description = "Commonly used rule sets",
                sourceUrl = "$geositeBaseUrl/$fullName.json",
                binaryUrl = "$geositeBaseUrl/$fullName.srs"
            )
        }

        val geoipRuleSets = geoipRules.map { name ->
            val fullName = "geoip-$name"
            HubRuleSet(
                name = fullName,
                ruleCount = 0,
                tags = listOf("Built-in", "geoip"),
                description = "Commonly used IP rule sets",
                sourceUrl = "$geoipBaseUrl/$fullName.json",
                binaryUrl = "$geoipBaseUrl/$fullName.srs"
            )
        }

        return geositeRuleSets + geoipRuleSets
    }

    private fun fetchGeositeFromSagerNet(currentSettings: AppSettings): List<HubRuleSet> {
        // Use path-only format, not full URL
        val rawUrl = "/SagerNet/sing-geosite/rule-set"
        val url = "https://api.github.com/repos/SagerNet/sing-geosite/git/trees/rule-set?recursive=1"
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "KunK-KunBox-App")
                .build()

            val response = executeRequestWithFallback(request, currentSettings)
            parseGeositeResponse(response, rawUrl)
        } catch (e: Exception) {
            Log.e(TAG, "[SagerNet] Request failed: ${e.javaClass.simpleName} - ${e.message}", e)
            emptyList()
        }
    }

    private fun fetchGeoipFromSagerNet(currentSettings: AppSettings): List<HubRuleSet> {
        // Use path-only format, not full URL
        val rawUrl = "/SagerNet/sing-geoip/rule-set"
        val url = "https://api.github.com/repos/SagerNet/sing-geoip/git/trees/rule-set?recursive=1"
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "KunK-KunBox-App")
                .build()

            val response = executeRequestWithFallback(request, currentSettings)
            parseGeoipResponse(response, rawUrl)
        } catch (e: Exception) {
            Log.e(TAG, "[SagerNet] Request failed: ${e.javaClass.simpleName} - ${e.message}", e)
            emptyList()
        }
    }

    private fun parseGeositeResponse(response: okhttp3.Response?, rawUrl: String): List<HubRuleSet> {
        if (response == null) {
            Log.e(TAG, "[SagerNet] Response is null")
            return emptyList()
        }

        return response.use { resp ->
            if (!resp.isSuccessful) {
                val errorBody = resp.body?.string() ?: ""
                Log.e(TAG, "[SagerNet] HTTP ${resp.code}, body=$errorBody")
                return@use emptyList()
            }

            val json = resp.body?.string() ?: "{}"
            val treeResponse: GithubTreeResponse = gson.fromJson(json, GithubTreeResponse::class.java)
                ?: return@use emptyList()

            val srsFiles = treeResponse.tree
                .filter { it.type == "blob" && it.path.endsWith(".srs") }

            // rawUrl is path-only like "/SagerNet/sing-geosite/rule-set", prepend proxy base
            val proxyBase = "https://ghp.ci"
            srsFiles.map { file ->
                val fileName = file.path.substringAfterLast("/")
                val nameWithoutExt = fileName.substringBeforeLast(".srs")
                val sourcePath = file.path.replace(".srs", ".json")
                HubRuleSet(
                    name = nameWithoutExt,
                    ruleCount = 0,
                    tags = listOf("Official", "geosite"),
                    description = "SagerNet Official Rule Set",
                    sourceUrl = "$proxyBase$rawUrl/$sourcePath",
                    binaryUrl = "$proxyBase$rawUrl/${file.path}"
                )
            }
        }
    }

    private fun parseGeoipResponse(response: okhttp3.Response?, rawUrl: String): List<HubRuleSet> {
        if (response == null) {
            Log.e(TAG, "[SagerNet] Response is null")
            return emptyList()
        }

        return response.use { resp ->
            if (!resp.isSuccessful) {
                val errorBody = resp.body?.string() ?: ""
                Log.e(TAG, "[SagerNet] HTTP ${resp.code}, body=$errorBody")
                return@use emptyList()
            }

            val json = resp.body?.string() ?: "{}"
            val treeResponse: GithubTreeResponse = gson.fromJson(json, GithubTreeResponse::class.java)
                ?: return@use emptyList()

            val srsFiles = treeResponse.tree
                .filter { it.type == "blob" && it.path.endsWith(".srs") }

            // rawUrl is path-only like "/SagerNet/sing-geoip/rule-set", prepend proxy base
            val proxyBase = "https://ghp.ci"
            srsFiles.map { file ->
                val fileName = file.path.substringAfterLast("/")
                val nameWithoutExt = fileName.substringBeforeLast(".srs")
                val sourcePath = file.path.replace(".srs", ".json")
                HubRuleSet(
                    name = nameWithoutExt,
                    ruleCount = 0,
                    tags = listOf("Official", "geoip"),
                    description = "SagerNet Official Rule Set",
                    sourceUrl = "$proxyBase$rawUrl/$sourcePath",
                    binaryUrl = "$proxyBase$rawUrl/${file.path}"
                )
            }
        }
    }

    private fun executeRequestWithFallback(
        request: okhttp3.Request,
        settings: AppSettings
    ): okhttp3.Response? {
        val proxyClient = getProxyClient(settings)
        if (proxyClient != null) {
            return try {
                val response = proxyClient.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d(TAG, "Proxy request succeeded")
                    response
                } else {
                    response.close()
                    Log.w(TAG, "Proxy request failed with ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Proxy request failed: ${e.message}")
                null
            }
        }

        return try {
            getDirectClient().newCall(request).execute()
        } catch (e: Exception) {
            Log.e(TAG, "Direct request also failed: ${e.message}")
            null
        }
    }

    private fun getDirectClient(): okhttp3.OkHttpClient {
        return NetworkClient.createClientWithTimeout(
            connectTimeoutSeconds = 10,
            readTimeoutSeconds = 10,
            writeTimeoutSeconds = 10
        )
    }

    private fun getProxyClient(settings: AppSettings): okhttp3.OkHttpClient? {
        if (!VpnStateStore.getActive() || settings.proxyPort <= 0) {
            return null
        }
        return NetworkClient.createClientWithProxy(
            proxyPort = settings.proxyPort,
            connectTimeoutSeconds = 10,
            readTimeoutSeconds = 10,
            writeTimeoutSeconds = 10
        )
    }
}
