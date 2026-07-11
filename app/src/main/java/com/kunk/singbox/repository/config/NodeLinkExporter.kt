package com.kunk.singbox.repository.config

import android.util.Base64
import com.google.gson.Gson
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.UInt32JsonAdapter
import com.kunk.singbox.model.V2RAY_TRANSPORT_PROTOCOLS
import com.kunk.singbox.model.V2RAY_TRANSPORT_TYPES
import com.kunk.singbox.model.V2RAY_XHTTP_TRANSPORT_TYPES
import com.kunk.singbox.model.VMessLinkConfig
import com.kunk.singbox.model.allHeaderValues

object NodeLinkExporter {

    fun export(outbound: Outbound, gson: Gson): String? {
        if (hasInvalidV2RayTransport(outbound)) return null
        return when (outbound.type) {
            "vless" -> generateVLessLink(outbound)
            "vmess" -> generateVMessLink(outbound, gson)
            "shadowsocks" -> generateShadowsocksLink(outbound)
            "trojan" -> generateTrojanLink(outbound)
            "hysteria2" -> generateHysteria2Link(outbound)
            "hysteria" -> generateHysteriaLink(outbound)
            "anytls" -> generateAnyTLSLink(outbound)
            "naive" -> generateNaiveLink(outbound)
            "tuic" -> generateTuicLink(outbound)
            "http" -> generateHttpLink(outbound)
            "socks" -> generateSocksLink(outbound)
            "ssh" -> generateSshLink(outbound)
            "wireguard" -> generateWireGuardLink(outbound)
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    private fun hasInvalidV2RayTransport(outbound: Outbound): Boolean {
        if (outbound.type !in V2RAY_TRANSPORT_PROTOCOLS) return false
        val transport = outbound.transport ?: return false
        val type = transport.type?.trim()?.lowercase().orEmpty()
        if (type.isNotEmpty() && type !in V2RAY_TRANSPORT_TYPES) return true
        if (type in V2RAY_XHTTP_TRANSPORT_TYPES && outbound.encryption?.let {
                it.isNotBlank() && !it.equals("none", ignoreCase = true)
            } == true) return true
        return type == "ws" && transport.maxEarlyData?.let { value ->
            runCatching { UInt32JsonAdapter.requireValue(value) }.isFailure
        } == true
    }

    private fun encodeUrlComponent(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun formatServerHost(server: String): String {
        val s = server.trim()
        return if (s.contains(":") && !s.startsWith("[") && !s.endsWith("]")) "[$s]" else s
    }

    private fun encodeBase64NoWrap(bytes: ByteArray): String {
        return try {
            val base64Class = Class.forName("java.util.Base64")
            val encoder = base64Class.getMethod("getEncoder").invoke(null)
            encoder.javaClass.getMethod("encodeToString", ByteArray::class.java).invoke(encoder, bytes) as String
        } catch (_: Throwable) {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }

    private fun buildOptionalQuery(params: List<String>): String {
        val query = params.filter { it.isNotBlank() }.joinToString("&")
        return if (query.isNotEmpty()) "?$query" else ""
    }

    private fun buildUserInfo(username: String?, password: String?): String {
        val user = username?.takeIf { it.isNotBlank() } ?: return ""
        val encodedUser = encodeUrlComponent(user)
        val encodedPassword = password?.takeIf { it.isNotBlank() }?.let { ":${encodeUrlComponent(it)}" }.orEmpty()
        return "$encodedUser$encodedPassword@"
    }

    private fun joinTransportHosts(hosts: List<String>?): String? {
        return hosts.orEmpty()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")
    }

    @Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod", "NestedBlockDepth", "LongMethod")
    private fun generateVLessLink(outbound: Outbound): String {
        val uuid = outbound.uuid ?: return ""
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 443
        val params = mutableListOf<String>()

        params.add("type=${outbound.transport?.type ?: "tcp"}")
        params.add("encryption=none")

        outbound.flow?.let { params.add("flow=$it") }

        if (outbound.tls?.enabled == true) {
            if (outbound.tls.reality?.enabled == true) {
                params.add("security=reality")
                outbound.tls.reality.publicKey?.let { params.add("pbk=${encodeUrlComponent(it)}") }
                outbound.tls.reality.shortId?.let { params.add("sid=${encodeUrlComponent(it)}") }
                outbound.tls.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
            } else {
                params.add("security=tls")
                outbound.tls.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
            }
            outbound.tls.utls?.fingerprint?.let { params.add("fp=${encodeUrlComponent(it)}") }
            if (outbound.tls.insecure == true) {
                params.add("allowInsecure=1")
            }
            outbound.tls.alpn?.let {
                if (it.isNotEmpty()) params.add("alpn=${encodeUrlComponent(it.joinToString(","))}")
            }
        }

        outbound.packetEncoding?.let { params.add("packetEncoding=$it") }

        when (outbound.transport?.type) {
            "ws" -> {
                val host = outbound.transport.headers?.get("Host")
                    ?: outbound.transport.host?.firstOrNull()
                host?.let { params.add("host=${encodeUrlComponent(it)}") }

                var path = outbound.transport.path ?: "/"
                outbound.transport.maxEarlyData?.let { ed ->
                    if (ed != 0L) {
                        val separator = if (path.contains("?")) "&" else "?"
                        path = "$path${separator}ed=$ed"
                    }
                }
                params.add("path=${encodeUrlComponent(path)}")
            }
            "grpc" -> {
                outbound.transport.serviceName?.let {
                    params.add("serviceName=${encodeUrlComponent(it)}")
                }
                params.add("mode=gun")
            }
            "http", "h2" -> {
                outbound.transport.path?.let { params.add("path=${encodeUrlComponent(it)}") }
                joinTransportHosts(outbound.transport.host)
                    ?.let { params.add("host=${encodeUrlComponent(it)}") }
            }
            "httpupgrade" -> {
                outbound.transport.path?.let { params.add("path=${encodeUrlComponent(it)}") }
                val host = outbound.transport.host?.firstOrNull()
                    ?: outbound.transport.headers?.get("Host")
                    ?: outbound.transport.headers?.get("host")
                host?.let { params.add("host=${encodeUrlComponent(it)}") }
            }
            "xhttp", "splithttp" -> {
                outbound.transport.path?.let { params.add("path=${encodeUrlComponent(it)}") }
                joinTransportHosts(outbound.transport.host)
                    ?.let { params.add("host=${encodeUrlComponent(it)}") }
                outbound.transport.mode?.let { params.add("mode=${encodeUrlComponent(it)}") }
                outbound.transport.xPaddingBytes?.let {
                    params.add("xPaddingBytes=${encodeUrlComponent(it)}")
                }
                outbound.transport.scMaxEachPostBytes?.let { params.add("scMaxEachPostBytes=$it") }
                outbound.transport.scMinPostsIntervalMs?.let { params.add("scMinPostsIntervalMs=$it") }
                outbound.transport.scMaxBufferedPosts?.let { params.add("scMaxBufferedPosts=$it") }
                if (outbound.transport.noGRPCHeader == true) params.add("noGRPCHeader=1")
                if (outbound.transport.noSSEHeader == true) params.add("noSSEHeader=1")
            }
        }

        val name = encodeUrlComponent(outbound.tag)
        val queryPart = buildOptionalQuery(params)
        return "vless://$uuid@$server:$port$queryPart#$name"
    }

    private fun generateVMessLink(outbound: Outbound, gson: Gson): String {
        return try {
            val json = VMessLinkConfig(
                v = "2",
                ps = outbound.tag,
                add = outbound.server,
                port = outbound.serverPort?.toString(),
                id = outbound.uuid,
                aid = (outbound.alterId ?: 0).toString(),
                scy = outbound.security,
                net = outbound.transport?.type ?: "tcp",
                type = "none",
                host = outbound.transport?.headers?.get("Host") ?: outbound.transport?.host?.firstOrNull() ?: "",
                path = outbound.transport?.path ?: "",
                tls = if (outbound.tls?.enabled == true) "tls" else "",
                sni = outbound.tls?.serverName ?: "",
                alpn = outbound.tls?.alpn?.joinToString(","),
                fp = outbound.tls?.utls?.fingerprint,
                mode = outbound.transport?.mode,
                xPaddingBytes = outbound.transport?.xPaddingBytes,
                scMaxEachPostBytes = outbound.transport?.scMaxEachPostBytes,
                scMinPostsIntervalMs = outbound.transport?.scMinPostsIntervalMs,
                scMaxBufferedPosts = outbound.transport?.scMaxBufferedPosts,
                noGRPCHeader = outbound.transport?.noGRPCHeader,
                noSSEHeader = outbound.transport?.noSSEHeader
            )
            val jsonStr = gson.toJson(json)
            val base64 = encodeBase64NoWrap(jsonStr.toByteArray())
            "vmess://$base64"
        } catch (_: Exception) {
            ""
        }
    }

    private fun generateShadowsocksLink(outbound: Outbound): String {
        val method = outbound.method ?: return ""
        val password = outbound.password ?: return ""
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: return ""
        val userInfo = "$method:$password"
        val encodedUserInfo = encodeBase64NoWrap(userInfo.toByteArray())
        val serverPart = "$server:$port"
        val name = encodeUrlComponent(outbound.tag)
        val params = mutableListOf<String>()
        outbound.plugin?.takeIf { it.isNotBlank() }?.let { plugin ->
            val pluginValue = outbound.pluginOpts
                ?.takeIf { it.isNotBlank() }
                ?.let { "$plugin;$it" }
                ?: plugin
            params.add("plugin=${encodeUrlComponent(pluginValue)}")
        }
        val queryPart = buildOptionalQuery(params)
        return "ss://$encodedUserInfo@$serverPart$queryPart#$name"
    }

    @Suppress("CyclomaticComplexMethod")
    private fun generateTrojanLink(outbound: Outbound): String {
        val password = encodeUrlComponent(outbound.password ?: "")
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 443
        val name = encodeUrlComponent(outbound.tag)

        val params = mutableListOf<String>()
        if (outbound.tls?.enabled == true) {
            params.add("security=tls")
            outbound.tls.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
            if (outbound.tls.insecure == true) params.add("allowInsecure=1")
            outbound.tls.alpn?.takeIf { it.isNotEmpty() }?.let {
                params.add("alpn=${encodeUrlComponent(it.joinToString(","))}")
            }
            outbound.tls.utls?.fingerprint?.let { params.add("fp=${encodeUrlComponent(it)}") }
        }

        val transport = outbound.transport
        if (transport != null) {
            params.add("type=${encodeUrlComponent(transport.type ?: "tcp")}")
            when (transport.type) {
                "ws" -> {
                    val host = transport.headers?.get("Host")
                        ?: transport.headers?.get("host")
                        ?: transport.host?.firstOrNull()
                    host?.let { params.add("host=${encodeUrlComponent(it)}") }
                    params.add("path=${encodeUrlComponent(transport.path ?: "/")}")
                }
                "grpc" -> transport.serviceName?.let {
                    params.add("serviceName=${encodeUrlComponent(it)}")
                }
                "http", "h2" -> {
                    joinTransportHosts(transport.host)?.let { params.add("host=${encodeUrlComponent(it)}") }
                    transport.path?.let { params.add("path=${encodeUrlComponent(it)}") }
                }
                "httpupgrade" -> {
                    transport.host?.firstOrNull()?.let { params.add("host=${encodeUrlComponent(it)}") }
                    transport.path?.let { params.add("path=${encodeUrlComponent(it)}") }
                }
                "xhttp", "splithttp" -> {
                    joinTransportHosts(transport.host)?.let { params.add("host=${encodeUrlComponent(it)}") }
                    transport.path?.let { params.add("path=${encodeUrlComponent(it)}") }
                    transport.mode?.let { params.add("mode=${encodeUrlComponent(it)}") }
                    transport.xPaddingBytes?.let {
                        params.add("xPaddingBytes=${encodeUrlComponent(it)}")
                    }
                    transport.scMaxEachPostBytes?.let { params.add("scMaxEachPostBytes=$it") }
                    transport.scMinPostsIntervalMs?.let { params.add("scMinPostsIntervalMs=$it") }
                    transport.scMaxBufferedPosts?.let { params.add("scMaxBufferedPosts=$it") }
                    if (transport.noGRPCHeader == true) params.add("noGRPCHeader=1")
                    if (transport.noSSEHeader == true) params.add("noSSEHeader=1")
                }
            }
        }

        val queryPart = buildOptionalQuery(params)
        return "trojan://$password@$server:$port$queryPart#$name"
    }

    @Suppress("CyclomaticComplexMethod")
    private fun generateHttpLink(outbound: Outbound): String {
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val useTls = outbound.tls?.enabled == true
        val scheme = if (useTls) "https" else "http"
        val port = outbound.serverPort ?: if (useTls) 443 else 8080
        val name = encodeUrlComponent(outbound.tag)
        val userInfo = buildUserInfo(outbound.username, outbound.password)
        val params = mutableListOf<String>()
        outbound.tls?.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
        if (outbound.tls?.insecure == true) params.add("insecure=1")
        outbound.tls?.alpn?.takeIf { it.isNotEmpty() }?.let {
            params.add("alpn=${encodeUrlComponent(it.joinToString(","))}")
        }
        outbound.tls?.certificatePublicKeySha256?.firstOrNull()?.let {
            params.add("pinSHA256=${encodeUrlComponent(it)}")
        }
        outbound.tls?.ca?.takeIf { it.isNotEmpty() }?.let {
            params.add("certificate=${encodeUrlComponent(it.joinToString("\n"))}")
        }
        outbound.tls?.caPath?.let { params.add("certificate_path=${encodeUrlComponent(it)}") }
        outbound.tls?.certificate?.takeIf { it.isNotEmpty() }?.let {
            params.add("client_certificate=${encodeUrlComponent(it.joinToString("\n"))}")
        }
        outbound.tls?.certificatePath?.let { params.add("client_certificate_path=${encodeUrlComponent(it)}") }
        outbound.tls?.key?.takeIf { it.isNotEmpty() }?.let {
            params.add("client_key=${encodeUrlComponent(it.joinToString("\n"))}")
        }
        outbound.tls?.keyPath?.let { params.add("client_key_path=${encodeUrlComponent(it)}") }
        outbound.path?.let { params.add("proxy_path=${encodeUrlComponent(it)}") }
        outbound.headers
            ?.takeIf { it.isNotEmpty() }
            ?.allHeaderValues()
            ?.flatMap { (key, values) -> values.map { value -> "$key: $value" } }
            ?.joinToString("\n")
            ?.let { params.add("headers=${encodeUrlComponent(it)}") }
        val queryPart = buildOptionalQuery(params)
        return "$scheme://$userInfo$server:$port$queryPart#$name"
    }

    private fun generateSocksLink(outbound: Outbound): String {
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 1080
        val name = encodeUrlComponent(outbound.tag)
        val userInfo = buildUserInfo(outbound.username, outbound.password)
        val version = outbound.version?.asString?.lowercase()?.takeIf { it in setOf("4", "4a", "5") } ?: "5"
        val params = mutableListOf<String>()
        outbound.network?.firstOrNull()?.let { params.add("network=${encodeUrlComponent(it)}") }
        if (outbound.udpOverTcp?.enabled == true) params.add("uot=1")
        val queryPart = buildOptionalQuery(params)
        return "socks$version://$userInfo$server:$port$queryPart#$name"
    }

    private fun generateSshLink(outbound: Outbound): String {
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 22
        val name = encodeUrlComponent(outbound.tag)
        val userInfo = buildUserInfo(outbound.user, outbound.password)
        val params = mutableListOf<String>()
        outbound.privateKey?.firstOrNull()?.let { params.add("private_key=${encodeUrlComponent(it)}") }
        outbound.privateKeyPath?.let { params.add("private_key_path=${encodeUrlComponent(it)}") }
        outbound.privateKeyPassphrase?.let { params.add("private_key_passphrase=${encodeUrlComponent(it)}") }
        outbound.hostKey?.takeIf { it.isNotEmpty() }?.let {
            params.add("host_key=${encodeUrlComponent(it.joinToString(","))}")
        }
        outbound.hostKeyAlgorithms?.takeIf { it.isNotEmpty() }?.let {
            params.add("host_key_algorithms=${encodeUrlComponent(it.joinToString(","))}")
        }
        outbound.clientVersion?.let { params.add("client_version=${encodeUrlComponent(it)}") }
        val queryPart = buildOptionalQuery(params)
        return "ssh://$userInfo$server:$port$queryPart#$name"
    }

    @Suppress("CyclomaticComplexMethod")
    private fun generateWireGuardLink(outbound: Outbound): String {
        val privateKey = outbound.privateKey?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return ""
        val peer = outbound.peers?.firstOrNull() ?: return ""
        val server = peer.server?.let { formatServerHost(it) } ?: return ""
        val publicKey = peer.publicKey?.takeIf { it.isNotBlank() } ?: return ""
        val localAddress = outbound.localAddress?.takeIf { it.isNotEmpty() }?.joinToString(",") ?: return ""
        val port = peer.serverPort ?: 51820
        val name = encodeUrlComponent(outbound.tag)
        val params = mutableListOf(
            "public_key=${encodeUrlComponent(publicKey)}",
            "address=${encodeUrlComponent(localAddress)}"
        )
        peer.preSharedKey?.takeIf { it.isNotBlank() }?.let {
            params.add("pre_shared_key=${encodeUrlComponent(it)}")
        }
        peer.allowedIps?.takeIf { it.isNotEmpty() }?.let {
            params.add("allowed_ips=${encodeUrlComponent(it.joinToString(","))}")
        }
        peer.persistentKeepaliveInterval?.let { params.add("persistent_keepalive_interval=$it") }
        peer.reserved?.takeIf { it.isNotEmpty() }?.let {
            params.add("reserved=${encodeUrlComponent(it.joinToString(","))}")
        }
        outbound.mtu?.let { params.add("mtu=$it") }
        outbound.listenPort?.let { params.add("listen_port=$it") }
        outbound.udpTimeout?.let { params.add("udp_timeout=${encodeUrlComponent(it)}") }
        outbound.workers?.let { params.add("workers=$it") }
        outbound.system?.let { params.add("system=${if (it) 1 else 0}") }
        outbound.endpointName?.let { params.add("name=${encodeUrlComponent(it)}") }
        val queryPart = buildOptionalQuery(params)
        return "wireguard://${encodeUrlComponent(privateKey)}@$server:$port$queryPart#$name"
    }

    @Suppress("CyclomaticComplexMethod")
    private fun generateHysteria2Link(outbound: Outbound): String {
        val password = encodeUrlComponent(outbound.password ?: "")
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 443
        val name = encodeUrlComponent(outbound.tag)

        val params = mutableListOf<String>()

        outbound.tls?.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
        if (outbound.tls?.insecure == true) params.add("insecure=1")
        outbound.tls?.alpn?.takeIf { it.isNotEmpty() }?.let {
            params.add("alpn=${encodeUrlComponent(it.joinToString(","))}")
        }
        outbound.tls?.certificatePublicKeySha256?.firstOrNull()?.let {
            params.add("pinSHA256=${encodeUrlComponent(it)}")
        }

        outbound.obfs?.let { obfs ->
            obfs.type?.let { params.add("obfs=${encodeUrlComponent(it)}") }
            obfs.password?.let { params.add("obfs-password=${encodeUrlComponent(it)}") }
        }
        outbound.upMbps?.let { params.add("upmbps=$it") }
        outbound.downMbps?.let { params.add("downmbps=$it") }
        outbound.serverPorts?.takeIf { it.isNotEmpty() }?.let {
            params.add("mport=${encodeUrlComponent(it.joinToString(","))}")
        }
        outbound.hopInterval?.let { params.add("hop_interval=${encodeUrlComponent(it)}") }
        outbound.network?.firstOrNull()?.let { params.add("network=${encodeUrlComponent(it)}") }

        val queryPart = buildOptionalQuery(params)
        return "hysteria2://$password@$server:$port$queryPart#$name"
    }

    @Suppress("CyclomaticComplexMethod")
    private fun generateHysteriaLink(outbound: Outbound): String {
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 443
        val name = encodeUrlComponent(outbound.tag)

        val params = mutableListOf<String>()
        outbound.authStr?.let { params.add("auth=${encodeUrlComponent(it)}") }
        outbound.upMbps?.let { params.add("upmbps=$it") }
        outbound.downMbps?.let { params.add("downmbps=$it") }

        outbound.tls?.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
        if (outbound.tls?.insecure == true) params.add("insecure=1")
        outbound.tls?.alpn?.let {
            if (it.isNotEmpty()) params.add("alpn=${encodeUrlComponent(it.joinToString(","))}")
        }
        outbound.tls?.certificatePublicKeySha256?.firstOrNull()?.let {
            params.add("pinSHA256=${encodeUrlComponent(it)}")
        }

        outbound.obfs?.let { obfs ->
            obfs.type?.let { params.add("obfs=${encodeUrlComponent(it)}") }
        }
        outbound.serverPorts?.takeIf { it.isNotEmpty() }?.let {
            params.add("mport=${encodeUrlComponent(it.joinToString(","))}")
        }
        outbound.hopInterval?.let { params.add("hop_interval=${encodeUrlComponent(it)}") }
        outbound.network?.firstOrNull()?.let { params.add("network=${encodeUrlComponent(it)}") }
        outbound.recvWindowConn?.let { params.add("recv_window_conn=$it") }
        outbound.recvWindow?.let { params.add("recv_window=$it") }
        if (outbound.disableMtuDiscovery == true) params.add("disable_mtu_discovery=1")

        val queryPart = buildOptionalQuery(params)
        return "hysteria://$server:$port$queryPart#$name"
    }

    private fun generateAnyTLSLink(outbound: Outbound): String {
        val password = encodeUrlComponent(outbound.password ?: "")
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 443
        val name = encodeUrlComponent(outbound.tag)

        val params = mutableListOf<String>()

        outbound.tls?.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
        if (outbound.tls?.insecure == true) params.add("insecure=1")
        outbound.tls?.alpn?.let {
            if (it.isNotEmpty()) params.add("alpn=${encodeUrlComponent(it.joinToString(","))}")
        }
        outbound.tls?.utls?.fingerprint?.let { params.add("fp=${encodeUrlComponent(it)}") }

        outbound.idleSessionCheckInterval?.let { params.add("idle_session_check_interval=$it") }
        outbound.idleSessionTimeout?.let { params.add("idle_session_timeout=$it") }
        outbound.minIdleSession?.let { params.add("min_idle_session=$it") }

        val queryPart = buildOptionalQuery(params)
        return "anytls://$password@$server:$port$queryPart#$name"
    }

    @Suppress("CyclomaticComplexMethod")
    private fun generateNaiveLink(outbound: Outbound): String {
        val username = encodeUrlComponent(outbound.username ?: "")
        val password = encodeUrlComponent(outbound.password ?: "")
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 443
        val name = encodeUrlComponent(outbound.tag)

        val params = mutableListOf<String>()
        val networkValue = if (outbound.quic == true || outbound.network?.firstOrNull() == "quic") "quic" else "h2"
        params.add("network=${encodeUrlComponent(networkValue)}")
        outbound.tls?.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
        outbound.insecureConcurrency?.let { params.add("insecure_concurrency=$it") }
        outbound.extraHeaders
            ?.takeIf { it.isNotEmpty() }
            ?.allHeaderValues()
            ?.flatMap { (key, values) -> values.map { value -> "$key: $value" } }
            ?.joinToString("\n")
            ?.let { params.add("extra_headers=${encodeUrlComponent(it)}") }
        (outbound.quicCongestionControl ?: outbound.congestionControl)
            ?.let { params.add("congestion_control=${encodeUrlComponent(it)}") }
        if (outbound.udpOverTcp?.enabled == true) params.add("uot=1")

        val queryPart = buildOptionalQuery(params)
        return "naive://$username:$password@$server:$port$queryPart#$name"
    }

    @Suppress("CyclomaticComplexMethod")
    private fun generateTuicLink(outbound: Outbound): String {
        val uuid = outbound.uuid ?: ""
        val password = encodeUrlComponent(outbound.password ?: "")
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 443
        val name = encodeUrlComponent(outbound.tag)

        val params = mutableListOf<String>()

        outbound.congestionControl?.let { params.add("congestion_control=${encodeUrlComponent(it)}") }
        outbound.udpRelayMode?.let { params.add("udp_relay_mode=${encodeUrlComponent(it)}") }
        if (outbound.udpOverStream == true) params.add("udp_over_stream=1")
        if (outbound.zeroRttHandshake == true) params.add("reduce_rtt=1")
        outbound.heartbeat?.let { params.add("heartbeat=${encodeUrlComponent(it)}") }
        outbound.network?.firstOrNull()?.let { params.add("network=${encodeUrlComponent(it)}") }

        outbound.tls?.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
        if (outbound.tls?.insecure == true) params.add("allow_insecure=1")
        outbound.tls?.alpn?.let {
            if (it.isNotEmpty()) params.add("alpn=${encodeUrlComponent(it.joinToString(","))}")
        }
        outbound.tls?.utls?.fingerprint?.let { params.add("fp=${encodeUrlComponent(it)}") }

        val queryPart = buildOptionalQuery(params)
        return "tuic://$uuid:$password@$server:$port$queryPart#$name"
    }
}
