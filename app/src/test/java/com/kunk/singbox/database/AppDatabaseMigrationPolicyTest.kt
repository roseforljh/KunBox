package com.kunk.singbox.database

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDatabaseMigrationPolicyTest {

    @Test
    fun versionEightDropsUnusedNodesTableExplicitly() {
        val databaseSource = File("src/main/java/com/kunk/singbox/database/AppDatabase.kt").readText()
        val nodeDaoFile = File("src/main/java/com/kunk/singbox/database/dao/NodeDao.kt")
        val nodeEntityFile = File("src/main/java/com/kunk/singbox/database/entity/NodeEntity.kt")

        assertTrue(databaseSource.contains("version = 8"))
        assertTrue(databaseSource.contains("MIGRATION_7_8"))
        assertTrue(databaseSource.contains("DROP TABLE IF EXISTS nodes"))
        assertTrue(databaseSource.contains("DROP INDEX IF EXISTS index_node_latencies_nodeId"))
        assertFalse(databaseSource.contains("NodeEntity::class"))
        assertFalse(databaseSource.contains("abstract fun nodeDao()"))
        assertFalse(nodeDaoFile.exists())
        assertFalse(nodeEntityFile.exists())
    }
}
