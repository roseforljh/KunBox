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
                targetProbeSucceeded = true,
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
                targetProbeSucceeded = true,
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
                targetProbeSucceeded = true,
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
                targetProbeSucceeded = true,
                recentRemoteDnsFailures = 0
            )
        )
    }

    @Test
    fun evaluateFailsWhenTargetHttpsProbeWasNotSuccessful() {
        assertEquals(
            "target_https_probe_failed",
            evaluateAutoFailoverLiveCheck(
                targetTag = "node-b",
                selectedTag = "node-b",
                targetProbeSucceeded = false,
                recentRemoteDnsFailures = 0
            )
        )
    }
}
