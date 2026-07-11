package com.kunk.singbox.viewmodel

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionInfoViewModelAuthTest {

    @Test
    fun clashApiAuthAddsBearerTokenOnlyWhenConfigured() {
        val authenticated = Request.Builder()
            .url("http://127.0.0.1:9090/connections")
            .withClashApiAuth("secret")
            .build()
        val legacy = Request.Builder()
            .url("http://127.0.0.1:9090/connections")
            .withClashApiAuth(null)
            .build()

        assertEquals("Bearer secret", authenticated.header("Authorization"))
        assertNull(legacy.header("Authorization"))
    }
}
