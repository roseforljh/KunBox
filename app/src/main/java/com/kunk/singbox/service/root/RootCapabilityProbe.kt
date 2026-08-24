package com.kunk.singbox.service.root

import android.os.Bundle
import android.os.Process

data class RootCapabilityReport(
    val rootUid: Boolean,
    val capNetAdmin: Boolean,
    val capNetRaw: Boolean,
    val ipCommand: Boolean,
    val iptables: Boolean,
    val ip6tables: Boolean,
    val tproxyIpv4: Boolean,
    val tproxyIpv6: Boolean,
    val redirectIpv4: Boolean,
    val redirectIpv6: Boolean,
    val ownerMatch: Boolean,
    val selinuxDomain: String,
    val error: String = ""
) {
    val supported: Boolean get() =
        rootUid && capNetAdmin && capNetRaw && ipCommand && iptables && tproxyIpv4 && redirectIpv4 && ownerMatch

    fun toBundle(): Bundle = Bundle().apply {
        putBoolean("root_uid", rootUid)
        putBoolean("cap_net_admin", capNetAdmin)
        putBoolean("cap_net_raw", capNetRaw)
        putBoolean("ip_command", ipCommand)
        putBoolean("iptables", iptables)
        putBoolean("ip6tables", ip6tables)
        putBoolean("tproxy_ipv4", tproxyIpv4)
        putBoolean("tproxy_ipv6", tproxyIpv6)
        putBoolean("redirect_ipv4", redirectIpv4)
        putBoolean("redirect_ipv6", redirectIpv6)
        putBoolean("owner_match", ownerMatch)
        putString("selinux_domain", selinuxDomain)
        putString("error", error)
        putBoolean("supported", supported)
    }

    companion object {
        fun fromBundle(bundle: Bundle?): RootCapabilityReport {
            if (bundle == null) {
                return RootCapabilityReport(
                    rootUid = false,
                    capNetAdmin = false,
                    capNetRaw = false,
                    ipCommand = false,
                    iptables = false,
                    ip6tables = false,
                    tproxyIpv4 = false,
                    tproxyIpv6 = false,
                    redirectIpv4 = false,
                    redirectIpv6 = false,
                    ownerMatch = false,
                    selinuxDomain = "unknown",
                    error = "Root capability report unavailable"
                )
            }
            return RootCapabilityReport(
                rootUid = bundle.getBoolean("root_uid"),
                capNetAdmin = bundle.getBoolean("cap_net_admin"),
                capNetRaw = bundle.getBoolean("cap_net_raw"),
                ipCommand = bundle.getBoolean("ip_command"),
                iptables = bundle.getBoolean("iptables"),
                ip6tables = bundle.getBoolean("ip6tables"),
                tproxyIpv4 = bundle.getBoolean("tproxy_ipv4"),
                tproxyIpv6 = bundle.getBoolean("tproxy_ipv6"),
                redirectIpv4 = bundle.getBoolean("redirect_ipv4"),
                redirectIpv6 = bundle.getBoolean("redirect_ipv6"),
                ownerMatch = bundle.getBoolean("owner_match"),
                selinuxDomain = bundle.getString("selinux_domain").orEmpty(),
                error = bundle.getString("error").orEmpty()
            )
        }
    }
}

