package com.kunk.singbox.model

import androidx.annotation.Keep
import com.google.gson.JsonArray
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type
import java.math.BigInteger

@Keep
data class SingBoxConfig(
    @SerializedName("\$schema") val schema: String? = null,
    @SerializedName("log") val log: LogConfig? = null,
    @SerializedName("dns") val dns: DnsConfig? = null,
    @SerializedName("ntp") val ntp: JsonElement? = null,
    @SerializedName("certificate") val certificate: JsonElement? = null,
    @SerializedName("endpoints") val endpoints: List<Endpoint>? = null,
    @SerializedName("inbounds") val inbounds: List<Inbound>? = null,
    @SerializedName("outbounds") val outbounds: List<Outbound>? = null,
    @SerializedName("route") val route: RouteConfig? = null,
    @SerializedName("services") val services: List<JsonElement>? = null,
    @SerializedName("experimental") val experimental: ExperimentalConfig? = null,
    // Add default outbounds for compatibility with non-standard config formats where outbounds are in a different field or handled differently
    @SerializedName("proxies") val proxies: List<Outbound>? = null
)

@Keep
data class LogConfig(
    @SerializedName("level") val level: String? = null,
    @SerializedName("timestamp") val timestamp: Boolean? = null,
    @SerializedName("output") val output: String? = null
)

@Keep
data class DnsConfig(
    @SerializedName("servers") val servers: List<DnsServer>? = null,
    @SerializedName("rules") val rules: List<DnsRule>? = null,
    @SerializedName("final") val finalServer: String? = null,
    @SerializedName("strategy") val strategy: String? = null,
    @SerializedName("disable_cache") val disableCache: Boolean? = null,
    @SerializedName("disable_expire") val disableExpire: Boolean? = null,
    @SerializedName("independent_cache") val independentCache: Boolean? = null,
    @SerializedName("reverse_mapping") val reverseMapping: Boolean? = null,
    @JsonAdapter(UInt32JsonAdapter::class)
    @SerializedName("cache_capacity") val cacheCapacity: Long? = null,
    @SerializedName("client_subnet") val clientSubnet: String? = null,
    @SerializedName("fakeip") val fakeip: DnsFakeIpConfig? = null
)

@Keep
data class DnsServer(
    @SerializedName("tag") val tag: String? = null,
    // New format fields (sing-box 1.13+)
    @SerializedName("type") val type: String? = null,
    @SerializedName("server") val server: String? = null,
    @SerializedName("server_port") val serverPort: Int? = null,
    @SerializedName("path") val pathRaw: Any? = null,
    @JsonAdapter(StringListMapJsonAdapter::class)
    @SerializedName("predefined") val predefined: Map<String, List<String>>? = null,
    @SerializedName("prefer_go") val preferGo: Boolean? = null,
    @SerializedName("method") val method: String? = null,
    @SerializedName("domain_resolver") val domainResolver: DomainResolveConfig? = null,
    @SerializedName("domain_strategy") val domainStrategy: String? = null,
    @SerializedName("udp_fragment") val udpFragment: Boolean? = null,
    @SerializedName("network_strategy") val networkStrategy: String? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("network_type") val networkType: List<String>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("fallback_network_type") val fallbackNetworkType: List<String>? = null,
    @SerializedName("fallback_delay") val fallbackDelay: String? = null,
    @SerializedName("bind_interface") val bindInterface: String? = null,
    @SerializedName("inet4_bind_address") val inet4BindAddress: String? = null,
    @SerializedName("inet6_bind_address") val inet6BindAddress: String? = null,
    @SerializedName("bind_address_no_port") val bindAddressNoPort: Boolean? = null,
    @SerializedName("protect_path") val protectPath: String? = null,
    @SerializedName("routing_mark") val routingMark: JsonElement? = null,
    @SerializedName("reuse_addr") val reuseAddr: Boolean? = null,
    @SerializedName("netns") val netns: String? = null,
    @SerializedName("connect_timeout") val connectTimeout: String? = null,
    @SerializedName("tcp_fast_open") val tcpFastOpen: Boolean? = null,
    @SerializedName("tcp_multi_path") val tcpMultiPath: Boolean? = null,
    @SerializedName("disable_tcp_keep_alive") val disableTcpKeepAlive: Boolean? = null,
    @SerializedName("tcp_keep_alive") val tcpKeepAlive: String? = null,
    @SerializedName("tcp_keep_alive_interval") val tcpKeepAliveInterval: String? = null,
    @SerializedName("interface") val interfaceName: String? = null,
    @SerializedName("service") val service: String? = null,
    @SerializedName("accept_default_resolvers") val acceptDefaultResolvers: Boolean? = null,
    @SerializedName("inet4_range") val inet4Range: String? = null,
    @SerializedName("inet6_range") val inet6Range: String? = null,
    // Legacy fields (kept for parsing imported old-format configs)
    @SerializedName("address") val address: String? = null,
    @SerializedName("address_resolver") val addressResolver: String? = null,
    @SerializedName("address_strategy") val addressStrategy: String? = null,
    @SerializedName("address_fallback_delay") val addressFallbackDelay: String? = null,
    @SerializedName("client_subnet") val clientSubnet: String? = null,
    @JsonAdapter(HttpHeaderMapJsonAdapter::class)
    @SerializedName("headers") val headers: Map<String, String>? = null,
    @SerializedName("tls") val tls: TlsConfig? = null,
    @SerializedName("detour") val detour: String? = null,
    @SerializedName("strategy") val strategy: String? = null
) {
    val path: String?
        get() = when (val raw = pathRaw) {
            is String -> raw
            is List<*> -> raw.firstOrNull()?.toString()
            else -> null
        }

    val paths: List<String>?
        get() = when (val raw = pathRaw) {
            is String -> listOf(raw)
            is List<*> -> raw.mapNotNull { it?.toString() }
            else -> null
        }?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
}

