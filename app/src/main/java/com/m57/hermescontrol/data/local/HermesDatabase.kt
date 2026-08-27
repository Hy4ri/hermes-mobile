package com.m57.hermescontrol.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import java.io.File

@Database(
    entities = [ChatMessageEntity::class],
    version = 6,
    exportSchema = true,
)
abstract class HermesDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        @Volatile
        private var instance: HermesDatabase? = null

        val MIGRATION_2_3: Migration =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_chat_messages_session_id_timestamp` " +
                            "ON `chat_messages` (`session_id`, `timestamp`)",
                    )
                }

                override fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_chat_messages_session_id_timestamp` " +
                            "ON `chat_messages` (`session_id`, `timestamp`)",
                    )
                }
            }

        val MIGRATION_3_4: Migration =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE `chat_messages` ADD COLUMN `reasoning_text` TEXT NOT NULL DEFAULT ''",
                    )
                }

                override fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "ALTER TABLE `chat_messages` ADD COLUMN `reasoning_text` TEXT NOT NULL DEFAULT ''",
                    )
                }
            }

        val MIGRATION_4_5: Migration =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // Issue #842: tool rows now carry the gateway's
                    // tool call id (`call_00_...`) so REST transcript
                    // rows can be matched 1:1 against their live WS
                    // bubbles instead of fragile result-content
                    // canonicalization.
                    db.execSQL(
                        "ALTER TABLE `chat_messages` ADD COLUMN `tool_call_id` TEXT NOT NULL DEFAULT ''",
                    )
                }

                override fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "ALTER TABLE `chat_messages` ADD COLUMN `tool_call_id` TEXT NOT NULL DEFAULT ''",
                    )
                }
            }

        val MIGRATION_5_6: Migration =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // Issue #904: timeline markers (display_kind) keep
                    // their tag through the Room cache so a cached
                    // marker never degrades back into a user bubble.
                    db.execSQL(
                        "ALTER TABLE `chat_messages` ADD COLUMN `display_kind` TEXT",
                    )
                }

                override fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "ALTER TABLE `chat_messages` ADD COLUMN `display_kind` TEXT",
                    )
                }
            }

        fun get(context: Context): HermesDatabase =
            instance ?: synchronized(this) {
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
                        AuthManager.getDatabasePassword(),
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
                    ).fallbackToDestructiveMigration(false)
                    .build()
                    .also { instance = it }
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
