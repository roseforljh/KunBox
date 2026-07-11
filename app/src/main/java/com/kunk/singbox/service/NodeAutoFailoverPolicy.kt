package com.kunk.singbox.service

import com.kunk.singbox.service.manager.UrlTestTagMatcher

object NodeAutoFailoverPolicy {

    internal const val RECENT_TRAFFIC_WINDOW_MS = 30_000L
    internal const val AUTO_FAILOVER_COOLDOWN_MS = 60_000L
    internal const val AUTO_FAILOVER_BUDGET_WINDOW_MS = 10 * 60_000L
    internal const val AUTO_FAILOVER_BUDGET_MAX_COUNT = 3
    internal const val NODE_QUARANTINE_MS = 10 * 60_000L

    internal data class TriggerContext(
        val isVpnRunning: Boolean,
        val isManuallyStopped: Boolean,
        val isAutoFailoverInFlight: Boolean,
        val inStartupGracePeriod: Boolean,
        val inNetworkChangeGracePeriod: Boolean,
        val isProxyIdle: Boolean,
        val lastMeaningfulTrafficAtMs: Long,
        val nowAtMs: Long,
        val lastAutoFailoverAtMs: Long,
        val budgetWindowStartAtMs: Long,
        val budgetCount: Int
    )

    enum class ProbeOutcome {
        CURRENT_HEALTHY,
        CURRENT_FAILED_WITH_ALTERNATIVE,
        NETWORK_FAILURE,
        NO_CURRENT_SELECTION,
        NO_RESULTS
    }

    data class ProbeEvaluation(
        val outcome: ProbeOutcome,
        val currentTag: String? = null,
        val currentDelayMs: Int? = null,
        val alternativeTag: String? = null,
        val alternativeDelayMs: Int? = null
    )

    internal data class BudgetState(
        val windowStartAtMs: Long,
        val count: Int
    )

    data class QuarantinedNode(
        val tag: String,
        val expiresAtMs: Long
    )

    internal fun shouldStartProbe(context: TriggerContext): Boolean {
        return shouldStartProbe(context, trigger = "")
    }

    internal fun shouldStartProbe(context: TriggerContext, trigger: String): Boolean {
        if (!context.isVpnRunning || context.isManuallyStopped) {
            return false
        }
        if (context.isAutoFailoverInFlight) {
            return false
        }
        if (context.inStartupGracePeriod || context.inNetworkChangeGracePeriod || context.isProxyIdle) {
            return false
        }
        if (!hasRecentMeaningfulTraffic(context.lastMeaningfulTrafficAtMs, context.nowAtMs)) {
            return false
        }
        val budgetExhausted = isBudgetExhausted(
            windowStartAtMs = context.budgetWindowStartAtMs,
            count = context.budgetCount,
            nowAtMs = context.nowAtMs
        )
        if (budgetExhausted) {
            return false
        }

        if (!isHealthFastPathTrigger(trigger) && isCooldownActive(context.lastAutoFailoverAtMs, context.nowAtMs)) {
            return false
        }
        return true
    }

    internal fun isHealthFastPathTrigger(trigger: String): Boolean {
        return trigger.startsWith("dns_remote_timeout") || trigger.startsWith("active_probe_failed")
    }

    internal fun hasRecentMeaningfulTraffic(
        lastMeaningfulTrafficAtMs: Long,
        nowAtMs: Long,
        recentTrafficWindowMs: Long = RECENT_TRAFFIC_WINDOW_MS
    ): Boolean {
        if (lastMeaningfulTrafficAtMs <= 0L || nowAtMs < lastMeaningfulTrafficAtMs) {
            return false
        }
        return nowAtMs - lastMeaningfulTrafficAtMs <= recentTrafficWindowMs
    }

    internal fun isCooldownActive(
        lastAutoFailoverAtMs: Long,
        nowAtMs: Long,
        cooldownMs: Long = AUTO_FAILOVER_COOLDOWN_MS
    ): Boolean {
        if (lastAutoFailoverAtMs <= 0L || nowAtMs < lastAutoFailoverAtMs) {
            return false
        }
        return nowAtMs - lastAutoFailoverAtMs < cooldownMs
    }

