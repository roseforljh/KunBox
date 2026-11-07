package com.kunk.singbox.repository.config

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TransportConfig

/** Converts only nodes requiring the embedded Xray outbound, without changing ordinary nodes. */
internal object RayBoxOutboundConverter {
    private val gson = Gson()
    private val dialFields = setOf(
        "tag", "detour", "bind_interface", "inet4_bind_address", "inet6_bind_address", "bind_address_no_port",
        "protect_path", "routing_mark", "reuse_addr", "netns", "tcp_fast_open", "tcp_multi_path",
        "disable_tcp_keep_alive", "tcp_keep_alive", "tcp_keep_alive_interval", "udp_fragment", "connect_timeout",
        "domain_resolver", "network_strategy", "network_type", "fallback_network_type", "fallback_delay",
        "domain_strategy"
    )

    @Suppress("CyclomaticComplexMethod")
    fun prepareRuntime(node: Outbound): Outbound {
        if (node.type == "xray" || node.rayboxCloudflare == true) return convert(node)
        val transport = node.transport
        val session = transport?.type?.lowercase() in setOf("xhttp", "splithttp") ||
            node.type == "vless" && !node.encryption.isNullOrBlank() &&
            !node.encryption.equals("none", ignoreCase = true)
        if (session && node.tls?.reality?.enabled == true) {
            require(node.tls.alpn.isNullOrEmpty() && node.tls.insecure != true) {
                "节点 ${node.tag}: Reality 不支持 insecure 或 ALPN"
            }
        }
        rejectRx(transport?.extra, node.tag)
        if (transport == null || transport.type?.lowercase() !in setOf("xhttp", "splithttp")) {
            return if (node.rayboxCloudflare == null) node else node.copy(rayboxCloudflare = null)
        }
        require(transport.host == null || transport.host.size <= 1) { "节点 ${node.tag}: XHTTP 仅支持一个 host" }
        val source = gson.toJsonTree(transport).asJsonObject
        val extra = transport.extra?.deepCopy() ?: JsonObject()
        mapOf(
            "sc_max_each_post_bytes" to "scMaxEachPostBytes", "sc_min_posts_interval_ms" to "scMinPostsIntervalMs",
            "sc_max_buffered_posts" to "scMaxBufferedPosts", "x_padding_bytes" to "xPaddingBytes",
            "no_grpc_header" to "noGRPCHeader", "no_sse_header" to "noSSEHeader"
        ).forEach { (from, to) -> source.get(from)?.let {
            require(!extra.has(to)) { "节点 ${node.tag}: 重复的 XHTTP 选项 $to" }
            extra.add(to, it)
        } }
        return node.copy(
            rayboxCloudflare = null,
            transport = transport.copy(
                extra = extra.takeIf { it.size() > 0 }, scMaxEachPostBytes = null, scMinPostsIntervalMs = null,
                scMaxBufferedPosts = null, xPaddingBytes = null, noGRPCHeader = null, noSSEHeader = null
            )
        )
    }

    fun convert(node: Outbound, cloudflare: Boolean = node.rayboxCloudflare == true): Outbound {
        if (node.type == "xray") return validateExisting(node)
        val transport = node.transport?.type?.lowercase()
        val encrypted = node.type == "vless" && !node.encryption.isNullOrBlank() &&
            !node.encryption.equals("none", ignoreCase = true)
        if (node.type !in setOf("vless", "vmess", "trojan") ||
            (transport !in setOf("xhttp", "splithttp") && !encrypted)
        ) {
            require(node.rayboxCloudflare != true) { "节点 ${node.tag}: Cloudflare 标记需要 Xray 节点" }
            return if (node.rayboxCloudflare == null) node else node.copy(rayboxCloudflare = null)
        }

        val input = gson.toJsonTree(node).asJsonObject
        val protocolFields = when (node.type) {
            "vless" -> setOf("uuid", "flow", "encryption")
            "vmess" -> setOf("uuid", "security", "alter_id")
            else -> setOf("password")
        }
        allowed(
            input,
            dialFields + setOf("type", "server", "server_port", "tls", "transport", "raybox_cloudflare") +
                protocolFields,
            node.tag
        )
        require(node.packetEncoding.isNullOrBlank()) {
            "节点 ${node.tag}: Xray outbound 不支持 packet_encoding"
        }
        require(!node.server.isNullOrBlank() && node.serverPort in 1..65535) { "节点 ${node.tag}: 服务器地址或端口无效" }
        val settings = settings(node)
        val stream = stream(node, cloudflare)
        val inner = JsonObject().apply {
            addProperty("protocol", node.type)
            add("settings", settings)
            add("streamSettings", stream)
        }
        val outer = JsonObject().apply {
            input.entrySet().filter { it.key in dialFields }.forEach { add(it.key, it.value.deepCopy()) }
            addProperty("type", "xray")
            add("outbound", inner)
        }
        return gson.fromJson(outer, Outbound::class.java)
    }

