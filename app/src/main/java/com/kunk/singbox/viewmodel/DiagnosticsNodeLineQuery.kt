package com.kunk.singbox.viewmodel

import android.app.Application
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.kunk.singbox.R
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.DnsServer
import com.kunk.singbox.model.RouteRule
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.repository.*
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.utils.NetworkClient
import kotlinx.coroutines.flow.first
import okhttp3.Request

internal class DiagnosticsNodeLineQueryRunner(
    private val application: Application,
    private val configRepository: ConfigRepository,
    private val settingsRepository: SettingsRepository
) {
    private val gson = Gson()

    suspend fun buildReport(): String {
        val activeLabel = SingBoxRemote.activeLabel.value.trim()
        val storedActiveLabel = VpnStateStore.getActiveLabel().trim()
        val activeNodeId = configRepository.activeNodeId.first()
        val activeLabelNode = activeLabel.takeIf { it.isNotBlank() }?.let(configRepository::getNodeByName)
        val storedActiveLabelNode = storedActiveLabel.takeIf { it.isNotBlank() }?.let(configRepository::getNodeByName)
        val fallbackNode = activeNodeId?.let(configRepository::getNodeById)
        val nodeResolution = resolveNodeLineTarget(
            activeLabelNode = activeLabelNode,
            storedActiveLabelNode = storedActiveLabelNode,
            fallbackNode = fallbackNode
        ) ?: return buildUnavailableMessage()

        val settings = settingsRepository.settings.first()
        val coreActive = VpnStateStore.getActive()
        val outbound = configRepository.getOutboundByNodeId(nodeResolution.node.id)
        val queryData = NodeLineQueryData(
            nodeResolution = nodeResolution,
            activeLabel = activeLabel,
            storedActiveLabel = storedActiveLabel,
            outbound = outbound,
            delay = queryNodeDelay(
                node = nodeResolution.node
            ),
            exitPortrait = queryExitPortrait(coreActive = coreActive, proxyPort = settings.proxyPort),
            coreActive = coreActive,
            proxyPort = settings.proxyPort,
            hasSignalConflict = activeLabelNode != null &&
                fallbackNode != null &&
                activeLabelNode.id != fallbackNode.id
        )

        return buildNodeLineQueryMessage(
            data = queryData,
            unknownText = application.getString(R.string.common_unknown)
        )
    }

    private fun buildUnavailableMessage(): String {
        return buildString {
            appendLine("当前没有可识别的活动节点。")
            appendLine()
            appendLine("说明:")
            appendLine("- 该结果依赖当前运行态标签或已选中的全局节点。")
            appendLine("- 如果刚切换配置或节点，请稍后再试。")
        }
    }

    private suspend fun queryNodeDelay(node: NodeUi): Int? {
        val latency = configRepository.testNodeLatency(node.id)
        return latency.takeIf { it > 0L && it <= Int.MAX_VALUE }?.toInt()
    }

    private fun queryExitPortrait(coreActive: Boolean, proxyPort: Int): NodeExitPortrait? {
        if (!coreActive || proxyPort <= 0) return null
        val request = Request.Builder()
            .url(EXIT_PORTRAIT_ENDPOINT)
            .header("Accept", "application/json")
            .build()

        return runCatching {
            NetworkClient.createClientWithProxy(
                proxyPort = proxyPort,
                connectTimeoutSeconds = 8,
                readTimeoutSeconds = 8,
                writeTimeoutSeconds = 8,
                callTimeoutSeconds = 12
            ).newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body.string()
                if (body.isBlank()) return@use null
                gson.fromJson(body, IpSbGeoIpResponse::class.java)?.toExitPortrait(
                    unknownText = application.getString(R.string.common_unknown)
                )
            }
        }.getOrNull()
    }
}

private const val EXIT_PORTRAIT_ENDPOINT = "https://api.ip.sb/geoip"

