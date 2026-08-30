package com.kunk.singbox.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.kunk.singbox.utils.perf.DiagnosticResourceSample
import com.kunk.singbox.utils.perf.DiagnosticResourceHistory
import com.kunk.singbox.utils.perf.formatDiagnosticResourceSamplesCsv
import com.kunk.singbox.utils.perf.mergeDiagnosticResourceSamples
import com.kunk.singbox.service.manager.ConnectionIncidentHistory
import com.kunk.singbox.service.manager.formatConnectionIncidentSnapshotsJsonl
import com.kunk.singbox.ipc.SingBoxRemote
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun writeDiagnosticArchive(file: File, entries: Map<String, String>) {
    require(entries.isNotEmpty())
    entries.keys.forEach { name ->
        require(name.isNotBlank() && !name.startsWith('/') && '\\' !in name && ".." !in name.split('/'))
    }
    file.parentFile?.mkdirs()
    runCatching {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }.onFailure {
        file.delete()
    }.getOrThrow()
}

internal data class DiagnosticArchiveResult(
    val location: String,
    val sizeBytes: Long
)

internal data class DiagnosticResourceSummary(
    val currentVersionSampleCount: Int,
    val historicalSampleCount: Int,
    val processSessionCount: Int,
    val historyStartEpochMs: Long?,
    val historyEndEpochMs: Long?,
    val latestProcessName: String?,
    val latestPid: Int?,
    val latestProcessStartedAtEpochMs: Long?,
    val latestSessionSampleCount: Int
)

@Suppress("LongParameterList")
internal fun buildDiagnosticArchiveEntries(
    manifest: String,
    logs: String,
    runningConfig: String?,
    resourcesCsv: String,
    connectionIncidentsJsonl: String,
    readinessJson: String = "{}",
    perAppVpnPlanJson: String = "{}",
    dialBudgetJson: String = "{}",
    directIncidentsJsonl: String = "",
    redactor: DiagnosticRedactor
): Map<String, String> = linkedMapOf<String, String>().apply {
    put("manifest.json", manifest)
    put("redaction-policy.txt", DiagnosticArchiveRepository.REDACTION_POLICY)
    put("logs.txt", redactor.redactText(logs))
    if (runningConfig != null) put("running_config.json", redactor.redactJson(runningConfig))
    put("resources.csv", resourcesCsv)
    put("connection_incidents.jsonl", redactor.redactJsonLines(connectionIncidentsJsonl))
    put("readiness.json", redactor.redactJson(readinessJson))
    put("per_app_vpn_plan.json", redactor.redactJson(perAppVpnPlanJson))
    put("dial_budget.json", redactor.redactJson(dialBudgetJson))
    put("direct_incidents.jsonl", redactor.redactJsonLines(directIncidentsJsonl))
}

private const val PROCESS_START_JITTER_TOLERANCE_MS = 2L

internal fun summarizeDiagnosticResources(
    samples: List<DiagnosticResourceSample>,
    currentVersionCode: Long
): DiagnosticResourceSummary {
    val latest = samples.maxByOrNull(DiagnosticResourceSample::timestampEpochMs)
    val currentVersionSampleCount = samples.count { it.appVersionCode == currentVersionCode }
    val processSessionCount = samples.groupBy {
        Triple(it.appVersionCode, it.processName, it.pid)
    }.values.sumOf(::countProcessSessions)
    val latestSessionSampleCount = latest?.let { current ->
        samples.count { it.belongsToSameProcessSession(current) }
    } ?: 0
    return DiagnosticResourceSummary(
        currentVersionSampleCount = currentVersionSampleCount,
        historicalSampleCount = samples.size - currentVersionSampleCount,
        processSessionCount = processSessionCount,
        historyStartEpochMs = samples.minOfOrNull(DiagnosticResourceSample::timestampEpochMs),
        historyEndEpochMs = latest?.timestampEpochMs,
        latestProcessName = latest?.processName,
        latestPid = latest?.pid,
        latestProcessStartedAtEpochMs = latest?.processStartedAtEpochMs,
        latestSessionSampleCount = latestSessionSampleCount
    )
}

private fun countProcessSessions(samples: List<DiagnosticResourceSample>): Int {
    var count = if (samples.any { it.processStartedAtEpochMs == null }) 1 else 0
    var previous: Long? = null
    samples.mapNotNull(DiagnosticResourceSample::processStartedAtEpochMs).sorted().forEach { current ->
        if (previous == null || current - checkNotNull(previous) > PROCESS_START_JITTER_TOLERANCE_MS) count++
        previous = current
    }
    return count
}

