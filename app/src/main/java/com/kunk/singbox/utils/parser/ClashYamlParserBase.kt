package com.kunk.singbox.utils.parser

import com.kunk.singbox.model.MultiplexConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import com.kunk.singbox.model.UtlsConfig
import java.net.URI

/**
 */

@Suppress("TooManyFunctions")
abstract class ClashYamlParserBase : SubscriptionParser {
    // Virtual declarations keep split class logic callable across files.
    abstract override fun canParse(content: String): Boolean

    abstract override fun parse(content: String): SingBoxConfig?

    protected abstract fun sanitizeUrlTestUrl(rawUrl: String?): String

    protected abstract fun isSafeUrlTestUri(uri: URI): Boolean

    protected abstract fun isUnsafeLiteralAddress(host: String): Boolean

    protected abstract fun looksLikeIpLiteral(host: String): Boolean

    protected abstract fun normalizeProxyGroupRefs(rawProxies: Any?, knownOutboundTags: Set<String>): List<String>

    protected abstract fun normalizeProxyGroupRef(ref: String, knownOutboundTags: Set<String>): String?

    protected abstract fun parseProxy(proxyMap: Map<*, *>, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): List<Outbound>?

    protected abstract fun parseVLess(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): Outbound?

    protected abstract fun extractXhttpExtraEncryption(map: Map<*, *>): String?

    protected abstract fun parseVMess(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): Outbound?

    protected abstract fun parseShadowsocksWithPlugin(
        map: Map<*, *>,
        name: String,
        server: String?,
        port: Int?,
        globalFingerprint: String?
    ): List<Outbound>?

    protected abstract fun parseTrojan(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): Outbound?

    protected abstract fun parseTrojanTransport(
        network: String?,
        map: Map<*, *>,
        sni: String,
        fingerprint: String?
    ): TransportConfig?

    protected abstract fun parseHysteria2(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): Outbound?

    protected abstract fun parseTuic(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): Outbound?

    protected abstract fun parseSSH(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound?

    protected abstract fun parseWireGuard(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound?

    protected abstract fun parseAnyTLS(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): Outbound?

    protected abstract fun parseNaive(
        map: Map<*, *>,
        name: String,
        server: String?,
        port: Int?,
        globalFingerprint: String? = null,
        globalTlsMinVersion: String? = null
    ): Outbound?

    protected abstract fun parseHysteria(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): Outbound?

    protected abstract fun parseHttp(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): Outbound?

    protected abstract fun parseSocks(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound?

    protected abstract fun parseShadowTLS(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String? = null): Outbound?

    protected abstract fun parseSmux(map: Map<*, *>): MultiplexConfig?

    protected abstract fun buildTlsConfig(
        map: Map<*, *>,
        enabled: Boolean = true,
        serverName: String? = null,
        insecure: Boolean? = null,
        alpn: List<String>? = null,
        minVersion: String? = null,
        utls: UtlsConfig? = null,
        reality: com.kunk.singbox.model.RealityConfig? = null
    ): TlsConfig

    protected abstract fun firstNonBlankString(map: Map<*, *>, vararg keys: String): String?

    protected abstract fun asNestedMap(v: Any?): Map<*, *>?

    protected abstract fun getUserAgent(fingerprint: String?): String

    protected abstract fun asString(v: Any?): String?

    protected abstract fun asInt(v: Any?): Int?

    protected abstract fun asBool(v: Any?): Boolean?

    protected abstract fun asStringList(v: Any?): List<String>?

    protected abstract fun buildWsOrHttpUpgradeTransport(
        wsOpts: Map<*, *>?,
        path: String,
        headers: Map<String, String>,
        host: String?
    ): TransportConfig

    protected abstract fun Map<String, String>.withoutHostHeader(): Map<String, String>
}
