package com.kunk.singbox.repository

import com.kunk.singbox.model.AppGroup
import com.kunk.singbox.model.AppInfo
import com.kunk.singbox.model.AppRule
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.DnsConfig
import com.kunk.singbox.model.DnsServer
import com.kunk.singbox.model.Endpoint
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.RouteConfig
import com.kunk.singbox.model.RouteRule
import com.kunk.singbox.model.RuleSetConfig
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.model.SingBoxConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeteredNodeConfigGuardTest {
    @Test
    fun configGuardReportsEveryProtectedReferenceLocation() {
        val config = SingBoxConfig(
            outbounds = listOf(
                Outbound(type = "http", tag = "New-HTTP"),
                Outbound(type = "vless", tag = "wrapped", detour = "New-HTTP"),
                Outbound(
                    type = "selector",
                    tag = "manual",
                    outbounds = listOf("safe", "New-HTTP"),
                    default = "New-HTTP"
                )
            ),
            endpoints = listOf(Endpoint(type = "wireguard", tag = "wg", detour = "New-HTTP")),
            route = RouteConfig(
                finalOutbound = "New-HTTP",
                rules = listOf(RouteRule(outbound = "New-HTTP")),
                ruleSet = listOf(RuleSetConfig(tag = "remote", downloadDetour = "New-HTTP"))
            ),
            dns = DnsConfig(servers = listOf(DnsServer(tag = "remote", detour = "New-HTTP")))
        )

        val violations = MeteredNodeConfigGuard.findConfigViolations(
            config = config,
            protectedTags = setOf("New-HTTP"),
            includeGroupReferences = true
        )

        listOf(
            "运行出站",
            "前置代理",
            "候选引用",
            "默认节点",
            "endpoint",
            "路由 final",
            "路由规则[0]",
            "规则集下载[0]",
            "DNS"
        ).forEach { location ->
            assertTrue("缺少保护校验位置: $location", violations.any { it.contains(location) })
        }
    }

    @Test
    fun sourceConfigGuardBlocksQualifiedProtectedDetour() {
        val protectedNodeId = ConfigRepository.stableNodeId("profile-c", "New-HTTP")
        val config = SingBoxConfig(
            outbounds = listOf(
                Outbound(
                    type = "vless",
                    tag = "regular",
                    detour = "profile-c::New-HTTP"
                )
            ),
            dns = DnsConfig(
                servers = listOf(DnsServer(tag = "remote", detour = "profile-c::New-HTTP"))
            )
        )

        val violations = MeteredNodeConfigGuard.findSourceConfigViolations(
            config = config,
            sourceProfileId = "profile-a",
            protectedNodeIds = setOf(protectedNodeId),
            includeGroupReferences = false
        )

        assertEquals(2, violations.size)
        assertTrue(violations.all { it.contains("profile-c::New-HTTP") })
        assertTrue(violations.any { it.contains("DNS") })
    }

    @Test
    fun sourceConfigGuardBlocksProtectedSelectorUnlessNodeWasManuallySelected() {
        val protectedNodeId = ConfigRepository.stableNodeId("profile-c", "New-HTTP")
        val config = SingBoxConfig(
            outbounds = listOf(
                Outbound(
                    type = "selector",
                    tag = "manual",
                    outbounds = listOf("safe", "profile-c::New-HTTP")
                )
            )
        )

        val blocked = MeteredNodeConfigGuard.findSourceConfigViolations(
            config = config,
            sourceProfileId = "profile-a",
            protectedNodeIds = setOf(protectedNodeId),
            includeGroupReferences = true,
            includeDeclaredNodes = false
        )
        val allowed = MeteredNodeConfigGuard.findSourceConfigViolations(
            config = config,
            sourceProfileId = "profile-a",
            protectedNodeIds = setOf(protectedNodeId),
            includeGroupReferences = true,
            includeDeclaredNodes = false,
            allowedProtectedNodeId = protectedNodeId
        )

        assertEquals(1, blocked.size)
        assertTrue(blocked.single().contains("selector"))
        assertTrue(allowed.isEmpty())
    }

    @Test
    fun explicitNodeAppRuleIsAnAuthorizedRoutingPath() {
        val protectedNode = NodeUi(
            id = "metered-id",
            name = "New-HTTP",
            protocol = "http",
            group = "Default",
            sourceProfileId = "profile-a",
            meteredProtected = true
        )
        val settings = AppSettings(
            appRules = listOf(
                AppRule(
                    packageName = "com.example.app",
                    appName = "Example",
                    outboundMode = RuleSetOutboundMode.NODE,
                    outboundValue = protectedNode.id
                )
            )
        )

        val violations = MeteredNodeConfigGuard.findSettingsViolations(settings, listOf(protectedNode))
        assertTrue(violations.isEmpty())
    }

    @Test
    fun profileReferenceAllowsProtectedNodeExcludedFromAutomaticSelection() {
        val protectedNode = NodeUi(
            id = "metered-id",
            name = "New-HTTP",
            protocol = "http",
            group = "Default",
            sourceProfileId = "profile-a",
            autoSelectionEligible = false,
            meteredProtected = true
        )
        val safeNode = NodeUi(
            id = "safe-id",
            name = "Safe",
            protocol = "vless",
            group = "Default",
            sourceProfileId = "profile-a"
        )
        val settings = AppSettings(
            appGroups = listOf(
                AppGroup(
                    name = "Profile route",
                    outboundMode = RuleSetOutboundMode.PROFILE,
                    outboundValue = "profile-a"
                )
            )
        )

        val violations = MeteredNodeConfigGuard.findSettingsViolations(
            settings = settings,
            nodes = listOf(protectedNode, safeNode)
        )

        assertTrue(violations.isEmpty())
    }

    @Test
    fun dormantAppGroupDoesNotAuthorizeProtectedProfile() {
        val protectedNode = NodeUi(
            id = "metered-id",
            name = "New-HTTP",
            protocol = "http",
            group = "Default",
            sourceProfileId = "profile-a",
            meteredProtected = true
        )
        val settings = AppSettings(
            appGroups = listOf(
                AppGroup(
                    name = "Dormant",
                    apps = listOf(AppInfo("com.dormant", "Dormant")),
                    outboundMode = RuleSetOutboundMode.PROFILE,
                    outboundValue = "profile-a"
                )
            )
        )

        val dormant = MeteredNodeConfigGuard.findSettingsViolations(
            settings,
            listOf(protectedNode),
            isPackageCaptured = { false }
        )
        val active = MeteredNodeConfigGuard.findSettingsViolations(
            settings,
            listOf(protectedNode),
            isPackageCaptured = { true }
        )

        assertTrue(dormant.isEmpty())
        assertEquals(1, active.size)
    }

    @Test
    fun explicitRouteAuthorizationRejectsSelectorAndDnsReferences() {
        val config = SingBoxConfig(
            outbounds = listOf(
                Outbound(type = "http", tag = "metered"),
                Outbound(type = "selector", tag = "PROXY", outbounds = listOf("safe", "metered"))
            ),
            route = RouteConfig(
                finalOutbound = "safe",
                rules = listOf(
                    RouteRule(packageName = listOf("com.example.app"), outbound = "metered")
                )
            ),
            dns = DnsConfig(
                servers = listOf(
                    DnsServer(
                        tag = "dns-explicit",
                        address = "https://dns.example",
                        detour = "metered"
                    )
                )
            )
        )

        val violations = MeteredNodeConfigGuard.findExplicitRouteScopeViolations(config, setOf("metered"))

        assertEquals(2, violations.size)
        assertTrue(violations.any { it.contains("selector") })
        assertTrue(violations.any { it.contains("DNS") })
        assertTrue(violations.none { it.contains("路由规则") })
    }

    @Test
    fun explicitRouteAuthorizationAllowsGeneratedDnsForExplicitNodeRoute() {
        val protectedTag = "New-HTTP"
        val config = SingBoxConfig(
            outbounds = listOf(Outbound(type = "http", tag = protectedTag)),
            route = RouteConfig(
                finalOutbound = "safe",
                rules = listOf(
                    RouteRule(packageName = listOf("com.example.app"), outbound = protectedTag)
                )
            ),
            dns = DnsConfig(
                servers = listOf(
                    DnsServer(
                        tag = ConfigRepository.buildDynamicDnsServerTag(protectedTag),
                        address = "https://dns.example",
                        detour = protectedTag
                    )
                )
            )
        )

        val violations = MeteredNodeConfigGuard.findExplicitRouteScopeViolations(config, setOf(protectedTag))

        assertTrue(violations.isEmpty())
    }

    @Test
    fun protectedNodeCannotEnterAnySelectorEvenWhenRouteIsExplicit() {
        val protectedTag = "metered"
        val config = SingBoxConfig(
            outbounds = listOf(
                Outbound(type = "http", tag = protectedTag),
                Outbound(type = "http", tag = "safe"),
                Outbound(
                    type = "selector",
                    tag = "explicit-selector",
                    outbounds = listOf(protectedTag, "safe"),
                    default = protectedTag
                )
            ),
            route = RouteConfig(
                finalOutbound = "safe",
                rules = listOf(RouteRule(packageName = listOf("com.example.app"), outbound = "explicit-selector"))
            ),
            dns = DnsConfig(
                servers = listOf(
                    DnsServer(
                        tag = ConfigRepository.buildDynamicDnsServerTag("explicit-selector"),
                        address = "https://dns.example",
                        detour = "explicit-selector"
                    )
                )
            )
        )

        val violations = MeteredNodeConfigGuard.findExplicitRouteScopeViolations(config, setOf(protectedTag))

        assertTrue(violations.any { it.contains("selector") })
    }

    @Test
    fun explicitNodeDefinitionIsKeptWhileGroupReferencesAreRemoved() {
        val filtered = MeteredNodeConfigGuard.removeGroupReferences(
            outbounds = listOf(
                Outbound(type = "http", tag = "metered"),
                Outbound(
                    type = "selector",
                    tag = "PROXY",
                    outbounds = listOf("safe", "metered"),
                    default = "metered"
                ),
                Outbound(type = "urltest", tag = "AUTO", outbounds = listOf("safe", "metered"))
            ),
            disallowedTags = setOf("metered")
        )

        assertTrue(filtered.any { it.tag == "metered" && it.type == "http" })
        assertEquals(listOf("safe"), filtered.first { it.tag == "PROXY" }.outbounds)
        assertNull(filtered.first { it.tag == "PROXY" }.default)
        assertEquals(listOf("safe"), filtered.first { it.tag == "AUTO" }.outbounds)
    }

    @Test
    fun unauthorizedRuntimeMappingKeepsOnlyProtectedUnselectedNode() {
        val mappings = mapOf(
            "safe" to RuntimeNodeRef("safe-id", "Safe"),
            "metered" to RuntimeNodeRef("metered-id", "New-HTTP", meteredProtected = true),
            "explicit" to RuntimeNodeRef(
                "explicit-id",
                "Explicit",
                meteredProtected = true,
                explicitRouteAuthorized = true
            )
        )

        val unauthorized = MeteredNodeConfigGuard.findUnauthorizedRuntimeNodes(
            mappings = mappings,
            protectedNodeIds = setOf("metered-id", "explicit-id"),
            selectedNodeId = "safe-id",
            manuallyAuthorizedNodeId = null
        )

        assertEquals(setOf("metered"), unauthorized.keys)
    }
}
