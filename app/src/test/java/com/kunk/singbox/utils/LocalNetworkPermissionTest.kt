package com.kunk.singbox.utils

import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.AppGroup
import com.kunk.singbox.model.AppRule
import com.kunk.singbox.model.CustomRule
import com.kunk.singbox.model.DefaultRule
import com.kunk.singbox.model.Inbound
import com.kunk.singbox.model.OutboundTag
import com.kunk.singbox.model.RoutingMode
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.model.RuleSetType
import com.kunk.singbox.model.RuleType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkPermissionTest {

    @Test
    fun requiredPermissionsAreEmptyBeforeAndroid17() {
        assertArrayEquals(emptyArray<String>(), LocalNetworkPermission.requiredPermissions(sdkInt = 36))
    }

    @Test
    fun requiredPermissionsContainLocalNetworkOnAndroid17() {
        assertArrayEquals(
            arrayOf(LocalNetworkPermission.ACCESS_LOCAL_NETWORK),
            LocalNetworkPermission.requiredPermissions(sdkInt = 37)
        )
    }

    @Test
    fun canExposeLanAllowsDisabledLanWithoutPermission() {
        assertTrue(
            LocalNetworkPermission.canExposeLan(
                allowLan = false,
                sdkInt = 37,
                localNetworkPermissionGranted = false
            )
        )
    }

    @Test
    fun canExposeLanAllowsPreAndroid17WithoutPermission() {
        assertTrue(
            LocalNetworkPermission.canExposeLan(
                allowLan = true,
                sdkInt = 36,
                localNetworkPermissionGranted = false
            )
        )
    }

    @Test
    fun canExposeLanBlocksAndroid17WithoutPermission() {
        assertFalse(
            LocalNetworkPermission.canExposeLan(
                allowLan = true,
                sdkInt = 37,
                localNetworkPermissionGranted = false
            )
        )
    }

    @Test
    fun shouldRestrictLanListenOnlyOnAndroid17WithoutPermission() {
        assertFalse(
            LocalNetworkPermission.shouldRestrictLanListen(
                sdkInt = 36,
                localNetworkPermissionGranted = false
            )
        )
        assertFalse(
            LocalNetworkPermission.shouldRestrictLanListen(
                sdkInt = 37,
                localNetworkPermissionGranted = true
            )
        )
        assertTrue(
            LocalNetworkPermission.shouldRestrictLanListen(
                sdkInt = 37,
                localNetworkPermissionGranted = false
            )
        )
    }

    @Test
    fun settingsRequireLocalNetworkAccessForLanRelatedRouting() {
        assertTrue(LocalNetworkPermission.requiresLocalNetworkAccess(AppSettings(allowLan = true)))
        assertTrue(LocalNetworkPermission.requiresLocalNetworkAccess(AppSettings(bypassLan = true)))
        assertTrue(
            LocalNetworkPermission.requiresLocalNetworkAccess(
                AppSettings(
                    bypassLan = false,
                    routingMode = RoutingMode.GLOBAL_DIRECT
                )
            )
        )
        assertTrue(
            LocalNetworkPermission.requiresLocalNetworkAccess(
                AppSettings(
                    bypassLan = false,
                    routingMode = RoutingMode.RULE,
                    defaultRule = DefaultRule.DIRECT
                )
            )
        )
    }

    @Test
    fun defaultSettingsRequireLocalNetworkAccessBecauseBypassLanIsEnabled() {
        assertTrue(LocalNetworkPermission.requiresLocalNetworkAccess(AppSettings()))
    }

    @Test
    fun settingsRequireLocalNetworkAccessForDirectLocalCustomRules() {
        val settings = AppSettings(
            bypassLan = false,
            customRules = listOf(
                CustomRule(
                    name = "nas",
                    type = RuleType.IP_CIDR,
                    value = "192.168.1.0/24",
                    outbound = OutboundTag.DIRECT
                ),
                CustomRule(
                    name = "printer",
                    type = RuleType.DOMAIN_SUFFIX,
                    value = "printer.local\nrouter.lan\nhome.arpa\nnas.localdomain",
                    outbound = OutboundTag.DIRECT
                )
            )
        )

        assertTrue(LocalNetworkPermission.requiresLocalNetworkAccess(settings))
    }

    @Test
    fun settingsDoNotRequireLocalNetworkAccessForProxyOnlyRules() {
        val settings = AppSettings(
            bypassLan = false,
            customRules = listOf(
                CustomRule(
                    name = "remote",
                    type = RuleType.DOMAIN,
                    value = "example.com",
                    outbound = OutboundTag.PROXY
                )
            )
        )

        assertFalse(LocalNetworkPermission.requiresLocalNetworkAccess(settings))
    }

    @Test
    fun settingsRequireLocalNetworkAccessForDirectRuleSetsAppsAndGroups() {
        assertTrue(
            LocalNetworkPermission.requiresLocalNetworkAccess(
                AppSettings(
                    bypassLan = false,
                    ruleSets = listOf(
                        RuleSet(
                            tag = "private",
                            type = RuleSetType.LOCAL,
                            outboundMode = RuleSetOutboundMode.DIRECT
                        )
                    )
                )
            )
        )
        assertTrue(
            LocalNetworkPermission.requiresLocalNetworkAccess(
                AppSettings(
                    bypassLan = false,
                    appRules = listOf(
                        AppRule(
                            packageName = "com.example.local",
                            appName = "Local",
                            outboundMode = RuleSetOutboundMode.DIRECT
                        )
                    )
                )
            )
        )
        assertTrue(
            LocalNetworkPermission.requiresLocalNetworkAccess(
                AppSettings(
                    bypassLan = false,
                    appGroups = listOf(AppGroup(name = "Local Apps", outboundMode = null))
                )
            )
        )
    }

    @Test
    fun appRulesRequireLocalNetworkAccessOnlyWhenEnabledAndDirect() {
        assertTrue(
            LocalNetworkPermission.requiresLocalNetworkAccess(
                AppSettings(
                    bypassLan = false,
                    appRules = listOf(
                        AppRule(
                            packageName = "com.example.local",
                            appName = "Local",
                            outboundMode = RuleSetOutboundMode.DIRECT,
                            enabled = true
                        )
                    )
                )
            )
        )
        assertFalse(
            LocalNetworkPermission.requiresLocalNetworkAccess(
                AppSettings(
                    bypassLan = false,
                    appRules = listOf(
                        AppRule(
                            packageName = "com.example.disabled",
                            appName = "Disabled",
                            outboundMode = RuleSetOutboundMode.DIRECT,
                            enabled = false
                        )
                    )
                )
            )
        )
        assertFalse(
            LocalNetworkPermission.requiresLocalNetworkAccess(
                AppSettings(
                    bypassLan = false,
                    appRules = listOf(AppRule(packageName = "com.example.proxy", appName = "Proxy"))
                )
            )
        )
    }

    @Test
    fun appGroupsRequireLocalNetworkAccessOnlyWhenEnabledAndDirectByDefault() {
        assertTrue(
            LocalNetworkPermission.requiresLocalNetworkAccess(
                AppSettings(
                    bypassLan = false,
                    appGroups = listOf(AppGroup(name = "Default Direct"))
                )
            )
        )
        assertFalse(
            LocalNetworkPermission.requiresLocalNetworkAccess(
                AppSettings(
                    bypassLan = false,
                    appGroups = listOf(AppGroup(name = "Disabled Direct", enabled = false))
                )
            )
        )
        assertFalse(
            LocalNetworkPermission.requiresLocalNetworkAccess(
                AppSettings(
                    bypassLan = false,
                    appGroups = listOf(
                        AppGroup(
                            name = "Proxy Group",
                            outboundMode = RuleSetOutboundMode.PROXY,
                            enabled = true
                        )
                    )
                )
            )
        )
    }

    @Test
    fun restrictInboundListenKeepsTunAndRestrictsWildcardListeners() {
        val tun = Inbound(type = "tun", listenPort = null, listen = null)
        val mixedAny = Inbound(type = "mixed", listenPort = 7890, listen = "0.0.0.0")
        val httpAnyV6 = Inbound(type = "http", listenPort = 7891, listen = "::")
        val socksDefault = Inbound(type = "socks", listenPort = 7892, listen = null)

        assertEquals(tun, LocalNetworkPermission.restrictInboundListen(tun))
        assertEquals("127.0.0.1", LocalNetworkPermission.restrictInboundListen(mixedAny).listen)
        assertEquals("127.0.0.1", LocalNetworkPermission.restrictInboundListen(httpAnyV6).listen)
        assertEquals("127.0.0.1", LocalNetworkPermission.restrictInboundListen(socksDefault).listen)
    }
}