class RootCapabilityProbe(
    private val executor: RootCommandExecutor = ProcessRootCommandExecutor()
) {
    companion object {
        private const val CAP_NET_ADMIN = 12
        private const val CAP_NET_RAW = 13
    }

    private val probeChain4 = "KBX_P4_${Process.myPid()}"
    private val probeChain6 = "KBX_P6_${Process.myPid()}"

    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod")
    fun probe(): RootCapabilityReport {
        val rootUid = Process.myUid() == 0
        val status = runCatching { executor.execute(listOf("cat", "/proc/self/status")) }
            .getOrDefault(RootCommandResult(-1, ""))
        val effectiveCapabilities = status.output.lineSequence()
            .firstOrNull { it.startsWith("CapEff:") }
            ?.substringAfter(':')
            ?.trim()
            ?.toULongOrNull(16)
            ?: 0uL
        val selinux = runCatching { executor.execute(listOf("cat", "/proc/self/attr/current")) }
            .getOrDefault(RootCommandResult(-1, "unknown"))
            .output
        val iptables = succeeds("iptables", "-V")
        val ip6tables = succeeds("ip6tables", "-V")
        val ipCommand = succeeds("ip", "rule", "show")
        val ownerMatch = iptables && probeOwnerMatch()
        val tproxyIpv4 = iptables && probeTproxy("iptables", probeChain4, RootNetfilterPlanner.IPV4_MARK)
        val tproxyIpv6 = ip6tables && probeTproxy("ip6tables", probeChain6, RootNetfilterPlanner.IPV6_MARK)
        val redirectIpv4 = iptables && probeRedirect("iptables", probeChain4)
        val redirectIpv6 = ip6tables && probeRedirect("ip6tables", probeChain6)
        val capNetAdmin = hasCapability(effectiveCapabilities, CAP_NET_ADMIN)
        val capNetRaw = hasCapability(effectiveCapabilities, CAP_NET_RAW)
        val error = buildList {
            if (!rootUid) add("RootService UID is not 0")
            if (!capNetAdmin) add("CAP_NET_ADMIN missing")
            if (!capNetRaw) add("CAP_NET_RAW missing")
            if (!ipCommand) add("ip command unavailable")
            if (!iptables) add("iptables unavailable")
            if (!ownerMatch) add("owner match unavailable")
            if (!tproxyIpv4) add("IPv4 TPROXY unavailable")
            if (!redirectIpv4) add("IPv4 REDIRECT unavailable")
        }.joinToString("; ")
        return RootCapabilityReport(
            rootUid = rootUid,
            capNetAdmin = capNetAdmin,
            capNetRaw = capNetRaw,
            ipCommand = ipCommand,
            iptables = iptables,
            ip6tables = ip6tables,
            tproxyIpv4 = tproxyIpv4,
            tproxyIpv6 = tproxyIpv6,
            redirectIpv4 = redirectIpv4,
            redirectIpv6 = redirectIpv6,
            ownerMatch = ownerMatch,
            selinuxDomain = selinux,
            error = error
        )
    }

    private fun probeOwnerMatch(): Boolean = withProbeChain("iptables", probeChain4) {
        succeeds(
            "iptables", "-t", "mangle", "-A", probeChain4,
            "-m", "owner", "--uid-owner", Process.myUid().toString(), "-j", "RETURN"
        )
    }

    private fun probeTproxy(binary: String, chain: String, mark: String): Boolean = withProbeChain(binary, chain) {
        listOf("tcp", "udp").all { protocol ->
            succeeds(
                binary, "-t", "mangle", "-A", chain,
                "-p", protocol, "-j", "TPROXY", "--on-port", "1536", "--tproxy-mark", mark
            )
        }
    }

    private fun probeRedirect(binary: String, chain: String): Boolean = withProbeChain(binary, chain, "nat") {
        succeeds(
            binary, "-t", "nat", "-A", chain,
            "-p", "tcp", "-j", "REDIRECT", "--to-ports", "1536"
        )
    }

    private fun withProbeChain(
        binary: String,
        chain: String,
        table: String = "mangle",
        check: () -> Boolean
    ): Boolean {
        cleanupProbeChain(binary, chain, table)
        if (!succeeds(binary, "-t", table, "-N", chain)) return false
        return try {
            check()
        } finally {
            cleanupProbeChain(binary, chain, table)
        }
    }

    private fun cleanupProbeChain(binary: String, chain: String, table: String = "mangle") {
        runCatching { executor.execute(listOf(binary, "-t", table, "-F", chain)) }
        runCatching { executor.execute(listOf(binary, "-t", table, "-X", chain)) }
    }

    private fun succeeds(vararg arguments: String): Boolean =
        runCatching { executor.execute(arguments.toList()).success }.getOrDefault(false)

    private fun hasCapability(value: ULong, bit: Int): Boolean = value and (1uL shl bit) != 0uL
}
