package com.kunk.singbox.repository.config

import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.Inbound
import com.kunk.singbox.model.TunStack
import com.kunk.singbox.model.TrafficCaptureMode
import com.kunk.singbox.model.IpVersionMode
import com.kunk.singbox.service.tun.VpnTunAddressPlanner

object InboundBuilder {
    const val ROOT_REDIRECT_PORT_IPV4 = 1536
    const val ROOT_REDIRECT_PORT_IPV6 = 1537
    const val ROOT_TPROXY_PORT_IPV4 = 1538
    const val ROOT_TPROXY_PORT_IPV6 = 1539
    const val ROOT_REDIRECT_TAG_IPV4 = "redirect-in-v4"
    const val ROOT_REDIRECT_TAG_IPV6 = "redirect-in-v6"
    const val ROOT_TPROXY_TAG_IPV4 = "tproxy-in-v4"
    const val ROOT_TPROXY_TAG_IPV6 = "tproxy-in-v6"

    fun build(settings: AppSettings, effectiveTunStack: TunStack): List<Inbound> {
        val inbounds = mutableListOf<Inbound>()

        if (settings.proxyPort > 0) {
            inbounds += mixedInbound(
                listen = if (settings.allowLan) "0.0.0.0" else "127.0.0.1",
                port = settings.proxyPort
            )
        }

        when (settings.resolvedTrafficCaptureMode()) {
            TrafficCaptureMode.VPN -> inbounds += tunInbound(settings, effectiveTunStack)
            TrafficCaptureMode.ROOT_TRANSPARENT -> inbounds += rootInbounds(settings.ipVersionMode)
            TrafficCaptureMode.PROXY_ONLY -> if (settings.proxyPort <= 0) {
                inbounds += mixedInbound("127.0.0.1", 2080)
            }
        }

        return inbounds
    }

    private fun tunInbound(settings: AppSettings, effectiveTunStack: TunStack): Inbound = Inbound(
        type = "tun",
        tag = "tun-in",
        addressRaw = VpnTunAddressPlanner.build(settings.ipVersionMode).cidrAddresses,
        mtu = settings.tunMtu,
        autoRoute = settings.autoRoute,
        strictRoute = settings.strictRoute,
        stack = effectiveTunStack.name.lowercase(),
        gso = null
    )

    private fun rootInbounds(ipVersionMode: IpVersionMode): List<Inbound> = buildList {
        if (ipVersionMode != IpVersionMode.IPV6_ONLY) {
            add(redirectInbound(ROOT_REDIRECT_TAG_IPV4, "0.0.0.0", ROOT_REDIRECT_PORT_IPV4))
            add(tproxyInbound(ROOT_TPROXY_TAG_IPV4, "0.0.0.0", ROOT_TPROXY_PORT_IPV4))
        }
        if (ipVersionMode != IpVersionMode.IPV4_ONLY) {
            add(redirectInbound(ROOT_REDIRECT_TAG_IPV6, "::", ROOT_REDIRECT_PORT_IPV6))
            add(tproxyInbound(ROOT_TPROXY_TAG_IPV6, "::", ROOT_TPROXY_PORT_IPV6))
        }
    }

    private fun tproxyInbound(tag: String, listen: String, port: Int): Inbound = Inbound(
        type = "tproxy",
        tag = tag,
        listen = listen,
        listenPort = port,
        network = "udp",
        udpTimeout = "5m",
        reuseAddr = true
    )

    private fun redirectInbound(tag: String, listen: String, port: Int): Inbound = Inbound(
        type = "redirect",
        tag = tag,
        listen = listen,
        listenPort = port,
        reuseAddr = true
    )

    private fun mixedInbound(listen: String, port: Int): Inbound = Inbound(
        type = "mixed",
        tag = "mixed-in",
        listen = listen,
        listenPort = port,
        reuseAddr = true
    )
}
