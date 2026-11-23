package com.kunk.singbox.repository

import com.kunk.singbox.model.AppGroup
import com.kunk.singbox.model.AppInfo
import com.kunk.singbox.model.AppRule
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.CustomRule
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.model.RuleSetType
import com.kunk.singbox.model.RuleType
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingReferenceSyncTest {

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

        // 规则本身必须保留，只把失效目标清回默认代理。
        assertEquals(
            listOf("app-profile", "app-node", "app-live"),
            result.appRules.map { it.id }
        )
        assertEquals(
            listOf(RuleSetOutboundMode.PROXY, RuleSetOutboundMode.PROXY, RuleSetOutboundMode.PROFILE),
            result.appRules.map { it.outboundMode }
        )
        assertEquals(listOf(null, null, "profile-live"), result.appRules.map { it.outboundValue })

        assertEquals(listOf("group-deleted", "group-live"), result.appGroups.map { it.id })
        assertEquals(listOf(null, null), result.appGroups.map { it.outboundValue })

        assertEquals(
            listOf("custom-qualified-node", "custom-profile-tag", "custom-live"),
            result.customRules.map { it.id }
        )
        assertEquals(listOf(null, null, null), result.customRules.map { it.outboundValue })

        assertEquals(listOf("ruleset-deleted", "ruleset-live"), result.ruleSets.map { it.id })
        assertEquals(listOf(null, null), result.ruleSets.map { it.outboundValue })
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

        // 两条规则都保留；只有指向已删节点的目标被清空。
        assertEquals(listOf("ambiguous-bare", "deleted-qualified"), result.appRules.map { it.id })
        assertEquals("Shared", result.appRules[0].outboundValue)
        assertEquals(RuleSetOutboundMode.NODE, result.appRules[0].outboundMode)
        assertEquals(null, result.appRules[1].outboundValue)
        assertEquals(RuleSetOutboundMode.PROXY, result.appRules[1].outboundMode)
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
        // 失效目标只清空指向，规则本身保留。
        assertEquals(listOf("stale-profile", "live-node"), result.appRules.map { it.id })
        assertEquals(null, result.appRules[0].outboundValue)
        assertEquals(RuleSetOutboundMode.PROXY, result.appRules[0].outboundMode)
        assertEquals("live-node", result.appRules[1].outboundValue)

        assertEquals(listOf("legacy-profile", "legacy-live-profile"), result.customRules.map { it.id })
        assertEquals(null, result.customRules[0].outboundValue)
        assertEquals(RuleSetOutboundMode.PROXY, result.customRules[0].outboundMode)
        assertEquals(RuleSetOutboundMode.PROFILE, result.customRules[1].outboundMode)
        assertEquals("live-profile", result.customRules[1].outboundValue)
    }

    @Test
    fun migrateProfileRoutingReferences_repointsImportedProfileRulesToNewId() {
        val settings = AppSettings(
            appRules = listOf(
                AppRule(
                    id = "app-profile",
                    packageName = "com.profile",
                    appName = "Profile",
                    outboundMode = RuleSetOutboundMode.PROFILE,
                    outboundValue = "old-profile-id"
                ),
                AppRule(
                    id = "app-node",
                    packageName = "com.node",
                    appName = "Node",
                    outboundMode = RuleSetOutboundMode.NODE,
                    outboundValue = "old-profile-id::US Node"
                )
            ),
            ruleSets = listOf(
                RuleSet(
                    id = "ruleset",
                    tag = "geosite-google",
                    type = RuleSetType.REMOTE,
                    outboundMode = RuleSetOutboundMode.PROFILE,
                    outboundValue = "old-profile-id"
                )
            ),
            customRules = listOf(
                CustomRule(
                    id = "legacy",
                    name = "Legacy",
                    type = RuleType.DOMAIN,
                    value = "legacy.example",
                    outboundMode = RuleSetOutboundMode.PROXY,
                    outboundValue = "P:Old#old-profile-id"
                )
            )
        )

        val result = migrateProfileRoutingReferences(
            settings = settings,
            oldProfileId = "old-profile-id",
            newProfileId = "new-profile-id"
        )

        assertEquals("new-profile-id", result.appRules[0].outboundValue)
        assertEquals(RuleSetOutboundMode.PROFILE, result.appRules[0].outboundMode)
        assertEquals("new-profile-id::US Node", result.appRules[1].outboundValue)
        assertEquals(RuleSetOutboundMode.NODE, result.appRules[1].outboundMode)
        assertEquals("new-profile-id", result.ruleSets.single().outboundValue)
        assertEquals(RuleSetOutboundMode.PROFILE, result.ruleSets.single().outboundMode)
        assertEquals("new-profile-id", result.customRules.single().outboundValue)
        assertEquals(RuleSetOutboundMode.PROFILE, result.customRules.single().outboundMode)
    }

    @Test
    fun migrateProfileRoutingReferences_remapsNodeUuidReferences() {
        val uuidSettings = AppSettings(
            appRules = listOf(
                AppRule(
                    id = "app-uuid",
                    packageName = "com.uuid",
                    appName = "Uuid",
                    outboundMode = RuleSetOutboundMode.NODE,
                    outboundValue = "old-node-uuid"
                )
            )
        )
        val remapped = migrateProfileRoutingReferences(
            settings = uuidSettings,
            oldProfileId = "old-profile-id",
            newProfileId = "new-profile-id",
            nodeIdMapping = mapOf("old-node-uuid" to "new-node-uuid")
        )
        assertEquals("new-node-uuid", remapped.appRules.single().outboundValue)
    }

    /** 真实事故快照：指向的配置已被删掉时，规则集和应用分组不能跟着消失。 */
    @Test
    fun removeInvalidRoutingReferences_keepsRulesWhenTheirProfileIsGone() {
        val settings = AppSettings(
            ruleSets = listOf(
                RuleSet(
                    id = "rs-google",
                    tag = "geosite-google",
                    type = RuleSetType.REMOTE,
                    outboundMode = RuleSetOutboundMode.NODE,
                    outboundValue = "973db114-c4e6-4b21-aa0f-b466fa75faf1::🇺🇸 VL | 美国 1.1"
                )
            ),
            appGroups = listOf(
                AppGroup(
                    id = "group-ex",
                    name = "交易所",
                    apps = listOf(AppInfo(packageName = "com.binance.dev", appName = "Binance")),
                    outboundMode = RuleSetOutboundMode.NODE,
                    outboundValue = "973db114-c4e6-4b21-aa0f-b466fa75faf1::🇩🇪 VL | 德国 9929"
                ),
                AppGroup(
                    id = "group-media",
                    name = "流媒体",
                    apps = listOf(AppInfo(packageName = "com.google.android.youtube", appName = "YouTube")),
                    outboundMode = RuleSetOutboundMode.PROFILE,
                    outboundValue = "a786fbb0-ae7e-4033-b59b-66ea007e85ad"
                )
            )
        )

        // 该配置已不存在：validProfileIds 里没有它，节点快照里也没有它的节点。
        val result = removeInvalidRoutingReferences(
            settings = settings,
            validProfileIds = setOf("7900406b-bee5-4257-be93-2d29d21020b8"),
            validNodeIds = setOf("4e3fb521-898c-3a36-bc00-7fecad308ddd"),
            validQualifiedNodeReferences = setOf("7900406b-bee5-4257-be93-2d29d21020b8::🇺🇸 VL | 美国 LAX (RayBox)"),
            validBareNodeNames = setOf("🇺🇸 VL | 美国 LAX (RayBox)")
        )

        assertEquals(1, result.ruleSets.size)
        assertEquals("geosite-google", result.ruleSets.single().tag)
        assertEquals(RuleSetOutboundMode.PROXY, result.ruleSets.single().outboundMode)
        assertEquals(null, result.ruleSets.single().outboundValue)

        assertEquals(listOf("group-ex", "group-media"), result.appGroups.map { it.id })
        assertEquals(RuleSetOutboundMode.PROXY, result.appGroups[0].outboundMode)
        assertEquals(null, result.appGroups[0].outboundValue)
        assertEquals(RuleSetOutboundMode.PROXY, result.appGroups[1].outboundMode)
        assertEquals(null, result.appGroups[1].outboundValue)
        // 分组的应用成员必须原样保留。
        assertEquals(listOf("com.binance.dev"), result.appGroups[0].apps.map { it.packageName })
        assertEquals(listOf("com.google.android.youtube"), result.appGroups[1].apps.map { it.packageName })
    }
}
