package com.kunk.singbox.service.root

import com.kunk.singbox.model.VpnAppMode
import com.kunk.singbox.model.RootAppRoutingCanonical
import com.kunk.singbox.model.RootAppRoutingPlan
import com.kunk.singbox.model.RootResolvedUidRoute
import com.kunk.singbox.model.compareRootUtf8
import com.kunk.singbox.model.requireValidRootPackageName

data class RootInstalledPackage(
    val userId: Int,
    val packageName: String,
    val uid: Int
) {
    init {
        require(userId >= 0) { "Invalid Android user ID: $userId" }
        require(uid > 0) { "Invalid Android package UID: $uid" }
        requireValidRootPackageName(packageName)
    }
}

data class RootUidSelection(
    val capturedUids: List<Int>,
    val capturedRanges: List<RootUidRange>,
    val excludedUids: List<Int>,
    val applicationUidRanges: List<RootUidRange> = emptyList()
)

internal data class RootUidSnapshot(
    val users: List<Int>,
    val packages: List<RootInstalledPackage>
)

data class RootResolvedRouting(
    val selection: RootUidSelection,
    val laneUids: Map<String, List<Int>>,
    val routes: List<RootResolvedUidRoute>,
    val resolvedPlanSha256: String
)

class RootUidResolver(
    private val executor: RootCommandExecutor = ProcessRootCommandExecutor()
) {
    fun applicationUidRanges(): List<RootUidRange> = listUsers().map(::applicationUidRange)

    fun resolveCapturedUids(
        mode: VpnAppMode,
        allowlist: Set<String>,
        blocklist: Set<String>,
        selfPackage: String,
        selfUid: Int
    ): RootUidSelection {
        val users = listUsers()
        val packages = users.flatMap(::listPackages)
        return resolveCapturedUids(mode, allowlist, blocklist, selfPackage, selfUid, users, packages)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun resolveRouting(
        plan: RootAppRoutingPlan,
        selfPackage: String,
        selfUid: Int
    ): RootResolvedRouting = resolveRouting(plan, selfPackage, selfUid, captureSnapshot())

    internal fun captureSnapshot(): RootUidSnapshot {
        val users = listUsers()
        val packages = users.flatMap(::listPackages)
        check(packages.isNotEmpty()) { "Root package UID enumeration returned no applications" }
        return RootUidSnapshot(users, packages)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    internal fun resolveRouting(
        plan: RootAppRoutingPlan,
        selfPackage: String,
        selfUid: Int,
        snapshot: RootUidSnapshot
    ): RootResolvedRouting {
        require(selfPackage.isNotBlank() && selfUid > 0)
        require(plan.staticPlanSha256 == RootAppRoutingCanonical.staticPlanSha256(plan)) {
            "Root static routing plan digest mismatch"
        }
        require(plan.appRoutingSha256 == RootAppRoutingCanonical.appRoutingSha256(plan)) {
            "Root app routing digest mismatch"
        }
        val mode = VpnAppMode.valueOf(plan.vpnAppMode)
        val users = snapshot.users
        check(users.isNotEmpty()) { "Root Android user enumeration returned no users" }
        val packages = snapshot.packages
        check(packages.isNotEmpty()) { "Root package UID enumeration returned no applications" }
        val duplicates = packages.groupBy { it.userId to it.packageName }.filterValues { it.size > 1 }
        check(duplicates.isEmpty()) { "Root package UID enumeration returned duplicate packages" }
        val selection = resolveCapturedUids(
            mode = mode,
            allowlist = plan.allowlist.toSet(),
            blocklist = plan.blocklist.toSet(),
            selfPackage = selfPackage,
            selfUid = selfUid,
            users = users,
            packages = packages
        )
        val requestAllowlist = plan.allowlist.toSet()
        val requestBlocklist = plan.blocklist.toSet()
        check(plan.allowlist.size == requestAllowlist.size && plan.allowlist.none(String::isBlank)) {
            "Root allowlist contains duplicate or empty packages"
        }
        check(plan.blocklist.size == requestBlocklist.size && plan.blocklist.none(String::isBlank)) {
            "Root blocklist contains duplicate or empty packages"
        }
        val installedNames = packages.mapTo(mutableSetOf(), RootInstalledPackage::packageName)
        val laneByPackage = buildMap {
            plan.lanes.forEach { lane ->
                lane.packageNames.filter { it in installedNames }.forEach { packageName ->
                    val previous = put(packageName, lane.laneId)
                    require(previous == null || previous == lane.laneId) {
                        "Root package $packageName maps to multiple lanes"
                    }
                }
            }
        }
        val packagesByUid = packages.groupBy(RootInstalledPackage::uid)
        val laneUids = linkedMapOf<String, MutableSet<Int>>()
        val routes = mutableListOf<RootResolvedUidRoute>()
        packagesByUid.toSortedMap().forEach { (uid, refs) ->
            val laneIds = refs.mapNotNull { laneByPackage[it.packageName] }.distinct()
            check(laneIds.size <= 1) {
                val names = refs.map(RootInstalledPackage::packageName).sorted().joinToString()
                "Root shared UID $uid 的应用 $names 指向多个 lane：${laneIds.joinToString()}"
            }
            val laneId = laneIds.singleOrNull()
            val excluded = uid in selection.excludedUids
            val captured = !excluded && (
                uid in selection.capturedUids || selection.capturedRanges.any { uid in it.first..it.last }
                )
            check(laneIds.isEmpty() || (captured && !excluded)) {
                val names = refs.map(RootInstalledPackage::packageName)
                    .sortedWith(::compareRootUtf8)
                    .joinToString()
                "Root 明确应用车道 $names 的 UID $uid 未被当前应用接管策略捕获"
            }
            val resolvedLaneId = when {
                excluded || !captured -> "excluded"
                laneId != null -> laneId
                else -> "generic"
            }
            if (captured && laneId != null) {
                laneUids.getOrPut(laneId) { linkedSetOf() }.add(uid)
            }
            refs.sortedWith(compareBy(RootInstalledPackage::userId, RootInstalledPackage::packageName)).forEach { ref ->
                routes += RootResolvedUidRoute(ref.userId, ref.uid, ref.packageName, resolvedLaneId)
            }
        }
        val resolvedDigest = RootAppRoutingCanonical.resolvedPlanSha256(plan, routes)
        return RootResolvedRouting(
            selection = selection,
            laneUids = laneUids.mapValues { (_, values) -> values.sorted() },
            routes = routes,
            resolvedPlanSha256 = resolvedDigest
        )
    }

    @Suppress("LongParameterList")
    private fun resolveCapturedUids(
        mode: VpnAppMode,
        allowlist: Set<String>,
        blocklist: Set<String>,
        selfPackage: String,
        selfUid: Int,
        users: List<Int>,
        packages: List<RootInstalledPackage>
    ): RootUidSelection {
        check(packages.isNotEmpty()) { "Root package UID enumeration returned no applications" }
        val packagesByUid = packages.groupBy(RootInstalledPackage::uid)
        val selfUids = packages.filter { it.packageName == selfPackage }.mapTo(mutableSetOf()) { it.uid } + selfUid
        val blockedUids = packagesByUid.filterValues { refs -> refs.any { it.packageName in blocklist } }.keys
        val applicationRanges = users.map(::applicationUidRange)
        return when (mode) {
            VpnAppMode.ALL -> RootUidSelection(
                capturedUids = packagesByUid.keys.filter { it in 1..9_999 },
                capturedRanges = applicationRanges,
                excludedUids = selfUids.sorted(),
                applicationUidRanges = applicationRanges
            )
            VpnAppMode.ALLOWLIST -> packagesByUid.filterValues { refs ->
                refs.any { it.packageName in allowlist }
            }.keys.let { selectedUids ->
                RootUidSelection(
                    capturedUids = selectedUids.filter { it > 0 && it !in selfUids }.sorted(),
                    capturedRanges = emptyList(),
                    excludedUids = selfUids.sorted(),
                    applicationUidRanges = applicationRanges
                )
            }
            VpnAppMode.BLOCKLIST -> RootUidSelection(
                capturedUids = packagesByUid.keys.filter { it in 1..9_999 && it !in blockedUids },
                capturedRanges = applicationRanges,
                excludedUids = (blockedUids + selfUids).sorted(),
                applicationUidRanges = applicationRanges
            )
        }
    }

    internal fun listUsers(): List<Int> {
        val result = executor.execute(listOf("cmd", "user", "list"))
        check(result.success) { "Failed to enumerate Android users: ${result.output}" }
        return result.output.lineSequence()
            .mapNotNull { line ->
                val body = line.substringAfter("UserInfo{", missingDelimiterValue = "")
                body.substringBefore(':').toIntOrNull()
            }
            .distinct()
            .sorted()
            .toList()
            .also { users -> check(users.isNotEmpty()) { "Root Android user enumeration returned no users" } }
    }

    private fun applicationUidRange(userId: Int): RootUidRange {
        require(userId >= 0) { "Invalid Android user ID: $userId" }
        val offset = Math.multiplyExact(userId, 100_000)
        return RootUidRange(
            Math.addExact(offset, 10_000),
            Math.addExact(offset, 99_999)
        )
    }

    internal fun listPackages(userId: Int): List<RootInstalledPackage> {
        val result = executor.execute(
            listOf("cmd", "package", "list", "packages", "-U", "--user", userId.toString())
        )
        check(result.success) { "Failed to enumerate packages for Android user $userId: ${result.output}" }
        val packageLines = result.output.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("package:") }
            .toList()
        check(packageLines.isNotEmpty()) {
            "Root package UID enumeration returned no packages for Android user $userId"
        }
        return packageLines.map { line ->
            val packageName = line.removePrefix("package:").takeWhile { !it.isWhitespace() }
            val fields = line.split(' ', '\t').filter(String::isNotBlank)
            val uid = fields.firstOrNull { it.startsWith("uid:") }
                ?.substringAfter("uid:")
                ?.toIntOrNull()
            check(packageName.isNotBlank() && uid != null) {
                "Malformed Root package UID record for Android user $userId: $line"
            }
            RootInstalledPackage(userId, packageName, uid)
        }.also { packages ->
            check(packages.map(RootInstalledPackage::packageName).distinct().size == packages.size) {
                "Root package UID enumeration returned duplicate packages for Android user $userId"
            }
        }
    }
}
