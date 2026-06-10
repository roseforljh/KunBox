package com.kunk.singbox.utils.parser

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.model.TlsConfig

/**
 */
class SingBoxParser(private val gson: Gson) : SubscriptionParser {
    companion object {
        private const val TAG = "SingBoxParser"
        private val OUTBOUND_LIST_TYPE = object : TypeToken<List<Outbound>>() {}.type
    }

    override fun canParse(content: String): Boolean {
        val trimmed = content.trim()
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
    }

    override fun parse(content: String): SingBoxConfig? {
        val trimmed = content.trim()

        if (trimmed.startsWith("[")) {
            return parseAsOutboundArray(trimmed)
        }

        return parseAsConfigObject(trimmed)
    }

    private fun normalizeOutbounds(outbounds: List<Outbound>): List<Outbound> {
        return outbounds.map(::normalizeOutbound)
    }

    private fun normalizeOutbounds(outbounds: List<Outbound>, rawElements: JsonArray): List<Outbound> {
        return outbounds.mapIndexed { index, outbound ->
            val rawElement = if (index < rawElements.size()) rawElements[index] else null
            val raw = rawElement?.takeIf { it.isJsonObject }?.asJsonObject
            normalizeOutbound(outbound, raw)
        }
    }

    private fun normalizeOutbound(outbound: Outbound, rawObject: JsonObject? = null): Outbound {
        val normalizedTls = normalizeTlsAliases(outbound.tls, rawObject?.getAsJsonObject("tls"))
        return if (normalizedTls == outbound.tls) {
            outbound
        } else {
            outbound.copy(tls = normalizedTls)
        }
    }

    private fun normalizeTlsAliases(tls: TlsConfig?, rawTls: JsonObject? = null): TlsConfig? {
        if (tls == null && rawTls == null) return null
        val baseTls = tls ?: TlsConfig()
        return baseTls.copy(
            ca = baseTls.ca ?: readJsonAlias(rawTls, "ca", "ca-cert", "ca_cert", "caPem", "ca_pem"),
            caPath = baseTls.caPath ?: readJsonAlias(rawTls, "ca_path", "ca-path"),
            certificate = baseTls.certificate ?: readJsonAlias(
                rawTls,
                "certificate",
                "cert",
                "client-cert",
                "client_cert"
            ),
            certificatePath = baseTls.certificatePath ?: readJsonAlias(
                rawTls,
                "certificate_path",
                "certificate-path",
                "cert_path",
                "cert-path",
                "client-cert-path",
                "client_cert_path"
            ),
            key = baseTls.key ?: readJsonAlias(rawTls, "key", "client-key", "client_key"),
            keyPath = baseTls.keyPath ?: readJsonAlias(
                rawTls,
                "key_path",
                "key-path",
                "client-key-path",
                "client_key_path"
            )
        )
    }

    private fun readJsonAlias(rawObject: JsonObject?, vararg aliases: String): String? {
        if (rawObject == null) return null
        return aliases.firstNotNullOfOrNull { alias ->
            rawObject.get(alias)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        }
    }

