package com.kunk.singbox.service.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootWatchdogContractTest {
    @Test
    fun staleInstalledScriptNeverMatchesBundledScript() {
        assertFalse(rootScriptContentMatches("old-script", "new-script"))
        assertFalse(rootScriptContentMatches(null, "new-script"))
        assertTrue(rootScriptContentMatches("new-script", "new-script"))
    }

    @Test
    fun watchdogFailsClosedAndTouchesOnlyKunBoxRules() {
        val watchdog = File("src/main/assets/root/kunbox-root-watchdog.sh").readText()
        val cleanup = File("src/main/assets/root/kunbox-root-cleanup-owned.sh").readText()

        listOf(
            "KBX_OUT4", "KBX_PRE4", "KBX_IN4", "KBX_RED4",
            "KBX_OUT6", "KBX_PRE6", "KBX_IN6", "KBX_RED6",
            "KBX_BLOCK4", "KBX_BLOCK6", "KBX_QUIC4", "KBX_QUIC6",
            "KBX_GUARD4", "KBX_GUARD6"
        ).forEach {
            assertTrue(cleanup.contains(it))
        }
        assertTrue(watchdog.contains("! kill -0"))
        assertTrue(watchdog.contains("ROOT_START_TIME") && watchdog.contains("/proc/\$ROOT_PID/stat"))
        assertTrue(watchdog.contains("[ ! -e \"\$APK_PATH\" ]"))
        assertTrue(watchdog.contains("cleanup-owned.sh"))
        assertTrue(watchdog.contains("CURRENT_SESSION") && watchdog.contains("EXPECTED_SESSION"))
        assertTrue(cleanup.contains("SLOT=0") && cleanup.contains("-lt 128"))
        assertTrue(
            cleanup.contains("ROUTE_PROTOCOL=\"233\"") &&
                cleanup.contains("[ \"${'$'}PROTOCOL\" = \"0\" ]")
        )
        assertFalse(
            cleanup.contains("rule del fwmark") && cleanup.contains("protocol \"${'$'}ROUTE_PROTOCOL\"")
        )
        assertFalse(
            cleanup.contains("route del local \"${'$'}2\"") &&
                cleanup.contains("proto \"${'$'}ROUTE_PROTOCOL\"")
        )
        assertTrue(cleanup.contains("STAGING=1") && cleanup.contains("chain_template"))
        assertTrue(cleanup.contains("chain_present_after"))
        assertTrue(cleanup.contains("rule_present_after"))
        assertTrue(cleanup.contains("route_present_after"))
        assertTrue(cleanup.contains("legacy_rule_present"))
        assertTrue(cleanup.contains("legacy_route_present"))
        assertFalse(watchdog.contains("rm -rf"))
        assertFalse(cleanup.contains("rm -rf"))
        assertFalse(watchdog.contains("setenforce"))
        assertFalse(cleanup.contains("setenforce"))
        assertFalse(watchdog.contains("permissive"))
        assertFalse(cleanup.contains("permissive"))
    }
}
