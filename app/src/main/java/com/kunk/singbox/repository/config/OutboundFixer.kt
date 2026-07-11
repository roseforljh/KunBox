package com.kunk.singbox.repository.config

import android.util.Log
import com.kunk.singbox.core.LibboxCompat
import com.kunk.singbox.model.MultiplexConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import com.kunk.singbox.model.UInt32JsonAdapter
import com.kunk.singbox.model.V2RAY_TRANSPORT_PROTOCOLS
import com.kunk.singbox.model.V2RAY_TRANSPORT_TYPES
import com.kunk.singbox.model.V2RAY_XHTTP_TRANSPORT_TYPES
import com.kunk.singbox.model.allHeaderValues
import com.kunk.singbox.model.asHttpHeaderMap
import com.kunk.singbox.repository.SettingsRepository

@Suppress("LargeClass")
object OutboundFixer {
    private const val TAG = "OutboundFixer"
    @Volatile private var cachedTcpKeepAliveEnabled: Boolean? = null
    @Volatile private var cachedTcpKeepAliveInterval: String? = null
    @Volatile private var cachedConnectTimeout: String? = null

    private fun getTcpKeepAliveConfig(context: android.content.Context): Triple<Boolean, String?, String?> {
        cachedTcpKeepAliveEnabled?.let { enabled ->
            return Triple(enabled, cachedTcpKeepAliveInterval, cachedConnectTimeout)
        }

        synchronized(this) {
            cachedTcpKeepAliveEnabled?.let { enabled ->
                return Triple(enabled, cachedTcpKeepAliveInterval, cachedConnectTimeout)
            }

            val settings = SettingsRepository.getInstance(context).settings.value
            val enabled = settings.tcpKeepAliveEnabled
            val interval = if (enabled) "${settings.tcpKeepAliveInterval}s" else null
            val timeout = if (enabled) "${settings.connectTimeout}s" else null

            cachedTcpKeepAliveEnabled = enabled
            cachedTcpKeepAliveInterval = interval
            cachedConnectTimeout = timeout

            return Triple(enabled, interval, timeout)
        }
    }

    fun clearTcpKeepAliveCache() {
        synchronized(this) {
            cachedTcpKeepAliveEnabled = null
            cachedTcpKeepAliveInterval = null
            cachedConnectTimeout = null
        }
    }

    private val REGEX_INTERVAL_DIGITS = Regex("^\\d+$")
    private val REGEX_INTERVAL_DECIMAL = Regex("^\\d+\\.\\d+$")
    private val REGEX_INTERVAL_UNIT = Regex("^\\d+(\\.\\d+)?[smhSMH]$")
    private val REGEX_IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val REGEX_IPV6 = Regex("^[0-9a-fA-F:]+$")

