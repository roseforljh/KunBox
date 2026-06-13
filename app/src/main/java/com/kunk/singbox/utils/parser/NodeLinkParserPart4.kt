package com.kunk.singbox.utils.parser

import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.UtlsConfig
import com.kunk.singbox.model.WireGuardPeer
import android.util.Log
import com.google.gson.Gson

/**
 */

@Suppress("TooManyFunctions")
open class NodeLinkParserPart4(gson: Gson) : NodeLinkParserPart3(gson) {
    protected override fun parseNaiveLink(link: String): Outbound? {
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

    protected override fun parseNaiveExtraHeaders(params: Map<String, String>): Map<String, String>? {
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

    protected override fun parseTuicLink(link: String): Outbound? {
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

    protected override fun parseWireGuardLink(link: String): Outbound? {
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

    protected override fun parseSSHLink(link: String): Outbound? {
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

    protected override fun parseHttpLink(link: String, useTls: Boolean): Outbound? {
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

    protected override fun parseHttpCredentials(uri: java.net.URI): Pair<String?, String?> {
        val userInfo = uri.userInfo ?: return null to null
        val parts = userInfo.split(":", limit = 2)
        val username = java.net.URLDecoder.decode(parts.getOrNull(0) ?: "", "UTF-8")
            .takeIf { it.isNotBlank() }
        val password = java.net.URLDecoder.decode(parts.getOrNull(1) ?: "", "UTF-8")
            .takeIf { it.isNotBlank() }
        return username to password
    }

    protected override fun buildHttpTlsConfig(useTls: Boolean, server: String): TlsConfig? {
        if (!useTls) return null
        return TlsConfig(
            enabled = true,
            serverName = defaultTlsServerName(
                explicitServerName = null,
                server = server
            )
        )
    }

    protected override fun looksLikeHttpProxyUri(uri: java.net.URI): Boolean {
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

    protected override fun parseSocks5Link(link: String): Outbound? {
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
