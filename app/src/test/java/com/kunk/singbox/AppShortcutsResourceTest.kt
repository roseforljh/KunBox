package com.kunk.singbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppShortcutsResourceTest {

    @Test
    fun shortcutsXmlRoutesToggleToShortcutActivityAndSwitchNodeToMainActivity() {
        val content = File("src/main/res/xml/shortcuts.xml").readText()

        assertTrue(content.contains("android:shortcutId=\"toggle_vpn\""))
        assertTrue(content.contains("com.kunk.singbox.action.TOGGLE"))
        assertTrue(content.contains("android:targetClass=\"com.kunk.singbox.ui.ShortcutActivity\""))
        assertTrue(content.contains("android:shortcutId=\"switch_node\""))
        assertTrue(content.contains("com.kunk.singbox.action.SWITCH_NODE"))
        assertTrue(content.contains("android:targetClass=\"com.kunk.singbox.MainActivity\""))
    }

    @Test
    fun mainActivityDoesNotHandleToggleShortcutAction() {
        val content = File("src/main/java/com/kunk/singbox/MainActivity.kt").readText()

        assertFalse(content.contains("com.kunk.singbox.action.TOGGLE"))
    }

    @Test
    fun blockedDnsRulesUsePredefinedNoErrorInsteadOfReject() {
        val content = File("src/main/java/com/kunk/singbox/repository/ConfigRepository.kt").readText()

        assertTrue(content.contains("fun dnsReject(rule: DnsRule): DnsRule ="))
        assertTrue(content.contains("action = \"predefined\""))
        assertTrue(content.contains("rcode = \"NOERROR\""))
        assertFalse(content.contains("fun dnsReject(rule: DnsRule): DnsRule = rule.copy(action = \"reject\""))
    }
}
