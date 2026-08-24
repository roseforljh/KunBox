package com.kunk.singbox.service.root

import com.kunk.singbox.model.VpnAppMode

data class RootInstalledPackage(
    val userId: Int,
    val packageName: String,
    val uid: Int
)

data class RootUidSelection(
    val capturedUids: List<Int>,
    val capturedRanges: List<RootUidRange>,
    val excludedUids: List<Int>
)

class RootUidResolver(
    private val executor: RootCommandExecutor = ProcessRootCommandExecutor()
) {
    fun resolveCapturedUids(
        mode: VpnAppMode,
        allowlist: Set<String>,
        blocklist: Set<String>,
        selfPackage: String,
        selfUid: Int
    ): RootUidSelection {
        val users = listUsers()
        val packages = users.flatMap(::listPackages)
        check(packages.isNotEmpty()) { "Root package UID enumeration returned no applications" }
        val packagesByUid = packages.groupBy(RootInstalledPackage::uid)
        val selfUids = packages.filter { it.packageName == selfPackage }.mapTo(mutableSetOf()) { it.uid } + selfUid
        val blockedUids = packagesByUid.filterValues { refs -> refs.any { it.packageName in blocklist } }.keys
        val applicationRanges = users.map { userId ->
            val offset = userId * 100_000
            RootUidRange(offset + 10_000, offset + 99_999)
        }
        return when (mode) {
            VpnAppMode.ALL -> RootUidSelection(
                capturedUids = packagesByUid.keys.filter { it in 1..9_999 },
                capturedRanges = applicationRanges,
                excludedUids = selfUids.sorted()
            )
            VpnAppMode.ALLOWLIST -> packagesByUid.filterValues { refs ->
                refs.any { it.packageName in allowlist }
            }.keys.let { selectedUids ->
                RootUidSelection(
                    capturedUids = selectedUids.filter { it > 0 && it !in selfUids }.sorted(),
                    capturedRanges = emptyList(),
                    excludedUids = selfUids.sorted()
                )
            }
            VpnAppMode.BLOCKLIST -> RootUidSelection(
                capturedUids = packagesByUid.keys.filter { it in 1..9_999 && it !in blockedUids },
                capturedRanges = applicationRanges,
                excludedUids = (blockedUids + selfUids).sorted()
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
            .toList()
    }

    internal fun listPackages(userId: Int): List<RootInstalledPackage> {
        val result = executor.execute(
            listOf("cmd", "package", "list", "packages", "-U", "--user", userId.toString())
        )
        check(result.success) { "Failed to enumerate packages for Android user $userId: ${result.output}" }
        return result.output.lineSequence().mapNotNull { line ->
            val packageName = line.substringAfter("package:", missingDelimiterValue = "")
                .substringBefore(' ')
                .trim()
            val uid = line.substringAfter("uid:", missingDelimiterValue = "").trim().toIntOrNull()
            if (packageName.isBlank() || uid == null) null else RootInstalledPackage(userId, packageName, uid)
        }.toList()
    }
}
