package com.kunk.singbox.service.root

import com.kunk.singbox.model.DnsConfig
import com.kunk.singbox.model.DnsServer
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootAutoFailoverControllerTest {
    @Test
    fun transportFailureUsesFirstOutboundInConnectionChain() {
        val tracker = RootRuntimeFailureTracker()

        repeat(2) { index ->
            val id = 100 + index
            tracker.observe("INFO [$id 0ms] outbound/hysteria2[bad-node]: outbound connection", index * 100L)
            assertNull(
                tracker.observe(
                    "time ERROR[$id 5s] remote error using outbound/direct[direct]: i/o timeout",
                    index * 100L + 50L
                )
            )
        }
        tracker.observe("INFO [102 0ms] outbound/hysteria2[bad-node]: outbound connection", 200L)

        assertEquals(
            RootFailoverTarget("bad-node"),
            tracker.observe("time ERROR[102 5s] outbound/direct[direct]: i/o timeout", 250L)
        )
    }

    @Test
    fun detectsCurrentSingBoxNoRecentActivityTimeoutFormat() {
        val tracker = RootRuntimeFailureTracker()

        repeat(3) { index ->
            val id = 200 + index
            tracker.observe(
                "INFO[0010] [$id 0ms] outbound/hysteria2[bad-node]: outbound connection",
                index * 100L
            )
            val failed = tracker.observe(
                "ERROR[0015] [$id 5.3s] connection: timeout: no recent network activity",
                index * 100L + 50L
            )
            if (index < 2) assertNull(failed) else assertEquals(RootFailoverTarget("bad-node"), failed)
        }
    }

    @Test
    fun threeSequentialFiveSecondTimeoutsStillTriggerFailover() {
        val tracker = RootRuntimeFailureTracker()

        repeat(3) { index ->
            val id = 300 + index
            val startedAt = index * 6_000L
            tracker.observe(
                "INFO[0010] [$id 0ms] outbound/hysteria2[bad-node]: outbound connection",
                startedAt
            )
            val failed = tracker.observe(
                "ERROR[0015] [$id 5.3s] connection: timeout: no recent network activity",
                startedAt + 5_300L
            )
            if (index < 2) assertNull(failed) else assertEquals(RootFailoverTarget("bad-node"), failed)
        }
    }

    @Test
    fun resolvesFailingNestedSelectorInsteadOfMainProxy() {
        val outbounds = listOf(
            Outbound(type = "selector", tag = "PROXY", outbounds = listOf("main", "bad-node")),
            Outbound(type = "selector", tag = "P:鹰", outbounds = listOf("bad-node", "backup")),
            Outbound(type = "vless", tag = "main"),
            Outbound(type = "hysteria2", tag = "bad-node"),
            Outbound(type = "vless", tag = "backup")
        )
        val selections = mapOf("PROXY" to "main", "P:鹰" to "bad-node")

        assertEquals(
            RootFailoverGroup("P:鹰", "bad-node", listOf("bad-node", "backup")),
            RootFailoverGroups.resolve(outbounds, "bad-node", selectedTag = selections::get)
        )
    }

    @Test
    fun transportFailureKeepsTheActualRoutingGroup() {
        val tracker = RootRuntimeFailureTracker(minimumFailures = 1)
        tracker.observe("DEBUG [401 1ms] router: match[8] => route(F:youtube)", 0L)
        tracker.observe("INFO [401 2ms] outbound/hysteria2[bad-node]: outbound connection", 1L)

        assertEquals(
            RootFailoverTarget("bad-node", "F:youtube"),
            tracker.observe("ERROR [401 5s] timeout: no recent network activity", 5_000L)
        )
    }

    @Test
    fun remoteDnsFailureResolvesItsOwnSelectorInsteadOfMainProxy() {
        val config = SingBoxConfig(
            dns = DnsConfig(
                servers = listOf(
                    DnsServer(tag = "dns-google", type = "https", server = "1.1.1.1", detour = "P:鹰")
                )
            ),
            outbounds = listOf(
                Outbound(type = "selector", tag = "PROXY", outbounds = listOf("main")),
                Outbound(type = "selector", tag = "P:鹰", outbounds = listOf("bad-node", "backup")),
                Outbound(type = "vless", tag = "main"),
                Outbound(type = "hysteria2", tag = "bad-node"),
                Outbound(type = "vless", tag = "backup")
            )
        )
        val selections = mapOf("PROXY" to "main", "P:鹰" to "bad-node")

        assertEquals(
            RootFailoverTarget("bad-node", "P:鹰"),
            RootFailoverGroups.resolveDnsFailureTarget(config, "dns-google", selections::get)
        )
    }

    @Test
    fun rootManualSwitchConvergesOldConnectionsAndNetwork() {
        val source = java.io.File(
            "src/main/java/com/kunk/singbox/service/root/RootTransparentForegroundService.kt"
        ).readText()
        val successBranch = source.substringAfter("is SelectorManager.SwitchResult.Success ->")
            .substringBefore("is SelectorManager.SwitchResult.NeedRestart")

        assertTrue(successBranch.contains("commandManager.closeConnections()"))
        assertTrue(successBranch.contains("rootConnection.service?.resetNetwork()"))
    }
}
