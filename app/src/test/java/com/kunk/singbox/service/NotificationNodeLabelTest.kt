package com.kunk.singbox.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationNodeLabelTest {

    @Test
    fun resolveNodeLabel_prefersRuntimeOverSelectedAndStored() {
        val label = resolveNotificationNodeLabel(
            selectedNodeName = "手选节点",
            selectedNodeStoreLabel = "存储节点",
            runtimeNodeName = "运行态节点"
        )

        assertEquals("运行态节点", label)
    }

    @Test
    fun resolveNodeLabel_fallsBackToStoredWhenRuntimeBlank() {
        val label = resolveNotificationNodeLabel(
            selectedNodeName = "手选节点",
            selectedNodeStoreLabel = "存储节点",
            runtimeNodeName = "  "
        )

        assertEquals("存储节点", label)
    }

    @Test
    fun resolveNodeLabel_fallsBackToSelectedWhenRuntimeAndStoredMissing() {
        val label = resolveNotificationNodeLabel(
            selectedNodeName = "手选节点"
        )

        assertEquals("手选节点", label)
    }

    @Test
    fun resolveNodeLabel_returnsNullWhenAllBlank() {
        val label = resolveNotificationNodeLabel(
            selectedNodeName = null,
            selectedNodeStoreLabel = "",
            runtimeNodeName = null
        )

        assertNull(label)
    }

    @Test
    fun resolveNodeLabel_usesCrossProcessSelectedNodeWhenRepositoryNodeIsStale() {
        val label = resolveNotificationNodeLabel(
            selectedNodeName = "上个配置节点",
            selectedNodeStoreLabel = "新配置节点"
        )

        assertEquals("新配置节点", label)
    }
}
