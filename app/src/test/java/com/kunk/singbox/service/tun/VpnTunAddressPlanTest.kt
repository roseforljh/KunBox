package com.kunk.singbox.service.tun

import com.google.gson.Gson
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.IpVersionMode
import com.kunk.singbox.model.VpnRouteMode
import org.junit.Assert.assertEquals

class VpnTunAddressPlanTest {

    @org.junit.Test
    fun plannerUsesOnlyIpv4WhenIpv4Only() {
        val plan = VpnTunAddressPlanner.build(IpVersionMode.IPV4_ONLY)

        assertEquals(listOf("172.19.0.1" to 30), plan.addresses)
        assertEquals(listOf("0.0.0.0" to 0), plan.globalRoutes)
        assertEquals(listOf("223.5.5.5", "119.29.29.29", "1.1.1.1"), plan.defaultDnsServers)
    }

    @org.junit.Test
    fun plannerUsesDualStackWhenDualStack() {
        val plan = VpnTunAddressPlanner.build(IpVersionMode.DUAL_STACK)

        assertEquals(listOf("172.19.0.1" to 30, "fd00::1" to 126), plan.addresses)
        assertEquals(listOf("0.0.0.0" to 0, "::" to 0), plan.globalRoutes)
        assertEquals(listOf("223.5.5.5", "119.29.29.29", "1.1.1.1", "2606:4700:4700::1111"), plan.defaultDnsServers)
    }

    @org.junit.Test
    fun plannerUsesOnlyIpv6WhenIpv6Only() {
        val plan = VpnTunAddressPlanner.build(IpVersionMode.IPV6_ONLY)

        assertEquals(listOf("fd00::1" to 126), plan.addresses)
        assertEquals(listOf("::" to 0), plan.globalRoutes)
        assertEquals(listOf("2606:4700:4700::1111"), plan.defaultDnsServers)
    }

    @org.junit.Test
    fun customRouteModeIncludesFakeIpRoutesWhenFakeDnsEnabled() {
        val settings = AppSettings(
            vpnRouteMode = VpnRouteMode.CUSTOM,
            vpnRouteIncludeCidrs = "8.8.8.8/32",
            fakeDnsEnabled = true,
            ipVersionMode = IpVersionMode.DUAL_STACK
        )

        val routes = VpnTunManager.resolveVpnRoutesForTest(
            settings = settings,
            tunPlan = VpnTunAddressPlanner.build(IpVersionMode.DUAL_STACK)
        )

        assertEquals(
            listOf(
                "8.8.8.8" to 32,
                "223.5.5.5" to 32,
                "119.29.29.29" to 32,
                "1.1.1.1" to 32,
                "2606:4700:4700::1111" to 128,
                "198.18.0.0" to 15,
                "fc00::" to 18
            ),
            routes
        )
    }

    @org.junit.Test
    fun fakeIpRoutesUseConfiguredIpv4AndIpv6RangesWhenFakeDnsEnabled() {
        val settings = AppSettings(
            fakeDnsEnabled = true,
            fakeIpRange = "198.19.0.0/16,fd00:abcd::/48",
            ipVersionMode = IpVersionMode.DUAL_STACK
        )

        val routes = VpnTunManager.resolveVpnRoutesForTest(
            settings = settings,
            tunPlan = VpnTunAddressPlanner.build(IpVersionMode.DUAL_STACK)
        )

        assertEquals(
            listOf(
                "0.0.0.0" to 0,
                "::" to 0,
                "198.19.0.0" to 16,
                "fd00:abcd::" to 48
            ),
            routes
        )
    }

