package com.kunk.singbox.model

import java.security.MessageDigest

data class PerAppVpnPolicy(
    val mode: VpnAppMode,
    val allowlist: Set<String>,
    val blocklist: Set<String>,
    val revision: Long
) {
    fun captures(packageName: String, selfPackage: String): Boolean {
        if (packageName.isBlank() || packageName == selfPackage) return false
        return when (mode) {
            VpnAppMode.ALL -> true
            VpnAppMode.ALLOWLIST -> packageName in allowlist
            VpnAppMode.BLOCKLIST -> packageName !in blocklist
        }
    }

    fun digest(): String {
        val canonical = buildString {
            append(mode.name).append('\n')
            when (mode) {
                VpnAppMode.ALL -> Unit
                VpnAppMode.ALLOWLIST -> allowlist.sorted().forEach {
                    append("A:").append(it).append('\n')
                }
                VpnAppMode.BLOCKLIST -> blocklist.sorted().forEach {
                    append("B:").append(it).append('\n')
                }
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        fun from(settings: AppSettings?): PerAppVpnPolicy = PerAppVpnPolicy(
            mode = settings?.vpnAppMode ?: VpnAppMode.ALL,
            allowlist = parsePackageNames(settings?.vpnAllowlist),
            blocklist = parsePackageNames(settings?.vpnBlocklist),
            revision = settings?.perAppPolicyRevision?.coerceAtLeast(0L) ?: 0L
        )

        fun parsePackageNames(raw: String?): Set<String> = raw.orEmpty()
            .split("\n", "\r", ",", ";", " ", "\t")
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toCollection(linkedSetOf())

        fun nextRevision(current: Long): Long = when {
            current < 0L -> 1L
            current == Long.MAX_VALUE -> Long.MAX_VALUE
            else -> current + 1L
        }
    }
}

data class PerAppVpnScope(
    val capturedPackages: Set<String>,
    val excludedPackages: Set<String>,
    val builderAllowedPackages: Set<String>,
    val builderDisallowedPackages: Set<String>
)

object PerAppVpnScopeResolver {
    fun resolve(
        policy: PerAppVpnPolicy,
        installedApps: Collection<InstalledAppUi>,
        selfPackage: String
    ): PerAppVpnScope {
        val installedByPackage = installedApps.associateBy(InstalledAppUi::packageName)
        val uidPackages = installedApps.groupByUid()
        val selfUid = installedByPackage[selfPackage]?.uid?.takeIf { it >= 0 }

        fun packagesSharingUid(packageNames: Set<String>): Set<String> {
            val selectedUids = packageNames.mapNotNullTo(mutableSetOf()) { packageName ->
                installedByPackage[packageName]?.uid?.takeIf { it >= 0 }
            }
            return buildSet {
                addAll(packageNames.filter(installedByPackage::containsKey))
                selectedUids.forEach { uid -> addAll(uidPackages[uid].orEmpty()) }
            }
        }

        val installedPackages = installedByPackage.keys - selfPackage
        return when (policy.mode) {
            VpnAppMode.ALL -> PerAppVpnScope(
                capturedPackages = installedPackages,
                excludedPackages = setOf(selfPackage),
                builderAllowedPackages = emptySet(),
                builderDisallowedPackages = setOf(selfPackage)
            )
            VpnAppMode.ALLOWLIST -> {
                val captured = packagesSharingUid(policy.allowlist) - selfPackage
                PerAppVpnScope(
                    capturedPackages = captured,
                    excludedPackages = installedPackages - captured,
                    builderAllowedPackages = policy.allowlist.filterTo(linkedSetOf(), installedByPackage::containsKey),
                    builderDisallowedPackages = emptySet()
                )
            }
            VpnAppMode.BLOCKLIST -> {
                val excluded = packagesSharingUid(policy.blocklist) + selfPackage +
                    if (selfUid == null) emptySet() else uidPackages[selfUid].orEmpty()
                PerAppVpnScope(
                    capturedPackages = installedPackages - excluded,
                    excludedPackages = excluded,
                    builderAllowedPackages = emptySet(),
                    builderDisallowedPackages = policy.blocklist
                        .filterTo(linkedSetOf(), installedByPackage::containsKey) + selfPackage
                )
            }
        }
    }

    private fun Collection<InstalledAppUi>.groupByUid(): Map<Int, Set<String>> =
        asSequence()
            .filter { it.uid >= 0 }
            .groupBy(InstalledAppUi::uid, InstalledAppUi::packageName)
            .mapValues { (_, packages) -> packages.toSet() }
}
