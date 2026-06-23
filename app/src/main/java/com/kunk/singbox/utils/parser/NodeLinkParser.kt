package com.kunk.singbox.utils.parser

import android.util.Log
import com.google.gson.Gson
import com.kunk.singbox.model.EchConfig
import com.kunk.singbox.model.ObfsConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.RealityConfig
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import com.kunk.singbox.model.UtlsConfig
import com.kunk.singbox.model.WireGuardPeer

/**
 */
@Suppress("TooManyFunctions", "LargeClass")
class NodeLinkParser(private val gson: Gson) {
    protected fun firstParam(params: Map<String, String>, vararg keys: String): String? {
        return keys.firstNotNullOfOrNull { key ->
            params.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
        }
    }

    protected fun hasRequiredLinkFields(protocol: String, server: String?, credential: String?, port: Int): Boolean {
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

    protected fun parseBooleanFlag(value: String?): Boolean? {
        val normalized = value?.trim()?.lowercase() ?: return null
        return when (normalized) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }
    }

    protected fun parseHostList(value: String?): List<String>? {
        if (value.isNullOrBlank()) return null
        val hosts = value.split(',').map { it.trim() }.filter { it.isNotBlank() }
        return hosts.takeIf { it.isNotEmpty() }
    }

    protected fun parseSingleHost(value: String?): List<String>? {
        return parseHostList(value)?.firstOrNull()?.let { listOf(it) }
    }

    protected fun parseWireGuardLocalAddress(params: Map<String, String>): List<String>? {
        return firstParam(params, "address", "local_address", "localAddress", "ip")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
    }