    fun fix(outbound: Outbound): Outbound {
        var result = normalizeTransport(outbound)

        // Fix interval
        val interval = result.interval
        if (interval != null) {
            val fixedInterval = when {
                REGEX_INTERVAL_DIGITS.matches(interval) -> "${interval}s"
                REGEX_INTERVAL_DECIMAL.matches(interval) -> "${interval}s"
                REGEX_INTERVAL_UNIT.matches(interval) -> interval.lowercase()
                else -> interval
            }
            if (fixedInterval != interval) {
                result = result.copy(interval = fixedInterval)
            }
        }

        // Fix flow
        val cleanedFlow = result.flow?.takeIf { it.isNotBlank() }
        val normalizedFlow = cleanedFlow?.let { flowValue ->
            if (flowValue.contains("xtls-rprx-vision")) {
                "xtls-rprx-vision"
            } else {
                flowValue
            }
        }
        if (normalizedFlow != result.flow) {
            result = result.copy(flow = normalizedFlow)
        }

        // Fix VLESS + Vision + Reality + Mux compatibility
        if (result.type == "vless") {
            val tunedMux = tuneMuxForVisionReality(result)
            if (tunedMux != result.multiplex) {
                result = result.copy(multiplex = tunedMux)
            }
        }

        // 统一旧别名并补齐 urltest 运行所需的最小字段
        if (result.type == "urltest" || result.type == "url-test") {
            result = result.copy(
                type = "urltest",
                default = null,
                interruptExistConnections = result.interruptExistConnections ?: false
            )
        }

        // Fix TLS SNI for WebSocket
        val tls = result.tls
        val transport = result.transport
        if (transport?.type == "ws" && tls?.enabled == true) {
            val wsHost = transport.headers?.get("Host")
                ?: transport.headers?.get("host")
                ?: transport.host?.firstOrNull()
            val sni = tls.serverName?.trim().orEmpty()
            if (!wsHost.isNullOrBlank() && !isIpLiteral(wsHost)) {
                if (sni.isBlank()) {
                    result = result.copy(tls = tls.copy(serverName = wsHost))
                }
            }
        }

        // Fix ALPN for WebSocket + TLS (skip when ECH is enabled to avoid outer ClientHello conflict)
        val tlsAfterSni = result.tls
        val needsWsAlpn = result.transport?.type == "ws" &&
            tlsAfterSni?.enabled == true &&
            tlsAfterSni.ech?.enabled != true
        if (needsWsAlpn && (tlsAfterSni.alpn == null || tlsAfterSni.alpn.isEmpty())) {
            result = result.copy(tls = tlsAfterSni.copy(alpn = listOf("http/1.1")))
        }

        if (transport?.type == "httpupgrade") {
            val headerHost = transport.headers?.get("Host")
                ?: transport.headers?.get("host")
            val httpUpgradeHost = transport.host
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: headerHost?.takeIf { it.isNotBlank() }
            val normalizedPath = transport.path?.trim()?.ifEmpty { "/" } ?: "/"
            val cleanedHeaders = transport.headers
                ?.allHeaderValues()
                ?.filterKeys { !it.equals("Host", ignoreCase = true) }
                ?.takeIf { it.isNotEmpty() }
                ?.asHttpHeaderMap()
            val normalizedHost = httpUpgradeHost?.let { listOf(it) }

            if (
                normalizedPath != transport.path ||
                normalizedHost != transport.host ||
                cleanedHeaders != transport.headers
            ) {
                result = result.copy(
                    transport = transport.copy(
                        path = normalizedPath,
                        host = normalizedHost,
                        headers = cleanedHeaders
                    )
                )
            }

            val tlsForHttpUpgrade = result.tls?.takeIf { it.enabled == true }
            val sni = tlsForHttpUpgrade?.serverName?.trim().orEmpty()
            val shouldFixSni = tlsForHttpUpgrade != null &&
                !httpUpgradeHost.isNullOrBlank() &&
                !isIpLiteral(httpUpgradeHost) &&
                sni.isBlank()

            if (shouldFixSni) {
                result = result.copy(tls = tlsForHttpUpgrade.copy(serverName = httpUpgradeHost))
            }
        }

        if (transport?.type == "xhttp" || transport?.type == "splithttp") {
            val rawPath = transport.path ?: "/"
            val normalizedPath = normalizeXhttpPath(rawPath)
            val xhttpHost = transport.host?.firstOrNull()
            val tlsForXhttp = result.tls?.takeIf { it.enabled == true }
            val xhttpSni = tlsForXhttp?.serverName?.trim().orEmpty()
            val server = result.server?.trim().orEmpty()
            val shouldFixXhttpSni = tlsForXhttp != null &&
                !xhttpHost.isNullOrBlank() &&
                !isIpLiteral(xhttpHost) &&
                (xhttpSni.isBlank() || isIpLiteral(xhttpSni) || (server.isNotBlank() && xhttpSni.equals(server, true)))
            val shouldFixXhttpAlpn = tlsForXhttp != null && tlsForXhttp.alpn.isNullOrEmpty()

            if (normalizedPath != rawPath || shouldFixXhttpSni || shouldFixXhttpAlpn) {
                var updated = result.copy(
                    transport = transport.copy(type = "xhttp", path = normalizedPath)
                )
                var tlsUpdated = tlsForXhttp
                if (shouldFixXhttpSni && tlsUpdated != null) tlsUpdated = tlsUpdated.copy(serverName = xhttpHost)
                if (shouldFixXhttpAlpn && tlsUpdated != null) tlsUpdated = tlsUpdated.copy(alpn = listOf("h2"))
                if (tlsUpdated != result.tls) updated = updated.copy(tls = tlsUpdated)
                result = updated
            }
        }

        if (result.type == "tuic" || result.type == "hysteria2") {
            val currentTls = result.tls ?: TlsConfig(enabled = true)
            result = if (result.disableSni == true) {
                result.copy(
                    disableSni = null,
                    tls = currentTls.copy(
                        enabled = true,
                        disableSni = true
                    )
                )
            } else {
                result.copy(
                    tls = currentTls.copy(enabled = true)
                )
            }
        }

        // Fix User-Agent and path for WS
        if (transport != null && transport.type == "ws") {
            val headers = transport.headers?.allHeaderValues()?.toMutableMap() ?: mutableMapOf()
            var needUpdate = false

            if (!headers.containsKey("Host")) {
                val host = transport.host?.firstOrNull()
                    ?: result.tls?.serverName
                    ?: result.server
                if (!host.isNullOrBlank()) {
                    headers["Host"] = listOf(host)
                    needUpdate = true
                }
            }

            if (!headers.containsKey("User-Agent")) {
                val fingerprint = result.tls?.utls?.fingerprint
                val userAgent = if (fingerprint?.contains("chrome") == true) {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
                } else {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
                }
                headers["User-Agent"] = listOf(userAgent)
                needUpdate = true
            }

            val rawPath = transport.path ?: "/"
            val (cleanPath, embeddedEarlyData) = splitWebSocketEarlyData(rawPath)
            val migratedEarlyData = if (transport.maxEarlyData == null) {
                embeddedEarlyData?.toLongOrNull()?.let { value ->
                    runCatching { UInt32JsonAdapter.requireValue(value) }.getOrNull()
                }
            } else {
                null
            }

            val pathChanged = cleanPath != rawPath
            val earlyDataChanged = migratedEarlyData != null

            if (needUpdate || pathChanged || earlyDataChanged) {
                result = result.copy(transport = transport.copy(
                    headers = headers.asHttpHeaderMap(),
                    path = cleanPath,
                    maxEarlyData = transport.maxEarlyData ?: migratedEarlyData,
                    earlyDataHeaderName = transport.earlyDataHeaderName
                        ?: migratedEarlyData?.let { "Sec-WebSocket-Protocol" }
                ))
            }
        }

        if (result.type == "vless" && result.security != null) {
            result = result.copy(security = null)
        }

        if (result.type == "hysteria" || result.type == "hysteria2") {
            val cleanedServerPorts = result.serverPorts
                ?.filter { it.isNotBlank() }
                ?.map { convertPortRangeFormat(it) }
                ?.takeIf { it.isNotEmpty() }
            val cleanedHopInterval = result.hopInterval?.takeIf { it.isNotBlank() }
            result = result.copy(
                serverPorts = cleanedServerPorts,
                hopInterval = cleanedHopInterval,
                obfs = result.obfs?.copy(stringValue = result.type == "hysteria")
            )
        }
        if (result.type == "naive") {
            result = fixNaive(result)
        }

        val currentTls = result.tls
        if (currentTls != null && currentTls.alpn?.isEmpty() == true) {
            result = result.copy(tls = currentTls.copy(alpn = null))
        }

        return result
    }

