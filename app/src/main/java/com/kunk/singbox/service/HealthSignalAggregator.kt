package com.kunk.singbox.service

import java.util.ArrayDeque

internal enum class HealthSignalKind {
    REMOTE_DNS_TIMEOUT,
    ACTIVE_PROBE_FAILED
}

internal data class HealthSignal(
    val kind: HealthSignalKind,
    val dnsServerTag: String? = null,
    val domains: Set<String> = emptySet(),
    val failureCount: Int = 0,
    val firstAtMs: Long,
    val lastAtMs: Long
)

internal class HealthSignalAggregator(
    private val dnsWindowMs: Long = 7_000L,
    private val minDnsFailures: Int = 3,
    private val minDnsQueryIds: Int = 2,
    private val dnsRouteBindingTtlMs: Long = 60_000L
) {
    private data class DnsRouteBinding(
        val dnsServerTag: String,
        val atMs: Long
    )

    private data class DnsFailure(
        val queryId: String,
        val domain: String,
        val dnsServerTag: String,
        val atMs: Long
    )

    private val queryDnsServer = mutableMapOf<String, DnsRouteBinding>()
    private val dnsFailures = ArrayDeque<DnsFailure>()
    // live 验收独立计数：不被 emit 信号时的 removeAll 消费
    private val observedDnsFailureAtMs = ArrayDeque<Long>()

    @Synchronized
    fun observeKernelLog(line: String, nowMs: Long): HealthSignal? {
        val normalizedLine = stripAnsi(line)
        trimRouteBindings(nowMs)
        observeDnsRoute(normalizedLine, nowMs)
        val failure = parseDnsFailure(normalizedLine, nowMs) ?: return null
        dnsFailures.addLast(failure)
        observedDnsFailureAtMs.addLast(nowMs)
        trimFailures(nowMs)
        trimObservedFailures(nowMs)
        return resolveDnsSignal(nowMs)
    }

    /** live 验收观察窗：统计最近远程 DNS 超时次数，不消费失败队列。 */
    @Synchronized
    fun recentRemoteDnsFailureCount(nowMs: Long, windowMs: Long = dnsWindowMs): Int {
        trimObservedFailures(nowMs, windowMs)
        return observedDnsFailureAtMs.size
    }

    /** 切换后开始 live 观察前清空，避免把旧超时算到新节点头上。 */
    @Synchronized
    fun clearDnsFailures() {
        dnsFailures.clear()
        observedDnsFailureAtMs.clear()
    }

    private fun trimObservedFailures(nowMs: Long, windowMs: Long = dnsWindowMs) {
        while (observedDnsFailureAtMs.isNotEmpty() && nowMs - observedDnsFailureAtMs.first > windowMs) {
            observedDnsFailureAtMs.removeFirst()
        }
    }

    private fun observeDnsRoute(line: String, nowMs: Long) {
        val queryId = QUERY_ID_REGEX.find(line)?.groupValues?.getOrNull(1) ?: return
        val dnsTag = DNS_ROUTE_REGEX.find(line)?.groupValues?.getOrNull(1) ?: return
        queryDnsServer[queryId] = DnsRouteBinding(dnsServerTag = dnsTag, atMs = nowMs)
    }

    private fun parseDnsFailure(line: String, nowMs: Long): DnsFailure? {
        val match = DNS_TIMEOUT_REGEX.find(line) ?: return null
        val queryId = QUERY_ID_REGEX.find(line)?.groupValues?.getOrNull(1) ?: return null
        val dnsTag = queryDnsServer[queryId]?.dnsServerTag ?: return null
        val domain = match.groupValues[1].trimEnd('.')
        return DnsFailure(
            queryId = queryId,
            domain = domain,
            dnsServerTag = dnsTag,
            atMs = nowMs
        )
    }

    private fun trimFailures(nowMs: Long) {
        while (dnsFailures.isNotEmpty() && nowMs - dnsFailures.first.atMs > dnsWindowMs) {
            dnsFailures.removeFirst()
        }
    }

    private fun trimRouteBindings(nowMs: Long) {
        queryDnsServer.entries.removeAll { (_, binding) -> nowMs - binding.atMs > dnsRouteBindingTtlMs }
    }

    private fun resolveDnsSignal(nowMs: Long): HealthSignal? {
        val group = dnsFailures
            .groupBy { it.dnsServerTag }
            .values
            .firstOrNull { failures ->
                failures.size >= minDnsFailures &&
                    failures.map { it.queryId }.distinct().size >= minDnsQueryIds
            }
            ?: return null

        dnsFailures.removeAll(group.toSet())
        return HealthSignal(
            kind = HealthSignalKind.REMOTE_DNS_TIMEOUT,
            dnsServerTag = group.first().dnsServerTag,
            domains = group.map { it.domain }.toSet(),
            failureCount = group.size,
            firstAtMs = group.minOf { it.atMs },
            lastAtMs = nowMs
        )
    }

    private fun stripAnsi(line: String): String {
        return ANSI_ESCAPE_REGEX.replace(line, "")
    }

    companion object {
        private val ANSI_ESCAPE_REGEX = Regex("\u001B\\[[;?0-9]*[ -/]*[@-~]")
        private val QUERY_ID_REGEX = Regex("""\[(\d+)\s+[^\]]+]""")
        private val DNS_ROUTE_REGEX = Regex("""route\((dns-remote-[^)]+)\)""")
        private val DNS_TIMEOUT_REGEX = Regex(
            """dns:\s+exchange failed for\s+(.+?)\.\s+IN\s+(?:A|AAAA):\s+context deadline exceeded""",
            RegexOption.IGNORE_CASE
        )

        fun buildSummary(signal: HealthSignal): String {
            val domains = signal.domains.joinToString(separator = ",").ifBlank { "(none)" }
            val dns = signal.dnsServerTag ?: "(unknown)"
            val windowMs = (signal.lastAtMs - signal.firstAtMs).coerceAtLeast(0L)
            return "WARN: Remote DNS timeout detected domains=$domains dns=$dns " +
                "dns_channel=remote diagnosis=remote_dns_timeout " +
                "failures=${signal.failureCount} window=${windowMs}ms"
        }
    }
}
