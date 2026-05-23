package com.kunk.singbox.service

internal fun resolveNotificationNodeLabel(
    runtimeNodeName: String?,
    selectedNodeName: String?,
    storedActiveLabel: String?,
    pendingNodeName: String? = null
): String? {
    return when {
        !runtimeNodeName.isNullOrBlank() && runtimeNodeName == selectedNodeName -> runtimeNodeName
        !pendingNodeName.isNullOrBlank() -> pendingNodeName
        !selectedNodeName.isNullOrBlank() -> selectedNodeName
        !runtimeNodeName.isNullOrBlank() -> runtimeNodeName
        else -> storedActiveLabel?.takeIf { it.isNotBlank() }
    }
}
