package com.kunk.singbox.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSelectionSyncTest {

    @Test
    fun selectedPackages_keepsOnlyInstalledVisiblePackagesOnConfirm() {
        val result = resolveVisibleSelectedPackages(
            selectedPackages = setOf("com.installed", "com.removed"),
            visiblePackages = setOf("com.installed")
        )

        assertEquals(listOf("com.installed"), result)
    }
}
