package com.m57.hermescontrol.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

@Database(
    entities = [ChatMessageEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class HermesDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        @Volatile
        private var instance: HermesDatabase? = null

        fun get(context: Context): HermesDatabase =
            instance ?: synchronized(this) {
                // SQLCipher can't open plaintext SQLite databases — if an old
                // unencrypted DB exists (v1), delete it so Room + SQLCipher can
                // create an encrypted replacement from scratch.
                val dbFile = context.getDatabasePath("hermes_control.db")
                if (dbFile.exists() && !isSqlCipherDatabase(dbFile)) {
                    dbFile.delete()
                }

                // Load SQLCipher native library before creating the factory
                System.loadLibrary("sqlcipher")
                val factory = SupportOpenHelperFactory(AuthManager.getDatabasePassword())

                val migration2to3 =
                    object : Migration(2, 3) {
                        override fun migrate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE INDEX IF NOT EXISTS `index_chat_messages_session_id_timestamp` " +
                                    "ON `chat_messages` (`session_id`, `timestamp`)",
                            )
                        }
                    }

                instance ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        HermesDatabase::class.java,
                        "hermes_control.db",
                    ).openHelperFactory(factory)
                    .addMigrations(migration2to3)
                    .fallbackToDestructiveMigration(false)
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
