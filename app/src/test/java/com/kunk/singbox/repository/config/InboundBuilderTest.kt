package com.kunk.singbox.repository.config

import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.IpVersionMode
import com.kunk.singbox.model.TunAddressConfig
import com.kunk.singbox.model.TunStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundBuilderTest {

    @Test
    fun buildOmitsLegacySniffFieldsWhileKeepingExpectedInbounds() {
        val inbounds = InboundBuilder.build(
            settings = AppSettings(
                tunEnabled = true,
                proxyPort = 7890
            ),
            effectiveTunStack = TunStack.MIXED
        )

        assertEquals(listOf("mixed-in", "tun-in"), inbounds.mapNotNull { it.tag })
        assertTrue(inbounds.all { it.sniff == null })
        assertTrue(inbounds.all { it.sniffOverrideDestination == null })
        assertTrue(inbounds.all { it.sniffTimeout == null })
    }

    @Test
    fun buildTunInboundAddressesFollowIpVersionMode() {
        val tunAddress = TunAddressConfig(ipv4 = "10.7.0.1/30", ipv6 = "fd07::1/126")

        val ipv4Only = InboundBuilder.build(
            settings = AppSettings(
                tunEnabled = true,
                proxyPort = 0,
                ipVersionMode = IpVersionMode.IPV4_ONLY,
                tunAddress = tunAddress
            ),
            effectiveTunStack = TunStack.MIXED
        ).single()
        val dualStack = InboundBuilder.build(
            settings = AppSettings(
                tunEnabled = true,
                proxyPort = 0,
                ipVersionMode = IpVersionMode.DUAL_STACK,
                tunAddress = tunAddress
            ),
            effectiveTunStack = TunStack.MIXED
        ).single()
        val preferIpv6 = InboundBuilder.build(
            settings = AppSettings(
                tunEnabled = true,
                proxyPort = 0,
                ipVersionMode = IpVersionMode.PREFER_IPV6,
                tunAddress = tunAddress
            ),
            effectiveTunStack = TunStack.MIXED
        ).single()
        val ipv6Only = InboundBuilder.build(
            settings = AppSettings(
                tunEnabled = true,
                proxyPort = 0,
                ipVersionMode = IpVersionMode.IPV6_ONLY,
                tunAddress = tunAddress
            ),
            effectiveTunStack = TunStack.MIXED
        ).single()

        assertEquals(listOf("10.7.0.1/30"), ipv4Only.address)
        assertEquals(listOf("10.7.0.1/30", "fd07::1/126"), dualStack.address)
        assertEquals(listOf("10.7.0.1/30", "fd07::1/126"), preferIpv6.address)
        assertEquals(listOf("fd07::1/126"), ipv6Only.address)
    }

    @Test
    fun buildFallbackMixedInboundAlsoOmitsLegacySniffFields() {
        val inbound = InboundBuilder.build(
            settings = AppSettings(
                tunEnabled = false,
                proxyPort = 0
            ),
            effectiveTunStack = TunStack.SYSTEM
        ).single()

        assertEquals("mixed-in", inbound.tag)
        assertNull(inbound.sniff)
        assertNull(inbound.sniffOverrideDestination)
        assertNull(inbound.sniffTimeout)
    }

    @Test
    fun buildMixedInboundUsesLoopbackWhenLanIsDisabled() {
        val inbound = InboundBuilder.build(
            settings = AppSettings(
                tunEnabled = false,
                proxyPort = 7890,
                allowLan = false
            ),
            effectiveTunStack = TunStack.SYSTEM
        ).single()

        assertEquals("127.0.0.1", inbound.listen)
    }

    @Test
    fun buildMixedInboundUsesAnyAddressWhenLanIsEnabled() {
        val inbound = InboundBuilder.build(
            settings = AppSettings(
                tunEnabled = false,
                proxyPort = 7890,
                allowLan = true
            ),
            effectiveTunStack = TunStack.SYSTEM
        ).single()

        assertEquals("0.0.0.0", inbound.listen)
    }

    @Test
    fun buildTunInboundRespectsAutoRouteAndStrictRouteSettings() {
        val inbound = InboundBuilder.build(
            settings = AppSettings(
                tunEnabled = true,
                proxyPort = 0,
                autoRoute = true,
                strictRoute = true
            ),
            effectiveTunStack = TunStack.MIXED
        ).single()

        assertEquals(true, inbound.autoRoute)
        assertEquals(true, inbound.strictRoute)
    }
}