internal fun buildDnsQuerySuccessMessage(host: String, addresses: List<String>): String {
    val ipList = addresses.joinToString("\n")
    return "Domain: $host\n\n" +
        "Result:\n$ipList\n\n" +
        "Note: System DNS only. KunBox app is excluded from VPN, so this does not represent current node DNS."
}

internal fun buildDnsQueryFailureMessage(host: String, errorMessage: String?): String {
    return "Domain: $host\n\n" +
        "Failed: ${errorMessage ?: "unknown"}\n\n" +
        "Note: System DNS only. KunBox app is excluded from VPN, so this does not represent current node DNS."
}

internal fun buildDnsLeakCheckReport(coreActive: Boolean, runConfig: SingBoxConfig?): String {
    if (!coreActive) {
        return buildString {
            appendLine("DNS 静态风险: 存在")
            appendLine()
            appendLine("原因:")
            appendLine("- 核心未运行，当前连接未被 KunBox VPN 接管。")
        }
    }
    if (runConfig == null) {
        return buildString {
            appendLine("DNS 静态风险: 存在")
            appendLine()
            appendLine("原因:")
            appendLine("- 无法读取运行配置，不能确认 DNS 已被接管。")
        }
    }

    val reasons = buildDnsLeakReasons(runConfig)
    return buildString {
        appendLine("DNS 静态风险: ${if (reasons.isEmpty()) "未发现明显配置缺口" else "存在"}")
        appendLine()
        if (reasons.isEmpty()) {
            appendLine("检查结果:")
            appendLine("- 运行配置包含 TUN 入站。")
            appendLine("- route.rules 已包含覆盖 tun-in:53 或 protocol=dns 的 hijack-dns。")
            appendLine("- DNS final 指向有效且安全的 server tag。")
            appendLine("- 未发现系统 DNS 或明文直连 DNS server。")
            appendLine("- 此结果仅检查静态配置，不能替代设备抓包或证明不存在 DoH、IPv6、分应用绕过。")
        } else {
            appendLine("原因:")
            reasons.forEach { appendLine("- $it") }
        }
    }
}

private fun buildDnsLeakReasons(runConfig: SingBoxConfig): List<String> {
    val reasons = mutableListOf<String>()
    val tunInbound = runConfig.inbounds.orEmpty().firstOrNull { it.type == "tun" }
    if (tunInbound == null) {
        reasons.add("运行配置缺少 TUN 入站，系统 DNS 流量不会进入 KunBox VPN。")
    }

    val routeRules = runConfig.route?.rules.orEmpty()
    if (!routeRules.any { it.isEffectiveDnsHijackRule() }) {
        reasons.add("运行配置缺少覆盖 tun-in:53 或 protocol=dns 的 DNS 劫持规则。")
    }
    if (!routeRules.any { it.isPort853BlockRule() }) {
        reasons.add("运行配置缺少 port 853 拦截规则，Android Private DNS (DoT) 可绕过 DNS 劫持。")
    }

    val dns = runConfig.dns
    val servers = dns?.servers.orEmpty()
    val finalServer = dns?.finalServer.orEmpty()
    val serversByTag = servers.mapNotNull { server -> server.tag?.let { it to server } }.toMap()
    if (finalServer.isBlank()) {
        reasons.add("DNS final 为空，最终 DNS 路径不明确。")
    } else {
        val finalDnsServer = serversByTag[finalServer]
        if (finalDnsServer == null) {
            reasons.add("DNS final 指向 $finalServer，但 servers 中不存在该 tag。")
        } else if (finalDnsServer.unsafeDnsReason(serversByTag, runConfig.outbounds.orEmpty()) != null) {
            reasons.add("DNS final 指向不安全服务器 $finalServer。")
        }
    }

    servers.mapNotNull { server -> server.unsafeDnsReason(serversByTag, runConfig.outbounds.orEmpty()) }
        .forEach { reasons.add(it) }
    return reasons.distinct()
}

