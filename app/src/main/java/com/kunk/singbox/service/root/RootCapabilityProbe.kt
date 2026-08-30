package com.kunk.singbox.service.root

import android.os.Bundle
import android.os.Process
import java.io.File

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
    val routeProtocol: Boolean,
    val selinuxDomain: String,
    val error: String = ""
) {
    val supported: Boolean get() =
        rootUid && capNetAdmin && capNetRaw && ipCommand && iptables && tproxyIpv4 && redirectIpv4 &&
            ownerMatch

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
        putBoolean("route_protocol", routeProtocol)
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
                    routeProtocol = false,
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
                routeProtocol = bundle.getBoolean("route_protocol"),
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

    @Suppress("CognitiveComplexMethod")
    fun probe(): RootCapabilityReport {
        val rootUid = Process.myUid() == 0
        val effectiveCapabilities = readProcFile("/proc/self/status").lineSequence()
            .firstOrNull { it.startsWith("CapEff:") }
            ?.substringAfter(':')
            ?.trim()
            ?.toULongOrNull(16)
            ?: 0uL
        val selinux = readProcFile("/proc/self/attr/current").ifBlank { "unknown" }
        val checks = runCatching {
            executor.execute(listOf("/system/bin/sh", "-c", buildProbeScript())).output
        }.getOrDefault("").lineSequence().mapNotNull { line ->
            val key = line.substringBefore('=', missingDelimiterValue = "")
            val value = line.substringAfter('=', missingDelimiterValue = "")
            if (key.isBlank() || value != "0" && value != "1") null else key to (value == "1")
        }.toMap()
        val iptables = checks["iptables"] == true
        val ip6tables = checks["ip6tables"] == true
        val ipCommand = checks["ip_command"] == true
        val ownerMatch = checks["owner_match"] == true
        val tproxyIpv4 = checks["tproxy_ipv4"] == true
        val tproxyIpv6 = checks["tproxy_ipv6"] == true
        val redirectIpv4 = checks["redirect_ipv4"] == true
        val redirectIpv6 = checks["redirect_ipv6"] == true
        val routeProtocol = checks["route_protocol"] == true
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
            routeProtocol = routeProtocol,
            selinuxDomain = selinux,
            error = error
        )
    }

    @Suppress("LongMethod")
    private fun buildProbeScript(): String = """
        xtables_probe_run() {
            xt_binary="${'$'}1"
            shift
            xt_attempt=1
            while [ "${'$'}xt_attempt" -le $XTABLES_MAX_ATTEMPTS ]; do
                xt_error="${'$'}{TMPDIR:-/data/local/tmp}/kunbox-probe-xtables.${'$'}${'$'}"
                command "${'$'}xt_binary" -w $XTABLES_WAIT_SECONDS "${'$'}@" 2>"${'$'}xt_error"
                xt_status="${'$'}?"
                if [ "${'$'}xt_status" -eq 0 ]; then
                    rm -f "${'$'}xt_error"
                    return 0
                fi
                if { grep -F -q 'xtables.lock' "${'$'}xt_error" || \
                    grep -F -q 'holding the xtables lock' "${'$'}xt_error"; } && \
                    [ "${'$'}xt_attempt" -lt $XTABLES_MAX_ATTEMPTS ]; then
                    xt_attempt="${'$'}((xt_attempt + 1))"
                    continue
                fi
                rm -f "${'$'}xt_error"
                return "${'$'}xt_status"
            done
        }
        iptables() { xtables_probe_run iptables "${'$'}@"; }
        ip6tables() { xtables_probe_run ip6tables "${'$'}@"; }
        cleanup_chain() {
            "${'$'}1" -t "${'$'}2" -F "${'$'}3" >/dev/null 2>&1 || :
            "${'$'}1" -t "${'$'}2" -X "${'$'}3" >/dev/null 2>&1 || :
        }
        probe_owner() {
            cleanup_chain "${'$'}1" mangle "${'$'}2"
            "${'$'}1" -t mangle -N "${'$'}2" >/dev/null 2>&1 || return 1
            "${'$'}1" -t mangle -A "${'$'}2" -m owner --uid-owner ${Process.myUid()} -j RETURN \
                >/dev/null 2>&1
            result=${'$'}?
            cleanup_chain "${'$'}1" mangle "${'$'}2"
            return "${'$'}result"
        }
        probe_tproxy() {
            cleanup_chain "${'$'}1" mangle "${'$'}2"
            "${'$'}1" -t mangle -N "${'$'}2" >/dev/null 2>&1 || return 1
            result=0
            "${'$'}1" -t mangle -A "${'$'}2" -p tcp -j TPROXY --on-port 1536 --tproxy-mark "${'$'}3" \
                >/dev/null 2>&1 || result=1
            "${'$'}1" -t mangle -A "${'$'}2" -p udp -j TPROXY --on-port 1536 --tproxy-mark "${'$'}3" \
                >/dev/null 2>&1 || result=1
            cleanup_chain "${'$'}1" mangle "${'$'}2"
            return "${'$'}result"
        }
        probe_redirect() {
            cleanup_chain "${'$'}1" nat "${'$'}2"
            "${'$'}1" -t nat -N "${'$'}2" >/dev/null 2>&1 || return 1
            "${'$'}1" -t nat -A "${'$'}2" -p tcp -j REDIRECT --to-ports 1536 >/dev/null 2>&1
            result=${'$'}?
            cleanup_chain "${'$'}1" nat "${'$'}2"
            return "${'$'}result"
        }
        IPTABLES=0
        IP6TABLES=0
        IP_COMMAND=0
        OWNER_MATCH=0
        TPROXY_IPV4=0
        TPROXY_IPV6=0
        REDIRECT_IPV4=0
        REDIRECT_IPV6=0
        iptables -V >/dev/null 2>&1 && IPTABLES=1
        ip6tables -V >/dev/null 2>&1 && IP6TABLES=1
        ip rule show >/dev/null 2>&1 && IP_COMMAND=1
        [ "${'$'}IPTABLES" -eq 1 ] && probe_owner iptables ${shellQuote(probeChain4)} && OWNER_MATCH=1
        [ "${'$'}IPTABLES" -eq 1 ] && \
            probe_tproxy iptables ${shellQuote(probeChain4)} ${RootNetfilterPlanner.IPV4_MARK} && TPROXY_IPV4=1
        [ "${'$'}IP6TABLES" -eq 1 ] && \
            probe_tproxy ip6tables ${shellQuote(probeChain6)} ${RootNetfilterPlanner.IPV6_MARK} && TPROXY_IPV6=1
        [ "${'$'}IPTABLES" -eq 1 ] && probe_redirect iptables ${shellQuote(probeChain4)} && REDIRECT_IPV4=1
        [ "${'$'}IP6TABLES" -eq 1 ] && \
            probe_redirect ip6tables ${shellQuote(probeChain6)} && REDIRECT_IPV6=1
        printf 'iptables=%s\n' "${'$'}IPTABLES"
        printf 'ip6tables=%s\n' "${'$'}IP6TABLES"
        printf 'ip_command=%s\n' "${'$'}IP_COMMAND"
        printf 'owner_match=%s\n' "${'$'}OWNER_MATCH"
        printf 'tproxy_ipv4=%s\n' "${'$'}TPROXY_IPV4"
        printf 'tproxy_ipv6=%s\n' "${'$'}TPROXY_IPV6"
        printf 'redirect_ipv4=%s\n' "${'$'}REDIRECT_IPV4"
        printf 'redirect_ipv6=%s\n' "${'$'}REDIRECT_IPV6"
    """.trimIndent()

    private fun readProcFile(path: String): String = runCatching { File(path).readText().trim() }.getOrDefault("")

    private fun hasCapability(value: ULong, bit: Int): Boolean = value and (1uL shl bit) != 0uL
}