@Keep
data class DnsFakeIpConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("inet4_range") val inet4Range: String? = null,
    @SerializedName("inet6_range") val inet6Range: String? = null
)

@Keep
data class DnsRule(
    // sing-box 1.11.0+: action-based DNS rule
    // https://sing-box.sagernet.org/configuration/dns/rule/
    // https://sing-box.sagernet.org/configuration/dns/rule_action/
    @SerializedName("type") val type: String? = null,
    @SerializedName("mode") val mode: String? = null,
    @SerializedName("rules") val rules: List<DnsRule>? = null,
    @SerializedName("invert") val invert: Boolean? = null,
    @SerializedName("action") val action: String? = null,
    @SerializedName("strategy") val strategy: String? = null,
    @SerializedName("disable_cache") val disableCache: Boolean? = null,
    @JsonAdapter(UInt32JsonAdapter::class)
    @SerializedName("rewrite_ttl") val rewriteTtl: Long? = null,
    @SerializedName("client_subnet") val clientSubnet: String? = null,
    // reject/predefined action fields
    @SerializedName("method") val method: String? = null,
    @SerializedName("no_drop") val noDrop: Boolean? = null,
    @JsonAdapter(NullSafeJsonPrimitiveAdapter::class)
    @SerializedName("rcode") val rcode: JsonPrimitive? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("answer") val answer: List<String>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("ns") val ns: List<String>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("extra") val extra: List<String>? = null,

    @JsonAdapter(StringArrayJsonAdapter::class)
    @SerializedName("domain") val domain: List<String>? = null,
    @JsonAdapter(StringArrayJsonAdapter::class)
    @SerializedName("domain_suffix") val domainSuffix: List<String>? = null,
    @JsonAdapter(StringArrayJsonAdapter::class)
    @SerializedName("domain_keyword") val domainKeyword: List<String>? = null,
    @JsonAdapter(StringArrayJsonAdapter::class)
    @SerializedName("domain_regex") val domainRegex: List<String>? = null,
    @JsonAdapter(StringArrayJsonAdapter::class)
    @SerializedName("geosite") val geosite: List<String>? = null,
    @JsonAdapter(StringArrayJsonAdapter::class)
    @SerializedName("rule_set") val ruleSet: List<String>? = null,
    @JsonAdapter(DnsQueryTypeListJsonAdapter::class)
    @SerializedName("query_type") val queryType: List<String>? = null,
    @JsonAdapter(StringArrayJsonAdapter::class)
    @SerializedName("inbound") val inbound: List<String>? = null,
    @JsonAdapter(StringArrayJsonAdapter::class)
    @SerializedName("package_name") val packageName: List<String>? = null,
    @JsonAdapter(Int32ListableJsonAdapter::class)
    @SerializedName("user_id") val userId: List<Int>? = null,
    @SerializedName("ip_version") val ipVersion: Int? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("network") val network: List<String>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("auth_user") val authUser: List<String>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("protocol") val protocol: List<String>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("client") val client: List<String>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("source_geoip") val sourceGeoip: List<String>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("geoip") val geoip: List<String>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("ip_cidr") val ipCidr: List<String>? = null,
    @SerializedName("ip_is_private") val ipIsPrivate: Boolean? = null,
    @SerializedName("ip_accept_any") val ipAcceptAny: Boolean? = null,
    @JsonAdapter(StringListMapJsonAdapter::class)
    @SerializedName("interface_address") val interfaceAddress: Map<String, List<String>>? = null,
    @JsonAdapter(StringListMapJsonAdapter::class)
    @SerializedName("network_interface_address") val networkInterfaceAddress: Map<String, List<String>>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("default_interface_address") val defaultInterfaceAddress: List<String>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("source_ip_cidr") val sourceIpCidr: List<String>? = null,
    @SerializedName("source_ip_is_private") val sourceIpIsPrivate: Boolean? = null,
    @JsonAdapter(UInt16ListableJsonAdapter::class)
    @SerializedName("source_port") val sourcePort: List<Int>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("source_port_range") val sourcePortRange: List<String>? = null,
    @JsonAdapter(UInt16ListableJsonAdapter::class)
    @SerializedName("port") val port: List<Int>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("port_range") val portRange: List<String>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("process_name") val processName: List<String>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("process_path") val processPath: List<String>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("process_path_regex") val processPathRegex: List<String>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("user") val user: List<String>? = null,
    @SerializedName("clash_mode") val clashMode: String? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("network_type") val networkType: List<String>? = null,
    @SerializedName("network_is_expensive") val networkIsExpensive: Boolean? = null,
    @SerializedName("network_is_constrained") val networkIsConstrained: Boolean? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("wifi_ssid") val wifiSsid: List<String>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("wifi_bssid") val wifiBssid: List<String>? = null,
    @SerializedName("rule_set_ip_cidr_match_source") val ruleSetIpCidrMatchSource: Boolean? = null,
    @SerializedName("rule_set_ip_cidr_accept_empty") val ruleSetIpCidrAcceptEmpty: Boolean? = null,
    @SerializedName("server") val server: String? = null,
    // outbound can be a String or List<String>, use Any to avoid parsing error
    @SerializedName("outbound") val outboundRaw: Any? = null
) {
    val outbound: String?
        get() = when (outboundRaw) {
            is String -> outboundRaw
            is List<*> -> outboundRaw.firstOrNull()?.toString()
            else -> null
        }
}

