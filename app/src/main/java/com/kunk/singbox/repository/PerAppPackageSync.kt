package com.kunk.singbox.repository

import com.kunk.singbox.model.AppSettings

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

internal fun shouldReloadInstalledAppsForPackageChange(isReplacing: Boolean, packageName: String?): Boolean {
    return !isReplacing && !packageName.isNullOrBlank()
}

private fun String.toPackageNames(): List<String> {
    return split("\n", "\r", ",", ";", " ", "\t")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}
