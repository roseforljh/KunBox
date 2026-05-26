package com.kunk.singbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test
    fun subscriptionImportPreservesDnsOverrideChain() {
        val profilesScreen = File("src/main/java/com/kunk/singbox/ui/screens/ProfilesScreen.kt").readText()
        val profilesViewModel = File("src/main/java/com/kunk/singbox/viewmodel/ProfilesViewModel.kt").readText()
        val configRepository = File("src/main/java/com/kunk/singbox/repository/ConfigRepository.kt").readText()

        assertTrue(
            profilesScreen.contains(
                "viewModel.importSubscription(name, url, autoUpdateInterval, dnsPreResolve, dnsServer, dnsOverride)"
            )
        )
        assertTrue(
            Regex(
                """fun importSubscription\([\s\S]*dnsOverride: String\? = null[\s\S]*""" +
                    """configRepository\.importFromSubscription\([\s\S]*dnsOverride = dnsOverride"""
            ).containsMatchIn(profilesViewModel)
        )
        assertTrue(
            Regex(
                """suspend fun importFromSubscription\([\s\S]*dnsOverride: String\? = null[\s\S]*""" +
                    """ProfileUi\([\s\S]*dnsOverride = dnsOverride"""
            ).containsMatchIn(configRepository)
        )
    }

    @Test
    fun englishAppListQuickSelectLabelStaysCompact() {
        val content = File("src/main/res/values-en/strings.xml").readText()
        val label = Regex("""<string name="app_list_quick_select">([^<]+)</string>""")
            .find(content)
            ?.groupValues
            ?.get(1)

        assertEquals("Common", label)
    }
}
