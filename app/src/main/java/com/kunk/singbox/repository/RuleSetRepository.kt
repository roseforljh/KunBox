package com.kunk.singbox.repository

import android.content.Context
import android.util.Log
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Request
import com.kunk.singbox.utils.NetworkClient
import okhttp3.OkHttpClient
import java.io.File

/**
 */
class RuleSetRepository(private val context: Context) {

    companion object {
        private const val TAG = "RuleSetRepository"
        private const val RAW_GITHUB_HOST = "raw.githubusercontent.com/"
        private const val RAW_GITHUB_PREFIX = "https://raw.githubusercontent.com/"
        private const val JSDELIVR_CDN_PREFIX = "https://cdn.jsdelivr.net/gh/"

        @Volatile
        private var instance: RuleSetRepository? = null

        fun getInstance(context: Context): RuleSetRepository {
            return instance ?: synchronized(this) {
                instance ?: RuleSetRepository(context.applicationContext).also { instance = it }
            }
        }

        @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LongMethod", "CognitiveComplexMethod")
        internal fun normalizeRuleSetUrl(url: String, mirrorUrl: String): String {
            var rawUrl = normalizeRawRuleSetUrl(url.trim())
            rawUrl = unwrapProxyRuleSetUrl(rawUrl)
            rawUrl = normalizeRawRuleSetUrl(rawUrl)

            var updatedUrl = rawUrl

            if (mirrorUrl.contains("cdn.jsdelivr.net")) {
                if (rawUrl.startsWith(RAW_GITHUB_PREFIX)) {
                    val path = rawUrl.removePrefix(RAW_GITHUB_PREFIX)
                    val parts = path.split("/", limit = 4)
                    if (parts.size >= 4) {
                        val user = parts[0]
                        val repo = parts[1]
                        val branch = parts[2]
                        val filePath = parts[3]
                        updatedUrl = "$JSDELIVR_CDN_PREFIX$user/$repo@$branch/$filePath"
                    }
                }
            } else if (mirrorUrl != RAW_GITHUB_PREFIX) {
                if (rawUrl.startsWith(RAW_GITHUB_PREFIX)) {
                    updatedUrl = rawUrl.replace(RAW_GITHUB_PREFIX, mirrorUrl)
                }
            }

            return updatedUrl
        }

        internal fun normalizeRuleSetForSave(ruleSet: RuleSet, mirrorUrl: String): RuleSet {
            if (ruleSet.type != RuleSetType.REMOTE) return ruleSet
            return ruleSet.copy(url = normalizeRuleSetUrl(ruleSet.url, mirrorUrl))
        }

        private fun normalizeRawRuleSetUrl(url: String): String {
            if (url.startsWith(JSDELIVR_CDN_PREFIX)) {
                return normalizeCdnRuleSetUrl(url)
            }

            if (url.contains(RAW_GITHUB_HOST)) {
                return normalizeRawGithubRuleSetUrl(url)
            }

            if (isGithubPathOnlyRuleSetUrl(url)) {
                return RAW_GITHUB_PREFIX + url.removePrefix("/")
            }

            return url
        }

        private fun normalizeCdnRuleSetUrl(url: String): String {
            val path = url.removePrefix(JSDELIVR_CDN_PREFIX)
            val parts = path.split("@", limit = 2)
            return if (parts.size == 2) {
                "$RAW_GITHUB_PREFIX${parts[0]}/${parts[1]}"
            } else {
                url
            }
        }

        private fun normalizeRawGithubRuleSetUrl(url: String): String {
            val path = url.substringAfter(RAW_GITHUB_HOST)
            if (path.startsWith("http://") || path.startsWith("https://")) {
                val cleanPath = path
                    .removePrefix("https://")
                    .removePrefix("http://")
                return RAW_GITHUB_PREFIX + cleanPath
            }
            if (path.contains(RAW_GITHUB_HOST)) {
                return RAW_GITHUB_PREFIX + path.substringAfter(RAW_GITHUB_HOST)
            }
            return RAW_GITHUB_PREFIX + path
        }

        private fun isGithubPathOnlyRuleSetUrl(url: String): Boolean {
            return !url.startsWith("http://") &&
                !url.startsWith("https://") &&
                url.count { it == '/' } >= 3
        }

        @Suppress("ReturnCount")
        private fun unwrapProxyRuleSetUrl(url: String): String {
            val proxy = ruleSetProxyPrefixes().firstOrNull { url.startsWith(it) } ?: return url
            val afterProxy = url.removePrefix(proxy)
            if (afterProxy.startsWith("http://") || afterProxy.startsWith("https://")) {
                return unwrapProtocolRuleSetUrl(afterProxy)
            }
            return afterProxy
        }

        private fun ruleSetProxyPrefixes(): List<String> {
            return listOf(
                "https://ghp.ci/",
                "https://mirror.ghproxy.com/",
                "https://ghproxy.com/",
                "https://ghproxy.net/",
                "https://ghfast.top/",
                "https://gh-proxy.com/"
            )
        }

        private fun unwrapProtocolRuleSetUrl(url: String): String {
            val withoutProtocol = url
                .removePrefix("https://")
                .removePrefix("http://")
            val firstSlash = withoutProtocol.indexOf('/')
            return if (firstSlash > 0) {
                "/" + withoutProtocol.substring(firstSlash)
            } else {
                "/$withoutProtocol"
            }
        }
    }

    private val ruleSetDir: File
        get() = File(context.filesDir, "rulesets").also { it.mkdirs() }

    private val settingsRepository = SettingsRepository.getInstance(context)

    private fun getDirectClient(): OkHttpClient {
        return NetworkClient.createClientWithTimeout(
            connectTimeoutSeconds = 30,
            readTimeoutSeconds = 60,
            writeTimeoutSeconds = 30
        )
    }

