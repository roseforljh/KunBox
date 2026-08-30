@file:Suppress("InvalidPackageDeclaration")

package com.kunk.singbox.service.root

import android.util.Log

internal class RootNetfilterVerifier(
    private val executor: RootCommandExecutor
) {
    fun captureState(netfilterBinaries: Collection<String> = emptyList()): Map<String, String> {
        val result = executor.executeBatch(rootStateSnapshotCommands(netfilterBinaries))
        check(result.success) { "Root state snapshot failed (${result.exitCode}): ${result.diagnosticOutput}" }
        return parseRootStateSnapshot(result.output) ?: error("Root state snapshot is incomplete")
    }

    fun verifyRules(
        commands: List<List<String>>,
        compatibleVerifyCommands: List<List<String>>,
        snapshot: Map<String, String>? = null,
        forbiddenChains: Set<String> = emptySet()
    ) {
        val netfilterCommands = commands.filter { it.firstOrNull() in NETFILTER_BINARIES }
        if (netfilterCommands.isEmpty()) return
        val binaries = netfilterCommands.mapNotNull(List<String>::firstOrNull).distinct()
        val snapshots = resolveSnapshots(binaries, compatibleVerifyCommands, snapshot) ?: return
        verifyForbiddenChains(snapshots, forbiddenChains)
        netfilterCommands.groupBy(List<String>::first).forEach { (binary, familyCommands) ->
            verifyFamily(binary, familyCommands, snapshots.getValue(snapshotSection(binary)))
        }
        Log.i(TAG, "[ROOT_NET] event=snapshot_verified commands=${netfilterCommands.size}")
    }

    fun verifyPolicyRouting(config: RootNetfilterConfig, snapshot: Map<String, String>? = null) {
        val state = snapshot ?: captureState()
        if (config.proxyIpv4) {
            verifyPolicyFamily(
                rules = state.getValue(ROOT_STATE_RULE4),
                routes = state.getValue(ROOT_STATE_ROUTE4),
                mark = RootNetfilterPlanner.IPV4_MARK,
                lanes = config.lanes.map { rootMark(it.markIpv4) to it.priorityIpv4 },
                family = "IPv4"
            )
        }
        if (config.proxyIpv6) {
            verifyPolicyFamily(
                rules = state.getValue(ROOT_STATE_RULE6),
                routes = state.getValue(ROOT_STATE_ROUTE6),
                mark = RootNetfilterPlanner.IPV6_MARK,
                lanes = config.lanes.map { rootMark(it.markIpv6) to it.priorityIpv6 },
                family = "IPv6"
            )
        }
    }

    private fun resolveSnapshots(
        binaries: List<String>,
        compatibleVerifyCommands: List<List<String>>,
        snapshot: Map<String, String>?
    ): Map<String, String>? {
        if (snapshot != null && binaries.all { snapshotSection(it) in snapshot }) return snapshot
        val saveResult = executor.executeBatch(binaries.map { listOf(saveBinary(it)) })
        if (saveResult.success) return binaries.associate { snapshotSection(it) to saveResult.output }
        Log.w(TAG, "iptables snapshot unavailable; using compatible rule verification")
        val compatibleResult = executor.executeBatch(compatibleVerifyCommands)
        check(compatibleResult.success) {
            "Root rule verification failed (${compatibleResult.exitCode}): ${compatibleResult.diagnosticOutput}"
        }
        return null
    }

    private fun verifyForbiddenChains(snapshots: Map<String, String>, forbiddenChains: Set<String>) {
        val lines = snapshots.values.asSequence().flatMap { it.lineSequence() }.map(String::trim).toList()
        forbiddenChains.forEach { chain ->
            check(lines.none { line -> line.startsWith(":$chain ") || "-j $chain" in line }) {
                "Root forbidden chain remains after transition: $chain"
            }
        }
    }

    private fun verifyFamily(binary: String, commands: List<List<String>>, snapshot: String) {
        val lines = snapshot.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        commands.mapNotNull { command -> operationValue(command, "-N") }.toSet().forEach { chain ->
            check(lines.any { it.startsWith(":$chain ") }) { "Root chain is missing after restore: $chain" }
        }
        commands.mapNotNull { command -> operationValue(command, "-A") }
            .groupingBy { it }
            .eachCount()
            .forEach { (chain, count) ->
                val actual = lines.count { it.startsWith("-A $chain ") }
                check(actual == count) {
                    "Root chain rule count mismatch after restore: chain=$chain expected=$count actual=$actual"
                }
            }
        commands.filter { operationValue(it, "-I") != null }.forEach { command ->
            val chain = operationValue(command, "-I").orEmpty()
            val target = command.getOrNull(command.indexOf("-j") + 1).orEmpty()
            check(chain.isNotBlank() && target.isNotBlank() && lines.any {
                it.startsWith("-A $chain ") && it.contains("-j $target")
            }) { "Root activation hook is missing after restore: binary=$binary chain=$chain target=$target" }
        }
    }

    private fun verifyPolicyFamily(
        rules: String,
        routes: String,
        mark: String,
        lanes: List<Pair<String, Int>>,
        family: String
    ) {
        check(mark in rules && RootNetfilterPlanner.ROUTE_TABLE in rules) { "$family policy rule is missing" }
        lanes.forEach { (laneMark, priority) ->
            check(laneMark in rules && priority.toString() in rules) { "$family lane policy rule is missing" }
        }
        check(routes.isNotBlank()) { "$family local policy route is missing" }
    }

    private fun operationValue(command: List<String>, operation: String): String? {
        val index = command.indexOf(operation)
        return command.getOrNull(index + 1).takeIf { index >= 0 }
    }

    private fun snapshotSection(binary: String): String =
        if (binary == "iptables") ROOT_STATE_IPTABLES4 else ROOT_STATE_IPTABLES6

    private fun saveBinary(binary: String): String = if (binary == "iptables") "iptables-save" else "ip6tables-save"

    private companion object {
        val NETFILTER_BINARIES = setOf("iptables", "ip6tables")
        const val TAG = "RootNetfilterVerifier"
    }
}
