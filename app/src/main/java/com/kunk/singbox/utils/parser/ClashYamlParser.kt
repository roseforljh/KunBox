package com.kunk.singbox.utils.parser

import com.kunk.singbox.model.MultiplexConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import com.kunk.singbox.model.UtlsConfig
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import java.net.InetAddress
import java.net.URI

/**
 */
class ClashYamlParser : SubscriptionParser {
    private companion object {
        private const val DEFAULT_URL_TEST_URL = "http://www.gstatic.com/generate_204"
    }

    override fun canParse(content: String): Boolean {
        val trimmed = content.trim()

        val nodeLinkPrefixes = listOf(
            "vmess://", "vless://", "ss://", "trojan://",
            "hysteria2://", "hy2://", "hysteria://",
            "tuic://", "anytls://", "wireguard://", "ssh://"
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

    private fun sanitizeUrlTestUrl(rawUrl: String?): String {
        val trimmed = rawUrl?.trim().orEmpty()
        val uri = runCatching { URI(trimmed) }.getOrNull()
        return if (trimmed.isNotBlank() && uri != null && isSafeUrlTestUri(uri)) {
            trimmed
        } else {
            DEFAULT_URL_TEST_URL
        }
    }

    private fun isSafeUrlTestUri(uri: URI): Boolean {
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.trim()?.lowercase().orEmpty()
        return (scheme == "http" || scheme == "https") &&
            host.isNotBlank() &&
            host != "localhost" &&
            !isUnsafeLiteralAddress(host)
    }

    private fun isUnsafeLiteralAddress(host: String): Boolean {
        val literalHost = host.removePrefix("[").removeSuffix("]")
        if (!looksLikeIpLiteral(literalHost)) return false

        val address = runCatching { InetAddress.getByName(literalHost) }.getOrNull() ?: return true
        return address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress
    }

    private fun looksLikeIpLiteral(host: String): Boolean {
        return host.contains(":") || host.all { it.isDigit() || it == '.' }
    }

    private fun normalizeProxyGroupRefs(rawProxies: Any?, knownOutboundTags: Set<String>): List<String> {
        return (rawProxies as? List<*>)
            ?.mapNotNull { value -> asString(value)?.trim()?.takeIf { it.isNotBlank() } }
            ?.mapNotNull { normalizeProxyGroupRef(it, knownOutboundTags) }
            ?.distinct()
            .orEmpty()
    }

    private fun normalizeProxyGroupRef(ref: String, knownOutboundTags: Set<String>): String? {
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

    private fun parseProxy(proxyMap: Map<*, *>, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): List<Outbound>? {
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

    private fun parseVLess(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): Outbound? {
        if (server == null || port == null) return null
        val uuid = asString(map["uuid"]) ?: return null
        val network = asString(map["network"])?.lowercase()
        val tlsEnabled = asBool(map["tls"]) == true
        val serverName = asString(map["servername"]) ?: asString(map["sni"]) ?: server

        val fingerprint = asString(map["client-fingerprint"]) ?: globalFingerprint
        val insecure = asBool(map["skip-cert-verify"]) == true
        val alpn = asStringList(map["alpn"])
        val flow = asString(map["flow"])
        val packetEncoding = asString(map["packet-encoding"]) ?: "xudp"
        val encryption = extractXhttpExtraEncryption(map)

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
                )
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
            "xhttp", "splithttp" -> {
                val xhttpOpts = map["xhttp-opts"] as? Map<*, *>
                    ?: map["splithttp-opts"] as? Map<*, *>
                val path = asString(xhttpOpts?.get("path")) ?: "/"
                val host = asString(xhttpOpts?.get("host"))?.let { listOf(it) }
                val mode = asString(xhttpOpts?.get("mode"))
                val xPaddingBytes = asString(xhttpOpts?.get("xPaddingBytes"))
                    ?: asString(xhttpOpts?.get("x-padding-bytes"))
                TransportConfig(
                    type = "xhttp",
                    path = path,
                    host = host,
                    mode = mode,
                    xPaddingBytes = xPaddingBytes
                )
            }
            else -> null
        }

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
            encryption = encryption,
            multiplex = multiplex
        )
    }

    private fun extractXhttpExtraEncryption(map: Map<*, *>): String? {
        val xhttpOpts = map["xhttp-opts"] as? Map<*, *>
            ?: map["splithttp-opts"] as? Map<*, *>
            ?: return null
        val extra = xhttpOpts["extra"] as? Map<*, *> ?: return null
        return asString(extra["encryption"])
    }

    private fun parseVMess(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): Outbound? {
        if (server == null || port == null) return null
        val uuid = asString(map["uuid"]) ?: return null

        val alterId = asInt(map["alterId"]) ?: 0
        android.util.Log.i("ClashYamlParser", "VMess node '$name': alterId=$alterId (raw=${map["alterId"]})")
        val cipher = asString(map["cipher"]) ?: "auto"
        val network = asString(map["network"])?.lowercase()
        val tlsEnabled = asBool(map["tls"]) == true
        val serverName = asString(map["servername"]) ?: asString(map["sni"]) ?: server

        val fingerprint = asString(map["client-fingerprint"]) ?: globalFingerprint

        val skipCertVerifyValue = map["skip-cert-verify"]
        val insecure = when {
            skipCertVerifyValue == null -> true
            asBool(skipCertVerifyValue) == true -> true

            server.count { it == '-' } >= 2 && server.split(".").firstOrNull()?.length ?: 0 > 10 -> true
            else -> false
        }
        val alpn = asStringList(map["alpn"])
        val packetEncoding = asString(map["packet-encoding"]) ?: "xudp"

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
                )
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
            "xhttp", "splithttp" -> {
                val xhttpOpts = map["xhttp-opts"] as? Map<*, *>
                    ?: map["splithttp-opts"] as? Map<*, *>
                val path = asString(xhttpOpts?.get("path")) ?: "/"
                val host = asString(xhttpOpts?.get("host"))?.let { listOf(it) }
                val mode = asString(xhttpOpts?.get("mode"))
                val xPaddingBytes = asString(xhttpOpts?.get("xPaddingBytes"))
                    ?: asString(xhttpOpts?.get("x-padding-bytes"))
                TransportConfig(
                    type = "xhttp",
                    path = path,
                    host = host,
                    mode = mode,
                    xPaddingBytes = xPaddingBytes
                )
            }
            else -> null
        }

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
    private fun parseShadowsocksWithPlugin(
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
                    version = stlsVersion,
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
                    network = if (!udpEnabled) "tcp" else null
                )

