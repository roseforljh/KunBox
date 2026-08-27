package com.kunk.singbox.model

import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.security.MessageDigest

object RootRoutingConstants {
    const val SCHEMA = 1
    const val NETFILTER_SEMANTIC_VERSION = 1
    const val MAX_LANES = 128
    const val LANE_PORT_BASE = 16_000
    const val PORTS_PER_LANE = 4
    const val ROUTE_TABLE = 20_231
    const val ROUTE_PROTOCOL = 233
    const val MAX_PACKAGE_NAMES = 4_096
    const val MAX_SIDECAR_BYTES = 256 * 1_024
    const val GENERIC_MARK_IPV4 = 0x2331
    const val GENERIC_MARK_IPV6 = 0x2332
    const val LANE_MARK_IPV4_BASE = 0x2400
    const val LANE_MARK_IPV6_BASE = 0x2500
    const val GENERIC_PRIORITY_IPV4 = 12_031
    const val GENERIC_PRIORITY_IPV6 = 12_032
    const val LANE_PRIORITY_IPV4_BASE = 12_100
    const val LANE_PRIORITY_IPV6_BASE = 12_300

    fun tcpPortIpv4(slot: Int): Int = lanePort(slot, 0)
    fun udpPortIpv4(slot: Int): Int = lanePort(slot, 1)
    fun tcpPortIpv6(slot: Int): Int = lanePort(slot, 2)
    fun udpPortIpv6(slot: Int): Int = lanePort(slot, 3)
    fun markIpv4(slot: Int): Int = LANE_MARK_IPV4_BASE + checkedSlot(slot)
    fun markIpv6(slot: Int): Int = LANE_MARK_IPV6_BASE + checkedSlot(slot)
    fun priorityIpv4(slot: Int): Int = LANE_PRIORITY_IPV4_BASE + checkedSlot(slot)
    fun priorityIpv6(slot: Int): Int = LANE_PRIORITY_IPV6_BASE + checkedSlot(slot)

    private fun lanePort(slot: Int, offset: Int): Int =
        LANE_PORT_BASE + checkedSlot(slot) * PORTS_PER_LANE + offset

    private fun checkedSlot(slot: Int): Int {
        require(slot in 0 until MAX_LANES) { "Root lane slot out of range: $slot" }
        return slot
    }
}

@Keep
data class RootAppRoutingPlan(
    val schema: Int = RootRoutingConstants.SCHEMA,
    val generation: Long,
    val netfilterSemanticVersion: Int = RootRoutingConstants.NETFILTER_SEMANTIC_VERSION,
    val staticPlanSha256: String = "",
    val appRoutingSha256: String = "",
    val configFileSha256: String = "",
    val routingMode: String,
    val vpnAppMode: String,
    val policyRevision: Long,
    val allowlist: List<String>,
    val blocklist: List<String>,
    val appRules: List<RootAppRuleSnapshot>,
    val appGroups: List<RootAppGroupSnapshot>,
    val ipVersionMode: String,
    val proxyIpv4: Boolean,
    val proxyIpv6: Boolean,
    val lanes: List<RootAppRouteLane>
)

@Keep
data class RootAppRuleSnapshot(
    val id: String,
    val enabled: Boolean,
    val packageName: String,
    val appName: String,
    val outboundMode: String,
    val outboundValue: String
)

@Keep
data class RootAppGroupSnapshot(
    val id: String,
    val enabled: Boolean,
    val name: String,
    val outboundMode: String,
    val outboundValue: String,
    val apps: List<RootAppInfoSnapshot>
)

@Keep
data class RootAppInfoSnapshot(
    val packageName: String,
    val appName: String
)

@Keep
data class RootAppRouteLane(
    val laneId: String,
    val slot: Int,
    val targetKind: String,
    val outboundTag: String,
    val routeAction: String,
    val packageNames: List<String>,
    val tcpPortIpv4: Int,
    val tcpPortIpv6: Int,
    val udpPortIpv4: Int,
    val udpPortIpv6: Int,
    val markIpv4: Int,
    val markIpv6: Int,
    val priorityIpv4: Int,
    val priorityIpv6: Int,
    val tcpInboundIpv4: String,
    val tcpInboundIpv6: String,
    val udpInboundIpv4: String,
    val udpInboundIpv6: String
) {
    val targetKey: String
        get() = listOf(targetKind, outboundTag, routeAction).joinToString(":")

    fun inboundTags(proxyIpv4: Boolean, proxyIpv6: Boolean): List<String> = buildList {
        if (proxyIpv4) addAll(listOf(tcpInboundIpv4, udpInboundIpv4))
        if (proxyIpv6) addAll(listOf(tcpInboundIpv6, udpInboundIpv6))
    }
}

