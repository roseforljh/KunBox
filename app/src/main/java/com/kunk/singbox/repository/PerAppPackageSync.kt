package com.kunk.singbox.repository

import android.content.Intent
import com.kunk.singbox.model.AppSettings
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
