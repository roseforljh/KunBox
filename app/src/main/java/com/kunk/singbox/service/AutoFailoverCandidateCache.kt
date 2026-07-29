package com.kunk.singbox.service

import com.kunk.singbox.service.manager.UrlTestTagMatcher

internal class AutoFailoverCandidateCache(
    private val maxAgeMs: Long = 60_000L
) {
    private data class Entry(
        val tag: String,
        val updatedAtMs: Long
    )

    @Volatile
    private var entry: Entry? = null

    @Synchronized
    fun update(
        currentTag: String,
        delays: Map<String, Int>,
        nowMs: Long,
        quarantinedTags: Set<String> = emptySet()
    ) {
        val candidate = delays
            .asSequence()
            .map { (tag, delay) -> tag.trim() to delay }
            .filter { (tag, delay) ->
                tag.isNotBlank() &&
                    delay > 0 &&
                    !sameTag(tag, currentTag) &&
                    quarantinedTags.none { sameTag(it, tag) }
            }
            .minByOrNull { (_, delay) -> delay }
            ?: return

        entry = Entry(candidate.first, nowMs)
    }

    @Synchronized
    fun resolve(
        currentTag: String,
        nowMs: Long,
        quarantinedTags: Set<String> = emptySet()
    ): String? {
        val candidate = entry ?: return null
        if (nowMs - candidate.updatedAtMs > maxAgeMs) {
            entry = null
            return null
        }
        if (sameTag(candidate.tag, currentTag) || quarantinedTags.any { sameTag(it, candidate.tag) }) {
            return null
        }
        return candidate.tag
    }

    @Synchronized
    fun clear() {
        entry = null
    }

    private fun sameTag(first: String, second: String): Boolean {
        return UrlTestTagMatcher.normalizeTag(first) == UrlTestTagMatcher.normalizeTag(second)
    }
}
