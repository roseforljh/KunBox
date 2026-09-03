package com.kunk.singbox.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConfigRepositoryNodeAddPolicyTest {

    @Test
    fun createdNodeActivatesOnlyWhenThereIsNoCurrentProfile() {
        assertTrue(ConfigRepository.shouldActivateCreatedNode(null))
        assertFalse(ConfigRepository.shouldActivateCreatedNode("profile-a"))
    }

    @Test
    fun manualNodeAddPathsUseCreatedNodeActivationPolicy() {
        val source = File(
            "src/main/java/com/kunk/singbox/repository/configrepo/ConfigRepositoryPart9.kt"
        ).readText()
        val createNodeBody = source.substring(
            source.indexOf("fun ConfigRepository.createNode("),
            source.indexOf("suspend fun ConfigRepository.deleteNode(")
        )
        val addSingleNodeBody = source.substring(
            source.indexOf("suspend fun ConfigRepository.addSingleNode("),
            source.indexOf("suspend fun ConfigRepository.updateNode(")
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
