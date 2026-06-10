package com.kunk.singbox.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsViewModelSourceTest {

    @Test
    fun importResultUsesAllOrNothingStatesOnly() {
        val model = File("src/main/java/com/kunk/singbox/model/ExportData.kt").readText()
        val viewModel = File("src/main/java/com/kunk/singbox/viewmodel/SettingsViewModel.kt").readText()
        val dialog = File("src/main/java/com/kunk/singbox/ui/components/ExportImportDialogs.kt").readText()
        val repository = File("src/main/java/com/kunk/singbox/repository/DataExportRepository.kt").readText()

        assertTrue(repository.contains("if (errors.isNotEmpty())"))
        assertTrue(repository.contains("ImportResult.Failed(errors.joinToString"))
        assertFalse(model.contains("PartialSuccess"))
        assertFalse(viewModel.contains("PartialSuccess"))
        assertFalse(dialog.contains("PartialSuccess"))
    }

    @Test
    fun ruleSetDownloadingStateUsesAtomicFlowUpdates() {
        val source = File("src/main/java/com/kunk/singbox/viewmodel/SettingsViewModel.kt").readText()
        val helpers = source.substringAfter("private fun markRuleSetDownloading")
            .substringBefore("fun setAutoConnect")

        assertTrue(source.contains("import kotlinx.coroutines.flow.update"))
        assertTrue(helpers.contains("_downloadingRuleSets.update { it + tag }"))
        assertTrue(helpers.contains("_downloadingRuleSets.update { it - tag }"))
        assertTrue(helpers.contains("private fun tryMarkRuleSetDownloading(tag: String): Boolean"))
        assertTrue(helpers.contains("if (current.contains(tag))"))
        assertFalse(source.contains("_downloadingRuleSets.value += "))
        assertFalse(source.contains("_downloadingRuleSets.value -= "))
        assertFalse(source.contains("!_downloadingRuleSets.value.contains"))
    }
}
