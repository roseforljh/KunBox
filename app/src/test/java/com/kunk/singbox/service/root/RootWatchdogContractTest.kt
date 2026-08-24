package com.kunk.singbox.service.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootWatchdogContractTest {
    @Test
    fun watchdogFailsOpenAndTouchesOnlyKunBoxRules() {
        val script = File("src/main/assets/root/kunbox-root-watchdog.sh").readText()

        listOf(
            "KBX_OUT4", "KBX_PRE4", "KBX_IN4", "KBX_RED4",
            "KBX_OUT6", "KBX_PRE6", "KBX_IN6", "KBX_RED6",
            "KBX_BLOCK4", "KBX_BLOCK6", "KBX_QUIC4", "KBX_QUIC6",
            "lease", "watchdog_ack"
        ).forEach {
            assertTrue(script.contains(it))
        }
        assertTrue(script.contains("! kill -0"))
        assertTrue(script.contains("[ ! -e \"\$APK_PATH\" ]"))
        assertTrue(script.contains("cleanup_rules"))
        assertTrue(script.contains("CURRENT_SESSION") && script.contains("EXPECTED_SESSION"))
        assertFalse(script.contains("rm -rf"))
        assertFalse(script.contains("setenforce"))
        assertFalse(script.contains("permissive"))
    }
}
