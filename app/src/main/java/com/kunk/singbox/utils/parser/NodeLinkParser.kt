package com.kunk.singbox.utils.parser

import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import com.kunk.singbox.model.UtlsConfig
import com.kunk.singbox.model.RealityConfig
import com.kunk.singbox.model.ObfsConfig
import com.kunk.singbox.model.WireGuardPeer
import android.util.Log
import com.google.gson.Gson

/**
 * 各种节点链接解析器集合
 */
class NodeLinkParser(private val gson: Gson) {

    /**
     * 预处理 URI 字符串，清理常见格式问题
     * - 对 fragment 和 query 中的空格进行 URL 编码
     * - 移除参数名和值周围的空格 (security =tls -> security=tls)
     */
    private fun sanitizeUri(link: String): String {
        var result = link

        // 分离 fragment
        val hashIndex = result.indexOf('#')
        var fragment = ""
        if (hashIndex != -1) {
            fragment = result.substring(hashIndex + 1)
            result = result.substring(0, hashIndex)
        }

        // 清理 query 部分的空格
        val questionIndex = result.indexOf('?')
        if (questionIndex != -1) {
            val base = result.substring(0, questionIndex)
            val query = result.substring(questionIndex + 1)
            // 移除参数中 = 和 & 周围的空格
            val cleanedQuery = query
                .replace(Regex("\\s*=\\s*"), "=")
                .replace(Regex("\\s*&\\s*"), "&")
                .replace(" ", "%20")
            result = "$base?$cleanedQuery"
        }

        // 重新附加 fragment
        if (fragment.isNotEmpty()) {
            result = "$result#${fragment.replace(" ", "%20")}"
        }

        return result
    }

    fun parse(link: String): Outbound? {
        return when {
            link.startsWith("ss://") -> parseShadowsocksLink(link)
            link.startsWith("vmess://") -> parseVMessLink(link)
            link.startsWith("vless://") -> parseVLessLink(link)
            link.startsWith("trojan://") -> parseTrojanLink(link)
            link.startsWith("hysteria2://") || link.startsWith("hy2://") -> parseHysteria2Link(link)
            link.startsWith("hysteria://") -> parseHysteriaLink(link)
            link.startsWith("anytls://") -> parseAnyTLSLink(link)
            link.startsWith("tuic://") -> parseTuicLink(link)
            link.startsWith("https://") -> parseHttpLink(link, useTls = true)
            link.startsWith("http://") -> parseHttpLink(link, useTls = false)
            link.startsWith("socks5://") || link.startsWith("socks://") -> parseSocks5Link(link)
            else -> null
        }
    }