    private fun normalizeTransport(outbound: Outbound): Outbound {
        val transport = outbound.transport ?: return outbound
        if (outbound.type !in V2RAY_TRANSPORT_PROTOCOLS) {
            return outbound.copy(transport = null)
        }

        val type = transport.type?.trim()?.lowercase().orEmpty()
        val normalized = when {
            type.isEmpty() || type == "tcp" -> null
            type == "h2" -> normalizeTransportFields(transport, "http")
            type == "splithttp" -> normalizeTransportFields(transport, "xhttp")
            type in V2RAY_TRANSPORT_TYPES -> normalizeTransportFields(transport, type)
            else -> transport.copy(type = type)
        }
        return outbound.copy(transport = normalized)
    }

    private fun normalizeTransportFields(transport: TransportConfig, type: String): TransportConfig {
        return when (type) {
            "http" -> transport.copy(
                type = type,
                serviceName = null,
                permitWithoutStream = null,
                earlyDataHeaderName = null,
                maxEarlyData = null
            )
            "ws" -> transport.copy(
                type = type,
                method = null,
                serviceName = null,
                host = null,
                idleTimeout = null,
                pingTimeout = null,
                permitWithoutStream = null
            )
            "quic" -> TransportConfig(type = type)
            "grpc" -> transport.copy(
                type = type,
                path = null,
                method = null,
                headers = null,
                host = null,
                earlyDataHeaderName = null,
                maxEarlyData = null
            )
            "httpupgrade" -> transport.copy(
                type = type,
                method = null,
                serviceName = null,
                host = transport.host
                    ?.asSequence()
                    ?.map(String::trim)
                    ?.firstOrNull(String::isNotEmpty)
                    ?.let(::listOf),
                idleTimeout = null,
                pingTimeout = null,
                permitWithoutStream = null,
                earlyDataHeaderName = null,
                maxEarlyData = null
            )
            "xhttp" -> transport.copy(
                type = type,
                method = null,
                serviceName = null,
                headers = null,
                idleTimeout = null,
                pingTimeout = null,
                permitWithoutStream = null,
                earlyDataHeaderName = null,
                maxEarlyData = null
            )
            else -> transport
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun applyCommonDialFields(runtime: Outbound, fixed: Outbound): Outbound {
        if (runtime.type == "selector" || runtime.type == "urltest") return runtime
        return runtime.copy(
            detour = runtime.detour ?: fixed.detour,
            bindInterface = runtime.bindInterface ?: fixed.bindInterface,
            inet4BindAddress = runtime.inet4BindAddress ?: fixed.inet4BindAddress,
            inet6BindAddress = runtime.inet6BindAddress ?: fixed.inet6BindAddress,
            bindAddressNoPort = runtime.bindAddressNoPort ?: fixed.bindAddressNoPort,
            protectPath = runtime.protectPath ?: fixed.protectPath,
            routingMark = runtime.routingMark ?: fixed.routingMark,
            reuseAddr = runtime.reuseAddr ?: fixed.reuseAddr,
            netns = runtime.netns ?: fixed.netns,
            connectTimeout = fixed.connectTimeout ?: runtime.connectTimeout,
            tcpFastOpen = runtime.tcpFastOpen ?: fixed.tcpFastOpen,
            tcpMultiPath = runtime.tcpMultiPath ?: fixed.tcpMultiPath,
            disableTcpKeepAlive = runtime.disableTcpKeepAlive ?: fixed.disableTcpKeepAlive,
            tcpKeepAlive = fixed.tcpKeepAlive ?: runtime.tcpKeepAlive,
            tcpKeepAliveInterval = fixed.tcpKeepAliveInterval ?: runtime.tcpKeepAliveInterval,
            udpFragment = runtime.udpFragment ?: fixed.udpFragment,
            domainResolver = runtime.domainResolver ?: fixed.domainResolver,
            networkStrategy = runtime.networkStrategy ?: fixed.networkStrategy,
            networkType = runtime.networkType ?: fixed.networkType,
            fallbackNetworkType = runtime.fallbackNetworkType ?: fixed.fallbackNetworkType,
            fallbackDelay = runtime.fallbackDelay ?: fixed.fallbackDelay,
            domainStrategy = runtime.domainStrategy ?: fixed.domainStrategy
        )
    }

    @Suppress("LongMethod")
    fun buildForRuntime(context: android.content.Context, outbound: Outbound): Outbound? {
        if (hasInvalidV2RayTransport(outbound)) return null
        val fixed = applyNaiveRuntimeCompatibility(fix(outbound))

        val (tcpKeepAliveEnabled, tcpKeepAliveInterval, connectTimeout) = getTcpKeepAliveConfig(context)

        return buildForRuntimeWithDialConfig(
            fixed = fixed,
            tcpKeepAliveEnabled = tcpKeepAliveEnabled,
            tcpKeepAliveInterval = tcpKeepAliveInterval,
            connectTimeout = connectTimeout
        )
    }

    internal fun buildForRuntimeWithDialConfigForTest(
        outbound: Outbound,
        tcpKeepAliveEnabled: Boolean = false,
        tcpKeepAliveInterval: String? = null,
        connectTimeout: String? = null
    ): Outbound? {
        if (hasInvalidV2RayTransport(outbound)) return null
        val fixed = applyNaiveRuntimeCompatibility(fix(outbound))
        return buildForRuntimeWithDialConfig(
            fixed = fixed,
            tcpKeepAliveEnabled = tcpKeepAliveEnabled,
            tcpKeepAliveInterval = tcpKeepAliveInterval,
            connectTimeout = connectTimeout
        )
    }

    @Suppress("LongMethod", "ReturnCount")
    private fun buildForRuntimeWithDialConfig(
        fixed: Outbound,
        tcpKeepAliveEnabled: Boolean,
        tcpKeepAliveInterval: String?,
        connectTimeout: String?
    ): Outbound? {
        if ((fixed.type == "selector" || fixed.type == "urltest") && fixed.outbounds.isNullOrEmpty()) {
            Log.w(TAG, "Skipping empty ${fixed.type} outbound: ${fixed.tag}")
            return null
        }
        return applyCommonDialFields(when (fixed.type) {
            "selector" -> Outbound(
                type = "selector",
                tag = fixed.tag,
                outbounds = fixed.outbounds,
                default = fixed.default,
                interruptExistConnections = fixed.interruptExistConnections
            )

            "urltest", "url-test" -> Outbound(
                type = "urltest",
                tag = fixed.tag,
                outbounds = fixed.outbounds,
                default = null,
                url = fixed.url,
                interval = fixed.interval,
                tolerance = fixed.tolerance,
                idleTimeout = fixed.idleTimeout,
                interruptExistConnections = fixed.interruptExistConnections
            )

            "direct" -> Outbound(type = fixed.type, tag = fixed.tag)

            // "block" and "dns" outbound types are removed in sing-box 1.13.0+
            // Use route rule actions "reject" and "hijack-dns" instead
            "block", "dns" -> {
                Log.w(TAG, "Skipping removed outbound type '${fixed.type}': ${fixed.tag}")
                return null
            }

            "vmess" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                uuid = fixed.uuid,
                alterId = fixed.alterId,
                security = fixed.security,
                globalPadding = fixed.globalPadding,
                authenticatedLength = fixed.authenticatedLength,
                packetEncoding = fixed.packetEncoding,
                tls = fixed.tls,
                transport = fixed.transport,
                network = fixed.network,
                multiplex = fixed.multiplex,
                domainResolver = resolveDomainResolver(fixed),

                tcpKeepAlive = tcpKeepAliveInterval,
                tcpKeepAliveInterval = tcpKeepAliveInterval,
                connectTimeout = connectTimeout
            )

            "vless" -> {
                val encryption = fixed.encryption?.trim()
                if (!encryption.isNullOrEmpty() && !encryption.equals("none", ignoreCase = true)) {
                    Log.w(TAG, "Skipping unsupported VLESS encryption for '${fixed.tag}': $encryption")
                    return null
                }
                Outbound(
                    type = fixed.type,
                    tag = fixed.tag,
                    server = fixed.server,
                    serverPort = fixed.serverPort,
                    uuid = fixed.uuid,
                    flow = fixed.flow,
                    packetEncoding = fixed.packetEncoding,
                    encryption = null,
                    tls = fixed.tls,
                    transport = fixed.transport,
                    network = fixed.network,
                    multiplex = fixed.multiplex,
                    domainResolver = resolveDomainResolver(fixed),

                    tcpKeepAlive = tcpKeepAliveInterval,
                    tcpKeepAliveInterval = tcpKeepAliveInterval,
                    connectTimeout = connectTimeout
                )
            }

            "trojan" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                password = fixed.password,
                tls = fixed.tls,
                transport = fixed.transport,
                network = fixed.network,
                multiplex = fixed.multiplex,
                domainResolver = resolveDomainResolver(fixed),

                tcpKeepAlive = tcpKeepAliveInterval,
                tcpKeepAliveInterval = tcpKeepAliveInterval,
                connectTimeout = connectTimeout
            )

            "shadowsocks" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                method = fixed.method,
                password = fixed.password,
                plugin = fixed.plugin,
                pluginOpts = fixed.pluginOpts,
                udpOverTcp = fixed.udpOverTcp,
                multiplex = fixed.multiplex,
                detour = fixed.detour,
                network = fixed.network,
                domainResolver = resolveDomainResolver(fixed),

                tcpKeepAlive = tcpKeepAliveInterval,
                tcpKeepAliveInterval = tcpKeepAliveInterval,
                connectTimeout = connectTimeout
            )

            "hysteria", "hysteria2" -> buildRuntimeHysteriaOutbound(fixed)

            "tuic" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                uuid = fixed.uuid,
                password = fixed.password,
                congestionControl = fixed.congestionControl,
                udpRelayMode = fixed.udpRelayMode,
                udpOverStream = fixed.udpOverStream,
                zeroRttHandshake = fixed.zeroRttHandshake,
                heartbeat = fixed.heartbeat,
                tls = fixed.tls,
                network = fixed.network,
                domainResolver = resolveDomainResolver(fixed),

                tcpKeepAlive = tcpKeepAliveInterval,
                tcpKeepAliveInterval = tcpKeepAliveInterval,
                connectTimeout = connectTimeout
            )

            "naive" -> buildRuntimeNaiveOutbound(
                fixed = fixed,
                tcpKeepAliveEnabled = tcpKeepAliveEnabled,
                tcpKeepAliveInterval = tcpKeepAliveInterval,
                connectTimeout = connectTimeout
            )

            "anytls" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                password = fixed.password,
                idleSessionCheckInterval = fixed.idleSessionCheckInterval,
                idleSessionTimeout = fixed.idleSessionTimeout,
                minIdleSession = fixed.minIdleSession,
                tls = fixed.tls,
                domainResolver = resolveDomainResolver(fixed),

                tcpKeepAlive = tcpKeepAliveInterval,
                tcpKeepAliveInterval = tcpKeepAliveInterval,
                connectTimeout = connectTimeout
            )

