package com.kunk.singbox.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MeteredRuntimePolicyTest {
    @Test
    fun proxyModeAlwaysSubscribesToGroupsConnectionsAndLogs() {
        val source = proxySource()
        val body = source
            .substringAfter("internal fun ProxyOnlyService.createRuntimeCommandOptions()")
            .substringBefore("internal fun ProxyOnlyService.handleRuntimeLogs(")

        assertTrue(body.contains("addCommand(Libbox.CommandGroup)"))
        assertTrue(body.contains("addCommand(Libbox.CommandConnections)"))
        assertTrue(body.contains("addCommand(Libbox.CommandLog)"))
    }

    @Test
    fun proxyModeImplementsAllSameNodeRecoveryStages() {
        val source = proxySource()
        val body = source
            .substringAfter("internal fun ProxyOnlyService.createSameNodeRecoveryCoordinator(")
            .substringBefore("internal fun ProxyOnlyService.handleRuntimeGroups(")

        assertTrue(body.contains("closeRuntimeConnections()"))
        assertTrue(body.contains("BoxWrapperManager.resetNetwork()"))
        assertTrue(body.contains("reloadCurrentConfigForSameNodeRecovery()"))
        assertTrue(body.contains("restartCurrentConfigForSameNodeRecovery()"))
        assertTrue(body.contains("LatencyProbeTrafficKind.HEALTH_CHECK"))
    }

    @Test
    fun hotSwitchClosesConnectionsBeforePublishingRuntimeSelection() {
        val proxyBody = proxySource()
            .substringAfter("internal suspend fun ProxyOnlyService.performHotSwitch(")
            .substringBefore("internal fun ProxyOnlyService.initializeRuntimeSelector(")
        val vpnBody = File("src/main/java/com/kunk/singbox/service/vpn/SingBoxStartupRuntime.kt")
            .readText(Charsets.UTF_8)
            .substringAfter("internal suspend fun SingBoxService.hotSwitchNode(nodeTag: String): Boolean {")
            .substringBefore("internal fun SingBoxService.cacheUidToPackage")
        val proxyCloseIndex = proxyBody.indexOf("closeRuntimeConnections()")
        val proxyPublishIndex = proxyBody.indexOf("VpnStateStore.setActiveLabel")
        val vpnCloseIndex = vpnBody.indexOf("commandManager.closeConnections()")
        val vpnSuccessIndex = vpnBody.indexOf("return true")

        assertTrue(proxyCloseIndex >= 0 && proxyPublishIndex > proxyCloseIndex)
        assertTrue(vpnCloseIndex >= 0 && vpnSuccessIndex > vpnCloseIndex)
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
        val source = File(
            "src/main/java/com/kunk/singbox/repository/configrepo/ConfigRepositoryPart9.kt"
        )
            .readText(Charsets.UTF_8)
        val updateBody = source
            .substringAfter("internal suspend fun ConfigRepository.applyNodeAutoSelectionEligibilityChange(")
            .substringBefore("internal fun ConfigRepository.resetRuntimeConnectionsForMeteredProtection()")
        val reloadGate = source
            .substringAfter("internal fun ConfigRepository.shouldReloadNodeSettingsChange()")
            .substringBefore("internal suspend fun ConfigRepository.refreshNodesAfterNodeMutation(")

        assertTrue(updateBody.contains("if (protectionEnabled)"))
        assertTrue(updateBody.contains("resetRuntimeConnectionsForMeteredProtection()"))
        assertTrue(updateBody.contains("if (protectionEnabled) stopRuntimeForMeteredProtection()"))
        assertTrue(reloadGate.contains("SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value"))
        assertTrue(!reloadGate.contains("_activeProfileId.value != profileId"))
    }

    private fun proxySource(): String {
        return listOf(
            "src/main/java/com/kunk/singbox/service/proxy/ProxyHealthRuntime.kt",
            "src/main/java/com/kunk/singbox/service/proxy/ProxyCoreRuntime.kt"
        ).joinToString("\n") { File(it).readText(Charsets.UTF_8) }
    }
}