    private fun validateExisting(node: Outbound): Outbound {
        val outer = gson.toJsonTree(node).asJsonObject
        allowed(outer, dialFields + setOf("type", "outbound"), node.tag)
        val inner = requireNotNull(node.xrayOutbound) { "节点 ${node.tag}: 缺少 Xray outbound" }
        allowed(inner, setOf("protocol", "settings", "streamSettings", "tag"), node.tag)
        val protocol = inner.get("protocol")
        require(protocol?.isJsonPrimitive == true && protocol.asJsonPrimitive.isString &&
            protocol.asString in setOf("vless", "vmess", "trojan")) { "节点 ${node.tag}: 不支持的 Xray 协议" }
        val stream = inner.get("streamSettings")
        require(stream == null || stream.isJsonObject && !stream.asJsonObject.has("sockopt")) {
            "节点 ${node.tag}: Xray sockopt 不受支持"
        }
        rejectRx(stream, node.tag)
        return node
    }

    private fun settings(node: Outbound): JsonObject {
        val user = JsonObject()
        if (node.type == "trojan") {
            require(!node.password.isNullOrBlank()) { "节点 ${node.tag}: 缺少密码" }
            user.addProperty("password", node.password)
        } else {
            require(!node.uuid.isNullOrBlank()) { "节点 ${node.tag}: 缺少 UUID" }
            user.addProperty("id", node.uuid)
            if (node.type == "vless") {
                user.addProperty("encryption", node.encryption?.takeIf { it.isNotBlank() } ?: "none")
                node.flow?.let { user.addProperty("flow", it) }
            } else {
                node.security?.let {
                    require(it.lowercase() in setOf("auto", "aes-128-gcm", "chacha20-poly1305")) {
                        "节点 ${node.tag}: 不支持的 VMess security"
                    }
                    user.addProperty("security", it)
                }
                require(node.alterId == null || node.alterId == 0) { "节点 ${node.tag}: Xray 不支持 VMess alterId" }
            }
        }
        val server = JsonObject().apply {
            addProperty("address", node.server)
            addProperty("port", node.serverPort)
            if (node.type == "trojan") addProperty("password", node.password)
            else add("users", JsonArray().apply { add(user) })
        }
        return JsonObject().apply {
            add(if (node.type == "trojan") "servers" else "vnext", JsonArray().apply { add(server) })
        }
    }

