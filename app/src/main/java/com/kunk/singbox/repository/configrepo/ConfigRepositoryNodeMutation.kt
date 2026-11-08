@file:Suppress("InvalidPackageDeclaration")

package com.kunk.singbox.repository

import com.kunk.singbox.model.DnsRule
import com.kunk.singbox.model.Endpoint
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig

internal fun mergeSubscriptionConfig(previous: SingBoxConfig?, updated: SingBoxConfig): SingBoxConfig {
    val incomingTags = updated.outbounds.orEmpty().mapTo(mutableSetOf(), Outbound::tag) +
        updated.endpoints.orEmpty().map(Endpoint::tag)
    // ponytail: 旧配置没有来源记录，首次更新保留缺失节点；记录基线后仅保留手动新增节点。
    val subscriptionTags = previous?.subscriptionOutboundTags?.toSet() ?: incomingTags
    val localOutbounds = previous?.outbounds.orEmpty().filter {
        it.tag !in subscriptionTags && it.type !in setOf("direct", "block", "dns", "selector", "urltest")
    }
    var merged = updated
    for (local in localOutbounds) {
        if (local.tag in incomingTags) {
            val reserved = merged.copy(outbounds = merged.outbounds.orEmpty() + localOutbounds)
            val newTag = resolveUpdatedOutboundTag(reserved, null, local.tag)
            merged = rewriteOutboundTagReferences(merged, local.tag, newTag)
        }
    }
    val subscriptionOutbounds = preserveSubscriptionDetours(previous, merged).outbounds.orEmpty()
    return merged.copy(
        outbounds = subscriptionOutbounds + localOutbounds,
        subscriptionOutboundTags = subscriptionOutbounds.map(Outbound::tag)
    )
}

internal fun preserveSubscriptionDetours(previous: SingBoxConfig?, updated: SingBoxConfig): SingBoxConfig {
    val savedDetours = previous?.outbounds.orEmpty().mapNotNull { outbound ->
        outbound.detour?.takeIf(String::isNotBlank)?.let { outbound.tag to it }
    }.toMap()
    return updated.copy(outbounds = updated.outbounds?.map { outbound ->
        savedDetours[outbound.tag]?.let { outbound.copy(detour = it) } ?: outbound
    })
}

private fun rewriteTagReference(value: String?, oldTag: String, newTag: String?): String? =
    if (value == oldTag) newTag else value

private fun rewriteTagReferences(values: List<String>?, oldTag: String, newTag: String?): List<String>? {
    return values
        ?.mapNotNull { value -> rewriteTagReference(value, oldTag, newTag) }
        ?.distinct()
        ?.takeIf { it.isNotEmpty() }
}

private fun dynamicTagReferenceContains(value: Any?, tag: String): Boolean = when (value) {
    is String -> value == tag
    is List<*> -> value.any { it == tag }
    else -> false
}

private fun rewriteDynamicTagReference(value: Any?, oldTag: String, newTag: String?): Any? = when (value) {
    is String -> rewriteTagReference(value, oldTag, newTag)
    is List<*> -> value.mapNotNull { item ->
        if (item is String) rewriteTagReference(item, oldTag, newTag) else item
    }.distinct().takeIf { it.isNotEmpty() }
    else -> value
}

private fun rewriteDnsRuleTag(rule: DnsRule, oldTag: String, newTag: String?): DnsRule? {
    val nestedRules = rule.rules?.mapNotNull { nested -> rewriteDnsRuleTag(nested, oldTag, newTag) }
    if (newTag == null && rule.rules != null && nestedRules.isNullOrEmpty()) return null

    val referencedOldTag = dynamicTagReferenceContains(rule.outboundRaw, oldTag)
    val outbound = rewriteDynamicTagReference(rule.outboundRaw, oldTag, newTag)
    if (newTag == null && referencedOldTag && outbound == null) return null
    return rule.copy(rules = nestedRules, outboundRaw = outbound)
}

