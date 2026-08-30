package com.kunk.singbox.service.root

import com.google.gson.Gson
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.RootAppRoutingCanonical
import com.kunk.singbox.model.RootAppRoutingPlanCompiler
import com.kunk.singbox.model.RootRoutingManifest
import com.kunk.singbox.model.TrafficCaptureMode
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootArtifactSnapshotStoreTest {
    @Test
    fun locksBoundArtifactsAndRejectsChangedSnapshot() = withTempDirectory { runtimeDir ->
        val artifacts = artifacts(1L)
        val store = testStore(runtimeDir)

        store.lock(
            1L,
            artifacts.config,
            artifacts.sidecar,
            artifacts.manifest,
            artifacts.configDigest,
            artifacts.sidecarDigest
        )
        File(runtimeDir, "generation_1/config.json").writeText("tampered")

        assertThrows(IllegalStateException::class.java) {
            store.lock(
                1L,
                artifacts.config,
                artifacts.sidecar,
                artifacts.manifest,
                artifacts.configDigest,
                artifacts.sidecarDigest
            )
        }
    }

    @Test
    fun rejectsManifestThatIsNotBoundToSidecar() = withTempDirectory { runtimeDir ->
        val artifacts = artifacts(2L)
        val badManifest = Gson().toJson(
            RootRoutingManifest(
                generation = 2L,
                configLength = artifacts.config.size.toLong(),
                configFileSha256 = artifacts.configDigest,
                sidecarLength = artifacts.sidecar.size.toLong(),
                sidecarFileSha256 = artifacts.sidecarDigest,
                staticPlanSha256 = "0".repeat(64),
                appRoutingSha256 = "1".repeat(64)
            )
        ).toByteArray(Charsets.UTF_8)

        assertThrows(IllegalStateException::class.java) {
            testStore(runtimeDir).lock(
                2L,
                artifacts.config,
                artifacts.sidecar,
                badManifest,
                artifacts.configDigest,
                artifacts.sidecarDigest
            )
        }
    }

    @Test
    fun pruneDeletesOnlyUnretainedGenerationDirectories() = withTempDirectory { runtimeDir ->
        val store = testStore(runtimeDir)
        listOf(3L, 4L).forEach { generation ->
            val artifacts = artifacts(generation)
            store.lock(
                generation,
                artifacts.config,
                artifacts.sidecar,
                artifacts.manifest,
                artifacts.configDigest,
                artifacts.sidecarDigest
            )
        }

        store.pruneGenerations(setOf(4L))

        assertFalse(File(runtimeDir, "generation_3").exists())
        assertTrue(File(runtimeDir, "generation_4").isDirectory)
    }

    private data class Artifacts(
        val config: ByteArray,
        val sidecar: ByteArray,
        val manifest: ByteArray,
        val configDigest: String,
        val sidecarDigest: String
    )

    private fun artifacts(generation: Long): Artifacts {
        val config = "{\"generation\":$generation}".toByteArray(Charsets.UTF_8)
        val configDigest = RootAppRoutingCanonical.sha256(config)
        val plan = RootAppRoutingPlanCompiler.compile(
            AppSettings(trafficCaptureMode = TrafficCaptureMode.ROOT_TRANSPARENT),
            emptyList(),
            generation
        ).copy(configFileSha256 = configDigest)
        val sidecar = Gson().toJson(plan).toByteArray(Charsets.UTF_8)
        val sidecarDigest = RootAppRoutingCanonical.sha256(sidecar)
        val manifest = Gson().toJson(
            RootRoutingManifest(
                generation = generation,
                configLength = config.size.toLong(),
                configFileSha256 = configDigest,
                sidecarLength = sidecar.size.toLong(),
                sidecarFileSha256 = sidecarDigest,
                staticPlanSha256 = plan.staticPlanSha256,
                appRoutingSha256 = plan.appRoutingSha256
            )
        ).toByteArray(Charsets.UTF_8)
        return Artifacts(config, sidecar, manifest, configDigest, sidecarDigest)
    }

    private fun testStore(directory: File): RootArtifactSnapshotStore = RootArtifactSnapshotStore(
        runtimeDirectory = directory,
        chmod = { _, _ -> },
        processId = { 1234 }
    )

    private inline fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("root-artifact-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
