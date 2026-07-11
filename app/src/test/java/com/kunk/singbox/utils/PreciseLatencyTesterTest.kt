package com.kunk.singbox.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PreciseLatencyTesterTest {

    @Test
    fun regularProbeClientReusesSharedNetworkResources() {
        val client = PreciseLatencyTester.buildClient(
            proxyPort = 2080,
            timeoutMs = 1_500,
            standard = PreciseLatencyTester.Standard.RTT
        )

        assertSame(NetworkClient.client.dispatcher, client.dispatcher)
        assertSame(NetworkClient.client.connectionPool, client.connectionPool)
    }

    @Test
    fun failedProbeKeepsSharedDispatcherAlive() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        val executor = NetworkClient.client.dispatcher.executorService

        val result = PreciseLatencyTester.test(
            proxyPort = closedPort,
            url = "https://www.gstatic.com/generate_204",
            timeoutMs = 200,
            warmup = false
        )

        assertEquals(-1L, result.latencyMs)
        assertFalse(executor.isShutdown)

        val executed = CountDownLatch(1)
        executor.execute { executed.countDown() }
        assertTrue(executed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun headersOnlyProbeBuildsHeadRequest() {
        val request = PreciseLatencyTester.buildRequest(
            url = "https://connect.facebook.net/en_US/sdk.js",
            headersOnly = true
        )

        assertEquals("HEAD", request.method)
        assertEquals("no-cache", request.header("Cache-Control"))
    }

    @Test
    fun normalProbeKeepsGetRequest() {
        val request = PreciseLatencyTester.buildRequest(
            url = "https://www.gstatic.com/generate_204",
            headersOnly = false
        )

        assertEquals("GET", request.method)
        assertNull(request.header("Range"))
    }

    @Test
    fun headersOnlyFallbackBuildsRangeRequest() {
        val request = PreciseLatencyTester.buildHeadersOnlyFallbackRequest(
            url = "https://connect.facebook.net/en_US/sdk.js"
        )

        assertEquals("GET", request.method)
        assertEquals("bytes=0-0", request.header("Range"))
    }
}
