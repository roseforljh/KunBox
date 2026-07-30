package com.kunk.singbox.service.manager

import com.kunk.singbox.model.AppGroup
import com.kunk.singbox.model.AppRule
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.RoutingMode
import java.io.File
import java.io.IOException
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformInterfaceImplTest {

    @Test
    fun cachedPackageNameIsUsedWhenPackageManagerLookupReturnsEmpty() {
        var lookupCalls = 0

        val result = PlatformInterfaceImpl.resolvePackageNames(
            uid = 10_123,
            cachedPackageName = "com.example.cached"
        ) {
            lookupCalls++
            emptyList()
        }

        assertEquals(listOf("com.example.cached"), result)
        assertEquals(1, lookupCalls)
    }

    @Test
    fun sharedUidReturnsAllPackageNames() {
        val result = PlatformInterfaceImpl.resolvePackageNames(
            uid = 10_123,
            cachedPackageName = "com.example.cached"
        ) {
            listOf("com.example.first", "com.example.second", "com.example.first")
        }

        assertEquals(listOf("com.example.first", "com.example.second"), result)
    }

    @Test
    fun staleAsyncListenerStartIsStoppedAfterRegistration() {
        var current = true
        var starts = 0
        var stops = 0

        PlatformInterfaceImpl.startListenerIfCurrent(
            isCurrent = { current },
            start = {
                starts++
                current = false
            },
            stop = { stops++ }
        )

        assertEquals(1, starts)
        assertEquals(1, stops)
    }

    @Test
    fun procFsEndpointEncodingMatchesKernelIPv4AndIPv6Format() {
        assertEquals(
            "0200000A:3039",
            PlatformInterfaceImpl.encodeProcFsEndpoint(InetAddress.getByName("10.0.0.2"), 12_345)
        )
        assertEquals(
            "B80D0120000000000000000001000000:01BB",
            PlatformInterfaceImpl.encodeProcFsEndpoint(InetAddress.getByName("2001:db8::1"), 443)
        )
    }

    @Test
    fun procFsTableSelectionUsesProtocolAndAddressFamily() {
        assertEquals("/proc/net/tcp", PlatformInterfaceImpl.procFsTablePath(ipProtocol = 6, addressLength = 4))
        assertEquals("/proc/net/tcp6", PlatformInterfaceImpl.procFsTablePath(ipProtocol = 6, addressLength = 16))
        assertEquals("/proc/net/udp", PlatformInterfaceImpl.procFsTablePath(ipProtocol = 17, addressLength = 4))
        assertEquals("/proc/net/udp6", PlatformInterfaceImpl.procFsTablePath(ipProtocol = 17, addressLength = 16))
        assertEquals(null, PlatformInterfaceImpl.procFsTablePath(ipProtocol = 1, addressLength = 4))
    }

    @Test
    fun procFsLookupUsesCompleteConnectionTuple() {
        val source = "0200000A:3039"
        val requestedDestination = "01010101:01BB"
        val lines = sequenceOf(
            PROC_FS_HEADER,
            procFsRow(source, "08080808:0035", uid = 10_123),
            procFsRow(source, requestedDestination, uid = 10_124)
        )

        val result = PlatformInterfaceImpl.resolveProcFsUidFromLines(
            lines = lines,
            sourceEndpoint = source,
            destinationEndpoint = requestedDestination
        )

        assertEquals(PlatformInterfaceImpl.ProcFsUidLookupStatus.RESOLVED, result.status)
        assertEquals(10_124, result.uid)
    }

    @Test
    fun procFsLookupFailsClosedWhenOnlySourceEndpointMatches() {
        val source = "0200000A:3039"
        val result = PlatformInterfaceImpl.resolveProcFsUidFromLines(
            lines = sequenceOf(PROC_FS_HEADER, procFsRow(source, "00000000:0000", uid = 10_123)),
            sourceEndpoint = source,
            destinationEndpoint = "01010101:01BB"
        )

        assertEquals(PlatformInterfaceImpl.ProcFsUidLookupStatus.NOT_FOUND, result.status)
        assertEquals(0, result.uid)
    }

    @Test
    fun udpProcFsLookupPrefersCompleteTupleOverWildcard() {
        val source = "0200000A:3039"
        val destination = "01010101:01BB"
        val result = PlatformInterfaceImpl.resolveProcFsUidFromLines(
            lines = sequenceOf(
                PROC_FS_HEADER,
                procFsRow(source, "00000000:0000", uid = 10_123),
                procFsRow(source, destination, uid = 10_124)
            ),
            sourceEndpoint = source,
            destinationEndpoint = destination,
            allowUdpWildcardFallback = true
        )

        assertEquals(PlatformInterfaceImpl.ProcFsUidLookupStatus.RESOLVED, result.status)
        assertEquals(10_124, result.uid)
    }

    @Test
    fun udpProcFsLookupUsesUniqueWildcardOwner() {
        val source = "0200000A:3039"
        val result = PlatformInterfaceImpl.resolveProcFsUidFromLines(
            lines = sequenceOf(PROC_FS_HEADER, procFsRow(source, "00000000:0000", uid = 10_123)),
            sourceEndpoint = source,
            destinationEndpoint = "01010101:01BB",
            allowUdpWildcardFallback = true
        )

        assertEquals(PlatformInterfaceImpl.ProcFsUidLookupStatus.RESOLVED, result.status)
        assertEquals(10_123, result.uid)
    }

    @Test
    fun udpProcFsLookupFailsClosedWhenLocalEndpointHasMultipleOwners() {
        val source = "0200000A:3039"
        val result = PlatformInterfaceImpl.resolveProcFsUidFromLines(
            lines = sequenceOf(
                PROC_FS_HEADER,
                procFsRow(source, "00000000:0000", uid = 10_123),
                procFsRow(source, "08080808:0035", uid = 10_124)
            ),
            sourceEndpoint = source,
            destinationEndpoint = "01010101:01BB",
            allowUdpWildcardFallback = true
        )

        assertEquals(PlatformInterfaceImpl.ProcFsUidLookupStatus.AMBIGUOUS, result.status)
        assertEquals(0, result.uid)
    }

    @Test
    fun udpProcFsLookupTreatsUidZeroAsCompetingOwner() {
        val source = "0200000A:3039"
        val result = PlatformInterfaceImpl.resolveProcFsUidFromLines(
            lines = sequenceOf(
                PROC_FS_HEADER,
                procFsRow(source, "00000000:0000", uid = 10_123),
                procFsRow(source, "08080808:0035", uid = 0)
            ),
            sourceEndpoint = source,
            destinationEndpoint = "01010101:01BB",
            allowUdpWildcardFallback = true
        )

        assertEquals(PlatformInterfaceImpl.ProcFsUidLookupStatus.AMBIGUOUS, result.status)
        assertEquals(0, result.uid)
    }

    @Test
    fun udpProcFsLookupTreatsMalformedUidAsCompetingOwner() {
        val source = "0200000A:3039"
        val result = PlatformInterfaceImpl.resolveProcFsUidFromLines(
            lines = sequenceOf(
                PROC_FS_HEADER,
                procFsRow(source, "00000000:0000", uid = 10_123),
                procFsRow(source, "08080808:0035", uid = "invalid")
            ),
            sourceEndpoint = source,
            destinationEndpoint = "01010101:01BB",
            allowUdpWildcardFallback = true
        )

        assertEquals(PlatformInterfaceImpl.ProcFsUidLookupStatus.AMBIGUOUS, result.status)
        assertEquals(0, result.uid)
    }

    @Test
    fun tcpProcFsLookupDoesNotUseWildcardOwner() {
        val source = "0200000A:3039"
        val result = PlatformInterfaceImpl.resolveProcFsUidFromLines(
            lines = sequenceOf(PROC_FS_HEADER, procFsRow(source, "00000000:0000", uid = 10_123)),
            sourceEndpoint = source,
            destinationEndpoint = "01010101:01BB",
            allowUdpWildcardFallback = false
        )

        assertEquals(PlatformInterfaceImpl.ProcFsUidLookupStatus.NOT_FOUND, result.status)
        assertEquals(0, result.uid)
    }

    @Test
    fun udpProcFsLookupAllowsDuplicateRowsForSameOwner() {
        val source = "0200000A:3039"
        val result = PlatformInterfaceImpl.resolveProcFsUidFromLines(
            lines = sequenceOf(
                PROC_FS_HEADER,
                procFsRow(source, "00000000:0000", uid = 10_123),
                procFsRow(source, "00000000:0000", uid = 10_123)
            ),
            sourceEndpoint = source,
            destinationEndpoint = "01010101:01BB",
            allowUdpWildcardFallback = true
        )

        assertEquals(PlatformInterfaceImpl.ProcFsUidLookupStatus.RESOLVED, result.status)
        assertEquals(10_123, result.uid)
    }

    @Test
    fun procFsLookupFailsClosedOnAmbiguousFullTuple() {
        val source = "0200000A:3039"
        val destination = "01010101:01BB"
        val result = PlatformInterfaceImpl.resolveProcFsUidFromLines(
            lines = sequenceOf(
                PROC_FS_HEADER,
                procFsRow(source, destination, uid = 10_123),
                procFsRow(source, destination, uid = 10_124)
            ),
            sourceEndpoint = source,
            destinationEndpoint = destination
        )

        assertEquals(PlatformInterfaceImpl.ProcFsUidLookupStatus.AMBIGUOUS, result.status)
        assertEquals(0, result.uid)
    }

    @Test
    fun procFsLookupReportsUnavailableTableWithoutFabricatingUid() {
        val result = PlatformInterfaceImpl.resolveProcFsUidFromLines(
            lines = sequenceOf("malformed header"),
            sourceEndpoint = "0200000A:3039",
            destinationEndpoint = "01010101:01BB"
        )

        assertEquals(PlatformInterfaceImpl.ProcFsUidLookupStatus.UNAVAILABLE, result.status)
        assertEquals(0, result.uid)
    }

    @Test
    fun uidPackageCacheExpiresAfterFiveMinutes() {
        val cachedAtMs = 1_000L

        assertTrue(
            PlatformInterfaceImpl.isUidPackageCacheFresh(
                cachedAtMs = cachedAtMs,
                nowMs = cachedAtMs + PlatformInterfaceImpl.UID_PACKAGE_CACHE_TTL_MS - 1L
            )
        )
        assertFalse(
            PlatformInterfaceImpl.isUidPackageCacheFresh(
                cachedAtMs = cachedAtMs,
                nowMs = cachedAtMs + PlatformInterfaceImpl.UID_PACKAGE_CACHE_TTL_MS
            )
        )
    }

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
    fun testPlatformInterfaceUsesSharedPhysicalNetworkListener() {
        val source = File("src/main/java/com/kunk/singbox/service/manager/PlatformInterfaceImpl.kt").readText()

        assertTrue(source.contains("DefaultNetworkListener.start"))
        assertFalse(source.contains("requestNetwork("))
        assertFalse(source.contains("vpnNetworkCallback"))
        assertFalse(source.contains("vpnHealthJob"))
    }

    @Test
    fun testAutoDetectInterfaceOnlyProtectsOutboundSocket() {
        val source = File("src/main/java/com/kunk/singbox/service/manager/PlatformInterfaceImpl.kt").readText()
        val method = source.substringAfter("override fun autoDetectInterfaceControl(fd: Int)")
            .substringBefore("override fun openTun")

        assertTrue(method.contains("callbacks.protect(fd)"))
        assertTrue(method.contains("ensureSocketProtected(protected, fd)"))
        assertFalse(method.contains("findBestPhysicalNetwork"))
        assertFalse(method.contains("bindSocket"))
    }

    @Test
    fun socketProtectionFailureAbortsNativeDial() {
        val error = assertThrows(IOException::class.java) {
            PlatformInterfaceImpl.ensureSocketProtected(protected = false, fd = 42)
        }

        assertTrue(error.message.orEmpty().contains("42"))
    }

    @Test
    fun successfulSocketProtectionAllowsNativeDial() {
        PlatformInterfaceImpl.ensureSocketProtected(protected = true, fd = 42)
    }

    @Test
    fun testCellularInterfaceNamesResolveToCellularType() {
        assertTrue(PlatformInterfaceImpl.isCellularInterfaceName("rmnet_data0"))
        assertTrue(PlatformInterfaceImpl.isCellularInterfaceName("ccmni0"))
        assertFalse(PlatformInterfaceImpl.isCellularInterfaceName("wlan0"))
    }

    private fun procFsRow(local: String, remote: String, uid: Int): String {
        return procFsRow(local, remote, uid.toString())
    }

    private fun procFsRow(local: String, remote: String, uid: String): String {
        return "0: $local $remote 01 00000000:00000000 00:00000000 00000000 $uid 0 0 0"
    }

    private companion object {
        const val PROC_FS_HEADER =
            "sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode"
    }
}
