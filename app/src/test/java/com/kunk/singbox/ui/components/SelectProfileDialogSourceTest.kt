package com.kunk.singbox.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SelectProfileDialogSourceTest {

    @Test
    fun selectProfileDialogSavesDraftAcrossRecreation() {
        val source = File("src/main/java/com/kunk/singbox/ui/components/SelectProfileDialog.kt").readText()
        val body = source.substring(
            source.indexOf("fun SelectProfileDialog("),
            source.indexOf("LaunchedEffect(profiles)")
        )

        assertTrue(source.contains("import androidx.compose.runtime.saveable.rememberSaveable"))
        assertTrue(body.contains("var isCreatingNew by rememberSaveable"))
        assertTrue(body.contains("var newProfileName by rememberSaveable"))
        assertTrue(body.contains("var selectedProfileId by rememberSaveable"))
    }
}