    @Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod", "NestedBlockDepth")
    private fun stream(node: Outbound, cloudflare: Boolean): JsonObject {
        val tls = node.tls
        val tlsJson = tls?.let { gson.toJsonTree(it).asJsonObject }
        tlsJson?.let { allowed(it, setOf("enabled", "server_name", "insecure", "alpn", "utls", "reality"), node.tag) }
        val reality = tls?.reality?.enabled == true
        require(tls?.utls?.enabled != true || !tls.utls.fingerprint.isNullOrBlank()) {
            "节点 ${node.tag}: 缺少 TLS fingerprint"
        }
        require(tls?.utls?.enabled != false || tls.utls.fingerprint == null) {
            "节点 ${node.tag}: 禁用 uTLS 时不能指定 fingerprint"
        }
        require(tls?.reality == null || reality ||
            tls.reality.publicKey == null && tls.reality.shortId == null) {
            "节点 ${node.tag}: 禁用 Reality 时不能指定 Reality 选项"
        }
        require(!reality || tls?.enabled == true) { "节点 ${node.tag}: Reality 需要 TLS" }
        require(!reality || tls?.insecure != true && tls?.alpn.isNullOrEmpty()) {
            "节点 ${node.tag}: Reality 不支持 insecure 或 ALPN"
        }
        val transport = node.transport
        val network = when (transport?.type?.lowercase()) {
            null, "", "tcp" -> "tcp"
            "xhttp", "splithttp" -> "xhttp"
            "ws" -> "ws"
            else -> throw IllegalArgumentException("节点 ${node.tag}: 不支持的 Xray 传输")
        }
        return JsonObject().apply {
            addProperty("network", network)
            addProperty("security", if (reality) "reality" else if (tls?.enabled == true) "tls" else "none")
            if (tls?.enabled == true) {
                val config = JsonObject().apply {
                    tls.serverName?.let { addProperty("serverName", it) }
                    if (reality) {
                        require(!tls.reality?.publicKey.isNullOrBlank()) { "节点 ${node.tag}: 缺少 Reality 公钥" }
                        addProperty("publicKey", tls.reality?.publicKey)
                        tls.reality?.shortId?.let { addProperty("shortId", it) }
                        addProperty("fingerprint", tls.utls?.fingerprint ?: "chrome")
                    } else {
                        tls.insecure?.let { addProperty("allowInsecure", it) }
                        tls.alpn?.let { add("alpn", gson.toJsonTree(it)) }
                        if (tls.utls?.enabled == true) addProperty("fingerprint", tls.utls.fingerprint)
                    }
                }
                add(if (reality) "realitySettings" else "tlsSettings", config)
            } else require(tlsJson == null || tlsJson.keySet().all { it == "enabled" }) {
                "节点 ${node.tag}: 未启用 TLS 但提供了 TLS 选项"
            }
            if (transport != null) {
                if (network == "tcp") allowed(gson.toJsonTree(transport).asJsonObject, setOf("type"), node.tag)
                else add(
                    if (network == "ws") "wsSettings" else "xhttpSettings",
                    transport(node.tag, transport, network, cloudflare)
                )
            }
            configureOob(this, node)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun transport(tag: String, value: TransportConfig, network: String, cloudflare: Boolean): JsonObject {
        val source = gson.toJsonTree(value).asJsonObject
        val names = if (network == "ws") setOf("type", "path", "headers") else setOf(
            "type", "path", "host", "mode", "extra", "sc_max_each_post_bytes", "sc_min_posts_interval_ms",
            "sc_max_buffered_posts", "x_padding_bytes", "no_grpc_header", "no_sse_header"
        )
        allowed(source, names, tag)
        require(value.host == null || value.host.size <= 1) { "节点 $tag: XHTTP 仅支持一个 host" }
        val extra = value.extra?.deepCopy() ?: JsonObject()
        val special = extra.has("rx") || extra.has("oob")
        if (!cloudflare) rejectRx(extra, tag)
        if (special) validateCloudflare(extra, tag)
        allowed(extra, setOf(
            "headers", "xPaddingBytes", "xPaddingObfsMode", "xPaddingKey", "xPaddingHeader", "xPaddingPlacement",
            "xPaddingMethod", "uplinkHTTPMethod", "sessionIDPlacement", "sessionIDKey", "sessionIDTable",
            "sessionIDLength", "seqPlacement", "seqKey", "uplinkDataPlacement", "uplinkDataKey", "uplinkChunkSize",
            "noGRPCHeader", "noSSEHeader", "scMaxEachPostBytes", "scMinPostsIntervalMs", "scMaxBufferedPosts",
            "scStreamUpServerSecs", "serverMaxHeaderBytes", "xmux", "packetDown", "rx", "oob"
        ), tag)
        extra.get("xmux")?.let {
            require(it.isJsonObject) { "节点 $tag: xmux 必须是对象" }
            allowed(it.asJsonObject, setOf(
                "maxConcurrency", "maxConnections", "cMaxReuseTimes", "hMaxRequestTimes", "hMaxReusableSecs",
                "hKeepAlivePeriod"
            ), tag)
        }
        val renames = mapOf(
            "sc_max_each_post_bytes" to "scMaxEachPostBytes", "sc_min_posts_interval_ms" to "scMinPostsIntervalMs",
            "sc_max_buffered_posts" to "scMaxBufferedPosts", "x_padding_bytes" to "xPaddingBytes",
            "no_grpc_header" to "noGRPCHeader", "no_sse_header" to "noSSEHeader"
        )
        renames.forEach { (from, to) -> source.get(from)?.let {
            require(!extra.has(to)) { "节点 $tag: 重复的 XHTTP 选项 $to" }
            extra.add(to, it)
        } }
        return JsonObject().apply {
            source.get("path")?.let { add("path", it) }
            if (network == "ws") source.get("headers")?.let { add("headers", it) }
            else {
                value.host?.firstOrNull()?.let { addProperty("host", it) }
                value.mode?.let { addProperty("mode", it) }
                if (extra.size() > 0) add("extra", extra)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun validateCloudflare(extra: JsonObject, tag: String) {
        val rx = extra.get("rx")
        require(rx?.isJsonObject == true) { "节点 $tag: RX 必须是对象" }
        val rxObject = rx.asJsonObject
        allowed(rxObject, setOf(
            "enabled", "stallDetect", "maxReplayBytes", "maxSessions", "probeIntervalMs", "stallTimeoutMs",
            "requestTimeoutMs", "sessionTtlSecs", "maxReconnects", "retryDelayMs", "maxResponseBytes", "preconnects",
            "maxSegments"
        ), tag)
        require(rxObject.get("enabled") == com.google.gson.JsonPrimitive(true)) { "节点 $tag: RX 必须显式启用" }
        val bounds = mapOf(
            "maxReplayBytes" to (4096..16777216), "maxSessions" to (1..4096), "probeIntervalMs" to (10..30000),
            "stallTimeoutMs" to (10..120000), "requestTimeoutMs" to (10..30000), "sessionTtlSecs" to (1..300),
            "maxReconnects" to (1..131072), "retryDelayMs" to (1..5000), "maxResponseBytes" to (256..65536),
            "preconnects" to (0..4), "maxSegments" to (0..16777216)
        )
        bounds.forEach { (key, range) -> rxObject.get(key)?.let { value ->
            val number = if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
                value.asString.toIntOrNull()
            } else null
            require(number != null && (number == 0 || number in range)) { "节点 $tag: RX $key 无效" }
        } }
        rxObject.get("stallDetect")?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "节点 $tag: RX stallDetect 无效" }
        }
        fun number(key: String, fallback: Int) = rxObject.get(key)?.asInt?.takeIf { it != 0 } ?: fallback
        val segment = number("maxResponseBytes", 0)
        require(segment <= number("maxReplayBytes", 262144) &&
            (segment != 0 || number("preconnects", 0) == 0 && number("maxSegments", 0) == 0) &&
            number("stallTimeoutMs", 3000) >= number("probeIntervalMs", 1000) &&
            number("sessionTtlSecs", 30) * 1000 >
            number("requestTimeoutMs", 5000) + 2 * number("probeIntervalMs", 1000)) {
            "节点 $tag: RX 参数组合无效"
        }
        extra.get("oob")?.let { oob ->
            require(oob.isJsonObject && oob.asJsonObject == JsonObject().apply { addProperty("enabled", true) }) {
                "节点 $tag: OOB 仅支持显式启用并自动配置 RX fallback"
            }
        }
        extra.entrySet().filter { it.key != "rx" && it.key != "oob" }.forEach { rejectRx(it.value, tag) }
    }

    private fun configureOob(stream: JsonObject, node: Outbound) {
        val xhttp = stream.getAsJsonObject("xhttpSettings") ?: return
        val extra = xhttp.getAsJsonObject("extra") ?: return
        if (!extra.has("rx") && !extra.has("oob")) return
        require(node.transport?.mode == "packet-up" && node.tls?.enabled == true &&
            node.tls.insecure != true && node.tls.reality?.enabled != true && node.tls.alpn == listOf("h2")) {
            "节点 ${node.tag}: RX/OOB 需要已验证 TLS、HTTP/2 和 packet-up"
        }
        if (!extra.has("oob")) return
        require(node.detour.isNullOrBlank() && node.tcpFastOpen != true) {
            "节点 ${node.tag}: OOB 不支持 detour 或 TCP Fast Open"
        }
        val fallback = stream.deepCopy()
        fallback.getAsJsonObject("xhttpSettings").getAsJsonObject("extra").remove("oob")
        extra.remove("rx")
        extra.getAsJsonObject("oob").add("fallbackSettings", fallback)
    }

    private fun rejectRx(value: com.google.gson.JsonElement?, tag: String) {
        when {
            value == null -> Unit
            value.isJsonArray -> value.asJsonArray.forEach { rejectRx(it, tag) }
            value.isJsonObject -> value.asJsonObject.entrySet().forEach { (key, child) ->
                require(!key.equals("rx", true) && !key.equals("oob", true)) {
                    "节点 $tag: RX/OOB 需要明确的 Cloudflare 上下文"
                }
                rejectRx(child, tag)
            }
        }
    }

    private fun allowed(value: JsonObject, keys: Set<String>, tag: String) {
        val unsupported = value.keySet() - keys
        require(unsupported.isEmpty()) { "节点 $tag: 不支持的选项 ${unsupported.joinToString()}" }
    }
}
