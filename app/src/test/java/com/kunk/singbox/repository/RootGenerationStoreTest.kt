package com.kunk.singbox.repository

import com.google.gson.Gson
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.RootAppRoutingCanonical
import com.kunk.singbox.model.RootAppRoutingPlanCompiler
import com.kunk.singbox.model.RootRoutingManifest
import com.kunk.singbox.model.TrafficCaptureMode
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

class RootGenerationStoreTest {
    @Test
    fun nextGenerationSkipsOrphanDirectories() = withTempDirectory { filesDir ->
        RootGenerationStore.generationDirectory(filesDir, 99L).mkdirs()

        val generation = RootGenerationStore.nextGeneration(filesDir, now = 1L)

        assertTrue(generation > 99L)
        assertFalse(RootGenerationStore.generationDirectory(filesDir, generation).exists())
    }

    @Test
    fun commitAndRestoreKeepMarkerPairConsistent() = withTempDirectory { filesDir ->
        val first = writeGeneration(filesDir, 1L, "{\"generation\":1}")
        val second = writeGeneration(filesDir, 2L, "{\"generation\":2}")

        assertTrue(RootGenerationStore.commit(filesDir, first.first, first.second))
        assertTrue(RootGenerationStore.commit(filesDir, second.first, second.second))
        assertTrue(RootGenerationStore.restorePrevious(filesDir, first.first))

        assertEquals(first.first, RootGenerationStore.readCurrentStrict(filesDir))
        assertEquals(first.first, RootGenerationStore.readLastGoodStrict(filesDir))
    }

    @Test
    fun malformedMarkerFailsClosed() = withTempDirectory { filesDir ->
        File(filesDir, RootGenerationStore.CURRENT_MARKER_NAME).writeText("generation=1\n")

        assertThrows(IllegalStateException::class.java) {
            RootGenerationStore.readCurrentStrict(filesDir)
        }
    }

    @Test
    fun pruneKeepsCommittedGenerationAndDeletesOrphan() = withTempDirectory { filesDir ->
        val committed = writeGeneration(filesDir, 10L, "{\"generation\":10}")
        writeGeneration(filesDir, 11L, "{\"generation\":11}")
        RootGenerationStore.commit(filesDir, committed.first, committed.second)

        assertEquals(1, RootGenerationStore.pruneGenerations(filesDir))
        assertTrue(RootGenerationStore.generationDirectory(filesDir, 10L).isDirectory)
        assertFalse(RootGenerationStore.generationDirectory(filesDir, 11L).exists())
    }

    @Test
    fun compatibilityCacheWriteRollsBackAllFourFilesOnMidTransactionFailure() =
        withTempDirectory { filesDir ->
            val generation = writeGeneration(filesDir, 20L, "{\"generation\":20}")
            val names = listOf(
                "running_config.json",
                "last_good_running_config.json",
                "running_config.generation",
                "last_good_running_config.generation"
            )
            names.forEachIndexed { index, name -> File(filesDir, name).writeText("old-$index") }
            val before = names.associateWith { File(filesDir, it).readBytes() }
            var writes = 0

            assertThrows(IllegalStateException::class.java) {
                RootGenerationStore.writeCompatibilityCaches(
                    filesDir,
                    generation.second,
                    generation.first
                ) { file, content ->
                    writes++
                    if (writes == 3) error("injected cache write failure")
                    file.writeText(content, Charsets.UTF_8)
                }
            }

            names.forEach { name ->
                assertTrue(File(filesDir, name).readBytes().contentEquals(before.getValue(name)))
            }
        }

    @Test
    fun damagedCacheMarkerIsRejected() = withTempDirectory { filesDir ->
        val generation = writeGeneration(filesDir, 21L, "{\"generation\":21}")
        RootGenerationStore.commit(filesDir, generation.first, generation.second)
        RootGenerationStore.writeCompatibilityCaches(filesDir, generation.second, generation.first)
        File(filesDir, "running_config.generation").appendText("unknown=value\n")

        assertFalse(
            RootGenerationStore.cacheMatchesCurrent(
                filesDir,
                "running_config.json",
                generation.first
            )
        )
    }

