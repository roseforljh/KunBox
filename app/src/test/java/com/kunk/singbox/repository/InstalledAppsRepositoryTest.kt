package com.kunk.singbox.repository

import com.kunk.singbox.model.InstalledAppUi
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledAppsRepositoryTest {

    @Test
    fun partialScanKeepsCachedAppsAndUpdatesScannedMetadata() {
        val cached = listOf(
            app("org.telegram.messenger", "Telegram", 10_376),
            app("com.example.old", "Old", 10_400)
        )
        val scanned = listOf(
            app("org.telegram.messenger", "Telegram New", 10_376),
            app("com.example.new", "New", 10_401)
        )

        val merged = InstalledAppsRepository.mergeScannedApps(cached, scanned, complete = false)

        assertEquals(3, merged.size)
        assertEquals("Telegram New", merged.first { it.packageName == "org.telegram.messenger" }.appName)
        assertTrue(merged.any { it.packageName == "com.example.old" })
        assertTrue(merged.any { it.packageName == "com.example.new" })
    }

    @Test
    fun completeScanRemovesPackagesMissingFromSystemInventory() {
        val cached = listOf(app("com.example.old", "Old", 10_400))
        val scanned = listOf(app("org.telegram.messenger", "Telegram", 10_376))

        val merged = InstalledAppsRepository.mergeScannedApps(cached, scanned, complete = true)

        assertEquals(listOf("org.telegram.messenger"), merged.map(InstalledAppUi::packageName))
    }

    @Test
    fun snapshotParserCreatesTypedAppsInsteadOfMaps() {
        val json = """
            {
              "schema": 1,
              "userId": 0,
              "apps": [
                {"packageName":"org.telegram.messenger","appName":"Telegram","isSystemApp":false,"hasLauncher":true,"uid":10376}
              ]
            }
        """.trimIndent()

        val apps = parseInstalledAppsSnapshot(json, expectedUserId = 0)

        assertNotNull(apps)
        assertEquals("org.telegram.messenger", apps.orEmpty().single().packageName)
    }

    @Test
    fun snapshotRoundTripUsesStableReleaseKeys() {
        val original = listOf(app("org.telegram.messenger", "Telegram", 10_376))

        val json = serializeInstalledAppsSnapshot(original, userId = 0, localeTag = "zh-CN")
        val restored = parseInstalledAppsSnapshot(json, expectedUserId = 0)

        assertTrue(json.contains("\"apps\""))
        assertEquals(original, restored)
    }

    @Test
    fun iconBatchNormalizesDuplicateAndBlankPackages() {
        val packages = InstalledAppsRepository.normalizeIconPackages(
            listOf(" org.telegram.messenger ", "", "org.telegram.messenger", "com.example.app")
        )

        assertEquals(listOf("org.telegram.messenger", "com.example.app"), packages)
    }

    @Test
    fun iconBatchUsesBoundedIoDispatcher() {
        val source = File("src/main/java/com/kunk/singbox/repository/InstalledAppsRepository.kt").readText()

        assertTrue(source.contains("Dispatchers.IO.limitedParallelism(4)"))
        assertTrue(source.contains("withContext(iconLoadDispatcher)"))
    }

    private fun app(packageName: String, name: String, uid: Int) = InstalledAppUi(
        packageName = packageName,
        appName = name,
        isSystemApp = false,
        hasLauncher = true,
        uid = uid
    )
}
