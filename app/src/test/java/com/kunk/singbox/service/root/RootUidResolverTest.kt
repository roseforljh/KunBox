package com.kunk.singbox.service.root

import com.kunk.singbox.model.VpnAppMode
import org.junit.Assert.assertEquals
import org.junit.Test

class RootUidResolverTest {
    private val executor = RootCommandExecutor { command ->
        when {
            command == listOf("cmd", "user", "list") -> RootCommandResult(
                0,
                "Users:\n\tUserInfo{0:Owner:13} running\n\tUserInfo{10:Work profile:30} running"
            )
            command.lastOrNull() == "0" -> RootCommandResult(
                0,
                "package:com.example.proxy uid:10123\n" +
                    "package:com.example.shared uid:10123\n" +
                    "package:com.example.direct uid:10124\n" +
                    "package:com.kunk.singbox uid:10234"
            )
            command.lastOrNull() == "10" -> RootCommandResult(
                0,
                "package:com.example.proxy uid:1010123\npackage:com.example.work uid:1010124"
            )
            else -> RootCommandResult(1, "unexpected")
        }
    }

    @Test
    fun allowlistExpandsSharedUidAndAllAndroidUsers() {
        val resolved = RootUidResolver(executor).resolveCapturedUids(
            mode = VpnAppMode.ALLOWLIST,
            allowlist = setOf("com.example.proxy"),
            blocklist = emptySet(),
            selfPackage = "com.kunk.singbox",
            selfUid = 10234
        )

        assertEquals(listOf(10123, 1010123), resolved.capturedUids)
        assertEquals(emptyList<RootUidRange>(), resolved.capturedRanges)
    }

    @Test
    fun blocklistExcludesEveryPackageSharingBlockedUid() {
        val resolved = RootUidResolver(executor).resolveCapturedUids(
            mode = VpnAppMode.BLOCKLIST,
            allowlist = emptySet(),
            blocklist = setOf("com.example.shared"),
            selfPackage = "com.kunk.singbox",
            selfUid = 10234
        )

        assertEquals(listOf(RootUidRange(10_000, 99_999), RootUidRange(1_010_000, 1_099_999)), resolved.capturedRanges)
        assertEquals(listOf(10123, 10234), resolved.excludedUids)
    }
}