@Keep
data class Inbound(
    @SerializedName("type") val type: String? = null,
    @SerializedName("tag") val tag: String? = null,
    @SerializedName("listen") val listen: String? = null,
    @SerializedName("listen_port") val listenPort: Int? = null,
    @SerializedName("reuse_addr") val reuseAddr: Boolean? = null,
    @SerializedName("interface_name") val interfaceName: String? = null,
    @SerializedName("address") val addressRaw: Any? = null,
    @SerializedName("inet4_address") val inet4AddressRaw: Any? = null,
    @SerializedName("inet6_address") val inet6AddressRaw: Any? = null,
    @SerializedName("mtu") val mtu: Int? = null,
    @SerializedName("auto_route") val autoRoute: Boolean? = null,
    @SerializedName("strict_route") val strictRoute: Boolean? = null,
    @SerializedName("stack") val stack: String? = null,
    @SerializedName("sniff") val sniff: Boolean? = null,
    @SerializedName("sniff_override_destination") val sniffOverrideDestination: Boolean? = null,
    @SerializedName("sniff_timeout") val sniffTimeout: String? = null,
    @SerializedName("tcp_fast_open") val tcpFastOpen: Boolean? = null,
    @SerializedName("gso") val gso: Boolean? = null,
    @SerializedName("users") val users: List<InboundUser>? = null
) {
    val address: List<String>?
        get() = when (val raw = addressRaw) {
            is String -> listOf(raw)
            is List<*> -> raw.filterIsInstance<String>()
            else -> null
        }

    val inet4Address: List<String>?
        get() = when (val raw = inet4AddressRaw) {
            is String -> listOf(raw)
            is List<*> -> raw.filterIsInstance<String>()
            else -> null
        }

    val inet6Address: List<String>?
        get() = when (val raw = inet6AddressRaw) {
            is String -> listOf(raw)
            is List<*> -> raw.filterIsInstance<String>()
            else -> null
        }
}

@Keep
data class InboundUser(
    @SerializedName("username") val username: String? = null,
    @SerializedName("password") val password: String? = null
)

