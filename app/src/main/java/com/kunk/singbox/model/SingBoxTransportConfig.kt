@file:Suppress("TooManyFunctions", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "LongMethod", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeConst")

package com.kunk.singbox.model

import androidx.annotation.Keep
import com.google.gson.JsonArray
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type

@Keep
data class ObfsConfig(
    @SerializedName("type") val type: String? = null,
    @SerializedName("password") val password: String? = null,
    @Transient val stringValue: Boolean = false
)

class ObfsConfigJsonAdapter : JsonSerializer<ObfsConfig>, JsonDeserializer<ObfsConfig> {
    override fun serialize(
        src: ObfsConfig?,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        if (src == null) return com.google.gson.JsonNull.INSTANCE
        if (src.stringValue) return JsonPrimitive(src.type.orEmpty())

        return JsonObject().apply {
            src.type?.takeIf { it.isNotBlank() }?.let { addProperty("type", it) }
            src.password?.takeIf { it.isNotBlank() }?.let { addProperty("password", it) }
        }
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): ObfsConfig? {
        if (json == null || json.isJsonNull) return null
        if (json.isJsonPrimitive) {
            return ObfsConfig(type = json.asString, stringValue = true)
        }
        if (!json.isJsonObject) return null

        val value = json.asJsonObject
        return ObfsConfig(
            type = value.get("type")?.takeIf { it.isJsonPrimitive }?.asString,
            password = value.get("password")?.takeIf { it.isJsonPrimitive }?.asString
        )
    }
}

@Keep
data class TlsConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("disable_sni") val disableSni: Boolean? = null,
    @SerializedName("server_name") val serverName: String? = null,
    @SerializedName("insecure") val insecure: Boolean? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("alpn") val alpn: List<String>? = null,
    @SerializedName("min_version") val minVersion: String? = null,
    @SerializedName("max_version") val maxVersion: String? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("cipher_suites") val cipherSuites: List<String>? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("curve_preferences") val curvePreferences: List<String>? = null,
    @SerializedName("utls") val utls: UtlsConfig? = null,
    @SerializedName("reality") val reality: RealityConfig? = null,
    @SerializedName("ech") val ech: EchConfig? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName(value = "certificate", alternate = ["ca"]) val ca: List<String>? = null,
    @SerializedName(value = "certificate_path", alternate = ["ca_path"]) val caPath: String? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName(value = "client_certificate", alternate = ["client-cert", "client_cert"])
    val certificate: List<String>? = null,
    @SerializedName(value = "client_certificate_path", alternate = ["client-cert-path", "client_cert_path"])
    val certificatePath: String? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName(value = "client_key", alternate = ["client-key"]) val key: List<String>? = null,
    @SerializedName(value = "client_key_path", alternate = ["client-key-path"])
    val keyPath: String? = null,
    @SerializedName("fragment") val fragment: Boolean? = null,
    @SerializedName("fragment_fallback_delay") val fragmentFallbackDelay: String? = null,
    @SerializedName("record_fragment") val recordFragment: Boolean? = null,
    @SerializedName("kernel_tx") val kernelTx: Boolean? = null,
    @SerializedName("kernel_rx") val kernelRx: Boolean? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("certificate_public_key_sha256") val certificatePublicKeySha256: List<String>? = null
)

@Keep
data class EchConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("query_server_name") val queryServerName: String? = null,
    @Transient val dnsServer: String? = null,
    @SerializedName("pq_signature_schemes_enabled") val pqSignatureSchemesEnabled: Boolean? = null,
    @SerializedName("dynamic_record_sizing_disabled") val dynamicRecordSizingDisabled: Boolean? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("config") val config: List<String>? = null,
    @SerializedName("config_path") val configPath: String? = null
)

@Keep
data class UtlsConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("fingerprint") val fingerprint: String? = null
)

@Keep
data class RealityConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("public_key") val publicKey: String? = null,
    @SerializedName("short_id") val shortId: String? = null
    // Note: spiderX is Xray-core specific, not supported by sing-box
)

