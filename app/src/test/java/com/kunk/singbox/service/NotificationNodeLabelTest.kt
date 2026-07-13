package com.kunk.singbox.service

import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
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
    fun resolveStartupProxyTagPrefersExplicitThenDefaultThenFirstOutbound() {
        val proxy = Outbound(type = "selector", tag = "PROXY", outbounds = listOf("node-a"))

        assertEquals("node-c", resolveStartupProxyTag(SingBoxConfig(), explicitTag = "node-c"))
        assertEquals(
            "node-b",
            resolveStartupProxyTag(SingBoxConfig(outbounds = listOf(proxy.copy(default = "node-b"))))
        )
        assertEquals("node-a", resolveStartupProxyTag(SingBoxConfig(outbounds = listOf(proxy))))
    }

    @Test
    fun resolveStartupProxyTagReturnsNullWithoutUsableProxySelector() {
        assertNull(resolveStartupProxyTag(SingBoxConfig()))
        assertNull(
            resolveStartupProxyTag(
                SingBoxConfig(outbounds = listOf(Outbound(type = "selector", tag = "PROXY")))
            )
        )
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
