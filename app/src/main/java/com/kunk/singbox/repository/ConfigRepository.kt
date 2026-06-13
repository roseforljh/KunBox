package com.kunk.singbox.repository

import android.content.Context
import com.kunk.singbox.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
class ConfigRepository(context: Context) : ConfigRepositoryPart7(context) {

    sealed class OutboundSemantic {
        object Direct : OutboundSemantic()
        object Block : OutboundSemantic()
        object Proxy : OutboundSemantic()
        data class RouteTag(val tag: String) : OutboundSemantic()
        data class FallbackProxy(val tag: String) : OutboundSemantic()
    }

    enum class RuleSetRuleType {
        IP,
        DOMAIN,
        MIXED,
        UNKNOWN
    }

    @Suppress("TooManyFunctions", "LargeClass")
    data class SubscriptionUserInfo(
        val upload: Long = 0,
        val download: Long = 0,
        val total: Long = 0,
        val expire: Long = 0
    )

    sealed class NodeSwitchResult {
        object Success : NodeSwitchResult()
        object NotRunning : NodeSwitchResult()
        data class Failed(val reason: String) : NodeSwitchResult()
    }

    data class ConfigGenerationResult(
        val path: String,
        val activeNodeTag: String?,
        val outboundTags: Set<String>
    )

    companion object : ConfigRepositoryCompanionPart3() {
    }
}
