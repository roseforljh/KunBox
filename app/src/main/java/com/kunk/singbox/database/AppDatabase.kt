package com.kunk.singbox.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kunk.singbox.database.dao.ActiveStateDao
import com.kunk.singbox.database.dao.NodeDao
import com.kunk.singbox.database.dao.NodeLatencyDao
import com.kunk.singbox.database.dao.ProfileDao
import com.kunk.singbox.database.dao.SettingsDao
import com.kunk.singbox.database.entity.ActiveStateEntity
import com.kunk.singbox.database.entity.NodeEntity
import com.kunk.singbox.database.entity.NodeLatencyEntity
import com.kunk.singbox.database.entity.ProfileEntity
import com.kunk.singbox.database.entity.SettingsEntity

/**
 *
 *
 */
@Database(
    entities = [
        ProfileEntity::class,
        NodeEntity::class,
        ActiveStateEntity::class,
        NodeLatencyEntity::class,
        SettingsEntity::class
    ],
    version = 6,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun profileDao(): ProfileDao
    abstract fun nodeDao(): NodeDao
    abstract fun activeStateDao(): ActiveStateDao
    abstract fun nodeLatencyDao(): NodeLatencyDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        private const val DATABASE_NAME = "singbox.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .allowMainThreadQueries()
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
        }

        /**
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS settings (
                        id INTEGER NOT NULL PRIMARY KEY,
                        version INTEGER NOT NULL,
                        data TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        /**
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS node_latencies_new (
                        nodeId TEXT NOT NULL PRIMARY KEY,
                        latencyMs INTEGER NOT NULL,
                        testedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT OR IGNORE INTO node_latencies_new (nodeId, latencyMs, testedAt)
                    SELECT nodeId, latencyMs, testedAt FROM node_latencies
                """.trimIndent())
                db.execSQL("DROP TABLE IF EXISTS node_latencies")
                db.execSQL("ALTER TABLE node_latencies_new RENAME TO node_latencies")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_node_latencies_nodeId ON node_latencies(nodeId)")
            }
        }

        /**
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN dnsPreResolve INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiles ADD COLUMN dnsServer TEXT DEFAULT NULL")
            }
        }

        /**
         * v5: remove legacy nodes.regionFlag column to prevent schema mismatch crash after upgrade.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS nodes_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        protocol TEXT NOT NULL,
                        `group` TEXT NOT NULL,
                        latencyMs INTEGER,
                        isFavorite INTEGER NOT NULL,
                        sourceProfileId TEXT NOT NULL,
                        tags TEXT NOT NULL,
                        trafficUsed INTEGER NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        FOREIGN KEY(sourceProfileId) REFERENCES profiles(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO nodes_new (
                        id, name, protocol, `group`, latencyMs, isFavorite, sourceProfileId, tags, trafficUsed, sortOrder
                    )
                    SELECT
                        id, name, protocol, `group`, latencyMs, isFavorite, sourceProfileId, tags, trafficUsed, sortOrder
                    FROM nodes
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE IF EXISTS nodes")
                db.execSQL("ALTER TABLE nodes_new RENAME TO nodes")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_nodes_sourceProfileId ON nodes(sourceProfileId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_nodes_protocol ON nodes(protocol)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_nodes_group ON nodes(`group`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_nodes_isFavorite ON nodes(isFavorite)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN dnsOverride TEXT")
            }
        }

        /**
         */
        fun getInMemoryDatabase(context: Context): AppDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                AppDatabase::class.java
            ).build()
        }
    }
}