@Keep
data class TransportConfig(
    @SerializedName("type") val type: String? = null,
    @SerializedName("path") val path: String? = null,
    @SerializedName("method") val method: String? = null,
    @JsonAdapter(HttpHeaderMapJsonAdapter::class)
    @SerializedName("headers") val headers: Map<String, String>? = null,
    @SerializedName("service_name") val serviceName: String? = null,
    @JsonAdapter(StringListJsonAdapter::class)
    @SerializedName("host") val host: List<String>? = null,
    @SerializedName("idle_timeout") val idleTimeout: String? = null,
    @SerializedName("ping_timeout") val pingTimeout: String? = null,
    @SerializedName("permit_without_stream") val permitWithoutStream: Boolean? = null,
    @SerializedName("early_data_header_name") val earlyDataHeaderName: String? = null,
    @JsonAdapter(UInt32JsonAdapter::class)
    @SerializedName("max_early_data") val maxEarlyData: Long? = null,
    @SerializedName("mode") val mode: String? = null,
    @SerializedName("sc_max_each_post_bytes") val scMaxEachPostBytes: Long? = null,
    @SerializedName("sc_min_posts_interval_ms") val scMinPostsIntervalMs: Long? = null,
    @SerializedName("sc_max_buffered_posts") val scMaxBufferedPosts: Long? = null,
    @SerializedName("x_padding_bytes") val xPaddingBytes: String? = null,
    @SerializedName("no_grpc_header") val noGRPCHeader: Boolean? = null,
    @SerializedName("no_sse_header") val noSSEHeader: Boolean? = null
)

internal class HttpHeaderMap(
    internal val allValues: Map<String, List<String>>
) : AbstractMap<String, String>() {
    override val entries: Set<Map.Entry<String, String>> = allValues
        .mapValues { (_, values) -> values.firstOrNull().orEmpty() }
        .entries
}

class HttpHeaderMapJsonAdapter : JsonSerializer<Map<String, String>>, JsonDeserializer<Map<String, String>> {
    override fun serialize(
        src: Map<String, String>?,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        if (src == null) return com.google.gson.JsonNull.INSTANCE
        val preserved = (src as? HttpHeaderMap)?.allValues
        return JsonObject().apply {
            src.forEach { (name, firstValue) ->
                val values = if (preserved?.containsKey(name) == true) {
                    preserved.getValue(name)
                } else {
                    listOf(firstValue)
                }
                if (values.size == 1) {
                    addProperty(name, values.first())
                } else {
                    add(name, JsonArray().apply { values.forEach { add(it) } })
                }
            }
        }
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): Map<String, String>? {
        if (json == null || json.isJsonNull) return null
        if (!json.isJsonObject) throw JsonParseException("HTTP headers must be an object")
        val values = buildMap {
            json.asJsonObject.entrySet().forEach { (name, rawValue) ->
                val headerValues = when {
                    rawValue.isJsonPrimitive && rawValue.asJsonPrimitive.isString -> listOf(rawValue.asString)
                    rawValue.isJsonArray -> rawValue.asJsonArray.map { item ->
                        if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString) {
                            throw JsonParseException("HTTP header '$name' must contain only strings")
                        }
                        item.asString
                    }
                    else -> throw JsonParseException("HTTP header '$name' must be a string or string array")
                }
                put(name, headerValues)
            }
        }
        return HttpHeaderMap(values)
    }
}

internal fun Map<String, String>.allHeaderValues(): Map<String, List<String>> {
    return (this as? HttpHeaderMap)?.allValues ?: mapValues { (_, value) -> listOf(value) }
}

internal fun Map<String, List<String>>.asHttpHeaderMap(): Map<String, String> = HttpHeaderMap(this)

class StringListJsonAdapter : JsonSerializer<List<String>>, JsonDeserializer<List<String>> {
    override fun serialize(
        src: List<String>?,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        val values = src.orEmpty().filter { it.isNotBlank() }
        if (values.size == 1) return JsonPrimitive(values.first())

        val array = JsonArray()
        values.forEach { array.add(it) }
        return array
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): List<String>? {
        if (json == null || json.isJsonNull) return null
        return when {
            json.isJsonPrimitive -> listOf(json.asString).filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            json.isJsonArray -> json.asJsonArray
                .mapNotNull { element -> element.takeIf { it.isJsonPrimitive }?.asString }
                .filter { it.isNotBlank() }
                .takeIf { it.isNotEmpty() }
            else -> null
        }
    }
}

class StringArrayJsonAdapter : JsonSerializer<List<String>>, JsonDeserializer<List<String>> {
    override fun serialize(
        src: List<String>?,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        val array = JsonArray()
        src.orEmpty()
            .filter { it.isNotBlank() }
            .forEach { array.add(it) }
        return array
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): List<String>? {
        if (json == null || json.isJsonNull) return null
        return when {
            json.isJsonPrimitive -> listOf(json.asString).filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            json.isJsonArray -> json.asJsonArray
                .mapNotNull { element -> element.takeIf { it.isJsonPrimitive }?.asString }
                .filter { it.isNotBlank() }
                .takeIf { it.isNotEmpty() }
            else -> null
        }
    }
}

