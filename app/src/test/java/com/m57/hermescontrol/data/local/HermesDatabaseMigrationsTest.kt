package com.m57.hermescontrol.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

class HermesDatabaseMigrationsTest {
    @Before
    fun setUp() {
        mockkStatic("androidx.sqlite.SQLite")
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.sqlite.SQLite")
    }

    @Test
    fun migration2to3_executesIndexCreationOnSupportDb() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        HermesDatabase.MIGRATION_2_3.migrate(db)
        verify(exactly = 1) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_chat_messages_session_id_timestamp` " +
                    "ON `chat_messages` (`session_id`, `timestamp`)",
            )
        }
    }

    @Test
    fun migration2to3_executesIndexCreationOnSQLiteConnection() {
        val connection = mockk<SQLiteConnection>(relaxed = true)
        HermesDatabase.MIGRATION_2_3.migrate(connection)
        verify(exactly = 1) {
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_chat_messages_session_id_timestamp` " +
                    "ON `chat_messages` (`session_id`, `timestamp`)",
            )
        }
    }

    @Test
    fun migration3to4_executesReasoningColumnAdditionOnSupportDb() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        HermesDatabase.MIGRATION_3_4.migrate(db)
        verify(exactly = 1) {
            db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `reasoning_text` TEXT NOT NULL DEFAULT ''")
        }
    }

    @Test
    fun migration3to4_executesReasoningColumnAdditionOnSQLiteConnection() {
        val connection = mockk<SQLiteConnection>(relaxed = true)
        HermesDatabase.MIGRATION_3_4.migrate(connection)
        verify(exactly = 1) {
            connection.execSQL(
                "ALTER TABLE `chat_messages` ADD COLUMN `reasoning_text` TEXT NOT NULL DEFAULT ''",
            )
        }
    }

    @Test
    fun migration4to5_executesToolCallIdColumnAdditionOnSupportDb() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        HermesDatabase.MIGRATION_4_5.migrate(db)
        verify(exactly = 1) {
            db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `tool_call_id` TEXT NOT NULL DEFAULT ''")
        }
    }

    @Test
    fun migration4to5_executesToolCallIdColumnAdditionOnSQLiteConnection() {
        val connection = mockk<SQLiteConnection>(relaxed = true)
        HermesDatabase.MIGRATION_4_5.migrate(connection)
        verify(exactly = 1) {
            connection.execSQL(
                "ALTER TABLE `chat_messages` ADD COLUMN `tool_call_id` TEXT NOT NULL DEFAULT ''",
            )
        }
    }

    @Test
    fun migration5to6_executesDisplayKindColumnAdditionOnSupportDb() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        HermesDatabase.MIGRATION_5_6.migrate(db)
        verify(exactly = 1) {
            db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `display_kind` TEXT")
        }
    }

    @Test
    fun migration5to6_executesDisplayKindColumnAdditionOnSQLiteConnection() {
        val connection = mockk<SQLiteConnection>(relaxed = true)
        HermesDatabase.MIGRATION_5_6.migrate(connection)
        verify(exactly = 1) {
            connection.execSQL(
                "ALTER TABLE `chat_messages` ADD COLUMN `display_kind` TEXT",
            )
        }
    }
}
