package com.kunk.singbox.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppVpnPolicyTest {
    private val apps = listOf(
        InstalledAppUi("com.one", "One", false, true, uid = 10001),
        InstalledAppUi("com.shared.a", "Shared A", false, true, uid = 10002),
        InstalledAppUi("com.shared.b", "Shared B", false, false, uid = 10002),
        InstalledAppUi("com.three", "Three", false, true, uid = 10003)
    )

    @Test
    fun allModeCapturesEveryInstalledAppExceptSelf() {
        val scope = PerAppVpnScopeResolver.resolve(
            PerAppVpnPolicy.from(AppSettings(vpnAppMode = VpnAppMode.ALL)),
            apps,
            "com.one"
        )

        assertEquals(setOf("com.shared.a", "com.shared.b", "com.three"), scope.capturedPackages)
        assertEquals(setOf("com.one"), scope.builderDisallowedPackages)
    }

    @Test
    fun allowlistExpandsEveryPackageSharingSelectedUid() {
        val settings = AppSettings(
            vpnAppMode = VpnAppMode.ALLOWLIST,
            vpnAllowlist = "com.shared.a"
        )

        val scope = PerAppVpnScopeResolver.resolve(PerAppVpnPolicy.from(settings), apps, "com.kunk")

        assertEquals(setOf("com.shared.a", "com.shared.b"), scope.capturedPackages)
        assertEquals(setOf("com.shared.a"), scope.builderAllowedPackages)
    }

    @Test
    fun blocklistExcludesEveryPackageSharingSelectedUid() {
        val settings = AppSettings(
            vpnAppMode = VpnAppMode.BLOCKLIST,
            vpnBlocklist = "com.shared.a"
        )

        val scope = PerAppVpnScopeResolver.resolve(PerAppVpnPolicy.from(settings), apps, "com.kunk")

        assertFalse("com.shared.a" in scope.capturedPackages)
        assertFalse("com.shared.b" in scope.capturedPackages)
        assertTrue("com.one" in scope.capturedPackages)
    }

    @Test
    fun digestIsOrderIndependentAndChangesWithMode() {
        val first = PerAppVpnPolicy.from(
            AppSettings(vpnAppMode = VpnAppMode.ALLOWLIST, vpnAllowlist = "com.b\ncom.a")
        )
        val reordered = PerAppVpnPolicy.from(
            AppSettings(vpnAppMode = VpnAppMode.ALLOWLIST, vpnAllowlist = "com.a\ncom.b")
        )
        val differentMode = PerAppVpnPolicy.from(
            AppSettings(vpnAppMode = VpnAppMode.BLOCKLIST, vpnBlocklist = "com.a\ncom.b")
        )

        assertEquals(first.digest(), reordered.digest())
        assertNotEquals(first.digest(), differentMode.digest())
    }

    @Test
    fun inactiveDraftListDoesNotChangeAppliedDigest() {
        val first = PerAppVpnPolicy.from(
            AppSettings(
                vpnAppMode = VpnAppMode.ALLOWLIST,
                vpnAllowlist = "com.active",
                vpnBlocklist = "com.old-draft"
            )
        )
        val changedDraft = PerAppVpnPolicy.from(
            AppSettings(
                vpnAppMode = VpnAppMode.ALLOWLIST,
                vpnAllowlist = "com.active",
                vpnBlocklist = "com.new-draft"
            )
        )

        assertEquals(first.digest(), changedDraft.digest())
    }

    @Test
    fun revisionSaturatesAtLongMaxValue() {
        assertEquals(1L, PerAppVpnPolicy.nextRevision(0L))
        assertEquals(Long.MAX_VALUE, PerAppVpnPolicy.nextRevision(Long.MAX_VALUE))
    }
}
