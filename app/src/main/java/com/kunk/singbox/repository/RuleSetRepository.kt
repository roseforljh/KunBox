package com.kunk.singbox.repository

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.stream.JsonReader
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetType
import com.kunk.singbox.utils.NetworkClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.OkHttpClient
import java.io.File
import java.io.InputStreamReader
import java.io.StringReader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class RuleSetRepository(private val context: Context) {

    companion object {
        private const val TAG = "RuleSetRepository"
        private const val RAW_GITHUB_HOST = "raw.githubusercontent.com/"
        private const val RAW_GITHUB_PREFIX = "https://raw.githubusercontent.com/"
        private const val JSDELIVR_CDN_PREFIX = "https://cdn.jsdelivr.net/gh/"
        private const val RULE_SET_MIN_SIZE_BYTES = 10L
        private const val RULE_SET_BINARY_MAGIC = "SRS"
        private const val RULE_SET_VALIDATION_SAMPLE_BYTES = 1024
        private const val LEGACY_BUNDLED_ADS_SIZE = 5865L
        private const val LEGACY_BUNDLED_ADS_SHA256 =
            "a3f0420fb7c6fb3663a401426411d17827a01bc4418ff6112d578e54b71374ae"

        private val REGEX_RULE_SET_ERROR_TEXT = Regex(
            "^(error|forbidden|not found|404|403|401|429|500|access denied|" +
                "invalid request|too many requests|rate limit|rate limited)\\b",
            RegexOption.IGNORE_CASE
        )

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

        internal fun ruleSetCacheFileName(tag: String): String {
            val trimmed = tag.trim()
            val safeTag = trimmed.isNotEmpty() &&
                trimmed != "." &&
                trimmed != ".." &&
                trimmed.all { it.isLetterOrDigit() || it in setOf('-', '_', '.', '!', '#', '@') }

            if (safeTag) return "$trimmed.srs"

            val prefix = trimmed
                .map { if (it.isLetterOrDigit() || it in setOf('-', '_', '.', '!', '#', '@')) it else '_' }
                .joinToString("")
                .trim('.', '_')
                .take(48)
                .ifBlank { "ruleset" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(trimmed.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(16)
            return "$prefix-$digest.srs"
        }

        internal fun shouldDownloadRemoteRuleSet(
            fileExists: Boolean,
            allowNetwork: Boolean,
            forceUpdate: Boolean,
            isExpired: Boolean
        ): Boolean {
            return allowNetwork && (!fileExists || forceUpdate || isExpired)
        }

        internal fun isRemoteRuleSetReadyAfterDownloadFailure(fileExists: Boolean, forceUpdate: Boolean): Boolean {
            return fileExists && !forceUpdate
        }

        internal fun ruleSetSourceKey(ruleSet: RuleSet): String {
            // 镜像只是下载入口；来源按规范 raw URL 计算，切换镜像不能让已校验缓存失效。
            val canonicalUrl = normalizeRuleSetUrl(ruleSet.url, RAW_GITHUB_PREFIX)
            return MessageDigest.getInstance("SHA-256")
                .digest("$canonicalUrl\n${ruleSet.format}".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        internal fun canUseLegacyRuleSetCache(ruleSet: RuleSet, previous: RuleSet?): Boolean =
            previous?.enabled == true && previous.tag == ruleSet.tag && previous.type == ruleSet.type &&
                ruleSetSourceKey(previous) == ruleSetSourceKey(ruleSet)

        internal fun isRemoteRuleSetCacheReady(ruleSet: RuleSet, file: File, requireSource: Boolean = false): Boolean {
            return try {
                if (!file.isFile || !isDownloadedRuleSetFileValid(file, ruleSet.format)) return false
                val sourceFile = File(file.parentFile, "${file.name}.source")
                if (sourceFile.isFile) return sourceFile.readText() == ruleSetSourceKey(ruleSet)
                // ponytail: 旧版缓存没有来源记录；沿用相同已启用来源，但新启用必须下载，且不信任内置广告规则。
                !requireSource && !isLegacyBundledAdsFile(ruleSet, file)
            } catch (e: Exception) {
                Log.w(TAG, "Rule set cache is unreadable: ${ruleSet.tag}", e)
                false
            }
        }

        private fun isLegacyBundledAdsFile(ruleSet: RuleSet, file: File): Boolean {
            if (ruleSet.tag != "geosite-category-ads-all" || file.length() != LEGACY_BUNDLED_ADS_SIZE) return false
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            return digest.joinToString("") { "%02x".format(it) } == LEGACY_BUNDLED_ADS_SHA256
        }

        private fun isDownloadedRuleSetFileValid(file: File, format: String): Boolean {
            if (file.length() < RULE_SET_MIN_SIZE_BYTES) return false
            if (isSourceFormat(format)) {
                return isSourceRuleSetFileValid(file)
            }

            val sample = readValidationSample(file)
            val header = sample.toString(Charsets.ISO_8859_1)
            return isDownloadedRuleSetContentValid(header, file.length(), format)
        }

        private fun isSourceRuleSetFileValid(file: File): Boolean {
            return runCatching {
                InputStreamReader(file.inputStream(), Charsets.UTF_8).use { streamReader ->
                    JsonReader(streamReader).use { reader ->
                        readSourceRuleSetJson(reader)
                    }
                }
            }.getOrDefault(false)
        }

        private fun readValidationSample(file: File): ByteArray {
            return file.inputStream().use { input ->
                val buffer = ByteArray(RULE_SET_VALIDATION_SAMPLE_BYTES)
                val read = input.read(buffer)
                if (read > 0) buffer.copyOf(read) else ByteArray(0)
            }
        }

        internal fun createDownloadTempFile(targetFile: File): File {
            targetFile.parentFile?.mkdirs()
            val prefix = "${targetFile.name.take(64)}.".takeIf { it.length >= 3 } ?: "tmp."
            return File.createTempFile(prefix, ".tmp", targetFile.parentFile)
        }

        internal fun isDownloadedRuleSetContentValid(
            header: String,
            fileLength: Long,
            format: String
        ): Boolean {
            val trimmed = header.trim()
            val isSource = isSourceFormat(format)
            return when {
                fileLength < RULE_SET_MIN_SIZE_BYTES -> false
                looksLikeHtml(trimmed) -> false
                isLikelyErrorText(trimmed) -> false
                isSource -> isSourceRuleSetJsonValid(trimmed)
                trimmed.startsWith(RULE_SET_BINARY_MAGIC) -> true
                isLikelyText(trimmed) -> false
                else -> false
            }
        }

        private fun isSourceFormat(format: String): Boolean {
            return format.equals("source", ignoreCase = true)
        }

        private fun looksLikeHtml(trimmed: String): Boolean {
            return trimmed.startsWith("<!DOCTYPE html", ignoreCase = true) ||
                trimmed.startsWith("<html", ignoreCase = true)
        }

        private fun isLikelyErrorText(trimmed: String): Boolean {
            val firstLine = trimmed.lineSequence().firstOrNull().orEmpty()
            return REGEX_RULE_SET_ERROR_TEXT.containsMatchIn(firstLine)
        }

        private fun isLikelyText(trimmed: String): Boolean {
            return trimmed.isNotEmpty() && trimmed.all { char ->
                char == '\t' || char == '\n' || char == '\r' || char.code in 32..126
            }
        }

        private fun isSourceRuleSetJsonValid(content: String): Boolean {
            return runCatching {
                JsonReader(StringReader(content)).use { reader ->
                    readSourceRuleSetJson(reader)
                }
            }.getOrDefault(false)
        }

        private fun readSourceRuleSetJson(reader: JsonReader): Boolean {
            reader.beginObject()
            var hasRules = false
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "rules" -> {
                        hasRules = true
                        reader.skipValue()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            return hasRules
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

    private val directClient: OkHttpClient by lazy {
        NetworkClient.createClientWithTimeout(
            connectTimeoutSeconds = 30,
            readTimeoutSeconds = 60,
            writeTimeoutSeconds = 30,
            callTimeoutSeconds = 90
        )
    }
    private val proxyClientCache = mutableMapOf<Int, OkHttpClient>()

    private fun getProxyClient(settings: AppSettings): OkHttpClient? {
        if (!VpnStateStore.getActive() || settings.proxyPort <= 0) {
            return null
        }
        return synchronized(proxyClientCache) {
            proxyClientCache.getOrPut(settings.proxyPort) {
                NetworkClient.createClientWithProxy(
                    proxyPort = settings.proxyPort,
                    connectTimeoutSeconds = 30,
                    readTimeoutSeconds = 60,
                    writeTimeoutSeconds = 30,
                    callTimeoutSeconds = 90
                )
            }
        }
    }

    fun isRuleSetLocal(tag: String): Boolean {
        return getRuleSetFile(tag).exists()
    }

    suspend fun hasLocalCache(): Boolean = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()

        settings.ruleSets.filter { it.enabled }.all { ruleSet ->
            when (ruleSet.type) {
                RuleSetType.LOCAL -> File(ruleSet.path).isFile
                RuleSetType.REMOTE -> {
                    val file = getRuleSetFile(ruleSet.tag)
                    isRemoteRuleSetCacheReady(ruleSet, file)
                }
            }
        }
    }

    @Suppress("CognitiveComplexMethod")
    suspend fun ensureRuleSetsReady(
        forceUpdate: Boolean = false,
        allowNetwork: Boolean = false,
        onProgress: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()
        var allReady = true

        settings.ruleSets.filter { it.enabled }.forEach { ruleSet ->
            when (ruleSet.type) {
                RuleSetType.LOCAL -> {
                    if (!File(ruleSet.path).isFile) {
                        allReady = false
                        Log.w(TAG, "Local rule set ${ruleSet.tag} is missing: ${ruleSet.path}")
                    }
                }
                RuleSetType.REMOTE -> {
                    val file = getRuleSetFile(ruleSet.tag)
                    val cacheReady = isRemoteRuleSetCacheReady(ruleSet, file)
                    val shouldUpdate = shouldDownloadRemoteRuleSet(
                        fileExists = cacheReady,
                        allowNetwork = allowNetwork,
                        forceUpdate = forceUpdate,
                        isExpired = file.isFile && isExpired(file)
                    )

                    if (shouldUpdate) {
                        onProgress("Updating rule set ${ruleSet.tag}...")
                        val success = downloadCustomRuleSet(ruleSet, settings)
                        if (!success && !isRemoteRuleSetReadyAfterDownloadFailure(cacheReady, forceUpdate)) {
                            allReady = false
                            Log.e(TAG, "Failed to download rule set ${ruleSet.tag} and no valid cache available")
                        }
                    } else if (!cacheReady) {
                        allReady = false
                        Log.w(TAG, "Rule set ${ruleSet.tag} is not downloaded or invalid")
                    }
                }
            }
        }

        allReady
    }

    suspend fun prefetchRuleSet(
        ruleSet: RuleSet,
        forceUpdate: Boolean = false,
        allowNetwork: Boolean = true,
        requireSource: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()

        return@withContext when (ruleSet.type) {
            RuleSetType.LOCAL -> File(ruleSet.path).isFile
            RuleSetType.REMOTE -> {
                val file = getRuleSetFile(ruleSet.tag)
                val cacheReady = isRemoteRuleSetCacheReady(ruleSet, file, requireSource)
                if (!allowNetwork) {
                    cacheReady
                } else if (
                    shouldDownloadRemoteRuleSet(
                        fileExists = cacheReady,
                        allowNetwork = true,
                        forceUpdate = forceUpdate,
                        isExpired = file.isFile && isExpired(file)
                    )
                ) {
                    val success = downloadCustomRuleSet(ruleSet, settings)
                    success || isRemoteRuleSetReadyAfterDownloadFailure(cacheReady, forceUpdate)
                } else {
                    cacheReady
                }
            }
        }
    }

    fun getRuleSetPath(tag: String): String {
        return getRuleSetFile(tag).absolutePath
    }

    internal fun isRemoteRuleSetReady(ruleSet: RuleSet): Boolean {
        return isRemoteRuleSetCacheReady(ruleSet, getRuleSetFile(ruleSet.tag))
    }

    private fun getRuleSetFile(tag: String): File {
        return File(ruleSetDir, ruleSetCacheFileName(tag))
    }

    private fun confirmRemoteDownload(ruleSet: RuleSet): Boolean {
        return runCatching {
            val sourceFile = File(ruleSetDir, "${ruleSetCacheFileName(ruleSet.tag)}.source")
            val tempFile = createDownloadTempFile(sourceFile)
            try {
                tempFile.writeText(ruleSetSourceKey(ruleSet))
                replaceRuleSetFile(tempFile, sourceFile)
            } finally {
                tempFile.delete()
            }
        }.onFailure { Log.e(TAG, "Failed to record downloaded rule set: ${ruleSet.tag}", it) }.getOrDefault(false)
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
        val success = downloadFileWithFallback(mirrorUrlString, getRuleSetFile(ruleSet.tag), settings, ruleSet.format)

        if (success) return confirmRemoteDownload(ruleSet)

        if (mirrorUrlString != ruleSet.url) {
            Log.w(TAG, "Mirror download failed, trying original URL: ${ruleSet.url}")
            return downloadFileWithFallback(ruleSet.url, getRuleSetFile(ruleSet.tag), settings, ruleSet.format) &&
                confirmRemoteDownload(ruleSet)
        }

        return false
    }

    private suspend fun downloadFileWithFallback(
        url: String,
        targetFile: File,
        settings: AppSettings,
        format: String
    ): Boolean {
        val proxyClient = getProxyClient(settings)
        if (proxyClient != null) {
            val proxySuccess = try {
                val success = downloadFile(proxyClient, url, targetFile, format)
                if (success) {
                    Log.d(TAG, "Proxy download succeeded: ${targetFile.name}")
                } else {
                    Log.w(TAG, "Proxy download failed")
                }
                success
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Proxy download error: ${e.message}")
                false
            }
            if (proxySuccess) return true
            Log.w(TAG, "Trying direct rule set download after proxy failure")
        }

        return downloadFile(directClient, url, targetFile, format)
    }

    @Suppress("ReturnCount", "NestedBlockDepth", "CyclomaticComplexMethod", "CognitiveComplexMethod")
    private suspend fun downloadFile(client: OkHttpClient, url: String, targetFile: File, format: String): Boolean {
        var tempFile: File? = null
        return try {
            val request = Request.Builder().url(url).build()
            NetworkClient.executeCancellable(client, request) { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed: HTTP ${response.code}")
                    false
                } else {
                    val body = response.body
                    val downloadTempFile = createDownloadTempFile(targetFile)
                    tempFile = downloadTempFile

                    body.byteStream().use { input ->
                        downloadTempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    val isValid = try {
                        if (!isDownloadedRuleSetFileValid(downloadTempFile, format)) {
                            Log.e(TAG, "Downloaded file is invalid, discarding: ${targetFile.name}")
                            false
                        } else {
                            true
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to verify downloaded file", e)

                        false
                    }

                    if (isValid) {
                        if (!replaceRuleSetFile(downloadTempFile, targetFile)) {
                            Log.e(TAG, "Failed to replace rule set file: ${downloadTempFile.name}")
                            false
                        } else {
                            downloadTempFile.delete()
                            Log.i(TAG, "Rule set downloaded and verified successfully: ${targetFile.name}")
                            true
                        }
                    } else {
                        downloadTempFile.delete()
                        false
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Download error: ${e.message}", e)
            false
        } finally {
            tempFile?.takeIf { it.exists() }?.delete()
        }
    }

    @Suppress("ReturnCount")
    private fun replaceRuleSetFile(tempFile: File, targetFile: File): Boolean {
        targetFile.parentFile?.mkdirs()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return replaceRuleSetFileWithFileApi(tempFile, targetFile)
        }

        return try {
            replaceRuleSetFileWithNio(tempFile, targetFile, atomic = true)
            true
        } catch (_: AtomicMoveNotSupportedException) {
            try {
                replaceRuleSetFileWithNio(tempFile, targetFile, atomic = false)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to replace rule set file: ${targetFile.name}", e)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to replace rule set file: ${targetFile.name}", e)
            false
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun replaceRuleSetFileWithNio(tempFile: File, targetFile: File, atomic: Boolean) {
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

    private fun replaceRuleSetFileWithFileApi(tempFile: File, targetFile: File): Boolean {
        return try {
            if (targetFile.exists() && !targetFile.delete()) {
                throw java.io.IOException("Failed to delete old rule set file: ${targetFile.absolutePath}")
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                if (!tempFile.delete()) {
                    Log.w(TAG, "Failed to delete moved rule set temp file: ${tempFile.absolutePath}")
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to replace rule set file: ${targetFile.name}", e)
            false
        }
    }
}
