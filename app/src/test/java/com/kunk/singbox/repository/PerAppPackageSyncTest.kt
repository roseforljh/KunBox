package com.kunk.singbox.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppPackageSyncTest {

    @Test
    fun removePackageFromList_removesOnlyExactPackageName() {
        val result = removePackageFromList(
            value = "com.example.app\ncom.example.app2\ncom.other",
            packageName = "com.example.app"
        )

        assertEquals("com.example.app2\ncom.other", result)
    }

    @Test
    fun sanitizePackageList_keepsOnlyInstalledPackageNames() {
        val result = sanitizePackageList(
            value = "com.installed\ncom.removed",
            isPackageInstalled = { it == "com.installed" }
        )

        assertEquals("com.installed", result)
    }

    @Test
    fun packageRemovedEvent_skipsReplacingUpdates() {
        assertFalse(shouldSyncRemovedPackage(isReplacing = true, packageName = "com.example"))
        assertTrue(shouldSyncRemovedPackage(isReplacing = false, packageName = "com.example"))
        assertFalse(shouldSyncRemovedPackage(isReplacing = false, packageName = ""))
    }
}
