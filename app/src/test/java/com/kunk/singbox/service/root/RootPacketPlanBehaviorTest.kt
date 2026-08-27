package com.kunk.singbox.service.root

import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.DnsConfig
import com.kunk.singbox.model.DnsRule
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.RootAppRoutingAssignment
import com.kunk.singbox.model.RootAppRoutingPlanCompiler
import com.kunk.singbox.model.RouteConfig
import com.kunk.singbox.model.RouteRule
import com.kunk.singbox.model.RoutingMode
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.model.TrafficCaptureMode
import com.kunk.singbox.model.TunStack
import com.kunk.singbox.repository.config.InboundBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootPacketPlanBehaviorTest {
    @Test
    @Suppress("LongMethod")
    fun tcpUdpAndDnsStayInTheirUidLaneWhileGenericUsesProxy() {
        val settings = AppSettings(
            trafficCaptureMode = TrafficCaptureMode.ROOT_TRANSPARENT,
            routingMode = RoutingMode.RULE
        )
        val plan = RootAppRoutingPlanCompiler.compile(
            settings,
            listOf(
                RootAppRoutingAssignment(
                    listOf("org.telegram.messenger"),
                    "OUTBOUND",
                    outboundTag = "germany",
                    sourceLabel = "Telegram"
                ),
                RootAppRoutingAssignment(
                    listOf("com.google.android.gms"),
                    "OUTBOUND",
                    outboundTag = "google-premium",
                    sourceLabel = "Google"
                )
            ),
            generation = 1L
        )
        val telegramLane = plan.lanes.single { "org.telegram.messenger" in it.packageNames }
        val googleLane = plan.lanes.single { "com.google.android.gms" in it.packageNames }
        val uidByLane = mapOf(telegramLane.laneId to 10_123, googleLane.laneId to 10_124)
        val netfilterPlan = RootNetfilterPlanner.build(
            RootNetfilterConfig(
                capturedUids = listOf(10_123, 10_124, 10_125),
                capturedUidRanges = emptyList(),
                excludedUids = listOf(10_126),
                appUid = 10_234,
                proxyIpv4 = true,
                proxyIpv6 = true,
                blockIpv4 = false,
                blockIpv6 = false,
                redirectPortIpv4 = InboundBuilder.ROOT_REDIRECT_PORT_IPV4,
                redirectPortIpv6 = InboundBuilder.ROOT_REDIRECT_PORT_IPV6,
                tproxyPortIpv4 = InboundBuilder.ROOT_TPROXY_PORT_IPV4,
                tproxyPortIpv6 = InboundBuilder.ROOT_TPROXY_PORT_IPV6,
                lanes = plan.lanes.map { lane ->
                    RootNetfilterLane(
                        lane.laneId,
                        lane.slot,
                        listOf(uidByLane.getValue(lane.laneId)),
                        lane.tcpPortIpv4,
                        lane.tcpPortIpv6,
                        lane.udpPortIpv4,
                        lane.udpPortIpv6,
                        lane.markIpv4,
                        lane.markIpv6,
                        lane.priorityIpv4,
                        lane.priorityIpv6
                    )
                }
            )
        )
        val config = SingBoxConfig(
            inbounds = InboundBuilder.build(settings, TunStack.SYSTEM, plan),
            outbounds = listOf(
                Outbound(type = "selector", tag = "PROXY"),
                Outbound(type = "direct", tag = "direct"),
                Outbound(type = "socks", tag = "germany"),
                Outbound(type = "socks", tag = "google-premium")
            ),
            route = RouteConfig(
                rules = plan.lanes.map { lane ->
                    RouteRule(inbound = lane.inboundTags(true, true), outbound = lane.outboundTag)
                },
                finalOutbound = "PROXY"
            ),
            dns = DnsConfig(
                rules = plan.lanes.map { lane ->
                    DnsRule(inbound = lane.inboundTags(true, true), server = lane.outboundTag)
                },
                finalServer = "PROXY"
            )
        )
        val interpreter = PacketInterpreter(netfilterPlan.setupCommands, config)

        listOf("tcp", "udp").forEach { protocol ->
            assertEquals("germany", interpreter.route(10_123, protocol, 443)?.outbound)
            assertEquals("google-premium", interpreter.route(10_124, protocol, 443)?.outbound)
            assertEquals("PROXY", interpreter.route(10_125, protocol, 443)?.outbound)
            assertNull(interpreter.route(10_126, protocol, 443))
        }
        assertEquals("germany", interpreter.route(10_123, "udp", 53)?.dnsServer)
        assertEquals("google-premium", interpreter.route(10_124, "tcp", 53)?.dnsServer)
        assertNull(interpreter.route(10_123, "udp", 5_353, destination = "224.0.0.251"))
        assertTrue(interpreter.route(10_123, "udp", 443, ipv6 = true)?.inbound?.endsWith("udp-v6") == true)
    }

    private data class PacketVerdict(val inbound: String, val outbound: String, val dnsServer: String?)

    private class PacketInterpreter(
        private val commands: List<List<String>>,
        private val config: SingBoxConfig
    ) {
        fun route(
            uid: Int,
            protocol: String,
            destinationPort: Int,
            destination: String = "203.0.113.1",
            ipv6: Boolean = false
        ): PacketVerdict? {
            val binary = if (ipv6) "ip6tables" else "iptables"
            val listenPort = if (protocol == "tcp") {
                evaluateTcp(binary, uid, destinationPort, destination)
            } else {
                evaluateUdp(binary, uid, destinationPort, destination, ipv6)
            } ?: return null
            val inbound = config.inbounds.orEmpty().single {
                it.listenPort == listenPort && (protocol == "tcp" || it.network == "udp") &&
                    (ipv6 == (it.listen == "::"))
            }.tag.orEmpty()
            val outbound = config.route?.rules.orEmpty().firstOrNull { inbound in it.inbound.orEmpty() }
                ?.outbound ?: config.route?.finalOutbound.orEmpty()
            val dnsServer = if (destinationPort == 53) {
                config.dns?.rules.orEmpty().firstOrNull { inbound in it.inbound.orEmpty() }?.server
                    ?: config.dns?.finalServer
            } else {
                null
            }
            return PacketVerdict(inbound, outbound, dnsServer)
        }

        private fun evaluateTcp(binary: String, uid: Int, port: Int, destination: String): Int? {
            val chain = if (binary == "iptables") "KBX_RED4" else "KBX_RED6"
            commands.filter { it.firstOrNull() == binary && it.containsAll(listOf("-A", chain)) }
                .forEach { command ->
                    if (!matches(command, uid, "tcp", port, destination)) return@forEach
                    return when (command.valueAfter("-j")) {
                        "RETURN" -> null
                        "REDIRECT" -> command.valueAfter("--to-ports")?.toInt()
                        else -> null
                    }
                }
            return null
        }

        @Suppress("ReturnCount")
        private fun evaluateUdp(
            binary: String,
            uid: Int,
            port: Int,
            destination: String,
            ipv6: Boolean
        ): Int? {
            val outChain = if (binary == "iptables") "KBX_OUT4" else "KBX_OUT6"
            var mark: String? = null
            commands.filter { it.firstOrNull() == binary && it.containsAll(listOf("-A", outChain)) }
                .forEach { command ->
                    if (!matches(command, uid, "udp", port, destination)) return@forEach
                    when (command.valueAfter("-j")) {
                        "RETURN" -> return if (mark == null) null else tproxyPort(binary, mark.orEmpty())
                        "MARK" -> mark = command.valueAfter("--set-mark")
                    }
                }
            val resolvedMark = mark ?: return null
            val policyCommand = commands.singleOrNull { command ->
                command.firstOrNull() == "ip" && ("-6" in command) == ipv6 &&
                    command.valueAfter("fwmark") == "$resolvedMark/0xffffffff" && "add" in command
            } ?: return null
            assertEquals(RootNetfilterPlanner.ROUTE_TABLE, policyCommand.valueAfter("table"))
            return tproxyPort(binary, resolvedMark)
        }

        private fun tproxyPort(binary: String, mark: String): Int? {
            val preChain = if (binary == "iptables") "KBX_PRE4" else "KBX_PRE6"
            return commands.firstOrNull { command ->
                command.firstOrNull() == binary && command.containsAll(listOf("-A", preChain, "TPROXY")) &&
                    command.valueAfter("--mark") == "$mark/0xffffffff"
            }?.valueAfter("--on-port")?.toIntOrNull()
        }

        @Suppress("ReturnCount")
        private fun matches(
            command: List<String>,
            uid: Int,
            protocol: String,
            port: Int,
            destination: String
        ): Boolean {
            if (command.valueAfter("--mark") != null) return false
            val owner = command.valueAfter("--uid-owner")
            if (owner != null && !ownerRangeContains(owner, uid)) return false
            val expectedProtocol = command.valueAfter("-p")
            if (expectedProtocol != null && expectedProtocol != protocol) return false
            val expectedPort = command.valueAfter("--dport")?.toIntOrNull()
            if (expectedPort != null && expectedPort != port) return false
            val expectedDestination = command.valueAfter("-d")
            return expectedDestination == null || expectedDestination == destination
        }

        private fun ownerRangeContains(raw: String, uid: Int): Boolean {
            val parts = raw.split('-', limit = 2)
            val first = parts.first().toInt()
            val last = parts.getOrElse(1) { parts.first() }.toInt()
            return uid in first..last
        }

        private fun List<String>.valueAfter(value: String): String? =
            indexOf(value).takeIf { it >= 0 }?.let { getOrNull(it + 1) }
    }
}
