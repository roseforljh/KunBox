package com.kunk.singbox.service

internal fun resolveNotificationNodeLabel(
    runtimeNodeName: String?,
    selectedNodeName: String?,
    storedActiveLabel: String?,
    pendingNodeName: String? = null
): String? {
    return pendingNodeName?.takeIf { it.isNotBlank() }
        ?: selectedNodeName
        ?: runtimeNodeName
        ?: storedActiveLabel?.takeIf { it.isNotBlank() }
}