    private fun parseShadowsocksLink(link: String): Outbound? {
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
                    userInfo = userInfoBase64
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

    private fun tryDecodeBase64(content: String): String? {
        // 清理内容中的空白字符
        val cleaned = content.trim()
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")

        // 首先尝试 java.util.Base64 (在 JVM 单元测试和 Android API 26+ 上可用)
        try {
            // 尝试 URL-safe 解码 (处理 - 和 _ 字符)
            val urlSafeDecoder = java.util.Base64.getUrlDecoder()
            // 添加 padding 如果需要
            val padded = when (cleaned.length % 4) {
                2 -> cleaned + "=="
                3 -> cleaned + "="
                else -> cleaned
            }
            val decoded = urlSafeDecoder.decode(padded)
            if (decoded.isNotEmpty()) {
                return String(decoded, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            // 继续尝试其他方式
        }

        try {
            // 尝试标准 Base64 解码
            val standardDecoder = java.util.Base64.getDecoder()
            val padded = when (cleaned.length % 4) {
                2 -> cleaned + "=="
                3 -> cleaned + "="
                else -> cleaned
            }
            val decoded = standardDecoder.decode(padded)
            if (decoded.isNotEmpty()) {
                return String(decoded, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            // 继续尝试 Android Base64
        }

        // 回退到 android.util.Base64 (兼容 Android API 24-25)
        val candidates = arrayOf(
            android.util.Base64.DEFAULT,
            android.util.Base64.NO_WRAP,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
        for (flags in candidates) {
            try {
                val decoded = android.util.Base64.decode(cleaned, flags)
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

    private fun parseHostPort(hostPort: String): Pair<String, Int> {
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

    private fun parseVMessLink(link: String): Outbound? {
        try {
            val base64Part = link.removePrefix("vmess://").trim()
            Log.d("NodeLinkParser", "Parsing VMess, base64 length: ${base64Part.length}")
            val decoded = tryDecodeBase64(base64Part)
            if (decoded == null) {
                Log.e("NodeLinkParser", "Failed to decode VMess base64, first 50 chars: ${base64Part.take(50)}")
                return null
            }
            Log.d("NodeLinkParser", "VMess decoded successfully, JSON length: ${decoded.length}")
            // 这里需要一个简单的内部类来映射 VMess 链接的 JSON
            val json = gson.fromJson(decoded, Map::class.java)

            val add = json["add"] as? String ?: ""
            val port = (json["port"] as? String)?.toIntOrNull() ?: (json["port"] as? Double)?.toInt() ?: 443
            val id = json["id"] as? String ?: ""
            val aid = (json["aid"] as? String)?.toIntOrNull() ?: (json["aid"] as? Double)?.toInt() ?: 0
            val net = json["net"] as? String ?: "tcp"
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
                "grpc" -> TransportConfig(
                    type = "grpc",
                    serviceName = path
                )
                "h2" -> TransportConfig(
                    type = "http",
                    host = if (host.isNotBlank()) listOf(host) else null,
                    path = path
                )
                else -> null
            }

            // 注意：sing-box 不支持 alter_id，只支持 AEAD 加密的 VMess (alterId=0)
            // 如果订阅中的 alterId != 0，该节点可能无法正常工作
            if (aid != 0) {
                Log.w("NodeLinkParser", "VMess node '$ps' has alterId=$aid, sing-box only supports alterId=0 (AEAD)")
            }

            return Outbound(
                type = "vmess",
                tag = ps,
                server = add,
                serverPort = port,
                uuid = id,
                security = "auto",
                tls = tlsConfig,
                transport = transport
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse VMess link", e)
        }
        return null
    }

    private fun parseVLessLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(sanitizeUri(link))
            val name = java.net.URLDecoder.decode(uri.fragment ?: "VLESS Node", "UTF-8")
            val uuid = uri.userInfo
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 443

            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }

            val security = params["security"] ?: "none"
            val sni = params["sni"] ?: params["host"] ?: server
            val transportType = params["type"] ?: "tcp"
            val insecure = params["allowInsecure"] == "1" || params["insecure"] == "1"
            val fingerprint = params["fp"]?.takeIf { it.isNotBlank() }
            val alpnList = params["alpn"]?.split(",")?.filter { it.isNotBlank() }
            val flow = params["flow"]?.takeIf { it.isNotBlank() }
            val packetEncoding = params["packetEncoding"]?.takeIf { it.isNotBlank() } ?: "xudp"

            val finalAlpnList = if ((security == "tls" || security == "reality") && (alpnList == null || alpnList.isEmpty())) {
                if (transportType == "ws") listOf("http/1.1") else listOf("h2", "http/1.1")
            } else {
                alpnList
            }

            val tlsConfig = when (security) {
                "tls" -> TlsConfig(
                    enabled = true,
                    serverName = sni,
                    insecure = insecure,
                    alpn = finalAlpnList,
                    utls = (fingerprint ?: "chrome").let { UtlsConfig(enabled = true, fingerprint = it) }
                )
                "reality" -> TlsConfig(
                    enabled = true,
                    serverName = sni,
                    insecure = insecure,
                    alpn = finalAlpnList,
                    reality = RealityConfig(
                        enabled = true,
                        publicKey = params["pbk"],
                        shortId = params["sid"]
                    ),
                    utls = (fingerprint ?: "chrome").let { UtlsConfig(enabled = true, fingerprint = it) }
                )
                else -> null
            }

            val transport = when (transportType) {
                "ws" -> TransportConfig(
                    type = "ws",
                    path = params["path"] ?: "/",
                    headers = params["host"]?.let { mapOf("Host" to it) }
                )
                "grpc" -> TransportConfig(
                    type = "grpc",
                    serviceName = params["serviceName"] ?: params["sn"] ?: ""
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
                packetEncoding = packetEncoding
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse VLESS link", e)
        }
        return null
    }

    private fun parseTrojanLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(sanitizeUri(link))
            val name = java.net.URLDecoder.decode(uri.fragment ?: "Trojan Node", "UTF-8")
            val password = uri.userInfo
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 443

            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }

            return Outbound(
                type = "trojan",
                tag = name,
                server = server,
                serverPort = port,
                password = password,
                tls = TlsConfig(
                    enabled = true,
                    serverName = params["sni"] ?: params["host"] ?: server
                )
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse Trojan link", e)
        }
        return null
    }

    private fun parseHysteria2Link(link: String): Outbound? {
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
                upMbps = params["up_mbps"]?.toIntOrNull() ?: params["up"]?.toIntOrNull() ?: 50,
                downMbps = params["down_mbps"]?.toIntOrNull() ?: params["down"]?.toIntOrNull() ?: 50,
                tls = TlsConfig(
                    enabled = true,
                    serverName = params["sni"] ?: server
                ),
                obfs = params["obfs"]?.let { ObfsConfig(type = it, password = params["obfs-password"]) }
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse Hy2 link", e)
        }
        return null
    }

    private fun parseHysteriaLink(link: String): Outbound? {
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
                    serverName = params["sni"] ?: server
                )
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse Hysteria link", e)
        }
        return null
    }

    private fun parseAnyTLSLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(sanitizeUri(link))
            val name = java.net.URLDecoder.decode(uri.fragment ?: "AnyTLS Node", "UTF-8")
            val password = uri.userInfo
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 443

            // 解析 query 参数
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

            val sni = params["sni"] ?: server
            val insecure = params["insecure"] == "1" || params["allowInsecure"] == "1"
            val alpnList = params["alpn"]?.split(",")?.filter { it.isNotBlank() }
            val fingerprint = params["fp"]?.takeIf { it.isNotBlank() }

            // AnyTLS 特有参数
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

    private fun parseTuicLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(sanitizeUri(link))
            val name = java.net.URLDecoder.decode(uri.fragment ?: "TUIC Node", "UTF-8")
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 443

            // 解析 userInfo: 可能是 uuid:password 或只有 uuid
            val userInfo = uri.userInfo ?: ""
            val colonIndex = userInfo.indexOf(':')
            val uuid = if (colonIndex > 0) userInfo.substring(0, colonIndex) else userInfo
            var password = if (colonIndex > 0) userInfo.substring(colonIndex + 1) else ""

            // 解析 query 参数
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

            // 如果 password 为空，尝试从 query 参数中获取，或使用 UUID 作为密码
            if (password.isBlank()) {
                password = params["password"] ?: params["token"] ?: uuid
            }

            val sni = params["sni"] ?: server
            val insecure = params["insecure"] == "1" || params["allow_insecure"] == "1" || params["allowInsecure"] == "1"
            val alpnList = params["alpn"]?.split(",")?.filter { it.isNotBlank() }
            val fingerprint = params["fp"]?.takeIf { it.isNotBlank() }

            // TUIC 特有参数
            val congestionControl = params["congestion_control"] ?: params["congestion"] ?: "bbr"
            val udpRelayMode = params["udp_relay_mode"] ?: "native"
            val zeroRtt = params["reduce_rtt"] == "1" || params["zero_rtt"] == "1"

            return Outbound(
                type = "tuic",
                tag = name,
                server = server,
                serverPort = port,
                uuid = uuid,
                password = password,
                congestionControl = congestionControl,
                udpRelayMode = udpRelayMode,
                zeroRttHandshake = zeroRtt,
                tls = TlsConfig(
                    enabled = true,
                    serverName = sni,
                    insecure = insecure,
                    alpn = alpnList,
                    utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
                )
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse TUIC link", e)
        }
        return null
    }

    private fun parseWireGuardLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(sanitizeUri(link))
            val name = java.net.URLDecoder.decode(uri.fragment ?: "WireGuard Node", "UTF-8")
            val privateKey = uri.userInfo
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 51820

            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }

            val peer = WireGuardPeer(
                server = server,
                serverPort = port,
                publicKey = params["public_key"] ?: ""
            )

            return Outbound(
                type = "wireguard",
                tag = name,
                privateKey = privateKey,
                peers = listOf(peer)
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse WG link", e)
        }
        return null
    }

    private fun parseSSHLink(link: String): Outbound? {
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
     * 解析 HTTP/HTTPS 代理链接
     * 格式: http://[username:password@]host:port[#name]
     *       https://[username:password@]host:port[#name]
     */
    private fun parseHttpLink(link: String, useTls: Boolean): Outbound? {
        try {
            val uri = java.net.URI(sanitizeUri(link))
            val name = java.net.URLDecoder.decode(
                uri.fragment ?: if (useTls) "HTTPS Proxy" else "HTTP Proxy",
                "UTF-8"
            )
            val server = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else if (useTls) 443 else 8080

            // 解析用户名和密码
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
                type = "http",
                tag = name,
                server = server,
                serverPort = port,
                username = username,
                password = password,
                tls = if (useTls) TlsConfig(enabled = true, serverName = server) else null
            )
        } catch (e: Exception) {
            Log.e("NodeLinkParser", "Failed to parse HTTP/HTTPS link", e)
        }
        return null
    }

    /**
     * 解析 SOCKS5 代理链接
     * 格式: socks5://[username:password@]host:port[#name]
     *       socks://[username:password@]host:port[#name]
     */
    private fun parseSocks5Link(link: String): Outbound? {
        try {
            // 统一转换为标准 URI 格式
            val normalizedLink = link
                .replace("socks5://", "socks://")
            val uri = java.net.URI(sanitizeUri(normalizedLink))
            val name = java.net.URLDecoder.decode(uri.fragment ?: "SOCKS5 Proxy", "UTF-8")
            val server = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 1080

            // 解析用户名和密码
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
