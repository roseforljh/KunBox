package com.kunk.singbox.repository

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import com.kunk.singbox.model.RootAppRoutingCanonical
import com.kunk.singbox.model.RootRoutingArtifactValidator
import com.kunk.singbox.model.isRootSha256

internal data class RootGenerationMarker(
    val generation: Long,
    val configFileSha256: String,
    val sidecarFileSha256: String,
    val staticPlanSha256: String,
    val appRoutingSha256: String
)

@Suppress("TooManyFunctions")
internal object RootGenerationStore {
    const val GENERATIONS_DIR_NAME = "root_generations"
    const val CURRENT_MARKER_NAME = "root_generation_current"
    const val LAST_GOOD_MARKER_NAME = "root_generation_last_good"
    private const val RUNNING_CACHE_MARKER_NAME = "running_config.generation"
    private const val LAST_GOOD_CACHE_MARKER_NAME = "last_good_running_config.generation"

    private const val SCHEMA = 1
    private var lastIssuedGeneration = 0L

    @Synchronized
    fun nextGeneration(filesDir: File, now: Long = System.currentTimeMillis()): Long {
        generationRoot(filesDir)
        val current = readCurrentStrict(filesDir)?.generation ?: 0L
        val lastGood = readLastGoodStrict(filesDir)?.generation ?: 0L
        val stored = storedGenerations(filesDir).maxOrNull() ?: 0L
        val clock = now.coerceAtLeast(1L)
        val previous = maxOf(current, lastGood, stored, lastIssuedGeneration)
        check(previous < Long.MAX_VALUE) { "Root generation counter is exhausted" }
        return maxOf(clock, previous + 1L).also { lastIssuedGeneration = it }
    }

    fun generationDirectory(filesDir: File, generation: Long): File {
        require(generation > 0L) { "Root generation must be positive" }
        return File(generationRoot(filesDir), "generation_$generation")
    }

    fun readCurrent(filesDir: File): RootGenerationMarker? = readMarker(File(filesDir, CURRENT_MARKER_NAME))

    fun readCurrentStrict(filesDir: File): RootGenerationMarker? =
        readMarkerStrict(File(filesDir, CURRENT_MARKER_NAME))

    fun readLastGood(filesDir: File): RootGenerationMarker? =
        readMarker(File(filesDir, LAST_GOOD_MARKER_NAME)) ?: readCurrent(filesDir)

    fun readLastGoodStrict(filesDir: File): RootGenerationMarker? =
        readMarkerStrict(File(filesDir, LAST_GOOD_MARKER_NAME)) ?: readCurrentStrict(filesDir)

    fun configFile(filesDir: File, marker: RootGenerationMarker): File =
        File(generationDirectory(filesDir, marker.generation), "config.json")

    fun sidecarFile(filesDir: File, marker: RootGenerationMarker): File =
        File(generationDirectory(filesDir, marker.generation), "root-routing.json")

    fun manifestFile(filesDir: File, marker: RootGenerationMarker): File =
        File(generationDirectory(filesDir, marker.generation), "manifest.json")

    fun generationForConfigPath(filesDir: File, path: String): Long? = runCatching {
        val rawConfig = File(path)
        val rawParent = rawConfig.parentFile ?: return@runCatching null
        val rawGenerations = rawParent.parentFile ?: return@runCatching null
        requireSafeFile(rawConfig, "Root generation config")
        requireSafeFile(rawParent, "Root generation directory")
        requireSafeFile(rawGenerations, "Root generations directory")
        requireSafeFile(filesDir, "Root files directory")
        val config = rawConfig.canonicalFile
        if (config.name != "config.json") return@runCatching null
        val generations = generationRoot(filesDir).canonicalFile
        val parent = config.parentFile ?: return@runCatching null
        if (parent.parentFile != generations || !parent.name.startsWith("generation_")) return@runCatching null
        parent.name.removePrefix("generation_").toLongOrNull()?.takeIf { it > 0L }
    }.getOrNull()

