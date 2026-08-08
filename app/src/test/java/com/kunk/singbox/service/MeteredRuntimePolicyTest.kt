package com.kunk.singbox.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MeteredRuntimePolicyTest {
    @Test
    fun proxyModeAlwaysSubscribesToGroupsConnectionsAndLogs() {
        val source = proxySource()
        val body = source
            .substringAfter("private fun createRuntimeCommandOptions()")
            .substringBefore("private fun handleRuntimeLogs(")

        assertTrue(body.contains("addCommand(Libbox.CommandGroup)"))
        assertTrue(body.contains("addCommand(Libbox.CommandConnections)"))
        assertTrue(body.contains("addCommand(Libbox.CommandLog)"))
    }

    @Test
    fun proxyModeImplementsAllSameNodeRecoveryStages() {
        val source = proxySource()
        val body = source
            .substringAfter("private fun createSameNodeRecoveryCoordinator(")
            .substringBefore("private fun handleRuntimeGroups(")

        assertTrue(body.contains("closeRuntimeConnections()"))
        assertTrue(body.contains("BoxWrapperManager.resetNetwork()"))
        assertTrue(body.contains("reloadCurrentConfigForSameNodeRecovery()"))
        assertTrue(body.contains("restartCurrentConfigForSameNodeRecovery()"))
        assertTrue(body.contains("LatencyProbeTrafficKind.HEALTH_CHECK"))
    }

    @Test
    fun hotSwitchClosesConnectionsBeforePublishingRuntimeSelection() {
        val proxyBody = proxySource()
            .substringAfter("private suspend fun performHotSwitch(")
            .substringBefore("private fun initializeRuntimeSelector(")
        val vpnBody = File("src/main/java/com/kunk/singbox/service/SingBoxService.kt")
            .readText(Charsets.UTF_8)
            .substringAfter("suspend fun hotSwitchNode(nodeTag: String): Boolean")
            .substringBefore("private fun cacheUidToPackage")

        assertTrue(proxyBody.indexOf("closeRuntimeConnections()") < proxyBody.indexOf("VpnStateStore.setActiveLabel"))
        assertTrue(vpnBody.indexOf("commandManager.closeConnections()") < vpnBody.indexOf("return true"))
    }

    @Test
    fun nextNodePurgesProtectedRuntimeConfigThroughSelectionTransaction() {
        val body = File("src/main/java/com/kunk/singbox/service/manager/NodeSwitchManager.kt")
            .readText(Charsets.UTF_8)
            .substringAfter("fun switchNextNode(")
            .substringBefore("private fun recordSwitchMetric(")
        val protectionCheck = body.indexOf("requiresProtectedConfigPurge")
        val transactionalSwitch = body.indexOf("configRepository.setActiveNodeWithResult(nextNode.id)")
        val ordinaryHotSwitch = body.indexOf("callbacks?.hotSwitchNode(nextNode.name)")

        assertTrue(body.contains("it.autoSelectionEligible && !it.meteredProtected"))
        assertTrue(protectionCheck >= 0)
        assertTrue(transactionalSwitch > protectionCheck)
        assertTrue(ordinaryHotSwitch > transactionalSwitch)
    }

    @Test
    fun enablingProtectionReloadsCrossProfileRuntimeAndFailsClosed() {
        val source = File("src/main/java/com/kunk/singbox/repository/ConfigRepository.kt")
            .readText(Charsets.UTF_8)
        val updateBody = source
            .substringAfter("private suspend fun applyNodeAutoSelectionEligibilityChange(")
            .substringBefore("private fun resetRuntimeConnectionsForMeteredProtection()")
        val reloadGate = source
            .substringAfter("private fun shouldReloadNodeSettingsChange()")
            .substringBefore("protected suspend fun refreshNodesAfterNodeMutation(")

        assertTrue(updateBody.contains("if (protectionEnabled)"))
        assertTrue(updateBody.contains("resetRuntimeConnectionsForMeteredProtection()"))
        assertTrue(updateBody.contains("if (protectionEnabled) stopRuntimeForMeteredProtection()"))
        assertTrue(reloadGate.contains("SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value"))
        assertTrue(!reloadGate.contains("_activeProfileId.value != profileId"))
    }

    private fun proxySource(): String {
        return File("src/main/java/com/kunk/singbox/service/ProxyOnlyService.kt")
            .readText(Charsets.UTF_8)
    }
}