private fun RouteRule.isEffectiveDnsHijackRule(): Boolean {
    if (action != "hijack-dns") return false
    val coversTunPort53 = inbound.orEmpty().contains("tun-in") && port.orEmpty().contains(53)
    val coversDnsProtocol = protocol.orEmpty().any { it.equals("dns", ignoreCase = true) }
    return coversTunPort53 || coversDnsProtocol
}

private fun RouteRule.isPort853BlockRule(): Boolean {
    return port.orEmpty().contains(853) && action == "reject"
}

private fun DnsServer.unsafeDnsReason(serversByTag: Map<String, DnsServer>, outbounds: List<Outbound>): String? {
    val label = tag ?: "(untagged)"
    val normalizedType = type?.lowercase().orEmpty()
    if (normalizedType == "fakeip") return null
    val target = server ?: address ?: "local"
    return when {
        normalizedType == "local" || normalizedType == "dhcp" -> {
            "DNS 服务器 $label 使用系统 DNS 类型 $normalizedType。"
        }
        normalizedType == "udp" || normalizedType == "tcp" || normalizedType.isBlank() -> {
            unsafePlainDnsReason(label, normalizedType, target, serversByTag, outbounds)
        }
        target.equals("local", ignoreCase = true) -> {
            "DNS 服务器 $label 指向系统 DNS local。"
        }
        else -> null
    }
}

private fun DnsServer.unsafePlainDnsReason(
    label: String,
    normalizedType: String,
    target: String,
    serversByTag: Map<String, DnsServer>,
    outbounds: List<Outbound>
): String? {
    val dnsType = normalizedType.ifBlank { "legacy" }
    val detourTag = detour?.takeIf { it.isNotBlank() }
    val outbound = detourTag?.let { tag -> outbounds.firstOrNull { it.tag == tag } }
    return when {
        detourTag == null -> "DNS 服务器 $label 使用明文直连 DNS $dnsType://$target。"
        serversByTag.containsKey(detourTag) -> "DNS 服务器 $label detour 指向 DNS server $detourTag，不是出站代理。"
        outbound == null -> "DNS 服务器 $label detour 指向不存在的出站 $detourTag。"
        outbound.type.equals("direct", ignoreCase = true) -> "DNS 服务器 $label detour 指向直连出站 $detourTag。"
        outbound.type.equals("block", ignoreCase = true) -> "DNS 服务器 $label detour 指向阻断出站 $detourTag，DNS 路径不可用。"
        else -> null
    }
}

private data class NodeLineQueryData(
    val nodeResolution: NodeLineTarget,
    val activeLabel: String,
    val storedActiveLabel: String,
    val outbound: Outbound?,
    val delay: Int?,
    val exitPortrait: NodeExitPortrait?,
    val coreActive: Boolean,
    val proxyPort: Int,
    val hasSignalConflict: Boolean
)

internal data class NodeLineTarget(
    val node: NodeUi,
    val sourceLabel: String
)

internal fun resolveNodeLineTarget(
    activeLabelNode: NodeUi?,
    storedActiveLabelNode: NodeUi?,
    fallbackNode: NodeUi?
): NodeLineTarget? {
    return activeLabelNode?.let {
        NodeLineTarget(it, "运行态 activeLabel")
    } ?: storedActiveLabelNode?.let {
        NodeLineTarget(it, "状态存储 activeLabel")
    } ?: fallbackNode?.let {
        NodeLineTarget(it, "当前选中节点")
    }
}

private fun buildNodeLineQueryMessage(data: NodeLineQueryData, unknownText: String): String {
    return buildString {
        appendLine("查询目标: 当前全局节点线路")
        appendLine("节点: ${data.nodeResolution.node.displayName}")
        appendLine("来源: ${data.nodeResolution.sourceLabel}")
        appendLine("协议: ${data.nodeResolution.node.protocolDisplay}")
        appendLine("配置 ID: ${data.nodeResolution.node.sourceProfileId}")
        appendLine("核心运行: ${if (data.coreActive) "是" else "否"}")
        appendSignalSection(data)
        appendNodeSection(data, unknownText)
        appendExitPortraitSection(data)
        appendNoteSection()
    }
}