@Keep
data class MultiplexConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("protocol") val protocol: String? = null,
    @SerializedName("max_connections") val maxConnections: Int? = null,
    @SerializedName("min_streams") val minStreams: Int? = null,
    @SerializedName("max_streams") val maxStreams: Int? = null,
    @SerializedName("padding") val padding: Boolean? = null,
    @SerializedName("brutal") val brutal: BrutalConfig? = null
)

@Keep
data class BrutalConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("up_mbps") val upMbps: Int? = null,
    @SerializedName("down_mbps") val downMbps: Int? = null
)

@Keep
data class RouteConfig(
    @SerializedName("rules") val rules: List<RouteRule>? = null,
    @SerializedName("rule_set") val ruleSet: List<RuleSetConfig>? = null,
    @SerializedName("final") val finalOutbound: String? = null,
    @SerializedName("find_process") val findProcess: Boolean? = null,
    @SerializedName("auto_detect_interface") val autoDetectInterface: Boolean? = null,
    @SerializedName("default_interface") val defaultInterface: String? = null,
    @SerializedName("default_domain_resolver") val defaultDomainResolver: DomainResolveConfig? = null,
    @SerializedName("default_network_strategy") val defaultNetworkStrategy: String? = null,
    @SerializedName("default_network_type") val defaultNetworkType: List<String>? = null,
    @SerializedName("default_fallback_network_type") val defaultFallbackNetworkType: List<String>? = null,
    @SerializedName("default_fallback_delay") val defaultFallbackDelay: String? = null,
    @SerializedName("default_udp_fragment") val defaultUdpFragment: Boolean? = null
)

@Keep
data class RouteRule(
    @SerializedName("action") val action: String? = null,
    @SerializedName("network") val networkRaw: Any? = null,
    @SerializedName("protocol") val protocolRaw: Any? = null,
    @SerializedName("domain") val domain: List<String>? = null,
    @SerializedName("domain_suffix") val domainSuffix: List<String>? = null,
    @SerializedName("domain_keyword") val domainKeyword: List<String>? = null,
    @SerializedName("geosite") val geosite: List<String>? = null,
    @SerializedName("geoip") val geoip: List<String>? = null,
    @SerializedName("ip_cidr") val ipCidr: List<String>? = null,
    @SerializedName("ip_is_private") val ipIsPrivate: Boolean? = null,
    @SerializedName("port") val port: List<Int>? = null,
    @SerializedName("port_range") val portRange: List<String>? = null,
    @SerializedName("rule_set") val ruleSet: List<String>? = null,
    @SerializedName("inbound") val inbound: List<String>? = null,
    @SerializedName("package_name") val packageName: List<String>? = null,
    @SerializedName("process_name") val processName: List<String>? = null,
    @SerializedName("user_id") val userId: List<Int>? = null,
    @SerializedName("user") val user: List<String>? = null,
    @SerializedName("outbound") val outbound: String? = null
) {
    val network: List<String>?
        get() = when (networkRaw) {
            is String -> listOf(networkRaw)
            is List<*> -> networkRaw.filterIsInstance<String>()
            else -> null
        }

    val protocol: List<String>?
        get() = when (protocolRaw) {
            is String -> listOf(protocolRaw)
            is List<*> -> protocolRaw.filterIsInstance<String>()
            else -> null
        }
}

@Keep
data class RuleSetConfig(
    @SerializedName("tag") val tag: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("format") val format: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("path") val path: String? = null,
    @SerializedName("download_detour") val downloadDetour: String? = null,
    @SerializedName("update_interval") val updateInterval: String? = null
)

@Keep
data class ExperimentalConfig(
    @SerializedName("cache_file") val cacheFile: CacheFileConfig? = null,
    @SerializedName("clash_api") val clashApi: ClashApiConfig? = null
)

@Keep
data class CacheFileConfig(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("path") val path: String? = null,
    @SerializedName("store_fakeip") val storeFakeip: Boolean? = null
)

@Keep
data class ClashApiConfig(
    @SerializedName("external_controller") val externalController: String? = null,
    @SerializedName("external_ui") val externalUi: String? = null,
    @SerializedName("secret") val secret: String? = null,
    @SerializedName("default_mode") val defaultMode: String? = null
)
