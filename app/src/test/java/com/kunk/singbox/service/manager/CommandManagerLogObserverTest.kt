package com.kunk.singbox.service.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandManagerLogObserverTest {

    @Test
    fun dispatchesObserverWhenUiLogsAreDisabled() {
        val observed = mutableListOf<String>()
        val stored = mutableListOf<String>()

        CommandManager.dispatchKernelLog(
            message = "ERROR dns: exchange failed for graph.facebook.com. IN A: context deadline exceeded",
            uiLogsEnabled = false,
            observer = { observed.add(it) },
            addToRepository = { stored.add(it) }
        )

        assertEquals(1, observed.size)
        assertTrue(stored.isEmpty())
    }

    @Test
    fun storesLogWhenUiLogsAreEnabled() {
        val observed = mutableListOf<String>()
        val stored = mutableListOf<String>()

        CommandManager.dispatchKernelLog(
            message = "INFO test",
            uiLogsEnabled = true,
            observer = { observed.add(it) },
            addToRepository = { stored.add(it) }
        )

        assertEquals(listOf("INFO test"), observed)
        assertEquals(listOf("INFO test"), stored)
    }

    @Test
    fun commandLogReconnectBackoffCapsAndNeverExhausts() {
        assertTrue(CommandManager.COMMAND_LOG_RECONNECT_DELAYS_MS.contentEquals(
            longArrayOf(500L, 1_000L, 2_000L, 4_000L, 8_000L)
        ))
        assertEquals(500L, CommandManager.commandLogReconnectDelay(1))
        assertEquals(8_000L, CommandManager.commandLogReconnectDelay(100))
        assertEquals(4, CommandManager.nextCommandLogFailureCount(previous = 3, stable = false))
        assertEquals(1, CommandManager.nextCommandLogFailureCount(previous = 3, stable = true))
        assertTrue(CommandManager.COMMAND_LOG_HEARTBEAT_TIMEOUT_MS > 5_000L)
        assertEquals(false, CommandManager.isCommandHeartbeatStale(1_000L, 16_000L))
        assertTrue(CommandManager.isCommandHeartbeatStale(1_000L, 16_001L))
        assertTrue(CommandManager.isCommandHeartbeatStale(null, 16_000L))
    }

    @Test
    fun commandLogCallbacksRequireMatchingSessionAndClientToken() {
        assertTrue(CommandManager.acceptsCommandLogCallback(7L, 7L, 11L, 11L, 0L))
        assertTrue(CommandManager.acceptsCommandLogCallback(7L, 7L, 12L, 11L, 12L))
        assertEquals(false, CommandManager.acceptsCommandLogCallback(6L, 7L, 11L, 11L, 0L))
        assertEquals(false, CommandManager.acceptsCommandLogCallback(7L, 7L, 13L, 11L, 12L))
    }

    @Test
    fun replayedKernelLogsDoNotReachLiveFailureObservers() {
        assertEquals(false, CommandManager.shouldNotifyCommandLogObserver(replayed = true))
        assertTrue(CommandManager.shouldNotifyCommandLogObserver(replayed = false))
    }
}
