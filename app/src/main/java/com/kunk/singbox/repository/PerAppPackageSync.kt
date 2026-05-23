package com.kunk.singbox.repository

internal fun removePackageFromList(value: String, packageName: String): String {
    return value.toPackageNames()
        .filterNot { it == packageName }
        .joinToString("\n")
}

internal fun sanitizePackageList(
    value: String,
    isPackageInstalled: (String) -> Boolean
): String {
    return value.toPackageNames()
        .filter { isPackageInstalled(it) }
        .joinToString("\n")
}

internal fun shouldSyncRemovedPackage(isReplacing: Boolean, packageName: String?): Boolean {
    return !isReplacing && !packageName.isNullOrBlank()
}

private fun String.toPackageNames(): List<String> {
    return split("\n", "\r", ",", ";", " ", "\t")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}
