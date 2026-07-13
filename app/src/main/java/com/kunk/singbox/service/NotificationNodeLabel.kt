package com.kunk.singbox.service

/**
 * 通知/运行态节点名解析。
 * 已连接时优先 runtime（真实出口），避免主页/通知继续显示用户旧选择。
 */
internal fun resolveNotificationNodeLabel(
    selectedNodeName: String?,
    selectedNodeStoreLabel: String? = null,
    runtimeNodeName: String? = null
): String? {
    return runtimeNodeName?.takeIf { it.isNotBlank() }
        ?: selectedNodeStoreLabel?.takeIf { it.isNotBlank() }
        ?: selectedNodeName?.takeIf { it.isNotBlank() }
}
