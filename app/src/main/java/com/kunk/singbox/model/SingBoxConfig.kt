package com.kunk.singbox.model

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class SingBoxConfig(
    @SerializedName("log") val log: LogConfig? = null,
    @SerializedName("dns") val dns: DnsConfig? = null,
    @SerializedName("inbounds") val inbounds: List<Inbound>? = null,
    @SerializedName("outbounds") val outbounds: List<Outbound>? = null,
    @SerializedName("route") val route: RouteConfig? = null,
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
    @SerializedName("fakeip") val fakeip: DnsFakeIpConfig? = null
)

@Keep
data class DnsServer(
    @SerializedName("tag") val tag: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("server") val server: String? = null,
    @SerializedName("address") val address: String? = null,
    @SerializedName("address_resolver") val addressResolver: String? = null,
    @SerializedName("detour") val detour: String? = null,
    @SerializedName("strategy") val strategy: String? = null
)

@Keep
data class DnsFakeIpConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("inet4_range") val inet4Range: String? = null,
    @SerializedName("inet6_range") val inet6Range: String? = null
)

@Keep
data class DnsRule(
    @SerializedName("domain") val domain: List<String>? = null,
    @SerializedName("domain_suffix") val domainSuffix: List<String>? = null,
    @SerializedName("geosite") val geosite: List<String>? = null,
    @SerializedName("rule_set") val ruleSet: List<String>? = null,
    @SerializedName("query_type") val queryType: List<String>? = null,
    @SerializedName("package_name") val packageName: List<String>? = null,
    @SerializedName("user_id") val userId: List<Int>? = null,
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
    @SerializedName("interface_name") val interfaceName: String? = null,
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
    @SerializedName("endpoint_independent_nat") val endpointIndependentNat: Boolean? = null,
    @SerializedName("gso") val gso: Boolean? = null,
    @SerializedName("users") val users: List<InboundUser>? = null
) {
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
    @SerializedName("tcp_fast_open") val tcpFastOpen: Boolean? = null,

    // TCP Keepalive 字段 (sing-box 1.13.0+)
    @SerializedName("tcp_keep_alive") val tcpKeepAlive: String? = null,
    @SerializedName("tcp_keep_alive_interval") val tcpKeepAliveInterval: String? = null,
    @SerializedName("connect_timeout") val connectTimeout: String? = null,

    // Selector/URLTest 字段
    @SerializedName("outbounds") val outbounds: List<String>? = null,
    @SerializedName("default") val default: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("interval") val interval: String? = null,
    @SerializedName("tolerance") val tolerance: Int? = null,
    @SerializedName("interrupt_exist_connections") val interruptExistConnections: Boolean? = null,

    // Shadowsocks 字段
    @SerializedName("method") val method: String? = null,
    @SerializedName("password") val password: String? = null,
    @SerializedName("plugin") val plugin: String? = null,
    @SerializedName("plugin_opts") val pluginOpts: String? = null,
    @SerializedName("udp_over_tcp") val udpOverTcp: UdpOverTcpConfig? = null,

    // VMess/VLESS 字段
    @SerializedName("uuid") val uuid: String? = null,
    @SerializedName(value = "security", alternate = ["cipher"]) val security: String? = null,
    // 注意：sing-box 不支持 alter_id 字段，只支持 AEAD 加密的 VMess (alterId=0)
    @SerializedName("flow") val flow: String? = null,
    @SerializedName("packet_encoding") val packetEncoding: String? = null,

    // Hysteria/Hysteria2 字段
    @SerializedName("up_mbps") val upMbps: Int? = null,
    @SerializedName("down_mbps") val downMbps: Int? = null,
    @SerializedName("obfs") val obfs: ObfsConfig? = null,
    @SerializedName("auth_str") val authStr: String? = null,
    @SerializedName("recv_window_conn") val recvWindowConn: Int? = null,
    @SerializedName("recv_window") val recvWindow: Int? = null,
    @SerializedName("disable_mtu_discovery") val disableMtuDiscovery: Boolean? = null,
    @SerializedName("hop_interval") val hopInterval: String? = null,
    @SerializedName("ports") val ports: String? = null,

    // AnyTLS 字段
    @SerializedName("idle_session_check_interval") val idleSessionCheckInterval: String? = null,
    @SerializedName("idle_session_timeout") val idleSessionTimeout: String? = null,
    @SerializedName("min_idle_session") val minIdleSession: Int? = null,

    // TLS 配置
    @SerializedName("tls") val tls: TlsConfig? = null,

    // 传输层配置
    @SerializedName("transport") val transport: TransportConfig? = null,

    // 多路复用配置
    @SerializedName("multiplex") val multiplex: MultiplexConfig? = null,

    // TUIC 特有字段
    @SerializedName("congestion_control") val congestionControl: String? = null,
    @SerializedName("udp_relay_mode") val udpRelayMode: String? = null,
    @SerializedName("zero_rtt_handshake") val zeroRttHandshake: Boolean? = null,
    @SerializedName("heartbeat") val heartbeat: String? = null,
    @SerializedName("disable_sni") val disableSni: Boolean? = null,
    @SerializedName("mtu") val mtu: Int? = null,

    // WireGuard 字段
    @SerializedName("local_address") val localAddress: List<String>? = null,
    @SerializedName("private_key") val privateKey: String? = null,
    @SerializedName("peer_public_key") val peerPublicKey: String? = null,
    @SerializedName("pre_shared_key") val preSharedKey: String? = null,
    @SerializedName("reserved") val reserved: List<Int>? = null,
    @SerializedName("peers") val peers: List<WireGuardPeer>? = null,

    // SSH 字段
    @SerializedName("user") val user: String? = null,
    @SerializedName("private_key_path") val privateKeyPath: String? = null,
    @SerializedName("private_key_passphrase") val privateKeyPassphrase: String? = null,
    @SerializedName("host_key") val hostKey: List<String>? = null,
    @SerializedName("host_key_algorithms") val hostKeyAlgorithms: List<String>? = null,
    @SerializedName("client_version") val clientVersion: String? = null,

    // ShadowTLS 字段
    @SerializedName("version") val version: Int? = null,
    @SerializedName("detour") val detour: String? = null,

    // SOCKS/HTTP 字段
    @SerializedName("username") val username: String? = null,
    @SerializedName("network") val network: String? = null,
    // HTTP outbound 特有字段
    @SerializedName("path") val path: String? = null,
    @SerializedName("headers") val headers: Map<String, String>? = null
)

