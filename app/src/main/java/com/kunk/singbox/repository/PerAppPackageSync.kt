package com.kunk.singbox.repository

import android.content.Intent
import com.kunk.singbox.model.AppGroup
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.model.VpnAppMode

internal fun resolvePerAppPackageSyncAction(
    action: String?,
    isReplacing: Boolean,
    packageName: String?,
    isInstalled: Boolean
): PerAppPackageSyncAction {
    if (packageName.isNullOrBlank() || isReplacing) return PerAppPackageSyncAction.NONE
    if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REMOVED) {
        return PerAppPackageSyncAction.NONE
    }
    return when {
        !isInstalled -> PerAppPackageSyncAction.REMOVE
        action == Intent.ACTION_PACKAGE_ADDED -> PerAppPackageSyncAction.ADD
        else -> PerAppPackageSyncAction.NONE
    }
}

internal fun addPackageToList(value: String, packageName: String): String {
    if (packageName.isBlank()) return value.toPackageNames().joinToString("\n")
    return (value.toPackageNames() + packageName)
        .distinct()
        .joinToString("\n")
}

internal fun addPackageToCurrentPerAppRule(settings: AppSettings, packageName: String): AppSettings {
    if (!settings.autoIncludeNewAppsInPerAppRules || packageName.isBlank()) return settings
    return when (settings.vpnAppMode) {
        VpnAppMode.ALLOWLIST -> settings.copy(
            vpnAllowlist = addPackageToList(settings.vpnAllowlist, packageName)
        )
        VpnAppMode.BLOCKLIST -> settings.copy(
            vpnBlocklist = addPackageToList(settings.vpnBlocklist, packageName)
        )
        VpnAppMode.ALL -> settings
    }
}

internal fun removePackageFromList(value: String, packageName: String): String {
    return value.toPackageNames()
        .filterNot { it == packageName }
        .joinToString("\n")
}

internal fun removePackageFromPerAppSettings(settings: AppSettings, packageName: String): AppSettings {
    if (packageName.isBlank()) return settings
    return settings.copy(
        vpnAllowlist = removePackageFromList(settings.vpnAllowlist, packageName),
        vpnBlocklist = removePackageFromList(settings.vpnBlocklist, packageName),
        appRules = settings.appRules.filterNot { it.packageName == packageName },
        appGroups = settings.appGroups.map { group ->
            group.copy(apps = group.apps.filterNot { it.packageName == packageName })
        }
    )
}

@Suppress("LongParameterList", "CognitiveComplexMethod")
internal fun migrateNodeRoutingReferences(
    settings: AppSettings,
    oldNodeId: String,
    newNodeId: String,
    oldQualifiedReference: String,
    newQualifiedReference: String,
    oldBareReference: String,
    newBareReference: String,
    oldBareNameIsUnique: Boolean,
    newBareNameIsUnique: Boolean
): AppSettings {
    val oldId = oldNodeId.trim()
    val newId = newNodeId.trim()
    val oldQualified = oldQualifiedReference.trim()
    val newQualified = newQualifiedReference.trim()
    val oldBare = oldBareReference.trim()
    val newBare = newBareReference.trim()
    if (oldId.isEmpty() || newId.isEmpty() || oldId == newId) {
        return settings.copy(
            customNodeOrder = settings.customNodeOrder.map { value ->
                if (value.trim() == oldId && oldId.isNotEmpty()) newId else value
            }.distinct()
        )
    }

    val bareReplacement = when {
        oldBare.isEmpty() -> null
        !oldBareNameIsUnique -> null
        newBareNameIsUnique && newBare.isNotEmpty() -> newBare
        newQualified.isNotEmpty() -> newQualified
        else -> null
    }

    fun migrate(mode: RuleSetOutboundMode?, value: String?): String? {
        if (mode != RuleSetOutboundMode.NODE || value.isNullOrBlank()) return value
        return when (value.trim()) {
            oldId -> newId
            oldQualified -> newQualified
            oldBare -> bareReplacement ?: value
            else -> value
        }
    }

    return settings.copy(
        customNodeOrder = settings.customNodeOrder.map { value ->
            if (value.trim() == oldId) newId else value
        }.distinct(),
        appRules = settings.appRules.map { rule ->
            rule.copy(outboundValue = migrate(rule.outboundMode, rule.outboundValue))
        },
        appGroups = settings.appGroups.map { group ->
            group.copy(outboundValue = migrate(group.outboundMode, group.outboundValue))
        },
        customRules = settings.customRules.map { rule ->
            rule.copy(outboundValue = migrate(rule.outboundMode, rule.outboundValue))
        },
        ruleSets = settings.ruleSets.map { ruleSet ->
            ruleSet.copy(outboundValue = migrate(ruleSet.outboundMode, ruleSet.outboundValue))
        }
    )
}

@Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod")
internal fun removeDeletedRoutingReferences(
    settings: AppSettings,
    deletedProfileId: String? = null,
    deletedNodeIds: Set<String> = emptySet(),
    deletedNodeReferences: Set<String> = emptySet()
): AppSettings {
    val profileId = deletedProfileId?.trim()?.takeIf { it.isNotEmpty() }
    val nodeIds = deletedNodeIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val nodeReferences = deletedNodeReferences.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    if (profileId == null && nodeIds.isEmpty() && nodeReferences.isEmpty()) return settings

    fun isDeletedReference(mode: RuleSetOutboundMode?, value: String?): Boolean {
        val target = value?.trim().orEmpty()
        if (target.isEmpty()) return false
        val legacyProfileReference = profileId != null && target.startsWith("P:") &&
            target.substringAfterLast('#', "").trim() == profileId
        return legacyProfileReference || when (mode) {
            RuleSetOutboundMode.NODE -> target in nodeIds || target in nodeReferences ||
                (profileId != null && target.startsWith("$profileId::"))
            RuleSetOutboundMode.PROFILE -> profileId != null && target == profileId
            else -> false
        }
    }

    return settings.copy(
        customNodeOrder = settings.customNodeOrder.filterNot { it.trim() in nodeIds },
        appRules = settings.appRules.filterNot { rule ->
            isDeletedReference(rule.outboundMode, rule.outboundValue)
        },
        appGroups = settings.appGroups.filterNot { group ->
            isDeletedReference(group.outboundMode, group.outboundValue)
        },
        customRules = settings.customRules.filterNot { rule ->
            isDeletedReference(rule.outboundMode, rule.outboundValue)
        },
        ruleSets = settings.ruleSets.filterNot { ruleSet ->
            isDeletedReference(ruleSet.outboundMode, ruleSet.outboundValue)
        }
    )
}

@Suppress("CyclomaticComplexMethod")
internal fun removeInvalidRoutingReferences(
    settings: AppSettings,
    validProfileIds: Set<String>,
    validNodeIds: Set<String>,
    validQualifiedNodeReferences: Set<String>,
    validBareNodeNames: Set<String>
): AppSettings {
    fun legacyProfileId(value: String): String? {
        if (!value.startsWith("P:")) return null
        return value.substringAfterLast('#', "").trim().takeIf(String::isNotEmpty)
    }

    fun normalize(
        mode: RuleSetOutboundMode?,
        value: String?
    ): Pair<RuleSetOutboundMode?, String?>? {
        val target = value?.trim().orEmpty()
        return when (mode) {
            RuleSetOutboundMode.NODE -> if (
                target in validNodeIds || target in validQualifiedNodeReferences || target in validBareNodeNames
            ) {
                mode to value
            } else {
                null
            }
            RuleSetOutboundMode.PROFILE -> {
                val profileId = target.takeIf(validProfileIds::contains)
                    ?: legacyProfileId(target)?.takeIf(validProfileIds::contains)
                profileId?.let { RuleSetOutboundMode.PROFILE to it }
            }
            RuleSetOutboundMode.PROXY -> {
                val legacyProfileId = legacyProfileId(target)
                when {
                    legacyProfileId == null -> mode to value
                    legacyProfileId in validProfileIds -> RuleSetOutboundMode.PROFILE to legacyProfileId
                    else -> null
                }
            }
            else -> mode to value
        }
    }

    return settings.copy(
        customNodeOrder = settings.customNodeOrder.filter { it.trim() in validNodeIds },
        appRules = settings.appRules.mapNotNull { rule ->
            normalize(rule.outboundMode, rule.outboundValue)?.let { (mode, value) ->
                rule.copy(outboundMode = mode, outboundValue = value)
            }
        },
        appGroups = settings.appGroups.mapNotNull { group ->
            normalize(group.outboundMode, group.outboundValue)?.let { (mode, value) ->
                group.copy(outboundMode = mode, outboundValue = value)
            }
        },
        customRules = settings.customRules.mapNotNull { rule ->
            normalize(rule.outboundMode, rule.outboundValue)?.let { (mode, value) ->
                rule.copy(outboundMode = mode, outboundValue = value)
            }
        },
        ruleSets = settings.ruleSets.mapNotNull { ruleSet ->
            normalize(ruleSet.outboundMode, ruleSet.outboundValue)?.let { (mode, value) ->
                ruleSet.copy(outboundMode = mode, outboundValue = value)
            }
        }
    )
}

