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
    @SerializedName("type") val type: ProfileType,
    @SerializedName("url") val url: String?,
    @SerializedName("lastUpdated") val lastUpdated: Long,
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("autoUpdateInterval") val autoUpdateInterval: Int = 0, // 0 means disabled, minutes
    @SerializedName("updateStatus") val updateStatus: UpdateStatus = UpdateStatus.Idle,
    @SerializedName("expireDate") val expireDate: Long = 0,
    @SerializedName("totalTraffic") val totalTraffic: Long = 0,
    @SerializedName("usedTraffic") val usedTraffic: Long = 0
)

@Keep
enum class ProfileType {
    @SerializedName("Subscription") Subscription,
    @SerializedName("LocalFile") LocalFile,
    @SerializedName("Imported") Imported
}

@Keep
enum class UpdateStatus {
    @SerializedName("Idle") Idle,
    @SerializedName("Updating") Updating,
    @SerializedName("Success") Success,
    @SerializedName("Failed") Failed
}

/**
 * 订阅更新结果
 */
sealed class SubscriptionUpdateResult {
    /**
     * 更新成功，有变化
     * @param profileName 配置名称
     * @param addedCount 新增节点数
     * @param removedCount 移除节点数
     * @param totalCount 总节点数
     */
    data class SuccessWithChanges(
        val profileName: String,
        val addedCount: Int,
        val removedCount: Int,
        val totalCount: Int
    ) : SubscriptionUpdateResult()

    /**
     * 更新成功，无变化
     * @param profileName 配置名称
     * @param totalCount 总节点数
     */
    data class SuccessNoChanges(
        val profileName: String,
        val totalCount: Int
    ) : SubscriptionUpdateResult()

    /**
     * 更新失败
     * @param profileName 配置名称
     * @param error 错误信息
     */
    data class Failed(
        val profileName: String,
        val error: String
    ) : SubscriptionUpdateResult()
}

/**
 * 批量更新订阅的汇总结果
 */
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
    @SerializedName("regionFlag") val regionFlag: String? = null,
    @SerializedName("latencyMs") val latencyMs: Long? = null, // null means not tested
    @SerializedName("isFavorite") val isFavorite: Boolean = false,
    @SerializedName("sourceProfileId") val sourceProfileId: String,
    @SerializedName("tags") val tags: List<String> = emptyList(),
    @SerializedName("trafficUsed") val trafficUsed: Long = 0
) {
    val displayName: String
        get() = name

    /**
     * 获取协议的显示名称
     * 将内部协议类型转换为用户友好的显示名称
     */
    val protocolDisplay: String
        get() = when (protocol.lowercase()) {
            "http" -> "HTTPS" // HTTP 类型配置了 TLS 就是 HTTPS
            "socks" -> "SOCKS5" // SOCKS 协议的现代版本
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

data class RuleSetUi(
    val id: String,
    val name: String,
    val type: String, // Remote, Local
    val sourceUrl: String?,
    val enabled: Boolean,
    val lastUpdated: Long,
    val ruleCount: Int
)

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

data class LogEntryUi(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
)

data class ConnectionStats(
    val uploadSpeed: Long, // bytes/s
    val downloadSpeed: Long, // bytes/s
    val uploadTotal: Long, // bytes
    val downloadTotal: Long, // bytes
    val duration: Long // ms
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

/**
 * VMess 链接配置格式
 */
@Keep
data class VMessLinkConfig(
    @SerializedName("v") val v: String? = null,
    @SerializedName("ps") val ps: String? = null, // 名称
    @SerializedName("add") val add: String? = null, // 服务器地址
    @SerializedName("port") val port: String? = null, // 端口
    @SerializedName("id") val id: String? = null, // UUID
    @SerializedName("aid") val aid: String? = null, // alterId
    @SerializedName("scy") val scy: String? = null, // 加密方式
    @SerializedName("net") val net: String? = null, // 传输协议
    @SerializedName("type") val type: String? = null, // 伪装类型
    @SerializedName("host") val host: String? = null, // 伪装域名
    @SerializedName("path") val path: String? = null, // 路径
    @SerializedName("tls") val tls: String? = null, // TLS
    @SerializedName("sni") val sni: String? = null, // SNI
    @SerializedName("alpn") val alpn: String? = null,
    @SerializedName("fp") val fp: String? = null, // fingerprint
    @SerializedName("packetEncoding") val packetEncoding: String? = null // packet encoding
)