    fun resolveConfigMarker(filesDir: File, path: String): RootGenerationMarker {
        val generation = generationForConfigPath(filesDir, path)
            ?: error("Root candidate config is outside its generation directory")
        val config = File(path).canonicalFile
        val manifestFile = File(config.parentFile, "manifest.json")
        requireSafeFile(manifestFile, "Root generation manifest")
        check(manifestFile.isFile) { "Root generation manifest is missing" }
        val manifest = RootRoutingArtifactValidator.requireManifestJson(manifestFile.readText(Charsets.UTF_8))
        check(manifest.generation == generation) { "Root generation path and manifest do not match" }
        val marker = RootGenerationMarker(
            generation = generation,
            configFileSha256 = manifest.configFileSha256,
            sidecarFileSha256 = manifest.sidecarFileSha256,
            staticPlanSha256 = manifest.staticPlanSha256,
            appRoutingSha256 = manifest.appRoutingSha256
        )
        requireValid(marker)
        validateArtifacts(filesDir, marker)
        check(configFile(filesDir, marker).canonicalFile == config) {
            "Root generation config path does not match its manifest"
        }
        return marker
    }

    fun currentConfigFile(filesDir: File): File? = readCurrentStrict(filesDir)?.let { marker ->
        validateArtifacts(filesDir, marker)
        configFile(filesDir, marker)
    }

    fun lastGoodConfigFile(filesDir: File): File? = readLastGoodStrict(filesDir)?.let { marker ->
        validateArtifacts(filesDir, marker)
        configFile(filesDir, marker)
    }

    fun commit(filesDir: File, marker: RootGenerationMarker, configContent: String): Boolean {
        requireValid(marker)
        validateArtifacts(filesDir, marker)
        check(sha256(configContent.toByteArray(Charsets.UTF_8)) == marker.configFileSha256) {
            "Root generation config content does not match commit marker"
        }
        writeMarkerPair(filesDir, marker)
        return readCurrent(filesDir) == marker && readLastGood(filesDir) == marker
    }

    fun writeCompatibilityCaches(
        filesDir: File,
        configContent: String,
        marker: RootGenerationMarker,
        writeEntry: (File, String) -> Unit = ::atomicWrite
    ) {
        requireValid(marker)
        validateArtifacts(filesDir, marker)
        check(sha256(configContent.toByteArray(Charsets.UTF_8)) == marker.configFileSha256) {
            "Root compatibility cache content does not match generation"
        }
        writeFilesAtomically(
            listOf(
                File(filesDir, "running_config.json") to configContent,
                File(filesDir, "last_good_running_config.json") to configContent,
                File(filesDir, RUNNING_CACHE_MARKER_NAME) to encodeCacheMarker(marker),
                File(filesDir, LAST_GOOD_CACHE_MARKER_NAME) to encodeCacheMarker(marker)
            ),
            writeEntry
        )
    }

    fun cacheMatchesCurrent(filesDir: File, fileName: String, marker: RootGenerationMarker? = null): Boolean {
        val expected = marker ?: if (fileName == "running_config.json") {
            readCurrent(filesDir)
        } else {
            readLastGood(filesDir)
        }
        if (expected == null) return false
        val file = File(filesDir, fileName)
        val markerFile = File(filesDir, if (fileName == "running_config.json") {
            RUNNING_CACHE_MARKER_NAME
        } else {
            LAST_GOOD_CACHE_MARKER_NAME
        })
        if (Files.isSymbolicLink(file.toPath()) || Files.isSymbolicLink(markerFile.toPath())) return false
        return file.isFile && readCacheMarker(markerFile)?.let { cache ->
            cache.generation == expected.generation && cache.configFileSha256 == expected.configFileSha256
        } == true && sha256(file.readBytes()) == expected.configFileSha256
    }

