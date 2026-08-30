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
    private val verifier = RootNetfilterVerifier(executor)

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
        val snapshot = verifier.captureState()
        val rules4 = snapshot.getValue(ROOT_STATE_RULE4)
        val rules6 = snapshot.getValue(ROOT_STATE_RULE6)
        val routes4 = snapshot.getValue(ROOT_STATE_ROUTE4)
        val routes6 = snapshot.getValue(ROOT_STATE_ROUTE6)
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
                checkNoResidualNftState()
                ownership.markLegacyScanCompleted()
            } else if (!ownership.hasCompletedLegacyScan()) {
                if (hasPotentialLegacyState()) {
                    ownership.cleanupLegacyForStartup().getOrThrow()
                    checkNoResidualNftState()
                }
                ownership.markLegacyScanCompleted()
            } else {
                Log.i(
                    TAG,
                    "[ROOT_NET] event=legacy_cleanup_skipped reason=completed_migration_scan " +
                        "staleRuntimePresent=$staleRuntimePresent"
                )
            }
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
        val probes = tableProbeCommands() + listOf(
            listOf("ip", "rule", "show"),
            listOf("ip", "-6", "rule", "show"),
            listOf("ip", "route", "show", "table", "all"),
            listOf("ip", "-6", "route", "show", "table", "all"),
            listOf("/system/bin/sh", "-c", "command -v nft >/dev/null 2>&1 && nft -a list ruleset || true")
        )
        val result = executor.executeBatch(probes)
        if (!result.success) return true
        return trafficAffectingKunBoxNftReferences(result.output).isNotEmpty() ||
            result.output.lineSequence().any { line ->
                isTrafficAffectingKunBoxIptablesReference(line) ||
                    RootNetfilterOwnership.isReservedPolicyLine(line) ||
                    (RootNetfilterPlanner.ROUTE_TABLE in line &&
                        (line.contains("local default") || line.contains("local ::/0")))
            }
    }

    fun apply(config: RootNetfilterConfig): Result<Unit> = runCatching {
        val plan = RootNetfilterPlanner.build(config)
        try {
            persistExpectedOwnership(plan = plan)
            val fastResult = executor.executeFastNetfilterPlan(plan.setupCommands)
            val snapshot = fastResult?.takeIf(RootCommandResult::success)?.output
                ?.let(::parseRootStateSnapshot)
            if (fastResult?.success == true) {
                Log.i(TAG, "Fast netfilter restore applied commands=${plan.setupCommands.size}")
                verifier.verifyRules(plan.setupCommands, plan.verifyCommands, snapshot = snapshot)
            } else {
                if (fastResult != null) {
                    Log.w(
                        TAG,
                        "Fast netfilter restore unavailable or failed (${fastResult.exitCode}); " +
                            "using compatible setup"
                    )
                    cleanup(plan.cleanupCommands)
                    checkNoResidualState()
                } else {
                    Log.w(TAG, "Fast netfilter restore serializer unavailable; using compatible setup")
                }
                executeRequired(plan.setupCommands)
                executeRequired(plan.verifyCommands)
            }
            verifier.verifyPolicyRouting(config, snapshot)
            activePlan = plan
            persistOwnership(active = true, chainSnapshot = snapshot)
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
            val snapshot = fastResult?.takeIf(RootCommandResult::success)?.output
                ?.let(::parseRootStateSnapshot)
            if (fastResult?.success == true) {
                verifier.verifyRules(plan.setupCommands, plan.verifyCommands, snapshot = snapshot)
            } else {
                if (fastResult != null) cleanup(plan.cleanupCommands)
                executeRequired(plan.setupCommands)
                executeRequired(plan.verifyCommands)
            }
            guardPlan = plan
            persistOwnership(active = false, chainSnapshot = snapshot)
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

    fun installGuardAndStage(
        guardConfig: RootFailClosedConfig,
        config: RootNetfilterConfig
    ): Result<RootNetfilterPlan> = runCatching {
        check(guardPlan == null && activePlan == null) { "Root guard or plan is already active" }
        val guard = RootNetfilterPlanner.buildGuard(guardConfig)
        val plan = RootNetfilterPlanner.build(config)
        val setup = guard.setupCommands + plan.stageCommands
        val verify = guard.verifyCommands + plan.verifyCommands.filterNot(::isActivationVerification)
        try {
            persistExpectedOwnership(guard = guard, plan = plan)
            val fastResult = executor.executeFastNetfilterPlan(setup)
            val snapshot = fastResult?.takeIf(RootCommandResult::success)?.output
                ?.let(::parseRootStateSnapshot)
            if (fastResult?.success == true) {
                verifier.verifyRules(setup, verify, snapshot = snapshot)
            } else {
                if (fastResult != null) cleanup(guard.cleanupCommands + plan.cleanupCommands)
                executeRequired(setup)
                executeRequired(verify)
            }
            verifier.verifyPolicyRouting(config, snapshot)
            guardPlan = guard
            activePlan = plan
            persistOwnership(active = false, chainSnapshot = snapshot)
            plan
        } catch (error: Exception) {
            runCatching { cleanup(guard.cleanupCommands + plan.cleanupCommands) }
            guardPlan = null
            activePlan = null
            ownership?.clearStaging()
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
            val snapshot = fastResult?.takeIf(RootCommandResult::success)?.output
                ?.let(::parseRootStateSnapshot)
            val stagedVerifyCommands = plan.verifyCommands.filterNot(::isActivationVerification)
            if (fastResult?.success == true) {
                verifier.verifyRules(plan.stageCommands, stagedVerifyCommands, snapshot = snapshot)
            } else {
                if (fastResult != null) {
                    cleanup(plan.cleanupCommands)
                    checkNoResidualState(allowGuard = guardPlan != null)
                }
                executeRequired(plan.stageCommands)
                executeRequired(stagedVerifyCommands)
            }
            verifier.verifyPolicyRouting(config, snapshot)
            activePlan = plan
            persistOwnership(active = false, chainSnapshot = snapshot)
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

    fun activateAndRemoveGuard(plan: RootNetfilterPlan): Result<Unit> = runCatching {
        val guard = guardPlan ?: error("Root fail-closed guard is unavailable")
        val transitionCommands = plan.activationCommands + guard.cleanupCommands
        val binaries = plan.activationCommands.mapNotNull(List<String>::firstOrNull)
            .filter { it == "iptables" || it == "ip6tables" }
            .distinct()
        val transitionResult = executor.executeBatch(transitionCommands + rootStateSnapshotCommands(binaries))
        val snapshot = transitionResult.takeIf(RootCommandResult::success)?.output
            ?.let(::parseRootStateSnapshot)
        if (!transitionResult.success || snapshot == null) {
            executeRequired(plan.verifyCommands.filter(::isActivationVerification))
            checkGuardAbsent()
        }
        verifier.verifyRules(
            plan.activationCommands,
            plan.verifyCommands.filter(::isActivationVerification),
            snapshot = snapshot,
            forbiddenChains = setOf(
                RootNetfilterPlanner.CHAIN_GUARD4,
                RootNetfilterPlanner.CHAIN_GUARD6
            )
        )
        guardPlan = null
        activePlan = plan
        ownership?.promoteStagingExcludingChains(
            setOf(RootNetfilterPlanner.CHAIN_GUARD4, RootNetfilterPlanner.CHAIN_GUARD6)
        ) ?: persistOwnership(active = true)
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
            .filter { line -> isTrafficAffectingKunBoxIptablesReference(line, allowGuard) }
            .toList()
        val remainingNftHooks = trafficAffectingKunBoxNftReferences(nftRules, allowGuard)
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

    private fun persistOwnership(active: Boolean, chainSnapshot: Map<String, String>? = null) {
        persistOwnership(active, refreshChainFingerprints = true, chainSnapshot = chainSnapshot)
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

    private fun persistOwnership(
        active: Boolean,
        refreshChainFingerprints: Boolean,
        chainSnapshot: Map<String, String>? = null
    ) {
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
        owner.persist(manifest, active, refreshChainFingerprints, chainSnapshot)
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