private fun rewriteOutboundTag(outbound: Outbound, oldTag: String, newTag: String?): Outbound {
    val members = rewriteTagReferences(outbound.outbounds, oldTag, newTag)
        ?: if (outbound.outbounds != null && newTag == null) listOf("direct") else null
    val default = if (outbound.default == oldTag) newTag ?: members?.firstOrNull() else outbound.default
    val tag = if (outbound.tag == oldTag && newTag != null) newTag else outbound.tag
    val detour = rewriteTagReference(outbound.detour, oldTag, newTag)?.takeUnless { it == tag }
    return outbound.copy(
        tag = tag,
        outbounds = members,
        default = default,
        detour = detour
    )
}

internal fun rewriteOutboundTagReferences(
    config: SingBoxConfig,
    oldTag: String,
    newTag: String?
): SingBoxConfig {
    val outbounds = config.outbounds
        ?.filterNot { newTag == null && it.tag == oldTag }
        ?.map { rewriteOutboundTag(it, oldTag, newTag) }
    val proxies = config.proxies
        ?.filterNot { newTag == null && it.tag == oldTag }
        ?.map { rewriteOutboundTag(it, oldTag, newTag) }
    val endpoints = config.endpoints
        ?.filterNot { newTag == null && it.tag == oldTag }
        ?.map { endpoint ->
            endpoint.copy(
                tag = if (endpoint.tag == oldTag && newTag != null) newTag else endpoint.tag,
                detour = rewriteTagReference(endpoint.detour, oldTag, newTag)
            )
        }
    val dns = config.dns?.copy(
        servers = config.dns.servers?.map { server ->
            server.copy(detour = rewriteTagReference(server.detour, oldTag, newTag))
        },
        rules = config.dns.rules?.mapNotNull { rule -> rewriteDnsRuleTag(rule, oldTag, newTag) }
    )
    val route = config.route?.copy(
        rules = config.route.rules
            ?.filterNot { newTag == null && it.outbound == oldTag }
            ?.map { rule -> rule.copy(outbound = rewriteTagReference(rule.outbound, oldTag, newTag)) },
        ruleSet = config.route.ruleSet?.map { ruleSet ->
            ruleSet.copy(downloadDetour = rewriteTagReference(ruleSet.downloadDetour, oldTag, newTag))
        },
        finalOutbound = rewriteTagReference(config.route.finalOutbound, oldTag, newTag)
    )
    return config.copy(
        outbounds = outbounds,
        proxies = proxies,
        endpoints = endpoints,
        dns = dns,
        route = route,
        subscriptionOutboundTags = config.subscriptionOutboundTags
            ?.mapNotNull { rewriteTagReference(it, oldTag, newTag) }?.distinct()
    )
}

internal fun resolveUpdatedOutboundTag(
    config: SingBoxConfig,
    oldTag: String?,
    requestedTag: String
): String {
    val base = requestedTag.trim().ifBlank { "unnamed" }
    val reserved = buildSet {
        config.outbounds.orEmpty().filterNot { it.tag == oldTag }.mapTo(this, Outbound::tag)
        config.proxies.orEmpty().filterNot { it.tag == oldTag }.mapTo(this, Outbound::tag)
        config.endpoints.orEmpty().filterNot { it.tag == oldTag }.mapTo(this, Endpoint::tag)
    }
    if (base !in reserved) return base
    var suffix = 1
    while ("${base}_$suffix" in reserved) suffix++
    return "${base}_$suffix"
}

internal fun replaceOutboundInConfig(
    config: SingBoxConfig,
    oldTag: String,
    newOutbound: Outbound
): Pair<SingBoxConfig, String> {
    val finalTag = resolveUpdatedOutboundTag(config, oldTag, newOutbound.tag)
    val replacement = newOutbound.copy(tag = finalTag)
    val replaced = config.copy(
        outbounds = config.outbounds?.map { outbound ->
            if (outbound.tag == oldTag) replacement else outbound
        },
        proxies = config.proxies?.map { outbound ->
            if (outbound.tag == oldTag) replacement else outbound
        }
    )
    return rewriteOutboundTagReferences(replaced, oldTag, finalTag) to finalTag
}

internal fun removeOutboundFromConfig(config: SingBoxConfig, removedTag: String): SingBoxConfig =
    rewriteOutboundTagReferences(config, removedTag, null)
