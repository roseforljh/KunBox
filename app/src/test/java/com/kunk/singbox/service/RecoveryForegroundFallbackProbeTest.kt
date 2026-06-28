package com.kunk.singbox.service

import com.kunk.singbox.core.BoxWrapperManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryForegroundFallbackProbeTest {

    @Test
    fun foregroundHardFallbackProbeSignalRunsTunnelOnlyAfterPhysicalProbe() = runBlocking {
        var tunnelCalls = 0

        val signal = SingBoxService.collectForegroundHardFallbackProbeSignalForTest(
            physicalProbe = { true },
            tunnelProbe = {
                tunnelCalls += 1
                false
            }
        )

        assertTrue(signal.physicalProbeOk)
        assertFalse(signal.tunnelProbeOk)
        assertEquals(1, tunnelCalls)
    }

    @Test
    fun foregroundHardFallbackProbeSignalSkipsTunnelWhenPhysicalProbeFails() = runBlocking {
        var tunnelCalls = 0

        val signal = SingBoxService.collectForegroundHardFallbackProbeSignalForTest(
            physicalProbe = { false },
            tunnelProbe = {
                tunnelCalls += 1
                true
            }
        )

        assertFalse(signal.physicalProbeOk)
        assertFalse(signal.tunnelProbeOk)
        assertEquals(0, tunnelCalls)
    }

    @Test
    fun foregroundHardFallbackProbeSignalTreatsExceptionsAsFailure() = runBlocking {
        val signal = SingBoxService.collectForegroundHardFallbackProbeSignalForTest(
            physicalProbe = { true },
            tunnelProbe = { error("tunnel failed") }
        )

        assertTrue(signal.physicalProbeOk)
        assertFalse(signal.tunnelProbeOk)
    }

    @Test
    fun foregroundHardFallbackSkipRequiresPhysicalAndTunnelProbe() {
        assertTrue(
            SingBoxService.shouldSkipForegroundHardFallbackAfterProbe(
                SingBoxServiceForegroundHardFallbackProbeSignal(
                    physicalProbeOk = true,
                    tunnelProbeOk = true
                )
            )
        )
        assertFalse(
            SingBoxService.shouldSkipForegroundHardFallbackAfterProbe(
                SingBoxServiceForegroundHardFallbackProbeSignal(
                    physicalProbeOk = true,
                    tunnelProbeOk = false
                )
            )
        )
        assertFalse(
            SingBoxService.shouldSkipForegroundHardFallbackAfterProbe(
                SingBoxServiceForegroundHardFallbackProbeSignal(
                    physicalProbeOk = false,
                    tunnelProbeOk = true
                )
            )
        )
    }

    @Test
    fun dnsFailoverProbeUrlsUseHostnamesToExerciseRemoteDns() {
        assertEquals("https://www.gstatic.com/generate_204", BoxWrapperManager.resolveGoogleProbeUrlForTest())
        assertEquals("https://www.cloudflare.com/cdn-cgi/trace", BoxWrapperManager.resolveCloudflareProbeUrlForTest())
        assertEquals("https://connect.facebook.net/en_US/sdk.js", BoxWrapperManager.resolveMetaProbeUrlForTest())
    }

    @Test
    fun activeHealthSignalHandlesGeneralAndMetaOnlyFailure() {
        assertFalse(
            SingBoxService.shouldTreatActiveProbeAsNodeFailure(
                googleProbeOk = true,
                cloudflareProbeOk = false,
                metaProbeOk = true
            )
        )
        assertTrue(
            SingBoxService.shouldTreatActiveProbeAsNodeFailure(
                googleProbeOk = false,
                cloudflareProbeOk = false,
                metaProbeOk = false
            )
        )
        assertTrue(
            SingBoxService.shouldTreatActiveProbeAsNodeFailure(
                googleProbeOk = true,
                cloudflareProbeOk = true,
                metaProbeOk = false
            )
        )
    }

    @Test
    fun activeProbeSchedulerUsesMetaCanaryForFastTick() {
        assertEquals(
            listOf(BoxWrapperManager.META_TUNNEL_HEALTH_PROBE_URL),
            SingBoxService.resolveActiveProbeTargetsForTest(fullSweep = false)
        )
        assertEquals(
            listOf(
                BoxWrapperManager.FOREGROUND_TUNNEL_HEALTH_PROBE_URL,
                BoxWrapperManager.CLOUDFLARE_TUNNEL_HEALTH_PROBE_URL,
                BoxWrapperManager.META_TUNNEL_HEALTH_PROBE_URL
            ),
            SingBoxService.resolveActiveProbeTargetsForTest(fullSweep = true)
        )
    }
}
