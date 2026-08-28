package com.kunk.singbox.service.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCapabilityProbeTest {
    @Test
    fun probesAllKernelFeaturesThroughOneShellProcess() {
        var calls = 0
        val executor = RootCommandExecutor { command ->
            calls++
            assertEquals(listOf("/system/bin/sh", "-c"), command.take(2))
            RootCommandResult(
                0,
                """
                    iptables=1
                    ip6tables=1
                    ip_command=1
                    owner_match=1
                    tproxy_ipv4=1
                    tproxy_ipv6=0
                    redirect_ipv4=1
                    redirect_ipv6=0
                """.trimIndent()
            )
        }

        val report = RootCapabilityProbe(executor).probe()

        assertEquals(1, calls)
        assertTrue(report.iptables)
        assertTrue(report.ip6tables)
        assertTrue(report.ipCommand)
        assertTrue(report.ownerMatch)
        assertTrue(report.tproxyIpv4)
        assertFalse(report.tproxyIpv6)
        assertTrue(report.redirectIpv4)
        assertFalse(report.redirectIpv6)
        assertFalse(report.routeProtocol)
    }

    @Test
    fun routeOwnershipDoesNotDependOnOptionalProtocolSupport() {
        var script = ""
        val executor = RootCommandExecutor { command ->
            script = command.last()
            RootCommandResult(0, "")
        }

        RootCapabilityProbe(executor).probe()

        assertFalse(script.contains("ip route add local"))
        assertFalse(script.contains("protocol 233"))
        assertTrue(
            RootCapabilityReport(
                rootUid = true,
                capNetAdmin = true,
                capNetRaw = true,
                ipCommand = true,
                iptables = true,
                ip6tables = false,
                tproxyIpv4 = true,
                tproxyIpv6 = false,
                redirectIpv4 = true,
                redirectIpv6 = false,
                ownerMatch = true,
                routeProtocol = false,
                selinuxDomain = "u:r:su:s0"
            ).supported
        )
    }
}
