package com.kunk.singbox.model

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class ExportData(
    @SerializedName("version") val version: Int = 1,
    @SerializedName("exportTime") val exportTime: Long,
    @SerializedName("appVersion") val appVersion: String,
    @SerializedName("settings") val settings: AppSettings, // AppSettings 导出数据
    @SerializedName("profiles") val profiles: List<ProfileExportData> = emptyList(),
    @SerializedName("activeProfileId") val activeProfileId: String?,
    @SerializedName("activeNodeId") val activeNodeId: String?
)

@Keep
data class ProfileExportData(
    @SerializedName("profile") val profile: ProfileUi,
    @SerializedName("config") val config: SingBoxConfig
)

@Keep
data class ImportOptions(
    val overwriteExisting: Boolean = true,
    val importSettings: Boolean = true, // 是否导入应用设置
    val importProfiles: Boolean = true, // 是否导入配置与节点
    val importRules: Boolean = true // 是否导入规则相关数据
)

@Keep
sealed class ImportResult {
    data class Success(
        val profilesImported: Int,
        val nodesImported: Int,
        val settingsImported: Boolean
    ) : ImportResult()

    data class Failed(val error: String) : ImportResult()
}

@Keep
data class ExportDataSummary(
    val version: Int,
    val exportTime: Long,
    val appVersion: String,
    val profileCount: Int,
    val totalNodeCount: Int,
    val hasSettings: Boolean,
    val hasCustomRules: Boolean,
    val hasRuleSets: Boolean,
    val hasAppRules: Boolean
)