@Keep
data class Outbound(
    @SerializedName("type") val type: String = "",
    @SerializedName("tag") val tag: String = "",

    @SerializedName("server") val server: String? = null,
    @SerializedName("server_port") val serverPort: Int? = null,
    @SerializedName("bind_interface") val bindInterface: String? = null,
    @SerializedName("inet4_bind_address") val inet4BindAddress: String? = null,
    @SerializedName("inet6_bind_address") val inet6BindAddress: String? = null,
    @SerializedName("bind_address_no_port") val bindAddressNoPort: Boolean? = null,
    @SerializedName("protect_path") val protectPath: String? = null,
    @SerializedName("routing_mark") val routingMark: JsonElement? = null,
    @SerializedName("reuse_addr") val reuseAddr: Boolean? = null,
    @SerializedName("netns") val netns: String? = null,
    @SerializedName("tcp_fast_open") val tcpFastOpen: Boolean? = null,
    @SerializedName("tcp_multi_path") val tcpMultiPath: Boolean? = null,
    @SerializedName("disable_tcp_keep_alive") val disableTcpKeepAlive: Boolean? = null,
    @SerializedName("tcp_keep_alive") val tcpKeepAlive: String? = null,
    @SerializedName("tcp_keep_alive_interval") val tcpKeepAliveInterval: String? = null,
    @SerializedName("udp_fragment") val udpFragment: Boolean? = null,
    @SerializedName("connect_timeout") val connectTimeout: String? = null,
    @SerializedName("domain_resolver") val domainResolver: DomainResolveConfig? = null,
    @SerializedName("network_strategy") val networkStrategy: String? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("network_type") val networkType: List<String>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("fallback_network_type") val fallbackNetworkType: List<String>? = null,
    @SerializedName("fallback_delay") val fallbackDelay: String? = null,
    @SerializedName("domain_strategy") val domainStrategy: String? = null,

    @SerializedName("outbounds") val outbounds: List<String>? = null,
    @SerializedName("default") val default: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("interval") val interval: String? = null,
    @SerializedName("tolerance") val tolerance: Int? = null,
    @SerializedName("idle_timeout") val idleTimeout: String? = null,
    @SerializedName("interrupt_exist_connections") val interruptExistConnections: Boolean? = null,

    @SerializedName("method") val method: String? = null,
    @SerializedName("password") val password: String? = null,
    @SerializedName("plugin") val plugin: String? = null,
    @SerializedName("plugin_opts") val pluginOpts: String? = null,
    @SerializedName("udp_over_tcp") val udpOverTcp: UdpOverTcpConfig? = null,

    @SerializedName("uuid") val uuid: String? = null,
    @SerializedName(value = "security", alternate = ["cipher"]) val security: String? = null,
    @SerializedName("alter_id") val alterId: Int? = null, // 0=AEAD, >0=legacy VMess MD5
    @SerializedName("global_padding") val globalPadding: Boolean? = null,
    @SerializedName("authenticated_length") val authenticatedLength: Boolean? = null,
    @SerializedName("flow") val flow: String? = null,
    @SerializedName("packet_encoding") val packetEncoding: String? = null,
    @SerializedName("encryption") val encryption: String? = null,

    @SerializedName("up") val up: JsonElement? = null,
    @SerializedName("up_mbps") val upMbps: Int? = null,
    @SerializedName("down") val down: JsonElement? = null,
    @SerializedName("down_mbps") val downMbps: Int? = null,
    @JsonAdapter(ObfsConfigJsonAdapter::class)
    @SerializedName("obfs") val obfs: ObfsConfig? = null,
    @SerializedName("auth") val auth: String? = null,
    @SerializedName("auth_str") val authStr: String? = null,
    @SerializedName("recv_window_conn") val recvWindowConn: BigInteger? = null,
    @SerializedName("recv_window") val recvWindow: BigInteger? = null,
    @SerializedName("disable_mtu_discovery") val disableMtuDiscovery: Boolean? = null,
    @SerializedName("hop_interval") val hopInterval: String? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("server_ports") val serverPorts: List<String>? = null,
    @SerializedName("brutal_debug") val brutalDebug: Boolean? = null,

    @SerializedName("idle_session_check_interval") val idleSessionCheckInterval: String? = null,
    @SerializedName("idle_session_timeout") val idleSessionTimeout: String? = null,
    @SerializedName("min_idle_session") val minIdleSession: Int? = null,

    @SerializedName("tls") val tls: TlsConfig? = null,

    @SerializedName("transport") val transport: TransportConfig? = null,

    @SerializedName("multiplex") val multiplex: MultiplexConfig? = null,

    @SerializedName("congestion_control") val congestionControl: String? = null,
    @SerializedName("quic") val quic: Boolean? = null,
    @SerializedName("quic_congestion_control") val quicCongestionControl: String? = null,
    @SerializedName("insecure_concurrency") val insecureConcurrency: Int? = null,
    @JsonAdapter(HttpHeaderMapJsonAdapter::class)
    @SerializedName("extra_headers") val extraHeaders: Map<String, String>? = null,
    @SerializedName("stream_receive_window") val streamReceiveWindow: JsonElement? = null,
    @SerializedName("udp_relay_mode") val udpRelayMode: String? = null,
    @SerializedName("udp_over_stream") val udpOverStream: Boolean? = null,
    @SerializedName("zero_rtt_handshake") val zeroRttHandshake: Boolean? = null,
    @SerializedName("heartbeat") val heartbeat: String? = null,
    @SerializedName("disable_sni") val disableSni: Boolean? = null,
    @SerializedName("quic_session_receive_window") val quicSessionReceiveWindow: JsonElement? = null,
    @SerializedName("mtu") val mtu: Int? = null,

    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("local_address") val localAddress: List<String>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("private_key") val privateKey: List<String>? = null,
    @SerializedName("system") val system: Boolean? = null,
    @SerializedName("name") val endpointName: String? = null,
    @SerializedName("listen_port") val listenPort: Int? = null,
    @SerializedName("udp_timeout") val udpTimeout: String? = null,
    @SerializedName("workers") val workers: Int? = null,
    @SerializedName("peer_public_key") val peerPublicKey: String? = null,
    @SerializedName("pre_shared_key") val preSharedKey: String? = null,
    @SerializedName("reserved") val reserved: List<Int>? = null,
    @SerializedName("peers") val peers: List<WireGuardPeer>? = null,

    @SerializedName("user") val user: String? = null,
    @SerializedName("private_key_path") val privateKeyPath: String? = null,
    @SerializedName("private_key_passphrase") val privateKeyPassphrase: String? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("host_key") val hostKey: List<String>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("host_key_algorithms") val hostKeyAlgorithms: List<String>? = null,
    @SerializedName("client_version") val clientVersion: String? = null,

    @JsonAdapter(NullSafeJsonPrimitiveAdapter::class)
    @SerializedName("version") val version: JsonPrimitive? = null,
    @SerializedName("detour") val detour: String? = null,

    @SerializedName("username") val username: String? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("network") val network: List<String>? = null,
    @SerializedName("path") val path: String? = null,
    @JsonAdapter(HttpHeaderMapJsonAdapter::class)
    @SerializedName("headers") val headers: Map<String, String>? = null
)

@Keep
data class WireGuardPeer(
    @SerializedName(value = "address", alternate = ["server"]) val server: String? = null,
    @SerializedName(value = "port", alternate = ["server_port"]) val serverPort: Int? = null,
    @SerializedName("public_key") val publicKey: String? = null,
    @SerializedName("pre_shared_key") val preSharedKey: String? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("allowed_ips") val allowedIps: List<String>? = null,
    @SerializedName("persistent_keepalive_interval") val persistentKeepaliveInterval: Int? = null,
    @SerializedName("reserved") val reserved: List<Int>? = null
)

@Keep
data class Endpoint(
    @SerializedName("type") val type: String = "",
    @SerializedName("tag") val tag: String = "",
    @SerializedName("system") val system: Boolean? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("mtu") val mtu: Int? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("address") val address: List<String>? = null,
    @SerializedName("private_key") val privateKey: String? = null,
    @SerializedName("listen_port") val listenPort: Int? = null,
    @SerializedName("peers") val peers: List<WireGuardPeer>? = null,
    @SerializedName("udp_timeout") val udpTimeout: String? = null,
    @SerializedName("workers") val workers: Int? = null,
    @SerializedName("detour") val detour: String? = null,
    @SerializedName("bind_interface") val bindInterface: String? = null,
    @SerializedName("inet4_bind_address") val inet4BindAddress: String? = null,
    @SerializedName("inet6_bind_address") val inet6BindAddress: String? = null,
    @SerializedName("bind_address_no_port") val bindAddressNoPort: Boolean? = null,
    @SerializedName("protect_path") val protectPath: String? = null,
    @SerializedName("routing_mark") val routingMark: JsonElement? = null,
    @SerializedName("reuse_addr") val reuseAddr: Boolean? = null,
    @SerializedName("netns") val netns: String? = null,
    @SerializedName("connect_timeout") val connectTimeout: String? = null,
    @SerializedName("tcp_fast_open") val tcpFastOpen: Boolean? = null,
    @SerializedName("tcp_multi_path") val tcpMultiPath: Boolean? = null,
    @SerializedName("disable_tcp_keep_alive") val disableTcpKeepAlive: Boolean? = null,
    @SerializedName("tcp_keep_alive") val tcpKeepAlive: String? = null,
    @SerializedName("tcp_keep_alive_interval") val tcpKeepAliveInterval: String? = null,
    @SerializedName("udp_fragment") val udpFragment: Boolean? = null,
    @SerializedName("network_strategy") val networkStrategy: String? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("network_type") val networkType: List<String>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("fallback_network_type") val fallbackNetworkType: List<String>? = null,
    @SerializedName("fallback_delay") val fallbackDelay: String? = null,
    @SerializedName("domain_strategy") val domainStrategy: String? = null,
    @SerializedName("domain_resolver") val domainResolver: DomainResolveConfig? = null
)

