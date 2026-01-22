package com.kunk.singbox.repository

import android.content.Context
import android.util.Log
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Request
import com.kunk.singbox.utils.NetworkClient
import java.io.File

/**
 * 规则集仓库 - 负责规则集的下载、缓存和管理
 */
class RuleSetRepository(private val context: Context) {

    companion object {
        private const val TAG = "RuleSetRepository"
        private const val AD_BLOCK_TAG = "geosite-category-ads-all"
        private const val AD_BLOCK_URL_SUFFIX = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs"

        @Volatile
        private var instance: RuleSetRepository? = null

        fun getInstance(context: Context): RuleSetRepository {
            return instance ?: synchronized(this) {
                instance ?: RuleSetRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val client = NetworkClient.client

    private val ruleSetDir: File
        get() = File(context.filesDir, "rulesets").also { it.mkdirs() }

    private val settingsRepository = SettingsRepository.getInstance(context)

    /**
     * 检查本地规则集是否存在
     */
    fun isRuleSetLocal(tag: String): Boolean {
        return getRuleSetFile(tag).exists()
    }

    /**
     * 快速检查所有启用的规则集是否有本地缓存
     * 用于 VPN 启动优化: 快速返回，不阻塞启动
     * @return true 如果所有启用的规则集都有本地缓存
     */
    suspend fun hasLocalCache(): Boolean = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()

        // 检查广告拦截规则集
        if (settings.blockAds) {
            if (!getRuleSetFile(AD_BLOCK_TAG).exists()) {
                return@withContext false
            }
        }

        // 检查自定义远程规则集
        settings.ruleSets.filter { it.enabled && it.type == RuleSetType.REMOTE }.forEach { ruleSet ->
            if (!getRuleSetFile(ruleSet.tag).exists()) {
                return@withContext false
            }
        }

        true
    }

    /**
     * 确保所有需要的规则集都已就绪（本地存在）
     * 如果不存在，尝试从 assets 复制或下载
     * @param forceUpdate 是否强制更新（忽略过期时间）
     * @return 是否所有规则集都可用（至少有旧缓存）
     */
    suspend fun ensureRuleSetsReady(
        forceUpdate: Boolean = false,
        allowNetwork: Boolean = false,
        onProgress: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()
        var allReady = true

        // 1. 处理广告拦截规则集
        if (settings.blockAds) {
            val adBlockFile = getRuleSetFile(AD_BLOCK_TAG)

            // 尝试从 assets 安装 baseline
            if (!adBlockFile.exists()) {
                onProgress("正在安装基础规则集...")
                installBaselineRuleSet(AD_BLOCK_TAG, adBlockFile)
            }

            if (allowNetwork && (!adBlockFile.exists() || (forceUpdate && isExpired(adBlockFile)))) {
                onProgress("正在更新广告规则集...")
                val success = downloadAdBlockRuleSet(settings)
                if (!success && !adBlockFile.exists()) {
                    // 如果下载失败但本地有缓存，不视为整体失败
                    if (!adBlockFile.exists()) {
                        allReady = false
                    }
                    Log.e(TAG, "Failed to download ad block rule set. Cache available: ${adBlockFile.exists()}")
                }
            } else if (!adBlockFile.exists()) {
                allReady = false
                Log.w(TAG, "Ad block rule set missing, and network download is disabled")
            }
        }

        // 2. 处理自定义远程规则集
        settings.ruleSets.filter { it.enabled && it.type == RuleSetType.REMOTE }.forEach { ruleSet ->
            val file = getRuleSetFile(ruleSet.tag)

            if (!file.exists()) {
                installBaselineRuleSet(ruleSet.tag, file)
            }

            if (allowNetwork && (!file.exists() || (forceUpdate && isExpired(file)))) {
                onProgress("正在更新规则集: ${ruleSet.tag}...")
                val success = downloadCustomRuleSet(ruleSet)
                if (!success && !file.exists()) {
                    allReady = false
                    Log.e(TAG, "Failed to download rule set ${ruleSet.tag} and no cache available")
                }
            } else if (!file.exists()) {
                allReady = false
                Log.w(TAG, "Rule set ${ruleSet.tag} missing, and network download is disabled")
            }
        }

        allReady
    }

    /**
     * 预下载指定规则集（用于添加时立刻拉取，避免启动阶段阻塞）
     */
    suspend fun prefetchRuleSet(
        ruleSet: RuleSet,
        forceUpdate: Boolean = false,
        allowNetwork: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        if (!ruleSet.enabled) return@withContext true

        return@withContext when (ruleSet.type) {
            RuleSetType.LOCAL -> File(ruleSet.path).exists()
            RuleSetType.REMOTE -> {
                val file = getRuleSetFile(ruleSet.tag)
                if (!file.exists()) {
                    installBaselineRuleSet(ruleSet.tag, file)
                }
                if (!allowNetwork) {
                    file.exists()
                } else if (!file.exists() || (forceUpdate && isExpired(file))) {
                    val success = downloadCustomRuleSet(ruleSet)
                    success || file.exists()
                } else {
                    true
                }
            }
        }
    }

    /**
     * 从 assets 安装基础规则集
     */
    private fun installBaselineRuleSet(tag: String, targetFile: File): Boolean {
        return try {
            val assetPath = "rulesets/$tag.srs"

            context.assets.open(assetPath).use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Baseline rule set installed: ${targetFile.name}")
            true
        } catch (e: Exception) {
            // 可能是 assets 里没有这个文件，这是正常的（比如自定义规则集）
            Log.w(TAG, "Baseline rule set not found in assets: $tag")
            false
        }
    }

    /**
     * 获取规则集本地文件路径
     */
    fun getRuleSetPath(tag: String): String {
        return getRuleSetFile(tag).absolutePath
    }

    private fun getRuleSetFile(tag: String): File {
        return File(ruleSetDir, "$tag.srs")
    }

    private fun isExpired(file: File): Boolean {
        // 简单策略：超过 24 小时视为过期
        // 实际生产中可以配合 ETag 或 Last-Modified 检查，这里简化处理
        val lastModified = file.lastModified()
        val now = System.currentTimeMillis()
        return (now - lastModified) > 24 * 60 * 60 * 1000
    }

    private suspend fun downloadAdBlockRuleSet(settings: AppSettings): Boolean {
        val mirrorUrl = settings.ghProxyMirror.url
        // AD_BLOCK_URL_SUFFIX 是完整 URL，需要规范化
        val url = normalizeRuleSetUrl(AD_BLOCK_URL_SUFFIX, mirrorUrl)
        return downloadFile(url, getRuleSetFile(AD_BLOCK_TAG))
    }

    private suspend fun downloadCustomRuleSet(ruleSet: RuleSet): Boolean {
        if (ruleSet.url.isBlank()) return false

        val settings = settingsRepository.settings.first()
        val mirrorUrl = settings.ghProxyMirror.url

        // 1. 尝试使用镜像下载
        val mirrorUrlString = normalizeRuleSetUrl(ruleSet.url, mirrorUrl)
        val success = downloadFile(mirrorUrlString, getRuleSetFile(ruleSet.tag))

        if (success) return true

        // 2. 如果镜像下载失败，且 URL 被修改过（即使用了镜像），则尝试原始 URL
        if (mirrorUrlString != ruleSet.url) {
            Log.w(TAG, "Mirror download failed, trying original URL: ${ruleSet.url}")
            return downloadFile(ruleSet.url, getRuleSetFile(ruleSet.tag))
        }

        return false
    }

    private fun normalizeRuleSetUrl(url: String, mirrorUrl: String): String {
        val rawPrefix = "https://raw.githubusercontent.com/"
        val cdnPrefix = "https://cdn.jsdelivr.net/gh/"

        // 先还原到原始 URL (raw.githubusercontent.com)
        var rawUrl = url

        // 1. 如果是 jsDelivr 格式，还原为 raw 格式
        // 示例: https://cdn.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-cn.srs
        if (rawUrl.startsWith(cdnPrefix)) {
            val path = rawUrl.removePrefix(cdnPrefix)
            // 提取 user/repo
            val parts = path.split("@", limit = 2)
            if (parts.size == 2) {
                val userRepo = parts[0]
                val branchPath = parts[1]
                rawUrl = "$rawPrefix$userRepo/$branchPath"
            }
        }

        // 2. 如果包含 raw.githubusercontent.com，无论是否有其他前缀，都提取出原始路径
        // 示例: https://ghproxy.com/https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs
        // 或者: https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs
        if (rawUrl.contains("raw.githubusercontent.com")) {
            // 关键修复: 这里不应该只看 substringAfter，还要看 path 是否已经是完整的 URL
            // rawUrl: https://raw.githubusercontent.com/https://raw.githubusercontent.com/... 这种错误情况
            var path = rawUrl.substringAfter("raw.githubusercontent.com/")

            // 如果 path 本身又以 https://raw.githubusercontent.com/ 开头（之前的错误叠加），需要递归清理
            while (path.contains("raw.githubusercontent.com/")) {
                path = path.substringAfter("raw.githubusercontent.com/")
            }

            // 如果 path 以 http 开头，说明截取错了位置，这里假设正常路径不包含协议头
            if (path.startsWith("https://") || path.startsWith("http://")) {
                // 这通常意味着 substringAfter 取到了参数或者错误的部分，尝试更严格的清洗
                path = path.replace("https://", "").replace("http://", "")
            }

            rawUrl = rawPrefix + path
        }

        var updatedUrl = rawUrl

        // 应用当前选择的镜像
        if (mirrorUrl.contains("cdn.jsdelivr.net")) {
            // 转换为 jsDelivr 格式: https://cdn.jsdelivr.net/gh/user/repo@branch/path
            if (rawUrl.startsWith(rawPrefix)) {
                val path = rawUrl.removePrefix(rawPrefix)
                // path 格式: user/repo/branch/path
                val parts = path.split("/", limit = 4)
                if (parts.size >= 4) {
                    val user = parts[0]
                    val repo = parts[1]
                    val branch = parts[2]
                    val filePath = parts[3]
                    updatedUrl = "$cdnPrefix$user/$repo@$branch/$filePath"
                }
            }
        } else if (mirrorUrl != rawPrefix) {
            // 其他镜像通常直接拼接
            if (rawUrl.startsWith(rawPrefix)) {
                updatedUrl = rawUrl.replace(rawPrefix, mirrorUrl)
            }
        }

        return updatedUrl
    }

    private suspend fun downloadFile(url: String, targetFile: File): Boolean {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed: HTTP ${response.code}")
                    return false
                }

                val body = response.body ?: return false
                val tempFile = File(targetFile.parent, "${targetFile.name}.tmp")

                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                // 校验文件内容是否有效 (不能是 HTML)
                val isValid = try {
                    val header = tempFile.inputStream().use { input ->
                        val buffer = ByteArray(64)
                        val read = input.read(buffer)
                        if (read > 0) String(buffer, 0, read) else ""
                    }
                    val trimmedHeader = header.trim()
                    val isInvalid = trimmedHeader.startsWith("<!DOCTYPE html", ignoreCase = true) ||
                        trimmedHeader.startsWith("<html", ignoreCase = true) ||
                        trimmedHeader.startsWith("{") // JSON error

                    if (isInvalid) {
                        Log.e(TAG, "Downloaded file is invalid (HTML/JSON), discarding: ${targetFile.name}")
                        false
                    } else if (tempFile.length() < 10) {
                        Log.e(TAG, "Downloaded file is too small, discarding: ${targetFile.name}")
                        false
                    } else {
                        true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to verify downloaded file", e)
                    // 如果无法读取，保守起见认为是坏的，但这里可能是IO错误
                    false
                }

                if (isValid) {
                    if (targetFile.exists()) {
                        targetFile.delete()
                    }
                    tempFile.renameTo(targetFile)
                    Log.i(TAG, "Rule set downloaded and verified successfully: ${targetFile.name}")
                    return true
                } else {
                    tempFile.delete()
                    return false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}", e)
            false
        }
    }
}
