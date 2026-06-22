package com.kunk.singbox.repository.subscription

import android.util.Log
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.utils.parser.SubscriptionManager
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 */
data class SubscriptionUserInfo(
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0
)

/**
 *
 */
class SubscriptionFetcher(
    private val client: OkHttpClient,
    private val subscriptionManager: SubscriptionManager
) {
    companion object {
        private const val TAG = "SubscriptionFetcher"

        private val USER_AGENTS = listOf(
            "v2rayN/6.23",
            "sing-box/1.12.0",
            "clash-verge/v1.3.8",
            "ClashforWindows/0.20.39",
            "Clash/1.18.0",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )

        private val REGEX_SANITIZE_UUID = Regex("(?i)uuid\\s*[:=]\\s*[^\\\\n]+")
        private val REGEX_SANITIZE_PASSWORD = Regex("(?i)password\\s*[:=]\\s*[^\\\\n]+")
        private val REGEX_SANITIZE_TOKEN = Regex("(?i)token\\s*[:=]\\s*[^\\\\n]+")
    }

    /**
     *
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod", "NestedBlockDepth", "ThrowsCount")
    fun fetch(
        url: String,
        onProgress: (String) -> Unit = {}
    ): FetchResult? {
        var lastError: Exception? = null

        for ((index, userAgent) in USER_AGENTS.withIndex()) {
            try {
                onProgress("Trying subscription request with User-Agent (${index + 1}/${USER_AGENTS.size})...")

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/yaml,text/yaml,text/plain,application/json,*/*")
                    .build()

                var parsedConfig: SingBoxConfig? = null
                var userInfo: SubscriptionUserInfo? = null

                client.newCall(request).execute().use { response ->
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
                            throw Exception("Subscription response body is empty")
                        }
                        return@use
                    }

                    userInfo = parseUserInfo(response.header("Subscription-Userinfo"), responseBody)

                    onProgress("Parsing subscription response...")

                    val config = subscriptionManager.parse(responseBody)
                    val isWarning = config?.outbounds?.let { outbounds ->
                        outbounds.size <= 5 && outbounds.any {
                            val tag = it.tag ?: ""
                            tag.contains("不支持") || tag.contains("更换") ||
                                (tag.contains("客户端") && (tag.contains("Clash") || tag.contains("v2rayN")))
                        }
                    } == true

                    if (config != null && !config.outbounds.isNullOrEmpty() && !isWarning) {
                        parsedConfig = config
                    } else {
                        if (isWarning) {
                            Log.w(TAG, "Response contains client warning tags with UA '$userAgent', ignoring.")
                        } else {
                            Log.w(TAG, "Failed to parse response with UA '$userAgent'")
                        }
                    }
                }

                parsedConfig?.let { config ->
                    Log.i(
                        TAG,
                        "Successfully parsed subscription with UA '$userAgent', " +
                            "got ${config.outbounds?.size ?: 0} outbounds"
                    )
                    return FetchResult(config, userInfo)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error with UA '$userAgent': ${e.message}")
                lastError = e
                if (index == USER_AGENTS.lastIndex) {
                    throw e
                }
            }
        }

        lastError?.let { Log.e(TAG, "All User-Agents failed", it) }
        return null
    }

    /**
     */
    private fun parseUserInfo(header: String?, body: String): SubscriptionUserInfo? {
        if (!header.isNullOrBlank()) {
            val info = parseUserInfoHeader(header)
            if (info != null) return info
        }

        return parseUserInfoFromBody(body)
    }

    /**
     */
    private fun parseUserInfoHeader(header: String): SubscriptionUserInfo? {
        try {
            var upload = 0L
            var download = 0L
            var total = 0L
            var expire = 0L

            header.split(";").forEach { part ->
                val kv = part.trim().split("=", limit = 2)
                if (kv.size == 2) {
                    val key = kv[0].trim().lowercase()
                    val value = kv[1].trim()
                    when (key) {
                        "upload" -> upload = value.toLongOrNull() ?: 0L
                        "download" -> download = value.toLongOrNull() ?: 0L
                        "total" -> total = value.toLongOrNull() ?: 0L
                        "expire" -> expire = value.toLongOrNull() ?: 0L
                    }
                }
            }

            if (upload > 0 || download > 0 || total > 0 || expire > 0) {
                return SubscriptionUserInfo(upload, download, total, expire)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse user info header", e)
        }
        return null
    }

    /**
     */
    private fun parseUserInfoFromBody(body: String): SubscriptionUserInfo? {

        return null
    }

    /**
     */
    fun sanitizeSnippet(body: String, maxLen: Int = 220): String {
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

    /**
     */
    data class FetchResult(
        val config: SingBoxConfig,
        val userInfo: SubscriptionUserInfo?
    )
}