@Keep
data class UdpOverTcpConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("version") val version: Int? = null
)

@Keep
@JsonAdapter(DomainResolveConfigJsonAdapter::class)
data class DomainResolveConfig(
    @SerializedName("server") val server: String? = null,
    @SerializedName("strategy") val strategy: String? = null,
    @SerializedName("disable_cache") val disableCache: Boolean? = null,
    @SerializedName("rewrite_ttl") val rewriteTtl: Long? = null,
    @SerializedName("client_subnet") val clientSubnet: String? = null
)

class NullSafeJsonPrimitiveAdapter : JsonDeserializer<JsonPrimitive> {
    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): JsonPrimitive? {
        if (json == null || json.isJsonNull) return null
        if (!json.isJsonPrimitive) {
            throw JsonParseException("Expected JSON primitive")
        }
        return json.asJsonPrimitive
    }
}

class UInt32JsonAdapter : JsonSerializer<Long>, JsonDeserializer<Long> {
    override fun serialize(
        src: Long?,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        if (src == null) return com.google.gson.JsonNull.INSTANCE
        return JsonPrimitive(requireValue(src))
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): Long? = read(json)

    companion object {
        private const val MAX_VALUE = 4_294_967_295L

        fun read(json: JsonElement?): Long? {
            if (json == null || json.isJsonNull) return null
            if (!json.isJsonPrimitive || !json.asJsonPrimitive.isNumber) {
                throw JsonParseException("uint32 value must be an integer")
            }
            val value = json.asString.toLongOrNull()
                ?: throw JsonParseException("uint32 value must be an integer")
            return requireValue(value)
        }

        fun requireValue(value: Long): Long {
            if (value !in 0L..MAX_VALUE) {
                throw JsonParseException("uint32 value out of range: $value")
            }
            return value
        }
    }
}

abstract class RangedIntListJsonAdapter(
    private val minValue: Long,
    private val maxValue: Long
) : JsonSerializer<List<Int>>, JsonDeserializer<List<Int>> {
    override fun serialize(
        src: List<Int>?,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        val values = src.orEmpty().map { value -> requireValue(value.toLong()) }
        if (values.size == 1) return JsonPrimitive(values.first())
        return JsonArray().apply { values.forEach { add(it) } }
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): List<Int>? {
        if (json == null || json.isJsonNull) return null
        val values = when {
            json.isJsonPrimitive -> listOf(readValue(json.asJsonPrimitive))
            json.isJsonArray -> json.asJsonArray.map { element ->
                if (!element.isJsonPrimitive) throw JsonParseException("integer list contains non-primitive value")
                readValue(element.asJsonPrimitive)
            }
            else -> throw JsonParseException("integer list must be a number or number array")
        }
        return values.takeIf { it.isNotEmpty() }
    }

    private fun readValue(value: JsonPrimitive): Int {
        if (!value.isNumber) throw JsonParseException("integer list value must be a number")
        return requireValue(
            value.asString.toLongOrNull()
                ?: throw JsonParseException("integer list value must be an integer")
        )
    }

    private fun requireValue(value: Long): Int {
        if (value !in minValue..maxValue) {
            throw JsonParseException("integer list value out of range: $value")
        }
        return value.toInt()
    }
}

class UInt16ListableJsonAdapter : RangedIntListJsonAdapter(0L, 65_535L)

class Int32ListableJsonAdapter : RangedIntListJsonAdapter(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())

private class DnsQueryTypeList(
    internal val rawValues: List<JsonPrimitive>
) : AbstractList<String>() {
    override val size: Int = rawValues.size

    override fun get(index: Int): String = rawValues[index].asString
}

class DnsQueryTypeListJsonAdapter : JsonSerializer<List<String>>, JsonDeserializer<List<String>> {
    override fun serialize(
        src: List<String>?,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        val values = (src as? DnsQueryTypeList)?.rawValues
            ?: src.orEmpty().filter { it.isNotBlank() }.map { JsonPrimitive(it) }
        if (values.size == 1) return values.first()
        return JsonArray().apply { values.forEach { add(it) } }
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): List<String>? {
        if (json == null || json.isJsonNull) return null
        val values = when {
            json.isJsonPrimitive -> listOf(requireQueryType(json.asJsonPrimitive))
            json.isJsonArray -> json.asJsonArray.map { element ->
                if (!element.isJsonPrimitive) throw JsonParseException("query_type contains non-primitive value")
                requireQueryType(element.asJsonPrimitive)
            }
            else -> throw JsonParseException("query_type must be a string, number, or array")
        }
        return DnsQueryTypeList(values).takeIf { it.isNotEmpty() }
    }

