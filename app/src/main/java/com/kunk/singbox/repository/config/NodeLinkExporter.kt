package com.kunk.singbox.repository.config

import android.util.Base64
import com.google.gson.Gson
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.VMessLinkConfig

object NodeLinkExporter {

    fun export(outbound: Outbound, gson: Gson): String? {
        return when (outbound.type) {
            "vless" -> generateVLessLink(outbound)
            "vmess" -> generateVMessLink(outbound, gson)
            "shadowsocks" -> generateShadowsocksLink(outbound)
            "trojan" -> generateTrojanLink(outbound)
            "hysteria2" -> generateHysteria2Link(outbound)
            "hysteria" -> generateHysteriaLink(outbound)
            "anytls" -> generateAnyTLSLink(outbound)
            "tuic" -> generateTuicLink(outbound)
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    private fun encodeUrlComponent(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun formatServerHost(server: String): String {
        val s = server.trim()
        return if (s.contains(":") && !s.startsWith("[") && !s.endsWith("]")) "[$s]" else s
    }

    private fun buildOptionalQuery(params: List<String>): String {
        val query = params.filter { it.isNotBlank() }.joinToString("&")
        return if (query.isNotEmpty()) "?$query" else ""
    }

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
                    if (ed != 0) {
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
                outbound.transport.host?.firstOrNull()?.let { params.add("host=${encodeUrlComponent(it)}") }
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
                aid = "0",
                scy = outbound.security,
                net = outbound.transport?.type ?: "tcp",
                type = "none",
                host = outbound.transport?.headers?.get("Host") ?: outbound.transport?.host?.firstOrNull() ?: "",
                path = outbound.transport?.path ?: "",
                tls = if (outbound.tls?.enabled == true) "tls" else "",
                sni = outbound.tls?.serverName ?: "",
                alpn = outbound.tls?.alpn?.joinToString(","),
                fp = outbound.tls?.utls?.fingerprint
            )
            val jsonStr = gson.toJson(json)
            val base64 = Base64.encodeToString(jsonStr.toByteArray(), Base64.NO_WRAP)
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
        val encodedUserInfo = Base64.encodeToString(userInfo.toByteArray(), Base64.NO_WRAP)
        val serverPart = "$server:$port"
        val name = encodeUrlComponent(outbound.tag)
        return "ss://$encodedUserInfo@$serverPart#$name"
    }

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
        }

        val queryPart = buildOptionalQuery(params)
        return "trojan://$password@$server:$port$queryPart#$name"
    }

    private fun generateHysteria2Link(outbound: Outbound): String {
        val password = encodeUrlComponent(outbound.password ?: "")
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 443
        val name = encodeUrlComponent(outbound.tag)

        val params = mutableListOf<String>()

        outbound.tls?.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
        if (outbound.tls?.insecure == true) params.add("insecure=1")

        outbound.obfs?.let { obfs ->
            obfs.type?.let { params.add("obfs=${encodeUrlComponent(it)}") }
            obfs.password?.let { params.add("obfs-password=${encodeUrlComponent(it)}") }
        }

        val queryPart = buildOptionalQuery(params)
        return "hysteria2://$password@$server:$port$queryPart#$name"
    }

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

        outbound.obfs?.let { obfs ->
            obfs.type?.let { params.add("obfs=${encodeUrlComponent(it)}") }
        }

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

    private fun generateTuicLink(outbound: Outbound): String {
        val uuid = outbound.uuid ?: ""
        val password = encodeUrlComponent(outbound.password ?: "")
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 443
        val name = encodeUrlComponent(outbound.tag)

        val params = mutableListOf<String>()

        outbound.congestionControl?.let { params.add("congestion_control=${encodeUrlComponent(it)}") }
        outbound.udpRelayMode?.let { params.add("udp_relay_mode=${encodeUrlComponent(it)}") }
        if (outbound.zeroRttHandshake == true) params.add("reduce_rtt=1")

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
