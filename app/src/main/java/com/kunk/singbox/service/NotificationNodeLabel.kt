package com.kunk.singbox.service

internal fun resolveNotificationNodeLabel(
    selectedNodeName: String?,
    selectedNodeStoreLabel: String? = null
): String? {
    return selectedNodeStoreLabel?.takeIf { it.isNotBlank() }
        ?: selectedNodeName?.takeIf { it.isNotBlank() }
}