    private fun requireQueryType(value: JsonPrimitive): JsonPrimitive {
        if (!value.isString && !value.isNumber) {
            throw JsonParseException("query_type must contain only strings or numbers")
        }
        return value
    }
}

class StringListMapJsonAdapter :
    JsonSerializer<Map<String, List<String>>>,
    JsonDeserializer<Map<String, List<String>>> {
    override fun serialize(
        src: Map<String, List<String>>?,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        if (src == null) return com.google.gson.JsonNull.INSTANCE
        return JsonObject().apply {
            src.forEach { (key, rawValues) ->
                val values = rawValues.filter { it.isNotBlank() }
                when (values.size) {
                    0 -> Unit
                    1 -> addProperty(key, values.first())
                    else -> add(key, JsonArray().apply { values.forEach { add(it) } })
                }
            }
        }
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): Map<String, List<String>>? {
        if (json == null || json.isJsonNull) return null
        if (!json.isJsonObject) throw JsonParseException("listable string map must be an object")
        return buildMap {
            json.asJsonObject.entrySet().forEach { (key, rawValue) ->
                val values = requireStringValues(key, rawValue)
                if (values.isNotEmpty()) put(key, values)
            }
        }.takeIf { it.isNotEmpty() }
    }

    private fun requireStringValues(key: String, rawValue: JsonElement): List<String> {
        return when {
            rawValue.isJsonPrimitive && rawValue.asJsonPrimitive.isString -> listOf(rawValue.asString)
            rawValue.isJsonArray -> rawValue.asJsonArray.map { item ->
                if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString) {
                    throw JsonParseException("listable string map '$key' must contain only strings")
                }
                item.asString
            }
            else -> throw JsonParseException("listable string map '$key' must be a string or string array")
        }.filter { it.isNotBlank() }
    }
}

class DomainResolveConfigJsonAdapter : JsonSerializer<DomainResolveConfig>, JsonDeserializer<DomainResolveConfig> {
    override fun serialize(
        src: DomainResolveConfig?,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        if (src == null) return com.google.gson.JsonNull.INSTANCE
        val hasQueryOptions = !src.strategy.isNullOrBlank() || src.disableCache == true
        val hasResponseOptions = src.rewriteTtl != null || !src.clientSubnet.isNullOrBlank()
        if (!src.server.isNullOrBlank() && !hasQueryOptions && !hasResponseOptions) {
            return JsonPrimitive(src.server)
        }

        return JsonObject().apply {
            src.server?.takeIf { it.isNotBlank() }?.let { addProperty("server", it) }
            src.strategy?.takeIf { it.isNotBlank() }?.let { addProperty("strategy", it) }
            src.disableCache?.takeIf { it }?.let { addProperty("disable_cache", it) }
            src.rewriteTtl?.let { addProperty("rewrite_ttl", UInt32JsonAdapter.requireValue(it)) }
            src.clientSubnet?.takeIf { it.isNotBlank() }?.let { addProperty("client_subnet", it) }
        }
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): DomainResolveConfig? {
        if (json == null || json.isJsonNull) return null
        if (json.isJsonPrimitive && json.asJsonPrimitive.isString) {
            return DomainResolveConfig(server = json.asString)
        }
        if (!json.isJsonObject) return null

        val value = json.asJsonObject
        return DomainResolveConfig(
            server = value.get("server")?.takeIf { it.isJsonPrimitive }?.asString,
            strategy = value.get("strategy")?.takeIf { it.isJsonPrimitive }?.asString,
            disableCache = value.get("disable_cache")?.takeIf { it.isJsonPrimitive }?.asBoolean,
            rewriteTtl = UInt32JsonAdapter.read(value.get("rewrite_ttl")),
            clientSubnet = value.get("client_subnet")?.takeIf { it.isJsonPrimitive }?.asString
        )
    }
}

@Keep
data class ObfsConfig(
    @SerializedName("type") val type: String? = null,
    @SerializedName("password") val password: String? = null,
    @Transient val stringValue: Boolean = false
)

class ObfsConfigJsonAdapter : JsonSerializer<ObfsConfig>, JsonDeserializer<ObfsConfig> {
    override fun serialize(
        src: ObfsConfig?,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        if (src == null) return com.google.gson.JsonNull.INSTANCE
        if (src.stringValue) return JsonPrimitive(src.type.orEmpty())

        return JsonObject().apply {
            src.type?.takeIf { it.isNotBlank() }?.let { addProperty("type", it) }
            src.password?.takeIf { it.isNotBlank() }?.let { addProperty("password", it) }
        }
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): ObfsConfig? {
        if (json == null || json.isJsonNull) return null
        if (json.isJsonPrimitive) {
            return ObfsConfig(type = json.asString, stringValue = true)
        }
        if (!json.isJsonObject) return null

        val value = json.asJsonObject
        return ObfsConfig(
            type = value.get("type")?.takeIf { it.isJsonPrimitive }?.asString,
            password = value.get("password")?.takeIf { it.isJsonPrimitive }?.asString
        )
    }
}

