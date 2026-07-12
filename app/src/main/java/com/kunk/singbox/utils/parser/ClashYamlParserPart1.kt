package com.kunk.singbox.utils.parser

import com.google.gson.JsonPrimitive
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.model.TransportConfig
import com.kunk.singbox.model.UtlsConfig
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException

private val CLASH_V2RAY_NETWORKS = setOf(
    "tcp", "http", "h2", "ws", "quic", "grpc", "httpupgrade", "xhttp", "splithttp"
)

@Suppress("TooManyFunctions")
abstract class ClashYamlParserPart1 : ClashYamlParserBase() {
    override fun canParse(content: String): Boolean {
        val trimmed = content.trim()

        val nodeLinkPrefixes = listOf(
            "vmess://", "vless://", "ss://", "trojan://",
            "hysteria2://", "hy2://", "hysteria://",
            "tuic://", "anytls://", "wireguard://", "wg://", "ssh://"
        )
        if (nodeLinkPrefixes.any { trimmed.startsWith(it) }) {
            return false
        }

        return trimmed.contains("proxies:") || trimmed.contains("proxy-groups:")
    }

    override fun parse(content: String): SingBoxConfig? {
        val root = try {
            Yaml().load<Any>(content)
        } catch (_: YAMLException) {
            return null
        } catch (_: Exception) {
            return null
        }

        val rootMap = (root as? Map<*, *>) ?: return null
        val proxiesRaw = rootMap["proxies"] as? List<*> ?: return null

        val globalClientFingerprint = asString(rootMap["global-client-fingerprint"])

        val globalTlsMinVersion = asString(rootMap["tls-version"])
            ?: asString(rootMap["min-tls-version"])

        val outbounds = mutableListOf<Outbound>()
        var skippedCount = 0

        for (p in proxiesRaw) {
            val m = p as? Map<*, *> ?: continue

            val obs = parseProxy(m, globalClientFingerprint, globalTlsMinVersion)
            if (obs != null && obs.isNotEmpty()) {
                outbounds.addAll(obs)
            } else {
                skippedCount++
            }
        }

        if (skippedCount > 0) {
            android.util.Log.d("ClashYamlParser", "Parsed ${outbounds.size} proxies, skipped $skippedCount")
        }

        val proxyGroupsRaw = rootMap["proxy-groups"] as? List<*>
        if (proxyGroupsRaw != null) {
            val knownGroupNames = proxyGroupsRaw.mapNotNull { group ->
                (group as? Map<*, *>)?.let { asString(it["name"]) }
            }.toSet()
            val knownOutboundTags = outbounds.map { it.tag }.toSet() + knownGroupNames
            for (g in proxyGroupsRaw) {
                val gm = g as? Map<*, *> ?: continue
                val name = asString(gm["name"]) ?: continue
                val type = asString(gm["type"])?.lowercase() ?: continue
                val proxies = normalizeProxyGroupRefs(gm["proxies"], knownOutboundTags)
                if (proxies.isEmpty()) continue

                when (type) {
                    "select", "selector" -> {
                        outbounds.add(
                            Outbound(
                                type = "selector",
                                tag = name,
                                outbounds = proxies,
                                default = proxies.firstOrNull(),
                                interruptExistConnections = false
                            )
                        )
                    }
                    "url-test", "urltest" -> {
                        val url = sanitizeUrlTestUrl(asString(gm["url"]))
                        val interval = asString(gm["interval"]) ?: asInt(gm["interval"])?.toString() ?: "300s"
                        val tolerance = asInt(gm["tolerance"]) ?: 50
                        outbounds.add(
                            Outbound(
                                type = "urltest",
                                tag = name,
                                outbounds = proxies,
                                url = url,
                                interval = interval,
                                tolerance = tolerance,
                                interruptExistConnections = false
                            )
                        )
                    }
                }
            }
        }

        if (outbounds.isEmpty()) return null

        return SingBoxConfig(outbounds = outbounds)
    }

    protected override fun sanitizeUrlTestUrl(rawUrl: String?): String {
        return AppSettings.normalizeLatencyTestUrl(rawUrl)
    }

    protected override fun normalizeProxyGroupRefs(rawProxies: Any?, knownOutboundTags: Set<String>): List<String> {
        return (rawProxies as? List<*>)
            ?.mapNotNull { value -> asString(value)?.trim()?.takeIf { it.isNotBlank() } }
            ?.mapNotNull { normalizeProxyGroupRef(it, knownOutboundTags) }
            ?.distinct()
            .orEmpty()
    }

