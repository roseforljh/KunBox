package com.kunk.singbox.model

import androidx.annotation.StringRes
import com.google.gson.annotations.SerializedName
import com.kunk.singbox.R

/**
 * 所有应用设置的数据模型
 */
data class AppSettings(
    // 通用设置
    @SerializedName("autoConnect") val autoConnect: Boolean = false,
    @SerializedName("excludeFromRecent") val excludeFromRecent: Boolean = false,
    @SerializedName("appTheme") val appTheme: AppThemeMode = AppThemeMode.SYSTEM,
    @SerializedName("appLanguage") val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    @SerializedName("showNotificationSpeed") val showNotificationSpeed: Boolean = true,
    
    // TUN/VPN 设置
    @SerializedName("tunEnabled") val tunEnabled: Boolean = true,
    @SerializedName("tunStack") val tunStack: TunStack = TunStack.MIXED,
    @SerializedName("tunMtu") val tunMtu: Int = 1280,  // 与 SettingsRepository 默认值保持一致
    @SerializedName("tunInterfaceName") val tunInterfaceName: String = "tun0",
    @SerializedName("autoRoute") val autoRoute: Boolean = false,
    @SerializedName("strictRoute") val strictRoute: Boolean = true,
    @SerializedName("endpointIndependentNat") val endpointIndependentNat: Boolean = false,
    @SerializedName("vpnRouteMode") val vpnRouteMode: VpnRouteMode = VpnRouteMode.GLOBAL,
    @SerializedName("vpnRouteIncludeCidrs") val vpnRouteIncludeCidrs: String = "",
    @SerializedName("vpnAppMode") val vpnAppMode: VpnAppMode = VpnAppMode.ALL,
    @SerializedName("vpnAllowlist") val vpnAllowlist: String = "",
    @SerializedName("vpnBlocklist") val vpnBlocklist: String = "",
    
    // 代理端口设置
    @SerializedName("proxyPort") val proxyPort: Int = 2080,
    @SerializedName("allowLan") val allowLan: Boolean = false,
    @SerializedName("appendHttpProxy") val appendHttpProxy: Boolean = false,
    
    // DNS 设置
    @SerializedName("localDns") val localDns: String = "223.5.5.5",
    @SerializedName("remoteDns") val remoteDns: String = "1.1.1.1",
    @SerializedName("fakeDnsEnabled") val fakeDnsEnabled: Boolean = true,
    @SerializedName("fakeIpRange") val fakeIpRange: String = "198.18.0.0/15",
    @SerializedName("dnsStrategy") val dnsStrategy: DnsStrategy = DnsStrategy.PREFER_IPV4,
    @SerializedName("remoteDnsStrategy") val remoteDnsStrategy: DnsStrategy = DnsStrategy.AUTO,
    @SerializedName("directDnsStrategy") val directDnsStrategy: DnsStrategy = DnsStrategy.AUTO,
    @SerializedName("serverAddressStrategy") val serverAddressStrategy: DnsStrategy = DnsStrategy.AUTO,
    @SerializedName("dnsCacheEnabled") val dnsCacheEnabled: Boolean = true,
    
    // 路由设置
    @SerializedName("routingMode") val routingMode: RoutingMode = RoutingMode.RULE,
    @SerializedName("defaultRule") val defaultRule: DefaultRule = DefaultRule.PROXY,
    @SerializedName("blockAds") val blockAds: Boolean = true,
    @SerializedName("bypassLan") val bypassLan: Boolean = true,
    @SerializedName("blockQuic") val blockQuic: Boolean = true,
    @SerializedName("debugLoggingEnabled") val debugLoggingEnabled: Boolean = false,
    
    // 连接重置设置 (参考 NekoBox)
    @SerializedName("networkChangeResetConnections") val networkChangeResetConnections: Boolean = true,
    @SerializedName("wakeResetConnections") val wakeResetConnections: Boolean = true,
    
    // 延迟测试设置
    @SerializedName("latencyTestMethod") val latencyTestMethod: LatencyTestMethod = LatencyTestMethod.REAL_RTT,
    @SerializedName("latencyTestUrl") val latencyTestUrl: String = "https://www.google.com/generate_204",
    @SerializedName("latencyTestTimeout") val latencyTestTimeout: Int = 5000, // 默认 5000ms (参考 v2rayNG/sing-box 的超时设置)
    @SerializedName("latencyTestConcurrency") val latencyTestConcurrency: Int = 10, // 批量测试并发数/每批大小
    
    // 镜像设置
    @SerializedName("ghProxyMirror") val ghProxyMirror: GhProxyMirror = GhProxyMirror.SAGERNET_ORIGIN,
    
    // 高级路由
    @SerializedName("customRules") val customRules: List<CustomRule> = emptyList(),
    @SerializedName("ruleSets") val ruleSets: List<RuleSet> = emptyList(),
    @SerializedName("appRules") val appRules: List<AppRule> = emptyList(),
    @SerializedName("appGroups") val appGroups: List<AppGroup> = emptyList(),
    
    // 规则集自动更新
    @SerializedName("ruleSetAutoUpdateEnabled") val ruleSetAutoUpdateEnabled: Boolean = false,
    @SerializedName("ruleSetAutoUpdateInterval") val ruleSetAutoUpdateInterval: Int = 60, // 分钟
    
    // 订阅更新超时设置
    @SerializedName("subscriptionUpdateTimeout") val subscriptionUpdateTimeout: Int = 30, // 秒，默认30秒

    // 节点列表设置
    @SerializedName("nodeFilter") val nodeFilter: NodeFilter = NodeFilter(),
    @SerializedName("nodeSortType") val nodeSortType: NodeSortType = NodeSortType.DEFAULT,
    @SerializedName("customNodeOrder") val customNodeOrder: List<String> = emptyList(),

    // 版本更新设置
    @SerializedName("autoCheckUpdate") val autoCheckUpdate: Boolean = true,

    // 后台省电设置
    @SerializedName("backgroundPowerSavingDelay") val backgroundPowerSavingDelay: BackgroundPowerSavingDelay = BackgroundPowerSavingDelay.MINUTES_30
)

