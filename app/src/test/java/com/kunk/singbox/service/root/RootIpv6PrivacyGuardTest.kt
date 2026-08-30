package com.kunk.singbox.service.root

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootIpv6PrivacyGuardTest {
    @Test
    fun publicIpv6IsDisabledAndOriginalStateIsRestored() {
        val root = Files.createTempDirectory("root-ipv6-privacy")
        try {
            val conf = root.resolve("conf").createDirectories()
            control(conf, "default", 0)
            control(conf, "all", 0)
            control(conf, "lo", 0)
            control(conf, "wlan0", 0)
            control(conf, "dummy0", 0)
            val addresses = root.resolve("if_inet6").createFile()
            addresses.writeText(
                "240e04710ab033bba0cc14fffed4904f 02 40 00 80 wlan0\n" +
                    "fd000000000000000000000000000001 03 40 00 80 dummy0\n"
            )
            val state = root.resolve("runtime/ipv6-privacy-state").also { it.parent.createDirectories() }.toFile()
            val guard = RootIpv6PrivacyGuard(conf.toFile(), addresses.toFile(), state)

            assertTrue(guard.activate("session-1").isSuccess)
            assertEquals("1", controlFile(conf, "default").readText())
            assertEquals("1", controlFile(conf, "wlan0").readText())
            assertEquals("0", controlFile(conf, "dummy0").readText())
            assertTrue(state.readText().contains("iface=wlan0|0"))

            assertTrue(guard.restore().isSuccess)
            assertEquals("0", controlFile(conf, "default").readText())
            assertEquals("0", controlFile(conf, "wlan0").readText())
            assertFalse(state.exists())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun interfaceCreatedDuringSessionReturnsToOriginalDefault() {
        val root = Files.createTempDirectory("root-ipv6-new-interface")
        try {
            val conf = root.resolve("conf").createDirectories()
            control(conf, "default", 0)
            control(conf, "all", 0)
            control(conf, "lo", 0)
            val addresses = root.resolve("if_inet6").createFile()
            val state = root.resolve("runtime/ipv6-privacy-state").also { it.parent.createDirectories() }.toFile()
            val guard = RootIpv6PrivacyGuard(conf.toFile(), addresses.toFile(), state)

            assertTrue(guard.activate("session-2").isSuccess)
            control(conf, "rmnet_data0", 1)
            addresses.writeText("240e0000000000000000000000000001 02 40 00 80 rmnet_data0\n")
            assertTrue(guard.enforce().isSuccess)
            assertTrue(state.readText().contains("iface=rmnet_data0|0"))

            assertTrue(guard.restore().isSuccess)
            assertEquals("0", controlFile(conf, "rmnet_data0").readText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun watchdogRestoresPrivacyStateAfterAbnormalRootExit() {
        val script = File("src/main/assets/root/kunbox-root-watchdog.sh").readText()

        assertTrue(script.contains("restore_ipv6_privacy"))
        assertTrue(script.contains("ipv6-privacy-state"))
        val cleanupIndex = script.indexOf("cleanup_owned \"\$SESSION_ID\"")
        val privacyRestoreIndex = script.indexOf("restore_ipv6_privacy \"\$SESSION_ID\"")
        assertTrue(cleanupIndex in 0 until privacyRestoreIndex)
        assertFalse(script.contains("net.ipv6.conf.all.disable_ipv6"))
    }

    private fun control(conf: java.nio.file.Path, interfaceName: String, value: Int) {
        val directory = conf.resolve(interfaceName).createDirectories()
        directory.resolve("disable_ipv6").writeText(value.toString())
    }

    private fun controlFile(conf: java.nio.file.Path, interfaceName: String): File =
        conf.resolve(interfaceName).resolve("disable_ipv6").toFile()
}