data class RootAppRoutingAssignment(
    val packageNames: List<String>,
    val targetKind: String,
    val outboundTag: String = "",
    val routeAction: String = "",
    val sourceLabel: String
) {
    val targetKey: String
        get() = listOf(targetKind, outboundTag, routeAction).joinToString(":")
}

data class RootResolvedUidRoute(
    val userId: Int,
    val uid: Int,
    val packageName: String,
    val laneId: String
)

@Keep
data class RootRoutingManifest(
    val schema: Int = RootRoutingConstants.SCHEMA,
    val generation: Long,
    val configLength: Long,
    val configFileSha256: String,
    val sidecarLength: Long,
    val sidecarFileSha256: String,
    val staticPlanSha256: String,
    val appRoutingSha256: String
)

object RootAppRoutingPlanCompiler {
    @Suppress("LongMethod")
    fun compile(
        settings: AppSettings,
        assignments: List<RootAppRoutingAssignment>,
        generation: Long
    ): RootAppRoutingPlan {
        require(generation > 0L) { "Root routing generation must be positive" }
        val packageTargets = linkedMapOf<String, Pair<String, String>>()
        assignments.forEach { assignment ->
            requireTarget(assignment)
            assignment.packageNames.normalizePackageNames().forEach { packageName ->
                val previous = packageTargets.putIfAbsent(
                    packageName,
                    assignment.targetKey to assignment.sourceLabel
                )
                require(previous == null || previous.first == assignment.targetKey) {
                    "应用分流冲突：$packageName 同时由「${previous?.second}」和「${assignment.sourceLabel}」" +
                        "指向不同目标 ${previous?.first} / ${assignment.targetKey}，已阻止启动"
                }
            }
        }
        val targetByKey = assignments.associateBy(RootAppRoutingAssignment::targetKey)
        val groupedPackages = packageTargets.entries.groupBy({ it.value.first }, { it.key })
        require(groupedPackages.size <= RootRoutingConstants.MAX_LANES) {
            "Root 应用分流目标数量 ${groupedPackages.size} 超过上限 ${RootRoutingConstants.MAX_LANES}"
        }
        val proxyIpv4 = settings.ipVersionMode != IpVersionMode.IPV6_ONLY
        val proxyIpv6 = settings.ipVersionMode != IpVersionMode.IPV4_ONLY
        val lanes = groupedPackages.keys.sortedWith(::compareRootUtf8).mapIndexed { slot, targetKey ->
            val assignment = requireNotNull(targetByKey[targetKey])
            buildLane(slot, assignment, groupedPackages.getValue(targetKey).sortedWith(::compareRootUtf8))
        }
        val policy = PerAppVpnPolicy.from(settings)
        val plan = RootAppRoutingPlan(
            generation = generation,
            routingMode = settings.routingMode.name,
            vpnAppMode = policy.mode.name,
            policyRevision = policy.revision,
            allowlist = policy.allowlist.sortedWith(::compareRootUtf8),
            blocklist = policy.blocklist.sortedWith(::compareRootUtf8),
            appRules = settings.appRules.sortedBy(AppRule::id).map { rule ->
                RootAppRuleSnapshot(
                    id = rule.id,
                    enabled = rule.enabled,
                    packageName = rule.packageName.trim(),
                    appName = rule.appName,
                    outboundMode = rule.outboundMode?.name.orEmpty(),
                    outboundValue = rule.outboundValue.orEmpty()
                )
            },
            appGroups = settings.appGroups.sortedBy(AppGroup::id).map { group ->
                RootAppGroupSnapshot(
                    id = group.id,
                    enabled = group.enabled,
                    name = group.name,
                    outboundMode = group.outboundMode?.name.orEmpty(),
                    outboundValue = group.outboundValue.orEmpty(),
                    apps = group.apps.sortedWith { left, right ->
                        compareRootUtf8(left.packageName, right.packageName)
                    }.map { app ->
                        RootAppInfoSnapshot(app.packageName.trim(), app.appName)
                    }
                )
            },
            ipVersionMode = settings.ipVersionMode.name,
            proxyIpv4 = proxyIpv4,
            proxyIpv6 = proxyIpv6,
            lanes = lanes
        )
        return plan.copy(
            staticPlanSha256 = RootAppRoutingCanonical.staticPlanSha256(plan),
            appRoutingSha256 = RootAppRoutingCanonical.appRoutingSha256(plan)
        )
    }

