package com.kunk.singbox.utils.parser

import android.util.Log
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import java.net.URLDecoder

interface SubscriptionParser {
    fun canParse(content: String): Boolean

    fun parse(content: String): SingBoxConfig?
}

class SubscriptionManager(private val parsers: List<SubscriptionParser>) {

    companion object {
        private const val TAG = "SubscriptionManager"
        private val FILENAME_REGEX =
            """filename\*?=\s*(?:([^'"]*)'[^']*')?["']?([^"';]*)["']?""".toRegex(RegexOption.IGNORE_CASE)

        private fun getDeduplicationKey(outbound: Outbound): String? {
            if (outbound.server == null || outbound.serverPort == null) return null
            val type = outbound.type

            if (type == "selector" || type == "urltest" || type == "direct" || type == "block" || type == "dns") {
                return null
            }

            return outbound.toString()
        }

        fun deduplicateOutbounds(outbounds: List<Outbound>): List<Outbound> {
            val seen = mutableSetOf<String>()
            val result = mutableListOf<Outbound>()
            var duplicateCount = 0

            for (outbound in outbounds) {
                val key = getDeduplicationKey(outbound)
                if (key == null) {

                    result.add(outbound)
                } else if (seen.add(key)) {

                    result.add(outbound)
                } else {

                    duplicateCount++
                }
            }

            if (duplicateCount > 0) {
                Log.d(TAG, "Deduplicated $duplicateCount duplicate nodes, ${result.size} unique nodes remaining")
            }

            return result
        }

        fun parseSubscriptionNameFromHeader(profileTitle: String?, contentDisposition: String?): String? {
            val rawName = parseProfileTitleName(profileTitle)
                ?: parseContentDispositionName(contentDisposition)
            return rawName?.removeKnownSubscriptionSuffix()
        }

        private fun parseProfileTitleName(profileTitle: String?): String? {
            if (profileTitle.isNullOrBlank()) {
                return null
            }
            return decodeHeaderValue(profileTitle, fallback = profileTitle)
        }

        private fun parseContentDispositionName(contentDisposition: String?): String? {
            if (contentDisposition.isNullOrBlank()) {
                return null
            }
            val match = FILENAME_REGEX.find(contentDisposition) ?: return null
            val charset = match.groupValues[1].takeIf { it.isNotBlank() }
            val encodedFilename = match.groupValues[2]

            return decodeHeaderValue(encodedFilename, charset = charset, fallback = null)
                ?: decodeHeaderValue(encodedFilename, fallback = encodedFilename)
        }

        private fun decodeHeaderValue(
            value: String,
            charset: String? = "UTF-8",
            fallback: String?
        ): String? {
            return try {
                URLDecoder.decode(value, charset ?: "UTF-8")
            } catch (e: Exception) {
                Log.d(TAG, "Failed to decode subscription header value with charset=$charset", e)
                fallback
            }
        }

        private fun String.removeKnownSubscriptionSuffix(): String {
            return removeSuffix(".yaml")
                .removeSuffix(".yml")
                .removeSuffix(".json")
        }
    }

    fun parse(content: String): SingBoxConfig? {
        for (parser in parsers) {
            if (parser.canParse(content)) {
                try {
                    val config = parser.parse(content)
                    if (config != null && !config.outbounds.isNullOrEmpty()) {
                        val deduplicatedOutbounds = deduplicateOutbounds(config.outbounds)
                        return config.copy(outbounds = deduplicatedOutbounds)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parser ${parser.javaClass.simpleName} failed", e)
                }
            }
        }
        return null
    }
}
