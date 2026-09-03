package com.kunk.singbox.service.tun

import android.content.pm.PackageManager
import com.google.gson.Gson
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.IpVersionMode
import com.kunk.singbox.model.VpnRouteMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import java.util.concurrent.CancellationException

class VpnTunAddressPlanTest {

    @org.junit.Test
    fun tunRetryCreatesANewAttemptAfterAnInvalidInterface() {
        val attempts = mutableListOf<Int>()
        val waits = mutableListOf<Long>()

        val result = retryVpnInterfaceEstablishment(
            backoffMs = longArrayOf(0L, 25L),
            isStopping = { false },
            sleep = waits::add,
            createAttempt = { it },
            establish = { attempt ->
                attempts += attempt
                if (attempt == 0) null else 42
            },
            isValid = { it > 0 },
            closeInvalid = {}
        )

        assertEquals(42, result)
        assertEquals(listOf(0, 1), attempts)
        assertEquals(listOf(25L), waits)
    }

    @org.junit.Test
    fun tunRetryContinuesAfterATransientEstablishException() {
        val attempts = mutableListOf<Int>()

        val result = retryVpnInterfaceEstablishment(
            backoffMs = longArrayOf(0L, 25L),
            isStopping = { false },
            sleep = {},
            createAttempt = { it },
            establish = { attempt ->
                attempts += attempt
                if (attempt == 0) throw IllegalStateException("system VPN is still releasing")
                42
            },
            isValid = { it > 0 },
            closeInvalid = {}
        )

        assertEquals(42, result)
        assertEquals(listOf(0, 1), attempts)
    }

    @org.junit.Test
    fun tunRetryDoesNotHideDeterministicBuilderConfigurationErrors() {
        var createCalls = 0

        assertThrows(IllegalArgumentException::class.java) {
            retryVpnInterfaceEstablishment(
                backoffMs = longArrayOf(0L, 25L),
                isStopping = { false },
                sleep = {},
                createAttempt = {
                    createCalls++
                    throw IllegalArgumentException("invalid route")
                },
                establish = { 42 },
                isValid = { true },
                closeInvalid = {}
            )
        }
        assertEquals(1, createCalls)
    }

    @org.junit.Test
    fun tunRetryStopsAsCancellationBeforeCreatingAnotherInterface() {
        var stoppingChecks = 0
        var establishCalls = 0

        assertThrows(CancellationException::class.java) {
            retryVpnInterfaceEstablishment(
                backoffMs = longArrayOf(25L),
                isStopping = { ++stoppingChecks > 1 },
                sleep = {},
                createAttempt = {
                    establishCalls++
                    it
                },
                establish = { 42 },
                isValid = { true },
                closeInvalid = {}
            )
        }
        assertEquals(0, establishCalls)
    }

    @org.junit.Test
    fun plannerUsesOnlyIpv4WhenIpv4Only() {
        val plan = VpnTunAddressPlanner.build(IpVersionMode.IPV4_ONLY)

        assertEquals(listOf("172.19.0.1" to 30), plan.addresses)
        assertEquals(listOf("0.0.0.0" to 0), plan.globalRoutes)
        assertEquals(listOf("172.19.0.2"), plan.defaultDnsServers)
    }

    @org.junit.Test
    fun plannerUsesDualStackWhenDualStack() {
        val plan = VpnTunAddressPlanner.build(IpVersionMode.DUAL_STACK)

        assertEquals(listOf("172.19.0.1" to 30, "fd00::1" to 126), plan.addresses)
        assertEquals(listOf("0.0.0.0" to 0, "::" to 0), plan.globalRoutes)
        assertEquals(listOf("172.19.0.2", "fd00::2"), plan.defaultDnsServers)
    }

    @org.junit.Test
    fun plannerUsesDualStackWhenPreferIpv6() {
        val plan = VpnTunAddressPlanner.build(IpVersionMode.PREFER_IPV6)

        assertEquals(listOf("172.19.0.1" to 30, "fd00::1" to 126), plan.addresses)
        assertEquals(listOf("172.19.0.1/30", "fd00::1/126"), plan.cidrAddresses)
        assertEquals(listOf("0.0.0.0" to 0, "::" to 0), plan.globalRoutes)
        assertEquals(listOf("172.19.0.2", "fd00::2"), plan.defaultDnsServers)
    }

    @org.junit.Test
    fun kernelTunAddressesAreRuntimeAuthorityWhenValid() {
        val kernelAddresses = listOf("10.0.0.1" to 30, "fd12::1" to 126)

        assertEquals(
            kernelAddresses,
            VpnTunManager.validateKernelTunAddresses(kernelAddresses)
        )
    }

