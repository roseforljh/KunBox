package com.kunk.singbox.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.CustomRule
import com.kunk.singbox.model.DefaultRule
import com.kunk.singbox.model.Inbound
import com.kunk.singbox.model.OutboundTag
import com.kunk.singbox.model.RoutingMode
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.model.RuleType

object LocalNetworkPermission {
    const val ACCESS_LOCAL_NETWORK = Manifest.permission.ACCESS_LOCAL_NETWORK
    const val ANDROID_17 = 37
    const val ANY_LISTEN = "0.0.0.0"
    const val LOOPBACK_LISTEN = "127.0.0.1"
    const val MISSING_PERMISSION_ERROR = "Local network permission is required to allow LAN connections"

    fun requiredPermissions(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> {
        return if (requiresPermission(sdkInt)) arrayOf(ACCESS_LOCAL_NETWORK) else emptyArray()
    }

    fun hasPermission(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        if (!requiresPermission(sdkInt)) return true
        return ContextCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
    }

    fun canExposeLan(
        context: Context,
        allowLan: Boolean,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean = canExposeLan(allowLan, sdkInt, hasPermission(context, sdkInt))

    fun canApplySettings(
        context: Context,
        settings: AppSettings,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean {
        return !requiresLocalNetworkAccess(settings) || hasPermission(context, sdkInt)
    }

    fun requiresLocalNetworkAccess(settings: AppSettings): Boolean {
        if (settings.allowLan || settings.bypassLan) return true
        if (settings.routingMode == RoutingMode.GLOBAL_DIRECT) return true
        if (settings.routingMode == RoutingMode.RULE && settings.defaultRule == DefaultRule.DIRECT) return true
        if (settings.customRules.any { it.enabled && customRuleRequiresLocalNetworkAccess(it) }) return true
        if (settings.ruleSets.any { it.enabled && it.outboundMode == RuleSetOutboundMode.DIRECT }) return true
        if (settings.appRules.any { it.enabled && it.outboundMode == RuleSetOutboundMode.DIRECT }) return true
        return settings.appGroups.any {
            it.enabled && (it.outboundMode == null || it.outboundMode == RuleSetOutboundMode.DIRECT)
        }
    }

    fun shouldRestrictLanListen(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        return shouldRestrictLanListen(sdkInt, hasPermission(context, sdkInt))
    }

    fun restrictInboundListen(inbound: Inbound): Inbound {
        if (inbound.type == "tun" || inbound.listenPort == null || !isWildcardListen(inbound.listen)) {
            return inbound
        }
        return inbound.copy(listen = LOOPBACK_LISTEN)
    }

    fun isWildcardListen(listen: String?): Boolean {
        val normalized = listen.orEmpty().trim().lowercase()
        return normalized.isEmpty() ||
            normalized == ANY_LISTEN ||
            normalized == "::" ||
            normalized == "[::]" ||
            normalized == "0:0:0:0:0:0:0:0"
    }

    internal fun requiresPermission(sdkInt: Int): Boolean = sdkInt >= ANDROID_17

    internal fun canExposeLan(
        allowLan: Boolean,
        sdkInt: Int,
        localNetworkPermissionGranted: Boolean
    ): Boolean {
        return !allowLan || !requiresPermission(sdkInt) || localNetworkPermissionGranted
    }

    internal fun shouldRestrictLanListen(sdkInt: Int, localNetworkPermissionGranted: Boolean): Boolean {
        return requiresPermission(sdkInt) && !localNetworkPermissionGranted
    }

    private fun customRuleRequiresLocalNetworkAccess(rule: CustomRule): Boolean {
        val isDirect = rule.outboundMode?.let { it == RuleSetOutboundMode.DIRECT }
            ?: (rule.outbound == OutboundTag.DIRECT)
        if (!isDirect) return false
        return when (rule.type) {
            RuleType.IP_CIDR -> splitRuleValues(rule.value).any(::looksLikeLocalAddress)
            RuleType.DOMAIN,
            RuleType.DOMAIN_SUFFIX,
            RuleType.DOMAIN_KEYWORD -> splitRuleValues(rule.value).any(::looksLikeLocalDomain)
            else -> false
        }
    }

    private fun splitRuleValues(value: String): List<String> {
        return value
            .split('\n', '\r', ',', ';')
            .map { it.trim().trimStart('=').trim('*') }
            .filter { it.isNotBlank() }
    }

    private fun looksLikeLocalDomain(value: String): Boolean {
        val normalized = value.trim('.').lowercase()
        return normalized == "localhost" ||
            normalized == "local" ||
            normalized.endsWith(".local") ||
            normalized == "lan" ||
            normalized.endsWith(".lan") ||
            normalized == "localdomain" ||
            normalized.endsWith(".localdomain") ||
            normalized == "arpa" ||
            normalized.endsWith(".arpa")
    }

    private fun looksLikeLocalAddress(value: String): Boolean {
        val host = value
            .substringBefore("/")
            .trim()
            .trim('[', ']')
            .lowercase()
        val secondIpv4Octet = host.split(".").getOrNull(1)?.toIntOrNull()
        return host == "localhost" ||
            host == "::1" ||
            host == "0:0:0:0:0:0:0:1" ||
            host.startsWith("127.") ||
            host.startsWith("10.") ||
            host.startsWith("192.168.") ||
            host.startsWith("169.254.") ||
            (host.startsWith("172.") && secondIpv4Octet in 16..31) ||
            host.startsWith("fc") ||
            host.startsWith("fd") ||
            host.startsWith("fe80:")
    }
}
