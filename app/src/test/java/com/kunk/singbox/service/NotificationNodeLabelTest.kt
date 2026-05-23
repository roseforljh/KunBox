package com.kunk.singbox.service

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationNodeLabelTest {

    @Test
    fun resolveNodeLabel_prefersSelectedNodeOverStaleRuntimeLabel() {
        val label = resolveNotificationNodeLabel(
            runtimeNodeName = "节点2",
            selectedNodeName = "节点1",
            storedActiveLabel = "节点2"
        )

        assertEquals("节点1", label)
    }

    @Test
    fun resolveNodeLabel_usesRuntimeLabelWhenSelectedNodeMissing() {
        val label = resolveNotificationNodeLabel(
            runtimeNodeName = "节点2",
            selectedNodeName = null,
            storedActiveLabel = "节点1"
        )

        assertEquals("节点2", label)
    }

    @Test
    fun resolveNodeLabel_usesStoredLabelWhenRuntimeAndSelectedMissing() {
        val label = resolveNotificationNodeLabel(
            runtimeNodeName = null,
            selectedNodeName = null,
            storedActiveLabel = "节点2"
        )

        assertEquals("节点2", label)
    }

    @Test
    fun resolveNodeLabel_prefersPendingNodeAfterProfileSwitch() {
        val label = resolveNotificationNodeLabel(
            runtimeNodeName = "配置1节点",
            selectedNodeName = null,
            storedActiveLabel = "配置1节点",
            pendingNodeName = "配置2节点"
        )

        assertEquals("配置2节点", label)
    }

    @Test
    fun resolveNodeLabel_prefersRuntimeAfterHotSwitchOverStalePendingNode() {
        val label = resolveNotificationNodeLabel(
            runtimeNodeName = "配置2节点2",
            selectedNodeName = "配置2节点2",
            storedActiveLabel = "配置2节点2",
            pendingNodeName = "配置2节点1"
        )

        assertEquals("配置2节点2", label)
    }
}
