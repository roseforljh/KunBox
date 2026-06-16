package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import com.kunk.singbox.model.ClashConnection
import com.kunk.singbox.model.ClashConnectionMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ConnectionInfoUiPolicyTest {
    @Test
    fun closeAllRemainsEnabledWhenSearchHasNoMatches() {
        val connections = listOf(sampleConnection(host = "example.com"))
        val filtered = ConnectionInfoUiPolicy.filterConnections(connections, "missing")

        assertTrue(filtered.isEmpty())
        assertTrue(
            ConnectionInfoUiPolicy.canCloseAll(
                vpnActive = true,
                allConnections = connections
            )
        )
    }

    @Test
    fun emptySubtitleUsesNoMatchWhenSearchFiltersAllConnections() {
        val connections = listOf(sampleConnection(host = "example.com"))
        val filtered = ConnectionInfoUiPolicy.filterConnections(connections, "missing")

        assertEquals(
            R.string.connection_info_no_match,
            ConnectionInfoUiPolicy.emptySubtitleRes(
                searchQuery = "missing",
                allConnections = connections,
                filteredConnections = filtered
            )
        )
    }

    @Test
    fun emptyTitleUsesNoMatchWhenSearchFiltersAllConnections() {
        val connections = listOf(sampleConnection(host = "example.com"))
        val filtered = ConnectionInfoUiPolicy.filterConnections(connections, "missing")

        assertEquals(
            R.string.connection_info_no_match_title,
            ConnectionInfoUiPolicy.emptyTitleRes(
                searchQuery = "missing",
                allConnections = connections,
                filteredConnections = filtered
            )
        )
    }

    @Test
    fun durationParsesRfc3339OffsetTime() {
        val nowMillis = parseUtcMillis("2026-06-16T07:01:30Z")

        assertEquals(
            "1m 30s",
            ConnectionInfoUiPolicy.formatDuration(
                startTime = "2026-06-16T15:00:00+08:00",
                nowMillis = nowMillis
            )
        )
    }

    @Test
    fun durationParsesNanosecondUtcTime() {
        val nowMillis = parseUtcMillis("2026-06-16T07:00:05Z")

        assertEquals(
            "5s",
            ConnectionInfoUiPolicy.formatDuration(
                startTime = "2026-06-16T07:00:00.000000000Z",
                nowMillis = nowMillis
            )
        )
    }

    private fun sampleConnection(host: String) = ClashConnection(
        id = host,
        metadata = ClashConnectionMetadata(
            network = "tcp",
            destinationIP = "93.184.216.34",
            destinationPort = "443",
            host = host
        )
    )

    private fun parseUtcMillis(value: String): Long {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(value)?.time ?: error("Invalid test time")
    }
}