    protected fun parseEchConfig(params: Map<String, String>): EchConfig? {
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

    protected fun parseWebSocketPathConfig(rawPath: String?): NodeLinkParserWebSocketPathConfig {
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
        val maxEarlyData = firstParam(queryParams, "ed")?.toIntOrNull()
        val earlyDataHeaderName = if (maxEarlyData != null) "Sec-WebSocket-Protocol" else null
        return NodeLinkParserWebSocketPathConfig(
            path = basePath,
            maxEarlyData = maxEarlyData,
            earlyDataHeaderName = earlyDataHeaderName
        )
    }

    protected fun parseQueryParams(query: String?): Map<String, String> {
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

    protected fun parseTuicCredentials(userInfo: String, params: Map<String, String>): NodeLinkParserTuicCredentials {
        val colonIndex = userInfo.indexOf(':')
        val uuid = if (colonIndex > 0) userInfo.substring(0, colonIndex) else userInfo
        val password = if (colonIndex > 0) {
            userInfo.substring(colonIndex + 1)
        } else {
            params["password"] ?: params["token"] ?: uuid
        }
        return NodeLinkParserTuicCredentials(uuid = uuid, password = password)
    }

    protected fun buildTuicTlsOptions(server: String?, params: Map<String, String>): NodeLinkParserTuicTlsOptions {
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

    protected fun buildTuicTransportOptions(params: Map<String, String>): NodeLinkParserTuicTransportOptions {
        return NodeLinkParserTuicTransportOptions(
            congestionControl = params["congestion_control"] ?: params["congestion"],
            udpRelayMode = params["udp_relay_mode"] ?: "native",
            zeroRtt = params["reduce_rtt"] == "1" || params["zero_rtt"] == "1"
        )
    }

    protected fun isIpLiteral(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val candidate = value.trim().removeSurrounding("[", "]")
        val ipv4Pattern = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        return ipv4Pattern.matches(candidate) || candidate.contains(":")
    }

    protected fun defaultTlsServerName(
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

    /**
     */

    protected fun sanitizeUri(link: String): String {
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

    protected fun normalizeInputLink(link: String): String {
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
                normalizedLink.startsWith("socks://") -> parseSocks5Link(normalizedLink)
            normalizedLink.startsWith("wireguard://") ||
                normalizedLink.startsWith("wg://") -> parseWireGuardLink(normalizedLink)
            normalizedLink.startsWith("ssh://") -> parseSSHLink(normalizedLink)
            else -> null
        }
    }

    protected fun parseShadowsocksLink(link: String): Outbound? {
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

    protected fun tryDecodeBase64(content: String): String? {

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

    protected fun padBase64(content: String): String {
        return when (content.length % 4) {
            2 -> content + "=="
            3 -> content + "="
            else -> content
        }
    }

    protected fun decodeWithJvmBase64(content: String, urlSafe: Boolean): ByteArray? {
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

    protected fun parseHostPort(hostPort: String): Pair<String, Int> {
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

    protected fun parseVMessLink(link: String): Outbound? {
        try {
            val base64Part = link.removePrefix("vmess://").trim()
            Log.d("NodeLinkParser", "Parsing VMess, base64 length: ${base64Part.length}")
            val decoded = tryDecodeBase64(base64Part)
            if (decoded == null) {
                Log.e("NodeLinkParser", "Failed to decode VMess base64, first 50 chars: ${base64Part.take(50)}")
                return null
            }
            Log.d("NodeLinkParser", "VMess decoded successfully, JSON length: ${decoded.length}")

            val json = gson.fromJson(decoded, Map::class.java)

            val add = json["add"] as? String ?: ""
            val port = (json["port"] as? String)?.toIntOrNull() ?: (json["port"] as? Double)?.toInt() ?: 443
            val id = json["id"] as? String ?: ""
            val aid = (json["aid"] as? String)?.toIntOrNull() ?: (json["aid"] as? Double)?.toInt() ?: 0
            val net = (json["net"] as? String ?: "tcp").lowercase()
            val type = json["type"] as? String ?: "none"
            val host = json["host"] as? String ?: ""
            val path = json["path"] as? String ?: ""
            val tls = json["tls"] as? String ?: ""
            val sni = json["sni"] as? String ?: ""
            val ps = json["ps"] as? String ?: "VMess Node"
            val fp = json["fp"] as? String ?: ""

            val tlsConfig = if (tls == "tls") {
                TlsConfig(
                    enabled = true,
                    serverName = if (sni.isNotBlank()) sni else if (host.isNotBlank()) host else add,
                    utls = if (fp.isNotBlank()) UtlsConfig(enabled = true, fingerprint = fp) else null
                )
            } else null

            val transport = when (net) {
                "ws" -> TransportConfig(
                    type = "ws",
                    path = if (path.isBlank()) "/" else path,
                    headers = if (host.isNotBlank()) mapOf("Host" to host) else null
                )
                "tcp" -> if (type == "http") {
                    TransportConfig(
                        type = "http",
                        host = parseHostList(host),
                        path = path
                    )
                } else {
                    null
                }
                "grpc" -> TransportConfig(
                    type = "grpc",
                    serviceName = path
                )
                "h2", "http" -> TransportConfig(
                    type = "http",
                    host = parseHostList(host),
                    path = path
                )
                "httpupgrade" -> TransportConfig(
                    type = "httpupgrade",
                    path = if (path.isBlank()) "/" else path,
                    host = parseSingleHost(host)
                )
                "xhttp", "splithttp" -> TransportConfig(
                    type = "xhttp",
                    path = if (path.isBlank()) "/" else path,
                    host = parseHostList(host),
                    mode = json["mode"] as? String,
                    xPaddingBytes = json["xPaddingBytes"] as? String,
                    scMaxEachPostBytes = (json["scMaxEachPostBytes"] as? String)?.toLongOrNull()
                        ?: (json["scMaxEachPostBytes"] as? Double)?.toLong(),
                    scMinPostsIntervalMs = (json["scMinPostsIntervalMs"] as? String)?.toLongOrNull()
                        ?: (json["scMinPostsIntervalMs"] as? Double)?.toLong(),
                    scMaxBufferedPosts = (json["scMaxBufferedPosts"] as? String)?.toLongOrNull()
                        ?: (json["scMaxBufferedPosts"] as? Double)?.toLong(),
                    noGRPCHeader = parseBooleanFlag(json["noGRPCHeader"]?.toString()),
                    noSSEHeader = parseBooleanFlag(json["noSSEHeader"]?.toString())
                )
                else -> null
            }

            if (aid != 0) {
                Log.w(
                    "NodeLinkParser",
                    "VMess node '$ps' uses legacy MD5 authentication (alterId=$aid). " +
                        "This protocol is insecure and vulnerable to replay attacks. " +
                        "Please migrate to AEAD (alterId=0) or " +
                        "use VLESS/XTLS."
                )
            }

            return Outbound(
                type = "vmess",
                tag = ps,
                server = add,
                serverPort = port,
                uuid = id,
                alterId = if (aid > 0) aid else null,
                security = "auto",
                tls = tlsConfig,
                transport = transport
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse VMess link", e)
        }
        return null
    }

    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod")
    protected fun parseVLessLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(sanitizeUri(link))
            val name = java.net.URLDecoder.decode(uri.fragment ?: "VLESS Node", "UTF-8")
            val uuid = uri.userInfo
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 443
            if (!hasRequiredLinkFields("VLESS", server, uuid, port)) return null

            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }

            val hostParam = firstParam(params, "host")
            val explicitSni = firstParam(params, "sni")?.takeIf { it.isNotBlank() }
            val transportType = firstParam(params, "type")?.lowercase() ?: "tcp"
            val securityRaw = (firstParam(params, "security") ?: "none").lowercase()
            val tlsLikeTransport = transportType in setOf("ws", "grpc", "xhttp", "splithttp", "httpupgrade")
            val shouldInferTls = explicitSni != null || (port == 443 && hostParam != null && tlsLikeTransport)
            // 很多机场生成的 VLESS 分享链接会省略 security=tls。
            // 出现 sni，或 443 端口上的常见 HTTP 类传输带 Host 时，按 TLS 处理。
            val security = if (securityRaw == "none" && shouldInferTls) {
                "tls"
            } else {
                securityRaw
            }
            val sni = defaultTlsServerName(
                explicitServerName = explicitSni,
                primaryFallback = hostParam,
                server = server
            )
            val insecure = parseBooleanFlag(firstParam(params, "allowInsecure", "insecure")) == true
            val fingerprint = firstParam(params, "fp")?.takeIf { it.isNotBlank() }
            val alpnList = firstParam(params, "alpn")?.split(",")?.filter { it.isNotBlank() }
            val flow = firstParam(params, "flow")?.takeIf { it.isNotBlank() }
            val packetEncoding = firstParam(params, "packetEncoding", "packet-encoding")
                ?.takeIf { it.isNotBlank() }
            val encryption = firstParam(params, "encryption")
                ?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }

            val echConfig = parseEchConfig(params)

            val tlsConfig = when (security) {
                "tls" -> TlsConfig(
                    enabled = true,
                    serverName = sni,
                    insecure = insecure,
                    alpn = alpnList,
                    utls = (fingerprint ?: "chrome").let { UtlsConfig(enabled = true, fingerprint = it) },
                    ech = echConfig
                )
                "reality" -> TlsConfig(
                    enabled = true,
                    serverName = sni,
                    insecure = insecure,
                    alpn = alpnList,
                    reality = RealityConfig(
                        enabled = true,
                        publicKey = firstParam(params, "pbk"),
                        shortId = firstParam(params, "sid")
                    ),
                    utls = (fingerprint ?: "chrome").let { UtlsConfig(enabled = true, fingerprint = it) },
                    ech = echConfig
                )
                else -> null
            }

            val transport = when (transportType) {
                "ws" -> {
                    val webSocketPathConfig = parseWebSocketPathConfig(firstParam(params, "path"))
                    TransportConfig(
                        type = "ws",
                        path = webSocketPathConfig.path,
                        headers = hostParam?.let { mapOf("Host" to it) },
                        maxEarlyData = webSocketPathConfig.maxEarlyData,
                        earlyDataHeaderName = webSocketPathConfig.earlyDataHeaderName
                    )
                }
                "grpc" -> TransportConfig(
                    type = "grpc",
                    serviceName = firstParam(params, "serviceName", "sn") ?: ""
                )
                "httpupgrade" -> TransportConfig(
                    type = "httpupgrade",
                    path = firstParam(params, "path") ?: "/",
                    host = parseSingleHost(hostParam)
                )
                "xhttp", "splithttp" -> TransportConfig(
                    type = "xhttp",
                    path = firstParam(params, "path") ?: "/",
                    host = parseHostList(hostParam),
                    mode = firstParam(params, "mode"),
                    xPaddingBytes = firstParam(params, "xPaddingBytes", "x-padding-bytes"),
                    scMaxEachPostBytes = firstParam(params, "scMaxEachPostBytes")?.toLongOrNull(),
                    scMinPostsIntervalMs = firstParam(params, "scMinPostsIntervalMs")?.toLongOrNull(),
                    scMaxBufferedPosts = firstParam(params, "scMaxBufferedPosts")?.toLongOrNull(),
                    noGRPCHeader = parseBooleanFlag(firstParam(params, "noGRPCHeader")),
                    noSSEHeader = parseBooleanFlag(firstParam(params, "noSSEHeader"))
                )
                else -> null
            }

            return Outbound(
                type = "vless",
                tag = name,
                server = server,
                serverPort = port,
                uuid = uuid,
                flow = flow,
                tls = tlsConfig,
                transport = transport,
                packetEncoding = packetEncoding,
                encryption = encryption
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse VLESS link", e)
        }
        return null
    }

    protected fun parseTrojanLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(sanitizeUri(link))
            val name = java.net.URLDecoder.decode(uri.fragment ?: "Trojan Node", "UTF-8")
            val password = uri.userInfo
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 443
            if (!hasRequiredLinkFields("Trojan", server, password, port)) return null

            val params = parseQueryParams(uri.query)

            val hostParam = firstParam(params, "host")
            val sni = defaultTlsServerName(
                explicitServerName = firstParam(params, "sni"),
                primaryFallback = hostParam,
                server = server
            )
            val insecure = parseBooleanFlag(firstParam(params, "allowInsecure", "insecure")) == true
            val fingerprint = firstParam(params, "fp")?.takeIf { it.isNotBlank() }
            val alpnList = firstParam(params, "alpn")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }

            val echConfig = parseEchConfig(params)

            val tlsConfig = TlsConfig(
                enabled = true,
                serverName = sni,
                insecure = insecure,
                alpn = alpnList,
                utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) },
                ech = echConfig
            )
            val transport = buildTrojanTransport(params, hostParam)

            return Outbound(
                type = "trojan",
                tag = name,
                server = server,
                serverPort = port,
                password = password,
                tls = tlsConfig,
                transport = transport
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse Trojan link", e)
        }
        return null
    }

    protected fun buildTrojanTransport(
        params: Map<String, String>,
        hostParam: String?
    ): TransportConfig? {
        val transportType = firstParam(params, "type")?.lowercase() ?: "tcp"
        return when (transportType) {
            "ws" -> TransportConfig(
                type = "ws",
                path = firstParam(params, "path") ?: "/",
                headers = hostParam?.let { mapOf("Host" to it) }
            )

            "grpc" -> TransportConfig(
                type = "grpc",
                serviceName = firstParam(params, "serviceName", "sn") ?: ""
            )

            "h2", "http" -> TransportConfig(
                type = "http",
                path = firstParam(params, "path"),
                host = parseHostList(hostParam)
            )

            "httpupgrade" -> TransportConfig(
                type = "httpupgrade",
                path = firstParam(params, "path") ?: "/",
                host = parseSingleHost(hostParam)
            )

            "xhttp", "splithttp" -> TransportConfig(
                type = "xhttp",
                path = firstParam(params, "path") ?: "/",
                host = parseHostList(hostParam),
                mode = firstParam(params, "mode"),
                xPaddingBytes = firstParam(params, "xPaddingBytes", "x-padding-bytes"),
                scMaxEachPostBytes = firstParam(params, "scMaxEachPostBytes")?.toLongOrNull(),
                scMinPostsIntervalMs = firstParam(params, "scMinPostsIntervalMs")?.toLongOrNull(),
                scMaxBufferedPosts = firstParam(params, "scMaxBufferedPosts")?.toLongOrNull(),
                noGRPCHeader = parseBooleanFlag(firstParam(params, "noGRPCHeader")),
                noSSEHeader = parseBooleanFlag(firstParam(params, "noSSEHeader"))
            )

            else -> null
        }
    }

    protected fun parseHysteria2Link(link: String): Outbound? {
        try {
            val uri = java.net.URI(sanitizeUri(link.replace("hy2://", "hysteria2://")))
            val name = java.net.URLDecoder.decode(uri.fragment ?: "Hysteria2 Node", "UTF-8")
            val password = uri.userInfo
            val server = uri.host
            val port = if (uri.port == -1) 443 else uri.port

            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }

            return Outbound(
                type = "hysteria2",
                tag = name,
                server = server,
                serverPort = port,
                password = password,
                upMbps = firstParam(params, "up_mbps", "upmbps", "up")?.toIntOrNull(),
                downMbps = firstParam(params, "down_mbps", "downmbps", "down")?.toIntOrNull(),
                tls = TlsConfig(
                    enabled = true,
                    serverName = defaultTlsServerName(
                        explicitServerName = params["sni"],
                        server = server
                    ),
                    insecure = parseBooleanQueryParam(
                        params["insecure"] ?: params["allowInsecure"] ?: params["skip-cert-verify"]
                    ),
                    alpn = parseCsvQueryParam(params["alpn"]),
                    certificatePublicKeySha256 = params["pinSHA256"]?.let { listOf(it) }
                ),
                obfs = params["obfs"]?.let { ObfsConfig(type = it, password = params["obfs-password"]) },
                serverPorts = parseServerPorts(params["mport"])
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse Hy2 link", e)
        }
        return null
    }

    protected fun parseBooleanQueryParam(value: String?): Boolean? {
        return when (value?.trim()?.lowercase()) {
            null, "" -> null
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }
    }

    protected fun parseCsvQueryParam(value: String?): List<String>? {
        return value
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
    }

    protected fun parseServerPorts(value: String?): List<String>? {
        return value
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
    }

    protected fun parseHysteriaLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(sanitizeUri(link))
            val name = java.net.URLDecoder.decode(uri.fragment ?: "Hysteria Node", "UTF-8")
            val server = uri.host
            val port = if (uri.port == -1) 443 else uri.port

            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }

            return Outbound(
                type = "hysteria",
                tag = name,
                server = server,
                serverPort = port,
                authStr = params["auth"],
                upMbps = params["up_mbps"]?.toIntOrNull() ?: params["up"]?.toIntOrNull() ?: 50,
                downMbps = params["down_mbps"]?.toIntOrNull() ?: params["down"]?.toIntOrNull() ?: 50,
                tls = TlsConfig(
                    enabled = true,
                    serverName = defaultTlsServerName(
                        explicitServerName = params["sni"],
                        server = server
                    )
                )
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse Hysteria link", e)
        }
        return null
    }