    private fun buildLane(
        slot: Int,
        assignment: RootAppRoutingAssignment,
        packageNames: List<String>
    ): RootAppRouteLane {
        val suffix = slot.toString().padStart(3, '0')
        return RootAppRouteLane(
            laneId = "root-lane-$suffix",
            slot = slot,
            targetKind = assignment.targetKind,
            outboundTag = assignment.outboundTag,
            routeAction = assignment.routeAction,
            packageNames = packageNames,
            tcpPortIpv4 = RootRoutingConstants.tcpPortIpv4(slot),
            tcpPortIpv6 = RootRoutingConstants.tcpPortIpv6(slot),
            udpPortIpv4 = RootRoutingConstants.udpPortIpv4(slot),
            udpPortIpv6 = RootRoutingConstants.udpPortIpv6(slot),
            markIpv4 = RootRoutingConstants.markIpv4(slot),
            markIpv6 = RootRoutingConstants.markIpv6(slot),
            priorityIpv4 = RootRoutingConstants.priorityIpv4(slot),
            priorityIpv6 = RootRoutingConstants.priorityIpv6(slot),
            tcpInboundIpv4 = "root-lane-$suffix-tcp-v4",
            tcpInboundIpv6 = "root-lane-$suffix-tcp-v6",
            udpInboundIpv4 = "root-lane-$suffix-udp-v4",
            udpInboundIpv6 = "root-lane-$suffix-udp-v6"
        )
    }

    private fun requireTarget(assignment: RootAppRoutingAssignment) {
        require(assignment.sourceLabel.isNotBlank()) { "Root app route source label is empty" }
        require(assignment.packageNames.isNotEmpty()) { "Root app route has no package" }
        require(assignment.targetKind in setOf("OUTBOUND", "DIRECT", "BLOCK")) {
            "Invalid Root app route target kind: ${assignment.targetKind}"
        }
        when (assignment.targetKind) {
            "OUTBOUND" -> require(assignment.outboundTag.isNotBlank() && assignment.routeAction.isBlank())
            "DIRECT" -> require(assignment.outboundTag == "direct" && assignment.routeAction.isBlank())
            "BLOCK" -> require(assignment.outboundTag.isBlank() && assignment.routeAction == "reject")
        }
    }
}

object RootRoutingArtifactValidator {
    private val planFields = listOf(
        "schema", "generation", "netfilterSemanticVersion", "staticPlanSha256", "appRoutingSha256",
        "configFileSha256", "routingMode", "vpnAppMode", "policyRevision", "allowlist", "blocklist",
        "appRules", "appGroups", "ipVersionMode", "proxyIpv4", "proxyIpv6", "lanes"
    )
    private val ruleFields = listOf("id", "enabled", "packageName", "appName", "outboundMode", "outboundValue")
    private val groupFields = listOf("id", "enabled", "name", "outboundMode", "outboundValue", "apps")
    private val appFields = listOf("packageName", "appName")
    private val laneFields = listOf(
        "laneId", "slot", "targetKind", "outboundTag", "routeAction", "packageNames",
        "tcpPortIpv4", "tcpPortIpv6", "udpPortIpv4", "udpPortIpv6", "markIpv4", "markIpv6",
        "priorityIpv4", "priorityIpv6", "tcpInboundIpv4", "tcpInboundIpv6", "udpInboundIpv4", "udpInboundIpv6"
    )
    private val manifestFields = listOf(
        "schema", "generation", "configLength", "configFileSha256", "sidecarLength",
        "sidecarFileSha256", "staticPlanSha256", "appRoutingSha256"
    )

