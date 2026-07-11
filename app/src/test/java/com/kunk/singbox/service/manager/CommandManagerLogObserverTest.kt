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
}
