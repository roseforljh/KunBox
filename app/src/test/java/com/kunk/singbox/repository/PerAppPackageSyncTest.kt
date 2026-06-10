package com.kunk.singbox.repository

import android.content.Intent
import com.kunk.singbox.model.AppGroup
import com.kunk.singbox.model.AppInfo
import com.kunk.singbox.model.AppRule
import com.kunk.singbox.model.AppSettings
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
            installedPackages = setOf("com.installed")
        )

        assertEquals("com.installed", result)
    }

    @Test
    fun sanitizePackageList_doesNotDropPackagesWhenInstalledAppSnapshotIsEmpty() {
        val result = sanitizePackageList(
            value = "com.selected",
            installedPackages = emptySet()
        )

        assertEquals("com.selected", result)
    }

    @Test
    fun packageChangeEvent_refreshesAddedAndNonReplacingRemovedPackages() {
        assertTrue(
            shouldReloadInstalledAppsForPackageChange(
                action = Intent.ACTION_PACKAGE_ADDED,
                isReplacing = false,
                packageName = "com.example"
            )
        )
        assertTrue(
            shouldReloadInstalledAppsForPackageChange(
                action = Intent.ACTION_PACKAGE_REMOVED,
                isReplacing = false,
                packageName = "com.example"
            )
        )
        assertFalse(
            shouldReloadInstalledAppsForPackageChange(
                action = Intent.ACTION_PACKAGE_REMOVED,
                isReplacing = true,
                packageName = "com.example"
            )
        )
        assertTrue(
            shouldReloadInstalledAppsForPackageChange(
                action = Intent.ACTION_PACKAGE_ADDED,
                isReplacing = true,
                packageName = "com.example"
            )
        )
        assertFalse(
            shouldReloadInstalledAppsForPackageChange(
                action = Intent.ACTION_PACKAGE_ADDED,
                isReplacing = false,
                packageName = ""
            )
        )
        assertFalse(
            shouldReloadInstalledAppsForPackageChange(
                action = "android.intent.action.PACKAGE_CHANGED",
                isReplacing = false,
                packageName = "com.example"
            )
        )
    }

    @Test
    fun removePackageFromPerAppSettings_cleansListsRulesAndGroups() {
        val settings = AppSettings(
            vpnAllowlist = "com.keep\ncom.removed",
            vpnBlocklist = "com.removed\ncom.blocked",
            appRules = listOf(
                AppRule(packageName = "com.removed", appName = "Removed"),
                AppRule(packageName = "com.keep", appName = "Keep")
            ),
            appGroups = listOf(
                AppGroup(
                    name = "group",
                    apps = listOf(
                        AppInfo(packageName = "com.removed", appName = "Removed"),
                        AppInfo(packageName = "com.keep", appName = "Keep")
                    )
                )
            )
        )

        val result = removePackageFromPerAppSettings(settings, "com.removed")

        assertEquals("com.keep", result.vpnAllowlist)
        assertEquals("com.blocked", result.vpnBlocklist)
        assertEquals(listOf("com.keep"), result.appRules.map { it.packageName })
        assertEquals(listOf("com.keep"), result.appGroups.first().apps.map { it.packageName })
    }
}