    protected override fun normalizeProxyGroupRef(ref: String, knownOutboundTags: Set<String>): String? {
        if (knownOutboundTags.contains(ref)) return ref
        return when {
            ref.equals("DIRECT", ignoreCase = true) -> "direct"
            ref.equals("REJECT", ignoreCase = true) -> null
            ref.equals("REJECT-DROP", ignoreCase = true) -> null
            ref.equals("PASS", ignoreCase = true) -> null
            ref.equals("GLOBAL", ignoreCase = true) -> null
            else -> ref
        }
    }

    protected override fun parseProxy(proxyMap: Map<*, *>, globalFingerprint: String?, globalTlsMinVersion: String?): List<Outbound>? {
        val name = asString(proxyMap["name"]) ?: run {
            android.util.Log.w("ClashYamlParser", "Proxy missing name field: ${proxyMap.keys}")
            return null
        }
        val type = asString(proxyMap["type"])?.lowercase() ?: run {
            android.util.Log.w("ClashYamlParser", "Proxy '$name' missing type field")
            return null
        }

        val server = asString(proxyMap["server"])
        val port = asInt(proxyMap["port"])

        if (type == "ss" || type == "shadowsocks") {
            val outbounds = parseShadowsocksWithPlugin(proxyMap, name, server, port, globalFingerprint)
            if (outbounds != null && outbounds.isNotEmpty()) {
                return outbounds
            }
            return null
        }

        val outbound = when (type) {
            "vless" -> parseVLess(proxyMap, name, server, port, globalFingerprint, globalTlsMinVersion)
            "vmess" -> parseVMess(proxyMap, name, server, port, globalFingerprint, globalTlsMinVersion)
            "trojan" -> parseTrojan(proxyMap, name, server, port, globalFingerprint, globalTlsMinVersion)
            "hysteria2", "hy2" -> parseHysteria2(proxyMap, name, server, port, globalFingerprint, globalTlsMinVersion)
            "hysteria" -> parseHysteria(proxyMap, name, server, port, globalFingerprint, globalTlsMinVersion)
            "tuic", "tuic-v5" -> parseTuic(proxyMap, name, server, port, globalFingerprint, globalTlsMinVersion)
            "anytls" -> parseAnyTLS(proxyMap, name, server, port, globalFingerprint, globalTlsMinVersion)
            "naive" -> parseNaive(proxyMap, name, server, port, globalFingerprint, globalTlsMinVersion)
            "ssh" -> parseSSH(proxyMap, name, server, port)
            "wireguard" -> parseWireGuard(proxyMap, name, server, port)
            "http" -> parseHttp(proxyMap, name, server, port, globalFingerprint, globalTlsMinVersion)
            "socks5" -> parseSocks(proxyMap, name, server, port)
            "shadowtls" -> parseShadowTLS(proxyMap, name, server, port, globalFingerprint)
            else -> null
        }

        if (outbound == null && (type.contains("tuic") || type.contains("anytls"))) {
            android.util.Log.w("ClashYamlParser", "Failed to parse $type node '$name'. Server: $server, Port: $port, Map: $proxyMap")
        }

        return outbound?.let { listOf(it) }
    }

    private fun buildDirectHttpUpgradeTransport(
        map: Map<*, *>,
        fallbackHost: String,
        fingerprint: String?
    ): TransportConfig? {
        val opts = map["http-upgrade-opts"] as? Map<*, *> ?: map["ws-opts"] as? Map<*, *>
        val path = asString(opts?.get("path")) ?: "/"
        val headers = mutableMapOf<String, String>()
        (opts?.get("headers") as? Map<*, *>)?.forEach { (key, value) ->
            val headerName = asString(key) ?: return@forEach
            val headerValue = asString(value) ?: return@forEach
            headers[headerName] = headerValue
        }
        val host = headers["Host"]
            ?: headers["host"]
            ?: asString(opts?.get("host"))
            ?: fallbackHost
        if (!headers.containsKey("User-Agent")) {
            headers["User-Agent"] = getUserAgent(fingerprint)
        }
        return buildWsOrHttpUpgradeTransport(
            wsOpts = opts,
            path = path,
            headers = headers,
            host = host,
            forceHttpUpgrade = true
        )
    }

