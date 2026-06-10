package com.kunk.singbox.repository

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InstalledAppsRepositorySourceTest {

    @Test
    fun installedAppsLoadingIsSerialized() {
        val content = File("src/main/java/com/kunk/singbox/repository/InstalledAppsRepository.kt").readText()

        assertTrue(content.contains("private val loadMutex = Mutex()"))
        assertTrue(content.contains("loadMutex.withLock"))
        assertTrue(content.contains("loadAppsLocked(force = false, clearBeforeLoad = false)"))
        assertTrue(content.contains("loadAppsLocked(force = true, clearBeforeLoad = true)"))
        assertTrue(content.contains("loadAppsLocked(force = true, clearBeforeLoad = false)"))
    }
}
