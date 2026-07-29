package com.kunk.singbox.service

import com.google.gson.Gson
import com.kunk.singbox.model.SingBoxConfig
import java.io.File

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

internal fun resolveStartupProxyTag(configPath: String, gson: Gson, explicitTag: String? = null): String? {
    val config = gson.fromJson(File(configPath).readText(), SingBoxConfig::class.java) ?: return null
    return resolveStartupProxyTag(config, explicitTag)
}

internal fun resolveStartupProxyTag(config: SingBoxConfig, explicitTag: String? = null): String? {
    val groupTags = config.outbounds.orEmpty()
        .filter { it.type == "selector" || it.type == "urltest" }
        .mapTo(mutableSetOf()) { it.tag }
    explicitTag?.takeIf { it.isNotBlank() && it !in groupTags }?.let { return it }
    val proxy = config.outbounds?.firstOrNull {
        it.type == "selector" && it.tag.equals("PROXY", ignoreCase = true)
    } ?: return null
    return proxy.default?.takeIf { it.isNotBlank() && it !in groupTags }
        ?: proxy.outbounds.orEmpty().firstOrNull { it.isNotBlank() && it !in groupTags }
}