    @Test
    fun generationWithUnknownFileIsRejectedAndNeverDeletedRecursively() = withTempDirectory { filesDir ->
        val generation = writeGeneration(filesDir, 22L, "{\"generation\":22}")
        File(RootGenerationStore.generationDirectory(filesDir, 22L), "unknown").writeText("keep")

        assertThrows(IllegalStateException::class.java) {
            RootGenerationStore.commit(filesDir, generation.first, generation.second)
        }
        assertThrows(IllegalStateException::class.java) {
            RootGenerationStore.deleteGeneration(filesDir, 22L)
        }
        assertTrue(File(RootGenerationStore.generationDirectory(filesDir, 22L), "unknown").isFile)
    }

    @Test
    fun clearingAbsentGenerationMarkersPreservesRealCompatibilityConfigs() = withTempDirectory { filesDir ->
        val running = File(filesDir, "running_config.json").apply { writeText("running") }
        val lastGood = File(filesDir, "last_good_running_config.json").apply { writeText("last-good") }
        File(filesDir, "running_config.generation").writeText("stale")
        File(filesDir, "last_good_running_config.generation").writeText("stale")

        RootGenerationStore.restoreCompatibilityCaches(filesDir, null)

        assertEquals("running", running.readText())
        assertEquals("last-good", lastGood.readText())
        assertFalse(File(filesDir, "running_config.generation").exists())
        assertFalse(File(filesDir, "last_good_running_config.generation").exists())
    }

    @Test
    fun symbolicLinkGenerationConfigIsRejected() = withTempDirectory { filesDir ->
        val directory = RootGenerationStore.generationDirectory(filesDir, 23L)
        assertTrue(directory.mkdirs())
        val outside = File(filesDir, "outside.json").apply { writeText("outside") }
        val link = File(directory, "config.json")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }

        assertNull(RootGenerationStore.generationForConfigPath(filesDir, link.absolutePath))
    }

    @Test
    fun configPathResolvesAuthoritativeGenerationWithoutIntentMetadata() = withTempDirectory { filesDir ->
        val expected = writeGeneration(filesDir, 24L, "{\"generation\":24}").first
        val config = RootGenerationStore.configFile(filesDir, expected)

        assertEquals(expected, RootGenerationStore.resolveConfigMarker(filesDir, config.absolutePath))
    }

    @Test
    fun configPathResolutionRejectsTamperedGenerationArtifacts() = withTempDirectory { filesDir ->
        val expected = writeGeneration(filesDir, 25L, "{\"generation\":25}").first
        RootGenerationStore.sidecarFile(filesDir, expected).appendText("tampered")

        assertThrows(IllegalStateException::class.java) {
            RootGenerationStore.resolveConfigMarker(
                filesDir,
                RootGenerationStore.configFile(filesDir, expected).absolutePath
            )
        }
    }

    private fun writeGeneration(
        filesDir: File,
        generation: Long,
        configContent: String
    ): Pair<RootGenerationMarker, String> {
        val configBytes = configContent.toByteArray(Charsets.UTF_8)
        val configDigest = RootAppRoutingCanonical.sha256(configBytes)
        val plan = RootAppRoutingPlanCompiler.compile(
            AppSettings(trafficCaptureMode = TrafficCaptureMode.ROOT_TRANSPARENT),
            emptyList(),
            generation
        ).copy(configFileSha256 = configDigest)
        val sidecar = Gson().toJson(plan)
        val sidecarBytes = sidecar.toByteArray(Charsets.UTF_8)
        val sidecarDigest = RootAppRoutingCanonical.sha256(sidecarBytes)
        val manifest = Gson().toJson(
            RootRoutingManifest(
                generation = generation,
                configLength = configBytes.size.toLong(),
                configFileSha256 = configDigest,
                sidecarLength = sidecarBytes.size.toLong(),
                sidecarFileSha256 = sidecarDigest,
                staticPlanSha256 = plan.staticPlanSha256,
                appRoutingSha256 = plan.appRoutingSha256
            )
        )
        val directory = RootGenerationStore.generationDirectory(filesDir, generation)
        assertTrue(directory.mkdirs())
        File(directory, "config.json").writeBytes(configBytes)
        File(directory, "root-routing.json").writeBytes(sidecarBytes)
        File(directory, "manifest.json").writeText(manifest, Charsets.UTF_8)
        return RootGenerationStore.marker(
            generation,
            configDigest,
            sidecarDigest,
            plan.staticPlanSha256,
            plan.appRoutingSha256
        ) to configContent
    }

    private inline fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("root-generation-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