    @Suppress("LongMethod")
    fun requireBoundPlanJson(raw: String): RootAppRoutingPlan {
        check(raw.toByteArray(Charsets.UTF_8).size <= RootRoutingConstants.MAX_SIDECAR_BYTES) {
            "Root routing sidecar exceeds ${RootRoutingConstants.MAX_SIDECAR_BYTES} bytes"
        }
        val json = JsonParser.parseString(raw)
        val objectValue = requireObject(json, "Root routing sidecar")
        requireFields(objectValue, planFields, "Root routing sidecar")
        requireNumber(objectValue, "schema")
        requireNumber(objectValue, "generation")
        requireNumber(objectValue, "netfilterSemanticVersion")
        requireString(objectValue, "staticPlanSha256")
        requireString(objectValue, "appRoutingSha256")
        requireString(objectValue, "configFileSha256")
        requireString(objectValue, "routingMode")
        requireString(objectValue, "vpnAppMode")
        requireNumber(objectValue, "policyRevision")
        requireStringArray(objectValue, "allowlist")
        requireStringArray(objectValue, "blocklist")
        requireBoolean(objectValue, "proxyIpv4")
        requireBoolean(objectValue, "proxyIpv6")
        requireString(objectValue, "ipVersionMode")

        val rules = requireArray(objectValue, "appRules")
        rules.forEachIndexed { index, element ->
            val rule = requireObject(element, "Root app rule[$index]")
            requireFields(rule, ruleFields, "Root app rule[$index]")
            requireString(rule, "id")
            requireBoolean(rule, "enabled")
            requireString(rule, "packageName")
            requireString(rule, "appName")
            requireString(rule, "outboundMode")
            requireString(rule, "outboundValue")
        }
        val groups = requireArray(objectValue, "appGroups")
        groups.forEachIndexed { index, element ->
            val group = requireObject(element, "Root app group[$index]")
            requireFields(group, groupFields, "Root app group[$index]")
            requireString(group, "id")
            requireBoolean(group, "enabled")
            requireString(group, "name")
            requireString(group, "outboundMode")
            requireString(group, "outboundValue")
            requireArray(group, "apps").forEachIndexed { appIndex, appElement ->
                val app = requireObject(appElement, "Root app group[$index].apps[$appIndex]")
                requireFields(app, appFields, "Root app group[$index].apps[$appIndex]")
                requireString(app, "packageName")
                requireString(app, "appName")
            }
        }
        requireArray(objectValue, "lanes").forEachIndexed { index, element ->
            val lane = requireObject(element, "Root lane[$index]")
            requireFields(lane, laneFields, "Root lane[$index]")
            requireString(lane, "laneId")
            requireNumber(lane, "slot")
            requireString(lane, "targetKind")
            requireString(lane, "outboundTag")
            requireString(lane, "routeAction")
            requireStringArray(lane, "packageNames")
            listOf(
                "tcpPortIpv4", "tcpPortIpv6", "udpPortIpv4", "udpPortIpv6", "markIpv4", "markIpv6",
                "priorityIpv4", "priorityIpv6"
            ).forEach { requireNumber(lane, it) }
            listOf("tcpInboundIpv4", "tcpInboundIpv6", "udpInboundIpv4", "udpInboundIpv6")
                .forEach { requireString(lane, it) }
        }

        val plan = Gson().fromJson(objectValue, RootAppRoutingPlan::class.java)
            ?: error("Root routing sidecar is empty")
        requireBoundPlan(plan)
        return plan
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun requireBoundPlan(plan: RootAppRoutingPlan) {
        check(plan.schema == RootRoutingConstants.SCHEMA) { "Root routing schema mismatch" }
        check(plan.generation > 0L) { "Root routing generation is invalid" }
        check(plan.netfilterSemanticVersion == RootRoutingConstants.NETFILTER_SEMANTIC_VERSION) {
            "Root netfilter semantic version mismatch"
        }
        check(plan.routingMode in RoutingMode.entries.map(RoutingMode::name)) { "Root routing mode is invalid" }
        check(plan.vpnAppMode in VpnAppMode.entries.map(VpnAppMode::name)) { "Root VPN app mode is invalid" }
        check(plan.policyRevision >= 0L) { "Root policy revision is invalid" }
        listOf(plan.staticPlanSha256, plan.appRoutingSha256, plan.configFileSha256).forEach {
            check(isRootSha256(it)) { "Root routing digest is malformed" }
        }
        check(plan.ipVersionMode in IpVersionMode.entries.map(IpVersionMode::name)) {
            "Root IP version mode is invalid"
        }
        check(plan.proxyIpv4 || plan.proxyIpv6) { "Root routing has no enabled IP family" }
        checkSortedPackages(plan.allowlist, "Root allowlist")
        checkSortedPackages(plan.blocklist, "Root blocklist")
        check(plan.proxyIpv4 == (plan.ipVersionMode != IpVersionMode.IPV6_ONLY.name)) {
            "Root IPv4 family does not match IP version mode"
        }
        check(plan.proxyIpv6 == (plan.ipVersionMode != IpVersionMode.IPV4_ONLY.name)) {
            "Root IPv6 family does not match IP version mode"
        }
        val allPackages = plan.lanes.flatMap(RootAppRouteLane::packageNames)
        check(allPackages.distinct().size == allPackages.size) { "Root lane package is duplicated" }
        check(allPackages.size <= RootRoutingConstants.MAX_PACKAGE_NAMES) {
            "Root package count exceeds limit"
        }
        check(plan.lanes.size <= RootRoutingConstants.MAX_LANES) { "Root lane count exceeds limit" }
        check(plan.lanes.map(RootAppRouteLane::slot).distinct().size == plan.lanes.size) {
            "Root lane slot is duplicated"
        }
        check(plan.lanes.map(RootAppRouteLane::laneId).distinct().size == plan.lanes.size) {
            "Root lane ID is duplicated"
        }
        check(plan.lanes.map(RootAppRouteLane::targetKey).distinct().size == plan.lanes.size) {
            "Root lane target is duplicated"
        }
        when (plan.vpnAppMode) {
            VpnAppMode.ALL.name -> Unit
            VpnAppMode.ALLOWLIST.name -> check(allPackages.all { it in plan.allowlist }) {
                "Root allowlist lane contains an unselected package"
            }
            VpnAppMode.BLOCKLIST.name -> check(allPackages.none { it in plan.blocklist }) {
                "Root blocklist lane contains an excluded package"
            }
        }
        check(plan.lanes.map(RootAppRouteLane::slot) == plan.lanes.map(RootAppRouteLane::slot).sorted()) {
            "Root lanes are not ordered by slot"
        }
        check(plan.appRules.map(RootAppRuleSnapshot::id) == plan.appRules.map(RootAppRuleSnapshot::id).sorted()) {
            "Root app rules are not ordered by ID"
        }
        check(plan.appGroups.map(RootAppGroupSnapshot::id) == plan.appGroups.map(RootAppGroupSnapshot::id).sorted()) {
            "Root app groups are not ordered by ID"
        }
        plan.allowlist.forEach(::requireValidRootPackageName)
        plan.blocklist.forEach(::requireValidRootPackageName)
        plan.appRules.forEach { rule ->
            check(rule.id.isNotBlank()) { "Root app rule ID is empty" }
            if (rule.enabled || rule.packageName.isNotBlank()) requireValidRootPackageName(rule.packageName)
        }
        plan.appGroups.forEach { group ->
            check(group.id.isNotBlank()) { "Root app group ID is empty" }
            check(group.apps.map(RootAppInfoSnapshot::packageName) ==
                group.apps.map(RootAppInfoSnapshot::packageName).sortedWith(::compareRootUtf8)) {
                "Root app group apps are not ordered"
            }
            group.apps.forEach { app -> requireValidRootPackageName(app.packageName) }
        }
        val inboundTags = mutableSetOf<String>()
        plan.lanes.forEach { lane ->
            check(lane.slot in 0 until RootRoutingConstants.MAX_LANES) { "Root lane slot is out of range" }
            check(lane.packageNames.isNotEmpty()) { "Root lane has no package" }
            checkSortedPackages(lane.packageNames, "Root lane ${lane.laneId} packages")
            lane.packageNames.forEach(::requireValidRootPackageName)
            check(lane.laneId == "root-lane-${lane.slot.toString().padStart(3, '0')}") {
                "Root lane ID does not match slot: ${lane.laneId}"
            }
            check(lane.targetKind in setOf("OUTBOUND", "DIRECT", "BLOCK")) {
                "Root lane target kind is invalid: ${lane.laneId}"
            }
            when (lane.targetKind) {
                "OUTBOUND" -> {
                    check(lane.outboundTag.isNotBlank() && lane.routeAction.isBlank())
                    requireSafeRootTag(lane.outboundTag, "${lane.laneId}.outboundTag")
                }
                "DIRECT" -> check(lane.outboundTag == "direct" && lane.routeAction.isBlank())
                "BLOCK" -> check(lane.outboundTag.isBlank() && lane.routeAction == "reject")
            }
            check(lane.tcpPortIpv4 == RootRoutingConstants.tcpPortIpv4(lane.slot))
            check(lane.udpPortIpv4 == RootRoutingConstants.udpPortIpv4(lane.slot))
            check(lane.tcpPortIpv6 == RootRoutingConstants.tcpPortIpv6(lane.slot))
            check(lane.udpPortIpv6 == RootRoutingConstants.udpPortIpv6(lane.slot))
            check(lane.markIpv4 == RootRoutingConstants.markIpv4(lane.slot))
            check(lane.markIpv6 == RootRoutingConstants.markIpv6(lane.slot))
            check(lane.priorityIpv4 == RootRoutingConstants.priorityIpv4(lane.slot))
            check(lane.priorityIpv6 == RootRoutingConstants.priorityIpv6(lane.slot))
            val suffix = lane.slot.toString().padStart(3, '0')
            check(lane.tcpInboundIpv4 == "root-lane-$suffix-tcp-v4")
            check(lane.tcpInboundIpv6 == "root-lane-$suffix-tcp-v6")
            check(lane.udpInboundIpv4 == "root-lane-$suffix-udp-v4")
            check(lane.udpInboundIpv6 == "root-lane-$suffix-udp-v6")
            lane.inboundTags(plan.proxyIpv4, plan.proxyIpv6).forEach { tag ->
                check(tag.isNotBlank() && inboundTags.add(tag)) { "Root lane inbound is duplicated" }
            }
        }
        check(plan.staticPlanSha256 == RootAppRoutingCanonical.staticPlanSha256(plan)) {
            "Root static plan digest mismatch"
        }
        check(plan.appRoutingSha256 == RootAppRoutingCanonical.appRoutingSha256(plan)) {
            "Root app routing digest mismatch"
        }
    }

    fun requireManifestJson(raw: String): RootRoutingManifest {
        val objectValue = requireObject(JsonParser.parseString(raw), "Root routing manifest")
        requireFields(objectValue, manifestFields, "Root routing manifest")
        requireNumber(objectValue, "schema")
        requireNumber(objectValue, "generation")
        requireNumber(objectValue, "configLength")
        requireString(objectValue, "configFileSha256")
        requireNumber(objectValue, "sidecarLength")
        requireString(objectValue, "sidecarFileSha256")
        requireString(objectValue, "staticPlanSha256")
        requireString(objectValue, "appRoutingSha256")
        val manifest = Gson().fromJson(objectValue, RootRoutingManifest::class.java)
            ?: error("Root routing manifest is empty")
        check(manifest.schema == RootRoutingConstants.SCHEMA) { "Root manifest schema mismatch" }
        check(manifest.generation > 0L) { "Root manifest generation is invalid" }
        check(manifest.configLength >= 0L && manifest.sidecarLength >= 0L) {
            "Root manifest length is invalid"
        }
        listOf(
            manifest.configFileSha256,
            manifest.sidecarFileSha256,
            manifest.staticPlanSha256,
            manifest.appRoutingSha256
        ).forEach { check(isRootSha256(it)) { "Root manifest digest is malformed" } }
        return manifest
    }

    private fun requireFields(
        objectValue: com.google.gson.JsonObject,
        expected: List<String>,
        label: String
    ) {
        check(objectValue.keySet().toList() == expected) { "$label contains missing, unknown, or reordered fields" }
    }

    private fun requireObject(element: JsonElement, label: String): com.google.gson.JsonObject {
        check(element.isJsonObject) { "$label must be an object" }
        return element.asJsonObject
    }

    private fun requireArray(objectValue: com.google.gson.JsonObject, field: String): com.google.gson.JsonArray {
        val element = objectValue.get(field)
        check(element != null && element.isJsonArray) { "Root field $field must be an array" }
        return element.asJsonArray
    }

    private fun requireStringArray(objectValue: com.google.gson.JsonObject, field: String) {
        requireArray(objectValue, field).forEach { element ->
            check(element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                "Root field $field must contain strings"
            }
        }
    }

    private fun requireString(objectValue: com.google.gson.JsonObject, field: String) {
        val element = objectValue.get(field)
        check(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            "Root field $field must be a string"
        }
    }

    private fun requireNumber(objectValue: com.google.gson.JsonObject, field: String) {
        val element = objectValue.get(field)
        check(
            element != null && element.isJsonPrimitive && element.asJsonPrimitive.isNumber &&
                element.asString.toLongOrNull() != null
        ) {
            "Root field $field must be a number"
        }
    }

    private fun requireBoolean(objectValue: com.google.gson.JsonObject, field: String) {
        val element = objectValue.get(field)
        check(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) {
            "Root field $field must be a boolean"
        }
    }

    private fun checkSortedPackages(values: List<String>, label: String) {
        check(values.distinct().size == values.size) { "$label contains duplicates" }
        check(values == values.sortedWith(::compareRootUtf8)) { "$label is not ordered" }
    }

    private fun requireSafeRootTag(value: String, label: String) {
        check(value.length in 1..255 && value.all { it.code in 0x21..0x7e && it !in "'\\" }) {
            "$label is invalid"
        }
    }
}

object RootAppRoutingCanonical {
    fun staticPlanSha256(plan: RootAppRoutingPlan): String = sha256(staticPlanBytes(plan))