    @org.junit.Test
    fun invalidKernelTunAddressesAreRejected() {
        val invalidAddress = runCatching {
            VpnTunManager.validateKernelTunAddresses(listOf("example.com" to 30))
        }
        val invalidPrefix = runCatching {
            VpnTunManager.validateKernelTunAddresses(listOf("10.0.0.1" to 33))
        }

        assertEquals(true, invalidAddress.exceptionOrNull() is IllegalArgumentException)
        assertEquals(true, invalidPrefix.exceptionOrNull() is IllegalArgumentException)
    }

    @org.junit.Test
    fun plannerUsesOnlyIpv6WhenIpv6Only() {
        val plan = VpnTunAddressPlanner.build(IpVersionMode.IPV6_ONLY)

        assertEquals(listOf("fd00::1" to 126), plan.addresses)
        assertEquals(listOf("::" to 0), plan.globalRoutes)
        assertEquals(listOf("fd00::2"), plan.defaultDnsServers)
    }

    @org.junit.Test
    fun customRouteModeIncludesFakeIpRoutesWhenFakeDnsEnabled() {
        val settings = AppSettings(
            vpnRouteMode = VpnRouteMode.CUSTOM,
            vpnRouteIncludeCidrs = "8.8.8.8/32",
            fakeDnsEnabled = true,
            ipVersionMode = IpVersionMode.DUAL_STACK
        )

        val routes = VpnTunManager.resolveVpnRoutes(
            settings = settings,
            tunPlan = VpnTunAddressPlanner.build(IpVersionMode.DUAL_STACK)
        )

        assertEquals(
            listOf(
                "8.8.8.8" to 32,
                "172.19.0.2" to 32,
                "fd00::2" to 128,
                "198.18.0.0" to 15,
                "fc00::" to 18
            ),
            routes
        )
    }

    @org.junit.Test
    fun customRouteModeFailsWhenNonEmptyInputHasNoValidCidrs() {
        val settings = AppSettings(
            vpnRouteMode = VpnRouteMode.CUSTOM,
            vpnRouteIncludeCidrs = "example.com/24,8.8.8.8/33,fd00::/129,999.1.1.1/24"
        )

        val failure = runCatching { VpnTunManager.resolveVpnRoutes(settings) }

        assertEquals(true, failure.exceptionOrNull() is IllegalArgumentException)
    }

    @org.junit.Test
    fun requiredGlobalRouteFailureStopsTunConfiguration() {
        val requiredRoute = "0.0.0.0" to 0

        val failure = runCatching {
            VpnTunManager.addVpnRoutesFailClosed(
                routes = listOf(requiredRoute),
                requiredRoutes = setOf(requiredRoute)
            ) { _, _ -> false }
        }

        assertEquals(true, failure.exceptionOrNull() is IllegalStateException)
    }

    @org.junit.Test
    fun customModeMarksEveryResolvedRouteAsRequired() {
        val settings = AppSettings(
            vpnRouteMode = VpnRouteMode.CUSTOM,
            vpnRouteIncludeCidrs = "8.8.8.8/32"
        )
        val tunPlan = VpnTunAddressPlanner.build(IpVersionMode.DUAL_STACK)
        val routes = VpnTunManager.resolveVpnRoutes(settings, tunPlan)
        val requiredRoutes = VpnTunManager.resolveRequiredVpnRoutes(settings, tunPlan, routes)

        assertEquals(routes.toSet(), requiredRoutes)
        val failure = runCatching {
            VpnTunManager.addVpnRoutesFailClosed(routes, requiredRoutes) { route, _ ->
                route != "8.8.8.8"
            }
        }

        assertEquals(true, failure.exceptionOrNull() is IllegalStateException)
    }

    @org.junit.Test
    fun fakeIpRoutesUseConfiguredIpv4AndIpv6RangesWhenFakeDnsEnabled() {
        val settings = AppSettings(
            fakeDnsEnabled = true,
            fakeIpRange = "198.19.0.0/16,fd00:abcd::/48",
            ipVersionMode = IpVersionMode.DUAL_STACK
        )

        val routes = VpnTunManager.resolveVpnRoutes(
            settings = settings,
            tunPlan = VpnTunAddressPlanner.build(IpVersionMode.DUAL_STACK)
        )

        assertEquals(
            listOf(
                "0.0.0.0" to 0,
                "::" to 0,
                "198.19.0.0" to 16,
                "fd00:abcd::" to 48
            ),
            routes
        )
    }

