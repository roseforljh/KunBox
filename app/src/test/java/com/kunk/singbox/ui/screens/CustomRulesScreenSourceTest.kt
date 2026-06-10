package com.kunk.singbox.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CustomRulesScreenSourceTest {

    @Test
    fun customRuleEditorSavesDraftAcrossRecreationAndTrimsFields() {
        val source = File("src/main/java/com/kunk/singbox/ui/screens/CustomRulesScreen.kt").readText()
        val body = source.substring(
            source.indexOf("fun CustomRuleEditorDialog("),
            source.indexOf("var showTypeDialog by remember")
        )
        val confirmBody = source.substring(
            source.indexOf("val newRule = initialRule?.copy("),
            source.indexOf("onConfirm(newRule)")
        )

        assertTrue(source.contains("import androidx.compose.runtime.saveable.rememberSaveable"))
        assertTrue(body.contains("var name by rememberSaveable(initialRule?.name)"))
        assertTrue(body.contains("var type by rememberSaveable(initialRule?.type)"))
        assertTrue(body.contains("var value by rememberSaveable(initialRule?.value)"))
        assertTrue(body.contains("var outbound by rememberSaveable(initialRule?.outbound)"))
        assertTrue(confirmBody.contains("name = name.trim()"))
        assertTrue(confirmBody.contains("value = value.trim()"))
    }
}
