package com.kunk.singbox.repository

import com.google.gson.Gson
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.model.SingBoxConfig

internal class MeteredNodeConfigurationException(
    val violations: List<String>
) : IllegalArgumentException(
    "计费节点保护阻止启动：" + violations.distinct().joinToString(separator = "；")
)

/** 生成配置前检查所有可能直接使用计费节点的引用。 */
internal object MeteredNodeConfigGuard {
    private val groupTypes = setOf("selector", "urltest", "url-test")
    private val gson = Gson()

    @Suppress("CyclomaticComplexMethod")
    fun findSettingsViolations(
        settings: AppSettings,
        nodes: List<NodeUi>,
        allowedProtectedNodeId: String? = null,
        isPackageCaptured: (String) -> Boolean = { true }
    ): List<String> {
        val protectedNodes = nodes.filter(NodeUi::meteredProtected)
        if (protectedNodes.isEmpty()) return emptyList()

        fun protectedProfileNode(profileId: String?): NodeUi? {
            return protectedNodes.firstOrNull {
                it.sourceProfileId == profileId &&
                    it.autoSelectionEligible &&
                    it.id != allowedProtectedNodeId
            }
        }

        fun violation(
            location: String,
            mode: RuleSetOutboundMode?,
            value: String?
        ): String? {
            val node = when (mode) {
                RuleSetOutboundMode.PROFILE -> protectedProfileNode(value)
                else -> null
            } ?: return null
            return "$location 引用受保护节点「${node.name}」"
        }

        return buildList {
            settings.appRules.filter { it.enabled && isPackageCaptured(it.packageName) }.forEach { rule ->
                violation("应用规则「${rule.appName}」", rule.outboundMode, rule.outboundValue)?.let(::add)
            }
            settings.appGroups.filter {
                it.enabled && it.apps.any { app -> isPackageCaptured(app.packageName) }
            }.forEach { group ->
                violation("应用组「${group.name}」", group.outboundMode, group.outboundValue)?.let(::add)
            }
            settings.customRules.filter { it.enabled }.forEach { rule ->
                violation("自定义规则「${rule.name}」", rule.outboundMode, rule.outboundValue)?.let(::add)
            }
            settings.ruleSets.filter { it.enabled }.forEach { ruleSet ->
                violation("规则集「${ruleSet.tag}」", ruleSet.outboundMode, ruleSet.outboundValue)?.let(::add)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    fun findConfigViolations(
        config: SingBoxConfig,
        protectedTags: Set<String>,
        includeGroupReferences: Boolean,
        includeDeclaredNodes: Boolean = includeGroupReferences
    ): List<String> {
        if (protectedTags.isEmpty()) return emptyList()

        return findConfigViolationsByReference(
            config,
            includeGroupReferences,
            includeDeclaredNodes
        ) { value ->
            value?.trim()?.takeIf(protectedTags::contains)
        }
    }

    fun findSourceConfigViolations(
        config: SingBoxConfig,
        sourceProfileId: String,
        protectedNodeIds: Set<String>,
        includeGroupReferences: Boolean,
        includeDeclaredNodes: Boolean = includeGroupReferences,
        allowedProtectedNodeId: String? = null
    ): List<String> {
        if (protectedNodeIds.isEmpty()) return emptyList()

        return findConfigViolationsByReference(
            config,
            includeGroupReferences,
            includeDeclaredNodes
        ) { value ->
            value?.trim()?.takeIf { reference ->
                isProtectedNodeReference(
                    sourceProfileId = sourceProfileId,
                    reference = reference,
                    protectedNodeIds = protectedNodeIds,
                    allowedProtectedNodeId = allowedProtectedNodeId
                )
            }
        }
    }

    fun isProtectedNodeReference(
        sourceProfileId: String,
        reference: String?,
        protectedNodeIds: Set<String>,
        allowedProtectedNodeId: String? = null
    ): Boolean {
        val normalized = reference?.trim().orEmpty()
        val targetNodeId = when {
            normalized.isBlank() -> null
            normalized in protectedNodeIds -> normalized
            else -> {
                val parts = normalized.split("::", limit = 2)
                val targetProfileId = if (parts.size == 2) parts[0].trim() else sourceProfileId
                val targetTag = if (parts.size == 2) parts[1].trim() else normalized
                ConfigRepository.stableNodeId(targetProfileId, targetTag)
                    .takeIf { targetProfileId.isNotBlank() && targetTag.isNotBlank() }
            }
        }
        return targetNodeId != null &&
            targetNodeId in protectedNodeIds &&
            targetNodeId != allowedProtectedNodeId
    }

    @Suppress("CyclomaticComplexMethod")
    private fun findConfigViolationsByReference(
        config: SingBoxConfig,
        includeGroupReferences: Boolean,
        includeDeclaredNodes: Boolean,
        protectedReference: (String?) -> String?
    ): List<String> {

        fun protectedTag(value: String?): String? {
            return protectedReference(value)
        }

        return buildList {
            config.outbounds.orEmpty().forEach { outbound ->
                if (includeDeclaredNodes && protectedTag(outbound.tag) != null) {
                    add("运行出站仍包含受保护节点「${outbound.tag}」")
                }
                protectedTag(outbound.detour)?.let { tag ->
                    add("出站「${outbound.tag}」的前置代理引用「$tag」")
                }
                if (includeGroupReferences && outbound.type.lowercase() in groupTypes) {
                    outbound.outbounds.orEmpty().mapNotNull(protectedReference).forEach { tag ->
                        add("${outbound.type}「${outbound.tag}」的候选引用「$tag」")
                    }
                    protectedTag(outbound.default)?.let { tag ->
                        add("selector「${outbound.tag}」的默认节点引用「$tag」")
                    }
                }
            }
            config.endpoints.orEmpty().forEach { endpoint ->
                if (includeDeclaredNodes && protectedTag(endpoint.tag) != null) {
                    add("运行 endpoint 仍包含受保护节点「${endpoint.tag}」")
                }
                protectedTag(endpoint.detour)?.let { tag ->
                    add("endpoint「${endpoint.tag}」的前置代理引用「$tag」")
                }
            }
            config.route?.finalOutbound?.let { finalTag ->
                protectedTag(finalTag)?.let { add("路由 final 引用「$it」") }
            }
            config.route?.rules.orEmpty().forEachIndexed { index, rule ->
                protectedTag(rule.outbound)?.let { add("路由规则[$index]引用「$it」") }
            }
            config.route?.ruleSet.orEmpty().forEachIndexed { index, ruleSet ->
                protectedTag(ruleSet.downloadDetour)?.let { add("规则集下载[$index]引用「$it」") }
            }
            config.dns?.servers.orEmpty().forEach { server ->
                protectedTag(server.detour)?.let { add("DNS「${server.tag.orEmpty()}」引用「$it」") }
            }
        }
    }

    fun removeDisallowedNodes(outbounds: List<Outbound>, disallowedTags: Set<String>): List<Outbound> {
        if (disallowedTags.isEmpty()) return outbounds
        return outbounds
            .filterNot { it.tag in disallowedTags }
            .map { outbound ->
                if (outbound.type.lowercase() !in groupTypes) return@map outbound
                val candidates = outbound.outbounds.orEmpty().filterNot(disallowedTags::contains)
                outbound.copy(
                    outbounds = candidates,
                    default = outbound.default?.takeIf(candidates::contains)
                )
            }
    }

    /** 显式 NODE 分流可保留实体出站，但禁止进入任何 selector 或 urltest。 */
    fun removeGroupReferences(outbounds: List<Outbound>, disallowedTags: Set<String>): List<Outbound> {
        if (disallowedTags.isEmpty()) return outbounds
        return outbounds.map { outbound ->
            if (outbound.type.lowercase() !in groupTypes) return@map outbound
            val candidates = outbound.outbounds.orEmpty().filterNot(disallowedTags::contains)
            outbound.copy(
                outbounds = candidates,
                default = outbound.default?.takeIf(candidates::contains)
            )
        }
    }

    /** 显式分流授权只允许 route.rules 使用，其他隐式路径继续视为违规。 */
    fun findExplicitRouteScopeViolations(
        config: SingBoxConfig,
        protectedTags: Set<String>
    ): List<String> {
        if (protectedTags.isEmpty()) return emptyList()

        fun protectedTag(value: String?): String? = value?.trim()?.takeIf(protectedTags::contains)

        return buildList {
            config.outbounds.orEmpty().forEach { outbound ->
                protectedTag(outbound.detour)?.let { tag ->
                    add("出站「${outbound.tag}」的前置代理引用「$tag」")
                }
                if (outbound.type.lowercase() in groupTypes) {
                    outbound.outbounds.orEmpty().mapNotNull(::protectedTag).forEach { tag ->
                        add("${outbound.type}「${outbound.tag}」的候选引用「$tag」")
                    }
                    protectedTag(outbound.default)?.let { tag ->
                        add("selector「${outbound.tag}」的默认节点引用「$tag」")
                    }
                }
            }
            config.endpoints.orEmpty().forEach { endpoint ->
                protectedTag(endpoint.detour)?.let { tag ->
                    add("endpoint「${endpoint.tag}」的前置代理引用「$tag」")
                }
            }
            protectedTag(config.route?.finalOutbound)?.let { add("路由 final 引用「$it」") }
            config.route?.ruleSet.orEmpty().forEachIndexed { index, ruleSet ->
                protectedTag(ruleSet.downloadDetour)?.let { add("规则集下载[$index]引用「$it」") }
            }
            config.dns?.servers.orEmpty().forEach { server ->
                protectedTag(server.detour)
                    ?.takeUnless { tag -> server.tag == ConfigRepository.buildDynamicDnsServerTag(tag) }
                    ?.let { tag -> add("DNS「${server.tag.orEmpty()}」引用「$tag」") }
            }
        }
    }

    fun requireNoViolations(violations: List<String>) {
        if (violations.isNotEmpty()) {
            throw MeteredNodeConfigurationException(violations)
        }
    }

    fun findUnauthorizedRuntimeNodes(
        mappings: Map<String, RuntimeNodeRef>,
        protectedNodeIds: Set<String>,
        selectedNodeId: String?,
        manuallyAuthorizedNodeId: String?
    ): Map<String, RuntimeNodeRef> {
        return mappings.filterValues { ref ->
            ref.nodeId in protectedNodeIds &&
                !ref.explicitRouteAuthorized &&
                (ref.nodeId != selectedNodeId || ref.nodeId != manuallyAuthorizedNodeId)
        }
    }

    fun requireRuntimeConfigAuthorized(configContent: String, selectedNodeId: String?) {
        val protectedNodeIds = NodeProtectionStore.protectedNodeIds()
        if (protectedNodeIds.isEmpty()) return
        requireNoViolations(
            listOfNotNull(
                "运行配置与节点保护映射不一致，需重新生成配置"
                    .takeUnless { NodeProtectionStore.runtimeConfigMatches(configContent) }
            )
        )

        val mappings = NodeProtectionStore.runtimeMappings()
        val effectiveSelectedNodeId = NodeProtectionStore.effectiveSelectedNodeId(selectedNodeId)
        val config = configContent.toConfig()
        val routeOnlyTags = mappings.filterValues { ref ->
            ref.explicitRouteAuthorized &&
                !NodeProtectionStore.isRuntimeUseAuthorized(ref.nodeId, effectiveSelectedNodeId)
        }.keys
        requireNoViolations(findExplicitRouteScopeViolations(configContent.toConfig(), routeOnlyTags))
        val unauthorized = findUnauthorizedRuntimeNodes(
            mappings = mappings,
            protectedNodeIds = protectedNodeIds,
            selectedNodeId = effectiveSelectedNodeId,
            manuallyAuthorizedNodeId = effectiveSelectedNodeId
                ?.let(NodeProtectionStore::authorizedManualNodeId)
        )
        if (unauthorized.isEmpty()) return

        requireNoViolations(
            findConfigViolations(
                config = config,
                protectedTags = unauthorized.keys,
                includeGroupReferences = true
            )
        )
    }

    private fun String.toConfig(): SingBoxConfig {
        return runCatching { gson.fromJson(this, SingBoxConfig::class.java) }
            .getOrElse { error ->
                throw MeteredNodeConfigurationException(
                    listOf("无法检查运行配置：${error.message ?: error.javaClass.simpleName}")
                )
            }
    }
}
