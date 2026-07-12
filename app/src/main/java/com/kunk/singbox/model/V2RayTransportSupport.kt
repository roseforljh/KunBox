package com.kunk.singbox.model

internal val V2RAY_TRANSPORT_PROTOCOLS = setOf("vmess", "vless", "trojan")
internal val V2RAY_TRANSPORT_TYPES = setOf(
    "tcp", "http", "h2", "ws", "quic", "grpc", "httpupgrade", "xhttp", "splithttp"
)
internal val V2RAY_XHTTP_TRANSPORT_TYPES = setOf("xhttp", "splithttp")
