package com.kunk.singbox.service.manager

import com.kunk.singbox.model.NodeUi
import org.junit.Assert.assertEquals
import org.junit.Test

class NodeSwitchDisplayNameTest {

    @Test
    fun explicitHotSwitchDisplayName_usesTargetNodeNameInsteadOfOutboundTag() {
        val node = NodeUi(
            id = "node-3",
            name = "节点3",
            protocol = "hysteria2",
            group = "默认",
            sourceProfileId = "profile-2"
        )

        val displayName = resolveExplicitHotSwitchDisplayName(
            node = node
        )

        assertEquals("节点3", displayName)
    }

    @Test
    fun explicitHotSwitchDisplayName_usesTargetNameWhenBackgroundRepositoryCannotFindNode() {
        val displayName = resolveExplicitHotSwitchDisplayName(
            node = null,
            targetNodeName = "节点3"
        )

        assertEquals("节点3", displayName)
    }
}
