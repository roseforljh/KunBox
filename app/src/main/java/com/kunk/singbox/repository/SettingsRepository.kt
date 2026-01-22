package com.kunk.singbox.repository

import android.content.Context
import android.util.Log
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.CustomRule
import com.kunk.singbox.model.DefaultRule
import com.kunk.singbox.model.DnsStrategy
import com.kunk.singbox.model.RoutingMode
import com.kunk.singbox.model.AppRule
import com.kunk.singbox.model.AppGroup
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.model.RuleSetType
import com.kunk.singbox.model.TunStack
import com.kunk.singbox.model.LatencyTestMethod
import com.kunk.singbox.model.VpnAppMode
import com.kunk.singbox.model.VpnRouteMode
import com.kunk.singbox.model.GhProxyMirror
import com.kunk.singbox.model.AppThemeMode
import com.kunk.singbox.model.AppLanguage
import com.kunk.singbox.model.NodeSortType
import com.kunk.singbox.model.NodeFilter
import com.kunk.singbox.model.BackgroundPowerSavingDelay
import com.kunk.singbox.repository.store.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 设置仓库 - 提供设置的读写接口
 *
 * 内部使用 SettingsStore (Room 数据库) 存储设置
 */
class SettingsRepository(private val context: Context) {

    private val settingsStore = SettingsStore.getInstance(context)

    fun getDefaultRuleSets(): List<RuleSet> {
        val geositeBase = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set"
        val geoipBase = "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set"
        return listOf(
            RuleSet(
                tag = "geosite-cn",
                type = RuleSetType.REMOTE,
                url = "$geositeBase/geosite-cn.srs",
                enabled = false,
                outboundMode = RuleSetOutboundMode.DIRECT
            ),
            RuleSet(
                tag = "geoip-cn",
                type = RuleSetType.REMOTE,
                url = "$geoipBase/geoip-cn.srs",
                enabled = false,
                outboundMode = RuleSetOutboundMode.DIRECT
            ),
            RuleSet(
                tag = "geosite-geolocation-!cn",
                type = RuleSetType.REMOTE,
                url = "$geositeBase/geosite-geolocation-!cn.srs",
                enabled = false,
                outboundMode = RuleSetOutboundMode.PROXY
            ),
            RuleSet(
                tag = "geosite-category-ads-all",
                type = RuleSetType.REMOTE,
                url = "$geositeBase/geosite-category-ads-all.srs",
                enabled = false,
                outboundMode = RuleSetOutboundMode.BLOCK
            ),
            RuleSet(
                tag = "geosite-private",
                type = RuleSetType.REMOTE,
                url = "$geositeBase/geosite-private.srs",
                enabled = false,
                outboundMode = RuleSetOutboundMode.DIRECT
            )
        )
    }

    /**
     * 设置 Flow - 直接从 SettingsStore 获取
     * 相比旧版每次都解析 JSON，性能提升显著
     */
    val settings: StateFlow<AppSettings> = settingsStore.settings

    // ==================== 通用设置 ====================

