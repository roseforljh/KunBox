package com.kunk.singbox.model

import androidx.annotation.StringRes
import com.google.gson.annotations.SerializedName
import com.kunk.singbox.R
import java.net.InetAddress
import java.net.URI

data class AppSettings(

    @SerializedName("autoConnect") val autoConnect: Boolean = false,
    @SerializedName("networkAutoSwitchEnabled") val networkAutoSwitchEnabled: Boolean = false,
    @SerializedName("trustedWifiSsids") val trustedWifiSsids: String = "",
    @SerializedName("excludeFromRecent") val excludeFromRecent: Boolean = false,
    @SerializedName("appTheme") val appTheme: AppThemeMode = AppThemeMode.SYSTEM,
    @SerializedName("appThemeStyle") val appThemeStyle: AppThemeStyle = AppThemeStyle.DEFAULT,
    @SerializedName("appLanguage") val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    @SerializedName("showNotificationSpeed") val showNotificationSpeed: Boolean = true,

    @SerializedName("tunEnabled") val tunEnabled: Boolean = true,
    @SerializedName("tunStack") val tunStack: TunStack = TunStack.MIXED,
    @SerializedName("ipVersionMode") val ipVersionMode: IpVersionMode = IpVersionMode.DUAL_STACK,
    // Throughput defaults:
    // - 1280 is IPv6 minimum MTU, safe but often reduces throughput.
    @SerializedName("tunMtu") val tunMtu: Int = 1500,
    // Higher MTU for QUIC-based proxies (Hysteria2/TUIC) to avoid fragmentation blackholes.
    // Note: For existing installs, Gson may deserialize missing boolean fields as false.
    @SerializedName("tunMtuAuto") val tunMtuAuto: Boolean = true,
    @SerializedName("autoRoute") val autoRoute: Boolean = false,
    @SerializedName("strictRoute") val strictRoute: Boolean = true,
    @SerializedName("vpnRouteMode") val vpnRouteMode: VpnRouteMode = VpnRouteMode.GLOBAL,
    @SerializedName("vpnRouteIncludeCidrs") val vpnRouteIncludeCidrs: String = "",
    @SerializedName("vpnAppMode") val vpnAppMode: VpnAppMode = VpnAppMode.ALL,
    @SerializedName("vpnAllowlist") val vpnAllowlist: String = "",
    @SerializedName("vpnBlocklist") val vpnBlocklist: String = "",

    @SerializedName("proxyPort") val proxyPort: Int = 2080,
    @SerializedName("allowLan") val allowLan: Boolean = false,
    @SerializedName("appendHttpProxy") val appendHttpProxy: Boolean = false,

    @SerializedName("localDns") val localDns: String = DEFAULT_LOCAL_DNS,

    @SerializedName("remoteDns") val remoteDns: String = DEFAULT_REMOTE_DNS,
    @SerializedName("fakeDnsEnabled") val fakeDnsEnabled: Boolean = false,
    @SerializedName("fakeIpRange") val fakeIpRange: String = DEFAULT_FAKE_IP_RANGE,
    @SerializedName("fakeIpExcludeDomains") val fakeIpExcludeDomains: String = "",
    @SerializedName("fakeDnsExcludedDomains") val fakeDnsExcludedDomains: String = "",
    @SerializedName("dnsStrategy") val dnsStrategy: DnsStrategy = DnsStrategy.PREFER_IPV4,
    @SerializedName("remoteDnsStrategy") val remoteDnsStrategy: DnsStrategy = DnsStrategy.AUTO,
    @SerializedName("directDnsStrategy") val directDnsStrategy: DnsStrategy = DnsStrategy.AUTO,
    @SerializedName("serverAddressStrategy") val serverAddressStrategy: DnsStrategy = DnsStrategy.AUTO,
    @SerializedName("dnsCacheEnabled") val dnsCacheEnabled: Boolean = true,

    @SerializedName("routingMode") val routingMode: RoutingMode = RoutingMode.RULE,
    @SerializedName("defaultRule") val defaultRule: DefaultRule = DefaultRule.PROXY,
    @SerializedName("bypassLan") val bypassLan: Boolean = true,
    @SerializedName("icmpEchoRoutingEnabled") val icmpEchoRoutingEnabled: Boolean = false,
    // Throughput default: allow QUIC/HTTP3; users can enable blocking if their network/ISP has QUIC issues.
    @SerializedName("blockQuic") val blockQuic: Boolean = false,
    @SerializedName("debugLoggingEnabled") val debugLoggingEnabled: Boolean = false,

    @SerializedName("tcpKeepAliveEnabled") val tcpKeepAliveEnabled: Boolean = true,

    @SerializedName("tcpKeepAliveInterval") val tcpKeepAliveInterval: Int = 15,

    @SerializedName("connectTimeout") val connectTimeout: Int = 10,

    @SerializedName("latencyTestMethod") val latencyTestMethod: LatencyTestMethod = LatencyTestMethod.REAL_RTT,
    @SerializedName("latencyTestUrl") val latencyTestUrl: String = DEFAULT_LATENCY_TEST_URL,
    @SerializedName("latencyTestTimeout") val latencyTestTimeout: Int = 5000,
    @SerializedName("latencyTestConcurrency") val latencyTestConcurrency: Int = 5,

    @SerializedName("ghProxyMirror") val ghProxyMirror: GhProxyMirror = GhProxyMirror.SAGERNET_ORIGIN,

    @SerializedName("customRules") val customRules: List<CustomRule> = emptyList(),
    @SerializedName("ruleSets") val ruleSets: List<RuleSet> = emptyList(),
    @SerializedName("appRules") val appRules: List<AppRule> = emptyList(),
    @SerializedName("appGroups") val appGroups: List<AppGroup> = emptyList(),

    @SerializedName("ruleSetAutoUpdateEnabled") val ruleSetAutoUpdateEnabled: Boolean = false,
    @SerializedName("ruleSetAutoUpdateInterval") val ruleSetAutoUpdateInterval: Int = 60, // 分钟

    @SerializedName("subscriptionUpdateTimeout") val subscriptionUpdateTimeout: Int = 30,

    @SerializedName("nodeFilter") val nodeFilter: NodeFilter = NodeFilter(),
    @SerializedName("nodeSortType") val nodeSortType: NodeSortType = NodeSortType.DEFAULT,
    @SerializedName("customNodeOrder") val customNodeOrder: List<String> = emptyList(),

    @SerializedName("autoCheckUpdate") val autoCheckUpdate: Boolean = true,

    @SerializedName("backgroundPowerSavingDelay") val backgroundPowerSavingDelay: BackgroundPowerSavingDelay = BackgroundPowerSavingDelay.MINUTES_30,

    @SerializedName("nodeColumnCount") val nodeColumnCount: Int = 1
) {
    companion object {
        const val DEFAULT_LOCAL_DNS = "https://223.5.5.5/dns-query"
        const val DEFAULT_REMOTE_DNS = "https://1.1.1.1/dns-query"
        const val DEFAULT_LATENCY_TEST_URL = "https://www.gstatic.com/generate_204"
        const val DEFAULT_FAKE_IP_RANGE = "198.18.0.0/15,fc00::/18"
        const val LEGACY_LOCAL_DNS = "local"
        const val LEGACY_DOMAIN_LOCAL_DNS = "https://dns.alidns.com/dns-query"
        const val DEFAULT_FAKE_DNS_EXCLUDED_DOMAINS = "accounts.google.com\noauth.googleusercontent.com\n" +
            "appleid.apple.com\nidmsa.apple.com\nlogin.microsoftonline.com\nlogin.live.com\n" +
            "lan\nlocal\nlocalhost\nlocaldomain\narpa"

        fun validateLatencyTestUrl(value: String?): String? {
            val candidate = value?.trim().orEmpty()
            if (candidate.isEmpty()) return null

            return runCatching {
                val uri = URI(candidate)
                require(
                    uri.scheme.equals("http", ignoreCase = true) ||
                        uri.scheme.equals("https", ignoreCase = true)
                )
                require(!uri.host.isNullOrBlank())
                require(uri.userInfo == null)
                require(uri.port == -1 || uri.port in 1..65535)
                require(!isUnsafeLatencyTestHost(uri.host))
                uri.toString()
            }.getOrNull()
        }

        fun normalizeLatencyTestUrl(value: String?): String =
            validateLatencyTestUrl(value) ?: DEFAULT_LATENCY_TEST_URL

        fun requireLatencyTestUrl(value: String?): String =
            requireNotNull(validateLatencyTestUrl(value)) { "Invalid latency test URL" }

        fun latencyTestUri(value: String?): URI = URI(requireLatencyTestUrl(value))

        private fun isUnsafeLatencyTestHost(rawHost: String): Boolean {
            val host = rawHost.removePrefix("[").removeSuffix("]")
            val isLocalhost = host.equals("localhost", ignoreCase = true) ||
                host.endsWith(".localhost", ignoreCase = true)
            val ipv4 = parseIpv4Host(host)
            return isLocalhost || (ipv4?.let(::isUnsafeIpv4Host) ?: isUnsafeIpv6Host(host))
        }

        private fun parseIpv4Host(host: String): List<Int>? {
            val parts = host.split('.')
            if (parts.size != 4) return null
            return parts.mapNotNull(String::toIntOrNull).takeIf { values ->
                values.size == 4 && values.all { it in 0..255 }
            }
        }

        private fun isUnsafeIpv4Host(parts: List<Int>): Boolean {
            val first = parts[0]
            val second = parts[1]
            return first == 0 || first == 10 || first == 127 || first >= 224 ||
                (first == 169 && second == 254) ||
                (first == 172 && second in 16..31) ||
                (first == 192 && second == 168)
        }

        private fun isUnsafeIpv6Host(host: String): Boolean {
            if (!host.contains(':')) return false
            val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return true
            return address.isAnyLocalAddress || address.isLoopbackAddress ||
                address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress
        }
    }
}

