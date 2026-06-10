package com.kunk.singbox.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppRoutingComponentsSourceTest {

    @Test
    fun appRuleEditorSavesDraftAcrossRecreation() {
        val source = File(
            "src/main/java/com/kunk/singbox/ui/screens/AppRoutingComponents.kt"
        ).readText()
        val start = source.indexOf("fun AppRuleEditorDialog(")
        val body = source.substring(
            start,
            source.indexOf("val context = LocalContext.current", start)
        )

        assertTrue(source.contains("import androidx.compose.runtime.saveable.rememberSaveable"))
        assertTrue(body.contains("var selectedAppPackageName by rememberSaveable"))
        assertTrue(body.contains("var selectedAppName by rememberSaveable"))
        assertTrue(body.contains("var outboundMode by rememberSaveable"))
        assertTrue(body.contains("var outboundValue by rememberSaveable"))
        assertTrue(source.contains("val selectedApp = selectedAppPackageName?.let"))
    }

    @Test
    fun appGroupEditorAndMultiSelectorSaveDraftAcrossRecreation() {
        val source = File(
            "src/main/java/com/kunk/singbox/ui/screens/AppRoutingComponents.kt"
        ).readText()
        val groupEditorStart = source.indexOf("fun AppGroupEditorDialog(")
        val groupEditor = source.substring(groupEditorStart)
        val multiSelector = source.substring(
            source.indexOf("fun MultiAppSelectorDialog("),
            source.indexOf("fun AppGroupEditorDialog(")
        )

        assertTrue(groupEditor.contains("var groupName by rememberSaveable"))
        assertTrue(groupEditor.contains("var outboundMode by rememberSaveable"))
        assertTrue(groupEditor.contains("var outboundValue by rememberSaveable"))
        assertTrue(groupEditor.contains("var selectedAppEntries by rememberSaveable"))
        assertTrue(multiSelector.contains("var tempSelectedEntries by rememberSaveable"))
        assertTrue(source.contains("private fun AppInfo.toSavedValue(): String"))
        assertTrue(source.contains("private fun String.toAppInfo(): AppInfo"))
    }

    @Test
    fun appGroupEditorDefaultsToDirectAndOffersDirectMode() {
        val source = File(
            "src/main/java/com/kunk/singbox/ui/screens/AppRoutingComponents.kt"
        ).readText()
        val groupEditorStart = source.indexOf("fun AppGroupEditorDialog(")
        val groupEditor = source.substring(groupEditorStart)

        assertTrue(groupEditor.contains("initialGroup?.outboundMode ?: RuleSetOutboundMode.DIRECT"))
        assertTrue(groupEditor.contains("val appRoutingModes = RuleSetOutboundMode.entries"))
    }
}
