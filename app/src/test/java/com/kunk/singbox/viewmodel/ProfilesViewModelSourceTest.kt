package com.kunk.singbox.viewmodel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfilesViewModelSourceTest {
    @Test
    fun deleteProfileCallsSuspendRepositoryFromViewModelScope() {
        val source = File("src/main/java/com/kunk/singbox/viewmodel/ProfilesViewModel.kt").readText()
        val function = source.substringAfter("fun deleteProfile(profileId: String)")
            .substringBefore("fun reorderProfiles")

        assertTrue(function.contains("viewModelScope.launch"))
        assertTrue(function.contains("configRepository.deleteProfile(profileId)"))
    }
}
