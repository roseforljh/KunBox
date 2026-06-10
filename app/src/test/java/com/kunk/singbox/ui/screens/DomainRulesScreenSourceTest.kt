package com.kunk.singbox.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DomainRulesScreenSourceTest {

    @Test
    fun domainRuleEditorSavesDraftAcrossRecreation() {
        val source = File("src/main/java/com/kunk/singbox/ui/screens/DomainRulesScreen.kt").readText()
        val body = source.substring(
            source.indexOf("private fun DomainRuleEditorDialog("),
            source.indexOf("var showOutboundDialog by remember")
        )

        assertTrue(source.contains("import androidx.compose.runtime.saveable.rememberSaveable"))
        assertTrue(body.contains("var value by rememberSaveable(initialRule?.value)"))
        assertTrue(
            body.contains("var outboundMode by rememberSaveable(initialRule?.outboundMode, initialRule?.outbound)")
        )
        assertTrue(body.contains("var outboundValue by rememberSaveable(initialRule?.outboundValue)"))
        assertTrue(source.contains("val rawValue = value.trim()"))
    }
}