private fun StringBuilder.appendSignalSection(data: NodeLineQueryData) {
    if (data.activeLabel.isNotBlank()) {
        appendLine("运行态标签: ${data.activeLabel}")
    }
    if (data.storedActiveLabel.isNotBlank() && data.storedActiveLabel != data.activeLabel) {
        appendLine("状态存储标签: ${data.storedActiveLabel}")
    }
    if (data.hasSignalConflict) {
        appendLine("状态差异: 运行态标签与当前选中节点不一致，已优先采用运行态标签")
    }
}

private fun StringBuilder.appendNodeSection(data: NodeLineQueryData, unknownText: String) {
    appendLine()
    appendLine("=== 节点线路信息 ===")
    appendLine("节点标签: ${data.nodeResolution.node.name}")
    appendLine("URLTest 延迟: ${formatNodeDelay(data.delay, unknownText)}")
    data.outbound?.let { outbound ->
        val outboundType = outbound.type.ifBlank { unknownText }
        appendLine("出站类型: $outboundType")
        appendServerLine(outbound = outbound, unknownText = unknownText)
    }
}

private fun StringBuilder.appendServerLine(outbound: Outbound, unknownText: String) {
    val server = outbound.server?.takeIf { it.isNotBlank() } ?: return
    val portText = outbound.serverPort?.toString() ?: unknownText
    appendLine("服务器: $server:$portText")
}

private fun StringBuilder.appendExitPortraitSection(data: NodeLineQueryData) {
    appendLine()
    appendLine("=== 当前出口观测 ===")
    when {
        !data.coreActive -> appendLine("核心未运行，未发起代理出口观测。")
        data.proxyPort <= 0 -> appendLine("本地代理端口不可用，无法获取当前出口画像。")
        data.exitPortrait == null -> appendLine("出口画像获取失败或暂不可用。")
        else -> {
            appendLine("观测来源: api.ip.sb")
            appendLine("出口 IP: ${data.exitPortrait.ip}")
            appendLine("国家/地区: ${data.exitPortrait.country}")
            appendLine("城市: ${data.exitPortrait.city}")
            appendLine("ASN: ${data.exitPortrait.asn}")
            appendLine("组织: ${data.exitPortrait.organization}")
            appendLine("运营商: ${data.exitPortrait.isp}")
        }
    }
}

private fun StringBuilder.appendNoteSection() {
    appendLine()
    appendLine("说明:")
    appendLine("- 结果优先基于运行态 activeLabel 推断当前全局节点。")
    appendLine("- 当前出口观测来自一次经本地代理发出的 HTTPS 请求，仅代表本次诊断请求。")
    appendLine("- 若命中 selector/urltest 分流组，真实业务流量出口可能与此结果不同。")
}

private fun formatNodeDelay(delay: Int?, unknownText: String): String {
    if (delay == null || delay <= 0) return unknownText
    return "${delay}ms"
}

private data class IpSbGeoIpResponse(
    val ip: String? = null,
    val asn: Long? = null,
    val country: String? = null,
    val city: String? = null,
    val isp: String? = null,
    val organization: String? = null,
    @SerializedName("asn_organization") val asnOrganization: String? = null
) {
    fun toExitPortrait(unknownText: String): NodeExitPortrait? {
        val resolvedIp = ip?.takeIf { it.isNotBlank() } ?: return null
        return NodeExitPortrait(
            ip = resolvedIp,
            country = country?.takeIf { it.isNotBlank() } ?: unknownText,
            city = city?.takeIf { it.isNotBlank() } ?: unknownText,
            asn = asn?.toString() ?: unknownText,
            organization = asnOrganization?.takeIf { it.isNotBlank() }
                ?: organization?.takeIf { it.isNotBlank() }
                ?: unknownText,
            isp = isp?.takeIf { it.isNotBlank() } ?: unknownText
        )
    }
}

private data class NodeExitPortrait(
    val ip: String,
    val country: String,
    val city: String,
    val asn: String,
    val organization: String,
    val isp: String
)