    fun restorePrevious(filesDir: File, marker: RootGenerationMarker?): Boolean {
        if (marker == null) {
            clearMarkers(filesDir)
            return readCurrent(filesDir) == null && readLastGood(filesDir) == null
        }
        requireValid(marker)
        validateArtifacts(filesDir, marker)
        writeMarkerPair(filesDir, marker)
        return readCurrent(filesDir) == marker
    }

    fun restoreCompatibilityCaches(filesDir: File, marker: RootGenerationMarker?) {
        if (marker == null) {
            deleteFilesAtomically(
                listOf(
                    File(filesDir, RUNNING_CACHE_MARKER_NAME),
                    File(filesDir, LAST_GOOD_CACHE_MARKER_NAME)
                )
            )
            return
        }
        val content = configFile(filesDir, marker).readText(Charsets.UTF_8)
        writeCompatibilityCaches(filesDir, content, marker)
    }

    fun clearCurrent(filesDir: File): Boolean {
        deleteIfFile(File(filesDir, CURRENT_MARKER_NAME))
        return !File(filesDir, CURRENT_MARKER_NAME).exists()
    }

    fun deleteGeneration(filesDir: File, generation: Long): Boolean {
        val root = File(filesDir, GENERATIONS_DIR_NAME).canonicalFile
        val target = generationDirectory(filesDir, generation).canonicalFile
        check(target.parentFile == root) { "Invalid Root generation directory" }
        if (!target.exists()) return true
        check(target.isDirectory) { "Root generation path is not a directory" }
        check(!Files.isSymbolicLink(target.toPath())) { "Root generation path cannot be a symbolic link" }
        val children = target.listFiles().orEmpty()
        check(children.all { child ->
            !Files.isSymbolicLink(child.toPath()) && child.isFile &&
                child.name in setOf("config.json", "root-routing.json", "manifest.json")
        }) { "Root generation $generation contains an unsafe or unknown entry" }
        children.forEach { child ->
            check(child.delete()) { "Cannot delete Root generation artifact: ${child.name}" }
        }
        check(target.delete()) { "Cannot delete Root generation $generation" }
        return !target.exists()
    }

    /** Remove orphaned generation directories while retaining every referenced marker. */
    fun pruneGenerations(filesDir: File, retainGenerations: Set<Long> = emptySet()): Int {
        val root = generationRoot(filesDir).canonicalFile
        if (!root.exists()) return 0
        check(root.isDirectory) {
            "Root generations path is invalid"
        }
        val retained = (retainGenerations +
            readCurrentStrict(filesDir)?.generation.orZero() +
            readLastGoodStrict(filesDir)?.generation.orZero())
            .filterTo(mutableSetOf()) { it > 0L }
        var removed = 0
        root.listFiles().orEmpty().forEach { child ->
            check(!Files.isSymbolicLink(child.toPath())) {
                "Root generation entry cannot be a symbolic link"
            }
            if (!child.isDirectory) return@forEach
            val generation = child.name.removePrefix("generation_").toLongOrNull()
                ?.takeIf { child.name == "generation_$it" && it > 0L }
                ?: return@forEach
            if (generation !in retained) {
                check(deleteGeneration(filesDir, generation)) {
                    "Cannot prune Root generation $generation"
                }
                removed++
            }
        }
        return removed
    }

