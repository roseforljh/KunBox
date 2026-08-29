package com.kunk.singbox.service.root

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootWatchdogContractTest {
    @Test
    fun staleInstalledScriptNeverMatchesBundledScript() {
        assertFalse(rootScriptContentMatches("old-script", "new-script"))
        assertFalse(rootScriptContentMatches(null, "new-script"))
        assertTrue(rootScriptContentMatches("new-script", "new-script"))
    }

    @Test
    fun cleanupTouchesOnlyKnownKunBoxStateAndReportsRealDiagnostics() {
        val watchdog = File("src/main/assets/root/kunbox-root-watchdog.sh").readText()
        val cleanup = File("src/main/assets/root/kunbox-root-cleanup-owned.sh").readText()

        listOf(
            "KBX_OUT4", "KBX_PRE4", "KBX_IN4", "KBX_RED4",
            "KBX_OUT6", "KBX_PRE6", "KBX_IN6", "KBX_RED6",
            "KBX_BLOCK4", "KBX_BLOCK6", "KBX_QUIC4", "KBX_QUIC6",
            "KBX_GUARD4", "KBX_GUARD6"
        ).forEach { assertTrue(cleanup.contains(it)) }
        assertTrue(cleanup.contains("delete_known_chains"))
        assertTrue(cleanup.contains("remaining_owned_rules"))
        assertTrue(cleanup.contains("remaining_owned_chains"))
        assertTrue(cleanup.contains("NETFILTER_VERIFICATION_FAILED:"))
        assertTrue(cleanup.contains("backend="))
        assertTrue(cleanup.contains("cleanup_command="))
        assertTrue(cleanup.contains("mkdir \"\$LOCK_DIR\""))
        assertFalse(cleanup.contains("iptables -F"))
        assertFalse(cleanup.contains("ip6tables -F"))
        assertFalse(cleanup.contains("nft flush ruleset"))
        assertFalse(cleanup.contains("rm -rf"))
        assertFalse(watchdog.contains("rm -rf"))
        assertFalse(watchdog.contains("setenforce"))
        assertFalse(cleanup.contains("setenforce"))
    }
}
