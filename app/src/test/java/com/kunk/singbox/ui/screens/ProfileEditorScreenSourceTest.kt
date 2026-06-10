package com.kunk.singbox.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileEditorScreenSourceTest {

    @Test
    fun profileEditorKeepsLargeDraftInViewModelAcrossRecreation() {
        val source = File("src/main/java/com/kunk/singbox/ui/screens/ProfileEditorScreen.kt").readText()

        assertTrue(source.contains("import androidx.lifecycle.ViewModel"))
        assertTrue(source.contains("import androidx.lifecycle.viewmodel.compose.viewModel"))
        assertTrue(source.contains("private class ProfileEditorViewModel : ViewModel()"))
        assertTrue(source.contains("val editorViewModel: ProfileEditorViewModel = viewModel(key = profileId)"))
        assertTrue(source.contains("editorViewModel.shouldLoad(profileId)"))
        assertTrue(source.contains("editorViewModel.updateContent(it)"))
        assertFalse(source.contains("var content by remember { mutableStateOf(\"\") }"))
        assertFalse(source.contains("rememberSaveable"))
    }
}