    protected fun parseAnyTLSLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(sanitizeUri(link))
            val name = java.net.URLDecoder.decode(uri.fragment ?: "AnyTLS Node", "UTF-8")
            val password = uri.userInfo
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 443

            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    try {
                        params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                    } catch (e: Exception) {
                        params[parts[0]] = parts[1]
                    }
                }
            }

            val sni = defaultTlsServerName(
                explicitServerName = params["sni"],
                server = server
            )
            val insecure = params["insecure"] == "1" || params["allowInsecure"] == "1"
            val alpnList = params["alpn"]?.split(",")?.filter { it.isNotBlank() }
            val fingerprint = params["fp"]?.takeIf { it.isNotBlank() }

            val idleSessionCheckInterval = params["idle_session_check_interval"]
            val idleSessionTimeout = params["idle_session_timeout"]
            val minIdleSession = params["min_idle_session"]?.toIntOrNull()

            return Outbound(
                type = "anytls",
                tag = name,
                server = server,
                serverPort = port,
                password = password,
                idleSessionCheckInterval = idleSessionCheckInterval,
                idleSessionTimeout = idleSessionTimeout,
                minIdleSession = minIdleSession,
                tls = TlsConfig(
                    enabled = true,
                    serverName = sni,
                    insecure = insecure,
                    alpn = alpnList,
                    utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
                )
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse AnyTLS link", e)
        }
        return null
    }

    protected fun parseNaiveLink(link: String): Outbound? {
        try {
            val normalizedLink = link.replace("naive+https://", "naive://")
            val uri = java.net.URI(sanitizeUri(normalizedLink))
            val name = java.net.URLDecoder.decode(uri.fragment ?: "Naive Node", "UTF-8")
            val server = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 443

            var username: String? = null
            var password: String? = null
            val userInfo = uri.userInfo
            if (!userInfo.isNullOrBlank()) {
                val parts = userInfo.split(":", limit = 2)
                username = java.net.URLDecoder.decode(parts.getOrNull(0) ?: "", "UTF-8")
                    .takeIf { it.isNotBlank() }
                password = java.net.URLDecoder.decode(parts.getOrNull(1) ?: "", "UTF-8")
                    .takeIf { it.isNotBlank() }
            }

            val params = parseQueryParams(uri.query)
            val network = firstParam(params, "network")
                ?: firstParam(params, "proto")
                ?: firstParam(params, "type")
                ?: "h2"
            val sni = defaultTlsServerName(
                explicitServerName = firstParam(params, "sni"),
                server = server
            )
            val insecure = parseBooleanFlag(firstParam(params, "insecure", "allowInsecure")) == true
            val alpn = firstParam(params, "alpn")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            val fingerprint = firstParam(params, "fp")?.takeIf { it.isNotBlank() }
            val congestionControl = firstParam(params, "congestion_control", "cc")
            val enableUdpOverTcp = parseBooleanFlag(firstParam(params, "uot", "udp_over_tcp")) == true
            val insecureConcurrency = firstParam(params, "insecure_concurrency")?.toIntOrNull()
            val extraHeaders = parseNaiveExtraHeaders(params)

            val useQuic = network.equals("quic", ignoreCase = true)

            return Outbound(
                type = "naive",
                tag = name,
                server = server,
                serverPort = port,
                username = username,
                password = password,
                network = if (useQuic) "quic" else "h2",
                insecureConcurrency = insecureConcurrency,
                extraHeaders = extraHeaders,
                quic = useQuic,
                quicCongestionControl = if (useQuic) congestionControl else null,
                congestionControl = if (useQuic) null else congestionControl,
                udpOverTcp = if (enableUdpOverTcp) com.kunk.singbox.model.UdpOverTcpConfig(enabled = true) else null,
                tls = TlsConfig(
                    enabled = true,
                    serverName = sni,
                    insecure = insecure,
                    alpn = alpn,
                    utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
                )
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse Naive link", e)
        }
        return null
    }

    protected fun parseNaiveExtraHeaders(params: Map<String, String>): Map<String, String>? {
        val normalized = linkedMapOf<String, String>()
        params.forEach { (key, rawValue) ->
            if (!key.equals("extra_headers", ignoreCase = true)) return@forEach
            rawValue
                .replace("\r\n", "\n")
                .split("\n", ";")
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { line ->
                    val separatorIndex = line.indexOf(':')
                    if (separatorIndex <= 0) return@forEach

                    val headerName = line.substring(0, separatorIndex).trim()
                    val headerValue = line.substring(separatorIndex + 1).trim()
                    if (headerName.isNotEmpty() && headerValue.isNotEmpty()) {
                        normalized[headerName] = headerValue
                    }
                }
        }

        return normalized.ifEmpty { null }
    }

    protected fun parseTuicLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(sanitizeUri(link))
            val name = java.net.URLDecoder.decode(uri.fragment ?: "TUIC Node", "UTF-8")
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 443
            val params = parseQueryParams(uri.query)
            val credentials = parseTuicCredentials(uri.userInfo ?: "", params)
            val tlsOptions = buildTuicTlsOptions(server, params)
            val transportOptions = buildTuicTransportOptions(params)

            return Outbound(
                type = "tuic",
                tag = name,
                server = server,
                serverPort = port,
                uuid = credentials.uuid,
                password = credentials.password,
                congestionControl = transportOptions.congestionControl,
                udpRelayMode = transportOptions.udpRelayMode,
                zeroRttHandshake = transportOptions.zeroRtt,
                disableSni = if (tlsOptions.disableSni) true else null,
                tls = TlsConfig(
                    enabled = true,
                    serverName = tlsOptions.serverName,
                    insecure = tlsOptions.insecure,
                    alpn = tlsOptions.alpn,
                    utls = tlsOptions.fingerprint?.let {
                        UtlsConfig(enabled = true, fingerprint = it)
                    }
                )
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse TUIC link", e)
        }
        return null
    }

    protected fun parseWireGuardLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(sanitizeUri(link))
            val name = java.net.URLDecoder.decode(uri.fragment ?: "WireGuard Node", "UTF-8")
            val privateKey = uri.userInfo?.takeIf { it.isNotBlank() } ?: return null
            val server = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val port = if (uri.port > 0) uri.port else 51820

            val params = parseQueryParams(uri.rawQuery.orEmpty())
            val publicKey = firstParam(params, "public_key", "publicKey", "peer_public_key") ?: return null
            val localAddress = parseWireGuardLocalAddress(params) ?: return null

            val peer = WireGuardPeer(
                server = server,
                serverPort = port,
                publicKey = publicKey,
                preSharedKey = firstParam(params, "pre_shared_key", "preSharedKey")
            )

            return Outbound(
                type = "wireguard",
                tag = name,
                privateKey = privateKey,
                localAddress = localAddress,
                peers = listOf(peer)
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse WG link", e)
        }
        return null
    }

    protected fun parseSSHLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(sanitizeUri(link))
            val name = java.net.URLDecoder.decode(uri.fragment ?: "SSH Node", "UTF-8")
            val userInfo = uri.userInfo ?: ""
            val parts = userInfo.split(":")

            return Outbound(
                type = "ssh",
                tag = name,
                server = uri.host,
                serverPort = if (uri.port > 0) uri.port else 22,
                user = parts.getOrNull(0),
                password = parts.getOrNull(1)
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse SSH link", e)
        }
        return null
    }

    /**
     *       https://[username:password@]host:port[#name]
     */

    protected fun parseHttpLink(link: String, useTls: Boolean): Outbound? {
        return try {
            val uri = java.net.URI(sanitizeUri(link))
            val server = uri.host
            if (!looksLikeHttpProxyUri(uri) || server.isNullOrBlank()) {
                null
            } else {
                val name = java.net.URLDecoder.decode(
                    uri.fragment ?: if (useTls) "HTTPS Proxy" else "HTTP Proxy",
                    "UTF-8"
                )
                val (username, password) = parseHttpCredentials(uri)
                val port = if (uri.port > 0) uri.port else if (useTls) 443 else 8080

                Outbound(
                    type = "http",
                    tag = name,
                    server = server,
                    serverPort = port,
                    username = username,
                    password = password,
                    tls = buildHttpTlsConfig(useTls, server)
                )
            }
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse HTTP/HTTPS link", e)
            null
        }
    }

    protected fun parseHttpCredentials(uri: java.net.URI): Pair<String?, String?> {
        val userInfo = uri.userInfo ?: return null to null
        val parts = userInfo.split(":", limit = 2)
        val username = java.net.URLDecoder.decode(parts.getOrNull(0) ?: "", "UTF-8")
            .takeIf { it.isNotBlank() }
        val password = java.net.URLDecoder.decode(parts.getOrNull(1) ?: "", "UTF-8")
            .takeIf { it.isNotBlank() }
        return username to password
    }

    protected fun buildHttpTlsConfig(useTls: Boolean, server: String): TlsConfig? {
        if (!useTls) return null
        return TlsConfig(
            enabled = true,
            serverName = defaultTlsServerName(
                explicitServerName = null,
                server = server
            )
        )
    }

    protected fun looksLikeHttpProxyUri(uri: java.net.URI): Boolean {
        val hasUserInfo = !uri.userInfo.isNullOrBlank()
        val hasExplicitPort = uri.port > 0
        val hasName = !uri.fragment.isNullOrBlank()
        if (!hasUserInfo && !hasExplicitPort && !hasName) {
            return false
        }

        val path = uri.rawPath.orEmpty()
        val hasNonRootPath = path.isNotBlank() && path != "/"
        val hasQuery = !uri.rawQuery.isNullOrBlank()
        val hasContentPath = hasNonRootPath || hasQuery
        val lacksExplicitProxyIdentity = !hasUserInfo && !hasName
        if (hasContentPath && lacksExplicitProxyIdentity) {
            return false
        }

        return true
    }

    /**
     *       socks://[username:password@]host:port[#name]
     */

    protected fun parseSocks5Link(link: String): Outbound? {
        try {

            val normalizedLink = link
                .replace("socks5://", "socks://")
            val uri = java.net.URI(sanitizeUri(normalizedLink))
            val name = java.net.URLDecoder.decode(uri.fragment ?: "SOCKS5 Proxy", "UTF-8")
            val server = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 1080

            var username: String? = null
            var password: String? = null
            if (uri.userInfo != null) {
                val parts = uri.userInfo.split(":", limit = 2)
                username = java.net.URLDecoder.decode(parts.getOrNull(0) ?: "", "UTF-8")
                    .takeIf { it.isNotBlank() }
                password = java.net.URLDecoder.decode(parts.getOrNull(1) ?: "", "UTF-8")
                    .takeIf { it.isNotBlank() }
            }

            return Outbound(
                type = "socks",
                tag = name,
                server = server,
                serverPort = port,
                username = username,
                password = password
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse SOCKS5 link", e)
        }
        return null
    }
}
