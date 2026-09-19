package com.m57.hermescontrol.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
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
    fun migration2to3_executesIndexCreationOnSQLiteConnection() =
        runBlocking {
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
    fun migration3to4_executesReasoningColumnAdditionOnSQLiteConnection() =
        runBlocking {
            val connection = mockk<SQLiteConnection>(relaxed = true)
            HermesDatabase.MIGRATION_3_4.migrate(connection)
            verify(exactly = 1) {
                connection.execSQL(
                    "ALTER TABLE `chat_messages` ADD COLUMN `reasoning_text` TEXT NOT NULL DEFAULT ''",
                )
            }
        }

    @Test
    fun migration4to5_executesToolCallIdColumnAdditionOnSQLiteConnection() =
        runBlocking {
            val connection = mockk<SQLiteConnection>(relaxed = true)
            HermesDatabase.MIGRATION_4_5.migrate(connection)
            verify(exactly = 1) {
                connection.execSQL(
                    "ALTER TABLE `chat_messages` ADD COLUMN `tool_call_id` TEXT NOT NULL DEFAULT ''",
                )
            }
        }

    @Test
    fun migration5to6_executesDisplayKindColumnAdditionOnSQLiteConnection() =
        runBlocking {
            val connection = mockk<SQLiteConnection>(relaxed = true)
            HermesDatabase.MIGRATION_5_6.migrate(connection)
            verify(exactly = 1) {
                connection.execSQL(
                    "ALTER TABLE `chat_messages` ADD COLUMN `display_kind` TEXT",
                )
            }
        }

    @Test
    fun migration6to7_executesTokenCountAndTpsColumnAdditionOnSQLiteConnection() =
        runBlocking {
            val connection = mockk<SQLiteConnection>(relaxed = true)
            HermesDatabase.MIGRATION_6_7.migrate(connection)
            verify(exactly = 1) {
                connection.execSQL(
                    "ALTER TABLE `chat_messages` ADD COLUMN `token_count` INTEGER",
                )
                connection.execSQL(
                    "ALTER TABLE `chat_messages` ADD COLUMN `tps` REAL",
                )
            }
        }

    @Test
    fun migration7to8_executesCompletionIdColumnAdditionOnSQLiteConnection() =
        runBlocking {
            val connection = mockk<SQLiteConnection>(relaxed = true)
            HermesDatabase.MIGRATION_7_8.migrate(connection)
            verify(exactly = 1) {
                connection.execSQL(
                    "ALTER TABLE `chat_messages` ADD COLUMN `completion_id` TEXT",
                )
            }
        }
}
