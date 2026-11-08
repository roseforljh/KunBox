package com.kunk.singbox.utils.parser

data class NodeLinkParserWebSocketPathConfig(
    val path: String,
    val maxEarlyData: Long?,
    val earlyDataHeaderName: String?
)

data class NodeLinkParserTuicCredentials(
    val uuid: String,
    val password: String
)

data class NodeLinkParserTuicTlsOptions(
    val disableSni: Boolean,
    val serverName: String?,
    val insecure: Boolean,
    val alpn: List<String>?,
    val fingerprint: String?,
    val ca: List<String>? = null,
    val caPath: String? = null,
    val certificate: List<String>? = null,
    val certificatePath: String? = null,
    val key: List<String>? = null,
    val keyPath: String? = null
)

data class NodeLinkParserTuicTransportOptions(
    val congestionControl: String?,
    val udpRelayMode: String,
    val zeroRtt: Boolean
)
