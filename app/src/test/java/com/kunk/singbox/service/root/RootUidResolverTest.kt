package com.kunk.singbox.service.root

import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.RootAppRoutingAssignment
import com.kunk.singbox.model.RootAppRoutingPlanCompiler
import com.kunk.singbox.model.TrafficCaptureMode
import com.kunk.singbox.model.VpnAppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
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
                "package:android uid:1000\n" +
                    "package:oplus\tuid:1001\n" +
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

    @Test
    fun resolvesLaneForEveryUserAndEveryPackageSharingUid() {
        val plan = RootAppRoutingPlanCompiler.compile(
            settings = AppSettings(
                trafficCaptureMode = TrafficCaptureMode.ROOT_TRANSPARENT,
                vpnAppMode = VpnAppMode.ALL
            ),
            assignments = listOf(
                RootAppRoutingAssignment(
                    packageNames = listOf("com.example.proxy"),
                    targetKind = "OUTBOUND",
                    outboundTag = "germany",
                    sourceLabel = "proxy"
                )
            ),
            generation = 1L
        )

        val resolved = RootUidResolver(executor).resolveRouting(plan, "com.kunk.singbox", 10234)
        val laneId = plan.lanes.single().laneId

        assertEquals(listOf(10123, 1010123), resolved.laneUids.getValue(laneId))
        assertEquals(
            laneId,
            resolved.routes.single { it.packageName == "com.example.shared" }.laneId
        )
        assertEquals(64, resolved.resolvedPlanSha256.length)
    }

    @Test
    fun rejectsSharedUidMappedToDifferentLanes() {
        val plan = RootAppRoutingPlanCompiler.compile(
            settings = AppSettings(
                trafficCaptureMode = TrafficCaptureMode.ROOT_TRANSPARENT,
                vpnAppMode = VpnAppMode.ALL
            ),
            assignments = listOf(
                RootAppRoutingAssignment(
                    packageNames = listOf("com.example.proxy"),
                    targetKind = "OUTBOUND",
                    outboundTag = "germany",
                    sourceLabel = "proxy"
                ),
                RootAppRoutingAssignment(
                    packageNames = listOf("com.example.shared"),
                    targetKind = "OUTBOUND",
                    outboundTag = "usa",
                    sourceLabel = "shared"
                )
            ),
            generation = 2L
        )

        assertThrows(IllegalStateException::class.java) {
            RootUidResolver(executor).resolveRouting(plan, "com.kunk.singbox", 10234)
        }
    }

    @Test
    fun resolvedDigestChangesWhenInstalledUidDrifts() {
        var packageUid = 10123
        val mutableExecutor = RootCommandExecutor { command ->
            when {
                command == listOf("cmd", "user", "list") -> RootCommandResult(0, "UserInfo{0:Owner:13}")
                command.lastOrNull() == "0" -> RootCommandResult(
                    0,
                    "package:com.example.proxy uid:$packageUid\npackage:com.kunk.singbox uid:10234"
                )
                else -> RootCommandResult(1, "unexpected")
            }
        }
        val plan = RootAppRoutingPlanCompiler.compile(
            AppSettings(
                trafficCaptureMode = TrafficCaptureMode.ROOT_TRANSPARENT,
                vpnAppMode = VpnAppMode.ALL
            ),
            listOf(
                RootAppRoutingAssignment(
                    packageNames = listOf("com.example.proxy"),
                    targetKind = "OUTBOUND",
                    outboundTag = "germany",
                    sourceLabel = "proxy"
                )
            ),
            3L
        )
        val resolver = RootUidResolver(mutableExecutor)
        val first = resolver.resolveRouting(plan, "com.kunk.singbox", 10234)

        packageUid = 10133
        val second = resolver.resolveRouting(plan, "com.kunk.singbox", 10234)

        assertNotEquals(first.resolvedPlanSha256, second.resolvedPlanSha256)
        assertEquals(listOf(10133), second.laneUids.getValue(plan.lanes.single().laneId))
    }

    @Test
    fun ignoresExplicitLaneForMissingPackage() {
        val plan = RootAppRoutingPlanCompiler.compile(
            AppSettings(trafficCaptureMode = TrafficCaptureMode.ROOT_TRANSPARENT),
            listOf(
                RootAppRoutingAssignment(
                    packageNames = listOf("com.example.missing"),
                    targetKind = "OUTBOUND",
                    outboundTag = "germany",
                    sourceLabel = "missing"
                )
            ),
            4L
        )

        val resolved = RootUidResolver(executor).resolveRouting(plan, "com.kunk.singbox", 10234)

        assertEquals(emptyMap<String, List<Int>>(), resolved.laneUids)
        assertEquals(64, resolved.resolvedPlanSha256.length)
    }

    @Test
    fun ignoresUninstalledHistoricalAllowAndBlockListEntries() {
        listOf(VpnAppMode.ALLOWLIST, VpnAppMode.BLOCKLIST).forEach { mode ->
            val settings = AppSettings(
                trafficCaptureMode = TrafficCaptureMode.ROOT_TRANSPARENT,
                vpnAppMode = mode,
                vpnAllowlist = "com.example.removed",
                vpnBlocklist = "com.example.removed"
            )
            val plan = RootAppRoutingPlanCompiler.compile(settings, emptyList(), generation = 5L)

            val resolved = RootUidResolver(executor).resolveRouting(
                plan,
                selfPackage = "com.kunk.singbox",
                selfUid = 10234
            )

            assertEquals(64, resolved.resolvedPlanSha256.length)
        }
    }
}
