package com.kunk.singbox.repository

import com.kunk.singbox.model.Outbound

object LatencyProbePolicy {
    fun shouldUseTcpFallback(outbound: Outbound): Boolean {
        val transportType = outbound.transport?.type?.trim()?.lowercase()
        val mode = outbound.transport?.mode?.trim()?.lowercase()
        return transportType == "xhttp" && mode == "packet-up"
    }

    fun shouldUseTcpFallbackAfterProtocolFailure(outbound: Outbound): Boolean {
        return shouldUseTcpFallback(outbound)
    }
}