            "wireguard" -> {
                Log.w(TAG, "Skipping removed WireGuard outbound '${fixed.tag}'; sing-box 1.13 requires an endpoint")
                return null
            }

            "ssh" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                user = fixed.user,
                password = fixed.password,
                privateKey = fixed.privateKey,
                privateKeyPath = fixed.privateKeyPath,
                privateKeyPassphrase = fixed.privateKeyPassphrase,
                hostKey = fixed.hostKey,
                hostKeyAlgorithms = fixed.hostKeyAlgorithms,
                clientVersion = fixed.clientVersion,
                domainResolver = resolveDomainResolver(fixed),

                tcpKeepAlive = tcpKeepAliveInterval,
                tcpKeepAliveInterval = tcpKeepAliveInterval,
                connectTimeout = connectTimeout
            )

            "socks" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                version = fixed.version,
                username = fixed.username,
                password = fixed.password,
                network = fixed.network,
                udpOverTcp = fixed.udpOverTcp,
                domainResolver = resolveDomainResolver(fixed),
                tcpKeepAlive = tcpKeepAliveInterval,
                tcpKeepAliveInterval = tcpKeepAliveInterval,
                connectTimeout = connectTimeout
            )

            "http" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                username = fixed.username,
                password = fixed.password,
                tls = fixed.tls,
                path = fixed.path,
                headers = fixed.headers,
                domainResolver = resolveDomainResolver(fixed),
                tcpKeepAlive = tcpKeepAliveInterval,
                tcpKeepAliveInterval = tcpKeepAliveInterval,
                connectTimeout = connectTimeout
            )

            "shadowtls" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                version = fixed.version,
                password = fixed.password,
                tls = fixed.tls,
                domainResolver = resolveDomainResolver(fixed),

                tcpKeepAlive = tcpKeepAliveInterval,
                tcpKeepAliveInterval = tcpKeepAliveInterval,
                connectTimeout = connectTimeout
            )

            else -> fixed
        }, fixed)
    }

    internal fun buildRuntimeHysteriaOutbound(fixed: Outbound): Outbound {
        val serverPorts = fixed.serverPorts?.takeIf { it.isNotEmpty() }
        return Outbound(
            type = fixed.type,
            tag = fixed.tag,
            server = fixed.server,
            serverPort = fixed.serverPort.takeIf { serverPorts == null },
            password = fixed.password,
            auth = fixed.auth,
            authStr = fixed.authStr,
            up = fixed.up,
            upMbps = fixed.upMbps,
            down = fixed.down,
            downMbps = fixed.downMbps,
            obfs = fixed.obfs,
            recvWindowConn = fixed.recvWindowConn,
            recvWindow = fixed.recvWindow,
            disableMtuDiscovery = fixed.disableMtuDiscovery.takeIf { fixed.type == "hysteria" },
            hopInterval = fixed.hopInterval,
            serverPorts = serverPorts,
            brutalDebug = fixed.brutalDebug.takeIf { fixed.type == "hysteria2" },
            tls = fixed.tls,
            network = fixed.network,
            domainResolver = resolveDomainResolver(fixed)
        )
    }

    internal fun buildRuntimeNaiveOutbound(
        fixed: Outbound,
        tcpKeepAliveEnabled: Boolean,
        tcpKeepAliveInterval: String?,
        connectTimeout: String?
    ): Outbound {
        val useQuic = fixed.quic == true || fixed.network?.firstOrNull().equals("quic", ignoreCase = true)
        return Outbound(
            type = fixed.type,
            tag = fixed.tag,
            server = fixed.server,
            serverPort = fixed.serverPort,
            username = fixed.username,
            password = fixed.password,
            insecureConcurrency = fixed.insecureConcurrency,
            extraHeaders = fixed.extraHeaders,
            streamReceiveWindow = fixed.streamReceiveWindow,
            quic = useQuic,
            quicCongestionControl = if (useQuic) {
                fixed.quicCongestionControl ?: fixed.congestionControl
            } else {
                null
            },
            quicSessionReceiveWindow = fixed.quicSessionReceiveWindow,
            tls = fixed.tls,
            udpOverTcp = fixed.udpOverTcp,
            domainResolver = resolveNaiveDomainResolver(fixed),

            tcpKeepAlive = if (tcpKeepAliveEnabled) tcpKeepAliveInterval else null,
            tcpKeepAliveInterval = if (tcpKeepAliveEnabled) tcpKeepAliveInterval else null,
            connectTimeout = connectTimeout
        )
    }

    @Suppress("CyclomaticComplexMethod")
    private fun fixNaive(outbound: Outbound): Outbound {
        val currentNetwork = outbound.network?.firstOrNull()?.trim()
        val normalizedNetwork = when (currentNetwork?.lowercase()) {
            "h2", "quic" -> currentNetwork
            else -> "h2"
        }
        val useQuic = normalizedNetwork == "quic" || outbound.quic == true

        val normalizedHeaderValues = buildMap {
            outbound.extraHeaders
                ?.allHeaderValues()
                ?.forEach { (key, values) ->
                    val normalizedKey = key.trim()
                    val normalizedValues = values.map(String::trim).filter(String::isNotEmpty)
                    if (normalizedKey.isNotEmpty() && normalizedValues.isNotEmpty()) {
                        put(normalizedKey, normalizedValues)
                    }
                }

            val host = outbound.headers?.get("Host")?.trim()
            if (!host.isNullOrEmpty() && !containsKey("Host")) {
                put("Host", listOf(host))
            }
        }.ifEmpty { null }
        val normalizedHeaders = normalizedHeaderValues?.asHttpHeaderMap()

        val host = normalizedHeaders?.get("Host")?.trim()
        val tls = outbound.tls ?: TlsConfig(enabled = true)
        val tlsEnabled = tls.enabled != false
        val shouldSetSni = tlsEnabled &&
            !host.isNullOrBlank() &&
            !isIpLiteral(host) &&
            tls.serverName.isNullOrBlank()
        val serverName = if (shouldSetSni) host else tls.serverName
        val tlsUpdated = tls.copy(
            enabled = true,
            serverName = serverName
        )

        return outbound.copy(
            network = listOf(if (useQuic) "quic" else "h2"),
            path = null,
            headers = null,
            extraHeaders = normalizedHeaders,
            quic = useQuic,
            quicCongestionControl = outbound.quicCongestionControl ?: outbound.congestionControl,
            tls = tlsUpdated,
            domainResolver = resolveNaiveDomainResolver(outbound)
        )
    }

    private fun resolveDomainResolver(outbound: Outbound): com.kunk.singbox.model.DomainResolveConfig? {
        val existing = outbound.domainResolver
        if (existing?.server.isNullOrBlank().not()) return existing

        val serverHost = outbound.server?.trim().orEmpty()
        if (serverHost.isBlank() || isIpLiteral(serverHost)) {
            return existing
        }

        return com.kunk.singbox.model.DomainResolveConfig(server = "dns-bootstrap")
    }

    // Keep backward-compatible alias for naive-specific callers
    private fun resolveNaiveDomainResolver(outbound: Outbound) = resolveDomainResolver(outbound)

    private fun applyNaiveRuntimeCompatibility(outbound: Outbound): Outbound {
        if (outbound.type != "naive") return outbound

        val hasQuic = outbound.quic == true || outbound.network?.firstOrNull()?.lowercase() == "quic"
        val quicSupported = LibboxCompat.isNaiveQuicSupported()
        if (!hasQuic || quicSupported) return outbound

        return outbound.copy(network = listOf("h2"), quic = false)
    }

    private fun hasInvalidV2RayTransport(outbound: Outbound): Boolean {
        val transport = outbound.transport ?: return false
        if (outbound.type !in V2RAY_TRANSPORT_PROTOCOLS) return false

        val transportType = transport.type?.trim()?.lowercase().orEmpty()
        val normalizedType = if (transportType == "h2") "http" else transportType
        return when {
            transportType.isEmpty() || transportType == "tcp" -> false
            normalizedType !in V2RAY_TRANSPORT_TYPES -> rejectTransport(
                "Skipping unknown V2Ray transport '$transportType' for '${outbound.tag}'"
            )
            normalizedType in V2RAY_XHTTP_TRANSPORT_TYPES && outbound.encryption?.let {
                it.isNotBlank() && !it.equals("none", ignoreCase = true)
            } == true -> rejectTransport(
                "Skipping XHTTP with unsupported private VLESS encryption for '${outbound.tag}'"
            )
            normalizedType == "ws" && hasInvalidWebSocketEarlyData(transport) -> rejectTransport(
                "Skipping WebSocket transport with invalid max_early_data for '${outbound.tag}'"
            )
            normalizedType == "quic" && outbound.tls?.enabled != true -> rejectTransport(
                "Skipping QUIC transport without TLS for '${outbound.tag}'"
            )
            else -> false
        }
    }

    private fun hasInvalidWebSocketEarlyData(transport: TransportConfig): Boolean {
        val invalidMaxEarlyData = transport.maxEarlyData?.let { value ->
            runCatching { UInt32JsonAdapter.requireValue(value) }.isFailure
        } == true
        val embeddedEarlyData = transport.path?.let(::splitWebSocketEarlyData)?.second
        val parsedEmbeddedEarlyData = embeddedEarlyData?.toLongOrNull()
        val invalidEmbeddedEarlyData = if (embeddedEarlyData == null) {
            false
        } else {
            parsedEmbeddedEarlyData == null || runCatching {
                UInt32JsonAdapter.requireValue(parsedEmbeddedEarlyData)
            }.isFailure
        }
        return invalidMaxEarlyData || invalidEmbeddedEarlyData
    }

    private fun rejectTransport(message: String): Boolean {
        Log.w(TAG, message)
        return true
    }

    private fun splitWebSocketEarlyData(rawPath: String): Pair<String, String?> {
        val queryIndex = rawPath.indexOf('?')
        if (queryIndex == -1) return rawPath to null

        val path = rawPath.substring(0, queryIndex).ifEmpty { "/" }
        var earlyData: String? = null
        val remainingQuery = rawPath.substring(queryIndex + 1)
            .split('&')
            .filter { parameter ->
                val isEarlyData = parameter.substringBefore('=').equals("ed", ignoreCase = true)
                if (isEarlyData && earlyData == null) {
                    earlyData = parameter.substringAfter('=', missingDelimiterValue = "")
                }
                !isEarlyData
            }
            .joinToString("&")
        val cleanPath = if (remainingQuery.isEmpty()) path else "$path?$remainingQuery"
        return cleanPath to earlyData
    }

    private fun normalizeXhttpPath(path: String): String {
        val trimmed = path.trim().ifEmpty { "/" }
        val withLeadingSlash = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        if (!withLeadingSlash.contains("://")) return withLeadingSlash
        return withLeadingSlash.substringAfter("://")
            .substringAfter("/", missingDelimiterValue = "/")
            .let { if (it.startsWith("/")) it else "/$it" }
    }

    private fun tuneMuxForVisionReality(outbound: Outbound): MultiplexConfig? {
        val mux = outbound.multiplex ?: return null
        if (mux.enabled != true) return mux

        val hasVisionFlow = outbound.flow?.contains("xtls-rprx-vision", ignoreCase = true) == true
        val tls = outbound.tls
        val hasReality = tls?.enabled == true && tls.reality?.enabled == true
        if (!hasVisionFlow || !hasReality) return mux

        val normalizedProtocol = when (mux.protocol?.lowercase()) {
            "h2mux", "smux", "yamux" -> "h2mux"
            null, "" -> "h2mux"
            else -> "h2mux"
        }
        val maxConnections = mux.maxConnections?.coerceIn(1, 2) ?: 1
        val minStreams = mux.minStreams?.coerceAtLeast(1)
        val maxStreams = mux.maxStreams?.coerceIn(1, 8) ?: 4

        return mux.copy(
            protocol = normalizedProtocol,
            maxConnections = maxConnections,
            minStreams = minStreams,
            maxStreams = maxStreams,
            padding = false
        )
    }

    private fun isIpLiteral(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return false
        if (REGEX_IPV4.matches(v)) {
            return v.split(".").all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
        }
        return v.contains(":") && REGEX_IPV6.matches(v)
    }

    private fun convertPortRangeFormat(portSpec: String): String {
        return portSpec.trim()
    }
}
