package com.kunk.singbox.utils.parser

import com.kunk.singbox.model.EchConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import com.google.gson.Gson

/**
 */

@Suppress("TooManyFunctions")
abstract class NodeLinkParserBase(protected val gson: Gson) {
    // Virtual declarations keep split class logic callable across files.
    protected abstract fun firstParam(params: Map<String, String>, vararg keys: String): String?

    protected abstract fun hasRequiredLinkFields(protocol: String, server: String?, credential: String?, port: Int): Boolean

    protected abstract fun parseBooleanFlag(value: String?): Boolean?

    protected abstract fun parseHostList(value: String?): List<String>?

    protected abstract fun parseSingleHost(value: String?): List<String>?

    protected abstract fun parseWireGuardLocalAddress(params: Map<String, String>): List<String>?

    protected abstract fun parseEchConfig(params: Map<String, String>): EchConfig?

    protected abstract fun parseWebSocketPathConfig(rawPath: String?): NodeLinkParserWebSocketPathConfig

    protected abstract fun parseQueryParams(query: String?): Map<String, String>

    protected abstract fun parseTuicCredentials(userInfo: String, params: Map<String, String>): NodeLinkParserTuicCredentials

    protected abstract fun buildTuicTlsOptions(server: String?, params: Map<String, String>): NodeLinkParserTuicTlsOptions

    protected abstract fun buildTuicTransportOptions(params: Map<String, String>): NodeLinkParserTuicTransportOptions

    protected abstract fun isIpLiteral(value: String?): Boolean

    protected abstract fun defaultTlsServerName(
        explicitServerName: String?,
        primaryFallback: String? = null,
        server: String?
    ): String?

    protected abstract fun sanitizeUri(link: String): String

    protected abstract fun normalizeInputLink(link: String): String

    abstract fun parse(link: String): Outbound?

    protected abstract fun parseShadowsocksLink(link: String): Outbound?

    protected abstract fun tryDecodeBase64(content: String): String?

    protected abstract fun padBase64(content: String): String

    protected abstract fun decodeWithJvmBase64(content: String, urlSafe: Boolean): ByteArray?

    protected abstract fun parseHostPort(hostPort: String): Pair<String, Int>

    protected abstract fun parseVMessLink(link: String): Outbound?

    protected abstract fun parseVLessLink(link: String): Outbound?

    protected abstract fun parseTrojanLink(link: String): Outbound?

    protected abstract fun buildTrojanTransport(
        params: Map<String, String>,
        hostParam: String?
    ): TransportConfig?

    protected abstract fun parseHysteria2Link(link: String): Outbound?

    protected abstract fun parseBooleanQueryParam(value: String?): Boolean?

    protected abstract fun parseCsvQueryParam(value: String?): List<String>?

    protected abstract fun parseServerPorts(value: String?): List<String>?

    protected abstract fun parseHysteriaLink(link: String): Outbound?

    protected abstract fun parseAnyTLSLink(link: String): Outbound?

    protected abstract fun parseNaiveLink(link: String): Outbound?

    protected abstract fun parseNaiveExtraHeaders(params: Map<String, String>): Map<String, String>?

    protected abstract fun parseTuicLink(link: String): Outbound?

    protected abstract fun parseWireGuardLink(link: String): Outbound?

    protected abstract fun parseSSHLink(link: String): Outbound?

    protected abstract fun parseHttpLink(link: String, useTls: Boolean): Outbound?

    protected abstract fun parseHttpCredentials(uri: java.net.URI): Pair<String?, String?>

    protected abstract fun buildHttpTlsConfig(useTls: Boolean, server: String): TlsConfig?

    protected abstract fun looksLikeHttpProxyUri(uri: java.net.URI): Boolean

    protected abstract fun parseSocks5Link(link: String): Outbound?
}