    fun appRoutingSha256(plan: RootAppRoutingPlan): String = sha256(appRoutingBytes(plan))

    fun resolvedPlanSha256(
        plan: RootAppRoutingPlan,
        routes: List<RootResolvedUidRoute>
    ): String = sha256(resolvedPlanBytes(plan, routes))

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    fun staticPlanBytes(plan: RootAppRoutingPlan): ByteArray = CanonicalWriter().apply {
        number("schema", plan.schema.toLong())
        number("netfilterSemanticVersion", plan.netfilterSemanticVersion.toLong())
        string("routingMode", plan.routingMode)
        string("vpnAppMode", plan.vpnAppMode)
        number("policyRevision", plan.policyRevision)
        number("laneCount", plan.lanes.size.toLong())
        plan.lanes.sortedBy(RootAppRouteLane::slot).forEachIndexed { index, lane ->
            val prefix = "lane.$index"
            string("$prefix.laneId", lane.laneId)
            number("$prefix.slot", lane.slot.toLong())
            string("$prefix.targetKind", lane.targetKind)
            string("$prefix.outboundTag", lane.outboundTag)
            string("$prefix.routeAction", lane.routeAction)
            strings("$prefix.packageNames", lane.packageNames)
            number("$prefix.tcpPortIpv4", lane.tcpPortIpv4.toLong())
            number("$prefix.tcpPortIpv6", lane.tcpPortIpv6.toLong())
            number("$prefix.udpPortIpv4", lane.udpPortIpv4.toLong())
            number("$prefix.udpPortIpv6", lane.udpPortIpv6.toLong())
            number("$prefix.markIpv4", lane.markIpv4.toLong())
            number("$prefix.markIpv6", lane.markIpv6.toLong())
            number("$prefix.priorityIpv4", lane.priorityIpv4.toLong())
            number("$prefix.priorityIpv6", lane.priorityIpv6.toLong())
            string("$prefix.tcpInboundIpv4", lane.tcpInboundIpv4)
            string("$prefix.tcpInboundIpv6", lane.tcpInboundIpv6)
            string("$prefix.udpInboundIpv4", lane.udpInboundIpv4)
            string("$prefix.udpInboundIpv6", lane.udpInboundIpv6)
        }
    }.bytes()

