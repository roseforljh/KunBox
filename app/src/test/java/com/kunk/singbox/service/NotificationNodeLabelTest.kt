package com.kunk.singbox.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationNodeLabelTest {

    @Test
    fun resolveNodeLabel_prefersSelectedNodeOverStaleRuntimeLabel() {
        val label = resolveNotificationNodeLabel(
            selectedNodeName = "节点1"
        )

        assertEquals("节点1", label)
    }

    @Test
    fun resolveNodeLabel_ignoresRuntimeLabelWhenSelectedNodeMissing() {
        val label = resolveNotificationNodeLabel(
            selectedNodeName = null
        )

        assertNull(label)
    }

    @Test
    fun resolveNodeLabel_ignoresStoredLabelWhenSelectedNodeMissing() {
        val label = resolveNotificationNodeLabel(
            selectedNodeName = null
        )

        assertNull(label)
    }

    @Test
    fun resolveNodeLabel_prefersSelectedNodeOverStalePendingNode() {
        val label = resolveNotificationNodeLabel(
            selectedNodeName = "节点3"
        )

        assertEquals("节点3", label)
    }

    @Test
    fun resolveNodeLabel_prefersRuntimeAfterHotSwitchOverStalePendingNode() {
        val label = resolveNotificationNodeLabel(
            selectedNodeName = "配置2节点2"
        )

        assertEquals("配置2节点2", label)
    }

    @Test
    fun resolveNodeLabel_ignoresRuntimeWhenSelectedNullAndPendingStale() {
        val label = resolveNotificationNodeLabel(
            selectedNodeName = null
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