private fun DiagnosticResourceSample.belongsToSameProcessSession(other: DiagnosticResourceSample): Boolean {
    if (appVersionCode != other.appVersionCode || processName != other.processName || pid != other.pid) return false
    val currentStart = processStartedAtEpochMs
    val otherStart = other.processStartedAtEpochMs
    if (currentStart == null || otherStart == null) return currentStart == otherStart
    return kotlin.math.abs(currentStart - otherStart) <= PROCESS_START_JITTER_TOLERANCE_MS
}

internal class DiagnosticArchiveRepository(
    context: Context,
    private val logRepository: LogRepository = LogRepository.getInstance()
) {

    private val appContext = context.applicationContext
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    suspend fun export(samples: List<DiagnosticResourceSample>): DiagnosticArchiveResult =
        withContext(Dispatchers.IO) {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val fileName = "kunbox_diagnostics_$timestamp.zip"
            val tempDirectory = File(appContext.cacheDir, "diagnostics").also { directory ->
                check(directory.exists() || directory.mkdirs()) { "无法创建诊断缓存目录" }
            }
            val tempArchive = File(tempDirectory, fileName)

            try {
                writeDiagnosticArchive(tempArchive, buildEntries(samples))
                publishArchive(tempArchive, fileName)
            } finally {
                tempArchive.delete()
            }
        }

    private suspend fun buildEntries(samples: List<DiagnosticResourceSample>): Map<String, String> {
        val redactor = DiagnosticRedactor(ByteArray(REDACTION_SALT_BYTES).also(SecureRandom()::nextBytes))
        val mergedSamples = mergeDiagnosticResourceSamples(
            DiagnosticResourceHistory(appContext).read(),
            samples
        )
        val runningConfig = File(appContext.filesDir, RUNNING_CONFIG_FILE)
            .takeIf(File::isFile)
            ?.readText(Charsets.UTF_8)
        val connectionIncidents = ConnectionIncidentHistory(appContext).read()
        val directIncidentsFile = File(appContext.filesDir, "diagnostics/direct_incidents.jsonl")
        val perAppPlanFile = File(appContext.filesDir, "diagnostics/per_app_vpn_plan.json")
        val logs = logRepository.getLogsAsTextForExport()
        return buildDiagnosticArchiveEntries(
            manifest = buildManifest(mergedSamples, runningConfig != null, connectionIncidents.size),
            logs = logs,
            runningConfig = runningConfig,
            resourcesCsv = formatDiagnosticResourceSamplesCsv(mergedSamples),
            connectionIncidentsJsonl = formatConnectionIncidentSnapshotsJsonl(connectionIncidents),
            readinessJson = gson.toJson(SingBoxRemote.readiness.value),
            perAppVpnPlanJson = perAppPlanFile.takeIf(File::isFile)?.readText().orEmpty().ifBlank { "{}" },
            dialBudgetJson = buildDialBudgetJson(logs),
            directIncidentsJsonl = directIncidentsFile.takeIf(File::isFile)?.readText().orEmpty(),
            redactor = redactor
        )
    }

    private fun buildDialBudgetJson(logs: String): String {
        val latest = logs.lineSequence().lastOrNull { "kunbox_physical_budget_v1" in it }
        return gson.toJson(
            mapOf(
                "native_snapshot_status" to if (latest == null) "unavailable" else "available",
                "latest_native_snapshot" to latest.orEmpty()
            )
        )
    }

    @Suppress("DEPRECATION")
    private fun buildManifest(
        samples: List<DiagnosticResourceSample>,
        hasRunningConfig: Boolean,
        connectionIncidentCount: Int
    ): String {
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        val resourceSummary = summarizeDiagnosticResources(samples, versionCode)
        return gson.toJson(
            JsonObject().apply {
                addProperty("format_version", 3)
                addProperty("created_at_epoch_ms", System.currentTimeMillis())
                addProperty("app_version", packageInfo.versionName.orEmpty())
                addProperty("app_version_code", versionCode)
                addProperty("device_manufacturer", Build.MANUFACTURER)
                addProperty("device_model", Build.MODEL)
                addProperty("android_version", Build.VERSION.RELEASE)
                addProperty("android_api", Build.VERSION.SDK_INT)
                addProperty("resource_sample_count", samples.size)
                addProperty("resource_current_version_sample_count", resourceSummary.currentVersionSampleCount)
                addProperty("resource_historical_sample_count", resourceSummary.historicalSampleCount)
                addProperty("resource_process_session_count", resourceSummary.processSessionCount)
                addProperty("resource_history_start_epoch_ms", resourceSummary.historyStartEpochMs ?: -1L)
                addProperty("resource_history_end_epoch_ms", resourceSummary.historyEndEpochMs ?: -1L)
                addProperty("latest_resource_process_name", resourceSummary.latestProcessName.orEmpty())
                addProperty("latest_resource_pid", resourceSummary.latestPid ?: -1)
                addProperty(
                    "latest_resource_process_started_at_epoch_ms",
                    resourceSummary.latestProcessStartedAtEpochMs ?: -1L
                )
                addProperty("latest_resource_session_sample_count", resourceSummary.latestSessionSampleCount)
                addProperty("running_config_included", hasRunningConfig)
                addProperty("connection_incident_count", connectionIncidentCount)
                addProperty("redaction", "salted-pseudonym-v1")
            }
        )
    }

    private fun publishArchive(source: File, fileName: String): DiagnosticArchiveResult {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishToDownloads(source, fileName)
        } else {
            publishToExternalFiles(source, fileName)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishToDownloads(source: File, fileName: String): DiagnosticArchiveResult {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, ZIP_MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/KunBox")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(
            appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ) { "无法创建下载文件" }
        try {
            copyArchiveToUri(source, uri)
            val completed = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            check(appContext.contentResolver.update(uri, completed, null, null) == 1) {
                "无法完成下载文件发布"
            }
            return DiagnosticArchiveResult(
                location = "${Environment.DIRECTORY_DOWNLOADS}/KunBox/$fileName",
                sizeBytes = source.length()
            )
        } catch (e: Exception) {
            appContext.contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun copyArchiveToUri(source: File, target: Uri) {
        val output = checkNotNull(appContext.contentResolver.openOutputStream(target, "w")) {
            "无法写入下载文件"
        }
        output.use { stream -> source.inputStream().use { input -> input.copyTo(stream) } }
    }

    private fun publishToExternalFiles(source: File, fileName: String): DiagnosticArchiveResult {
        val directory = checkNotNull(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) {
            "外部存储目录不可用"
        }
        val targetDirectory = File(directory, "KunBox").also { target ->
            check(target.exists() || target.mkdirs()) { "无法创建导出目录" }
        }
        val target = File(targetDirectory, fileName)
        source.copyTo(target, overwrite = true)
        return DiagnosticArchiveResult(target.absolutePath, target.length())
    }

    internal companion object {
        const val RUNNING_CONFIG_FILE = "running_config.json"
        const val ZIP_MIME_TYPE = "application/zip"
        const val REDACTION_SALT_BYTES = 32
        val REDACTION_POLICY = """
            KunBox 诊断包字段分级：
            1. 保留：应用版本、系统版本、时间、数值指标、协议类型和端口。
            2. 加盐化名：服务器、域名、IP、包名、节点标签、路由引用和文件路径。
            3. 完全删除：密码、UUID、令牌、Cookie、授权头、私钥和预共享密钥。
            4. 每次导出使用新的随机盐，诊断包之间无法关联同一地址或标识。
        """.trimIndent()
    }
}

internal class DiagnosticRedactor(private val salt: ByteArray) {

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val compactGson = GsonBuilder().disableHtmlEscaping().create()

    fun redactJson(source: String): String {
        val root = runCatching { JsonParser.parseString(source) }.getOrElse { return INVALID_JSON_REDACTION }
        return gson.toJson(redactElement(root, null))
    }

    fun redactText(source: String): String {
        var redacted = PRIVATE_KEY_BLOCK_REGEX.replace(source, REDACTED)
        redacted = BEARER_REGEX.replace(redacted, "Bearer <redacted>")
        redacted = TEXT_CREDENTIAL_VALUE_REGEX.replace(redacted) { match ->
            val keyQuote = match.groupValues[1]
            val key = match.groupValues[2]
            val separator = match.groupValues[3]
            val rawValue = match.groupValues[4]
            val valueQuote = matchingQuote(rawValue)
            "$keyQuote$key$keyQuote$separator$valueQuote$REDACTED$valueQuote"
        }
        redacted = TEXT_PSEUDONYM_VALUE_REGEX.replace(redacted) { match ->
            val keyQuote = match.groupValues[1]
            val key = match.groupValues[2]
            val separator = match.groupValues[3]
            val rawValue = match.groupValues[4]
            val valueQuote = matchingQuote(rawValue)
            val replacement = "<value:${fingerprint(rawValue.trim('"', '\''))}>"
            "$keyQuote$key$keyQuote$separator$valueQuote$replacement$valueQuote"
        }
        redacted = OUTBOUND_TAG_REGEX.replace(redacted) { match ->
            "${match.groupValues[1]}<id:${fingerprint(match.groupValues[2])}>${match.groupValues[3]}"
        }
        redacted = URI_REGEX.replace(redacted) { match -> "<uri:${fingerprint(match.value)}>" }
        redacted = EMAIL_REGEX.replace(redacted) { match -> "<email:${fingerprint(match.value)}>" }
        redacted = PRIVATE_PATH_REGEX.replace(redacted) { match -> "<path:${fingerprint(match.value)}>" }
        redacted = MAC_REGEX.replace(redacted) { match -> "<mac:${fingerprint(match.value)}>" }
        redacted = BRACKETED_IPV6_REGEX.replace(redacted) { match ->
            if (LOG_TIMESTAMP_REGEX.matches(match.value)) match.value else "<ip:${fingerprint(match.value)}>"
        }
        redacted = IPV4_REGEX.replace(redacted) { match -> "<ip:${fingerprint(match.value)}>" }
        redacted = COMPRESSED_IPV6_REGEX.replace(redacted) { match -> "<ip:${fingerprint(match.value)}>" }
        redacted = IPV6_REGEX.replace(redacted) { match -> "<ip:${fingerprint(match.value)}>" }
        redacted = UUID_REGEX.replace(redacted, REDACTED)
        return DOMAIN_REGEX.replace(redacted) { match -> "<host:${fingerprint(match.value)}>" }
    }

    fun redactJsonLines(source: String): String {
        return source.lineSequence()
            .filter(String::isNotBlank)
            .joinToString(separator = "\n", postfix = if (source.isBlank()) "" else "\n") { line ->
                val redacted = runCatching {
                    redactElement(JsonParser.parseString(line), null)
                }.getOrElse { JsonPrimitive(INVALID_JSON_REDACTION) }
                compactGson.toJson(redacted)
            }
    }

    private fun redactElement(element: JsonElement, key: String?): JsonElement {
        val normalizedKey = key?.let(::normalizeKey)
        return when {
            isCredentialKey(normalizedKey) -> JsonPrimitive(REDACTED)
            normalizedKey in ENDPOINT_KEYS -> pseudonymize(element, "endpoint")
            normalizedKey in IDENTIFIER_KEYS || normalizedKey in SELECTOR_REFERENCE_KEYS -> {
                pseudonymize(element, "id")
            }
            normalizedKey in PATH_KEYS -> pseudonymize(element, "path")
            element.isJsonObject -> redactObject(element.asJsonObject)
            element.isJsonArray -> redactArray(element.asJsonArray, null)
            element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
                JsonPrimitive(redactText(element.asString))
            }
            else -> element.deepCopy()
        }
    }

    private fun redactObject(source: JsonObject): JsonObject = JsonObject().also { target ->
        source.entrySet().forEach { (key, value) -> target.add(key, redactElement(value, key)) }
    }

    private fun redactArray(source: JsonArray, key: String?): JsonArray = JsonArray().also { target ->
        source.forEach { value -> target.add(redactElement(value, key)) }
    }

    private fun pseudonymize(element: JsonElement, kind: String): JsonElement = when {
        element.isJsonArray -> JsonArray().also { target ->
            element.asJsonArray.forEach { target.add(pseudonymize(it, kind)) }
        }
        element.isJsonObject -> redactObject(element.asJsonObject)
        element.isJsonNull -> element.deepCopy()
        else -> JsonPrimitive("<$kind:${fingerprint(element.asString)}>")
    }

    private fun fingerprint(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(0)
        return digest.digest(value.toByteArray(Charsets.UTF_8))
            .take(FINGERPRINT_BYTES)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun normalizeKey(key: String): String = key.lowercase().replace('-', '_').replace(' ', '_')

    private fun matchingQuote(value: String): String {
        val first = value.firstOrNull() ?: return ""
        return first.takeIf { (it == '"' || it == '\'') && value.lastOrNull() == it }?.toString().orEmpty()
    }

    private fun isCredentialKey(key: String?): Boolean {
        if (key == null) return false
        return key in CREDENTIAL_KEYS || CREDENTIAL_KEY_SUFFIXES.any(key::endsWith)
    }

    private companion object {
        const val REDACTED = "<redacted>"
        const val FINGERPRINT_BYTES = 6
        const val INVALID_JSON_REDACTION = "{\"redaction\":\"invalid_json_omitted\"}"

        val CREDENTIAL_KEYS = setOf(
            "access_token",
            "api_key",
            "auth",
            "auth_str",
            "auth_string",
            "authorization",
            "client_key",
            "client_secret",
            "cookie",
            "passphrase",
            "password",
            "passwd",
            "pre_shared_key",
            "private_key",
            "proxy_authorization",
            "psk",
            "refresh_token",
            "secret",
            "set_cookie",
            "short_id",
            "token",
            "uuid"
        )
        val CREDENTIAL_KEY_SUFFIXES = setOf(
            "_api_key",
            "_authorization",
            "_client_key",
            "_cookie",
            "_passphrase",
            "_password",
            "_private_key",
            "_secret",
            "_token"
        )
        val ENDPOINT_KEYS = setOf(
            "address",
            "endpoint",
            "host",
            "hostname",
            "remote",
            "server",
            "server_address",
            "server_name",
            "servers",
            "url"
        )
        val IDENTIFIER_KEYS = setOf(
            "actual",
            "auth_user",
            "chain",
            "connection",
            "detour",
            "domain",
            "domain_keyword",
            "domain_suffix",
            "inbound",
            "destination_ip_cidr",
            "final",
            "geoip",
            "geosite",
            "ip_cidr",
            "node",
            "node_id",
            "outbound",
            "outbound_tag",
            "package_name",
            "package_names",
            "public_key",
            "rule_set",
            "source_ip_cidr",
            "source",
            "selected",
            "target_node_name",
            "tag",
            "user",
            "username"
        )
        val SELECTOR_REFERENCE_KEYS = setOf("default", "outbounds")
        val PATH_KEYS = setOf("file", "file_path", "path", "private_path")
        val TEXT_CREDENTIAL_KEY_PATTERNS = (
            CREDENTIAL_KEYS.map(::keyPattern) + CREDENTIAL_KEY_SUFFIXES.map(::credentialSuffixPattern)
            ).distinct()
        val TEXT_PSEUDONYM_KEYS = ENDPOINT_KEYS + IDENTIFIER_KEYS + PATH_KEYS + setOf("package")
        val TEXT_CREDENTIAL_VALUE_REGEX = Regex(
            "(?i)([\"']?)\\b(${TEXT_CREDENTIAL_KEY_PATTERNS.joinToString("|")})\\b\\1[ \\t]*([:=])[ \\t]*" +
                "(\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\r\\n]+)"
        )
        val TEXT_PSEUDONYM_VALUE_REGEX = Regex(
            "(?i)([\"']?)\\b(${TEXT_PSEUDONYM_KEYS.joinToString("|") { keyPattern(it) }})\\b\\1[ \\t]*" +
                "([:=])[ \\t]*" +
                "(\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s,;}\\]]+)"
        )
        val OUTBOUND_TAG_REGEX = Regex("(?i)(\\boutbound/[a-z0-9_-]+\\[)([^\\]\\r\\n]+)(])")
        val PRIVATE_KEY_BLOCK_REGEX = Regex(
            "(?is)-----BEGIN ([A-Z0-9 ]*PRIVATE KEY)-----.*?(?:-----END \\1-----|\\z)"
        )
        val BEARER_REGEX = Regex("(?i)Bearer[ \\t]+[^\\s,;}\\]]+")
        val URI_REGEX = Regex("(?i)\\b[a-z][a-z0-9+.-]{1,20}://[^\\s\"'<>]+")
        val EMAIL_REGEX = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,63}\\b")
        val PRIVATE_PATH_REGEX = Regex("/(?:data|storage|sdcard)/(?:[^\\s\"'<>]+)")
        val MAC_REGEX = Regex("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b")
        val BRACKETED_IPV6_REGEX = Regex("(?i)\\[[0-9a-f:]{2,}]")
        val LOG_TIMESTAMP_REGEX = Regex("\\[(?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(?:\\.\\d{3})?]")
        val IPV4_REGEX = Regex("\\b(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}\\b")
        val COMPRESSED_IPV6_REGEX = Regex(
            "(?i)(?<![0-9a-f:])(?=[0-9a-f:]*::)(?:[0-9a-f]{0,4}:){1,7}[0-9a-f]{0,4}(?![0-9a-f:])"
        )
        val IPV6_REGEX = Regex("(?i)(?<![0-9a-f:])(?:[0-9a-f]{1,4}:){3,7}[0-9a-f]{0,4}(?![0-9a-f:])")
        val UUID_REGEX = Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b")
        val DOMAIN_REGEX = Regex("(?i)\\b(?:[a-z0-9-]+\\.)+[a-z]{2,63}\\b")

        fun keyPattern(key: String): String = key.split('_').joinToString("[-_]") { Regex.escape(it) }

        fun credentialSuffixPattern(suffix: String): String {
            return "[a-z0-9][a-z0-9_-]*[-_]${keyPattern(suffix.removePrefix("_"))}"
        }
    }
}