    @org.junit.Test
    fun fakeIpRoutesRecoverDefaultRangesWhenConfiguredRangeIsNull() {
        val settings = Gson().fromJson(
            """{"fakeDnsEnabled":true,"ipVersionMode":"DUAL_STACK","fakeIpRange":null}""",
            AppSettings::class.java
        )

        val routes = VpnTunManager.resolveVpnRoutes(
            settings = settings,
            tunPlan = VpnTunAddressPlanner.build(IpVersionMode.DUAL_STACK)
        )

        assertEquals(
            listOf(
                "0.0.0.0" to 0,
                "::" to 0,
                "198.18.0.0" to 15,
                "fc00::" to 18
            ),
            routes
        )
    }

    @org.junit.Test
    fun vpnDnsResolverRejectsTunLocalAddressFromLibbox() {
        val settings = AppSettings(
            localDns = "https://dns.alidns.com/dns-query",
            remoteDns = "https://1.1.1.1/dns-query"
        )

        val dnsServers = VpnTunManager.resolveVpnDnsServers(
            settings = settings,
            dnsServerAddress = "172.19.0.1"
        )

        assertEquals(listOf("172.19.0.2", "fd00::2"), dnsServers)
    }

    @org.junit.Test
    fun vpnDnsResolverRejectsExternalAddressFromLibbox() {
        val settings = AppSettings(ipVersionMode = IpVersionMode.DUAL_STACK)

        val dnsServers = VpnTunManager.resolveVpnDnsServers(
            settings = settings,
            dnsServerAddress = "8.8.8.8"
        )

        assertEquals(listOf("172.19.0.2", "fd00::2"), dnsServers)
    }

    @org.junit.Test
    fun vpnDnsResolverUsesTrustedInternalAddressFromLibbox() {
        val settings = AppSettings(ipVersionMode = IpVersionMode.DUAL_STACK)

        val dnsServers = VpnTunManager.resolveVpnDnsServers(
            settings = settings,
            dnsServerAddress = "172.19.0.2"
        )

        assertEquals(listOf("172.19.0.2"), dnsServers)
    }

    @org.junit.Test
    fun vpnDnsResolverUsesAllTrustedInternalAddressesFromLibbox() {
        val settings = AppSettings(ipVersionMode = IpVersionMode.DUAL_STACK)

        val dnsServers = VpnTunManager.resolveVpnDnsServers(
            settings = settings,
            dnsServerAddresses = listOf("172.19.0.2", "fd00::2")
        )

        assertEquals(listOf("172.19.0.2", "fd00::2"), dnsServers)
    }

    @org.junit.Test
    fun vpnDnsResolverFallsBackToDefaultDnsServersWhenNoLibboxAddress() {
        val settings = AppSettings(ipVersionMode = IpVersionMode.DUAL_STACK)

        val dnsServers = VpnTunManager.resolveVpnDnsServers(
            settings = settings,
            dnsServerAddress = null,
            tunPlan = VpnTunAddressPlanner.build(IpVersionMode.DUAL_STACK)
        )

        assertEquals(listOf("172.19.0.2", "fd00::2"), dnsServers)
    }

    @org.junit.Test
    fun vpnDnsResolverUsesIpv4OnlyDefaultDnsWhenIpv4Only() {
        val settings = AppSettings(ipVersionMode = IpVersionMode.IPV4_ONLY)

        val dnsServers = VpnTunManager.resolveVpnDnsServers(
            settings = settings,
            dnsServerAddress = null,
            tunPlan = VpnTunAddressPlanner.build(IpVersionMode.IPV4_ONLY)
        )

        assertEquals(listOf("172.19.0.2"), dnsServers)
    }

    @org.junit.Test
    fun vpnDnsResolverUsesIpv6OnlyInternalDnsWhenIpv6Only() {
        val settings = AppSettings(ipVersionMode = IpVersionMode.IPV6_ONLY)

        val dnsServers = VpnTunManager.resolveVpnDnsServers(
            settings = settings,
            dnsServerAddress = null,
            tunPlan = VpnTunAddressPlanner.build(IpVersionMode.IPV6_ONLY)
        )

        assertEquals(listOf("fd00::2"), dnsServers)
    }

    @org.junit.Test
    fun dualStackDnsContinuesWhenOneInternalServerSucceeds() {
        val internalDnsServers = setOf("172.19.0.2", "fd00::2")
        var attempts = 0

        VpnTunManager.addVpnDnsServersFailClosed(
            dnsServers = internalDnsServers.toList(),
            internalDnsServers = internalDnsServers
        ) { dns ->
            attempts++
            dns == "fd00::2"
        }

        assertEquals(2, attempts)
    }