    /**
     */
    private fun parseAsOutboundArray(content: String): SingBoxConfig? {
        return try {
            val rawElements = JsonParser.parseString(content).asJsonArray
            val outbounds: List<Outbound> = gson.fromJson(content, OUTBOUND_LIST_TYPE)
            if (outbounds.isNotEmpty()) {
                SingBoxConfig(outbounds = normalizeOutbounds(outbounds, rawElements))
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse as outbound array: ${e.message}")
            null
        }
    }

    /**
     */
    @Suppress("NestedBlockDepth")
    private fun parseAsConfigObject(content: String): SingBoxConfig? {
        return try {
            val jsonObject = JsonParser.parseString(content).asJsonObject

            val outboundsElement = jsonObject.get("outbounds") ?: jsonObject.get("proxies")

            if (outboundsElement != null && outboundsElement.isJsonArray) {
                val rawElements = outboundsElement.asJsonArray
                val outbounds: List<Outbound> = gson.fromJson(rawElements, OUTBOUND_LIST_TYPE)
                if (outbounds.isNotEmpty()) {
                    val normalizedOutbounds = normalizeOutbounds(outbounds, rawElements)
                    return if (jsonObject.has("outbounds")) {
                        gson.fromJson(jsonObject, SingBoxConfig::class.java).copy(outbounds = normalizedOutbounds)
                    } else {
                        SingBoxConfig(outbounds = normalizedOutbounds)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract outbounds from JSON: ${e.message}")
            null
        }
    }
}

/**
 */
class Base64Parser(private val nodeParser: (String) -> Outbound?) : SubscriptionParser {
    private val linkPrefixes = listOf(
        "vmess://",
        "vless://",
        "ss://",
        "ssr://",
        "trojan://",
        "hysteria://",
        "hysteria2://",
        "hy2://",
        "tuic://",
        "anytls://",
        "naive://",
        "naive+https://",
        "wireguard://",
        "ssh://",
        "socks5://",
        "socks://",
        "http://",
        "https://"
    )

    override fun canParse(content: String): Boolean {
        val trimmed = content.trim()
        return !trimmed.startsWith("{") && !trimmed.startsWith("proxies:") && !trimmed.startsWith("proxy-groups:")
    }

    override fun parse(content: String): SingBoxConfig? {
        android.util.Log.d("Base64Parser", "Parsing content, length: ${content.length}, starts with: ${content.take(20)}")
        val trimmed = content.trim()

        val isAlreadyLink = linkPrefixes.any { trimmed.startsWith(it) }
        val decoded = if (isAlreadyLink) trimmed else (tryDecodeBase64(trimmed) ?: trimmed)
        val normalized = decoded
            .replace("\u2028", "\n")
            .replace("\u2029", "\n")
        val candidates = normalized.lines().flatMap { extractLinksFromLine(it) }
            .ifEmpty { normalized.split(Regex("\\s+")).flatMap { extractLinksFromLine(it) } }
        android.util.Log.d("Base64Parser", "Found ${candidates.size} link candidates")
        val outbounds = mutableListOf<Outbound>()

        for (candidate in candidates) {
            android.util.Log.d("Base64Parser", "Trying to parse candidate: ${candidate.take(30)}...")
            val outbound = nodeParser(candidate)
            if (outbound != null) {
                android.util.Log.d("Base64Parser", "Successfully parsed: ${outbound.tag}")
                outbounds.add(outbound)
            } else {
                android.util.Log.w("Base64Parser", "Failed to parse candidate")
            }
        }

        android.util.Log.d("Base64Parser", "Total outbounds parsed: ${outbounds.size}")
        if (outbounds.isEmpty()) return null

        return SingBoxConfig(outbounds = outbounds)
    }

    private fun extractLinksFromLine(line: String): List<String> {
        val normalized = line.trim()
            .trimStart('\uFEFF', '\u200B', '\u200C', '\u200D')
            .removePrefix("- ")
            .removePrefix("·")
            .trim()
            .trim('`', '"', '\'')

        if (normalized.isBlank()) return emptyList()

        val sortedPrefixes = linkPrefixes.sortedByDescending { it.length }

        val linkPositions = mutableListOf<Pair<Int, String>>()
        val usedPositions = mutableSetOf<Int>()

        for (prefix in sortedPrefixes) {
            var searchFrom = 0
            while (searchFrom < normalized.length) {
                val index = normalized.indexOf(prefix, searchFrom)
                if (index < 0) break

                val isOverlapped = usedPositions.any { usedPos ->
                    val matchedPrefixLength = sortedPrefixes.firstOrNull {
                        normalized.substring(usedPos).startsWith(it)
                    }?.length ?: return@any false
                    index >= usedPos && index < usedPos + matchedPrefixLength
                }

                if (!isOverlapped) {
                    linkPositions.add(index to prefix)
                    usedPositions.add(index)
                }
                searchFrom = index + 1
            }
        }

        if (linkPositions.isEmpty()) return emptyList()

        val sortedPositions = linkPositions.sortedBy { it.first }

        val results = mutableListOf<String>()
        for (i in sortedPositions.indices) {
            val start = sortedPositions[i].first
            val end = if (i + 1 < sortedPositions.size) sortedPositions[i + 1].first else normalized.length
            var candidate = normalized.substring(start, end).trim()
            candidate = candidate.trimEnd(',', '，', ';', '；', '.', '。', ')', '）', ']', '】', '}', '`', '"', '\'')
            if (candidate.isNotBlank()) {
                results.add(candidate)
            }
        }
        return results
    }

    private fun tryDecodeBase64(content: String): String? {
        val cleaned = content.replace(Regex("\\s+"), "")
        val padded = padBase64(cleaned)

        val jvmDecoded = decodeWithJvmBase64(padded, urlSafe = false)
            ?: decodeWithJvmBase64(padded, urlSafe = true)
        if (jvmDecoded != null) return jvmDecoded

        val candidates = arrayOf(
            Base64.DEFAULT,
            Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        for (flags in candidates) {
            try {
                val decoded = Base64.decode(padded, flags)
                decodeSubscriptionText(decoded)?.let { return it }
            } catch (e: Exception) {
                Log.d("Base64Parser", "Base64 decode failed for flags=$flags: ${e.message}")
            }
        }
        return null
    }

    private fun padBase64(content: String): String {
        return when (content.length % 4) {
            2 -> content + "=="
            3 -> content + "="
            else -> content
        }
    }

    private fun decodeWithJvmBase64(content: String, urlSafe: Boolean): String? {
        return try {
            val decoded = if (urlSafe) {
                java.util.Base64.getUrlDecoder().decode(content)
            } else {
                java.util.Base64.getDecoder().decode(content)
            }
            decodeSubscriptionText(decoded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun decodeSubscriptionText(decoded: ByteArray?): String? {
        if (decoded == null) return null
        val text = String(decoded, Charsets.UTF_8)
        return if (text.isNotBlank() && looksLikeSubscriptionText(text)) text else null
    }

    private fun looksLikeSubscriptionText(text: String): Boolean {
        return text.contains("://") ||
            text.contains("\n") ||
            text.contains("\r") ||
            text.all { it.isLetterOrDigit() || it.isWhitespace() || "=/-_:.".contains(it) }
    }
}
