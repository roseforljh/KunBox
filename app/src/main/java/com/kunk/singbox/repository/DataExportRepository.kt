package com.kunk.singbox.repository

import com.kunk.singbox.R
import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
// import com.kunk.singbox.BuildConfig // Build config is usually in root package or needs verification
import com.kunk.singbox.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * 数据导入导出仓库
 * 负责应用数据的备份和恢复
 */
class DataExportRepository(private val context: Context) {

    companion object {
        private const val TAG = "DataExportRepository"
        private const val CURRENT_VERSION = 1

        @Volatile
        private var instance: DataExportRepository? = null

        fun getInstance(context: Context): DataExportRepository {
            return instance ?: synchronized(this) {
                instance ?: DataExportRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    // 使用 Application Scope 替代 GlobalScope,避免内存泄漏
    // Repository 是单例且生命周期与应用相同,使用 SupervisorJob 确保子协程异常不影响父协程
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .create()

    private val settingsRepository = SettingsRepository.getInstance(context)
    private val configRepository = ConfigRepository.getInstance(context)
    private val ruleSetRepository = RuleSetRepository.getInstance(context)

    private val configDir: File
        get() = File(context.filesDir, "configs").also { it.mkdirs() }

    /**
     * 导出所有数据
     * @return 导出数据的 JSON 字符串
     */
    suspend fun exportAllData(): Result<String> = withContext(Dispatchers.IO) {
        try {

            // 1. 获取当前设置
            val settings = settingsRepository.settings.first()

            // 2. 获取配置列表和节点数据
            val profiles = configRepository.profiles.value
            val activeProfileId = configRepository.activeProfileId.value
            val activeNodeId = configRepository.activeNodeId.value

            // 3. 加载每个配置的完整节点数据
            val profileExportDataList = profiles.mapNotNull { profile ->
                try {
                    val configFile = File(configDir, "${profile.id}.json")
                    if (configFile.exists()) {
                        val configJson = configFile.readText()
                        val config = gson.fromJson(configJson, SingBoxConfig::class.java)
                        ProfileExportData(profile = profile, config = config)
                    } else {
                        Log.w(TAG, "Config file not found for profile: ${profile.id}")
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load config for profile: ${profile.id}", e)
                    null
                }
            }

            // 4. 构建导出数据
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val appVersionName = packageInfo.versionName ?: "Unknown"

            val exportData = ExportData(
                version = CURRENT_VERSION,
                exportTime = System.currentTimeMillis(),
                appVersion = appVersionName,
                settings = settings,
                profiles = profileExportDataList,
                activeProfileId = activeProfileId,
                activeNodeId = activeNodeId
            )

            // 5. 序列化为 JSON
            val jsonString = gson.toJson(exportData)

            Result.success(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            Result.failure(e)
        }
    }

    /**
     * 导出到文件
     * @param uri 目标文件 URI
     */
    suspend fun exportToFile(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val jsonResult = exportAllData()
            if (jsonResult.isFailure) {
                return@withContext Result.failure(jsonResult.exceptionOrNull() ?: Exception(context.getString(R.string.export_failed)))
            }

            val jsonString = jsonResult.getOrThrow()

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(jsonString.toByteArray(Charsets.UTF_8))
                outputStream.flush()
            } ?: throw Exception("Could not open file for writing")

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Export to file failed", e)
            Result.failure(e)
        }
    }

    /**
     * 验证导入数据
     * @param jsonData 导入的 JSON 字符串
     * @return 解析后的导出数据
     */
    suspend fun validateImportData(jsonData: String): Result<ExportData> = withContext(Dispatchers.IO) {
        try {
            val exportData = gson.fromJson(jsonData, ExportData::class.java)

            // 验证版本
            if (exportData.version > CURRENT_VERSION) {
                return@withContext Result.failure(
                    Exception("Data version too high (v${exportData.version}), please update app and try again")
                )
            }

            // 验证必要字段
            if (exportData.settings == null) {
                return@withContext Result.failure(Exception("Data format error: missing settings info"))
            }

            Result.success(exportData)
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "Invalid JSON format", e)
            Result.failure(Exception("Data format error, please check file validity"))
        } catch (e: Exception) {
            Log.e(TAG, "Validation failed", e)
            Result.failure(e)
        }
    }

    /**
     * 获取导入数据摘要
     * @param exportData 导出数据
     * @return 数据摘要
     */
    fun getExportDataSummary(exportData: ExportData): ExportDataSummary {
        val totalNodeCount = exportData.profiles.sumOf { profileData ->
            profileData.config.outbounds?.count { outbound ->
                outbound.type in listOf(
                    "shadowsocks", "vmess", "vless", "trojan",
                    "hysteria", "hysteria2", "tuic", "wireguard",
                    "shadowtls", "ssh", "anytls"
                )
            } ?: 0
        }

        return ExportDataSummary(
            version = exportData.version,
            exportTime = exportData.exportTime,
            appVersion = exportData.appVersion,
            profileCount = exportData.profiles.size,
            totalNodeCount = totalNodeCount,
            hasSettings = true,
            hasCustomRules = exportData.settings.customRules.isNotEmpty(),
            hasRuleSets = exportData.settings.ruleSets.isNotEmpty(),
            hasAppRules = exportData.settings.appRules.isNotEmpty() || exportData.settings.appGroups.isNotEmpty()
        )
    }

    /**
     * 导入数据
     * @param jsonData 导入的 JSON 字符串
     * @param options 导入选项
     * @return 导入结果
     */
    suspend fun importData(jsonData: String, options: ImportOptions = ImportOptions()): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            // 1. 验证数据
            val validateResult = validateImportData(jsonData)
            if (validateResult.isFailure) {
                return@withContext Result.failure(validateResult.exceptionOrNull()!!)
            }
            val exportData = validateResult.getOrThrow()

            var profilesImported = 0
            var nodesImported = 0
            var settingsImported = false
            val errors = mutableListOf<String>()

            // 2. 导入设置
            if (options.importSettings) {
                try {
                    importSettings(exportData.settings)
                    settingsImported = true

                    // 触发规则集下载
                    if (exportData.settings.ruleSets.isNotEmpty()) {
                        Log.i(TAG, "Triggering rule set download after import...")
                        // 使用 repositoryScope 替代 GlobalScope,避免内存泄漏
                        // 在后台启动下载任务，不阻塞导入流程
                        repositoryScope.launch {
                            try {
                                ruleSetRepository.ensureRuleSetsReady(forceUpdate = false, allowNetwork = true) {
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to download rule sets after import", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to import settings", e)
                    errors.add("Failed to import settings: ${e.message}")
                }
            }

            // 3. 导入配置和节点
            if (options.importProfiles) {
                for (profileData in exportData.profiles) {
                    try {
                        val nodeCount = importProfile(profileData, options.overwriteExisting)
                        profilesImported++
                        nodesImported += nodeCount
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to import profile: ${profileData.profile.name}", e)
                        errors.add("Profile '${profileData.profile.name}' import failed: ${e.message}")
                    }
                }
            }

            // 4. 恢复活跃状态
            if (options.importProfiles && exportData.activeProfileId != null) {
                try {
                    // 检查活跃配置是否存在
                    val profiles = configRepository.profiles.value
                    if (profiles.any { it.id == exportData.activeProfileId }) {
                        configRepository.setActiveProfile(
                            exportData.activeProfileId,
                            exportData.activeNodeId
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore active profile", e)
                }
            }

            // 5. 返回结果
            val result = when {
                errors.isEmpty() -> ImportResult.Success(
                    profilesImported = profilesImported,
                    nodesImported = nodesImported,
                    settingsImported = settingsImported
                )
                profilesImported > 0 || settingsImported -> ImportResult.PartialSuccess(
                    profilesImported = profilesImported,
                    profilesFailed = exportData.profiles.size - profilesImported,
                    errors = errors
                )
                else -> ImportResult.Failed(errors.joinToString("\n"))
            }

            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            Result.failure(e)
        }
    }

    /**
     * 从文件导入
     * @param uri 源文件 URI
     * @param options 导入选项
     */
    suspend fun importFromFile(uri: Uri, options: ImportOptions = ImportOptions()): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            val jsonData = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().readText()
            } ?: throw Exception("Could not read file")

            importData(jsonData, options)
        } catch (e: Exception) {
            Log.e(TAG, "Import from file failed", e)
            Result.failure(e)
        }
    }

    /**
     * 从文件验证数据（用于预览）
     */
    suspend fun validateFromFile(uri: Uri): Result<ExportData> = withContext(Dispatchers.IO) {
        try {
            val jsonData = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().readText()
            } ?: throw Exception("Could not read file")

            validateImportData(jsonData)
        } catch (e: Exception) {
            Log.e(TAG, "Validate from file failed", e)
            Result.failure(e)
        }
    }

    /**
     * 导入设置
     */
    private suspend fun importSettings(settings: AppSettings) {
        // 通用设置
        settingsRepository.setAutoConnect(settings.autoConnect)
        settingsRepository.setExcludeFromRecent(settings.excludeFromRecent)
        settingsRepository.setAppTheme(settings.appTheme)

        // TUN/VPN 设置
        settingsRepository.setTunEnabled(settings.tunEnabled)
        settingsRepository.setTunStack(settings.tunStack)
        settingsRepository.setTunMtu(settings.tunMtu)
        settingsRepository.setTunInterfaceName(settings.tunInterfaceName)
        settingsRepository.setAutoRoute(settings.autoRoute)
        settingsRepository.setStrictRoute(settings.strictRoute)
        settingsRepository.setEndpointIndependentNat(settings.endpointIndependentNat)
        settingsRepository.setVpnRouteMode(settings.vpnRouteMode)
        settingsRepository.setVpnRouteIncludeCidrs(settings.vpnRouteIncludeCidrs)
        settingsRepository.setVpnAppMode(settings.vpnAppMode)
        settingsRepository.setVpnAllowlist(settings.vpnAllowlist)
        settingsRepository.setVpnBlocklist(settings.vpnBlocklist)

        // DNS 设置
        settingsRepository.setLocalDns(settings.localDns)
        settingsRepository.setRemoteDns(settings.remoteDns)
        settingsRepository.setFakeDnsEnabled(settings.fakeDnsEnabled)
        settingsRepository.setFakeIpRange(settings.fakeIpRange)
        settingsRepository.setDnsStrategy(settings.dnsStrategy)
        settingsRepository.setRemoteDnsStrategy(settings.remoteDnsStrategy)
        settingsRepository.setDirectDnsStrategy(settings.directDnsStrategy)
        settingsRepository.setServerAddressStrategy(settings.serverAddressStrategy)
        settingsRepository.setDnsCacheEnabled(settings.dnsCacheEnabled)

        // 路由设置
        settingsRepository.setRoutingMode(settings.routingMode, notifyRestartRequired = false)
        settingsRepository.setDefaultRule(settings.defaultRule)
        settingsRepository.setBlockAds(settings.blockAds)
        settingsRepository.setBypassLan(settings.bypassLan)
        settingsRepository.setBlockQuic(settings.blockQuic)
        settingsRepository.setDebugLoggingEnabled(settings.debugLoggingEnabled)

        // 延迟测试设置
        settingsRepository.setLatencyTestMethod(settings.latencyTestMethod)
        settingsRepository.setLatencyTestUrl(settings.latencyTestUrl)

        // 镜像设置
        if (settings.ghProxyMirror != null) {
            settingsRepository.setGhProxyMirror(settings.ghProxyMirror)
        }

        // 代理端口设置
        settingsRepository.setProxyPort(settings.proxyPort)
        settingsRepository.setAllowLan(settings.allowLan)
        settingsRepository.setAppendHttpProxy(settings.appendHttpProxy)

        // 高级路由规则
        settingsRepository.setCustomRules(settings.customRules)
        settingsRepository.setRuleSets(settings.ruleSets, notify = false)
        settingsRepository.setAppRules(settings.appRules)
        settingsRepository.setAppGroups(settings.appGroups)

        // 规则集自动更新
        settingsRepository.setRuleSetAutoUpdateEnabled(settings.ruleSetAutoUpdateEnabled)
        settingsRepository.setRuleSetAutoUpdateInterval(settings.ruleSetAutoUpdateInterval)

        // 节点列表设置
        settingsRepository.setNodeFilter(settings.nodeFilter)
        settingsRepository.setNodeSortType(settings.nodeSortType)
        settingsRepository.setCustomNodeOrder(settings.customNodeOrder)
    }

    /**
     * 导入单个配置
     * @return 导入的节点数量
     */
    private suspend fun importProfile(profileData: ProfileExportData, overwrite: Boolean): Int {
        val profile = profileData.profile
        val config = profileData.config

        // 检查是否已存在同名或同ID的配置
        val existingProfiles = configRepository.profiles.value
        val existingById = existingProfiles.find { it.id == profile.id }
        val existingByName = existingProfiles.find { it.name == profile.name }

        if (existingById != null || existingByName != null) {
            if (!overwrite) {
                throw Exception("Profile already exists")
            }
            // 删除现有配置
            val existingId = existingById?.id ?: existingByName?.id
            if (existingId != null) {
                configRepository.deleteProfile(existingId)
            }
        }

        // 保存配置文件
        val configFile = File(configDir, "${profile.id}.json")
        configFile.writeText(gson.toJson(config))

        // 使用 ConfigRepository 直接导入 profile 到 Room 数据库
        val newProfile = profile.copy(
            id = profile.id,
            lastUpdated = System.currentTimeMillis(),
            updateStatus = UpdateStatus.Idle
        )

        // 直接调用 ConfigRepository 添加 profile
        configRepository.importProfileDirectly(newProfile, config)

        // 计算节点数量
        val nodeCount = config.outbounds?.count { outbound ->
            outbound.type in listOf(
                "shadowsocks", "vmess", "vless", "trojan",
                "hysteria", "hysteria2", "tuic", "wireguard",
                "shadowtls", "ssh", "anytls"
            )
        } ?: 0

        return nodeCount
    }

    /**
     * 清理资源，取消协程 scope
     *
     * 注意：由于 DataExportRepository 是单例且生命周期与 Application 相同，
     * 通常不需要手动调用此方法。此方法主要用于：
     * 1. 测试场景中清理资源
     * 2. 极端内存压力下的紧急清理
     */
    fun cleanup() {
        repositoryScope.cancel()
        Log.i(TAG, "DataExportRepository cleanup completed")
    }
}