@Keep
data class TlsConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("disable_sni") val disableSni: Boolean? = null,
    @SerializedName("server_name") val serverName: String? = null,
    @SerializedName("insecure") val insecure: Boolean? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("alpn") val alpn: List<String>? = null,
    @SerializedName("min_version") val minVersion: String? = null,
    @SerializedName("max_version") val maxVersion: String? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("cipher_suites") val cipherSuites: List<String>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("curve_preferences") val curvePreferences: List<String>? = null,
    @SerializedName("utls") val utls: UtlsConfig? = null,
    @SerializedName("reality") val reality: RealityConfig? = null,
    @SerializedName("ech") val ech: EchConfig? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName(value = "certificate", alternate = ["ca"]) val ca: List<String>? = null,
    @SerializedName(value = "certificate_path", alternate = ["ca_path"]) val caPath: String? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName(value = "client_certificate", alternate = ["client-cert", "client_cert"])
    val certificate: List<String>? = null,
    @SerializedName(value = "client_certificate_path", alternate = ["client-cert-path", "client_cert_path"])
    val certificatePath: String? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName(value = "client_key", alternate = ["client-key"]) val key: List<String>? = null,
    @SerializedName(value = "client_key_path", alternate = ["client-key-path"])
    val keyPath: String? = null,
    @SerializedName("fragment") val fragment: Boolean? = null,
    @SerializedName("fragment_fallback_delay") val fragmentFallbackDelay: String? = null,
    @SerializedName("record_fragment") val recordFragment: Boolean? = null,
    @SerializedName("kernel_tx") val kernelTx: Boolean? = null,
    @SerializedName("kernel_rx") val kernelRx: Boolean? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("certificate_public_key_sha256") val certificatePublicKeySha256: List<String>? = null
)

@Keep
data class EchConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("query_server_name") val queryServerName: String? = null,
    @Transient val dnsServer: String? = null,
    @SerializedName("pq_signature_schemes_enabled") val pqSignatureSchemesEnabled: Boolean? = null,
    @SerializedName("dynamic_record_sizing_disabled") val dynamicRecordSizingDisabled: Boolean? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("config") val config: List<String>? = null,
    @SerializedName("config_path") val configPath: String? = null
)

@Keep
data class UtlsConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("fingerprint") val fingerprint: String? = null
)

@Keep
data class RealityConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("public_key") val publicKey: String? = null,
    @SerializedName("short_id") val shortId: String? = null
    // Note: spiderX is Xray-core specific, not supported by sing-box
)

@Keep
data class TransportConfig(
    @SerializedName("type") val type: String? = null,
    @SerializedName("path") val path: String? = null,
    @SerializedName("method") val method: String? = null,
    @JsonAdapter(HttpHeaderMapJsonAdapter::class)
    @SerializedName("headers") val headers: Map<String, String>? = null,
    @SerializedName("service_name") val serviceName: String? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("host") val host: List<String>? = null,
    @SerializedName("idle_timeout") val idleTimeout: String? = null,
    @SerializedName("ping_timeout") val pingTimeout: String? = null,
    @SerializedName("permit_without_stream") val permitWithoutStream: Boolean? = null,
    @SerializedName("early_data_header_name") val earlyDataHeaderName: String? = null,
    @JsonAdapter(UInt32JsonAdapter::class)
    @SerializedName("max_early_data") val maxEarlyData: Long? = null,
    @SerializedName("mode") val mode: String? = null,
    @SerializedName("sc_max_each_post_bytes") val scMaxEachPostBytes: Long? = null,
    @SerializedName("sc_min_posts_interval_ms") val scMinPostsIntervalMs: Long? = null,
    @SerializedName("sc_max_buffered_posts") val scMaxBufferedPosts: Long? = null,
    @SerializedName("x_padding_bytes") val xPaddingBytes: String? = null,
    @SerializedName("no_grpc_header") val noGRPCHeader: Boolean? = null,
    @SerializedName("no_sse_header") val noSSEHeader: Boolean? = null
)

private class HttpHeaderMap(
    internal val allValues: Map<String, List<String>>
) : AbstractMap<String, String>() {
    override val entries: Set<Map.Entry<String, String>> = allValues
        .mapValues { (_, values) -> values.firstOrNull().orEmpty() }
        .entries
}

class HttpHeaderMapJsonAdapter : JsonSerializer<Map<String, String>>, JsonDeserializer<Map<String, String>> {
    override fun serialize(
        src: Map<String, String>?,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        if (src == null) return com.google.gson.JsonNull.INSTANCE
        val preserved = (src as? HttpHeaderMap)?.allValues
        return JsonObject().apply {
            src.forEach { (name, firstValue) ->
                val values = if (preserved?.containsKey(name) == true) {
                    preserved.getValue(name)
                } else {
                    listOf(firstValue)
                }
                if (values.size == 1) {
                    addProperty(name, values.first())
                } else {
                    add(name, JsonArray().apply { values.forEach { add(it) } })
                }
            }
        }
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): Map<String, String>? {
        if (json == null || json.isJsonNull) return null
        if (!json.isJsonObject) throw JsonParseException("HTTP headers must be an object")
        val values = buildMap {
            json.asJsonObject.entrySet().forEach { (name, rawValue) ->
                val headerValues = when {
                    rawValue.isJsonPrimitive && rawValue.asJsonPrimitive.isString -> listOf(rawValue.asString)
                    rawValue.isJsonArray -> rawValue.asJsonArray.map { item ->
                        if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString) {
                            throw JsonParseException("HTTP header '$name' must contain only strings")
                        }
                        item.asString
                    }
                    else -> throw JsonParseException("HTTP header '$name' must be a string or string array")
                }
                put(name, headerValues)
            }
        }
        return HttpHeaderMap(values)
    }
}

internal fun Map<String, String>.allHeaderValues(): Map<String, List<String>> {
    return (this as? HttpHeaderMap)?.allValues ?: mapValues { (_, value) -> listOf(value) }
}

internal fun Map<String, List<String>>.asHttpHeaderMap(): Map<String, String> = HttpHeaderMap(this)