@Keep
data class WireGuardPeer(
    @SerializedName("server") val server: String? = null,
    @SerializedName("server_port") val serverPort: Int? = null,
    @SerializedName("public_key") val publicKey: String? = null,
    @SerializedName("pre_shared_key") val preSharedKey: String? = null,
    @SerializedName("allowed_ips") val allowedIps: List<String>? = null,
    @SerializedName("reserved") val reserved: List<Int>? = null
)

@Keep
data class UdpOverTcpConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("version") val version: Int? = null
)

@Keep
data class ObfsConfig(
    @SerializedName("type") val type: String? = null,
    @SerializedName("password") val password: String? = null
)

@Keep
data class TlsConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("server_name") val serverName: String? = null,
    @SerializedName("insecure") val insecure: Boolean? = null,
    @SerializedName("alpn") val alpn: List<String>? = null,
    @SerializedName("min_version") val minVersion: String? = null, // TLS 版本限制: "1.2" 或 "1.3"
    @SerializedName("max_version") val maxVersion: String? = null,
    @SerializedName("utls") val utls: UtlsConfig? = null,
    @SerializedName("reality") val reality: RealityConfig? = null,
    @SerializedName("ech") val ech: EchConfig? = null,
    @SerializedName("ca") val ca: String? = null,
    @SerializedName("ca_path") val caPath: String? = null,
    @SerializedName("certificate") val certificate: String? = null,
    @SerializedName("certificate_path") val certificatePath: String? = null,
    @SerializedName("key") val key: String? = null,
    @SerializedName("key_path") val keyPath: String? = null
)

@Keep
data class EchConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("pq_signature_schemes_enabled") val pqSignatureSchemesEnabled: Boolean? = null,
    @SerializedName("dynamic_record_sizing_disabled") val dynamicRecordSizingDisabled: Boolean? = null,
    @SerializedName("config") val config: List<String>? = null,
    @SerializedName("key") val key: List<String>? = null
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
    @SerializedName("short_id") val shortId: String? = null,
    @SerializedName("spider_x") val spiderX: String? = null
)

@Keep
data class TransportConfig(
    @SerializedName("type") val type: String? = null,
    @SerializedName("path") val path: String? = null,
    @SerializedName("headers") val headers: Map<String, String>? = null,
    @SerializedName("service_name") val serviceName: String? = null,
    @SerializedName("host") val host: List<String>? = null,
    @SerializedName("early_data_header_name") val earlyDataHeaderName: String? = null,
    @SerializedName("max_early_data") val maxEarlyData: Int? = null
)

@Keep
data class MultiplexConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("protocol") val protocol: String? = null,
    @SerializedName("max_connections") val maxConnections: Int? = null,
    @SerializedName("min_streams") val minStreams: Int? = null,
    @SerializedName("max_streams") val maxStreams: Int? = null,
    @SerializedName("padding") val padding: Boolean? = null
)

@Keep
data class RouteConfig(
    @SerializedName("rules") val rules: List<RouteRule>? = null,
    @SerializedName("rule_set") val ruleSet: List<RuleSetConfig>? = null,
    @SerializedName("final") val finalOutbound: String? = null,
    @SerializedName("find_process") val findProcess: Boolean? = null,
    @SerializedName("auto_detect_interface") val autoDetectInterface: Boolean? = null,
    @SerializedName("default_interface") val defaultInterface: String? = null
)

@Keep
data class RouteRule(
    @SerializedName("action") val action: String? = null,
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