                return listOf(ssOutbound, shadowTlsOutbound)
            }

            "obfs", "obfs-local", "simple-obfs" -> {
                val obfsMode = asString(pluginOpts?.get("mode"))?.lowercase()
                val obfsHost = asString(pluginOpts?.get("host")) ?: server

                val transport = when (obfsMode) {
                    "http" -> TransportConfig(
                        type = "http",
                        host = listOf(obfsHost)
                    )
                    "tls" -> null
                    else -> null
                }

                return listOf(Outbound(
                    type = "shadowsocks",
                    tag = name,
                    server = server,
                    serverPort = port,
                    method = cipher,
                    password = password,
                    multiplex = multiplex,
                    transport = transport,
                    network = if (!udpEnabled) "tcp" else null
                ))
            }

            "v2ray-plugin" -> {
                val mode = asString(pluginOpts?.get("mode"))?.lowercase() ?: "websocket"
                val tlsEnabled = asBool(pluginOpts?.get("tls")) == true
                val host = asString(pluginOpts?.get("host")) ?: server
                val path = asString(pluginOpts?.get("path")) ?: "/"
                val fingerprint = asString(map["client-fingerprint"]) ?: globalFingerprint

                val transport = when (mode) {
                    "websocket", "ws" -> TransportConfig(
                        type = "ws",
                        path = path,
                        headers = mapOf("Host" to host)
                    )
                    "quic" -> TransportConfig(type = "quic")
                    "grpc" -> TransportConfig(
                        type = "grpc",
                        serviceName = asString(pluginOpts?.get("grpc-service-name")) ?: ""
                    )
                    else -> null
                }

                val tlsConfig = if (tlsEnabled) {
                    buildTlsConfig(
                        map = pluginOpts ?: emptyMap<Any, Any>(),
                        serverName = host,
                        utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
                    )
                } else null

                return listOf(Outbound(
                    type = "shadowsocks",
                    tag = name,
                    server = server,
                    serverPort = port,
                    method = cipher,
                    password = password,
                    multiplex = multiplex,
                    transport = transport,
                    tls = tlsConfig,
                    network = if (!udpEnabled) "tcp" else null
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
                    network = if (!udpEnabled) "tcp" else null
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
                    network = if (!udpEnabled) "tcp" else null
                ))
            }
        }
    }

    private fun parseTrojan(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): Outbound? {
        if (server == null || port == null) return null
        val password = asString(map["password"]) ?: return null
        val network = asString(map["network"])?.lowercase()
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

    private fun parseTrojanTransport(
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
                )
            }
            "grpc" -> {
                val grpcOpts = map["grpc-opts"] as? Map<*, *>
                val serviceName = asString(grpcOpts?.get("grpc-service-name")) ?: ""
                TransportConfig(type = "grpc", serviceName = serviceName)
            }
            "xhttp", "splithttp" -> {
                val xhttpOpts = map["xhttp-opts"] as? Map<*, *>
                    ?: map["splithttp-opts"] as? Map<*, *>
                val path = asString(xhttpOpts?.get("path")) ?: "/"
                val host = asString(xhttpOpts?.get("host"))?.let { listOf(it) }
                val mode = asString(xhttpOpts?.get("mode"))
                val xPaddingBytes = asString(xhttpOpts?.get("xPaddingBytes"))
                    ?: asString(xhttpOpts?.get("x-padding-bytes"))
                TransportConfig(
                    type = "xhttp",
                    path = path,
                    host = host,
                    mode = mode,
                    xPaddingBytes = xPaddingBytes
                )
            }
            else -> null
        }
    }

    private fun parseHysteria2(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): Outbound? {
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
            network = network,
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

    private fun parseTuic(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): Outbound? {
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

    private fun parseSSH(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound? {
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

    private fun parseWireGuard(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound? {
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

    private fun parseAnyTLS(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): Outbound? {
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
    private fun parseNaive(
        map: Map<*, *>,
        name: String,
        server: String?,
        port: Int?,
        globalFingerprint: String? = null,
        globalTlsMinVersion: String? = null
    ): Outbound? {
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

    private fun parseHysteria(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): Outbound? {
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

    private fun parseHttp(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): Outbound? {
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

    private fun parseSocks(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound? {
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

    private fun parseShadowTLS(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String? = null): Outbound? {
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
    private fun parseSmux(map: Map<*, *>): MultiplexConfig? {
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

    private fun buildTlsConfig(
        map: Map<*, *>,
        enabled: Boolean = true,
        serverName: String? = null,
        insecure: Boolean? = null,
        alpn: List<String>? = null,
        minVersion: String? = null,
        utls: UtlsConfig? = null,
        reality: com.kunk.singbox.model.RealityConfig? = null
    ): TlsConfig {
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

    private fun firstNonBlankString(map: Map<*, *>, vararg keys: String): String? {
        return keys.firstNotNullOfOrNull { key ->
            asString(map[key])?.takeIf { it.isNotBlank() }
        }
    }

    private fun asNestedMap(v: Any?): Map<*, *>? = v as? Map<*, *>

    private fun getUserAgent(fingerprint: String?): String {
        return if (fingerprint?.contains("chrome", ignoreCase = true) == true) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
        } else {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
        }
    }

    private fun asString(v: Any?): String? = when (v) {
        is String -> v
        is Number -> v.toString()
        is Boolean -> v.toString()
        is List<*> -> v.firstOrNull()?.toString()
        else -> null
    }

    private fun asInt(v: Any?): Int? = when (v) {
        is Int -> v
        is Long -> v.toInt()
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    private fun asBool(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is String -> when (v.lowercase()) {
            "true", "1", "yes", "y" -> true
            "false", "0", "no", "n" -> false
            else -> null
        }
        else -> null
    }

    private fun asStringList(v: Any?): List<String>? {
        return when (v) {
            is List<*> -> v.mapNotNull { asString(it) }.takeIf { it.isNotEmpty() }
            is String -> v.split(",").map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    private fun buildWsOrHttpUpgradeTransport(
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

    private fun Map<String, String>.withoutHostHeader(): Map<String, String> {
        return filterKeys { !it.equals("Host", ignoreCase = true) }
    }
}