    private fun getProxyClient(settings: AppSettings): OkHttpClient? {
        if (!VpnStateStore.getActive() || settings.proxyPort <= 0) {
            return null
        }
        return NetworkClient.createClientWithProxy(
            proxyPort = settings.proxyPort,
            connectTimeoutSeconds = 30,
            readTimeoutSeconds = 60,
            writeTimeoutSeconds = 30
        )
    }

    /**
     */
    fun isRuleSetLocal(tag: String): Boolean {
        return getRuleSetFile(tag).exists()
    }

    /**
     */
    suspend fun hasLocalCache(): Boolean = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()

        settings.ruleSets.filter { it.enabled && it.type == RuleSetType.REMOTE }.forEach { ruleSet ->
            if (!getRuleSetFile(ruleSet.tag).exists()) {
                return@withContext false
            }
        }

        true
    }

    /**
     */
    suspend fun ensureRuleSetsReady(
        forceUpdate: Boolean = false,
        allowNetwork: Boolean = false,
        onProgress: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()
        var allReady = true

        settings.ruleSets.filter { it.enabled && it.type == RuleSetType.REMOTE }.forEach { ruleSet ->
            val file = getRuleSetFile(ruleSet.tag)

            if (!file.exists()) {
                installBaselineRuleSet(ruleSet.tag, file)
            }

            if (allowNetwork && (!file.exists() || (forceUpdate && isExpired(file)))) {
                onProgress("Updating rule set ${ruleSet.tag}...")
                val success = downloadCustomRuleSet(ruleSet, settings)
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
     */
    suspend fun prefetchRuleSet(
        ruleSet: RuleSet,
        forceUpdate: Boolean = false,
        allowNetwork: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        if (!ruleSet.enabled) return@withContext true

        val settings = settingsRepository.settings.first()

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
                    val success = downloadCustomRuleSet(ruleSet, settings)
                    success || file.exists()
                } else {
                    true
                }
            }
        }
    }

    /**
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

            Log.w(TAG, "Baseline rule set not found in assets: $tag")
            false
        }
    }

    /**
     */
    fun getRuleSetPath(tag: String): String {
        return getRuleSetFile(tag).absolutePath
    }

    private fun getRuleSetFile(tag: String): File {
        return File(ruleSetDir, "$tag.srs")
    }

    private fun isExpired(file: File): Boolean {

        val lastModified = file.lastModified()
        val now = System.currentTimeMillis()
        return (now - lastModified) > 24 * 60 * 60 * 1000
    }

    private suspend fun downloadCustomRuleSet(
        ruleSet: RuleSet,
        settings: AppSettings
    ): Boolean {
        if (ruleSet.url.isBlank()) return false
        val mirrorUrl = settings.ghProxyMirror.url

        val mirrorUrlString = normalizeRuleSetUrl(ruleSet.url, mirrorUrl)
        val success = downloadFileWithFallback(mirrorUrlString, getRuleSetFile(ruleSet.tag), settings)

        if (success) return true

        if (mirrorUrlString != ruleSet.url) {
            Log.w(TAG, "Mirror download failed, trying original URL: ${ruleSet.url}")
            return downloadFileWithFallback(ruleSet.url, getRuleSetFile(ruleSet.tag), settings)
        }

        return false
    }

    private suspend fun downloadFileWithFallback(
        url: String,
        targetFile: File,
        settings: AppSettings
    ): Boolean {
        val proxyClient = getProxyClient(settings)
        if (proxyClient != null) {
            return try {
                val success = downloadFile(proxyClient, url, targetFile)
                if (success) {
                    Log.d(TAG, "Proxy download succeeded: ${targetFile.name}")
                } else {
                    Log.w(TAG, "Proxy download failed")
                }
                success
            } catch (e: Exception) {
                Log.w(TAG, "Proxy download error: ${e.message}")
                false
            }
        }

        return downloadFile(getDirectClient(), url, targetFile)
    }

    @Suppress("ReturnCount", "NestedBlockDepth", "CyclomaticComplexMethod", "CognitiveComplexMethod")
    private suspend fun downloadFile(client: OkHttpClient, url: String, targetFile: File): Boolean {
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

                    false
                }

                if (isValid) {
                    if (!replaceRuleSetFile(tempFile, targetFile)) {
                        Log.e(TAG, "Failed to replace rule set file, keeping temp file for retry: ${tempFile.name}")
                        return false
                    }
                    tempFile.delete()
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

    @Suppress("ReturnCount")
    private fun replaceRuleSetFile(tempFile: File, targetFile: File): Boolean {
        val backupFile = File(targetFile.parentFile, "${targetFile.name}.bak")

        if (backupFile.exists() && !backupFile.delete()) {
            Log.e(TAG, "Failed to delete stale backup file: ${backupFile.name}")
            return false
        }

        if (targetFile.exists()) {
            try {
                targetFile.copyTo(backupFile, overwrite = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create rule set backup before replace: ${targetFile.name}", e)
                return false
            }

            if (!targetFile.delete()) {
                Log.e(TAG, "Failed to remove existing rule set before replace: ${targetFile.name}")
                return false
            }
        }

        if (tempFile.renameTo(targetFile)) {
            if (backupFile.exists() && !backupFile.delete()) {
                Log.w(TAG, "Failed to delete rule set backup after successful replace: ${backupFile.name}")
            }
            return true
        }

        Log.e(TAG, "Failed to replace rule set file: ${targetFile.name}")
        if (backupFile.exists()) {
            try {
                backupFile.copyTo(targetFile, overwrite = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore original rule set after replace failure: ${targetFile.name}", e)
            }
        }
        return false
    }
}
