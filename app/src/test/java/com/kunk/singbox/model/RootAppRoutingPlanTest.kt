package com.kunk.singbox.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootAppRoutingPlanTest {
    @Test
    fun mergesSameTargetAndAssignsStablePortsMarksAndDigests() {
        val settings = AppSettings(
            trafficCaptureMode = TrafficCaptureMode.ROOT_TRANSPARENT,
            routingMode = RoutingMode.RULE,
            appRules = listOf(
                AppRule(id = "b", packageName = "org.telegram.messenger", appName = "Telegram"),
                AppRule(id = "a", packageName = "com.google.android.youtube", appName = "YouTube")
            )
        )
        val assignments = listOf(
            RootAppRoutingAssignment(
                packageNames = listOf("org.telegram.messenger"),
                targetKind = "OUTBOUND",
                outboundTag = "germany-node",
                sourceLabel = "Telegram"
            ),
            RootAppRoutingAssignment(
                packageNames = listOf("com.google.android.youtube"),
                targetKind = "OUTBOUND",
                outboundTag = "germany-node",
                sourceLabel = "YouTube"
            )
        )

        val plan = RootAppRoutingPlanCompiler.compile(settings, assignments, generation = 7L)
        val lane = plan.lanes.single()

        assertEquals(listOf("com.google.android.youtube", "org.telegram.messenger"), lane.packageNames)
        assertEquals(16_000, lane.tcpPortIpv4)
        assertEquals(16_001, lane.udpPortIpv4)
        assertEquals(16_002, lane.tcpPortIpv6)
        assertEquals(16_003, lane.udpPortIpv6)
        assertEquals(0x2400, lane.markIpv4)
        assertEquals(0x2500, lane.markIpv6)
        assertEquals(12_100, lane.priorityIpv4)
        assertEquals(12_300, lane.priorityIpv6)
        assertEquals(plan.staticPlanSha256, RootAppRoutingCanonical.staticPlanSha256(plan))
        assertEquals(plan.appRoutingSha256, RootAppRoutingCanonical.appRoutingSha256(plan))
        assertTrue(plan.staticPlanSha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun rejectsSamePackageWithDifferentTargets() {
        val settings = AppSettings(trafficCaptureMode = TrafficCaptureMode.ROOT_TRANSPARENT)
        val first = RootAppRoutingAssignment(
            packageNames = listOf("org.telegram.messenger"),
            targetKind = "OUTBOUND",
            outboundTag = "germany",
            sourceLabel = "first"
        )
        val second = first.copy(outboundTag = "hong-kong", sourceLabel = "second")

        assertThrows(IllegalArgumentException::class.java) {
            RootAppRoutingPlanCompiler.compile(settings, listOf(first, second), 1L)
        }
    }

    @Test
    fun resolvedDigestIsOrderIndependentAndRejectsDuplicates() {
        val plan = RootAppRoutingPlanCompiler.compile(
            settings = AppSettings(trafficCaptureMode = TrafficCaptureMode.ROOT_TRANSPARENT),
            assignments = listOf(
                RootAppRoutingAssignment(
                    packageNames = listOf("org.telegram.messenger"),
                    targetKind = "OUTBOUND",
                    outboundTag = "germany",
                    sourceLabel = "Telegram"
                )
            ),
            generation = 9L
        )
        val first = RootResolvedUidRoute(0, 10123, "org.telegram.messenger", plan.lanes.single().laneId)
        val second = RootResolvedUidRoute(10, 1_010_123, "org.telegram.messenger", plan.lanes.single().laneId)

        assertEquals(
            RootAppRoutingCanonical.resolvedPlanSha256(plan, listOf(first, second)),
            RootAppRoutingCanonical.resolvedPlanSha256(plan, listOf(second, first))
        )
        assertNotEquals(
            RootAppRoutingCanonical.resolvedPlanSha256(plan, listOf(first)),
            RootAppRoutingCanonical.resolvedPlanSha256(plan, listOf(second))
        )
        assertThrows(IllegalArgumentException::class.java) {
            RootAppRoutingCanonical.resolvedPlanSha256(plan, listOf(first, first))
        }
    }

    @Test
    fun acceptsReorderedJsonObjectFields() {
        val plan = boundPlan()
        val root = Gson().toJsonTree(plan).asJsonObject
        root.add("schema", root.remove("schema"))
        root.getAsJsonArray("lanes").first().asJsonObject.apply {
            add("laneId", remove("laneId"))
        }

        assertEquals(plan, RootRoutingArtifactValidator.requireBoundPlanJson(root.toString()))
    }

    @Test
    fun stillRejectsMissingOrUnknownJsonObjectFields() {
        val planJson = Gson().toJsonTree(boundPlan()).asJsonObject
        val missing = planJson.deepCopy().apply { remove("schema") }
        val unknown = planJson.deepCopy().apply { addProperty("unknown", true) }

        assertThrows(IllegalStateException::class.java) {
            RootRoutingArtifactValidator.requireBoundPlanJson(missing.toString())
        }
        assertThrows(IllegalStateException::class.java) {
            RootRoutingArtifactValidator.requireBoundPlanJson(unknown.toString())
        }
    }

    @Test
    fun acceptsAndroidFrameworkPackageButRejectsOtherSingleSegmentNames() {
        requireValidRootPackageName("android")

        assertThrows(IllegalArgumentException::class.java) {
            requireValidRootPackageName("untrusted")
        }
    }

    private fun boundPlan(): RootAppRoutingPlan = RootAppRoutingPlanCompiler.compile(
        settings = AppSettings(trafficCaptureMode = TrafficCaptureMode.ROOT_TRANSPARENT),
        assignments = listOf(
            RootAppRoutingAssignment(
                packageNames = listOf("org.telegram.messenger"),
                targetKind = "OUTBOUND",
                outboundTag = "germany",
                sourceLabel = "Telegram"
            )
        ),
        generation = 11L
    ).copy(configFileSha256 = "0".repeat(64))
}
