@file:Suppress("TooManyFunctions", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "LongMethod", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeConst")

package com.kunk.singbox.utils.parser

import android.util.Log
import com.google.gson.Gson
import com.kunk.singbox.model.EchConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TransportConfig
import com.kunk.singbox.model.UInt32JsonAdapter
import com.kunk.singbox.model.V2RAY_TRANSPORT_TYPES

@Suppress("TooManyFunctions", "LargeClass")
class NodeLinkParser(internal val gson: Gson) {
    companion object {
        internal val SUPPORTED_LINK_PREFIXES = listOf(
            "vmess://", "vless://", "ss://", "trojan://",
            "hysteria2://", "hy2://", "hysteria://", "tuic://", "anytls://",
            "naive://", "naive+https://", "wireguard://", "wg://", "ssh://",
            "socks4://", "socks4a://", "socks5://", "socks://", "http://", "https://"
        )
        internal fun isSupportedLink(link: String): Boolean {
            val value = link.trim()
            val prefix = SUPPORTED_LINK_PREFIXES.firstOrNull { value.startsWith(it, ignoreCase = true) }
                ?: return false
            if (prefix != "http://" && prefix != "https://") return true
            return runCatching {
                val uri = java.net.URI(value)
                !uri.userInfo.isNullOrBlank() || uri.port > 0 || !uri.fragment.isNullOrBlank()
            }.getOrDefault(false)
        }
    }

    internal fun firstParam(params: Map<String, String>, vararg keys: String): String? {
        return keys.firstNotNullOfOrNull { key ->
            params.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
        }
    }

    internal fun hasRequiredLinkFields(protocol: String, server: String?, credential: String?, port: Int): Boolean {
        if (server.isNullOrBlank()) {
            Log.w("NodeLinkParser", "$protocol link missing server")
            return false
        }
        if (credential.isNullOrBlank()) {
            Log.w("NodeLinkParser", "$protocol link missing credential")
            return false
        }
        if (port !in 1..65535) {
            Log.w("NodeLinkParser", "$protocol link has invalid port: $port")
            return false
        }
        return true
    }

    internal fun parseBooleanFlag(value: String?): Boolean? {
        val normalized = value?.trim()?.lowercase() ?: return null
        return when (normalized) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }
    }

    internal fun buildXhttpTransport(
        host: String?,
        path: String?,
        value: (String) -> Any?
    ): TransportConfig {
        fun longValue(name: String): Long? = when (val raw = value(name)) {
            is Number -> raw.toLong()
            else -> raw?.toString()?.toLongOrNull()
        }

        return TransportConfig(
            type = "xhttp",
            path = path.orEmpty().ifBlank { "/" },
            host = parseHostList(host),
            mode = value("mode")?.toString(),
            xPaddingBytes = value("xPaddingBytes")?.toString(),
            scMaxEachPostBytes = longValue("scMaxEachPostBytes"),
            scMinPostsIntervalMs = longValue("scMinPostsIntervalMs"),
            scMaxBufferedPosts = longValue("scMaxBufferedPosts"),
            noGRPCHeader = parseBooleanFlag(value("noGRPCHeader")?.toString()),
            noSSEHeader = parseBooleanFlag(value("noSSEHeader")?.toString())
        )
    }

    internal fun parseHostList(value: String?): List<String>? {
        if (value.isNullOrBlank()) return null
        val hosts = value.split(',').map { it.trim() }.filter { it.isNotBlank() }
        return hosts.takeIf { it.isNotEmpty() }
    }

    internal fun parseSingleHost(value: String?): List<String>? {
        return parseHostList(value)?.firstOrNull()?.let { listOf(it) }
    }

    internal fun isUnsupportedV2RayTransport(type: String): Boolean =
        type.isNotEmpty() && type !in V2RAY_TRANSPORT_TYPES

    internal fun parseWireGuardLocalAddress(params: Map<String, String>): List<String>? {
        return firstParam(params, "address", "local_address", "localAddress", "ip")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
    }

    internal fun parseEchConfig(params: Map<String, String>): EchConfig? {
        val echParam = firstParam(params, "ech") ?: return null
        val disabled = echParam.isBlank() ||
            echParam.equals("0", ignoreCase = true) ||
            echParam.equals("false", ignoreCase = true)
        val queryServerName = echParam
            .substringBefore('+')
            .substringBefore(' ')
            .takeIf { it.contains(".") }
        val dnsServerSource = if (echParam.contains("+")) {
            echParam.substringAfter('+')
        } else {
            echParam.substringAfter(' ', missingDelimiterValue = "")
        }
        val dnsServer = dnsServerSource
            .substringBefore(' ')
            .takeIf { it.isNotBlank() && it.contains(".") }
        return if (disabled) {
            null
        } else {
            EchConfig(enabled = true, queryServerName = queryServerName, dnsServer = dnsServer)
        }
    }

    internal fun parseWebSocketPathConfig(rawPath: String?): NodeLinkParserWebSocketPathConfig? {
        if (rawPath.isNullOrBlank()) {
            return NodeLinkParserWebSocketPathConfig(path = "/", maxEarlyData = null, earlyDataHeaderName = null)
        }

        val trimmed = rawPath.trim().ifEmpty { "/" }
        val normalized = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        val questionIndex = normalized.indexOf('?')
        if (questionIndex == -1) {
            return NodeLinkParserWebSocketPathConfig(path = normalized, maxEarlyData = null, earlyDataHeaderName = null)
        }

        val basePath = normalized.substring(0, questionIndex).ifEmpty { "/" }
        val queryParams = parseQueryParams(normalized.substring(questionIndex + 1))
        val rawMaxEarlyData = firstParam(queryParams, "ed")
        val maxEarlyData = rawMaxEarlyData?.toLongOrNull()?.let { value ->
            runCatching { UInt32JsonAdapter.requireValue(value) }.getOrNull()
        }
        if (rawMaxEarlyData != null && maxEarlyData == null) return null
        val earlyDataHeaderName = if (maxEarlyData != null) "Sec-WebSocket-Protocol" else null
        return NodeLinkParserWebSocketPathConfig(
            path = basePath,
            maxEarlyData = maxEarlyData,
            earlyDataHeaderName = earlyDataHeaderName
        )
    }

    internal fun parseQueryParams(query: String?): Map<String, String> {
        if (query.isNullOrBlank()) return emptyMap()
        val params = mutableMapOf<String, String>()
        query.split("&").forEach { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) {
                params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
            }
        }
        return params
    }

    internal fun parseTuicCredentials(userInfo: String, params: Map<String, String>): NodeLinkParserTuicCredentials {
        val colonIndex = userInfo.indexOf(':')
        val uuid = if (colonIndex > 0) userInfo.substring(0, colonIndex) else userInfo
        val password = if (colonIndex > 0) {
            userInfo.substring(colonIndex + 1)
        } else {
            params["password"] ?: params["token"] ?: uuid
        }
        return NodeLinkParserTuicCredentials(uuid = uuid, password = password)
    }

    internal fun buildTuicTlsOptions(server: String?, params: Map<String, String>): NodeLinkParserTuicTlsOptions {
        val disableSni = parseBooleanFlag(firstParam(params, "disable_sni", "disableSni")) == true
        val serverName = defaultTlsServerName(explicitServerName = firstParam(params, "sni"), server = server)
        val insecure = listOf("insecure", "allow_insecure", "allowInsecure").any {
            params[it] == "1"
        }
        val alpn = params["alpn"]?.split(",")?.filter { it.isNotBlank() }
        val fingerprint = params["fp"]?.takeIf { it.isNotBlank() }
        return NodeLinkParserTuicTlsOptions(
            disableSni = disableSni,
            serverName = serverName,
            insecure = insecure,
            alpn = alpn,
            fingerprint = fingerprint
        )
    }

    internal fun buildTuicTransportOptions(params: Map<String, String>): NodeLinkParserTuicTransportOptions {
        return NodeLinkParserTuicTransportOptions(
            congestionControl = params["congestion_control"] ?: params["congestion"],
            udpRelayMode = params["udp_relay_mode"] ?: "native",
            zeroRtt = params["reduce_rtt"] == "1" || params["zero_rtt"] == "1"
        )
    }

    internal fun isIpLiteral(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val candidate = value.trim().removeSurrounding("[", "]")
        val ipv4Pattern = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        return ipv4Pattern.matches(candidate) || candidate.contains(":")
    }

    internal fun defaultTlsServerName(
        explicitServerName: String?,
        primaryFallback: String? = null,
        server: String?
    ): String? {
        val explicit = explicitServerName?.takeIf { it.isNotBlank() }
        if (explicit != null) return explicit

        val fallback = primaryFallback?.takeIf { it.isNotBlank() }
        if (fallback != null) return fallback

        return server?.takeIf { it.isNotBlank() && !isIpLiteral(it) }
    }

    internal fun sanitizeUri(link: String): String {
        var result = link

        val hashIndex = result.indexOf('#')
        var fragment = ""
        if (hashIndex != -1) {
            fragment = result.substring(hashIndex + 1)
            result = result.substring(0, hashIndex)
        }

        val questionIndex = result.indexOf('?')
        if (questionIndex != -1) {
            val base = result.substring(0, questionIndex)
            val query = result.substring(questionIndex + 1)

            val cleanedQuery = query
                .replace(Regex("\\s*=\\s*"), "=")
                .replace(Regex("\\s*&\\s*"), "&")
                .replace(" ", "%20")
            result = "$base?$cleanedQuery"
        }

        if (fragment.isNotEmpty()) {
            result = "$result#${fragment.replace(" ", "%20")}"
        }

        return result
    }

    internal fun normalizeInputLink(link: String): String {
        val trimmed = link.trim().trim('`', '"', '\'')
        val prefixMatch = Regex("^[A-Za-z][A-Za-z0-9+.-]*://\\S+")
            .find(trimmed)
            ?.value
            ?: trimmed
        return prefixMatch.trimEnd(',', '，', ';', '；', '。')
    }

    fun parse(link: String): Outbound? {
        val normalizedLink = normalizeInputLink(link)
        return when {
            normalizedLink.startsWith("ss://") -> parseShadowsocksLink(normalizedLink)
            normalizedLink.startsWith("vmess://") -> parseVMessLink(normalizedLink)
            normalizedLink.startsWith("vless://") -> parseVLessLink(normalizedLink)
            normalizedLink.startsWith("trojan://") -> parseTrojanLink(normalizedLink)
            normalizedLink.startsWith("hysteria2://") ||
                normalizedLink.startsWith("hy2://") -> parseHysteria2Link(normalizedLink)
            normalizedLink.startsWith("hysteria://") -> parseHysteriaLink(normalizedLink)
            normalizedLink.startsWith("anytls://") -> parseAnyTLSLink(normalizedLink)
            normalizedLink.startsWith("naive://") ||
                normalizedLink.startsWith("naive+https://") -> parseNaiveLink(normalizedLink)
            normalizedLink.startsWith("tuic://") -> parseTuicLink(normalizedLink)
            normalizedLink.startsWith("https://") -> parseHttpLink(normalizedLink, useTls = true)
            normalizedLink.startsWith("http://") -> parseHttpLink(normalizedLink, useTls = false)
            normalizedLink.startsWith("socks5://") ||
                normalizedLink.startsWith("socks4a://") ||
                normalizedLink.startsWith("socks4://") ||
                normalizedLink.startsWith("socks://") -> parseSocks5Link(normalizedLink)
            normalizedLink.startsWith("wireguard://") ||
                normalizedLink.startsWith("wg://") -> parseWireGuardLink(normalizedLink)
            normalizedLink.startsWith("ssh://") -> parseSSHLink(normalizedLink)
            else -> null
        }
    }

    internal fun parseShadowsocksLink(link: String): Outbound? {
        try {
            var uriString = link.removePrefix("ss://")

            // 1. Extract Name (Fragment)
            val nameIndex = uriString.lastIndexOf('#')
            val name = if (nameIndex > 0) {
                val tag = uriString.substring(nameIndex + 1)
                uriString = uriString.substring(0, nameIndex)
                try {
                    java.net.URLDecoder.decode(tag, "UTF-8")
                } catch (e: Exception) {
                    tag
                }
            } else "SS Node"

            // 2. Extract Query Parameters
            var params = mutableMapOf<String, String>()
            val questionIndex = uriString.indexOf('?')
            if (questionIndex > 0) {
                val query = uriString.substring(questionIndex + 1)
                uriString = uriString.substring(0, questionIndex)

                query.split("&").forEach {
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2) {
                        try {
                            params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                        } catch (e: Exception) {
                            params[parts[0]] = parts[1]
                        }
                    }
                }
            }

            var server: String
            var port: Int
            var method: String
            var password: String

            val atIndex = uriString.lastIndexOf('@')
            if (atIndex > 0) {
                // SIP002 Format: userinfo@host:port
                val userInfoBase64 = uriString.substring(0, atIndex)
                val serverPart = uriString.substring(atIndex + 1)

                // Try decode Base64, fallback to raw if it contains colon (common non-standard format)
                var userInfo = tryDecodeBase64(userInfoBase64)
                if (userInfo == null && userInfoBase64.contains(":")) {
                    // Non-Base64 format: method:password may be URL-encoded
                    userInfo = try {
                        java.net.URLDecoder.decode(userInfoBase64, "UTF-8")
                    } catch (e: Exception) {
                        userInfoBase64
                    }
                }
                if (userInfo == null) return null

                val methodPwd = userInfo.split(":", limit = 2)
                method = methodPwd[0]
                password = methodPwd.getOrElse(1) { "" }

                val portParts = parseHostPort(serverPart)
                server = portParts.first
                port = portParts.second
            } else {
                // Legacy Format: Base64(method:password@host:port)
                // Also support raw method:password@host:port
                var decoded = tryDecodeBase64(uriString)
                if (decoded == null && uriString.contains("@")) {
                    decoded = uriString
                }
                if (decoded == null) return null

                val lastAt = decoded.lastIndexOf('@')
                if (lastAt == -1) return null

                val userPart = decoded.substring(0, lastAt)
                val hostPart = decoded.substring(lastAt + 1)

                val methodPwd = userPart.split(":", limit = 2)
                method = methodPwd[0]
                password = methodPwd.getOrElse(1) { "" }

                // Check parameters in hostPart
                var cleanHostPart = hostPart
                val qIndex = cleanHostPart.indexOf('?')
                if (qIndex > 0) {
                    val query = cleanHostPart.substring(qIndex + 1)
                    cleanHostPart = cleanHostPart.substring(0, qIndex)

                    query.split("&").forEach {
                        val parts = it.split("=", limit = 2)
                        if (parts.size == 2) {
                            try {
                                params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                            } catch (e: Exception) {
                                params[parts[0]] = parts[1]
                            }
                        }
                    }
                }

                val portParts = parseHostPort(cleanHostPart)
                server = portParts.first
                port = portParts.second
            }

            // 3. Process Plugin
            // Sing-box shadowsocks inbound/outbound does not support native transport/tls fields directly.
            // It relies on external plugins (v2ray-plugin, obfs-local) if transport wrapping is needed.
            // So we parse and pass the plugin fields as is.

            var pluginStr = params["plugin"]
            var pluginOptsStr: String? = null

            if (pluginStr != null) {
                // Format: name;opts (SIP002)
                // If the link is ss://...?plugin=v2ray-plugin%3Bmode%3Dwebsocket...
                // It decodes to "v2ray-plugin;mode=websocket..."

                val semiIndex = pluginStr.indexOf(';')
                if (semiIndex > 0) {
                    val namePart = pluginStr.substring(0, semiIndex)
                    val optsPart = pluginStr.substring(semiIndex + 1)

                    pluginStr = namePart
                    pluginOptsStr = optsPart
                } else {
                    // No options, just plugin name
                    pluginOptsStr = null
                }
            }

            return Outbound(
                type = "shadowsocks",
                tag = name,
                server = server,
                serverPort = port,
                method = method.lowercase(),
                password = password,
                plugin = pluginStr,
                pluginOpts = pluginOptsStr
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse SS link", e)
        }
        return null
    }

    internal fun tryDecodeBase64(content: String): String? {

        val cleaned = content.trim()
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
        val padded = padBase64(cleaned)

        decodeWithJvmBase64(padded, urlSafe = true)?.let { decoded ->
            if (decoded.isNotEmpty()) return String(decoded, Charsets.UTF_8)
        }
        decodeWithJvmBase64(padded, urlSafe = false)?.let { decoded ->
            if (decoded.isNotEmpty()) return String(decoded, Charsets.UTF_8)
        }

        val candidates = arrayOf(
            android.util.Base64.DEFAULT,
            android.util.Base64.NO_WRAP,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
        for (flags in candidates) {
            try {
                val decoded = android.util.Base64.decode(padded, flags)
                // Basic validation: string should not contain excessive control chars
                if (decoded.isNotEmpty()) {
                    return String(decoded, Charsets.UTF_8)
                }
            } catch (_: Exception) {
                // Continue
            }
        }
        return null
    }

    internal fun padBase64(content: String): String {
        return when (content.length % 4) {
            2 -> content + "=="
            3 -> content + "="
            else -> content
        }
    }

    internal fun decodeWithJvmBase64(content: String, urlSafe: Boolean): ByteArray? {
        return try {
            val base64Class = Class.forName("java.util.Base64")
            val methodName = if (urlSafe) "getUrlDecoder" else "getDecoder"
            val decoder = base64Class.getMethod(methodName).invoke(null)
            decoder.javaClass.getMethod("decode", String::class.java).invoke(decoder, content) as? ByteArray
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: ClassCastException) {
            null
        }
    }

    internal fun parseHostPort(hostPort: String): Pair<String, Int> {
        val lastColon = hostPort.lastIndexOf(':')
        val lastBracket = hostPort.lastIndexOf(']')

        var server: String
        var port: Int = 8388

        if (lastColon > lastBracket) {
            server = hostPort.substring(0, lastColon)
            port = hostPort.substring(lastColon + 1).toIntOrNull() ?: 8388
        } else {
            server = hostPort
        }

        if (server.startsWith("[") && server.endsWith("]")) {
            server = server.substring(1, server.length - 1)
        }
        return server to port
    }
}
