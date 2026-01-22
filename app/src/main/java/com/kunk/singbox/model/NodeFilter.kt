package com.kunk.singbox.model

import com.google.gson.annotations.SerializedName

// 过滤模式枚举
enum class FilterMode {
    @SerializedName("NONE") NONE, // 不过滤
    @SerializedName("INCLUDE") INCLUDE, // 只显示包含关键字的节点
    @SerializedName("EXCLUDE") EXCLUDE // 排除包含关键字的节点
}

// 节点过滤配置数据类
data class NodeFilter(
    @SerializedName("filterMode") val filterMode: FilterMode = FilterMode.NONE,
    @SerializedName("includeKeywords") val includeKeywords: List<String> = emptyList(),
    @SerializedName("excludeKeywords") val excludeKeywords: List<String> = emptyList(),
    @Deprecated("Use includeKeywords/excludeKeywords instead")
    @SerializedName("keywords") val keywords: List<String> = emptyList()
) {
    // 兼容性：如果旧数据只有 keywords，迁移到对应的字段
    val effectiveIncludeKeywords: List<String>
        get() = includeKeywords.ifEmpty {
            if (filterMode == FilterMode.INCLUDE) keywords else emptyList()
        }

    val effectiveExcludeKeywords: List<String>
        get() = excludeKeywords.ifEmpty {
            if (filterMode == FilterMode.EXCLUDE) keywords else emptyList()
        }
}
