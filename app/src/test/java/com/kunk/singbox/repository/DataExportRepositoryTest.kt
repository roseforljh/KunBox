package com.kunk.singbox.repository

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DataExportRepositoryTest {

    @Test
    fun `profile export completeness fails when any profile is skipped`() {
        val result = DataExportRepository.validateProfileExportCompletenessForTest(
            totalProfiles = 3,
            exportedProfiles = 2
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("1 profile"))
    }

    @Test
    fun `profile export completeness succeeds when all profiles are exported`() {
        val result = DataExportRepository.validateProfileExportCompletenessForTest(
            totalProfiles = 3,
            exportedProfiles = 3
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `settings export and rollback snapshots keep full theme style settings`() {
        val source = File("src/main/java/com/kunk/singbox/repository/DataExportRepository.kt").readText()

        assertTrue(source.contains("val settings = settingsRepository.settings.first()"))
        assertTrue(source.contains("settings = settings,"))
        assertTrue(source.contains("settings = settingsRepository.settings.first()"))
        assertTrue(source.contains("importSettings(exportData.settings, importRules = options.importRules)"))
    }
}
