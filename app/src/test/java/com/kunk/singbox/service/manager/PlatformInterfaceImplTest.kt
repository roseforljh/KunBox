package com.kunk.singbox.service.manager

import com.kunk.singbox.model.AppGroup
import com.kunk.singbox.model.AppRule
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.RoutingMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformInterfaceImplTest {

    @Test
    fun testShouldExposeProcFsToLibboxDisabledWhenRuleModeHasAppRules() {
        val settings = AppSettings(
            routingMode = RoutingMode.RULE,
            appRules = listOf(AppRule(packageName = "com.example.x", appName = "X"))
        )

        val result = PlatformInterfaceImpl.shouldExposeProcFsToLibbox(
            procFsReadable = true,
            settings = settings
        )

        assertFalse(result)
    }

    @Test
    fun testShouldExposeProcFsToLibboxDisabledWhenRuleModeHasAppGroups() {
        val settings = AppSettings(
            routingMode = RoutingMode.RULE,
            appGroups = listOf(AppGroup(name = "social"))
        )

        val result = PlatformInterfaceImpl.shouldExposeProcFsToLibbox(
            procFsReadable = true,
            settings = settings
        )

        assertFalse(result)
    }

    @Test
    fun testShouldExposeProcFsToLibboxKeepsProcFsForNonAppRouting() {
        val result = PlatformInterfaceImpl.shouldExposeProcFsToLibbox(
            procFsReadable = true,
            settings = AppSettings(routingMode = RoutingMode.GLOBAL_PROXY)
        )

        assertTrue(result)
    }

    @Test
    fun testPlatformInterfaceHandoverAllowsActivePhysicalNetworkBeforeValidated() {
        val result = PlatformInterfaceImpl.shouldHandoverToActiveDefaultNetwork(
            isActiveDefault = true,
            isVpn = false,
            isValidPhysical = true
        )

        assertTrue(result)
    }

    @Test
    fun testRequestedDefaultNetworkHandoverDoesNotRequireActiveNetworkIdentity() {
        val result = PlatformInterfaceImpl.shouldHandoverToRequestedDefaultNetwork(
            isVpn = false,
            isValidPhysical = true
        )

        assertTrue(result)
    }

    @Test
    fun testShouldProtectAndBindPhysicalNetworkForOutboundSockets() {
        val result = PlatformInterfaceImpl.shouldBindProtectedSocketToPhysicalNetwork(
            protected = true,
            physicalNetworkAvailable = true
        )

        assertTrue(result)
    }

    @Test
    fun testCellularInterfaceNamesResolveToCellularType() {
        assertTrue(PlatformInterfaceImpl.isCellularInterfaceName("rmnet_data0"))
        assertTrue(PlatformInterfaceImpl.isCellularInterfaceName("ccmni0"))
        assertFalse(PlatformInterfaceImpl.isCellularInterfaceName("wlan0"))
    }
}