    @org.junit.Test
    fun fakeIpRoutesRecoverDefaultRangesWhenConfiguredRangeIsNull() {
        val settings = Gson().fromJson(
            """{"fakeDnsEnabled":true,"ipVersionMode":"DUAL_STACK","fakeIpRange":null}""",
            AppSettings::class.java
        )

        val routes = VpnTunManager.resolveVpnRoutesForTest(
            settings = settings,
            tunPlan = VpnTunAddressPlanner.build(IpVersionMode.DUAL_STACK)
        )

        assertEquals(
            listOf(
                "0.0.0.0" to 0,
                "::" to 0,
                "198.18.0.0" to 15,
                "fc00::" to 18
            ),
            routes
        )
    }

    @org.junit.Test
    fun vpnDnsResolverRejectsTunLocalAddressFromLibbox() {
        val settings = AppSettings(
            localDns = "https://dns.alidns.com/dns-query",
            remoteDns = "https://1.1.1.1/dns-query"
        )

        val dnsServers = VpnTunManager.resolveVpnDnsServersForTest(
            settings = settings,
            dnsServerAddress = "172.19.0.1"
        )

        assertEquals(listOf("223.5.5.5", "119.29.29.29", "1.1.1.1", "2606:4700:4700::1111"), dnsServers)
    }

    @org.junit.Test
    fun vpnDnsResolverUsesNonTunAddressFromLibbox() {
        val settings = AppSettings(ipVersionMode = IpVersionMode.DUAL_STACK)

        val dnsServers = VpnTunManager.resolveVpnDnsServersForTest(
            settings = settings,
            dnsServerAddress = "8.8.8.8"
        )

        assertEquals(listOf("8.8.8.8"), dnsServers)
    }

    @org.junit.Test
    fun vpnDnsResolverFallsBackToDefaultDnsServersWhenNoLibboxAddress() {
        val settings = AppSettings(ipVersionMode = IpVersionMode.DUAL_STACK)

        val dnsServers = VpnTunManager.resolveVpnDnsServersForTest(
            settings = settings,
            dnsServerAddress = null,
            tunPlan = VpnTunAddressPlanner.build(IpVersionMode.DUAL_STACK)
        )

        assertEquals(listOf("223.5.5.5", "119.29.29.29", "1.1.1.1", "2606:4700:4700::1111"), dnsServers)
    }

    @org.junit.Test
    fun vpnDnsResolverUsesIpv4OnlyDefaultDnsWhenIpv4Only() {
        val settings = AppSettings(ipVersionMode = IpVersionMode.IPV4_ONLY)

        val dnsServers = VpnTunManager.resolveVpnDnsServersForTest(
            settings = settings,
            dnsServerAddress = null,
            tunPlan = VpnTunAddressPlanner.build(IpVersionMode.IPV4_ONLY)
        )

        assertEquals(listOf("223.5.5.5", "119.29.29.29", "1.1.1.1"), dnsServers)
    }

    @org.junit.Test
    fun perAppPlanUsesDisallowedApplicationsInBlocklistMode() {
        val settings = AppSettings(
            vpnAppMode = com.kunk.singbox.model.VpnAppMode.BLOCKLIST,
            vpnBlocklist = "com.blocked\ncom.other"
        )

        val plan = VpnTunManager.resolvePerAppVpnPlanForTest(settings, "com.kunk.singbox")

        assertEquals(emptyList<String>(), plan.allowedPackages)
        assertEquals(listOf("com.kunk.singbox", "com.blocked", "com.other"), plan.disallowedPackages)
    }

    @org.junit.Test
    fun httpProxyIsNotAppendedWhenTunModeIsEnabled() {
        val settings = AppSettings(
            tunEnabled = true,
            proxyPort = 2080,
            appendHttpProxy = true
        )

        assertEquals(false, VpnTunManager.shouldAppendHttpProxy(settings))
    }

    @org.junit.Test
    fun httpProxyCanBeAppendedOnlyOutsideTunMode() {
        val settings = AppSettings(
            tunEnabled = false,
            proxyPort = 2080,
            appendHttpProxy = true
        )

        assertEquals(true, VpnTunManager.shouldAppendHttpProxy(settings))
    }
}