    @org.junit.Test
    fun allInternalDnsFailuresStopTunConfiguration() {
        val internalDnsServers = setOf("172.19.0.2", "fd00::2")

        val failure = runCatching {
            VpnTunManager.addVpnDnsServersFailClosed(
                dnsServers = internalDnsServers.toList(),
                internalDnsServers = internalDnsServers
            ) { false }
        }

        assertEquals(true, failure.exceptionOrNull() is IllegalStateException)
    }

    @org.junit.Test
    fun optionalDnsSuccessCannotReplaceInternalDns() {
        val internalDnsServers = setOf("172.19.0.2", "fd00::2")

        val failure = runCatching {
            VpnTunManager.addVpnDnsServersFailClosed(
                dnsServers = internalDnsServers.toList() + "8.8.8.8",
                internalDnsServers = internalDnsServers
            ) { dns -> dns == "8.8.8.8" }
        }

        assertEquals(true, failure.exceptionOrNull() is IllegalStateException)
    }

    @org.junit.Test
    fun perAppPlanUsesDisallowedApplicationsInBlocklistMode() {
        val settings = AppSettings(
            vpnAppMode = com.kunk.singbox.model.VpnAppMode.BLOCKLIST,
            vpnBlocklist = "com.blocked\ncom.other"
        )

        val plan = VpnTunManager.resolvePerAppVpnPlan(settings, "com.kunk.singbox")

        assertEquals(emptyList<String>(), plan.allowedPackages)
        assertEquals(listOf("com.kunk.singbox", "com.blocked", "com.other"), plan.disallowedPackages)
    }

    @org.junit.Test
    fun emptyAllowlistCannotSilentlyBecomeAllAppsMode() {
        val settings = AppSettings(vpnAppMode = com.kunk.singbox.model.VpnAppMode.ALLOWLIST)

        assertEquals(false, VpnTunManager.hasUsablePerAppAllowlist(settings, addedAllowedCount = 0))
        assertEquals(true, VpnTunManager.hasUsablePerAppAllowlist(settings, addedAllowedCount = 1))
    }

    @org.junit.Test
    fun allowlistSkipsMissingApplicationAndKeepsValidApplications() {
        val attempts = mutableListOf<String>()

        val addedCount = VpnTunManager.addAllowedApplicationsFailClosed(
            listOf("com.example.valid", "com.example.missing", "com.example.second")
        ) { packageName ->
            attempts += packageName
            if (packageName == "com.example.missing") {
                throw PackageManager.NameNotFoundException(packageName)
            }
        }

        assertEquals(
            listOf("com.example.valid", "com.example.missing", "com.example.second"),
            attempts
        )
        assertEquals(2, addedCount)
    }

    @org.junit.Test
    fun allowlistStillFailsOnUnexpectedBuilderError() {
        val failure = runCatching {
            VpnTunManager.addAllowedApplicationsFailClosed(listOf("com.example.invalid")) {
                error("builder failure")
            }
        }

        assertEquals(true, failure.exceptionOrNull() is IllegalStateException)
    }

    @org.junit.Test
    fun autoMtuDoesNotInventEncapsulationOverhead() {
        assertEquals(
            1500,
            VpnTunManager.resolveAutoMtu(
                configuredMtu = 1500,
                physicalMtu = 1500,
                includesIpv6 = true
            )
        )
    }

    @org.junit.Test
    fun autoMtuUsesPhysicalAndConfiguredUpperBounds() {
        assertEquals(1400, VpnTunManager.resolveAutoMtu(1500, 1400, includesIpv6 = true))
        assertEquals(1280, VpnTunManager.resolveAutoMtu(1500, 1200, includesIpv6 = true))
        assertEquals(1380, VpnTunManager.resolveAutoMtu(1380, 9000, includesIpv6 = true))
    }

    @org.junit.Test
    fun httpProxyIsNotAppendedWhenTunModeIsEnabled() {
        val settings = AppSettings(
            tunEnabled = true,
            proxyPort = 2080,
            appendHttpProxy = true
        )

        assertEquals(false, VpnTunManager.shouldAppendHttpProxy(settings))
    }

    @org.junit.Test
    fun httpProxyCanBeAppendedOnlyOutsideTunMode() {
        val settings = AppSettings(
            tunEnabled = false,
            proxyPort = 2080,
            appendHttpProxy = true
        )

        assertEquals(true, VpnTunManager.shouldAppendHttpProxy(settings))
    }
}