    fun readMarker(file: File): RootGenerationMarker? {
        if (Files.isSymbolicLink(file.toPath())) return null
        if (!file.isFile) return null
        return runCatching {
            val raw = file.readText(Charsets.UTF_8)
            check(raw.endsWith('\n') && !raw.endsWith("\n\n")) { "Invalid Root generation marker" }
            val lines = raw.split('\n')
            check(lines.size == 7 && lines.last().isEmpty()) { "Invalid Root generation marker" }
            val fields = lines.dropLast(1).map { line ->
                val separator = line.indexOf('=')
                check(separator > 0) { "Invalid Root generation marker field" }
                line.substring(0, separator) to line.substring(separator + 1)
            }
            check(fields.map { it.first }.toSet().size == 6) { "Duplicate Root generation marker field" }
            val values = fields.toMap()
            check(values.keys == setOf(
                "schema", "generation", "config_file_sha256", "sidecar_file_sha256",
                "static_plan_sha256", "app_routing_sha256"
            )) { "Unknown Root generation marker field" }
            check(values["schema"] == SCHEMA.toString())
            val generation = values.getValue("generation").toLong().also { check(it > 0L) }
            val configDigest = values.getValue("config_file_sha256")
            val sidecarDigest = values.getValue("sidecar_file_sha256")
            val staticDigest = values.getValue("static_plan_sha256")
            val appDigest = values.getValue("app_routing_sha256")
            listOf(configDigest, sidecarDigest, staticDigest, appDigest).forEach {
                check(isRootSha256(it)) { "Malformed Root generation digest" }
            }
            RootGenerationMarker(generation, configDigest, sidecarDigest, staticDigest, appDigest)
        }.getOrNull()
    }

    fun readMarkerStrict(file: File): RootGenerationMarker? {
        if (!file.exists()) return null
        return readMarker(file) ?: error("Invalid Root generation marker: ${file.absolutePath}")
    }

    fun encode(marker: RootGenerationMarker): String {
        requireValid(marker)
        return buildString {
            append("schema=").append(SCHEMA).append('\n')
            append("generation=").append(marker.generation).append('\n')
            append("config_file_sha256=").append(marker.configFileSha256).append('\n')
            append("sidecar_file_sha256=").append(marker.sidecarFileSha256).append('\n')
            append("static_plan_sha256=").append(marker.staticPlanSha256).append('\n')
            append("app_routing_sha256=").append(marker.appRoutingSha256).append('\n')
        }
    }

    fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    fun marker(
        generation: Long,
        configFileSha256: String,
        sidecarFileSha256: String,
        staticPlanSha256: String,
        appRoutingSha256: String
    ): RootGenerationMarker = RootGenerationMarker(
        generation = generation,
        configFileSha256 = configFileSha256,
        sidecarFileSha256 = sidecarFileSha256,
        staticPlanSha256 = staticPlanSha256,
        appRoutingSha256 = appRoutingSha256
    )

    private fun validateArtifacts(filesDir: File, marker: RootGenerationMarker) {
        val root = generationRoot(filesDir).canonicalFile
        val config = configFile(filesDir, marker)
        val sidecar = sidecarFile(filesDir, marker)
        val manifest = manifestFile(filesDir, marker)
        val generation = config.parentFile
        check(generation?.canonicalFile?.parentFile == root) {
            "Root generation directory is outside the owned directory"
        }
        requireSafeFile(generation, "Root generation directory")
        requireSafeFile(config, "Root generation config")
        requireSafeFile(sidecar, "Root generation sidecar")
        requireSafeFile(manifest, "Root generation manifest")
        check(config.isFile && sidecar.isFile && manifest.isFile) {
            "Root generation artifacts are incomplete"
        }
        check(generation.listFiles().orEmpty().map(File::getName).toSet() ==
            setOf("config.json", "root-routing.json", "manifest.json")) {
            "Root generation contains unknown or incomplete files"
        }
        val configBytes = config.readBytes()
        val sidecarBytes = sidecar.readBytes()
        check(sha256(configBytes) == marker.configFileSha256) {
            "Root generation config is missing or changed before commit"
        }
        check(sha256(sidecarBytes) == marker.sidecarFileSha256) {
            "Root generation sidecar changed before commit"
        }
        val plan = RootRoutingArtifactValidator.requireBoundPlanJson(sidecarBytes.toString(Charsets.UTF_8))
        check(plan.generation == marker.generation)
        check(plan.configFileSha256 == marker.configFileSha256)
        check(plan.staticPlanSha256 == marker.staticPlanSha256)
        check(plan.appRoutingSha256 == marker.appRoutingSha256)
        check(plan.staticPlanSha256 == RootAppRoutingCanonical.staticPlanSha256(plan))
        check(plan.appRoutingSha256 == RootAppRoutingCanonical.appRoutingSha256(plan))
        val manifestValue = RootRoutingArtifactValidator.requireManifestJson(manifest.readText(Charsets.UTF_8))
        check(manifestValue.generation == marker.generation)
        check(manifestValue.configLength == configBytes.size.toLong())
        check(manifestValue.sidecarLength == sidecarBytes.size.toLong())
        check(manifestValue.configFileSha256 == marker.configFileSha256)
        check(manifestValue.sidecarFileSha256 == marker.sidecarFileSha256)
        check(manifestValue.staticPlanSha256 == marker.staticPlanSha256)
        check(manifestValue.appRoutingSha256 == marker.appRoutingSha256)
    }

