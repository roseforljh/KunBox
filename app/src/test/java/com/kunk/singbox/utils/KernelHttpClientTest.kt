package com.kunk.singbox.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
