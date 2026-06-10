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
    fun mainActivityProcessesShortcutAndDeepLinkNewIntents() {
        val content = File("src/main/java/com/kunk/singbox/MainActivity.kt").readText()

        assertTrue(content.contains("private object MainIntentEvents"))
        assertTrue(content.contains("MainIntentEvents.emit(intent)"))
        assertTrue(content.contains("LaunchedEffect(intentEvent?.id)"))
        assertTrue(content.contains("DeepLinkHandler.setPendingSubscriptionImport(name, url, interval)"))
        assertTrue(content.contains("pendingNavigation = \"profiles\""))
    }

    @Test
    fun profileEditorRouteCarriesProfileIdAndSavesContent() {
        val navigation = File("src/main/java/com/kunk/singbox/ui/navigation/AppNavigation.kt").readText()
        val profilesScreen = File("src/main/java/com/kunk/singbox/ui/screens/ProfilesScreen.kt").readText()
        val editorScreen = File("src/main/java/com/kunk/singbox/ui/screens/ProfileEditorScreen.kt").readText()
        val repository = File("src/main/java/com/kunk/singbox/repository/ConfigRepository.kt").readText()

        assertTrue(navigation.contains("object ProfileEditor : Screen(\"profile_editor/{profileId}\")"))
        assertTrue(navigation.contains("fun createRoute(profileId: String)"))
        assertTrue(profilesScreen.contains("navController.navigate(Screen.ProfileEditor.createRoute(profile.id))"))
        assertTrue(editorScreen.contains("readProfileConfigContent(profileId)"))
        assertTrue(editorScreen.contains("updateProfileConfigContent(profileId, content)"))
        assertTrue(repository.contains("suspend fun updateProfileConfigContent(profileId: String, content: String)"))
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
