package com.kunk.singbox.repository.config

import com.google.gson.Gson
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.IpVersionMode
import com.kunk.singbox.model.RootAppRoutingAssignment
import com.kunk.singbox.model.RootAppRoutingPlanCompiler
import com.kunk.singbox.model.TunStack
import com.kunk.singbox.model.TrafficCaptureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val ipv4Only = InboundBuilder.build(
            settings = AppSettings(
                tunEnabled = true,
                proxyPort = 0,
                ipVersionMode = IpVersionMode.IPV4_ONLY
            ),
            effectiveTunStack = TunStack.MIXED
        ).single()
        val dualStack = InboundBuilder.build(
            settings = AppSettings(
                tunEnabled = true,
                proxyPort = 0,
                ipVersionMode = IpVersionMode.DUAL_STACK
            ),
            effectiveTunStack = TunStack.MIXED
        ).single()
        val preferIpv6 = InboundBuilder.build(
            settings = AppSettings(
                tunEnabled = true,
                proxyPort = 0,
                ipVersionMode = IpVersionMode.PREFER_IPV6
            ),
            effectiveTunStack = TunStack.MIXED
        ).single()
        val ipv6Only = InboundBuilder.build(
            settings = AppSettings(
                tunEnabled = true,
                proxyPort = 0,
                ipVersionMode = IpVersionMode.IPV6_ONLY
            ),
            effectiveTunStack = TunStack.MIXED
        ).single()

        assertEquals(listOf("172.19.0.1/30"), ipv4Only.address)
        assertEquals(listOf("172.19.0.1/30", "fd00::1/126"), dualStack.address)
        assertEquals(listOf("172.19.0.1/30", "fd00::1/126"), preferIpv6.address)
        assertEquals(listOf("fd00::1/126"), ipv6Only.address)
        listOf(ipv4Only, dualStack, preferIpv6, ipv6Only).forEach { inbound ->
            assertNull(inbound.interfaceName)
            assertFalse(Gson().toJson(inbound).contains("endpoint_independent_nat"))
        }
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

    @Test
    fun buildRootTransparentInboundUsesRedirectForTcpAndTproxyForUdp() {
        val inbounds = InboundBuilder.build(
            settings = AppSettings(
                trafficCaptureMode = TrafficCaptureMode.ROOT_TRANSPARENT,
                proxyPort = 7890,
                ipVersionMode = IpVersionMode.DUAL_STACK
            ),
            effectiveTunStack = TunStack.SYSTEM
        )

        assertEquals(
            listOf("mixed-in", "redirect-in-v4", "tproxy-in-v4", "redirect-in-v6", "tproxy-in-v6"),
            inbounds.mapNotNull { it.tag }
        )
        val rootInbounds = inbounds.drop(1)
        assertEquals(listOf("redirect", "tproxy", "redirect", "tproxy"), rootInbounds.map { it.type })
        assertEquals(listOf(null, "udp", null, "udp"), rootInbounds.map { it.network })
        assertEquals(listOf(null, "5m", null, "5m"), rootInbounds.map { it.udpTimeout })
        assertEquals(listOf("0.0.0.0", "0.0.0.0", "::", "::"), rootInbounds.map { it.listen })
        assertTrue(rootInbounds.all { it.address == null })
    }

    @Test
    fun buildRootTransparentInboundAddsDeterministicLaneListeners() {
        val settings = AppSettings(
            trafficCaptureMode = TrafficCaptureMode.ROOT_TRANSPARENT,
            proxyPort = 0,
            ipVersionMode = IpVersionMode.DUAL_STACK
        )
        val plan = RootAppRoutingPlanCompiler.compile(
            settings = settings,
            assignments = listOf(
                RootAppRoutingAssignment(
                    packageNames = listOf("org.telegram.messenger"),
                    targetKind = "OUTBOUND",
                    outboundTag = "germany",
                    sourceLabel = "Telegram"
                )
            ),
            generation = 1L
        )

        val inbounds = InboundBuilder.build(settings, TunStack.SYSTEM, plan)
        val lane = plan.lanes.single()

        assertEquals(
            listOf(
                lane.tcpInboundIpv4,
                lane.udpInboundIpv4,
                lane.tcpInboundIpv6,
                lane.udpInboundIpv6
            ),
            inbounds.drop(4).mapNotNull { it.tag }
        )
        assertEquals(
            listOf(lane.tcpPortIpv4, lane.udpPortIpv4, lane.tcpPortIpv6, lane.udpPortIpv6),
            inbounds.drop(4).mapNotNull { it.listenPort }
        )
    }
}
