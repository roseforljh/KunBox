package com.kunk.singbox.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfilesScreenSourceTest {

    @Test
    fun customConfigDialogSavesNameAndSelectionAcrossRecreation() {
        val source = File("src/main/java/com/kunk/singbox/ui/screens/ProfilesScreen.kt").readText()
        val body = source.substring(
            source.indexOf("private fun CustomConfigDialog("),
            source.indexOf("androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss)")
        )

        assertTrue(body.contains("var name by rememberSaveable"))
        assertTrue(body.contains("var selectedNodeIds by rememberSaveable"))
        assertTrue(source.contains("private fun List<String>.updatedCustomSelection("))
        assertTrue(source.contains("onSelectionChange: (String, Boolean) -> Unit"))
    }

    @Test
    fun subscriptionInputDialogSavesEditableDraftAcrossRecreation() {
        val source = File("src/main/java/com/kunk/singbox/ui/screens/ProfilesScreen.kt").readText()
        val body = source.substring(
            source.indexOf("private fun SubscriptionInputDialog("),
            source.indexOf("val dnsServerOptions = listOf(")
        )

        assertTrue(source.contains("import androidx.compose.runtime.saveable.rememberSaveable"))
        assertTrue(body.contains("var name by rememberSaveable(initialName)"))
        assertTrue(body.contains("var url by rememberSaveable(initialUrl)"))
        assertTrue(body.contains("var autoUpdateEnabled by rememberSaveable(initialAutoUpdateInterval)"))
        assertTrue(body.contains("var autoUpdateMinutes by rememberSaveable(initialAutoUpdateInterval)"))
        assertTrue(body.contains("var dnsPreResolveEnabled by rememberSaveable(initialDnsPreResolve)"))
        assertTrue(body.contains("var selectedDnsServer by rememberSaveable(initialDnsServer)"))
        assertTrue(body.contains("var dnsOverrideText by rememberSaveable(initialDnsOverride)"))
        assertTrue(body.contains("var showDnsOverride by rememberSaveable(initialDnsOverride)"))
    }
}