enum class LatencyTestMethod(@StringRes val displayNameRes: Int) {
    @SerializedName("TCP") TCP(R.string.latency_test_tcp),
    @SerializedName("REAL_RTT") REAL_RTT(R.string.latency_test_rtt),
    @SerializedName("HANDSHAKE") HANDSHAKE(R.string.latency_test_handshake),
    @SerializedName("URL_TEST") URL_TEST(R.string.latency_test_url_test);

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

enum class IpVersionMode(@StringRes val displayNameRes: Int) {
    @SerializedName("IPV4_ONLY") IPV4_ONLY(R.string.ip_version_mode_ipv4_only),
    @SerializedName("DUAL_STACK") DUAL_STACK(R.string.ip_version_mode_dual_stack),
    @SerializedName("PREFER_IPV6") PREFER_IPV6(R.string.ip_version_mode_prefer_ipv6),
    @SerializedName("IPV6_ONLY") IPV6_ONLY(R.string.ip_version_mode_ipv6_only);

    companion object {
        fun fromDisplayName(name: String): IpVersionMode {
            return entries.find { it.name == name } ?: DUAL_STACK
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
    @SerializedName("ALLOWLIST") ALLOWLIST(R.string.vpn_app_mode_allowlist),
    @SerializedName("BLOCKLIST") BLOCKLIST(R.string.vpn_app_mode_blocklist);

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

enum class AppThemeStyle(@StringRes val displayNameRes: Int) {
    @SerializedName("DEFAULT") DEFAULT(R.string.theme_style_default),
    @SerializedName("LIQUID_GLASS") LIQUID_GLASS(R.string.theme_style_liquid_glass);

    companion object {
        fun fromDisplayName(name: String): AppThemeStyle {
            return entries.find { it.name == name } ?: DEFAULT
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