    private fun writeMarkerPair(filesDir: File, marker: RootGenerationMarker) {
        val current = File(filesDir, CURRENT_MARKER_NAME)
        val lastGood = File(filesDir, LAST_GOOD_MARKER_NAME)
        requireSafeFile(current, "Root current marker")
        requireSafeFile(lastGood, "Root last-good marker")
        val previousCurrent = current.takeIf(File::isFile)?.readBytes()
        val previousLastGood = lastGood.takeIf(File::isFile)?.readBytes()
        val encoded = encode(marker)
        try {
            // current is authoritative. A crash between the two writes must leave the old last-good
            // marker rather than advertise an uncommitted generation as the current one.
            atomicWrite(current, encoded)
            atomicWrite(lastGood, encoded)
        } catch (error: Exception) {
            runCatching { restoreMarkerFile(current, previousCurrent) }
                .onFailure(error::addSuppressed)
            runCatching { restoreMarkerFile(lastGood, previousLastGood) }
                .onFailure(error::addSuppressed)
            throw error
        }
    }

    private fun clearMarkers(filesDir: File) {
        val current = File(filesDir, CURRENT_MARKER_NAME)
        val lastGood = File(filesDir, LAST_GOOD_MARKER_NAME)
        requireSafeFile(current, "Root current marker")
        requireSafeFile(lastGood, "Root last-good marker")
        val previousCurrent = current.takeIf(File::isFile)?.readBytes()
        val previousLastGood = lastGood.takeIf(File::isFile)?.readBytes()
        try {
            deleteIfFile(current)
            deleteIfFile(lastGood)
        } catch (error: Exception) {
            runCatching { restoreMarkerFile(current, previousCurrent) }
                .onFailure(error::addSuppressed)
            runCatching { restoreMarkerFile(lastGood, previousLastGood) }
                .onFailure(error::addSuppressed)
            throw error
        }
    }

    private fun restoreMarkerFile(target: File, bytes: ByteArray?) {
        if (bytes == null) {
            deleteIfFile(target)
        } else {
            atomicWrite(target, bytes.toString(Charsets.UTF_8))
        }
    }

    private fun storedGenerations(filesDir: File): List<Long> {
        val root = generationRoot(filesDir)
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty().asSequence()
            .onEach { child ->
                check(!Files.isSymbolicLink(child.toPath())) {
                    "Root generation entry cannot be a symbolic link"
                }
            }
            .filter(File::isDirectory)
            .mapNotNull { child ->
                child.name.removePrefix("generation_").toLongOrNull()
                    ?.takeIf { child.name == "generation_$it" && it > 0L }
            }
            .toList()
    }

    private fun Long?.orZero(): Long = this ?: 0L

    private fun encodeCacheMarker(marker: RootGenerationMarker): String = buildString {
        append("generation=").append(marker.generation).append('\n')
        append("config_file_sha256=").append(marker.configFileSha256).append('\n')
    }

    private data class CacheMarker(val generation: Long, val configFileSha256: String)

