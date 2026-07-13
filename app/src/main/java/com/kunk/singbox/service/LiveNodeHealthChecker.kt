package com.kunk.singbox.service

import com.kunk.singbox.service.manager.UrlTestTagMatcher

/**
 * 自动切换 live 终验。
 * @return null 表示通过，否则返回失败原因。
 */
internal fun evaluateAutoFailoverLiveCheck(
    targetTag: String,
    selectedTag: String?,
    offlineDelayMs: Long?,
    recentRemoteDnsFailures: Int,
    maxAllowedDnsFailures: Int = 1
): String? {
    val selectedOk = !selectedTag.isNullOrBlank() &&
        UrlTestTagMatcher.normalizeTag(targetTag) == UrlTestTagMatcher.normalizeTag(selectedTag)
    if (!selectedOk) return "selected_mismatch"
    if (offlineDelayMs == null || offlineDelayMs <= 0L) return "offline_delay_failed"
    if (recentRemoteDnsFailures > maxAllowedDnsFailures) return "live_remote_dns_timeout"
    return null
}