enum class LatencyTestMethod(@StringRes val displayNameRes: Int) {
    @SerializedName("TCP") TCP(R.string.latency_test_tcp),
    @SerializedName("REAL_RTT") REAL_RTT(R.string.latency_test_rtt),
    @SerializedName("HANDSHAKE") HANDSHAKE(R.string.latency_test_handshake);
    
    companion object {
        fun fromDisplayName(name: String): LatencyTestMethod {
            // Deprecated: use enum name for storage
            return entries.find { it.name == name } ?: REAL_RTT
        }
    }
}

enum class TunStack(@StringRes val displayNameRes: Int) {
    @SerializedName("SYSTEM") SYSTEM(R.string.tun_stack_system),
    @SerializedName("GVISOR") GVISOR(R.string.tun_stack_gvisor),
    @SerializedName("MIXED") MIXED(R.string.tun_stack_mixed);
    
    companion object {
        fun fromDisplayName(name: String): TunStack {
            return entries.find { it.name == name } ?: SYSTEM
        }
    }
}

enum class VpnRouteMode(@StringRes val displayNameRes: Int) {
    @SerializedName("GLOBAL") GLOBAL(R.string.vpn_route_mode_global),
    @SerializedName("CUSTOM") CUSTOM(R.string.vpn_route_mode_custom);

    companion object {
        fun fromDisplayName(name: String): VpnRouteMode {
            return entries.find { it.name == name } ?: GLOBAL
        }
    }
}

enum class VpnAppMode(@StringRes val displayNameRes: Int) {
    @SerializedName("ALL") ALL(R.string.vpn_app_mode_all),
    @SerializedName("ALLOWLIST") ALLOWLIST(R.string.vpn_app_mode_allowlist);

    companion object {
        fun fromDisplayName(name: String): VpnAppMode {
            return entries.find { it.name == name } ?: ALL
        }
    }
}

enum class DnsStrategy(@StringRes val displayNameRes: Int) {
    @SerializedName("AUTO") AUTO(R.string.dns_strategy_auto),
    @SerializedName("PREFER_IPV4") PREFER_IPV4(R.string.dns_strategy_prefer_ipv4),
    @SerializedName("PREFER_IPV6") PREFER_IPV6(R.string.dns_strategy_prefer_ipv6),
    @SerializedName("ONLY_IPV4") ONLY_IPV4(R.string.dns_strategy_only_ipv4),
    @SerializedName("ONLY_IPV6") ONLY_IPV6(R.string.dns_strategy_only_ipv6);
    
