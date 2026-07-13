package com.kunk.singbox.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveNodeHealthCheckerTest {

    @Test
    fun evaluatePassesWhenSelectedMatchesAndDnsQuiet() {
        assertNull(
            evaluateAutoFailoverLiveCheck(
                targetTag = "node-b",
                selectedTag = "node-b",
                recentRemoteDnsFailures = 0
            )
        )
    }

    @Test
    fun evaluateFailsWhenSelectedMismatch() {
        assertEquals(
            "selected_mismatch",
            evaluateAutoFailoverLiveCheck(
                targetTag = "node-b",
                selectedTag = "node-a",
                recentRemoteDnsFailures = 0
            )
        )
    }

    @Test
    fun evaluateDoesNotRequireOfflineDelay() {
        // 离线延迟假活不可信，live 终验不再依赖它
        assertNull(
            evaluateAutoFailoverLiveCheck(
                targetTag = "node-b",
                selectedTag = "node-b",
                offlineDelayMs = null,
                recentRemoteDnsFailures = 0
            )
        )
    }

    @Test
    fun evaluateFailsWhenLiveRemoteDnsTimeoutsSpike() {
        assertEquals(
            "live_remote_dns_timeout",
            evaluateAutoFailoverLiveCheck(
                targetTag = "node-b",
                selectedTag = "node-b",
                offlineDelayMs = 50L,
                recentRemoteDnsFailures = 3
            )
        )
    }

    @Test
    fun evaluateAcceptsNormalizedSelectedTag() {
        assertNull(
            evaluateAutoFailoverLiveCheck(
                targetTag = " node-a ",
                selectedTag = "node-a",
                offlineDelayMs = 10L,
                recentRemoteDnsFailures = 0
            )
        )
    }
}