    fun appRoutingBytes(plan: RootAppRoutingPlan): ByteArray = CanonicalWriter().apply {
        string("routingMode", plan.routingMode)
        string("vpnAppMode", plan.vpnAppMode)
        number("policyRevision", plan.policyRevision)
        strings("allowlist", plan.allowlist)
        strings("blocklist", plan.blocklist)
        number("appRules.count", plan.appRules.size.toLong())
        plan.appRules.sortedBy(RootAppRuleSnapshot::id).forEachIndexed { index, rule ->
            val prefix = "appRules.$index"
            string("$prefix.id", rule.id)
            bool("$prefix.enabled", rule.enabled)
            string("$prefix.packageName", rule.packageName)
            string("$prefix.appName", rule.appName)
            string("$prefix.outboundMode", rule.outboundMode)
            string("$prefix.outboundValue", rule.outboundValue)
        }
        number("appGroups.count", plan.appGroups.size.toLong())
        plan.appGroups.sortedBy(RootAppGroupSnapshot::id).forEachIndexed { index, group ->
            val prefix = "appGroups.$index"
            string("$prefix.id", group.id)
            bool("$prefix.enabled", group.enabled)
            string("$prefix.name", group.name)
            string("$prefix.outboundMode", group.outboundMode)
            string("$prefix.outboundValue", group.outboundValue)
            number("$prefix.apps.count", group.apps.size.toLong())
            group.apps.sortedBy(RootAppInfoSnapshot::packageName).forEachIndexed { appIndex, app ->
                string("$prefix.apps.$appIndex.packageName", app.packageName)
                string("$prefix.apps.$appIndex.appName", app.appName)
            }
        }
    }.bytes()