    companion object {
        fun fromDisplayName(name: String): DnsStrategy {
            return entries.find { it.name == name } ?: AUTO
        }
    }
}

enum class RoutingMode(@StringRes val displayNameRes: Int) {
    @SerializedName("RULE") RULE(R.string.routing_mode_rule),
    @SerializedName("GLOBAL_PROXY") GLOBAL_PROXY(R.string.routing_mode_global_proxy),
    @SerializedName("GLOBAL_DIRECT") GLOBAL_DIRECT(R.string.routing_mode_global_direct);
    
    companion object {
        fun fromDisplayName(name: String): RoutingMode {
            return entries.find { it.name == name } ?: RULE
        }
    }
}

enum class DefaultRule(@StringRes val displayNameRes: Int) {
    @SerializedName("DIRECT") DIRECT(R.string.default_rule_direct),
    @SerializedName("PROXY") PROXY(R.string.default_rule_proxy),
    @SerializedName("BLOCK") BLOCK(R.string.default_rule_block);
    
    companion object {
        fun fromDisplayName(name: String): DefaultRule {
            return entries.find { it.name == name } ?: PROXY
        }
    }
}

enum class AppThemeMode(@StringRes val displayNameRes: Int) {
    @SerializedName("SYSTEM") SYSTEM(R.string.theme_system),
    @SerializedName("LIGHT") LIGHT(R.string.theme_light),
    @SerializedName("DARK") DARK(R.string.theme_dark);

    companion object {
        fun fromDisplayName(name: String): AppThemeMode {
            return entries.find { it.name == name } ?: SYSTEM
        }
    }
}

enum class AppLanguage(@StringRes val displayNameRes: Int, val localeCode: String) {
    @SerializedName("SYSTEM") SYSTEM(R.string.language_system, ""),
    @SerializedName("CHINESE") CHINESE(R.string.language_chinese, "zh"),
    @SerializedName("ENGLISH") ENGLISH(R.string.language_english, "en");

    companion object {
        fun fromLocaleCode(code: String): AppLanguage {
            return entries.find { it.localeCode == code } ?: SYSTEM
        }
        
        fun fromDisplayName(name: String): AppLanguage {
            return entries.find { it.name == name } ?: SYSTEM
        }
    }
}

enum class GhProxyMirror(val url: String, @StringRes val displayNameRes: Int) {
    @SerializedName("SAGERNET_ORIGIN") SAGERNET_ORIGIN("https://raw.githubusercontent.com/", R.string.gh_mirror_sagernet),
    @SerializedName("JSDELIVR_CDN") JSDELIVR_CDN("https://cdn.jsdelivr.net/gh/", R.string.gh_mirror_jsdelivr);
    
    companion object {
        fun fromUrl(url: String): GhProxyMirror {
            return entries.find { url.startsWith(it.url) } ?: SAGERNET_ORIGIN
        }
        
        fun fromDisplayName(name: String): GhProxyMirror {
            return entries.find { it.name == name } ?: SAGERNET_ORIGIN
        }
    }
}

enum class BackgroundPowerSavingDelay(val delayMs: Long, @StringRes val displayNameRes: Int) {
    @SerializedName("MINUTES_5") MINUTES_5(5 * 60 * 1000L, R.string.power_saving_delay_5min),
    @SerializedName("MINUTES_15") MINUTES_15(15 * 60 * 1000L, R.string.power_saving_delay_15min),
    @SerializedName("MINUTES_30") MINUTES_30(30 * 60 * 1000L, R.string.power_saving_delay_30min),
    @SerializedName("HOURS_1") HOURS_1(60 * 60 * 1000L, R.string.power_saving_delay_1hour),
    @SerializedName("HOURS_2") HOURS_2(2 * 60 * 60 * 1000L, R.string.power_saving_delay_2hours),
    @SerializedName("NEVER") NEVER(Long.MAX_VALUE, R.string.power_saving_delay_never);
    
    companion object {
        fun fromDisplayName(name: String): BackgroundPowerSavingDelay {
            return entries.find { it.name == name } ?: MINUTES_30
        }
    }
}

