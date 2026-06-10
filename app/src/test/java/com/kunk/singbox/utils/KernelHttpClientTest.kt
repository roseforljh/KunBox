package com.kunk.singbox.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KernelHttpClientTest {

    @Test
    fun shouldNotFallbackToOkHttpWhenKernelFetchWasAvailable() {
        assertFalse(KernelHttpClient.shouldFallbackToOkHttp(kernelFetchAvailable = true, vpnActive = false))
    }

    @Test
    fun shouldFallbackToOkHttpWhenKernelFetchWasNotAvailableAndVpnInactive() {
        assertTrue(KernelHttpClient.shouldFallbackToOkHttp(kernelFetchAvailable = false, vpnActive = false))
    }

    @Test
    fun shouldNotFallbackToOkHttpWhenVpnActive() {
        assertFalse(KernelHttpClient.shouldFallbackToOkHttp(kernelFetchAvailable = false, vpnActive = true))
    }

    @Test
    fun timeoutMillisRoundsUpToAtLeastOneSecond() {
        assertEquals(1L, KernelHttpClient.timeoutSecondsForTest(1))
        assertEquals(1L, KernelHttpClient.timeoutSecondsForTest(500))
        assertEquals(1L, KernelHttpClient.timeoutSecondsForTest(1000))
        assertEquals(2L, KernelHttpClient.timeoutSecondsForTest(1001))
    }

    @Test
    fun okHttpFallbackResponsesAreClosed() {
        val source = File("src/main/java/com/kunk/singbox/utils/KernelHttpClient.kt").readText()

        assertTrue(source.contains("client.newCall(request).execute().use { response ->"))
        assertTrue(source.contains("client.newCall(requestBuilder.build()).execute().use { response ->"))
        assertTrue(!source.contains("val response = client.newCall(request).execute()"))
        assertTrue(!source.contains("val response = client.newCall(requestBuilder.build()).execute()"))
    }
}
