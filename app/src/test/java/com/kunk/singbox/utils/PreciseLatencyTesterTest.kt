package com.kunk.singbox.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreciseLatencyTesterTest {

    @Test
    fun headersOnlyProbeBuildsHeadRequest() {
        val request = PreciseLatencyTester.buildRequestForTest(
            url = "https://connect.facebook.net/en_US/sdk.js",
            headersOnly = true
        )

        assertEquals("HEAD", request.method)
        assertEquals("no-cache", request.header("Cache-Control"))
    }

    @Test
    fun normalProbeKeepsGetRequest() {
        val request = PreciseLatencyTester.buildRequestForTest(
            url = "https://www.gstatic.com/generate_204",
            headersOnly = false
        )

        assertEquals("GET", request.method)
        assertNull(request.header("Range"))
    }

    @Test
    fun headersOnlyFallbackBuildsRangeRequest() {
        val request = PreciseLatencyTester.buildHeadersOnlyFallbackRequestForTest(
            url = "https://connect.facebook.net/en_US/sdk.js"
        )

        assertEquals("GET", request.method)
        assertEquals("bytes=0-0", request.header("Range"))
    }
}
