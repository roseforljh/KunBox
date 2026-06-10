package com.kunk.singbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SingBoxApplicationSourceTest {

    @Test
    fun workManagerUsesConfigurationProviderWithoutManualInitialization() {
        val source = File("src/main/java/com/kunk/singbox/SingBoxApplication.kt").readText()
        val buildFile = File("build.gradle.kts").readText()

        assertTrue(source.contains("class SingBoxApplication : Application(), Configuration.Provider"))
        assertTrue(source.contains(".setDefaultProcessName(packageName)"))
        assertTrue(buildFile.contains("androidx.work:work-multiprocess:2.9.0"))
        assertFalse(source.contains("WorkManager.initialize"))
        assertFalse(source.contains("isWorkManagerInitialized"))
    }

    @Test
    fun applicationLoadsSettingsAndTempFilesOffMainThread() {
        val source = File("src/main/java/com/kunk/singbox/SingBoxApplication.kt").readText()
        val onCreate = source.substringAfter("override fun onCreate()")
            .substringBefore("private fun isMainProcess")

        assertTrue(onCreate.contains("withContext(Dispatchers.IO)"))
        assertTrue(onCreate.contains("SettingsRepository.getInstance(this@SingBoxApplication)"))
        assertTrue(onCreate.contains("applicationScope.launch(Dispatchers.IO)"))
        assertTrue(onCreate.contains("cleanupOrphanedTempFiles()"))
        assertFalse(onCreate.contains("val settingsRepository = SettingsRepository.getInstance(this)"))
    }
}
