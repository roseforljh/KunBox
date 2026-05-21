package com.kunk.singbox.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelHttpClientTest {

    @Test
    fun shouldNotFallbackToOkHttpWhenKernelFetchWasAvailable() {
        assertFalse(KernelHttpClient.shouldFallbackToOkHttp(kernelFetchAvailable = true))
    }

    @Test
    fun shouldFallbackToOkHttpWhenKernelFetchWasNotAvailable() {
        assertTrue(KernelHttpClient.shouldFallbackToOkHttp(kernelFetchAvailable = false))
    }
}
