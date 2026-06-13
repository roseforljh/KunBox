package com.kunk.singbox.utils.parser

import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import com.kunk.singbox.model.UtlsConfig
import com.kunk.singbox.model.RealityConfig
import com.kunk.singbox.model.ObfsConfig
import android.util.Log
import com.google.gson.Gson

/**
 */

@Suppress("TooManyFunctions")
abstract class NodeLinkParserPart3(gson: Gson) : NodeLinkParserPart2(gson) {
    protected override fun parseShadowsocksLink(link: String): Outbound? {
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

    protected override fun tryDecodeBase64(content: String): String? {

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

    protected override fun padBase64(content: String): String {
        return when (content.length % 4) {
            2 -> content + "=="
            3 -> content + "="
            else -> content
        }
    }

    protected override fun decodeWithJvmBase64(content: String, urlSafe: Boolean): ByteArray? {
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

    protected override fun parseHostPort(hostPort: String): Pair<String, Int> {
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

    protected override fun parseVMessLink(link: String): Outbound? {
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
    protected override fun parseVLessLink(link: String): Outbound? {
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

    protected override fun parseTrojanLink(link: String): Outbound? {
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

    protected override fun buildTrojanTransport(
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

    protected override fun parseHysteria2Link(link: String): Outbound? {
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
                    alpn = parseCsvQueryParam(params["alpn"])
                ),
                obfs = params["obfs"]?.let { ObfsConfig(type = it, password = params["obfs-password"]) },
                serverPorts = parseServerPorts(params["mport"])
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse Hy2 link", e)
        }
        return null
    }

    protected override fun parseBooleanQueryParam(value: String?): Boolean? {
        return when (value?.trim()?.lowercase()) {
            null, "" -> null
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }
    }

    protected override fun parseCsvQueryParam(value: String?): List<String>? {
        return value
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
    }

    protected override fun parseServerPorts(value: String?): List<String>? {
        return value
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
    }

    protected override fun parseHysteriaLink(link: String): Outbound? {
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

    protected override fun parseAnyTLSLink(link: String): Outbound? {
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
}