    private fun buildXhttpTransport(map: Map<*, *>): TransportConfig? {
        val opts = map["xhttp-opts"] as? Map<*, *>
            ?: map["splithttp-opts"] as? Map<*, *>
            ?: return null
        val extra = asNestedMap(opts["extra"])
        val encryption = asString(extra?.get("encryption"))
        if (!encryption.isNullOrBlank() && !encryption.equals("none", ignoreCase = true)) {
            return null
        }
        return TransportConfig(
            type = "xhttp",
            path = asString(opts["path"]) ?: "/",
            host = asStringList(opts["host"]),
            mode = asString(opts["mode"]),
            xPaddingBytes = asString(opts["xPaddingBytes"]) ?: asString(opts["x-padding-bytes"])
                ?: asString(opts["x_padding_bytes"]),
            scMaxEachPostBytes = asLong(opts["scMaxEachPostBytes"] ?: opts["sc_max_each_post_bytes"]),
            scMinPostsIntervalMs = asLong(opts["scMinPostsIntervalMs"] ?: opts["sc_min_posts_interval_ms"]),
            scMaxBufferedPosts = asLong(opts["scMaxBufferedPosts"] ?: opts["sc_max_buffered_posts"]),
            noGRPCHeader = asBool(opts["noGRPCHeader"] ?: opts["no_grpc_header"]),
            noSSEHeader = asBool(opts["noSSEHeader"] ?: opts["no_sse_header"])
        )
    }

    private fun asLong(value: Any?): Long? {
        return when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
    protected override fun parseVLess(
        map: Map<*, *>,
        name: String,
        server: String?,
        port: Int?,
        globalFingerprint: String?,
        globalTlsMinVersion: String?
    ): Outbound? {
        if (server == null || port == null) return null
        val uuid = asString(map["uuid"]) ?: return null
        val network = asString(map["network"])?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (network != null && network !in CLASH_V2RAY_NETWORKS) return null
        val tlsEnabled = asBool(map["tls"]) == true
        val serverName = asString(map["servername"]) ?: asString(map["sni"]) ?: server

        val fingerprint = asString(map["client-fingerprint"]) ?: globalFingerprint
        val insecure = asBool(map["skip-cert-verify"]) == true
        val alpn = asStringList(map["alpn"])
        val flow = asString(map["flow"])
        val packetEncoding = asString(map["packet-encoding"])?.takeIf { it.isNotBlank() }

        val tlsMinVersion = asString(map["tls-version"]) ?: asString(map["min-tls-version"]) ?: globalTlsMinVersion

        // Reality support
        val realityOpts = asNestedMap(map["reality-opts"])
        val realityPublicKey = asString(realityOpts?.get("public-key"))
        val realityShortId = asString(realityOpts?.get("short-id"))

        val finalAlpn = if (tlsEnabled && network == "ws" && (alpn == null || alpn.isEmpty())) listOf("http/1.1") else alpn

        val tlsConfig = if (tlsEnabled) {
            buildTlsConfig(
                map = map,
                serverName = serverName,
                insecure = insecure,
                alpn = finalAlpn,
                minVersion = tlsMinVersion,
                utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) },
                reality = if (realityPublicKey != null) {
                    com.kunk.singbox.model.RealityConfig(
                        enabled = true,
                        publicKey = realityPublicKey,
                        shortId = realityShortId
                    )
                } else null
            )
        } else null

        val transport = when (network) {
            "ws" -> {
                val wsOpts = map["ws-opts"] as? Map<*, *>
                val path = asString(wsOpts?.get("path")) ?: "/"
                val headersRaw = wsOpts?.get("headers") as? Map<*, *>
                val headers = mutableMapOf<String, String>()
                headersRaw?.forEach { (k, v) ->
                    val ks = asString(k) ?: return@forEach
                    val vs = asString(v) ?: return@forEach
                    headers[ks] = vs
                }

                val host = headers["Host"] ?: headers["host"] ?: serverName
                if (!host.isNullOrBlank()) headers["Host"] = host

                if (!headers.containsKey("User-Agent")) {
                    headers["User-Agent"] = getUserAgent(fingerprint)
                }

                buildWsOrHttpUpgradeTransport(
                    wsOpts = wsOpts,
                    path = path,
                    headers = headers,
                    host = host
                ) ?: return null
            }
            "grpc" -> {
                val grpcOpts = map["grpc-opts"] as? Map<*, *>
                val serviceName = asString(grpcOpts?.get("grpc-service-name"))
                    ?: asString(grpcOpts?.get("service-name"))
                    ?: asString(map["grpc-service-name"])
                    ?: ""
                TransportConfig(type = "grpc", serviceName = serviceName)
            }
            "h2", "http" -> {
                val h2Opts = map["h2-opts"] as? Map<*, *>
                val path = asString(map["path"]) ?: asString(h2Opts?.get("path"))
                val host = asString(map["host"])?.let { listOf(it) } ?: asStringList(h2Opts?.get("host"))
                TransportConfig(type = "http", path = path, host = host)
            }
            "xhttp", "splithttp" -> buildXhttpTransport(map) ?: return null
            "quic" -> TransportConfig(type = "quic")
            "httpupgrade" -> buildDirectHttpUpgradeTransport(map, serverName, fingerprint) ?: return null
            else -> null
        }
        if (network == "quic" && tlsConfig?.enabled != true) return null