    internal fun isBudgetExhausted(
        windowStartAtMs: Long,
        count: Int,
        nowAtMs: Long,
        budgetWindowMs: Long = AUTO_FAILOVER_BUDGET_WINDOW_MS,
        budgetMaxCount: Int = AUTO_FAILOVER_BUDGET_MAX_COUNT
    ): Boolean {
        if (count < budgetMaxCount || windowStartAtMs <= 0L || nowAtMs < windowStartAtMs) {
            return false
        }
        return nowAtMs - windowStartAtMs < budgetWindowMs
    }

    internal fun registerFailoverAttempt(
        windowStartAtMs: Long,
        count: Int,
        nowAtMs: Long,
        budgetWindowMs: Long = AUTO_FAILOVER_BUDGET_WINDOW_MS
    ): BudgetState {
        if (windowStartAtMs <= 0L || nowAtMs < windowStartAtMs || nowAtMs - windowStartAtMs >= budgetWindowMs) {
            return BudgetState(windowStartAtMs = nowAtMs, count = 1)
        }
        return BudgetState(windowStartAtMs = windowStartAtMs, count = count + 1)
    }

    internal fun evaluateProbe(
        currentTag: String?,
        urlTestResults: Map<String, Int>,
        quarantinedTags: Set<String> = emptySet()
    ): ProbeEvaluation {
        val normalizedCurrentTag = currentTag?.trim().orEmpty()
        val alternative = urlTestResults.asSequence()
            .map { (tag, delay) -> tag.trim() to delay }
            .filter { (tag, delay) ->
                tag.isNotBlank() &&
                    delay > 0 &&
                    UrlTestTagMatcher.normalizeTag(tag) != UrlTestTagMatcher.normalizeTag(normalizedCurrentTag) &&
                    quarantinedTags.none { quarantined ->
                        UrlTestTagMatcher.normalizeTag(quarantined) == UrlTestTagMatcher.normalizeTag(tag)
                    }
            }
            .minByOrNull { (_, delay) -> delay }
        val currentDelay = resolvePositiveDelay(normalizedCurrentTag, urlTestResults)

        return when {
            normalizedCurrentTag.isBlank() -> ProbeEvaluation(outcome = ProbeOutcome.NO_CURRENT_SELECTION)
            urlTestResults.isEmpty() -> ProbeEvaluation(
                outcome = ProbeOutcome.NO_RESULTS,
                currentTag = normalizedCurrentTag
            )
            currentDelay != null -> ProbeEvaluation(
                outcome = ProbeOutcome.CURRENT_HEALTHY,
                currentTag = normalizedCurrentTag,
                currentDelayMs = currentDelay
            )
            alternative == null -> ProbeEvaluation(
                outcome = ProbeOutcome.NETWORK_FAILURE,
                currentTag = normalizedCurrentTag
            )
            else -> ProbeEvaluation(
                outcome = ProbeOutcome.CURRENT_FAILED_WITH_ALTERNATIVE,
                currentTag = normalizedCurrentTag,
                alternativeTag = alternative.first,
                alternativeDelayMs = alternative.second
            )
        }
    }

    internal fun createQuarantineRecord(
        tag: String,
        nowAtMs: Long,
        quarantineMs: Long = NODE_QUARANTINE_MS
    ): QuarantinedNode {
        return QuarantinedNode(tag = tag.trim(), expiresAtMs = nowAtMs + quarantineMs)
    }

    internal fun cleanupExpiredQuarantine(
        records: List<QuarantinedNode>,
        nowAtMs: Long
    ): List<QuarantinedNode> {
        return records
            .filter { it.tag.isNotBlank() && it.expiresAtMs > nowAtMs }
            .distinctBy { UrlTestTagMatcher.normalizeTag(it.tag) }
    }

    internal fun encodeQuarantine(records: List<QuarantinedNode>): String {
        return records.joinToString(separator = ";") { "${it.tag}|${it.expiresAtMs}" }
    }

    internal fun decodeQuarantine(raw: String?): List<QuarantinedNode> {
        return raw
            .orEmpty()
            .split(';')
            .mapNotNull { entry ->
                val tag = entry.substringBefore('|').trim()
                val expiresAtMs = entry.substringAfter('|', missingDelimiterValue = "").trim().toLongOrNull()
                if (tag.isBlank() || expiresAtMs == null) {
                    null
                } else {
                    QuarantinedNode(tag = tag, expiresAtMs = expiresAtMs)
                }
            }
    }

    private fun resolvePositiveDelay(queryTag: String, results: Map<String, Int>): Int? {
        return UrlTestTagMatcher.resolveDelayDetail(results, queryTag)
            ?.delay
            ?.takeIf { it > 0 }
    }
}