    suspend fun setAutoConnect(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(autoConnect = value) }
    }

    suspend fun setExcludeFromRecent(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(excludeFromRecent = value) }
    }

    suspend fun setAppTheme(value: AppThemeMode) {
        settingsStore.updateSettingsAndWait { it.copy(appTheme = value) }
    }

    suspend fun setAppLanguage(value: AppLanguage) {
        settingsStore.updateSettingsAndWait { it.copy(appLanguage = value) }
    }

    suspend fun setShowNotificationSpeed(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(showNotificationSpeed = value) }
    }

    // ==================== TUN/VPN 设置 ====================

    suspend fun setTunEnabled(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(tunEnabled = value) }
        notifyRestartRequired()
    }

    suspend fun setTunStack(value: TunStack) {
        settingsStore.updateSettingsAndWait { it.copy(tunStack = value) }
        notifyRestartRequired()
    }

    suspend fun setTunMtu(value: Int) {
        settingsStore.updateSettingsAndWait { it.copy(tunMtu = value) }
        notifyRestartRequired()
    }

    suspend fun setTunInterfaceName(value: String) {
        settingsStore.updateSettingsAndWait { it.copy(tunInterfaceName = value) }
        notifyRestartRequired()
    }

    suspend fun setAutoRoute(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(autoRoute = value) }
        notifyRestartRequired()
    }

    suspend fun setStrictRoute(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(strictRoute = value) }
        notifyRestartRequired()
    }

    suspend fun setEndpointIndependentNat(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(endpointIndependentNat = value) }
        notifyRestartRequired()
    }

    suspend fun setVpnRouteMode(value: VpnRouteMode) {
        settingsStore.updateSettingsAndWait { it.copy(vpnRouteMode = value) }
        notifyRestartRequired()
    }

    suspend fun setVpnRouteIncludeCidrs(value: String) {
        settingsStore.updateSettingsAndWait { it.copy(vpnRouteIncludeCidrs = value) }
        notifyRestartRequired()
    }

    suspend fun setVpnAppMode(value: VpnAppMode) {
        settingsStore.updateSettingsAndWait { it.copy(vpnAppMode = value) }
        notifyRestartRequired()
    }

    suspend fun setVpnAllowlist(value: String) {
        settingsStore.updateSettingsAndWait { it.copy(vpnAllowlist = value) }
        notifyRestartRequired()
    }

    suspend fun setVpnBlocklist(value: String) {
        settingsStore.updateSettingsAndWait { it.copy(vpnBlocklist = value) }
        notifyRestartRequired()
    }

    // ==================== DNS 设置 ====================

    suspend fun setLocalDns(value: String) {
        settingsStore.updateSettingsAndWait { it.copy(localDns = value) }
        notifyRestartRequired()
    }

    suspend fun setRemoteDns(value: String) {
        settingsStore.updateSettingsAndWait { it.copy(remoteDns = value) }
        notifyRestartRequired()
    }

    suspend fun setFakeDnsEnabled(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(fakeDnsEnabled = value) }
        notifyRestartRequired()
    }

    suspend fun setFakeIpRange(value: String) {
        settingsStore.updateSettingsAndWait { it.copy(fakeIpRange = value) }
        notifyRestartRequired()
    }

    suspend fun setDnsStrategy(value: DnsStrategy) {
        settingsStore.updateSettingsAndWait { it.copy(dnsStrategy = value) }
        notifyRestartRequired()
    }

    suspend fun setRemoteDnsStrategy(value: DnsStrategy) {
        settingsStore.updateSettingsAndWait { it.copy(remoteDnsStrategy = value) }
        notifyRestartRequired()
    }

    suspend fun setDirectDnsStrategy(value: DnsStrategy) {
        settingsStore.updateSettingsAndWait { it.copy(directDnsStrategy = value) }
        notifyRestartRequired()
    }

    suspend fun setServerAddressStrategy(value: DnsStrategy) {
        settingsStore.updateSettingsAndWait { it.copy(serverAddressStrategy = value) }
        notifyRestartRequired()
    }

    suspend fun setDnsCacheEnabled(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(dnsCacheEnabled = value) }
        notifyRestartRequired()
    }

    // ==================== 路由设置 ====================

    suspend fun setRoutingMode(value: RoutingMode, notifyRestartRequired: Boolean = true) {
        settingsStore.updateSettingsAndWait { it.copy(routingMode = value) }
        if (notifyRestartRequired) {
            notifyRestartRequired()
        }
    }

    suspend fun setDefaultRule(value: DefaultRule) {
        settingsStore.updateSettingsAndWait { it.copy(defaultRule = value) }
        notifyRestartRequired()
    }

    suspend fun setBlockAds(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(blockAds = value) }
        notifyRestartRequired()
    }

    suspend fun setBlockQuic(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(blockQuic = value) }
        notifyRestartRequired()
    }

    suspend fun setDebugLoggingEnabled(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(debugLoggingEnabled = value) }
        notifyRestartRequired()
    }

    suspend fun setLatencyTestMethod(value: LatencyTestMethod) {
        settingsStore.updateSettingsAndWait { it.copy(latencyTestMethod = value) }
    }

    suspend fun setLatencyTestUrl(value: String) {
        settingsStore.updateSettingsAndWait { it.copy(latencyTestUrl = value) }
    }

    suspend fun setLatencyTestTimeout(value: Int) {
        settingsStore.updateSettingsAndWait { it.copy(latencyTestTimeout = value) }
    }

    suspend fun setLatencyTestConcurrency(value: Int) {
        settingsStore.updateSettingsAndWait { it.copy(latencyTestConcurrency = value) }
    }

    suspend fun setBypassLan(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(bypassLan = value) }
        notifyRestartRequired()
    }

    suspend fun setNetworkChangeResetConnections(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(networkChangeResetConnections = value) }
    }

    suspend fun setWakeResetConnections(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(wakeResetConnections = value) }
    }

    suspend fun setGhProxyMirror(value: GhProxyMirror) {
        settingsStore.updateSettingsAndWait { it.copy(ghProxyMirror = value) }
        notifyRestartRequired()
    }

    suspend fun setProxyPort(value: Int) {
        settingsStore.updateSettingsAndWait { it.copy(proxyPort = value) }
        notifyRestartRequired()
    }

    suspend fun setAllowLan(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(allowLan = value) }
        notifyRestartRequired()
    }

    suspend fun setAppendHttpProxy(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(appendHttpProxy = value) }
        notifyRestartRequired()
    }

    // ==================== 高级路由 ====================

    suspend fun setCustomRules(value: List<CustomRule>) {
        settingsStore.updateSettingsAndWait { it.copy(customRules = value) }
        notifyRestartRequired()
    }

    suspend fun setRuleSets(value: List<RuleSet>, notify: Boolean = true) {
        settingsStore.updateSettingsAndWait { it.copy(ruleSets = value) }
        if (notify) {
            notifyRestartRequired()
        }
    }

    suspend fun getRuleSets(): List<RuleSet> {
        return settings.value.ruleSets
    }

    suspend fun setAppRules(value: List<AppRule>) {
        settingsStore.updateSettingsAndWait { it.copy(appRules = value) }
        notifyRestartRequired()
    }

    suspend fun setAppGroups(value: List<AppGroup>) {
        settingsStore.updateSettingsAndWait { it.copy(appGroups = value) }
        notifyRestartRequired()
    }

    // ==================== 规则集自动更新 ====================

    suspend fun setRuleSetAutoUpdateEnabled(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(ruleSetAutoUpdateEnabled = value) }
    }

    suspend fun setRuleSetAutoUpdateInterval(value: Int) {
        settingsStore.updateSettingsAndWait { it.copy(ruleSetAutoUpdateInterval = value) }
    }

    // ==================== 订阅更新超时 ====================

    suspend fun setSubscriptionUpdateTimeout(value: Int) {
        settingsStore.updateSettingsAndWait { it.copy(subscriptionUpdateTimeout = value) }
    }

    // ==================== 版本更新设置 ====================

    suspend fun setAutoCheckUpdate(value: Boolean) {
        settingsStore.updateSettingsAndWait { it.copy(autoCheckUpdate = value) }
    }

    // ==================== 后台省电设置 ====================

    suspend fun setBackgroundPowerSavingDelay(value: BackgroundPowerSavingDelay) {
        settingsStore.updateSettingsAndWait { it.copy(backgroundPowerSavingDelay = value) }
    }

    // ==================== 节点列表设置 ====================

    suspend fun setNodeFilter(value: NodeFilter) {
        settingsStore.updateSettingsAndWait { it.copy(nodeFilter = value) }
    }

    suspend fun getNodeFilter(): NodeFilter {
        return settings.value.nodeFilter
    }

    fun getNodeFilterFlow(): Flow<NodeFilter> {
        return settings.map { it.nodeFilter }
    }

    suspend fun setNodeSortType(sortType: NodeSortType) {
        settingsStore.updateSettingsAndWait { it.copy(nodeSortType = sortType) }
    }

    fun getNodeSortType(): Flow<NodeSortType> {
        return settings.map { it.nodeSortType }
    }

    suspend fun setCustomNodeOrder(nodeIds: List<String>) {
        settingsStore.updateSettingsAndWait { it.copy(customNodeOrder = nodeIds) }
    }

    fun getCustomNodeOrder(): Flow<List<String>> {
        return settings.map { it.customNodeOrder }
    }

    // ==================== 迁移检查 ====================

    suspend fun checkAndMigrateRuleSets() {
        try {
            val currentSettings = settings.value

            // 初始化默认规则集
            if (currentSettings.ruleSets.isEmpty()) {
                Log.i("SettingsRepository", "Initializing default rule sets")
                setRuleSets(getDefaultRuleSets(), notify = false)
                return
            }

            val currentMirrorUrl = currentSettings.ghProxyMirror.url
            val rawPrefix = "https://raw.githubusercontent.com/"
            val cdnPrefix = "https://cdn.jsdelivr.net/gh/"

            val migratedRuleSets = currentSettings.ruleSets.map { ruleSet ->
                var updatedUrl = ruleSet.url
                var updatedTag = ruleSet.tag

                // 修复旧标签
                if (updatedTag.equals("geosite-ads", ignoreCase = true)) {
                    updatedTag = "geosite-category-ads-all"
                }

                // 修复旧 URL
                if (updatedUrl.contains("geosite-ads.srs")) {
                    updatedUrl = updatedUrl.replace("geosite-ads.srs", "geosite-category-ads-all.srs")
                }

                // 还原到原始 URL
                var rawUrl = updatedUrl
                if (rawUrl.startsWith(cdnPrefix)) {
                    val path = rawUrl.removePrefix(cdnPrefix)
                    val parts = path.split("@", limit = 2)
                    if (parts.size == 2) {
                        val userRepo = parts[0]
                        val branchPath = parts[1]
                        rawUrl = "$rawPrefix$userRepo/$branchPath"
                    }
                } else {
                    val oldMirrors = listOf(
                        "https://ghp.ci/",
                        "https://mirror.ghproxy.com/",
                        "https://ghproxy.com/",
                        "https://ghproxy.net/",
                        "https://ghfast.top/",
                        "https://gh-proxy.com/"
                    )
                    for (mirror in oldMirrors) {
                        if (rawUrl.startsWith(mirror)) {
                            rawUrl = rawUrl.replace(mirror, rawPrefix)
                        }
                    }
                }

                // 应用当前镜像
                if (currentMirrorUrl.contains("cdn.jsdelivr.net")) {
                    if (rawUrl.startsWith(rawPrefix)) {
                        val path = rawUrl.removePrefix(rawPrefix)
                        val parts = path.split("/", limit = 4)
                        if (parts.size >= 4) {
                            val user = parts[0]
                            val repo = parts[1]
                            val branch = parts[2]
                            val filePath = parts[3]
                            updatedUrl = "$cdnPrefix$user/$repo@$branch/$filePath"
                        }
                    }
                } else if (currentMirrorUrl != rawPrefix) {
                    if (rawUrl.startsWith(rawPrefix)) {
                        updatedUrl = rawUrl.replace(rawPrefix, currentMirrorUrl)
                    }
                } else {
                    updatedUrl = rawUrl
                }

                if (updatedUrl != ruleSet.url || updatedTag != ruleSet.tag) {
                    ruleSet.copy(tag = updatedTag, url = updatedUrl)
                } else {
                    ruleSet
                }
            }.distinctBy { it.tag }

            if (migratedRuleSets != currentSettings.ruleSets) {
                Log.i("SettingsRepository", "Saving migrated rule sets")
                setRuleSets(migratedRuleSets, notify = false)
            }
        } catch (e: Exception) {
            Log.e("SettingsRepository", "Error during migration", e)
        }
    }

    private fun notifyRestartRequired() {
        _restartRequiredEvents.tryEmit(Unit)
    }

    companion object {
        private val _restartRequiredEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val restartRequiredEvents: SharedFlow<Unit> = _restartRequiredEvents.asSharedFlow()

        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
