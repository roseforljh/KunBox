package com.kunk.singbox.viewmodel

import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.ipc.DataPlaneReadinessSnapshot
import com.kunk.singbox.ipc.DataPlaneStatus
import com.kunk.singbox.model.ConnectionState
import com.kunk.singbox.model.FilterMode
import com.kunk.singbox.model.NodeFilter
import com.kunk.singbox.model.NodeSortType
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.service.ServiceState

internal fun resolveTrustedDashboardConnectionState(
    serviceState: ServiceState,
    ipcBound: Boolean,
    readiness: DataPlaneReadinessSnapshot = DataPlaneReadinessSnapshot.stopped(),
    mode: VpnStateStore.CoreMode = VpnStateStore.CoreMode.NONE,
    apiLevel: Int = 0,
    nowElapsedMs: Long = 0L
): ConnectionState {
    return when (serviceState) {
        ServiceState.STARTING -> ConnectionState.Connecting
        ServiceState.STOPPING -> ConnectionState.Disconnecting
        ServiceState.STOPPED -> ConnectionState.Idle
        ServiceState.RUNNING -> when {
            !ipcBound -> if (mode != VpnStateStore.CoreMode.NONE) {
                ConnectionState.Connecting
            } else {
                ConnectionState.Idle
            }
            readiness.status == DataPlaneStatus.FAILED_BLOCKED ||
                readiness.status == DataPlaneStatus.FAILED_UNPROTECTED -> ConnectionState.Error
            readiness.isReady(serviceState, mode, ipcBound, apiLevel, nowElapsedMs) ->
                ConnectionState.Connected
            else -> ConnectionState.Connecting
        }
    }
}

/**
 * 启动同步时是否允许清掉 persisted active：
 * 仅当无系统 VPN、persisted active、VPN 模式，且 IPC 已确认服务 STOPPED。
 * IPC 未可信（未绑定/冷起）时只显示同步中，不写运行态，避免加宽"假停"窗口。
 */
internal fun shouldClearPersistedActiveOnBoot(
    hasSystemVpn: Boolean,
    persistedActive: Boolean,
    mode: VpnStateStore.CoreMode,
    ipcBound: Boolean,
    serviceState: ServiceState
): Boolean {
    if (hasSystemVpn || !persistedActive) return false
    if (mode != VpnStateStore.CoreMode.VPN) return false
    return ipcBound && serviceState == ServiceState.STOPPED
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
