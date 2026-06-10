package com.kunk.singbox.repository

import org.junit.Assert.assertTrue
import org.junit.Test

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
}
