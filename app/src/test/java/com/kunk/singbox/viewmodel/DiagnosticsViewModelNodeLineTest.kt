package com.kunk.singbox.viewmodel

import com.kunk.singbox.model.DnsConfig
import com.kunk.singbox.model.DnsServer
import com.kunk.singbox.model.Inbound
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.RouteConfig
import com.kunk.singbox.model.RouteRule
import com.kunk.singbox.model.SingBoxConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsViewModelNodeLineTest {

    @Test
    fun resolveNodeLineTarget_prefersRuntimeActiveLabelNode() {
        val runtimeNode = createNode(id = "runtime", name = "runtime-node")
        val storedNode = createNode(id = "stored", name = "stored-node")
        val fallbackNode = createNode(id = "fallback", name = "fallback-node")

        val result = resolveNodeLineTarget(runtimeNode, storedNode, fallbackNode)

        assertEquals("runtime", result?.node?.id)
        assertEquals("运行态 activeLabel", result?.sourceLabel)
    }

    @Test
    fun resolveNodeLineTarget_usesStoredActiveLabelWhenRuntimeMissing() {
        val storedNode = createNode(id = "stored", name = "stored-node")
        val fallbackNode = createNode(id = "fallback", name = "fallback-node")

        val result = resolveNodeLineTarget(
            activeLabelNode = null,
            storedActiveLabelNode = storedNode,
            fallbackNode = fallbackNode
        )

        assertEquals("stored", result?.node?.id)
        assertEquals("状态存储 activeLabel", result?.sourceLabel)
    }

    @Test
    fun resolveNodeLineTarget_fallsBackToSelectedNode() {
        val fallbackNode = createNode(id = "fallback", name = "fallback-node")

        val result = resolveNodeLineTarget(
            activeLabelNode = null,
            storedActiveLabelNode = null,
            fallbackNode = fallbackNode
        )

        assertEquals("fallback", result?.node?.id)
        assertEquals("当前选中节点", result?.sourceLabel)
    }

    @Test
    fun resolveNodeLineTarget_returnsNullWhenNoSignalAvailable() {
        val result = resolveNodeLineTarget(
            activeLabelNode = null,
            storedActiveLabelNode = null,
            fallbackNode = null
        )

        assertNull(result)
    }

    @Test
    fun buildDnsQuerySuccessMessage_marksSystemDnsAsNonNodeSignal() {
        val message = buildDnsQuerySuccessMessage(
            host = "www.google.com",
            addresses = listOf("1.1.1.1", "2606:4700:4700::1111")
        )

        assertTrue(message.contains("Domain: www.google.com"))
        assertTrue(message.contains("Result:\n1.1.1.1\n2606:4700:4700::1111"))
        assertTrue(message.contains("System DNS only"))
        assertTrue(message.contains("KunBox app is excluded from VPN"))
        assertTrue(message.contains("does not represent current node DNS"))
    }

    @Test
    fun buildDnsQueryFailureMessage_marksSystemDnsAsNonNodeSignal() {
        val message = buildDnsQueryFailureMessage(
            host = "www.google.com",
            errorMessage = "timeout"
        )

        assertTrue(message.contains("Domain: www.google.com"))
        assertTrue(message.contains("Failed: timeout"))
        assertTrue(message.contains("System DNS only"))
        assertTrue(message.contains("does not represent current node DNS"))
    }

    @Test
    fun buildDnsLeakCheckReport_reportsLeakWhenHijackDnsRuleMissing() {
        val report = buildDnsLeakCheckReport(
            coreActive = true,
            runConfig = createConfig(routeRules = emptyList())
        )

        assertTrue(report.contains("DNS 静态风险: 存在"))
        assertTrue(report.contains("运行配置缺少覆盖 tun-in:53 或 protocol=dns 的 DNS 劫持规则"))
    }

    @Test
    fun buildDnsLeakCheckReport_reportsLeakWhenSystemDnsServerExists() {
        val report = buildDnsLeakCheckReport(
            coreActive = true,
            runConfig = createConfig(dnsServers = listOf(DnsServer(tag = "local", type = "local")))
        )

        assertTrue(report.contains("DNS 静态风险: 存在"))
        assertTrue(report.contains("DNS 服务器 local 使用系统 DNS 类型 local"))
    }

    @Test
    fun buildDnsLeakCheckReport_reportsLeakWhenFinalDnsServerMissing() {
        val report = buildDnsLeakCheckReport(
            coreActive = true,
            runConfig = createConfig(dnsServers = listOf(DnsServer(tag = "remote", type = "https")))
        )

        assertTrue(report.contains("DNS 静态风险: 存在"))
        assertTrue(report.contains("DNS final 指向 local，但 servers 中不存在该 tag"))
    }

    @Test
    fun buildDnsLeakCheckReport_reportsLeakWhenTunInboundMissing() {
        val report = buildDnsLeakCheckReport(
            coreActive = true,
            runConfig = createConfig(inbounds = emptyList())
        )

        assertTrue(report.contains("DNS 静态风险: 存在"))
        assertTrue(report.contains("运行配置缺少 TUN 入站"))
    }

    @Test
    fun buildDnsLeakCheckReport_reportsLeakWhenHijackDnsRuleDoesNotCoverTunDns() {
        val report = buildDnsLeakCheckReport(
            coreActive = true,
            runConfig = createConfig(
                routeRules = listOf(
                    RouteRule(inbound = listOf("mixed-in"), action = "hijack-dns")
                )
            )
        )

        assertTrue(report.contains("DNS 静态风险: 存在"))
        assertTrue(report.contains("运行配置缺少覆盖 tun-in:53 或 protocol=dns 的 DNS 劫持规则"))
    }

    @Test
    fun buildDnsLeakCheckReport_reportsLeakWhenUdpDirectDnsServerExists() {
        val report = buildDnsLeakCheckReport(
            coreActive = true,
            runConfig = createConfig(dnsServers = listOf(DnsServer(tag = "local", type = "udp", server = "223.5.5.5")))
        )

        assertTrue(report.contains("DNS 静态风险: 存在"))
        assertTrue(report.contains("DNS 服务器 local 使用明文直连 DNS udp://223.5.5.5"))
    }

    @Test
    fun buildDnsLeakCheckReport_reportsLeakWhenFinalDnsServerIsUnsafe() {
        val report = buildDnsLeakCheckReport(
            coreActive = true,
            runConfig = createConfig(
                dnsServers = listOf(
                    DnsServer(tag = "local", type = "https"),
                    DnsServer(tag = "remote", type = "udp", server = "1.1.1.1")
                ),
                finalServer = "remote"
            )
        )

        assertTrue(report.contains("DNS 静态风险: 存在"))
        assertTrue(report.contains("DNS final 指向不安全服务器 remote"))
    }

    @Test
    fun buildDnsLeakCheckReport_reportsLeakWhenDnsDetourMissing() {
        val report = buildDnsLeakCheckReport(
            coreActive = true,
            runConfig = createConfig(
                dnsServers = listOf(
                    DnsServer(tag = "local", type = "udp", server = "223.5.5.5", detour = "proxy-node")
                )
            )
        )

        assertTrue(report.contains("DNS 静态风险: 存在"))
        assertTrue(report.contains("DNS 服务器 local detour 指向不存在的出站 proxy-node"))
    }

    @Test
    fun buildDnsLeakCheckReport_reportsLeakWhenDnsDetourIsDirect() {
        val report = buildDnsLeakCheckReport(
            coreActive = true,
            runConfig = createConfig(
                dnsServers = listOf(
                    DnsServer(tag = "local", type = "udp", server = "223.5.5.5", detour = "direct")
                ),
                outbounds = listOf(Outbound(type = "direct", tag = "direct"))
            )
        )

        assertTrue(report.contains("DNS 静态风险: 存在"))
        assertTrue(report.contains("DNS 服务器 local detour 指向直连出站 direct"))
    }

    @Test
    fun buildDnsLeakCheckReport_doesNotTreatAutoRouteAsAndroidVpnRouteSignal() {
        val report = buildDnsLeakCheckReport(
            coreActive = true,
            runConfig = createConfig(
                inbounds = listOf(Inbound(type = "tun", tag = "tun-in", autoRoute = false, strictRoute = true))
            )
        )

        assertTrue(report.contains("DNS 静态风险: 未发现明显配置缺口"))
        assertTrue(report.contains("此结果仅检查静态配置"))
    }

    @Test
    fun buildDnsLeakCheckReport_reportsSafeWhenConfigIsProtected() {
        val report = buildDnsLeakCheckReport(
            coreActive = true,
            runConfig = createConfig()
        )

        assertTrue(report.contains("DNS 静态风险: 未发现明显配置缺口"))
        assertTrue(report.contains("route.rules 已包含覆盖 tun-in:53 或 protocol=dns 的 hijack-dns"))
        assertTrue(report.contains("DNS final 指向有效且安全的 server tag"))
        assertTrue(report.contains("未发现系统 DNS 或明文直连 DNS server"))
        assertTrue(report.contains("不能替代设备抓包"))
    }

    private fun createNode(id: String, name: String): NodeUi {
        return NodeUi(
            id = id,
            name = name,
            protocol = "vmess",
            group = "test",
            sourceProfileId = "profile-1"
        )
    }

    private fun createConfig(
        routeRules: List<RouteRule> = listOf(
            RouteRule(inbound = listOf("tun-in"), port = listOf(53), action = "hijack-dns"),
            RouteRule(protocolRaw = listOf("dns"), action = "hijack-dns"),
            RouteRule(port = listOf(853), action = "reject")
        ),
        dnsServers: List<DnsServer> = listOf(DnsServer(tag = "local", type = "https", server = "dns.alidns.com")),
        finalServer: String = "local",
        inbounds: List<Inbound> = listOf(Inbound(type = "tun", tag = "tun-in", autoRoute = true, strictRoute = true)),
        outbounds: List<Outbound> = listOf(Outbound(type = "selector", tag = "PROXY"))
    ): SingBoxConfig {
        return SingBoxConfig(
            dns = DnsConfig(servers = dnsServers, finalServer = finalServer),
            inbounds = inbounds,
            outbounds = outbounds,
            route = RouteConfig(rules = routeRules)
        )
    }
}
