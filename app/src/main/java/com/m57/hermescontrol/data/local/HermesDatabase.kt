package com.m57.hermescontrol.data.local

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import java.io.File

@Database(
    entities = [ChatMessageEntity::class],
    version = 10,
    exportSchema = true,
)
abstract class HermesDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        @Volatile
        private var instance: HermesDatabase? = null

        val MIGRATION_2_3: Migration =
            object : Migration(2, 3) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_chat_messages_session_id_timestamp` " +
                            "ON `chat_messages` (`session_id`, `timestamp`)",
                    )
                }
            }

        val MIGRATION_3_4: Migration =
            object : Migration(3, 4) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "ALTER TABLE `chat_messages` ADD COLUMN `reasoning_text` TEXT NOT NULL DEFAULT ''",
                    )
                }
            }

        val MIGRATION_4_5: Migration =
            object : Migration(4, 5) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    // Issue #842: tool rows now carry the gateway's
                    // tool call id (`call_00_...`) so REST transcript
                    // rows can be matched 1:1 against their live WS
                    // bubbles instead of fragile result-content
                    // canonicalization.
                    connection.execSQL(
                        "ALTER TABLE `chat_messages` ADD COLUMN `tool_call_id` TEXT NOT NULL DEFAULT ''",
                    )
                }
            }

        val MIGRATION_5_6: Migration =
            object : Migration(5, 6) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    // Issue #904: timeline markers (display_kind) keep
                    // their tag through the Room cache so a cached
                    // marker never degrades back into a user bubble.
                    connection.execSQL(
                        "ALTER TABLE `chat_messages` ADD COLUMN `display_kind` TEXT",
                    )
                }
            }

        val MIGRATION_6_7: Migration =
            object : Migration(6, 7) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "ALTER TABLE `chat_messages` ADD COLUMN `token_count` INTEGER",
                    )
                    connection.execSQL(
                        "ALTER TABLE `chat_messages` ADD COLUMN `tps` REAL",
                    )
                }
            }

        val MIGRATION_7_8: Migration =
            object : Migration(7, 8) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "ALTER TABLE `chat_messages` ADD COLUMN `completion_id` TEXT",
                    )
                }
            }

        val MIGRATION_8_9: Migration =
            object : Migration(8, 9) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL("ALTER TABLE chat_messages ADD COLUMN rest_id TEXT")
                    connection.execSQL("ALTER TABLE chat_messages ADD COLUMN sort_group INTEGER NOT NULL DEFAULT 1")
                    connection.execSQL("ALTER TABLE chat_messages ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
                    // v8 has no durable local sequence. Preserve its surviving physical insertion order.
                    connection.execSQL("UPDATE chat_messages SET sort_order = rowid")
                    val suffix = "substr(id, length(session_id) + 7)"
                    val digits = "ltrim($suffix, '0')"
                    connection.execSQL(
                        "UPDATE chat_messages SET sort_group = 0, sort_order = CAST($suffix AS INTEGER) " +
                            "WHERE substr(id, 1, length(session_id) + 6) = 'rest-' || session_id || '-' " +
                            "AND $suffix != '' AND $suffix NOT GLOB '*[^0-9]*' " +
                            "AND (length($digits) < 19 OR " +
                            "(length($digits) = 19 AND $digits <= '9223372036854775807'))",
                    )
                    connection.execSQL("DROP INDEX index_chat_messages_session_id_timestamp")
                    connection.execSQL(
                        "CREATE INDEX index_chat_messages_session_id_sort_group_sort_order_id " +
                            "ON chat_messages (session_id, sort_group, sort_order, id)",
                    )
                }
            }

        val MIGRATION_9_10: Migration =
            object : Migration(9, 10) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    // v9 cannot prove whether a UUID-only row reached the server. Preserve that
                    // ambiguity; only new outgoing prompts record LOCAL_PENDING before submit.
                    connection.execSQL(
                        "ALTER TABLE chat_messages ADD COLUMN message_provenance TEXT NOT NULL DEFAULT 'UNKNOWN'",
                    )
                }
            }

        suspend fun get(context: Context): HermesDatabase =
            withContext(Dispatchers.IO) {
                instance?.let { return@withContext it }
                val password = AuthManager.getDatabasePassword()
                synchronized(this@Companion) {
                    instance?.let { return@synchronized it }
                    // SQLCipher can't open plaintext SQLite databases — if an old
                    // unencrypted DB exists (v1), delete it so Room + SQLCipher can
                    // create an encrypted replacement from scratch.
                    val dbFile = context.getDatabasePath("hermes_control.db")
                    if (dbFile.exists() && !isSqlCipherDatabase(dbFile)) {
                        dbFile.delete()
                    }

                    // Load SQLCipher native library before creating the driver
                    System.loadLibrary("sqlcipher")
                    val driver =
                        SQLCipherDriver(
                            password,
                            null,
                            null,
                        )

                    instance ?: Room
                        .databaseBuilder(
                            context.applicationContext,
                            HermesDatabase::class.java,
                            "hermes_control.db",
                        ).setDriver(driver)
                        .addMigrations(
                            MIGRATION_2_3,
                            MIGRATION_3_4,
                            MIGRATION_4_5,
                            MIGRATION_5_6,
                            MIGRATION_6_7,
                            MIGRATION_7_8,
                            MIGRATION_8_9,
                            MIGRATION_9_10,
                        ).fallbackToDestructiveMigration(false)
                        .build()
                        .also { instance = it }
                }
            }

        /** Returns true if the database file starts with the SQLCipher magic header. */
        private fun isSqlCipherDatabase(file: File): Boolean =
            try {
                val header = ByteArray(16)
                file.inputStream().use { it.read(header) }
                // SQLCipher 4.x databases start with bytes that differ from
                // the plaintext SQLite header "SQLite format 3\0"
                val plaintextHeader = "SQLite format 3\u0000"
                !header.contentEquals(plaintextHeader.toByteArray())
            } catch (_: Exception) {
                false // if we can't read it, treat as plaintext and delete
            }

        /** For testing — inject a custom instance. */
        fun setForTest(db: HermesDatabase?) {
            instance = db
        }
    }
}
