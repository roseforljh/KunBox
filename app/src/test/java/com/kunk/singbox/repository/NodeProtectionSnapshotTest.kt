package com.kunk.singbox.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeProtectionSnapshotTest {
    @Test
    fun runtimeSnapshotKeepsOldTagsAndBindsLatestConfigFingerprint() {
        val oldRef = RuntimeNodeRef("metered-id", "New-HTTP", meteredProtected = true)
        val oldConfig = "{\"outbounds\":[{\"tag\":\"old-metered\"}]}"
        val previous = buildRuntimeNodeMappingSnapshot(
            previous = null,
            mapping = mapOf("old-metered" to oldRef),
            configContent = oldConfig
        )
        val newConfig = "{\"outbounds\":[{\"tag\":\"safe\"}]}"

        val updated = buildRuntimeNodeMappingSnapshot(
            previous = previous,
            mapping = mapOf("safe" to RuntimeNodeRef("safe-id", "Safe")),
            configContent = newConfig
        )

        assertEquals(oldRef, updated.mappings["old-metered"])
        assertEquals("safe-id", updated.mappings["safe"]?.nodeId)
        assertEquals(runtimeConfigFingerprint(newConfig), updated.configSha256)
        assertNotEquals(runtimeConfigFingerprint(oldConfig), updated.configSha256)
    }

    @Test
    fun enablingProtectionDoesNotCreateAuthorization() {
        assertNull(
            authorizationAfterProtectionUpdate(
                previousAuthorizedNodeId = "node-id",
                oldNodeId = "node-id",
                updatedNodeId = "node-id",
                wasProtected = false,
                isProtected = true
            )
        )
    }

    @Test
    fun editingAlreadyAuthorizedProtectedNodePreservesAuthorizationAcrossRename() {
        assertEquals(
            "renamed-id",
            authorizationAfterProtectionUpdate(
                previousAuthorizedNodeId = "old-id",
                oldNodeId = "old-id",
                updatedNodeId = "renamed-id",
                wasProtected = true,
                isProtected = true
            )
        )
    }
}
