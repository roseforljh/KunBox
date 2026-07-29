package com.kunk.singbox.service

import com.kunk.singbox.service.manager.UrlTestTagMatcher

/**
 * 自动切换 live 终验。
 * 只看运行态：选中是否正确、观察窗内远程 DNS 是否继续炸。
 * 离线延迟不可靠，不再作为 commit 硬条件。
 * @return null 表示通过，否则返回失败原因。
 */
internal fun evaluateAutoFailoverLiveCheck(
    targetTag: String,
    selectedTag: String?,
    targetProbeSucceeded: Boolean,
    recentRemoteDnsFailures: Int,
    maxAllowedDnsFailures: Int = 1
): String? {
    val selectedOk = !selectedTag.isNullOrBlank() &&
        UrlTestTagMatcher.normalizeTag(targetTag) == UrlTestTagMatcher.normalizeTag(selectedTag)
    return when {
        !selectedOk -> "selected_mismatch"
        !targetProbeSucceeded -> "target_https_probe_failed"
        recentRemoteDnsFailures > maxAllowedDnsFailures -> "live_remote_dns_timeout"
        else -> null
    }
}
