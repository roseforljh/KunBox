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
    val fingerprint: String?
)

data class NodeLinkParserTuicTransportOptions(
    val congestionControl: String?,
    val udpRelayMode: String,
    val zeroRtt: Boolean
)
