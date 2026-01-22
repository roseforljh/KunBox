package com.kunk.singbox.core

import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

object LocalResolverImpl : LocalDNSTransport {
    override fun raw(): Boolean {
        return false
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        try {
            val addresses = InetAddress.getAllByName(domain)
            val result = StringBuilder()
            for (address in addresses) {
                if (network == "ip4" && address is Inet4Address) {
                    if (result.isNotEmpty()) result.append("\n")
                    result.append(address.hostAddress)
                } else if (network == "ip6" && address is Inet6Address) {
                    if (result.isNotEmpty()) result.append("\n")
                    result.append(address.hostAddress)
                }
            }
            if (result.isEmpty() && addresses.isNotEmpty()) {
                // No matching IP version found, but domain exists.
                // Maybe we should return empty success?
                // Or maybe we don't filter strict if network is just "ip"?
                // Sing-box usually passes "ip4" or "ip6".
            }
            ctx.success(result.toString())
        } catch (e: Exception) {
            // RCode 3 = NXDOMAIN, 2 = SERVFAIL
            ctx.errorCode(2)
        }
    }

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        // Not used if raw() returns false
    }
}
