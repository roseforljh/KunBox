package com.kunk.singbox.model

val IpVersionMode.includesIpv4: Boolean
    get() = this != IpVersionMode.IPV6_ONLY

val IpVersionMode.includesIpv6: Boolean
    get() = this != IpVersionMode.IPV4_ONLY

fun IpVersionMode.autoDnsStrategy(): String {
    return when (this) {
        IpVersionMode.IPV4_ONLY -> "ipv4_only"
        IpVersionMode.DUAL_STACK -> "prefer_ipv4"
        IpVersionMode.PREFER_IPV6 -> "prefer_ipv6"
        IpVersionMode.IPV6_ONLY -> "ipv6_only"
    }
}

fun IpVersionMode.resolveDnsStrategy(strategy: DnsStrategy): String {
    return when (this) {
        IpVersionMode.IPV4_ONLY -> "ipv4_only"
        IpVersionMode.IPV6_ONLY -> "ipv6_only"
        IpVersionMode.PREFER_IPV6 -> when (strategy) {
            DnsStrategy.AUTO -> "prefer_ipv6"
            DnsStrategy.PREFER_IPV4 -> "prefer_ipv4"
            DnsStrategy.PREFER_IPV6 -> "prefer_ipv6"
            DnsStrategy.ONLY_IPV4 -> "ipv4_only"
            DnsStrategy.ONLY_IPV6 -> "ipv6_only"
        }
        IpVersionMode.DUAL_STACK -> when (strategy) {
            DnsStrategy.AUTO -> "prefer_ipv4"
            DnsStrategy.PREFER_IPV4 -> "prefer_ipv4"
            DnsStrategy.PREFER_IPV6 -> "prefer_ipv6"
            DnsStrategy.ONLY_IPV4 -> "ipv4_only"
            DnsStrategy.ONLY_IPV6 -> "ipv6_only"
        }
    }
}
