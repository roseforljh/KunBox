package com.kunk.singbox.viewmodel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfilesViewModelSourceTest {

    @Test
    fun deleteProfileCallsSuspendRepositoryFromViewModelScope() {
        val source = readProfilesViewModelSource()
        val function = source.substringAfter("fun deleteProfile(profileId: String)")
            .substringBefore("fun reorderProfiles")

        assertTrue(function.contains("viewModelScope.launch"))
        assertTrue(function.contains("configRepository.deleteProfile(profileId)"))
    }

    @Test
    fun metadataSaveAndImportSuccessEmitDnsOverrideCompatibilityWarning() {
        val source = readProfilesViewModelSource()
        val updateBody = source.substring(
            source.indexOf("fun updateProfileMetadata("),
            source.indexOf("@Suppress(\"CognitiveComplexMethod\")")
        )
        val importBody = source.substring(
            source.indexOf("fun importSubscription("),
            source.indexOf("fun createCustomConfig(")
        )

        assertTrue(source.contains("ConfigRepository.buildDnsOverrideCompatibilityWarning(dnsOverride)"))
        assertTrue(updateBody.contains("emitDnsOverrideCompatibilityWarning(dnsOverride)"))
        assertTrue(importBody.contains("emitDnsOverrideCompatibilityWarning(profile.dnsOverride)"))
    }

    private fun readProfilesViewModelSource(): String {
        val candidates = listOf(
            File("src/main/java/com/kunk/singbox/viewmodel/ProfilesViewModel.kt"),
            File("app/src/main/java/com/kunk/singbox/viewmodel/ProfilesViewModel.kt")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("ProfilesViewModel.kt not found from ${File(".").absolutePath}")
    }
}
