package com.kunk.singbox.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LogExportSafetyTest {
    @Test
    fun exportSharesAFileInsteadOfPuttingLogsInTheBinderPayload() {
        val screen = source("java/com/kunk/singbox/ui/screens/LogsScreen.kt")
        val viewModel = source("java/com/kunk/singbox/viewmodel/LogViewModel.kt")
        val manifest = source("AndroidManifest.xml")
        val providerPaths = File("src/main/res/xml/file_paths.xml")

        assertTrue(screen.contains("Intent.EXTRA_STREAM"))
        assertFalse(screen.contains("Intent.EXTRA_TEXT"))
        assertTrue(screen.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue(viewModel.contains("FileProvider.getUriForFile"))
        assertTrue(viewModel.contains("writeText(logs, Charsets.UTF_8)"))
        assertTrue(manifest.contains("androidx.core.content.FileProvider"))
        assertTrue(manifest.contains("@xml/file_paths"))
        assertTrue(providerPaths.exists())
        assertTrue(providerPaths.readText(Charsets.UTF_8).contains("path=\"log_exports/\""))
    }

    private fun source(path: String): String = File("src/main/$path").readText(Charsets.UTF_8)
}
