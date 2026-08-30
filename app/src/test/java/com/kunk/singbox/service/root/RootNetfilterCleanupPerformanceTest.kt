package com.kunk.singbox.service.root

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootNetfilterCleanupPerformanceTest {
    @Test
    fun fastCleanupUsesRestoreAndIpBatchForOnlyInstalledState() {
        val plan = RootNetfilterPlanner.build(config())
        val cleanup = cleanupCommandsForInstalledSetup(plan.setupCommands)
        val script = requireNotNull(buildRootNetfilterCleanupScript(cleanup))

        assertTrue(script.contains("iptables-restore -w 2 --noflush"))
        assertFalse(script.contains("ip6tables-restore -w 2 --noflush"))
        assertTrue(script.contains("-D OUTPUT -j KBX_OUT4"))
        assertTrue(script.indexOf("-D OUTPUT -j KBX_OUT4") < script.indexOf("-F KBX_OUT4"))
        assertTrue(script.indexOf("-F KBX_OUT4") < script.indexOf("-X KBX_OUT4"))
        assertTrue(script.contains("ip -batch - <<'KBX_IP_4'"))
        assertFalse(cleanup.any { "-A" in it || "-I" in it || "add" in it })
    }

    @Test
    fun verifiedFastCleanupClearsOwnershipWithoutSlowRecoveryScript() {
        val directory = Files.createTempDirectory("root-fast-cleanup-test").toFile()
        val config = config()
        val plan = RootNetfilterPlanner.build(config)
        var fastCleanupCalls = 0
        var recoveryCalls = 0
        val executor = object : RootCommandExecutor {
            override fun execute(arguments: List<String>): RootCommandResult {
                if (arguments.firstOrNull() == "/system/bin/sh") recoveryCalls++
                return RootCommandResult(0, "")
            }

            override fun executeFastNetfilterPlan(commands: List<List<String>>): RootCommandResult =
                RootCommandResult(0, runningRootStateSnapshot(plan.setupCommands))

            override fun executeFastNetfilterCleanupPlan(commands: List<List<String>>): RootCommandResult {
                fastCleanupCalls++
                return RootCommandResult(0, cleanRootStateSnapshot())
            }
        }
        val store = RootNetfilterOwnershipStore(executor, directory)
        val manager = RootNetfilterManager(executor, store)
        try {
            manager.beginOwnership(RootNetfilterOwnership.context("fast-cleanup", 1L, "a".repeat(64)))
                .getOrThrow()
            manager.apply(config).getOrThrow()

            assertTrue(store.hasOwner())
            manager.cleanup().getOrThrow()

            assertEquals(1, fastCleanupCalls)
            assertEquals(0, recoveryCalls)
            assertFalse(store.hasOwner())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun config(): RootNetfilterConfig = RootNetfilterConfig(
        capturedUids = listOf(10123),
        capturedUidRanges = emptyList(),
        excludedUids = emptyList(),
        appUid = 10234,
        proxyIpv4 = true,
        proxyIpv6 = false,
        blockIpv4 = false,
        blockIpv6 = false,
        redirectPortIpv4 = 1536,
        redirectPortIpv6 = 1537,
        tproxyPortIpv4 = 1538,
        tproxyPortIpv6 = 1539
    )

    private fun runningRootStateSnapshot(commands: List<List<String>>): String = buildString {
        append("__KBX_ROOT_STATE_iptables4__\n")
        commands.filter { it.firstOrNull() == "iptables" }.forEach { command ->
            val tableIndex = command.indexOf("-t")
            val operationIndex = tableIndex + 2
            when (command.getOrNull(operationIndex)) {
                "-N" -> append(':').append(command[operationIndex + 1]).append(" - [0:0]\n")
                "-A" -> append(command.drop(operationIndex).joinToString(" ")).append('\n')
                "-I" -> {
                    val chain = command[operationIndex + 1]
                    val jump = command.indexOf("-j")
                    append("-A ").append(chain).append(" -j ").append(command[jump + 1]).append('\n')
                }
            }
        }
        append("__KBX_ROOT_STATE_rule4__\n")
        append("12031: from all fwmark 0x2331/0xffffffff lookup 20231\n")
        append("__KBX_ROOT_STATE_rule6__\n")
        append("__KBX_ROOT_STATE_route4__\n")
        append("local 0.0.0.0/0 dev lo table 20231\n")
        append("__KBX_ROOT_STATE_route6__\n")
    }

    private fun cleanRootStateSnapshot(): String = buildString {
        append("__KBX_ROOT_STATE_iptables4__\n")
        append("__KBX_ROOT_STATE_iptables6__\n")
        append("__KBX_ROOT_STATE_rule4__\n")
        append("__KBX_ROOT_STATE_rule6__\n")
        append("__KBX_ROOT_STATE_route4__\n")
        append("__KBX_ROOT_STATE_route6__\n")
    }
}
