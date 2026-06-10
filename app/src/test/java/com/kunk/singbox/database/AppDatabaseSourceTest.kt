package com.kunk.singbox.database

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppDatabaseSourceTest {
    @Test
    fun databaseDoesNotAllowRoomQueriesOnMainThread() {
        val source = File("src/main/java/com/kunk/singbox/database/AppDatabase.kt").readText()

        assertFalse(source.contains("allowMainThreadQueries"))
    }

    @Test
    fun roomDaosDoNotExposeSynchronousDatabaseMethods() {
        val daoDir = File("src/main/java/com/kunk/singbox/database/dao")
        val sources = daoDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        assertTrue(sources.contains("suspend fun get"))
        assertFalse(sources.contains("getSync("))
        assertFalse(sources.contains("saveSync("))
        assertFalse(sources.contains("insertSync("))
        assertFalse(sources.contains("insertAllSync("))
        assertFalse(sources.contains("getAllSync("))
        assertFalse(sources.contains("countSync("))
        assertFalse(sources.contains("hasSettingsSync("))
    }
}
