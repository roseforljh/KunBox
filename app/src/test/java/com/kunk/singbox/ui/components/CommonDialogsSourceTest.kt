package com.kunk.singbox.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CommonDialogsSourceTest {
    @Test
    fun inputDialogSavesTextDraftAcrossRecreation() {
        val source = File("src/main/java/com/kunk/singbox/ui/components/CommonDialogs.kt").readText()
        val start = source.indexOf("fun InputDialog(")
        val body = source.substring(
            start,
            source.indexOf("Dialog(onDismissRequest = onDismiss)", start)
        )

        assertTrue(source.contains("import androidx.compose.runtime.saveable.rememberSaveable"))
        assertTrue(body.contains("var text by rememberSaveable(initialValue)"))
    }

    @Test
    fun singleSelectDialogDoesNotConfirmInvalidSelection() {
        val source = File("src/main/java/com/kunk/singbox/ui/components/CommonDialogs.kt").readText()
        val body = source.substring(
            source.indexOf("fun SingleSelectDialog("),
            source.indexOf("fun ProfileNodeSelectDialog(")
        )

        assertTrue(body.contains("val canConfirm = tempSelectedIndex in options.indices"))
        assertTrue(body.contains("enabled = canConfirm"))
        assertTrue(body.contains("if (canConfirm) onSelect(tempSelectedIndex)"))
    }
}