        val multiplex = parseSmux(map)

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
            multiplex = multiplex
        )
    }

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
    protected override fun parseVMess(
        map: Map<*, *>,
        name: String,
        server: String?,
        port: Int?,
        globalFingerprint: String?,
        globalTlsMinVersion: String?
    ): Outbound? {
        if (server == null || port == null) return null
        val uuid = asString(map["uuid"]) ?: return null

        val alterId = asInt(map["alterId"]) ?: 0
        android.util.Log.i("ClashYamlParser", "VMess node '$name': alterId=$alterId (raw=${map["alterId"]})")
        val cipher = asString(map["cipher"]) ?: "auto"
        val network = asString(map["network"])?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (network != null && network !in CLASH_V2RAY_NETWORKS) return null
        val tlsEnabled = asBool(map["tls"]) == true
        val serverName = asString(map["servername"]) ?: asString(map["sni"]) ?: server

        val fingerprint = asString(map["client-fingerprint"]) ?: globalFingerprint

        val insecure = asBool(map["skip-cert-verify"]) == true
        val alpn = asStringList(map["alpn"])
        val packetEncoding = asString(map["packet-encoding"])?.takeIf { it.isNotBlank() }

        val tlsMinVersion = asString(map["tls-version"]) ?: asString(map["min-tls-version"]) ?: globalTlsMinVersion

        val finalAlpn = if (tlsEnabled && network == "ws" && (alpn == null || alpn.isEmpty())) listOf("http/1.1") else alpn

        val tlsConfig = if (tlsEnabled) {
            buildTlsConfig(
                map = map,
                serverName = serverName,
                insecure = insecure,
                alpn = finalAlpn,
                minVersion = tlsMinVersion,
                utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
            )
        } else null

        val transport = when (network) {
            "ws" -> {
                val wsOpts = map["ws-opts"] as? Map<*, *>
                val path = asString(wsOpts?.get("path")) ?: "/"
                val headersRaw = wsOpts?.get("headers") as? Map<*, *>
                val headers = mutableMapOf<String, String>()
                headersRaw?.forEach { (k, v) ->
                    val ks = asString(k) ?: return@forEach
                    val vs = asString(v) ?: return@forEach
                    headers[ks] = vs
                }
                val host = headers["Host"] ?: headers["host"] ?: serverName
                if (!host.isNullOrBlank()) headers["Host"] = host
                if (!headers.containsKey("User-Agent")) {
                    headers["User-Agent"] = getUserAgent(fingerprint)
                }

                buildWsOrHttpUpgradeTransport(
                    wsOpts = wsOpts,
                    path = path,
                    headers = headers,
                    host = host
                ) ?: return null
            }
            "grpc" -> {
                val grpcOpts = map["grpc-opts"] as? Map<*, *>
                val serviceName = asString(grpcOpts?.get("grpc-service-name")) ?: ""
                TransportConfig(type = "grpc", serviceName = serviceName)
            }
            "h2", "http" -> {
                val h2Opts = map["h2-opts"] as? Map<*, *>
                val path = asString(h2Opts?.get("path"))
                val host = asStringList(h2Opts?.get("host"))
                TransportConfig(type = "http", path = path, host = host)
            }
            "xhttp", "splithttp" -> buildXhttpTransport(map) ?: return null
            "quic" -> TransportConfig(type = "quic")
            "httpupgrade" -> buildDirectHttpUpgradeTransport(map, serverName, fingerprint) ?: return null
            else -> null
        }
        if (network == "quic" && tlsConfig?.enabled != true) return null

        val multiplex = parseSmux(map)

        return Outbound(
            type = "vmess",
            tag = name,
            server = server,
            serverPort = port,
            uuid = uuid,
            alterId = if (alterId > 0) alterId else null,

            security = cipher,
            tls = tlsConfig,
            transport = transport,
            packetEncoding = packetEncoding,
            multiplex = multiplex
        )
    }

    /**
     *
     *   - type: ss
     *     plugin: shadow-tls
     *     plugin-opts:
     *       host: example.com
     *       password: xxx
     *       version: 3
     *
     */

    protected override fun parseShadowsocksWithPlugin(
        map: Map<*, *>,
        name: String,
        server: String?,
        port: Int?,
        globalFingerprint: String?
    ): List<Outbound>? {
        if (server == null || port == null) return null
        val cipher = asString(map["cipher"]) ?: return null
        val password = asString(map["password"]) ?: return null
        val plugin = asString(map["plugin"])?.lowercase()
        val pluginOpts = map["plugin-opts"] as? Map<*, *>
        val serializedPluginOpts = pluginOpts
            ?.mapNotNull { (key, value) ->
                val optionName = asString(key)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val optionValue = asString(value)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                "$optionName=$optionValue"
            }
            ?.joinToString(";")
            ?.takeIf { it.isNotBlank() }

        val multiplex = parseSmux(map)
        val udpEnabled = asBool(map["udp"]) != false

        when (plugin) {
            "shadow-tls", "shadowtls" -> {
                if (pluginOpts == null) {
                    android.util.Log.w("ClashYamlParser", "SS node '$name' has shadow-tls plugin but no plugin-opts")
                    return null
                }

                val stlsPassword = asString(pluginOpts["password"]) ?: return null
                val stlsVersion = asInt(pluginOpts["version"]) ?: 3
                val stlsHost = asString(pluginOpts["host"]) ?: server
                val fingerprint = asString(map["client-fingerprint"]) ?: globalFingerprint

                val shadowTlsTag = "${name}_shadowtls"

                val shadowTlsOutbound = Outbound(
                    type = "shadowtls",
                    tag = shadowTlsTag,
                    server = server,
                    serverPort = port,
                    version = JsonPrimitive(stlsVersion),
                    password = stlsPassword,
                    tls = buildTlsConfig(
                        map = pluginOpts,
                        serverName = stlsHost,
                        utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
                    )
                )

                val ssOutbound = Outbound(
                    type = "shadowsocks",
                    tag = name,
                    server = server,
                    serverPort = port,
                    method = cipher,
                    password = password,
                    detour = shadowTlsTag,
                    multiplex = multiplex,
                    network = if (!udpEnabled) listOf("tcp") else null
                )

                return listOf(ssOutbound, shadowTlsOutbound)
            }

            "obfs", "obfs-local", "simple-obfs" -> {
                return listOf(Outbound(
                    type = "shadowsocks",
                    tag = name,
                    server = server,
                    serverPort = port,
                    method = cipher,
                    password = password,
                    multiplex = multiplex,
                    plugin = plugin,
                    pluginOpts = serializedPluginOpts,
                    network = if (!udpEnabled) listOf("tcp") else null
                ))
            }

            "v2ray-plugin" -> {
                return listOf(Outbound(
                    type = "shadowsocks",
                    tag = name,
                    server = server,
                    serverPort = port,
                    method = cipher,
                    password = password,
                    multiplex = multiplex,
                    plugin = plugin,
                    pluginOpts = serializedPluginOpts,
                    network = if (!udpEnabled) listOf("tcp") else null
                ))
            }

            null, "" -> {
                return listOf(Outbound(
                    type = "shadowsocks",
                    tag = name,
                    server = server,
                    serverPort = port,
                    method = cipher,
                    password = password,
                    multiplex = multiplex,
                    plugin = plugin,
                    pluginOpts = serializedPluginOpts,
                    network = if (!udpEnabled) listOf("tcp") else null
                ))
            }

            else -> {
                android.util.Log.w("ClashYamlParser", "SS node '$name' has unsupported plugin: $plugin")
                return listOf(Outbound(
                    type = "shadowsocks",
                    tag = name,
                    server = server,
                    serverPort = port,
                    method = cipher,
                    password = password,
                    multiplex = multiplex,
                    network = if (!udpEnabled) listOf("tcp") else null
                ))
            }
        }
    }

    protected override fun parseTrojan(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String?, globalTlsMinVersion: String?): Outbound? {
        if (server == null || port == null) return null
        val password = asString(map["password"]) ?: return null
        val network = asString(map["network"])?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (network != null && network !in CLASH_V2RAY_NETWORKS) return null
        val sni = asString(map["sni"]) ?: server

        val fingerprint = asString(map["client-fingerprint"]) ?: globalFingerprint
        val insecure = asBool(map["skip-cert-verify"]) == true
        val alpn = asStringList(map["alpn"])

        val tlsMinVersion = asString(map["tls-version"]) ?: asString(map["min-tls-version"]) ?: globalTlsMinVersion

        val tlsConfig = buildTlsConfig(
            map = map,
            serverName = sni,
            insecure = insecure,
            alpn = alpn,
            minVersion = tlsMinVersion,
            utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
        )

        val transport = parseTrojanTransport(network, map, sni, fingerprint)
        if (network != null && network != "tcp" && transport == null) return null

        val multiplex = parseSmux(map)

        return Outbound(
            type = "trojan",
            tag = name,
            server = server,
            serverPort = port,
            password = password,
            tls = tlsConfig,
            transport = transport,
            multiplex = multiplex
        )
    }

    protected override fun parseTrojanTransport(
        network: String?,
        map: Map<*, *>,
        sni: String,
        fingerprint: String?
    ): TransportConfig? {
        return when (network) {
            "ws" -> {
                val wsOpts = map["ws-opts"] as? Map<*, *>
                val path = asString(wsOpts?.get("path")) ?: "/"
                val headersRaw = wsOpts?.get("headers") as? Map<*, *>
                val headers = mutableMapOf<String, String>()
                headersRaw?.forEach { (k, v) -> headers[asString(k) ?: ""] = asString(v) ?: "" }
                if (!headers.containsKey("Host")) headers["Host"] = sni
                if (!headers.containsKey("User-Agent")) {
                    headers["User-Agent"] = getUserAgent(fingerprint)
                }
                val host = headers["Host"] ?: headers["host"] ?: sni

                buildWsOrHttpUpgradeTransport(
                    wsOpts = wsOpts,
                    path = path,
                    headers = headers,
                    host = host
                ) ?: return null
            }
            "grpc" -> {
                val grpcOpts = map["grpc-opts"] as? Map<*, *>
                val serviceName = asString(grpcOpts?.get("grpc-service-name")) ?: ""
                TransportConfig(type = "grpc", serviceName = serviceName)
            }
            "h2", "http" -> {
                val h2Opts = map["h2-opts"] as? Map<*, *>
                val path = asString(map["path"]) ?: asString(h2Opts?.get("path"))
                val host = asString(map["host"])?.let { listOf(it) } ?: asStringList(h2Opts?.get("host"))
                TransportConfig(type = "http", path = path, host = host)
            }
            "xhttp", "splithttp" -> buildXhttpTransport(map) ?: return null
            "quic" -> TransportConfig(type = "quic")
            "httpupgrade" -> buildDirectHttpUpgradeTransport(map, sni, fingerprint)
            else -> null
        }
    }

    protected override fun parseHysteria2(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String?, globalTlsMinVersion: String?): Outbound? {
        if (server == null || port == null) return null
        val password = asString(map["password"]) ?: return null
        val sni = asString(map["sni"]) ?: server
        val insecure = asBool(map["skip-cert-verify"]) == true
        val alpn = asStringList(map["alpn"])

        val fingerprint = asString(map["client-fingerprint"]) ?: globalFingerprint
        val obfs = asString(map["obfs"])
        val obfsPassword = asString(map["obfs-password"])

        val tlsMinVersion = asString(map["tls-version"]) ?: asString(map["min-tls-version"]) ?: globalTlsMinVersion

        val network = asString(map["network"])

        val upMbps = asInt(map["up"]) ?: asInt(map["up-mbps"])
        val downMbps = asInt(map["down"]) ?: asInt(map["down-mbps"])

        val portsStr = asString(map["ports"])?.takeIf { it.isNotBlank() }
        val serverPorts = portsStr?.let { listOf(it) }
        val hopInterval = asString(map["hop-interval"])?.takeIf { it.isNotBlank() }

        return Outbound(
            type = "hysteria2",
            tag = name,
            server = server,
            serverPort = port,
            password = password,
            network = network?.let(::listOf),
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
            obfs = if (obfs != null) com.kunk.singbox.model.ObfsConfig(type = obfs, password = obfsPassword) else null
        )
    }
}
