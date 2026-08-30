package com.kunk.singbox.manager

import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.ipc.DataPlaneReadinessSnapshot
import com.kunk.singbox.ipc.DataPlaneStatus
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.service.root.RootTransparentForegroundService
import com.kunk.singbox.model.TrafficCaptureMode
import com.kunk.singbox.service.manager.VpnStopInitiator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class VpnServiceManagerTest {

    @Test
    fun buildStartCommandForVpnIncludesProvidedConfigPath() {
        val command = VpnServiceManager.buildStartCommand(
            tunMode = true,
            configPath = "/data/user/0/com.kunk.singbox/files/running_config.json",
            cleanCache = true
        )

        assertEquals(SingBoxService::class.java, command.serviceClass)
        assertEquals(SingBoxService.ACTION_START, command.action)
        assertEquals(
            "/data/user/0/com.kunk.singbox/files/running_config.json",
            command.configPath
        )
        assertEquals(true, command.cleanCache)
    }

    @Test
    fun buildStartCommandForProxyKeepsConfigPathOptional() {
        val command = VpnServiceManager.buildStartCommand(tunMode = false)

        assertEquals(ProxyOnlyService::class.java, command.serviceClass)
        assertEquals(ProxyOnlyService.ACTION_START, command.action)
        assertNull(command.configPath)
        assertEquals(false, command.cleanCache)
    }

    @Test
    fun tunModeReadsSettingsRepositoryInsteadOfLegacyPreferences() {
        val source = File("src/main/java/com/kunk/singbox/manager/VpnServiceManager.kt").readText()

        assertTrue(source.contains("SettingsRepository"))
        assertFalse(source.contains("com.kunk.singbox_preferences"))
        assertFalse(source.contains("tun_enabled"))
    }

    @Test
    fun buildStartCommandForRootUsesRootForegroundService() {
        val command = VpnServiceManager.buildStartCommand(
            mode = TrafficCaptureMode.ROOT_TRANSPARENT,
            configPath = "/data/user/0/com.kunk.singbox/files/root.json",
            requestId = "root-request",
            cleanCache = true
        )

        assertEquals(RootTransparentForegroundService::class.java, command.serviceClass)
        assertEquals(RootTransparentForegroundService.ACTION_START, command.action)
        assertEquals("/data/user/0/com.kunk.singbox/files/root.json", command.configPath)
        assertEquals("root-request", command.requestId)
        assertFalse(command.cleanCache)
    }

    @Test
    fun onlyRootModeCreatesCandidateRequestId() {
        assertTrue(VpnServiceManager.newCandidateRequestId(TrafficCaptureMode.ROOT_TRANSPARENT).isNullOrBlank().not())
        assertNull(VpnServiceManager.newCandidateRequestId(TrafficCaptureMode.VPN))
        assertNull(VpnServiceManager.newCandidateRequestId(TrafficCaptureMode.PROXY_ONLY))
    }

    @Test
    fun runtimeStateDoesNotReadLegacyVpnPreferences() {
        val source = File("src/main/java/com/kunk/singbox/manager/VpnServiceManager.kt").readText()

        assertTrue(source.contains("VpnStateStore.getActive()"))
        assertTrue(source.contains("VpnStateStore.getPending()"))
        assertFalse(source.contains("PREFS_VPN_STATE"))
        assertFalse(source.contains("getSharedPreferences"))
    }

    @Test
    fun terminalStoppingStateDoesNotKeepVpnBusyForever() {
        assertTrue(
            VpnServiceManager.isTerminalStoppingState(
                pending = "stopping",
                active = false,
                runtimeStateOrdinal = ServiceState.STOPPED.ordinal
            )
        )
        assertFalse(
            VpnServiceManager.isTerminalStoppingState(
                pending = "stopping",
                active = false,
                runtimeStateOrdinal = ServiceState.STOPPING.ordinal
            )
        )
        assertFalse(
            VpnServiceManager.isTerminalStoppingState(
                pending = "stopping",
                active = true,
                runtimeStateOrdinal = ServiceState.STOPPED.ordinal
            )
        )
    }

    @Test
    fun knownRuntimeModeStopsOnlyMatchingService() {
        assertTrue(
            VpnServiceManager.shouldDispatchStopToService(
                activeMode = VpnStateStore.CoreMode.VPN,
                serviceMode = VpnStateStore.CoreMode.VPN
            )
        )
        assertFalse(
            VpnServiceManager.shouldDispatchStopToService(
                activeMode = VpnStateStore.CoreMode.VPN,
                serviceMode = VpnStateStore.CoreMode.PROXY
            )
        )
        assertTrue(
            VpnServiceManager.shouldDispatchStopToService(
                activeMode = VpnStateStore.CoreMode.ROOT,
                serviceMode = VpnStateStore.CoreMode.ROOT
            )
        )
        assertTrue(
            VpnServiceManager.shouldDispatchStopToService(
                activeMode = VpnStateStore.CoreMode.PROXY,
                serviceMode = VpnStateStore.CoreMode.PROXY
            )
        )
        assertFalse(
            VpnServiceManager.shouldDispatchStopToService(
                activeMode = VpnStateStore.CoreMode.PROXY,
                serviceMode = VpnStateStore.CoreMode.VPN
            )
        )
    }

    @Test
    fun unknownRuntimeModeDoesNotStartServicesJustToStopThem() {
        assertFalse(
            VpnServiceManager.shouldDispatchStopToService(
                activeMode = VpnStateStore.CoreMode.NONE,
                serviceMode = VpnStateStore.CoreMode.VPN
            )
        )
        assertFalse(
            VpnServiceManager.shouldDispatchStopToService(
                activeMode = VpnStateStore.CoreMode.NONE,
                serviceMode = VpnStateStore.CoreMode.PROXY
            )
        )
    }

    @Test
    fun stopWithoutAnOwnerIsIdempotentAndClearsStaleState() {
        val source = File("src/main/java/com/kunk/singbox/manager/VpnServiceManager.kt")
            .readText(Charsets.UTF_8)
        val stop = source.substringAfter("fun stopVpn(context: Context, initiator: VpnStopInitiator)")
            .substringBefore("fun restartVpn(context: Context)")

        assertTrue(stop.contains("markStopCompletedWithoutService(initiator.isManualStop)"))
        assertTrue(stop.contains("return@runCatching Unit"))
        assertFalse(stop.contains("No active VPN stop owner"))
    }

    @Test
    fun stopVpnUsesPersistedModeBeforeDispatching() {
        val source = File("src/main/java/com/kunk/singbox/manager/VpnServiceManager.kt").readText()
        val start = source.indexOf("fun stopVpn(context: Context, initiator: VpnStopInitiator)")
        val body = source.substring(
            start,
            source.indexOf("fun restartVpn(context: Context)", start)
        )

        assertTrue(body.contains("SingBoxService::class.java"))
        assertTrue(body.contains("ProxyOnlyService::class.java"))
        assertTrue(body.contains("RootTransparentForegroundService::class.java"))
        assertTrue(body.contains("val activeMode = resolveStopOwnerMode()"))
        assertTrue(body.contains("shouldDispatchStopToService(activeMode, VpnStateStore.CoreMode.VPN)"))
        assertTrue(body.contains("shouldDispatchStopToService(activeMode, VpnStateStore.CoreMode.PROXY)"))
        assertTrue(body.contains("shouldDispatchStopToService(activeMode, VpnStateStore.CoreMode.ROOT)"))
        assertTrue(body.contains("putExtra(SingBoxService.EXTRA_STOP_INITIATOR, initiator.wireValue)"))
    }

    @Test
    fun startPersistsTargetModeBeforeDispatchingService() {
        val source = File("src/main/java/com/kunk/singbox/manager/VpnServiceManager.kt").readText()
        val body = source.substringAfter("fun startVpn(context: Context, mode: TrafficCaptureMode)")
            .substringBefore("fun stopVpn(context: Context, initiator: VpnStopInitiator)")

        val ownerIndex = body.indexOf("VpnStateStore.setStopOwnerMode(targetMode)")
        val persistIndex = body.indexOf("VpnStateStore.setMode(targetMode)")
        val dispatchIndex = body.indexOf("startForegroundService(intent)")
        assertTrue(ownerIndex >= 0)
        assertTrue(persistIndex > ownerIndex)
        assertTrue(dispatchIndex > persistIndex)
    }

    @Test
    fun stopModeResolutionUsesLiveRootStartupWhenPersistedModeIsUnknown() {
        val source = File("src/main/java/com/kunk/singbox/manager/VpnServiceManager.kt").readText()
        val resolver = source.substringAfter("private fun resolveActiveMode()")
            .substringBefore("fun stopVpn(context: Context")

        assertTrue(resolver.contains("RootTransparentForegroundService.isRunning"))
        assertTrue(resolver.contains("RootTransparentForegroundService.isStarting"))
        assertTrue(resolver.contains("VpnStateStore.CoreMode.ROOT"))
        assertTrue(resolver.contains("VpnStateStore.getStopOwnerMode() ?: resolveActiveMode()"))
    }

    @Test
    fun forceStopUsesEmergencyActionForBothServiceModes() {
        val source = File("src/main/java/com/kunk/singbox/manager/VpnServiceManager.kt")
            .readText(Charsets.UTF_8)
        val body = source.substringAfter("fun forceStop(context: Context)")
            .substringBefore("fun restartVpn(context: Context)")

        assertTrue(body.contains("SingBoxService.ACTION_FORCE_STOP"))
        assertTrue(body.contains("ProxyOnlyService.ACTION_FORCE_STOP"))
        assertTrue(body.contains("RootTransparentForegroundService.ACTION_FORCE_STOP"))
    }

    @Test
    fun onlyExplicitUserActionsHaveManualStopSemantics() {
        assertTrue(VpnStopInitiator.USER_UI.isManualStop)
        assertTrue(VpnStopInitiator.QUICK_SETTINGS.isManualStop)
        assertTrue(VpnStopInitiator.NOTIFICATION.isManualStop)
        assertFalse(VpnStopInitiator.TRUSTED_WIFI.isManualStop)
        assertFalse(VpnStopInitiator.METERED_PROTECTION.isManualStop)
        assertFalse(VpnStopInitiator.MODE_SWITCH.isManualStop)
        assertFalse(VpnStopInitiator.START_TIMEOUT.isManualStop)
        assertFalse(VpnStopInitiator.RESTART.isManualStop)
        assertFalse(VpnStopInitiator.SYSTEM_REVOKE.isManualStop)
        assertFalse(VpnStopInitiator.UNKNOWN.isManualStop)
        assertEquals(VpnStopInitiator.UNKNOWN, VpnStopInitiator.fromWireValue("invalid"))
    }

    @Test
    @Suppress("LongMethod")
    fun rootPolicyConfirmationSeparatesRuntimeAndRoutingGenerations() {
        val generation = com.kunk.singbox.repository.ConfigRepository.ConfigGenerationResult(
            path = "root/config.json",
            activeNodeTag = "node",
            outboundTags = setOf("node"),
            requestId = "request-1",
            configDigest = "config",
            appRoutingDigest = "app",
            rootRoutingSidecarDigest = "sidecar",
            rootRoutingStaticPlanDigest = "static",
            rootRoutingAppDigest = "routing",
            rootRoutingGeneration = 1_788_054_884_342L
        )
        val readiness = DataPlaneReadinessSnapshot(
            status = DataPlaneStatus.READY,
            coreReady = true,
            selectorReady = true,
            rootRuntimeSessionId = "root-session",
            rootRoutingGeneration = generation.rootRoutingGeneration,
            rootConfigSha256 = generation.configDigest,
            rootSidecarSha256 = generation.rootRoutingSidecarDigest,
            rootStaticPlanSha256 = generation.rootRoutingStaticPlanDigest,
            rootAppRoutingSha256 = generation.rootRoutingAppDigest,
            rootResolvedPlanSha256 = "resolved",
            rootWatchdogReady = true,
            rootRulesInstalled = true,
            serviceInstanceId = "service-1"
        )
        val applied = VpnStateStore.AppliedPerAppPolicySnapshot(
            revision = 5L,
            digest = "policy",
            serviceInstanceId = "service-1",
            runtimeGeneration = 114_746_858_063_364L,
            requestId = generation.requestId,
            configDigest = generation.configDigest,
            appRoutingDigest = "app",
            sidecarFileSha256 = generation.rootRoutingSidecarDigest,
            staticPlanSha256 = generation.rootRoutingStaticPlanDigest,
            rootRoutingAppSha256 = generation.rootRoutingAppDigest,
            resolvedPlanSha256 = "resolved",
            rootRuntimeSessionId = "root-session"
        )
        val runtime = VpnStateStore.RuntimeStateSnapshot(
            generation = 114_746_858_063_365L,
            stateOrdinal = ServiceState.RUNNING.ordinal,
            readiness = readiness
        )

        assertTrue(
            VpnServiceManager.isRootPerAppPolicyConfirmationSatisfied(
                targetRevision = 5L,
                targetDigest = "policy",
                targetRoutingDigest = "app",
                generation = generation,
                applied = applied,
                runtime = runtime
            )
        )
        assertFalse(
            VpnServiceManager.isRootPerAppPolicyConfirmationSatisfied(
                targetRevision = 5L,
                targetDigest = "policy",
                targetRoutingDigest = "app",
                generation = generation,
                applied = applied.copy(serviceInstanceId = "stale-service"),
                runtime = runtime
            )
        )
        assertFalse(
            VpnServiceManager.isRootPerAppPolicyConfirmationSatisfied(
                targetRevision = 5L,
                targetDigest = "policy",
                targetRoutingDigest = "app",
                generation = generation,
                applied = applied.copy(rootRuntimeSessionId = "stale-session"),
                runtime = runtime
            )
        )
    }

    @Test
    fun systemRevokeIsRecordedAsAutomaticStop() {
        val source = File("src/main/java/com/kunk/singbox/service/vpn/SingBoxControlRuntime.kt")
            .readText(Charsets.UTF_8)
        val body = source.substringAfter("internal fun SingBoxService.onRevokeRuntime()")
            .substringBefore("internal fun SingBoxService")

        assertTrue(body.contains("lastStopInitiator = VpnStopInitiator.SYSTEM_REVOKE"))
        assertTrue(body.contains("VpnStateStore.setManuallyStopped(false)"))
        assertFalse(body.contains("VpnStateStore.setManuallyStopped(true)"))
    }
}
