package com.kunk.singbox.utils.parser

import com.kunk.singbox.model.MultiplexConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import com.kunk.singbox.model.UtlsConfig

/**
 */

@Suppress("TooManyFunctions")
open class ClashYamlParserPart2 : ClashYamlParserPart1() {
    protected override fun parseTuic(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String?, globalTlsMinVersion: String?): Outbound? {
        if (server == null || port == null) return null
        val uuid = asString(map["uuid"]) ?: return null

        val password = asString(map["password"]) ?: asString(map["token"]) ?: uuid

        val sni = asString(map["sni"]) ?: asString(map["servername"]) ?: server
        val insecure = asBool(map["skip-cert-verify"]) == true || asBool(map["allow-insecure"]) == true || asBool(map["insecure"]) == true
        val alpn = asStringList(map["alpn"])

        val fingerprint = asString(map["client-fingerprint"]) ?: asString(map["fingerprint"]) ?: globalFingerprint
        val congestion = asString(map["congestion-controller"]) ?: asString(map["congestion"])
        val udpRelayMode = asString(map["udp-relay-mode"]) ?: "native"
        val zeroRtt = asBool(map["reduce-rtt"]) == true || asBool(map["zero-rtt-handshake"]) == true

        val tlsMinVersion = asString(map["tls-version"]) ?: asString(map["min-tls-version"]) ?: globalTlsMinVersion

        return Outbound(
            type = "tuic",
            tag = name,
            server = server,
            serverPort = port,
            uuid = uuid,
            password = password,
            congestionControl = congestion,
            udpRelayMode = udpRelayMode,
            zeroRttHandshake = zeroRtt,
            tls = buildTlsConfig(
                map = map,
                serverName = sni,
                insecure = insecure,
                alpn = alpn,
                minVersion = tlsMinVersion,
                utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
            )
        )
    }

