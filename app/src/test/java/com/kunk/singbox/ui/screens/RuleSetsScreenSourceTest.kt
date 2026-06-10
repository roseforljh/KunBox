package com.kunk.singbox.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuleSetsScreenSourceTest {

    @Test
    fun ruleSetEditorSavesDraftAcrossRecreationAndTrimsSavedFields() {
        val source = File("src/main/java/com/kunk/singbox/ui/screens/RuleSetsScreen.kt").readText()
        val body = source.substring(
            source.indexOf("fun RuleSetEditorDialog("),
            source.indexOf("if (showTypeDialog)")
        )
        val confirmBody = source.substring(
            source.indexOf("val newRuleSet = initialRuleSet?.copy("),
            source.indexOf("onConfirm(newRuleSet)")
        )

        assertTrue(source.contains("import androidx.compose.runtime.saveable.rememberSaveable"))
        assertTrue(body.contains("var tag by rememberSaveable(initialRuleSet?.tag)"))
        assertTrue(body.contains("var type by rememberSaveable(initialRuleSet?.type)"))
        assertTrue(body.contains("var format by rememberSaveable(initialRuleSet?.format)"))
        assertTrue(body.contains("var url by rememberSaveable(initialRuleSet?.url)"))
        assertTrue(body.contains("var path by rememberSaveable(initialRuleSet?.path)"))
        assertTrue(confirmBody.contains("tag = tag.trim()"))
        assertTrue(confirmBody.contains("url = url.trim()"))
        assertTrue(confirmBody.contains("path = path.trim()"))
    }

    @Test
    fun targetSelectionDialogGuardsMissingSelectionIndex() {
        val source = File("src/main/java/com/kunk/singbox/ui/screens/RuleSetsScreen.kt").readText()

        assertTrue(source.contains("val selectedIndex = targetOptions.indexOfFirst"))
        assertTrue(source.contains("selectedIndex = selectedIndex.coerceAtLeast(0)"))
        assertTrue(
            source.contains("val selectedValue = targetOptions.getOrNull(index)?.second ?: return@SingleSelectDialog")
        )
    }
}