class StringListJsonAdapter : JsonSerializer<List<String>>, JsonDeserializer<List<String>> {
    override fun serialize(
        src: List<String>?,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        val values = src.orEmpty().filter { it.isNotBlank() }
        if (values.size == 1) return JsonPrimitive(values.first())

        val array = JsonArray()
        values.forEach { array.add(it) }
        return array
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): List<String>? {
        if (json == null || json.isJsonNull) return null
        return when {
            json.isJsonPrimitive -> listOf(json.asString).filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            json.isJsonArray -> json.asJsonArray
                .mapNotNull { element -> element.takeIf { it.isJsonPrimitive }?.asString }
                .filter { it.isNotBlank() }
                .takeIf { it.isNotEmpty() }
            else -> null
        }
    }
}

class StringArrayJsonAdapter : JsonSerializer<List<String>>, JsonDeserializer<List<String>> {
    override fun serialize(
        src: List<String>?,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        val array = JsonArray()
        src.orEmpty()
            .filter { it.isNotBlank() }
            .forEach { array.add(it) }
        return array
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): List<String>? {
        if (json == null || json.isJsonNull) return null
        return when {
            json.isJsonPrimitive -> listOf(json.asString).filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            json.isJsonArray -> json.asJsonArray
                .mapNotNull { element -> element.takeIf { it.isJsonPrimitive }?.asString }
                .filter { it.isNotBlank() }
                .takeIf { it.isNotEmpty() }
            else -> null
        }
    }
}

@Keep
data class MultiplexConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("protocol") val protocol: String? = null,
    @SerializedName("max_connections") val maxConnections: Int? = null,
    @SerializedName("min_streams") val minStreams: Int? = null,
    @SerializedName("max_streams") val maxStreams: Int? = null,
    @SerializedName("padding") val padding: Boolean? = null,
    @SerializedName("brutal") val brutal: BrutalConfig? = null
)

@Keep
data class BrutalConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("up_mbps") val upMbps: Int? = null,
    @SerializedName("down_mbps") val downMbps: Int? = null
)

@Keep
data class RouteConfig(
    @SerializedName("rules") val rules: List<RouteRule>? = null,
    @SerializedName("rule_set") val ruleSet: List<RuleSetConfig>? = null,
    @SerializedName("final") val finalOutbound: String? = null,
    @SerializedName("find_process") val findProcess: Boolean? = null,
    @SerializedName("auto_detect_interface") val autoDetectInterface: Boolean? = null,
    @SerializedName("default_interface") val defaultInterface: String? = null,
    @SerializedName("default_domain_resolver") val defaultDomainResolver: DomainResolveConfig? = null,
    @SerializedName("default_network_strategy") val defaultNetworkStrategy: String? = null,
    @SerializedName("default_network_type") val defaultNetworkType: List<String>? = null,
    @SerializedName("default_fallback_network_type") val defaultFallbackNetworkType: List<String>? = null,
    @SerializedName("default_fallback_delay") val defaultFallbackDelay: String? = null,
    @SerializedName("default_udp_fragment") val defaultUdpFragment: Boolean? = null
)

@Keep
data class RouteRule(
    @SerializedName("action") val action: String? = null,
    @SerializedName("network") val networkRaw: Any? = null,
    @SerializedName("protocol") val protocolRaw: Any? = null,
    @SerializedName("domain") val domain: List<String>? = null,
    @SerializedName("domain_suffix") val domainSuffix: List<String>? = null,
    @SerializedName("domain_keyword") val domainKeyword: List<String>? = null,
    @SerializedName("geosite") val geosite: List<String>? = null,
    @SerializedName("geoip") val geoip: List<String>? = null,
    @SerializedName("ip_cidr") val ipCidr: List<String>? = null,
    @SerializedName("ip_is_private") val ipIsPrivate: Boolean? = null,
    @SerializedName("port") val port: List<Int>? = null,
    @SerializedName("port_range") val portRange: List<String>? = null,
    @SerializedName("rule_set") val ruleSet: List<String>? = null,
    @SerializedName("inbound") val inbound: List<String>? = null,
    @SerializedName("package_name") val packageName: List<String>? = null,
    @SerializedName("process_name") val processName: List<String>? = null,
    @SerializedName("user_id") val userId: List<Int>? = null,
    @SerializedName("user") val user: List<String>? = null,
    @SerializedName("outbound") val outbound: String? = null
) {
    val network: List<String>?
        get() = when (networkRaw) {
            is String -> listOf(networkRaw)
            is List<*> -> networkRaw.filterIsInstance<String>()
            else -> null
        }

    val protocol: List<String>?
        get() = when (protocolRaw) {
            is String -> listOf(protocolRaw)
            is List<*> -> protocolRaw.filterIsInstance<String>()
            else -> null
        }
}

@Keep
data class RuleSetConfig(
    @SerializedName("tag") val tag: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("format") val format: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("path") val path: String? = null,
    @SerializedName("download_detour") val downloadDetour: String? = null,
    @SerializedName("update_interval") val updateInterval: String? = null
)

@Keep
data class ExperimentalConfig(
    @SerializedName("cache_file") val cacheFile: CacheFileConfig? = null,
    @SerializedName("clash_api") val clashApi: ClashApiConfig? = null
)

@Keep
data class CacheFileConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("path") val path: String? = null,
    @SerializedName("store_fakeip") val storeFakeip: Boolean? = null
)

@Keep
data class ClashApiConfig(
    @SerializedName("external_controller") val externalController: String? = null,
    @SerializedName("external_ui") val externalUi: String? = null,
    @SerializedName("secret") val secret: String? = null,
    @SerializedName("default_mode") val defaultMode: String? = null
)