internal fun removeUninstalledPackagesFromPerAppSettings(
    settings: AppSettings,
    installedPackages: Set<String>
): AppSettings {
    if (installedPackages.isEmpty()) return settings
    return settings.copy(
        vpnAllowlist = sanitizePackageList(settings.vpnAllowlist, installedPackages),
        vpnBlocklist = sanitizePackageList(settings.vpnBlocklist, installedPackages),
        appRules = settings.appRules.filter { it.packageName in installedPackages },
        appGroups = settings.appGroups.map { group ->
            group.copy(apps = group.apps.filter { it.packageName in installedPackages })
        }
    )
}

internal fun upsertExclusiveAppGroup(settings: AppSettings, group: AppGroup): AppSettings {
    val normalizedApps = group.apps
        .map { app -> app.copy(packageName = app.packageName.trim()) }
        .filter { it.packageName.isNotEmpty() }
        .distinctBy { it.packageName }
    if (normalizedApps.isEmpty()) return settings
    val normalizedGroup = group.copy(apps = normalizedApps)
    val packageNames = normalizedApps.mapTo(mutableSetOf()) { it.packageName }
    val groupsWithTransfer = settings.appGroups.map { existing ->
        if (existing.id == normalizedGroup.id) {
            normalizedGroup
        } else {
            existing.copy(apps = existing.apps.filterNot { it.packageName in packageNames })
        }
    }
    return settings.copy(
        appRules = settings.appRules.filterNot { it.packageName in packageNames },
        appGroups = if (groupsWithTransfer.any { it.id == normalizedGroup.id }) {
            groupsWithTransfer
        } else {
            groupsWithTransfer + normalizedGroup
        }
    )
}

internal fun normalizeExclusiveAppAssignments(settings: AppSettings): AppSettings {
    val groupedPackages = mutableSetOf<String>()
    val normalizedGroups = settings.appGroups.asReversed().map { group ->
        val apps = group.apps.asReversed()
            .map { app -> app.copy(packageName = app.packageName.trim()) }
            .filter { app -> app.packageName.isNotEmpty() && groupedPackages.add(app.packageName) }
            .asReversed()
        group.copy(apps = apps)
    }.asReversed()
    val normalizedRules = settings.appRules.asReversed()
        .map { rule -> rule.copy(packageName = rule.packageName.trim()) }
        .filter { rule -> rule.packageName.isNotEmpty() && rule.packageName !in groupedPackages }
        .distinctBy { rule -> rule.packageName }
        .asReversed()
    return settings.copy(appRules = normalizedRules, appGroups = normalizedGroups)
}

internal fun sanitizePackageList(
    value: String,
    installedPackages: Set<String>
): String {
    if (installedPackages.isEmpty()) return value.toPackageNames().joinToString("\n")
    return value.toPackageNames()
        .filter { it in installedPackages }
        .joinToString("\n")
}

internal fun shouldReloadInstalledAppsForPackageChange(
    action: String?,
    isReplacing: Boolean,
    packageName: String?
): Boolean {
    if (packageName.isNullOrBlank()) return false
    return when (action) {
        Intent.ACTION_PACKAGE_ADDED -> true
        Intent.ACTION_PACKAGE_REMOVED -> !isReplacing
        else -> false
    }
}

private fun String.toPackageNames(): List<String> {
    return split("\n", "\r", ",", ";", " ", "\t")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}
