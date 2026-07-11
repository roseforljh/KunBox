package com.kunk.singbox.repository.config

import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.Inbound
import com.kunk.singbox.model.TunStack
import com.kunk.singbox.service.tun.VpnTunAddressPlanner

object InboundBuilder {

    fun build(settings: AppSettings, effectiveTunStack: TunStack): List<Inbound> {
        val inbounds = mutableListOf<Inbound>()

        if (settings.proxyPort > 0) {
            inbounds.add(
                Inbound(
                    type = "mixed",
                    tag = "mixed-in",
                    listen = if (settings.allowLan) "0.0.0.0" else "127.0.0.1",
                    listenPort = settings.proxyPort,
                    reuseAddr = true
                )
            )
        }

        if (settings.tunEnabled) {
            inbounds.add(
                Inbound(
                    type = "tun",
                    tag = "tun-in",
                    addressRaw = VpnTunAddressPlanner.build(settings.ipVersionMode).cidrAddresses,
                    mtu = settings.tunMtu,
                    autoRoute = settings.autoRoute,
                    strictRoute = settings.strictRoute,
                    stack = effectiveTunStack.name.lowercase(),
                    gso = null
                )
            )
        } else if (settings.proxyPort <= 0) {
            inbounds.add(
                Inbound(
                    type = "mixed",
                    tag = "mixed-in",
                    listen = "127.0.0.1",
                    listenPort = 2080,
                    reuseAddr = true
                )
            )
        }

        return inbounds
    }
}