    private fun readCacheMarker(file: File): CacheMarker? = runCatching {
        check(!Files.isSymbolicLink(file.toPath()) && file.isFile)
        val raw = file.readText(Charsets.UTF_8)
        check(raw.endsWith('\n') && !raw.endsWith("\n\n"))
        val lines = raw.split('\n').dropLast(1)
        check(lines.size == 2)
        val fields = lines.map { line ->
            val separator = line.indexOf('=')
            check(separator > 0)
            line.substring(0, separator) to line.substring(separator + 1)
        }
        check(fields.map { it.first }.distinct().size == fields.size)
        val values = fields.toMap()
        check(values.keys == setOf("generation", "config_file_sha256"))
        val generation = values["generation"]?.toLongOrNull()?.takeIf { it > 0L } ?: return@runCatching null
        val digest = values["config_file_sha256"] ?: return@runCatching null
        check(isRootSha256(digest))
        CacheMarker(generation, digest)
    }.getOrNull()

    private fun requireValid(marker: RootGenerationMarker) {
        check(marker.generation > 0L) { "Root generation must be positive" }
        listOf(
            marker.configFileSha256,
            marker.sidecarFileSha256,
            marker.staticPlanSha256,
            marker.appRoutingSha256
        ).forEach { check(isRootSha256(it)) { "Malformed Root generation digest" } }
    }

    private fun atomicWrite(target: File, content: String) {
        requireSafeFile(target, "Root atomic target")
        target.parentFile?.let { parent -> check(parent.exists() || parent.mkdirs()) }
        val temp = File.createTempFile(".${target.name}.", ".tmp", target.parentFile)
        try {
            FileOutputStream(temp).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
                runCatching { output.fd.sync() }
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            target.parentFile?.let { parent ->
                runCatching {
                    FileChannel.open(parent.toPath()).use { channel -> channel.force(true) }
                }
            }
        } finally {
            deleteIfFile(temp)
        }
    }

    private fun deleteIfFile(file: File) {
        requireSafeFile(file, "Root removable file")
        if (file.isFile) check(file.delete()) { "Cannot remove Root generation marker: ${file.absolutePath}" }
    }

    private fun generationRoot(filesDir: File): File {
        requireSafeFile(filesDir, "Root files directory")
        val root = File(filesDir, GENERATIONS_DIR_NAME)
        requireSafeFile(root, "Root generations directory")
        check(!root.exists() || root.isDirectory) { "Root generations path is not a directory" }
        return root
    }

    private fun requireSafeFile(file: File?, label: String) {
        check(file != null && !Files.isSymbolicLink(file.toPath())) {
            "$label cannot be a symbolic link"
        }
    }

    private fun writeFilesAtomically(
        entries: List<Pair<File, String>>,
        writeEntry: (File, String) -> Unit
    ) {
        val backups = entries.map { (target, _) ->
            requireSafeFile(target, "Root atomic target")
            check(!target.exists() || target.isFile) { "Root atomic target is not a file: ${target.path}" }
            target to target.takeIf(File::isFile)?.readBytes()
        }
        try {
            entries.forEach { (target, content) -> writeEntry(target, content) }
        } catch (error: Exception) {
            backups.asReversed().forEach { (target, bytes) ->
                runCatching {
                    if (bytes == null) deleteIfFile(target) else atomicWrite(target, bytes.toString(Charsets.UTF_8))
                }.onFailure(error::addSuppressed)
            }
            throw error
        }
    }

    private fun deleteFilesAtomically(targets: List<File>) {
        val backups = targets.map { target ->
            requireSafeFile(target, "Root removable file")
            check(!target.exists() || target.isFile) { "Root removable target is not a file: ${target.path}" }
            target to target.takeIf(File::isFile)?.readBytes()
        }
        try {
            targets.forEach(::deleteIfFile)
        } catch (error: Exception) {
            backups.asReversed().forEach { (target, bytes) ->
                if (bytes != null) {
                    runCatching { atomicWrite(target, bytes.toString(Charsets.UTF_8)) }
                        .onFailure(error::addSuppressed)
                }
            }
            throw error
        }
    }
}
