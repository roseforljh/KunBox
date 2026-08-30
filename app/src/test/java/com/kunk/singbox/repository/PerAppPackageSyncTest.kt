package com.kunk.singbox.repository

import android.content.Intent
import com.kunk.singbox.model.AppGroup
import com.kunk.singbox.model.AppInfo
import com.kunk.singbox.model.AppRule
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.CustomRule
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.model.RuleSetType
import com.kunk.singbox.model.RuleType
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

    @Test
    @Suppress("LongMethod")
    fun removeDeletedRoutingReferences_cleansEveryRoutingRuleFamily() {
        val settings = AppSettings(
            appRules = listOf(
                AppRule(
                    id = "app-profile",
                    packageName = "com.deleted.profile",
                    appName = "Deleted profile",
                    outboundMode = RuleSetOutboundMode.PROFILE,
                    outboundValue = "profile-deleted"
                ),
                AppRule(
                    id = "app-node",
                    packageName = "com.deleted.node",
                    appName = "Deleted node",
                    outboundMode = RuleSetOutboundMode.NODE,
                    outboundValue = "node-deleted"
                ),
                AppRule(
                    id = "app-live",
                    packageName = "com.live",
                    appName = "Live",
                    outboundMode = RuleSetOutboundMode.PROFILE,
                    outboundValue = "profile-live"
                )
            ),
            appGroups = listOf(
                AppGroup(
                    id = "group-deleted",
                    name = "Deleted group",
                    outboundMode = RuleSetOutboundMode.PROFILE,
                    outboundValue = "profile-deleted"
                ),
                AppGroup(id = "group-live", name = "Live group")
            ),
            customRules = listOf(
                CustomRule(
                    id = "custom-qualified-node",
                    name = "Qualified node",
                    type = RuleType.DOMAIN,
                    value = "deleted.example",
                    outboundMode = RuleSetOutboundMode.NODE,
                    outboundValue = "profile-deleted::Residential"
                ),
                CustomRule(
                    id = "custom-profile-tag",
                    name = "Old profile tag",
                    type = RuleType.DOMAIN,
                    value = "old.example",
                    outboundMode = RuleSetOutboundMode.PROXY,
                    outboundValue = "P:Old#profile-deleted"
                ),
                CustomRule(
                    id = "custom-live",
                    name = "Live rule",
                    type = RuleType.DOMAIN,
                    value = "live.example",
                    outboundMode = RuleSetOutboundMode.PROXY
                )
            ),
            ruleSets = listOf(
                RuleSet(
                    id = "ruleset-deleted",
                    tag = "deleted-set",
                    type = RuleSetType.LOCAL,
                    outboundMode = RuleSetOutboundMode.PROFILE,
                    outboundValue = "profile-deleted"
                ),
                RuleSet(
                    id = "ruleset-live",
                    tag = "live-set",
                    type = RuleSetType.LOCAL,
                    outboundMode = RuleSetOutboundMode.PROXY
                )
            )
        )

        val result = removeDeletedRoutingReferences(
            settings = settings,
            deletedProfileId = "profile-deleted",
            deletedNodeIds = setOf("node-deleted"),
            deletedNodeReferences = setOf("profile-deleted::Residential")
        )

        assertEquals(listOf("app-live"), result.appRules.map { it.id })
        assertEquals(listOf("group-live"), result.appGroups.map { it.id })
        assertEquals(listOf("custom-live"), result.customRules.map { it.id })
        assertEquals(listOf("ruleset-live"), result.ruleSets.map { it.id })
    }

    @Test
    fun migrateNodeRoutingReferences_updatesIdsQualifiedNamesAndUniqueBareNames() {
        val settings = AppSettings(
            customNodeOrder = listOf("old-id", "keep-id"),
            appRules = listOf(
                AppRule(
                    packageName = "com.id",
                    appName = "ID",
                    outboundMode = RuleSetOutboundMode.NODE,
                    outboundValue = "old-id"
                )
            ),
            appGroups = listOf(
                AppGroup(
                    name = "Qualified",
                    outboundMode = RuleSetOutboundMode.NODE,
                    outboundValue = "profile-a::Old"
                )
            ),
            customRules = listOf(
                CustomRule(
                    name = "Bare",
                    type = RuleType.DOMAIN,
                    value = "example.com",
                    outboundMode = RuleSetOutboundMode.NODE,
                    outboundValue = "Old"
                )
            )
        )

        val result = migrateNodeRoutingReferences(
            settings = settings,
            oldNodeId = "old-id",
            newNodeId = "new-id",
            oldQualifiedReference = "profile-a::Old",
            newQualifiedReference = "profile-a::New",
            oldBareReference = "Old",
            newBareReference = "New",
            oldBareNameIsUnique = true,
            newBareNameIsUnique = true
        )

        assertEquals(listOf("new-id", "keep-id"), result.customNodeOrder)
        assertEquals("new-id", result.appRules.single().outboundValue)
        assertEquals("profile-a::New", result.appGroups.single().outboundValue)
        assertEquals("New", result.customRules.single().outboundValue)
    }

    @Test
    fun migrateNodeRoutingReferences_doesNotHijackAmbiguousBareName() {
        val settings = AppSettings(
            appRules = listOf(
                AppRule(
                    packageName = "com.shared",
                    appName = "Shared",
                    outboundMode = RuleSetOutboundMode.NODE,
                    outboundValue = "Shared"
                )
            )
        )

        val result = migrateNodeRoutingReferences(
            settings = settings,
            oldNodeId = "old-id",
            newNodeId = "new-id",
            oldQualifiedReference = "profile-a::Shared",
            newQualifiedReference = "profile-a::Renamed",
            oldBareReference = "Shared",
            newBareReference = "Renamed",
            oldBareNameIsUnique = false,
            newBareNameIsUnique = true
        )

        assertEquals("Shared", result.appRules.single().outboundValue)
    }

    @Test
    fun migrateNodeRoutingReferences_qualifiesNewNameWhenItWouldBeAmbiguous() {
        val settings = AppSettings(
            appRules = listOf(
                AppRule(
                    packageName = "com.old",
                    appName = "Old",
                    outboundMode = RuleSetOutboundMode.NODE,
                    outboundValue = "Old"
                )
            )
        )

        val result = migrateNodeRoutingReferences(
            settings = settings,
            oldNodeId = "old-id",
            newNodeId = "new-id",
            oldQualifiedReference = "profile-a::Old",
            newQualifiedReference = "profile-a::Shared",
            oldBareReference = "Old",
            newBareReference = "Shared",
            oldBareNameIsUnique = true,
            newBareNameIsUnique = false
        )

        assertEquals("profile-a::Shared", result.appRules.single().outboundValue)
    }

    @Test
    fun removeDeletedRoutingReferences_keepsSameNameNodeFromAnotherProfile() {
        val settings = AppSettings(
            appRules = listOf(
                AppRule(
                    id = "ambiguous-bare",
                    packageName = "com.shared",
                    appName = "Shared",
                    outboundMode = RuleSetOutboundMode.NODE,
                    outboundValue = "Shared"
                ),
                AppRule(
                    id = "deleted-qualified",
                    packageName = "com.deleted",
                    appName = "Deleted",
                    outboundMode = RuleSetOutboundMode.NODE,
                    outboundValue = "profile-a::Shared"
                )
            )
        )

        val result = removeDeletedRoutingReferences(
            settings = settings,
            deletedNodeIds = setOf("deleted-id"),
            deletedNodeReferences = setOf("profile-a::Shared")
        )

        assertEquals(listOf("ambiguous-bare"), result.appRules.map { it.id })
    }

    @Test
    fun removeInvalidRoutingReferences_repairsHistoricalStaleTargets() {
        val settings = AppSettings(
            customNodeOrder = listOf("live-node", "gone-node"),
            appRules = listOf(
                AppRule(
                    id = "stale-profile",
                    packageName = "com.stale",
                    appName = "Stale",
                    outboundMode = RuleSetOutboundMode.PROFILE,
                    outboundValue = "gone-profile"
                ),
                AppRule(
                    id = "live-node",
                    packageName = "com.live",
                    appName = "Live",
                    outboundMode = RuleSetOutboundMode.NODE,
                    outboundValue = "live-node"
                )
            ),
            customRules = listOf(
                CustomRule(
                    id = "legacy-profile",
                    name = "Legacy",
                    type = RuleType.DOMAIN,
                    value = "legacy.example",
                    outboundMode = RuleSetOutboundMode.PROXY,
                    outboundValue = "P:Old#gone-profile"
                ),
                CustomRule(
                    id = "legacy-live-profile",
                    name = "Legacy live",
                    type = RuleType.DOMAIN,
                    value = "live.example",
                    outboundMode = RuleSetOutboundMode.PROXY,
                    outboundValue = "P:Live#live-profile"
                )
            )
        )

        val result = removeInvalidRoutingReferences(
            settings = settings,
            validProfileIds = setOf("live-profile"),
            validNodeIds = setOf("live-node"),
            validQualifiedNodeReferences = setOf("live-profile::Live"),
            validBareNodeNames = setOf("Live")
        )

        assertEquals(listOf("live-node"), result.customNodeOrder)
        assertEquals(listOf("live-node"), result.appRules.map { it.id })
        assertEquals(listOf("legacy-live-profile"), result.customRules.map { it.id })
        assertEquals(RuleSetOutboundMode.PROFILE, result.customRules.single().outboundMode)
        assertEquals("live-profile", result.customRules.single().outboundValue)
    }

    @Test
    fun removeUninstalledPackagesFromPerAppSettings_cleansEveryPersistedReference() {
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

        val result = removeUninstalledPackagesFromPerAppSettings(
            settings,
            setOf("com.keep", "com.blocked")
        )

        assertEquals("com.keep", result.vpnAllowlist)
        assertEquals("com.blocked", result.vpnBlocklist)
        assertEquals(listOf("com.keep"), result.appRules.map { it.packageName })
        assertEquals(listOf("com.keep"), result.appGroups.single().apps.map { it.packageName })
    }

    @Test
    fun latestGroupAssignmentMovesAppsOutOfPreviousConfigurations() {
        val settings = AppSettings(
            appRules = listOf(
                AppRule(id = "rule-shared", packageName = "com.shared", appName = "Shared"),
                AppRule(id = "rule-keep", packageName = "com.keep.rule", appName = "Keep Rule")
            ),
            appGroups = listOf(
                AppGroup(
                    id = "group-a",
                    name = "A",
                    apps = listOf(
                        AppInfo(packageName = "com.shared", appName = "Shared"),
                        AppInfo(packageName = "com.keep.group", appName = "Keep Group")
                    )
                ),
                AppGroup(id = "group-b", name = "B", apps = listOf(AppInfo("com.old", "Old")))
            )
        )
        val latest = AppGroup(
            id = "group-b",
            name = "B",
            apps = listOf(AppInfo(packageName = "com.shared", appName = "Shared"))
        )

        val result = upsertExclusiveAppGroup(settings, latest)

        assertEquals(listOf("com.keep.rule"), result.appRules.map { it.packageName })
        assertEquals(
            listOf("com.keep.group"),
            result.appGroups.first { it.id == "group-a" }.apps.map { it.packageName }
        )
        assertEquals(latest, result.appGroups.first { it.id == "group-b" })
        assertEquals(1, result.appGroups.sumOf { group -> group.apps.count { it.packageName == "com.shared" } })
    }

    @Test
    fun normalizeExclusiveAssignmentsKeepsLatestConfigurationForEachApp() {
        val settings = AppSettings(
            appRules = listOf(
                AppRule(id = "rule-old", packageName = "com.rule", appName = "Old rule"),
                AppRule(id = "rule-latest", packageName = " com.rule ", appName = "Latest rule"),
                AppRule(id = "rule-cross", packageName = "com.cross", appName = "Cross")
            ),
            appGroups = listOf(
                AppGroup(
                    id = "group-a",
                    name = "A",
                    apps = listOf(
                        AppInfo(packageName = "com.cross", appName = "Cross"),
                        AppInfo(packageName = "com.moved", appName = "Moved from A")
                    )
                ),
                AppGroup(
                    id = "group-b",
                    name = "B",
                    apps = listOf(
                        AppInfo(packageName = "com.moved", appName = "Older in B"),
                        AppInfo(packageName = " com.moved ", appName = "Latest in B")
                    )
                )
            )
        )

        val result = normalizeExclusiveAppAssignments(settings)

        assertEquals(listOf("com.rule"), result.appRules.map { it.packageName })
        assertEquals("Latest rule", result.appRules.single().appName)
        assertEquals(listOf("com.cross"), result.appGroups[0].apps.map { it.packageName })
        assertEquals(listOf("com.moved"), result.appGroups[1].apps.map { it.packageName })
        assertEquals("Latest in B", result.appGroups[1].apps.single().appName)
    }
}