    fun resolvedPlanBytes(
        plan: RootAppRoutingPlan,
        routes: List<RootResolvedUidRoute>
    ): ByteArray {
        val sorted = routes.sortedWith { left, right ->
            left.userId.compareTo(right.userId)
                .takeIf { it != 0 }
                ?: left.uid.compareTo(right.uid).takeIf { it != 0 }
                ?: compareUtf8(left.packageName, right.packageName).takeIf { it != 0 }
                ?: compareUtf8(left.laneId, right.laneId)
        }
        require(sorted.distinct().size == sorted.size) { "Duplicate Root resolved UID route" }
        val allowedLaneIds = plan.lanes.mapTo(mutableSetOf(), RootAppRouteLane::laneId) +
            setOf("generic", "excluded")
        require(sorted.all { it.uid >= 0 && it.userId >= 0 && it.laneId in allowedLaneIds }) {
            "Invalid Root resolved UID route"
        }
        return CanonicalWriter().apply {
            number("schema", plan.schema.toLong())
            string("staticPlanSha256", plan.staticPlanSha256)
            number("generation", plan.generation)
            number("netfilterSemanticVersion", plan.netfilterSemanticVersion.toLong())
            number("tupleCount", sorted.size.toLong())
            sorted.forEachIndexed { index, route ->
                val prefix = "tuple.$index"
                number("$prefix.userId", route.userId.toLong())
                number("$prefix.uid", route.uid.toLong())
                string("$prefix.packageName", route.packageName)
                string("$prefix.laneId", route.laneId)
            }
        }.bytes()
    }

