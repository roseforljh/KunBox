package com.kunk.singbox.service.manager

import com.kunk.singbox.ipc.VpnNetworkOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ForeignVpnMonitorPolicyTest {
    @Test
    fun unknownOwnerDuringStartupIsVerification() {
        assertEquals(
            VpnNetworkOwnership.UNKNOWN,
            resolveVpnNetworkOwnership(
                ownerUid = -1,
                applicationUid = 10590,
                preExisting = false,
                canClaim = true,
                alreadyOwned = false
            )
        )
    }

    @Test
    fun unknownOwnerOutsideCandidateWindowIsIgnored() {
        assertEquals(
            VpnNetworkOwnership.IGNORE,
            resolveVpnNetworkOwnership(
                ownerUid = -1,
                applicationUid = 10590,
                preExisting = false,
                canClaim = false,
                alreadyOwned = false
            )
        )
    }

    @Test
    fun matchingOwnerWinsEvenWhenTheCallbackArrivesAfterStartup() {
        assertEquals(
            VpnNetworkOwnership.OWNED,
            resolveVpnNetworkOwnership(
                ownerUid = 10590,
                applicationUid = 10590,
                preExisting = false,
                canClaim = false,
                alreadyOwned = false
            )
        )
    }

    @Test
    fun onlyAnExplicitDifferentOwnerIsForeign() {
        assertEquals(
            VpnNetworkOwnership.FOREIGN,
            resolveVpnNetworkOwnership(
                ownerUid = 11001,
                applicationUid = 10590,
                preExisting = false,
                canClaim = true,
                alreadyOwned = false
            )
        )
    }

    @Test
    fun preExistingUnknownNetworkDoesNotPolluteCurrentSession() {
        assertEquals(
            VpnNetworkOwnership.IGNORE,
            resolveVpnNetworkOwnership(
                ownerUid = -1,
                applicationUid = 10590,
                preExisting = true,
                canClaim = true,
                alreadyOwned = false
            )
        )
    }

    @Test
    fun preExistingNetworkWithExplicitDifferentOwnerIsForeign() {
        assertEquals(
            VpnNetworkOwnership.FOREIGN,
            resolveVpnNetworkOwnership(
                ownerUid = 11001,
                applicationUid = 10590,
                preExisting = true,
                canClaim = false,
                alreadyOwned = false
            )
        )
    }

    @Test
    fun legacyCandidateIsOwnedWithoutOwnerUid() {
        assertEquals(
            VpnNetworkOwnership.OWNED,
            resolveVpnNetworkOwnership(
                ownerUid = null,
                applicationUid = 10590,
                preExisting = false,
                canClaim = true,
                alreadyOwned = false
            )
        )
    }

    @Test
    fun availableCallbackNeverPerformsRacySynchronousCapabilityLookup() {
        val source = File("src/main/java/com/kunk/singbox/service/manager/ForeignVpnMonitor.kt")
            .readText(Charsets.UTF_8)
        val availableBody = source
            .substringAfter("override fun onAvailable(network: Network)")
            .substringBefore("override fun onCapabilitiesChanged")

        assertFalse(availableBody.contains("getNetworkCapabilities"))
        assertTrue(source.contains("override fun onCapabilitiesChanged"))
        assertTrue(source.contains("isCurrentGenerationLocked"))
    }

    @Test
    fun serviceDestroyInvalidatesTheVpnNetworkMonitor() {
        val source = File("src/main/java/com/kunk/singbox/service/vpn/SingBoxControlRuntime.kt")
            .readText(Charsets.UTF_8)
        val destroyBody = source
            .substringAfter("fun SingBoxService.onDestroyRuntime()")
            .substringBefore("fun SingBoxService.onRevokeRuntime()")

        assertTrue(destroyBody.contains("foreignVpnMonitor.cleanup()"))
    }
}