    protected override fun parseSSH(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound? {
        if (server == null || port == null) return null
        val user = asString(map["username"]) ?: "root"
        val password = asString(map["password"])
        val privateKey = asString(map["private-key"])

        return Outbound(
            type = "ssh",
            tag = name,
            server = server,
            serverPort = port,
            user = user,
            password = password,
            privateKey = privateKey
        )
    }

    protected override fun parseWireGuard(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound? {
        if (server == null || port == null) return null
        val privateKey = asString(map["private-key"]) ?: return null
        val publicKey = asString(map["public-key"]) ?: return null // Peer public key
        val preSharedKey = asString(map["pre-shared-key"])
        val address = asStringList(map["ip"]) // Local Address
        val mtu = asInt(map["mtu"]) ?: 1420

        val peer = com.kunk.singbox.model.WireGuardPeer(
            server = server,
            serverPort = port,
            publicKey = publicKey,
            preSharedKey = preSharedKey
        )

        return Outbound(
            type = "wireguard",
            tag = name,
            localAddress = address,
            privateKey = privateKey,
            peers = listOf(peer),
            mtu = mtu
        )
    }

    protected override fun parseAnyTLS(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String?, globalTlsMinVersion: String?): Outbound? {
        if (server == null || port == null) return null

        val password = asString(map["password"])
            ?: asString(map["uuid"])
            ?: asString(map["token"])
            ?: return null

        val sni = asString(map["sni"]) ?: asString(map["servername"]) ?: server
        val insecure = asBool(map["skip-cert-verify"]) == true || asBool(map["allow-insecure"]) == true || asBool(map["insecure"]) == true
        val alpn = asStringList(map["alpn"])

        val fingerprint = asString(map["client-fingerprint"]) ?: asString(map["fingerprint"]) ?: globalFingerprint

        val tlsMinVersion = asString(map["tls-version"]) ?: asString(map["min-tls-version"]) ?: globalTlsMinVersion

        val idleSessionCheckInterval = asString(map["idle-session-check-interval"])
        val idleSessionTimeout = asString(map["idle-session-timeout"])
        val minIdleSession = asInt(map["min-idle-session"])

        return Outbound(
            type = "anytls",
            tag = name,
            server = server,
            serverPort = port,
            password = password,
            idleSessionCheckInterval = idleSessionCheckInterval,
            idleSessionTimeout = idleSessionTimeout,
            minIdleSession = minIdleSession,
            tls = buildTlsConfig(
                map = map,
                serverName = sni,
                insecure = insecure,
                alpn = alpn,
                minVersion = tlsMinVersion,
                utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
            )
        )
    }

    @Suppress("CyclomaticComplexMethod")
    protected override fun parseNaive(
        map: Map<*, *>,
        name: String,
        server: String?,
        port: Int?,
        globalFingerprint: String?,
        globalTlsMinVersion: String?): Outbound? {
        if (server == null || port == null) return null

        val username = asString(map["username"])
        val password = asString(map["password"])
        val host = asString(map["host"])
            ?: asString((map["headers"] as? Map<*, *>)?.get("Host"))
            ?: asString((map["headers"] as? Map<*, *>)?.get("host"))
        val sni = asString(map["sni"]) ?: asString(map["servername"]) ?: host ?: server

        val insecure = asBool(map["skip-cert-verify"]) == true ||
            asBool(map["allow-insecure"]) == true ||
            asBool(map["insecure"]) == true
        val alpn = asStringList(map["alpn"])
        val fingerprint = asString(map["client-fingerprint"]) ?: asString(map["fingerprint"]) ?: globalFingerprint
        val tlsMinVersion = asString(map["tls-version"]) ?: asString(map["min-tls-version"]) ?: globalTlsMinVersion

        val rawNetwork = asString(map["network"]) ?: asString(map["proto"]) ?: asString(map["type"])
        val useQuic = rawNetwork.equals("quic", ignoreCase = true)
        val pathRaw = asString(map["path"]) ?: asString(map["url"]) ?: "/"
        val normalizedPath = if (pathRaw.startsWith("/")) pathRaw else "/$pathRaw"

        val congestionControl = asString(map["congestion-control"]) ?: asString(map["cc"])
        val uot = asBool(map["uot"]) == true ||
            asBool(map["udp-over-tcp"]) == true ||
            asBool(map["udp_over_tcp"]) == true

        val headers = host?.takeIf { it.isNotBlank() }?.let { mapOf("Host" to it) }

        return Outbound(
            type = "naive",
            tag = name,
            server = server,
            serverPort = port,
            username = username,
            password = password,
            network = if (useQuic) "quic" else "h2",
            path = normalizedPath,
            headers = headers,
            quic = useQuic,
            quicCongestionControl = if (useQuic) congestionControl else null,
            congestionControl = if (useQuic) null else congestionControl,
            udpOverTcp = if (uot) com.kunk.singbox.model.UdpOverTcpConfig(enabled = true) else null,
            tls = buildTlsConfig(
                map = map,
                serverName = sni,
                insecure = insecure,
                alpn = alpn,
                minVersion = tlsMinVersion,
                utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
            )
        )
    }

    protected override fun parseHysteria(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String?, globalTlsMinVersion: String?): Outbound? {
        if (server == null || port == null) return null
        val authStr = asString(map["auth-str"]) ?: asString(map["auth_str"]) ?: asString(map["auth"])
        val upMbps = asInt(map["up"]) ?: asInt(map["up-mbps"])
        val downMbps = asInt(map["down"]) ?: asInt(map["down-mbps"])
        val sni = asString(map["sni"]) ?: server
        val insecure = asBool(map["skip-cert-verify"]) == true
        val alpn = asStringList(map["alpn"])

        val fingerprint = asString(map["client-fingerprint"]) ?: globalFingerprint
        val obfs = asString(map["obfs"])

        val tlsMinVersion = asString(map["tls-version"]) ?: asString(map["min-tls-version"]) ?: globalTlsMinVersion

        val portsStr = asString(map["ports"])?.takeIf { it.isNotBlank() }
        val serverPorts = portsStr?.let { listOf(it) }
        val hopInterval = asString(map["hop-interval"])?.takeIf { it.isNotBlank() }

        return Outbound(
            type = "hysteria",
            tag = name,
            server = server,
            serverPort = port,
            authStr = authStr,
            upMbps = upMbps,
            downMbps = downMbps,
            serverPorts = serverPorts,
            hopInterval = hopInterval,
            tls = buildTlsConfig(
                map = map,
                serverName = sni,
                insecure = insecure,
                alpn = alpn,
                minVersion = tlsMinVersion,
                utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
            ),
            obfs = if (obfs != null) com.kunk.singbox.model.ObfsConfig(type = obfs) else null
        )
    }

    protected override fun parseHttp(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String?, globalTlsMinVersion: String?): Outbound? {
        if (server == null || port == null) return null

        val username = asString(map["username"])
        val password = asString(map["password"])

        val tlsEnabled = asBool(map["tls"]) == true

        val fingerprint = asString(map["client-fingerprint"]) ?: asString(map["fingerprint"]) ?: globalFingerprint
        val tlsConfig = if (tlsEnabled) {
            val sni = asString(map["sni"]) ?: asString(map["servername"]) ?: server

            val skipCertVerify = map["skip-cert-verify"]
            val insecure = asBool(skipCertVerify) == true
            val alpn = asStringList(map["alpn"])
            val tlsMinVersion = asString(map["tls-version"]) ?: asString(map["min-tls-version"]) ?: globalTlsMinVersion
            buildTlsConfig(
                map = map,
                serverName = sni,
                insecure = insecure,
                alpn = alpn,
                minVersion = tlsMinVersion,
                utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
            )
        } else null

        val path = asString(map["path"])
        val headersRaw = map["headers"] as? Map<*, *>
        val headers = if (headersRaw != null) {
            val headerMap = mutableMapOf<String, String>()
            headersRaw.forEach { (k, v) ->
                val ks = asString(k) ?: return@forEach
                val vs = asString(v) ?: return@forEach
                headerMap[ks] = vs
            }
            headerMap.takeIf { it.isNotEmpty() }
        } else null

        return Outbound(
            type = "http",
            tag = name,
            server = server,
            serverPort = port,
            username = username,
            password = password,
            tls = tlsConfig,
            path = path,
            headers = headers
        )
    }

    protected override fun parseSocks(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound? {
        if (server == null || port == null) return null

        val tlsEnabled = asBool(map["tls"]) == true
        if (tlsEnabled) {
            android.util.Log.w("ClashYamlParser", "SOCKS proxy '$name' has TLS enabled but sing-box does not support it, importing without TLS")
        }

        val username = asString(map["username"])
        val password = asString(map["password"])

        return Outbound(
            type = "socks",
            tag = name,
            server = server,
            serverPort = port,
            username = username,
            password = password
        )
    }

    protected override fun parseShadowTLS(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String?): Outbound? {
        if (server == null || port == null) return null
        val password = asString(map["password"]) ?: return null
        val version = asInt(map["version"]) ?: 3
        val sni = asString(map["sni"]) ?: server
        val fingerprint = asString(map["client-fingerprint"]) ?: globalFingerprint

        return Outbound(
            type = "shadowtls",
            tag = name,
            server = server,
            serverPort = port,
            version = version,
            password = password,
            tls = buildTlsConfig(
                map = map,
                serverName = sni,
                utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
            )
        )
    }

    // --- Helpers ---

    /**
     * smux:
     *   enabled: true
     *   protocol: smux  # smux/yamux/h2mux
     *   max-connections: 4
     *   min-streams: 4
     *   max-streams: 0
     *   padding: false
     */

    protected override fun parseSmux(map: Map<*, *>): MultiplexConfig? {
        val smuxOpts = map["smux"] as? Map<*, *> ?: return null
        val enabled = asBool(smuxOpts["enabled"]) == true
        if (!enabled) return null

        return MultiplexConfig(
            enabled = true,
            protocol = asString(smuxOpts["protocol"]) ?: "smux",
            maxConnections = asInt(smuxOpts["max-connections"]),
            minStreams = asInt(smuxOpts["min-streams"]),
            maxStreams = asInt(smuxOpts["max-streams"]),
            padding = asBool(smuxOpts["padding"])
        )
    }

    protected override fun buildTlsConfig(
        map: Map<*, *>,
        enabled: Boolean,
        serverName: String?,
        insecure: Boolean?,
        alpn: List<String>?,
        minVersion: String?,
        utls: UtlsConfig?,
        reality: com.kunk.singbox.model.RealityConfig?): TlsConfig {
        return TlsConfig(
            enabled = enabled,
            serverName = serverName,
            insecure = insecure,
            alpn = alpn,
            minVersion = minVersion,
            utls = utls,
            reality = reality,
            ca = firstNonBlankString(map, "ca", "ca-cert", "ca_cert", "caPem", "ca_pem"),
            caPath = firstNonBlankString(map, "ca_path", "ca-path"),
            certificate = firstNonBlankString(
                map,
                "certificate",
                "cert",
                "client-cert",
                "client_cert"
            ),
            certificatePath = firstNonBlankString(
                map,
                "certificate_path",
                "certificate-path",
                "cert_path",
                "cert-path",
                "client-cert-path",
                "client_cert_path"
            ),
            key = firstNonBlankString(map, "key", "client-key", "client_key"),
            keyPath = firstNonBlankString(map, "key_path", "key-path", "client-key-path", "client_key_path")
        )
    }

    protected override fun firstNonBlankString(map: Map<*, *>, vararg keys: String): String? {
        return keys.firstNotNullOfOrNull { key ->
            asString(map[key])?.takeIf { it.isNotBlank() }
        }
    }

    protected override fun asNestedMap(v: Any?): Map<*, *>? = v as? Map<*, *>

    protected override fun getUserAgent(fingerprint: String?): String {
        return if (fingerprint?.contains("chrome", ignoreCase = true) == true) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
        } else {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
        }
    }

    protected override fun asString(v: Any?): String? = when (v) {
        is String -> v
        is Number -> v.toString()
        is Boolean -> v.toString()
        is List<*> -> v.firstOrNull()?.toString()
        else -> null
    }

    protected override fun asInt(v: Any?): Int? = when (v) {
        is Int -> v
        is Long -> v.toInt()
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    protected override fun asBool(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is String -> when (v.lowercase()) {
            "true", "1", "yes", "y" -> true
            "false", "0", "no", "n" -> false
            else -> null
        }
        else -> null
    }

    protected override fun asStringList(v: Any?): List<String>? {
        return when (v) {
            is List<*> -> v.mapNotNull { asString(it) }.takeIf { it.isNotEmpty() }
            is String -> v.split(",").map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    protected override fun buildWsOrHttpUpgradeTransport(
        wsOpts: Map<*, *>?,
        path: String,
        headers: Map<String, String>,
        host: String?
    ): TransportConfig {
        val maxEarlyData = asInt(wsOpts?.get("max-early-data"))?.takeIf { it > 0 }
        val earlyDataHeaderName = if (maxEarlyData != null) {
            asString(wsOpts?.get("early-data-header-name")) ?: "Sec-WebSocket-Protocol"
        } else {
            null
        }
        val isHttpUpgrade = asBool(wsOpts?.get("v2ray-http-upgrade")) == true

        return TransportConfig(
            type = if (isHttpUpgrade) "httpupgrade" else "ws",
            path = path,
            headers = if (isHttpUpgrade) headers.withoutHostHeader().ifEmpty { null } else headers,
            host = if (isHttpUpgrade) host?.takeIf { it.isNotBlank() }?.let { listOf(it) } else null,
            maxEarlyData = if (isHttpUpgrade) null else maxEarlyData,
            earlyDataHeaderName = if (isHttpUpgrade) null else earlyDataHeaderName
        )
    }

    protected override fun Map<String, String>.withoutHostHeader(): Map<String, String> {
        return filterKeys { !it.equals("Host", ignoreCase = true) }
    }
}