    private class CanonicalWriter {
        private val value = StringBuilder()

        fun string(name: String, raw: String?) {
            if (raw == null) {
                value.append(name).append("=-1:\n")
                return
            }
            value.append(name).append('=').append(raw.toByteArray(Charsets.UTF_8).size)
                .append(':').append(raw).append('\n')
        }

        fun strings(name: String, values: Collection<String>) {
            val sorted = values.distinct().sortedWith(::compareUtf8)
            number("$name.count", sorted.size.toLong())
            sorted.forEachIndexed { index, item -> string("$name.$index", item) }
        }

        fun number(name: String, raw: Long) {
            value.append(name).append('=').append(raw).append('\n')
        }

        fun bool(name: String, raw: Boolean) {
            value.append(name).append('=').append(raw).append('\n')
        }

        fun bytes(): ByteArray = value.toString().toByteArray(Charsets.UTF_8)
    }

    private fun compareUtf8(left: String, right: String): Int {
        val first = left.toByteArray(Charsets.UTF_8)
        val second = right.toByteArray(Charsets.UTF_8)
        val shared = minOf(first.size, second.size)
        for (index in 0 until shared) {
            val result = (first[index].toInt() and 0xff).compareTo(second[index].toInt() and 0xff)
            if (result != 0) return result
        }
        return first.size.compareTo(second.size)
    }
}

internal fun compareRootUtf8(left: String, right: String): Int {
    val first = left.toByteArray(Charsets.UTF_8)
    val second = right.toByteArray(Charsets.UTF_8)
    val shared = minOf(first.size, second.size)
    for (index in 0 until shared) {
        val result = (first[index].toInt() and 0xff).compareTo(second[index].toInt() and 0xff)
        if (result != 0) return result
    }
    return first.size.compareTo(second.size)
}

internal fun isRootSha256(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

private fun Collection<String>.normalizePackageNames(): List<String> = asSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .onEach { packageName -> requireValidRootPackageName(packageName) }
    .distinct()
    .sortedWith(::compareRootUtf8)
    .toList()

internal fun requireValidRootPackageName(packageName: String) {
    require(packageName.length in 3..255 && '.' in packageName) {
        "Invalid Android package name: $packageName"
    }
    require(packageName.split('.').all { segment ->
        segment.isNotEmpty() &&
            (segment.first().isLetter() || segment.first() == '_') &&
            segment.all { it.isLetterOrDigit() || it == '_' }
    }) { "Invalid Android package name: $packageName" }
}
