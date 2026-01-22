package com.kunk.singbox.model

import androidx.annotation.Keep
import androidx.annotation.StringRes
import com.google.gson.annotations.SerializedName
import com.kunk.singbox.R
import java.util.UUID

@Keep
enum class RuleType(@StringRes val displayNameRes: Int) {
    @SerializedName("DOMAIN") DOMAIN(R.string.rule_type_domain),
    @SerializedName("DOMAIN_SUFFIX") DOMAIN_SUFFIX(R.string.rule_type_domain_suffix),
    @SerializedName("DOMAIN_KEYWORD") DOMAIN_KEYWORD(R.string.rule_type_domain_keyword),
    @SerializedName("IP_CIDR") IP_CIDR(R.string.rule_type_ip_cidr),
    @SerializedName("GEOIP") GEOIP(R.string.rule_type_geoip),
    @SerializedName("GEOSITE") GEOSITE(R.string.rule_type_geosite),
    @SerializedName("PORT") PORT(R.string.rule_type_port),
    @SerializedName("PROCESS_NAME") PROCESS_NAME(R.string.rule_type_process_name)
}

@Keep
enum class OutboundTag(@StringRes val displayNameRes: Int) {
    @SerializedName("DIRECT") DIRECT(R.string.outbound_tag_direct),
    @SerializedName("PROXY") PROXY(R.string.outbound_tag_proxy),
    @SerializedName("BLOCK") BLOCK(R.string.outbound_tag_block)
}

@Keep
data class CustomRule(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("name") val name: String,
    @SerializedName("type") val type: RuleType,
    @SerializedName("value") val value: String, // e.g., "google.com", "cn"
    @SerializedName("outbound") val outbound: OutboundTag = OutboundTag.PROXY,
    @SerializedName("outboundMode") val outboundMode: RuleSetOutboundMode? = null,
    @SerializedName("outboundValue") val outboundValue: String? = null, // ID for Node/Profile, or Name for Group
    @SerializedName("enabled") val enabled: Boolean = true
)

@Keep
enum class RuleSetType(@StringRes val displayNameRes: Int) {
    @SerializedName("REMOTE") REMOTE(R.string.ruleset_type_remote),
    @SerializedName("LOCAL") LOCAL(R.string.ruleset_type_local)
}

@Keep
enum class RuleSetOutboundMode(@StringRes val displayNameRes: Int) {
    @SerializedName("DIRECT") DIRECT(R.string.ruleset_outbound_direct),
    @SerializedName("BLOCK") BLOCK(R.string.ruleset_outbound_block),
    @SerializedName("PROXY") PROXY(R.string.ruleset_outbound_proxy),
    @SerializedName("NODE") NODE(R.string.ruleset_outbound_node),
    @SerializedName("PROFILE") PROFILE(R.string.ruleset_outbound_profile)
}

@Keep
data class RuleSet(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("tag") val tag: String, // Unique identifier in config
    @SerializedName("type") val type: RuleSetType,
    @SerializedName("format") val format: String = "binary", // "binary" or "source"
    @SerializedName("url") val url: String = "", // For remote
    @SerializedName("path") val path: String = "", // For local
    @SerializedName("enabled") val enabled: Boolean = true,
    @SerializedName("outboundMode") val outboundMode: RuleSetOutboundMode? = RuleSetOutboundMode.DIRECT,
    @SerializedName("outboundValue") val outboundValue: String? = null, // ID for Node/Profile, or Name for Group
    @SerializedName("inbounds") val inbounds: List<String>? = emptyList(), // List of inbound tags
    @SerializedName("autoUpdateInterval") val autoUpdateInterval: Int = 0 // 0 means disabled, in minutes
)

@Keep
data class AppRule(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("packageName") val packageName: String, // e.g., "com.google.android.youtube"
    @SerializedName("appName") val appName: String, // Display name, e.g., "YouTube"
    @SerializedName("outboundMode") val outboundMode: RuleSetOutboundMode? = RuleSetOutboundMode.DIRECT,
    @SerializedName("outboundValue") val outboundValue: String? = null, // ID for Node/Profile, or Name for Group
    @SerializedName("enabled") val enabled: Boolean = true
)

@Keep
data class AppGroup(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("name") val name: String, // Group name, e.g., "社交应用", "游戏"
    @SerializedName("apps") val apps: List<AppInfo> = emptyList(), // Apps in this group
    @SerializedName("outboundMode") val outboundMode: RuleSetOutboundMode? = RuleSetOutboundMode.DIRECT,
    @SerializedName("outboundValue") val outboundValue: String? = null, // ID for Node/Profile, or Name for Group
    @SerializedName("enabled") val enabled: Boolean = true
)

@Keep
data class AppInfo(
    @SerializedName("packageName") val packageName: String,
    @SerializedName("appName") val appName: String
)

@Keep
data class InstalledApp(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean = false
)
