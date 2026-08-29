package com.kunk.singbox.service.root

import android.util.Log
import com.kunk.singbox.model.RootRoutingConstants

class RootNetfilterManager internal constructor(
    private val executor: RootCommandExecutor = ProcessRootCommandExecutor(),
    private val ownership: RootNetfilterOwnershipStore? = null
) {
    companion object {
        private const val TAG = "RootNetfilterManager"
        private const val MAX_CLEANUP_REPEATS = 32
    }

    private var activePlan: RootNetfilterPlan? = null
    private var guardPlan: RootGuardPlan? = null
    private var ownershipContext: RootNetfilterOwnerContext? = null

    internal fun beginOwnership(context: RootNetfilterOwnerContext): Result<Unit> = runCatching {
        ownershipContext = context
    }

    fun hasGuard(): Boolean = guardPlan != null

    internal fun rebindOwnership(context: RootNetfilterOwnerContext): Result<Unit> = runCatching {
        ownershipContext = context
        persistOwnership(active = false)
    }

    fun checkReservedStateAvailable(): Result<Unit> = runCatching {
        val currentOwner = ownership?.readAnyOwner()
        val expectedSession = ownershipContext?.sessionId
        if (currentOwner != null && currentOwner.context.sessionId != expectedSession) {
            error("Reserved Root policy routing is owned by another session")
        }
        val rules4 = executeRequiredResult(listOf("ip", "rule", "show")).output
        val rules6 = executeRequiredResult(listOf("ip", "-6", "rule", "show")).output
        val routes4 = executeRequiredResult(
            listOf("ip", "route", "show", "table", RootNetfilterPlanner.ROUTE_TABLE)
        ).output
        val routes6 = executeRequiredResult(
            listOf("ip", "-6", "route", "show", "table", RootNetfilterPlanner.ROUTE_TABLE)
        ).output
        checkNoForeignReservedPolicy(rules4, ipv6 = false)
        checkNoForeignReservedPolicy(rules6, ipv6 = true)
        checkNoForeignReservedRoute(routes4, ipv6 = false)
        checkNoForeignReservedRoute(routes6, ipv6 = true)
    }

    fun prepareForStart(staleRuntimePresent: Boolean): Result<Unit> = runCatching {
        if (staleRuntimePresent) Log.i(TAG, "Cleaning stale Root runtime before start")
        if (ownership != null) {
            if (ownership.hasOwner()) {
                ownership.cleanupAnyOwnerForStartup().getOrThrow()
            } else if (hasPotentialLegacyState()) {
                ownership.cleanupLegacyForStartup().getOrThrow()
            } else {
                Log.i(
                    TAG,
                    "[ROOT_NET] event=legacy_cleanup_skipped reason=no_harmful_owned_state " +
                        "staleRuntimePresent=$staleRuntimePresent"
                )
            }
            checkNoResidualNftState()
        } else {
            cleanup(RootNetfilterPlanner.cleanupCommands())
            checkNoResidualState()
        }
        activePlan = null
        guardPlan = null
    }

    /**
     * Cheap preflight used only when no ownership manifest exists. A full legacy
     * cleanup scans every table and policy rule, which is unnecessary on the
     * normal clean-start path and can block Root startup for tens of seconds.
     */
    private fun hasPotentialLegacyState(): Boolean {
        val tableState = tableProbeCommands().any { command ->
            val result = executor.execute(command)
            !result.success || result.output.lineSequence().any(::isTrafficAffectingKunBoxIptablesReference)
        }
        if (tableState) return true
        val policyState = listOf(
            listOf("ip", "rule", "show"),
            listOf("ip", "-6", "rule", "show"),
            listOf("ip", "route", "show", "table", RootNetfilterPlanner.ROUTE_TABLE),
            listOf("ip", "-6", "route", "show", "table", RootNetfilterPlanner.ROUTE_TABLE)
        ).any { command ->
            val result = executor.execute(command)
            !result.success ||
                result.output.lineSequence().any { line ->
                    RootNetfilterOwnership.isReservedPolicyLine(line) ||
                        "KBX_" in line ||
                        (RootNetfilterPlanner.ROUTE_TABLE in line &&
                            (line.contains("local default") || line.contains("local ::/0")))
                }
        }
        return policyState
    }

    fun apply(config: RootNetfilterConfig): Result<Unit> = runCatching {
        val plan = RootNetfilterPlanner.build(config)
        try {
            persistExpectedOwnership(plan = plan)
            val fastResult = executor.executeFastNetfilterPlan(plan.setupCommands)
            if (fastResult?.success == true) {
                Log.i(TAG, "Fast netfilter restore applied")
            } else {
                if (fastResult != null) {
                    Log.w(
                        TAG,
                        "Fast netfilter restore unavailable or failed (${fastResult.exitCode}); " +
                            "using compatible setup"
                    )
                    cleanup(plan.cleanupCommands)
                    checkNoResidualState()
                }
                executeRequired(plan.setupCommands)
            }
            executeRequired(plan.verifyCommands)
            verifyPolicyRouting(config)
            activePlan = plan
            persistOwnership(active = true)
        } catch (error: Exception) {
            cleanup(plan.cleanupCommands)
            activePlan = null
            val cleanupError = runCatching { checkNoResidualState() }.exceptionOrNull()
            if (cleanupError != null) {
                throw IllegalStateException(
                    "KunBox cleanup verification failed: ${cleanupError.message.orEmpty()}",
                    cleanupError
                )
            }
            throw error
        }
    }

    fun installGuard(config: RootFailClosedConfig): Result<Unit> = runCatching {
        val previousGuard = guardPlan
        previousGuard?.let {
            cleanup(it.cleanupCommands)
            checkGuardAbsent()
            guardPlan = null
            persistOwnership(active = true)
        }
        val plan = RootNetfilterPlanner.buildGuard(config)
        try {
            persistExpectedOwnership(guard = plan)
            val fastResult = executor.executeFastNetfilterPlan(plan.setupCommands)
            if (fastResult?.success != true) {
                if (fastResult != null) cleanup(plan.cleanupCommands)
                executeRequired(plan.setupCommands)
            }
            executeRequired(plan.verifyCommands)
            guardPlan = plan
            persistOwnership(active = false)
        } catch (error: Exception) {
            runCatching { cleanup(plan.cleanupCommands) }
            guardPlan = null
            if (previousGuard == null && activePlan == null) {
                ownership?.clearStaging()
            } else {
                runCatching { persistOwnership(active = previousGuard == null) }
            }
            throw error
        }
    }

    fun removeGuard(): Result<Unit> = runCatching {
        val plan = guardPlan ?: return@runCatching
        cleanup(plan.cleanupCommands)
        checkGuardAbsent()
        guardPlan = null
        persistOwnership(active = true)
    }

    fun stage(config: RootNetfilterConfig): Result<RootNetfilterPlan> = runCatching {
        val plan = RootNetfilterPlanner.build(config)
        try {
            persistExpectedOwnership(plan = plan)
            val fastResult = executor.executeFastNetfilterPlan(plan.stageCommands)
            if (fastResult?.success != true) {
                if (fastResult != null) {
                    cleanup(plan.cleanupCommands)
                    checkNoResidualState(allowGuard = guardPlan != null)
                }
                executeRequired(plan.stageCommands)
            }
            executeRequired(plan.verifyCommands.filterNot(::isActivationVerification))
            verifyPolicyRouting(config)
            activePlan = plan
            persistOwnership(active = false)
            plan
        } catch (error: Exception) {
            runCatching { cleanup(plan.cleanupCommands) }
            activePlan = null
            if (guardPlan != null) {
                runCatching { persistOwnership(active = false) }
            } else {
                ownership?.clearStaging()
            }
            throw error
        }
    }

    fun activate(plan: RootNetfilterPlan): Result<Unit> = runCatching {
        executeRequired(plan.activationCommands)
        executeRequired(plan.verifyCommands.filter(::isActivationVerification))
        activePlan = plan
        persistOwnership(active = true)
    }

    fun discard(plan: RootNetfilterPlan): Result<Unit> = runCatching {
        cleanup(plan.cleanupCommands)
        checkNoResidualState()
        if (activePlan === plan) activePlan = null
        persistOwnership(active = false)
    }

    fun cleanup(): Result<Unit> = runCatching {
        if (ownership != null) {
            val hadPlan = activePlan != null || guardPlan != null
            if (ownership.hasOwner()) {
                ownership.cleanupAnyOwner().getOrThrow()
            } else {
                ownership.cleanupLegacy().getOrThrow()
            }
            checkNoResidualNftState()
            check(!hadPlan || !ownership.hasOwner()) {
                "Root ownership record disappeared before cleanup confirmation"
            }
        } else {
            guardPlan?.let { cleanup(it.cleanupCommands) }
            cleanup(activePlan?.cleanupCommands ?: RootNetfilterPlanner.cleanupCommands())
            checkNoResidualState()
        }
        activePlan = null
        guardPlan = null
        ownershipContext = null
    }

    fun cleanupActivePlanKeepingGuard(): Result<Unit> = runCatching {
        val plan = activePlan ?: return@runCatching
        cleanup(plan.cleanupCommands)
        checkNoResidualState(allowGuard = true)
        activePlan = null
        persistOwnership(active = false)
    }

    private fun cleanup(commands: List<List<String>>) {
        val repeatable = commands.indices.filterTo(mutableSetOf()) { isRepeatableCleanup(commands[it]) }
        executor.executeBestEffortBatch(commands, repeatable, MAX_CLEANUP_REPEATS)
    }

    private fun isRepeatableCleanup(command: List<String>): Boolean =
        "-D" in command || ("rule" in command && "del" in command)

    private fun isActivationVerification(command: List<String>): Boolean {
        val checkIndex = command.indexOf("-C")
        val chain = command.getOrNull(checkIndex + 1)
        return checkIndex >= 0 && chain in setOf("OUTPUT", "PREROUTING", "INPUT")
    }

    private fun executeRequired(commands: List<List<String>>) {
        if (commands.isEmpty()) return
        val result = executor.executeBatch(commands)
        check(result.success) {
            val category = if (isXtablesLockContention(result)) "xtables_lock_timeout" else "command_failed"
            "Root $category (${result.exitCode}): ${result.diagnosticOutput}"
        }
    }

    @Suppress("LongMethod")
    private fun checkNoResidualState(allowGuard: Boolean = false) {
        val tableRules = buildString {
            append(executeRequiredBatchResult(tableProbeCommands()).output)
            append('\n')
            append(optionalIpv6NatRules())
        }
        val policyState = executeRequiredBatchResult(
            listOf(
                listOf("ip", "rule", "show"),
                listOf("ip", "-6", "rule", "show"),
                listOf("ip", "route", "show", "table", "all"),
                listOf("ip", "-6", "route", "show", "table", "all")
            )
        ).output
        val nftRules = optionalNftRules()
        val chainNames = buildList {
            addAll(listOf(
                RootNetfilterPlanner.CHAIN_OUT4,
                RootNetfilterPlanner.CHAIN_PRE4,
                RootNetfilterPlanner.CHAIN_IN4,
                RootNetfilterPlanner.CHAIN_RED4,
                RootNetfilterPlanner.CHAIN_OUT6,
                RootNetfilterPlanner.CHAIN_PRE6,
                RootNetfilterPlanner.CHAIN_IN6,
                RootNetfilterPlanner.CHAIN_RED6,
                RootNetfilterPlanner.CHAIN_BLOCK4,
                RootNetfilterPlanner.CHAIN_BLOCK6,
                RootNetfilterPlanner.CHAIN_QUIC4,
                RootNetfilterPlanner.CHAIN_QUIC6
            ))
            if (!allowGuard) {
                add(RootNetfilterPlanner.CHAIN_GUARD4)
                add(RootNetfilterPlanner.CHAIN_GUARD6)
            }
        }
        val remainingHooks = tableRules.lineSequence()
            .map(String::trim)
            .filter(::isTrafficAffectingKunBoxIptablesReference)
            .toList()
        val remainingNftHooks = trafficAffectingKunBoxNftReferences(nftRules)
        val remainingMetadata = (tableRules.lineSequence() + nftRules.lineSequence())
            .map(String::trim)
            .filter { line ->
                chainNames.any(line::contains) &&
                    line !in remainingHooks &&
                    remainingNftHooks.none { reference -> reference.endsWith("rule=$line") }
            }
            .toList()
        val remainingPolicy = policyState.lineSequence()
            .filter { line ->
                RootNetfilterOwnership.isReservedPolicyLine(line) ||
                    (line.contains("table ${RootNetfilterPlanner.ROUTE_TABLE}") &&
                        (line.trimStart().startsWith("local ") || line.trimStart().startsWith("default ")))
            }
            .toList()
        if (remainingMetadata.isNotEmpty()) {
            Log.w(
                TAG,
                "[ROOT_NET] event=harmless_metadata_remaining lines=${remainingMetadata.joinToString(";")}"
            )
        }
        check(remainingHooks.isEmpty() && remainingNftHooks.isEmpty() && remainingPolicy.isEmpty()) {
            "Harmful KunBox network state remains after cleanup: " +
                "hooks=${remainingHooks.joinToString(";")} nft=${remainingNftHooks.joinToString(";")} " +
                "policy=${remainingPolicy.joinToString(";")}"
        }
    }

    private fun checkNoResidualNftState() {
        val remaining = trafficAffectingKunBoxNftReferences(optionalNftRules())
        check(remaining.isEmpty()) {
            "Harmful KunBox nft state remains after cleanup: ${remaining.joinToString(";")}"
        }
    }

    private fun checkGuardAbsent() {
        val output = executeRequiredBatchResult(
            listOf(
                listOf("iptables", "-t", "filter", "-S"),
                listOf("ip6tables", "-t", "filter", "-S")
            )
        ).output
        check(RootNetfilterPlanner.CHAIN_GUARD4 !in output && RootNetfilterPlanner.CHAIN_GUARD6 !in output) {
            "KunBox fail-closed guard remains after cleanup"
        }
    }

    private fun verifyPolicyRouting(config: RootNetfilterConfig) {
        if (config.proxyIpv4) {
            val rules = executeRequiredResult(listOf("ip", "rule", "show")).output
            val routes = executeRequiredResult(
                listOf("ip", "route", "show", "table", RootNetfilterPlanner.ROUTE_TABLE)
            ).output
            check(RootNetfilterPlanner.IPV4_MARK in rules && RootNetfilterPlanner.ROUTE_TABLE in rules)
            config.lanes.forEach { lane ->
                check(rootMark(lane.markIpv4) in rules && lane.priorityIpv4.toString() in rules) {
                    "IPv4 lane policy rule is missing: ${lane.laneId}"
                }
            }
            check(routes.isNotBlank()) { "IPv4 local policy route is missing" }
        }
        if (config.proxyIpv6) {
            val rules = executeRequiredResult(listOf("ip", "-6", "rule", "show")).output
            val routes = executeRequiredResult(
                listOf("ip", "-6", "route", "show", "table", RootNetfilterPlanner.ROUTE_TABLE)
            ).output
            check(RootNetfilterPlanner.IPV6_MARK in rules && RootNetfilterPlanner.ROUTE_TABLE in rules)
            config.lanes.forEach { lane ->
                check(rootMark(lane.markIpv6) in rules && lane.priorityIpv6.toString() in rules) {
                    "IPv6 lane policy rule is missing: ${lane.laneId}"
                }
            }
            check(routes.isNotBlank()) { "IPv6 local policy route is missing" }
        }
    }

    private fun executeRequiredResult(command: List<String>): RootCommandResult =
        executor.execute(command).also { result ->
            check(result.success) {
                val category = if (isXtablesLockContention(result)) {
                    "NETFILTER_VERIFICATION_FAILED query_failed=xtables_lock"
                } else {
                    "probe_failed"
                }
                "Root $category (${result.exitCode}): ${command.joinToString(" ")} ${result.diagnosticOutput}"
            }
        }

    private fun executeRequiredBatchResult(commands: List<List<String>>): RootCommandResult =
        executor.executeBatch(commands).also { result ->
            check(result.success) {
                val category = if (isXtablesLockContention(result)) {
                    "NETFILTER_VERIFICATION_FAILED query_failed=xtables_lock"
                } else {
                    "probe_batch_failed"
                }
                "Root $category (${result.exitCode}): ${result.diagnosticOutput}"
            }
        }

    private fun optionalIpv6NatRules(): String {
        val command = listOf("ip6tables", "-t", "nat", "-S")
        val result = executeOptional(command)
        if (result.success) return result.output
        val saveResult = executeXtablesWithRetry(
            executeOnce = { executeOptional(listOf("ip6tables-save")) }
        )
        if (saveResult.success) {
            return runCatching { extractIptablesSaveTable(saveResult.output, "nat").orEmpty() }
                .getOrElse { failure ->
                    error("Root NETFILTER_VERIFICATION_FAILED: ${failure.message.orEmpty()}")
                }
        }
        error(
            "Root NETFILTER_VERIFICATION_FAILED: primary=${command.joinToString(" ")} " +
                "exitCode=${result.exitCode} ${result.diagnosticOutput}; " +
                "fallback=ip6tables-save exitCode=${saveResult.exitCode} ${saveResult.diagnosticOutput}"
        )
    }

    private fun executeOptional(command: List<String>): RootCommandResult = runCatching {
        executor.execute(command)
    }.getOrElse { error ->
        RootCommandResult(127, "", error.message.orEmpty())
    }

    private fun optionalNftRules(): String = runCatching {
        executor.execute(listOf("nft", "-a", "list", "ruleset"))
    }.getOrNull()?.takeIf(RootCommandResult::success)?.output.orEmpty()

    private fun tableProbeCommands(): List<List<String>> = listOf(
        listOf("iptables", "-t", "mangle", "-S"),
        listOf("iptables", "-t", "filter", "-S"),
        listOf("iptables", "-t", "nat", "-S"),
        listOf("ip6tables", "-t", "mangle", "-S"),
        listOf("ip6tables", "-t", "filter", "-S")
    )

    private fun persistOwnership(active: Boolean) {
        persistOwnership(active, refreshChainFingerprints = true)
    }

    private fun persistExpectedOwnership(
        plan: RootNetfilterPlan? = null,
        guard: RootGuardPlan? = null
    ) {
        val owner = ownership ?: return
        val context = ownershipContext ?: return
        val commands = buildList {
            guard?.setupCommands?.let(::addAll)
            guardPlan?.setupCommands?.let(::addAll)
            activePlan?.setupCommands?.let(::addAll)
            plan?.setupCommands?.let(::addAll)
        }
        if (commands.isEmpty()) {
            owner.clearStaging()
            return
        }
        owner.persist(
            RootNetfilterOwnership.fromCommands(context, commands),
            active = false,
            refreshChainFingerprints = false
        )
    }

    private fun persistOwnership(active: Boolean, refreshChainFingerprints: Boolean) {
        val owner = ownership ?: return
        val context = ownershipContext ?: return
        val commands = buildList {
            guardPlan?.setupCommands?.let(::addAll)
            activePlan?.setupCommands?.let(::addAll)
        }
        if (commands.isEmpty()) {
            owner.clearStaging()
            return
        }
        val manifest = RootNetfilterOwnership.fromCommands(context, commands)
        owner.persist(manifest, active, refreshChainFingerprints)
    }

    private fun checkNoForeignReservedPolicy(output: String, ipv6: Boolean) {
        val tuples = buildList {
            add(
                if (ipv6) {
                    RootRoutingConstants.GENERIC_MARK_IPV6 to RootRoutingConstants.GENERIC_PRIORITY_IPV6
                } else {
                    RootRoutingConstants.GENERIC_MARK_IPV4 to RootRoutingConstants.GENERIC_PRIORITY_IPV4
                }
            )
            repeat(RootRoutingConstants.MAX_LANES) { slot ->
                add(
                    if (ipv6) {
                        RootRoutingConstants.markIpv6(slot) to RootRoutingConstants.priorityIpv6(slot)
                    } else {
                        RootRoutingConstants.markIpv4(slot) to RootRoutingConstants.priorityIpv4(slot)
                    }
                )
            }
        }
        output.lineSequence().filter(String::isNotBlank).forEach { line ->
            val conflict = tuples.any { (mark, priority) ->
                line.trimStart().startsWith("$priority:") ||
                    line.contains("fwmark ${rootMark(mark)}/0xffffffff") ||
                    line.contains("lookup ${RootRoutingConstants.ROUTE_TABLE}")
            }
            check(!conflict) { "Reserved Root policy routing conflict: $line" }
        }
    }

    private fun checkNoForeignReservedRoute(output: String, ipv6: Boolean) {
        val prefix = if (ipv6) "::/0" else "0.0.0.0/0"
        output.lineSequence().filter(String::isNotBlank).forEach { line ->
            check(!line.contains("local $prefix") && !line.contains("table ${RootRoutingConstants.ROUTE_TABLE}")) {
                "Reserved Root policy route conflict: $line"
            }
        }
    }
}
