package com.kunk.singbox.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSelectionSyncTest {

    @Test
    fun confirmKeepsUninstalledPackagesFromOriginalSelection() {
        val result = resolveVisibleSelectedPackages(
            selectedPackages = setOf("com.installed"),
            visiblePackages = setOf("com.installed"),
            originalSelectedPackages = setOf("com.installed", "com.uninstalled")
        )

        assertEquals(listOf("com.installed", "com.uninstalled"), result)
    }

    @Test
    fun confirmRemovesUncheckedVisiblePackages() {
        val result = resolveVisibleSelectedPackages(
            selectedPackages = setOf("com.kept"),
            visiblePackages = setOf("com.kept", "com.unchecked"),
            originalSelectedPackages = setOf("com.kept", "com.unchecked", "com.uninstalled")
        )

        assertEquals(listOf("com.kept", "com.uninstalled"), result)
    }
}
