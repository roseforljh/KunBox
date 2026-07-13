package com.kunk.singbox.viewmodel

import com.kunk.singbox.model.ConnectionState
import com.kunk.singbox.model.FilterMode
import com.kunk.singbox.model.NodeFilter
import com.kunk.singbox.model.NodeSortType
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.service.ServiceState

internal fun resolveTrustedDashboardConnectionState(
    serviceState: ServiceState,
    ipcBound: Boolean
): ConnectionState {
    if (!ipcBound) return ConnectionState.Idle

    return when (serviceState) {
        ServiceState.RUNNING -> ConnectionState.Connected
        ServiceState.STARTING -> ConnectionState.Connecting
        ServiceState.STOPPING -> ConnectionState.Disconnecting
        ServiceState.STOPPED -> ConnectionState.Idle
    }
}

/**
 * 主页节点名：已连接时优先运行态标签，连接中显示本次用户选择。
 */
internal fun resolveDashboardDisplayedNodeName(
    connectionState: ConnectionState,
    runtimeLabel: String?,
    selectedNodeDisplayName: String?
): String? {
    if (connectionState == ConnectionState.Connected) {
        runtimeLabel?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return selectedNodeDisplayName?.takeIf { it.isNotBlank() }
}

internal fun buildDashboardNodes(
    nodes: List<NodeUi>,
    filter: NodeFilter,
    sortType: NodeSortType,
    customOrder: List<String>
): List<NodeUi> {
    val filtered = when (filter.filterMode) {
        FilterMode.NONE -> nodes
        FilterMode.INCLUDE -> {
            val keywords = filter.effectiveIncludeKeywords
            if (keywords.isEmpty()) {
                nodes
            } else {
                nodes.filter { node ->
                    keywords.any { keyword -> node.displayName.contains(keyword, ignoreCase = true) }
                }
            }
        }
        FilterMode.EXCLUDE -> {
            val keywords = filter.effectiveExcludeKeywords
            if (keywords.isEmpty()) {
                nodes
            } else {
                nodes.filter { node ->
                    keywords.none { keyword -> node.displayName.contains(keyword, ignoreCase = true) }
                }
            }
        }
    }

    return when (sortType) {
        NodeSortType.DEFAULT -> filtered
        NodeSortType.LATENCY -> filtered.sortedWith(compareBy<NodeUi> {
            val latency = it.latencyMs
            if (latency == null || latency <= 0) Long.MAX_VALUE else latency
        })
        NodeSortType.NAME,
        NodeSortType.REGION -> filtered.sortedBy { it.name }
        NodeSortType.CUSTOM -> {
            val orderMap = customOrder.withIndex().associate { it.value to it.index }
            filtered.sortedBy { orderMap[it.id] ?: Int.MAX_VALUE }
        }
    }
}
