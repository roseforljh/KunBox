package com.kunk.singbox.utils.parser

import com.google.gson.JsonPrimitive
import com.kunk.singbox.model.MultiplexConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import com.kunk.singbox.model.UInt32JsonAdapter
import com.kunk.singbox.model.UtlsConfig

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
            privateKey = privateKey?.let(::listOf)
        )
    }

    protected override fun parseWireGuard(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound? {
        if (server == null || port == null) return null
        val privateKey = asString(map["private-key"]) ?: return null
        val publicKey = asString(map["public-key"]) ?: return null
        val localAddress = asStringList(map["ip"])?.takeIf { it.isNotEmpty() } ?: return null
        val reserved = asStringList(map["reserved"])
            ?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.isNotEmpty() }

        val peer = com.kunk.singbox.model.WireGuardPeer(
            server = server,
            serverPort = port,
            publicKey = publicKey,
            preSharedKey = asString(map["pre-shared-key"]),
            allowedIps = asStringList(map["allowed-ips"]),
            persistentKeepaliveInterval = asInt(map["persistent-keepalive"]),
            reserved = reserved
        )

        return Outbound(
            type = "wireguard",
            tag = name,
            localAddress = localAddress,
            privateKey = listOf(privateKey),
            peers = listOf(peer),
            mtu = asInt(map["mtu"]),
            listenPort = asInt(map["listen-port"]),
            udpTimeout = asString(map["udp-timeout"]),
            workers = asInt(map["workers"]),
            system = asBool(map["system"]),
            endpointName = asString(map["interface-name"])
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

        val rawNetwork = asString(map["network"]) ?: asString(map["proto"]) ?: asString(map["type"])
        val useQuic = rawNetwork.equals("quic", ignoreCase = true)

        val congestionControl = asString(map["congestion-control"]) ?: asString(map["cc"])
        val uot = asBool(map["uot"]) == true ||
            asBool(map["udp-over-tcp"]) == true ||
            asBool(map["udp_over_tcp"]) == true

        val extraHeaders = (map["headers"] as? Map<*, *>)
            ?.mapNotNull { (key, value) ->
                val normalizedKey = asString(key)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val normalizedValue = asString(value)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                normalizedKey to normalizedValue
            }
            ?.toMap()
            ?.toMutableMap()
            ?: mutableMapOf()
        host?.takeIf { it.isNotBlank() }?.let { extraHeaders.putIfAbsent("Host", it) }

        return Outbound(
            type = "naive",
            tag = name,
            server = server,
            serverPort = port,
            username = username,
            password = password,
            network = listOf(if (useQuic) "quic" else "h2"),
            extraHeaders = extraHeaders.ifEmpty { null },
            quic = useQuic,
            quicCongestionControl = if (useQuic) congestionControl else null,
            congestionControl = if (useQuic) null else congestionControl,
            udpOverTcp = if (uot) com.kunk.singbox.model.UdpOverTcpConfig(enabled = true) else null,
            tls = TlsConfig(
                enabled = true,
                serverName = sni,
                ca = firstNonEmptyStringList(map, "ca", "ca-cert", "ca_cert", "caPem", "ca_pem"),
                caPath = firstNonBlankString(map, "ca_path", "ca-path")
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
            obfs = if (obfs != null) {
                com.kunk.singbox.model.ObfsConfig(type = obfs, stringValue = true)
            } else {
                null
            }
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
        val version = asString(map["version"]) ?: "5"
        val network = asString(map["network"])
        val udpOverTcp = asBool(map["udp-over-tcp"]) == true || asBool(map["udp_over_tcp"]) == true

        return Outbound(
            type = "socks",
            tag = name,
            server = server,
            serverPort = port,
            version = JsonPrimitive(version),
            username = username,
            password = password,
            network = network?.let(::listOf),
            udpOverTcp = if (udpOverTcp) com.kunk.singbox.model.UdpOverTcpConfig(enabled = true) else null
        )
    }

    protected override fun parseShadowTLS(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String?): Outbound? {
        if (server == null || port == null) return null
        val version = asInt(map["version"]) ?: 3
        val password = asString(map["password"])
        if (version >= 2 && password.isNullOrBlank()) return null
        val sni = asString(map["sni"]) ?: server
        val fingerprint = asString(map["client-fingerprint"]) ?: globalFingerprint

        return Outbound(
            type = "shadowtls",
            tag = name,
            server = server,
            serverPort = port,
            version = JsonPrimitive(version),
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
            ca = firstNonEmptyStringList(map, "ca", "ca-cert", "ca_cert", "caPem", "ca_pem"),
            caPath = firstNonBlankString(map, "ca_path", "ca-path"),
            certificate = firstNonEmptyStringList(
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
            key = firstNonEmptyStringList(map, "key", "client-key", "client_key"),
            keyPath = firstNonBlankString(map, "key_path", "key-path", "client-key-path", "client_key_path")
        )
    }

    protected override fun firstNonBlankString(map: Map<*, *>, vararg keys: String): String? {
        return keys.firstNotNullOfOrNull { key ->
            asString(map[key])?.takeIf { it.isNotBlank() }
        }
    }

    private fun firstNonEmptyStringList(map: Map<*, *>, vararg keys: String): List<String>? {
        return keys.firstNotNullOfOrNull { key ->
            when (val value = map[key]) {
                is List<*> -> value.mapNotNull { item ->
                    asString(item)?.takeIf { it.isNotBlank() }
                }.takeIf { it.isNotEmpty() }
                else -> asString(value)?.takeIf { it.isNotBlank() }?.let(::listOf)
            }
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

    @Suppress("CognitiveComplexMethod")
    protected override fun buildWsOrHttpUpgradeTransport(
        wsOpts: Map<*, *>?,
        path: String,
        headers: Map<String, String>,
        host: String?,
        forceHttpUpgrade: Boolean
    ): TransportConfig? {
        val isHttpUpgrade = forceHttpUpgrade || asBool(wsOpts?.get("v2ray-http-upgrade")) == true
        val rawMaxEarlyData = asString(wsOpts?.get("max-early-data")).takeUnless { isHttpUpgrade }
        val maxEarlyData = rawMaxEarlyData?.toLongOrNull()?.let { value ->
            runCatching { UInt32JsonAdapter.requireValue(value) }.getOrNull()
        }
        if (rawMaxEarlyData != null && maxEarlyData == null) return null
        val earlyDataHeaderName = if (maxEarlyData != null) {
            asString(wsOpts?.get("early-data-header-name")) ?: "Sec-WebSocket-Protocol"
        } else {
            null
        }
        val httpUpgradeHost = host
            ?.split(',')
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()

        return TransportConfig(
            type = if (isHttpUpgrade) "httpupgrade" else "ws",
            path = path,
            headers = if (isHttpUpgrade) headers.withoutHostHeader().ifEmpty { null } else headers,
            host = if (isHttpUpgrade) httpUpgradeHost?.let { listOf(it) } else null,
            maxEarlyData = if (isHttpUpgrade) null else maxEarlyData,
            earlyDataHeaderName = if (isHttpUpgrade) null else earlyDataHeaderName
        )
    }

    protected override fun Map<String, String>.withoutHostHeader(): Map<String, String> {
        return filterKeys { !it.equals("Host", ignoreCase = true) }
    }
}
