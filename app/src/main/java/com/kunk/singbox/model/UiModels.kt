package com.kunk.singbox.model

import android.content.Context
import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName
import com.kunk.singbox.R

@Keep
@Immutable
data class ProfileUi(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("type") val type: ProfileType = ProfileType.Subscription, // Profile type
    @SerializedName("url") val url: String?,
    @SerializedName("lastUpdated") val lastUpdated: Long,
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("autoUpdateInterval") val autoUpdateInterval: Int = 0, // 0 means disabled, minutes
    @SerializedName("updateStatus") val updateStatus: UpdateStatus = UpdateStatus.Idle,
    @SerializedName("expireDate") val expireDate: Long = 0,
    @SerializedName("totalTraffic") val totalTraffic: Long = 0,
    @SerializedName("usedTraffic") val usedTraffic: Long = 0,

    @SerializedName("dnsPreResolve") val dnsPreResolve: Boolean = false,
    @SerializedName("dnsServer") val dnsServer: String? = null,
    @SerializedName("dnsOverride") val dnsOverride: String? = null,
    @Transient val updateStage: SubscriptionUpdateStage? = null
)

@Keep
enum class SubscriptionUpdateStage(
    @StringRes val labelRes: Int
) {
    Requesting(R.string.subscription_update_stage_requesting),
    Parsing(R.string.subscription_update_stage_parsing),
    Saving(R.string.subscription_update_stage_saving),
    DnsBackground(R.string.subscription_update_stage_dns_background)
}

@Keep
enum class ProfileType {
    @SerializedName("Subscription") Subscription,
    @SerializedName("LocalFile") LocalFile,
    @SerializedName("Imported") Imported,
    @SerializedName("Custom") Custom
}

@Keep
enum class UpdateStatus {
    @SerializedName("Idle") Idle,
    @SerializedName("Updating") Updating,
    @SerializedName("Success") Success,
    @SerializedName("Failed") Failed
}

sealed class SubscriptionUpdateResult {
    data class SuccessWithChanges(
        val profileName: String,
        val addedCount: Int,
        val removedCount: Int,
        val totalCount: Int
    ) : SubscriptionUpdateResult()

    data class SuccessNoChanges(
        val profileName: String,
        val totalCount: Int
    ) : SubscriptionUpdateResult()

    data class Failed(
        val profileName: String,
        val error: String
    ) : SubscriptionUpdateResult()
}

data class BatchUpdateResult(
    val successWithChanges: Int = 0,
    val successNoChanges: Int = 0,
    val failed: Int = 0,
    val details: List<SubscriptionUpdateResult> = emptyList()
) {
    val totalCount: Int get() = successWithChanges + successNoChanges + failed
    val successCount: Int get() = successWithChanges + successNoChanges

    fun toDisplayMessage(context: Context): String {
        return when {
            totalCount == 0 -> context.getString(R.string.update_status_no_subscription)
            failed == totalCount -> context.getString(R.string.update_status_failed)
            successWithChanges > 0 && failed == 0 -> context.getString(R.string.update_status_success_changes, successWithChanges)
            successNoChanges == totalCount -> context.getString(R.string.update_status_success_no_changes)
            failed > 0 -> context.getString(R.string.update_status_partial, successCount, failed)
            else -> context.getString(R.string.update_status_complete)
        }
    }
}

@Immutable
@Keep
data class NodeUi(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("protocol") val protocol: String,
    @SerializedName("group") val group: String,
    @SerializedName("latencyMs") val latencyMs: Long? = null, // null means not tested
    @SerializedName("isFavorite") val isFavorite: Boolean = false,
    @SerializedName("sourceProfileId") val sourceProfileId: String,
    @SerializedName("tags") val tags: List<String> = emptyList(),
    @SerializedName("trafficUsed") val trafficUsed: Long = 0,
    @SerializedName("autoSelectionEligible") val autoSelectionEligible: Boolean = true,
    @SerializedName("meteredProtected") val meteredProtected: Boolean = false,
    @SerializedName("hasDetour") val hasDetour: Boolean = false
) {
    val displayName: String
        get() = name

    val protocolDisplay: String
        get() = when (protocol.lowercase()) {
            "http" -> "HTTPS"
            "socks" -> "SOCKS5"
            "shadowsocks" -> "SS"
            "vmess" -> "VMess"
            "vless" -> "VLESS"
            "trojan" -> "Trojan"
            "hysteria" -> "Hysteria"
            "hysteria2" -> "Hysteria2"
            "tuic" -> "TUIC"
            "wireguard" -> "WireGuard"
            "ssh" -> "SSH"
            "anytls" -> "AnyTLS"
            else -> protocol.uppercase()
        }
}

data class ConnectionStats(
    val uploadSpeed: Long, // bytes/s
    val downloadSpeed: Long, // bytes/s
    val uploadTotal: Long, // bytes
    val downloadTotal: Long, // bytes
    val duration: Long // ms
)

data class InstalledAppUi(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val hasLauncher: Boolean
)

enum class ConnectionState(@StringRes val displayNameRes: Int) {
    Idle(R.string.connection_idle),
    Connecting(R.string.connection_connecting),
    Connected(R.string.connection_connected),
    Disconnecting(R.string.connection_disconnecting),
    Error(R.string.connection_error)
}

@Keep
data class SavedProfilesData(
    @SerializedName("profiles") val profiles: List<ProfileUi>,
    @SerializedName("activeProfileId") val activeProfileId: String?,
    @SerializedName("activeNodeId") val activeNodeId: String?,
    @SerializedName("nodeLatencies") val nodeLatencies: Map<String, Long> = emptyMap()
)

@Keep
data class VMessLinkConfig(
    @SerializedName("v") val v: String? = null,
    @SerializedName("ps") val ps: String? = null, // 备注
    @SerializedName("add") val add: String? = null,
    @SerializedName("port") val port: String? = null, // 端口
    @SerializedName("id") val id: String? = null, // UUID
    @SerializedName("aid") val aid: String? = null, // alterId
    @SerializedName("scy") val scy: String? = null, // 加密方式
    @SerializedName("net") val net: String? = null, // 传输网络
    @SerializedName("type") val type: String? = null, // 传输类型
    @SerializedName("host") val host: String? = null, // 主机
    @SerializedName("path") val path: String? = null, // 路径
    @SerializedName("tls") val tls: String? = null, // TLS
    @SerializedName("sni") val sni: String? = null, // SNI
    @SerializedName("alpn") val alpn: String? = null,
    @SerializedName("fp") val fp: String? = null, // fingerprint
    @SerializedName("packetEncoding") val packetEncoding: String? = null, // packet encoding
    @SerializedName("mode") val mode: String? = null,
    @SerializedName("xPaddingBytes") val xPaddingBytes: String? = null,
    @SerializedName("scMaxEachPostBytes") val scMaxEachPostBytes: Long? = null,
    @SerializedName("scMinPostsIntervalMs") val scMinPostsIntervalMs: Long? = null,
    @SerializedName("scMaxBufferedPosts") val scMaxBufferedPosts: Long? = null,
    @SerializedName("noGRPCHeader") val noGRPCHeader: Boolean? = null,
    @SerializedName("noSSEHeader") val noSSEHeader: Boolean? = null
)
