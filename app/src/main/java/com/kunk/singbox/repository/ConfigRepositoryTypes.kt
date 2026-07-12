package com.kunk.singbox.repository

import com.kunk.singbox.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ConfigRepositoryNodeTestInfo(
    val outbound: Outbound,
    val nodeId: String,
    val profileId: String,
    val dnsConfig: DnsConfig?,
    val allOutbounds: List<Outbound>
)

data class ConfigRepositoryLatencyRuntimeContext(
    val outbounds: List<Outbound>,
    val dnsConfig: DnsConfig?
)

data class ConfigRepositorySubscriptionAttemptContext(
    val host: String,
    val userAgent: String,
    val isRemembered: Boolean
)

data class ConfigRepositoryFetchResult(
    val config: SingBoxConfig,
    val userInfo: ConfigRepository.SubscriptionUserInfo?,
    val subscriptionName: String? = null
)

data class ConfigRepositorySubscriptionAttemptResult(
    val fetchResult: ConfigRepositoryFetchResult? = null,
    val shouldStopFallback: Boolean = false,
    val terminalError: Exception? = null
)

data class ConfigRepositoryRunOutboundsContext(
    val outbounds: List<Outbound>,
    val selectorTag: String,
    val nodeTagResolver: (String?) -> String?,
    val nodeTagMap: Map<String, String>
)

data class ConfigRepositoryOutboundSemanticContext(
    val selectorTag: String,
    val outbounds: List<Outbound>,
    val profiles: List<ProfileUi>,
    val nodeTagResolver: (String?) -> String?
)

data class ConfigRepositoryFakeIpRanges(
    val inet4Range: String,
    val inet6Range: String
)
