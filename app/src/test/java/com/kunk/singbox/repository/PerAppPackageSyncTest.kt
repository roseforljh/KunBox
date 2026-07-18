package com.kunk.singbox.repository

import android.content.Intent
import com.kunk.singbox.model.AppGroup
import com.kunk.singbox.model.AppInfo
import com.kunk.singbox.model.AppRule
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.VpnAppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppPackageSyncTest {

    @Test
    fun addPackageToList_normalizesAndDeduplicatesPackageNames() {
        val result = addPackageToList(
            value = "com.existing, com.duplicate\ncom.duplicate",
            packageName = "com.new"
        )

        assertEquals("com.existing\ncom.duplicate\ncom.new", result)
    }

    @Test
    fun newPackage_followsAllowlistWhenEnabled() {
        val settings = AppSettings(
            vpnAppMode = VpnAppMode.ALLOWLIST,
            vpnAllowlist = "com.existing",
            vpnBlocklist = "com.blocked",
            autoIncludeNewAppsInPerAppRules = true
        )

        val result = addPackageToCurrentPerAppRule(settings, "com.new")

        assertEquals("com.existing\ncom.new", result.vpnAllowlist)
        assertEquals("com.blocked", result.vpnBlocklist)
    }

    @Test
    fun newPackage_followsBlocklistWhenEnabled() {
        val settings = AppSettings(
            vpnAppMode = VpnAppMode.BLOCKLIST,
            vpnAllowlist = "com.allowed",
            vpnBlocklist = "com.existing",
            autoIncludeNewAppsInPerAppRules = true
        )

        val result = addPackageToCurrentPerAppRule(settings, "com.new")

        assertEquals("com.allowed", result.vpnAllowlist)
        assertEquals("com.existing\ncom.new", result.vpnBlocklist)
    }

    @Test
    fun newPackage_doesNotChangeRulesWhenSwitchDisabledOrModeIsAll() {
        val disabled = AppSettings(
            vpnAppMode = VpnAppMode.ALLOWLIST,
            vpnAllowlist = "com.existing"
        )
        val allApps = AppSettings(
            vpnAppMode = VpnAppMode.ALL,
            autoIncludeNewAppsInPerAppRules = true
        )

        assertEquals(disabled, addPackageToCurrentPerAppRule(disabled, "com.new"))
        assertEquals(allApps, addPackageToCurrentPerAppRule(allApps, "com.new"))
    }

    @Test
    fun freshInstalledPackageIsAddedToCurrentRule() {
        assertEquals(
            PerAppPackageSyncAction.ADD,
            resolvePerAppPackageSyncAction(
                action = Intent.ACTION_PACKAGE_ADDED,
                isReplacing = false,
                packageName = "com.new",
                isInstalled = true
            )
        )
    }

    @Test
    fun addEventForPackageThatIsAlreadyAbsentRemovesStaleRule() {
        assertEquals(
            PerAppPackageSyncAction.REMOVE,
            resolvePerAppPackageSyncAction(
                action = Intent.ACTION_PACKAGE_ADDED,
                isReplacing = false,
                packageName = "com.removed",
                isInstalled = false
            )
        )
    }

    @Test
    fun removeEventForReinstalledPackageWaitsForAddEvent() {
        assertEquals(
            PerAppPackageSyncAction.NONE,
            resolvePerAppPackageSyncAction(
                action = Intent.ACTION_PACKAGE_REMOVED,
                isReplacing = false,
                packageName = "com.reinstalled",
                isInstalled = true
            )
        )
    }

    @Test
    fun removeEventForAbsentPackageRemovesStaleRule() {
        assertEquals(
            PerAppPackageSyncAction.REMOVE,
            resolvePerAppPackageSyncAction(
                action = Intent.ACTION_PACKAGE_REMOVED,
                isReplacing = false,
                packageName = "com.removed",
                isInstalled = false
            )
        )
    }

    @Test
    fun packageUpdateOnlyRefreshesInstalledAppMetadata() {
        assertEquals(
            PerAppPackageSyncAction.NONE,
            resolvePerAppPackageSyncAction(
                action = Intent.ACTION_PACKAGE_ADDED,
                isReplacing = true,
                packageName = "com.updated",
                isInstalled = true
            )
        )
    }

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
