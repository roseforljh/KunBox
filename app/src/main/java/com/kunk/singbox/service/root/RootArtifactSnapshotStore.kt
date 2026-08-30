package com.kunk.singbox.service.root

import android.system.Os
import com.kunk.singbox.model.RootAppRoutingCanonical
import com.kunk.singbox.model.RootRoutingArtifactValidator
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files

internal data class RootLockedArtifacts(
    val configBytes: ByteArray,
    val sidecarBytes: ByteArray,
    val manifestBytes: ByteArray
)

internal class RootArtifactSnapshotStore(
    private val runtimeDirectory: File = File(RootNetfilterOwnership.RUNTIME_DIR),
    private val chmod: (File, Int) -> Unit = { file, mode -> Os.chmod(file.absolutePath, mode) },
    private val processId: () -> Int = { android.os.Process.myPid() }
) {
    @Suppress("LongParameterList")
    fun lock(
        generation: Long,
        configBytes: ByteArray,
        sidecarBytes: ByteArray,
        manifestBytes: ByteArray,
        configFileSha256: String,
        sidecarFileSha256: String
    ): RootLockedArtifacts {
        require(generation > 0L)
        require(RootAppRoutingCanonical.sha256(configBytes) == configFileSha256) {
            "Root-owned config digest does not match request"
        }
        require(RootAppRoutingCanonical.sha256(sidecarBytes) == sidecarFileSha256) {
            "Root-owned sidecar digest does not match request"
        }
        val plan = RootRoutingArtifactValidator.requireBoundPlanJson(
            sidecarBytes.toString(Charsets.UTF_8)
        )
        val manifestValue = RootRoutingArtifactValidator.requireManifestJson(
            manifestBytes.toString(Charsets.UTF_8)
        )
        check(plan.generation == generation) { "Root-owned plan generation mismatch" }
        check(plan.configFileSha256 == configFileSha256) { "Root-owned plan config digest mismatch" }
        check(manifestValue.generation == generation) { "Root-owned manifest generation mismatch" }
        check(manifestValue.configLength == configBytes.size.toLong()) {
            "Root-owned manifest config length mismatch"
        }
        check(manifestValue.sidecarLength == sidecarBytes.size.toLong()) {
            "Root-owned manifest sidecar length mismatch"
        }
        check(manifestValue.configFileSha256 == configFileSha256) {
            "Root-owned manifest config digest mismatch"
        }
        check(manifestValue.sidecarFileSha256 == sidecarFileSha256) {
            "Root-owned manifest sidecar digest mismatch"
        }
        check(manifestValue.staticPlanSha256 == plan.staticPlanSha256) {
            "Root-owned manifest static digest mismatch"
        }
        check(manifestValue.appRoutingSha256 == plan.appRoutingSha256) {
            "Root-owned manifest app digest mismatch"
        }
        val directory = prepareGenerationDirectory(generation)
        val config = writeOrVerify(directory, CONFIG_NAME, configBytes)
        val sidecar = writeOrVerify(directory, SIDECAR_NAME, sidecarBytes)
        val manifest = writeOrVerify(directory, MANIFEST_NAME, manifestBytes)
        check(RootAppRoutingCanonical.sha256(config) == configFileSha256) {
            "Root-owned config digest mismatch"
        }
        check(RootAppRoutingCanonical.sha256(sidecar) == sidecarFileSha256) {
            "Root-owned sidecar digest mismatch"
        }
        return RootLockedArtifacts(config, sidecar, manifest)
    }

    private fun prepareGenerationDirectory(generation: Long): File {
        val rootPath = runtimeDirectory.toPath()
        check(!runtimeDirectory.exists() || !Files.isSymbolicLink(rootPath)) {
            "Root runtime directory cannot be a symbolic link"
        }
        val root = runtimeDirectory.canonicalFile
        check(root.exists() || root.mkdirs()) { "Cannot create Root runtime directory" }
        check(root.isDirectory && !Files.isSymbolicLink(root.toPath())) {
            "Root runtime directory is invalid"
        }
        chmod(root, 0b111000000)
        val rawDirectory = File(root, "generation_$generation")
        check(!rawDirectory.exists() || !Files.isSymbolicLink(rawDirectory.toPath())) {
            "Root generation snapshot cannot be a symbolic link"
        }
        val directory = rawDirectory.canonicalFile
        check(directory.parentFile == root) { "Invalid Root snapshot directory" }
        check(directory.exists() || directory.mkdir()) { "Cannot create Root generation snapshot" }
        check(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {
            "Root generation snapshot directory is invalid"
        }
        chmod(directory, 0b111000000)
        val expectedNames = setOf(CONFIG_NAME, SIDECAR_NAME, MANIFEST_NAME)
        val files = directory.listFiles().orEmpty()
        check(files.all { it.name in expectedNames || it.isSnapshotTemporaryFile() }) {
            "Root generation snapshot contains an unknown file"
        }
        files.filter { it.isSnapshotTemporaryFile() }.forEach { temp ->
            check(!Files.isSymbolicLink(temp.toPath())) { "Root snapshot temporary file is a symbolic link" }
            check(temp.delete()) { "Cannot remove incomplete Root snapshot temporary file" }
        }
        return directory
    }

    private fun File.isSnapshotTemporaryFile(): Boolean =
        name.startsWith("$CONFIG_NAME.tmp.") || name.startsWith("$SIDECAR_NAME.tmp.") ||
            name.startsWith("$MANIFEST_NAME.tmp.")

    fun pruneGenerations(retainGenerations: Set<Long>) {
        val root = runtimeDirectory.canonicalFile
        if (!root.exists()) return
        check(root.isDirectory && !Files.isSymbolicLink(root.toPath())) {
            "Root runtime directory is invalid"
        }
        root.listFiles().orEmpty().forEach { child ->
            if (!child.isDirectory || Files.isSymbolicLink(child.toPath())) return@forEach
            val generation = child.name.removePrefix("generation_").toLongOrNull()
                ?.takeIf { child.name == "generation_$it" && it > 0L }
                ?: return@forEach
            if (generation in retainGenerations) return@forEach
            check(child.listFiles().orEmpty().all {
                it.name in setOf(CONFIG_NAME, SIDECAR_NAME, MANIFEST_NAME) &&
                    !Files.isSymbolicLink(it.toPath()) && it.isFile
            }) { "Root generation snapshot contains an unsafe file" }
            child.listFiles().orEmpty().forEach { file ->
                check(file.delete()) { "Cannot delete Root snapshot file" }
            }
            check(child.delete()) { "Cannot delete Root generation snapshot" }
        }
    }

    private fun writeOrVerify(directory: File, name: String, bytes: ByteArray): ByteArray {
        val target = File(directory, name)
        check(!Files.isSymbolicLink(target.toPath())) { "Root snapshot file cannot be a symbolic link" }
        if (!target.exists()) {
            val temp = File(directory, "$name.tmp.${processId()}.${System.nanoTime()}")
            try {
                FileOutputStream(temp).use { output ->
                    output.write(bytes)
                    output.flush()
                    output.fd.sync()
                }
                chmod(temp, 0b100000000)
                try {
                    Files.move(
                        temp.toPath(),
                        target.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: Exception) {
                    Files.move(temp.toPath(), target.toPath())
                }
                runCatching {
                    FileChannel.open(directory.toPath()).use { channel -> channel.force(true) }
                }
            } finally {
                if (temp.isFile) check(temp.delete()) { "Cannot remove Root snapshot temporary file" }
            }
        }
        check(target.isFile) { "Root generation snapshot is incomplete" }
        chmod(target, 0b100000000)
        return target.readBytes().also { locked ->
            check(locked.contentEquals(bytes)) { "Root generation snapshot changed" }
        }
    }

    private companion object {
        const val CONFIG_NAME = "config.json"
        const val SIDECAR_NAME = "root-routing.json"
        const val MANIFEST_NAME = "manifest.json"
    }
}
