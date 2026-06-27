package com.kunk.singbox.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConfigRepositoryNodeAddPolicyTest {

    @Test
    fun createdNodeActivatesOnlyWhenThereIsNoCurrentProfile() {
        assertTrue(ConfigRepository.shouldActivateCreatedNodeForTest(null))
        assertFalse(ConfigRepository.shouldActivateCreatedNodeForTest("profile-a"))
    }

    @Test
    fun manualNodeAddPathsUseCreatedNodeActivationPolicy() {
        val source = File("src/main/java/com/kunk/singbox/repository/ConfigRepository.kt").readText()
        val createNodeBody = source.substring(
            source.indexOf("fun createNode("),
            source.indexOf("protected fun removeOutboundFromConfig")
        )
        val addSingleNodeBody = source.substring(
            source.indexOf("suspend fun addSingleNode("),
            source.indexOf("suspend fun exportNode(")
        )

        assertTrue(createNodeBody.contains("shouldActivateCreatedNode"))
        assertTrue(addSingleNodeBody.contains("shouldActivateCreatedNode"))
        assertTrue(createNodeBody.contains("applyActiveProfileNodes(profileId, nodes)"))
        assertTrue(addSingleNodeBody.contains("applyActiveProfileNodes(profileId, nodes)"))
        assertFalse(createNodeBody.contains("setActiveProfile(profileId)"))
        assertFalse(addSingleNodeBody.contains("setActiveProfile(profileId)"))
        assertFalse(createNodeBody.contains("_nodes.value = nodes"))
        assertFalse(addSingleNodeBody.contains("_nodes.value = nodes"))
    }
}
