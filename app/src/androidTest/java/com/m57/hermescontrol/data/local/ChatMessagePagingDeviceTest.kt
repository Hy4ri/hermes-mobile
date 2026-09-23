package com.m57.hermescontrol.data.local

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatPersistenceRepository
import com.m57.hermescontrol.ui.chat.MessageRole
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Executes the generated Room DAO against SQLCipher, rather than reproducing its SQL in a fake. */
@RunWith(AndroidJUnit4::class)
class ChatMessagePagingDeviceTest {
    private lateinit var context: Context
    private lateinit var database: HermesDatabase
    private lateinit var repository: ChatPersistenceRepository
    private val databaseName = "paging-regression-${UUID.randomUUID()}.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        System.loadLibrary("sqlcipher")
        openDatabase()
    }

    private fun openDatabase() {
        database =
            Room
                .databaseBuilder(context, HermesDatabase::class.java, databaseName)
                .setDriver(driver())
                .addMigrations(HermesDatabase.MIGRATION_8_9)
                .build()
        repository = ChatPersistenceRepository(database.chatMessageDao())
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun unpaddedRestIdsSelectNumericLatestPageAndWalkEveryRowExactlyOnce() =
        runBlocking {
            val rows = (1..350).map { restRow(it, timestamp = 10L) }
            repository.persistMessages(rows, "session")
            repository.persistMessage(restRow(999, timestamp = 10L).copy(id = "rest-other-999"), "other")
            val latest = repository.loadPage("session", null, 150)
            assertEquals(rows.takeLast(150).map { it.id }, latest.messages.map { it.id })
            repository.persistMessage(restRow(351, timestamp = 1L), "session")
            val middle = repository.loadPage("session", latest.cursor, 150)
            val oldest = repository.loadPage("session", middle.cursor, 150)
            assertEquals(rows.map { it.id }, (oldest.messages + middle.messages + latest.messages).map { it.id })
            assertEquals(true, latest.hasOlder)
            assertEquals(true, middle.hasOlder)
            assertEquals(false, oldest.hasOlder)
            assertEquals(
                "rest-session-351",
                repository
                    .loadPage("session", null, 1)
                    .messages
                    .single()
                    .id,
            )
            assertEquals(
                "rest-other-999",
                repository
                    .loadPage("other", null, 1)
                    .messages
                    .single()
                    .id,
            )
        }

    @Test
    fun nonMonotonicTimestampsKeepServerOrderInBothPageArrivalOrdersAfterReopen() =
        runBlocking {
            val rows =
                listOf(9, 10, 100, 200).mapIndexed { index, id ->
                    restRow(id, listOf(900L, 100L, 800L, 200L)[index])
                }
            for (pages in listOf(listOf(rows.take(2), rows.drop(2)), listOf(rows.drop(2), rows.take(2)))) {
                repository.clearMessagesForSession("session")
                pages.forEach { repository.persistMessages(it, "session") }
                database.close()
                openDatabase()
                val latest = repository.loadPage("session", null, 2)
                val older = repository.loadPage("session", latest.cursor, 2)
                assertEquals(rows.drop(2).map { it.id }, latest.messages.map { it.id })
                assertEquals(rows.take(2).map { it.id }, older.messages.map { it.id })
                assertEquals(false, older.hasOlder)
            }
        }

    @Test
    fun uuidOnlyRowsKeepInsertionOrderAfterUpdateAppendAndReopen() =
        runBlocking {
            val rows =
                listOf("ffffffff", "00000000", "aaaaaaaa").mapIndexed { index, prefix ->
                    ChatMessage(
                        id = "$prefix-0000-4000-8000-000000000000",
                        role = MessageRole.USER,
                        content = "message-$index",
                        timestamp = listOf(900L, 100L, 800L)[index],
                    )
                }
            rows.forEach { repository.persistMessage(it, "local") }
            repository.persistMessage(rows.first().copy(content = "edited", timestamp = 999L), "local")
            database.close()
            openDatabase()
            val latest = repository.loadPage("local", null, 2)
            repository.persistMessage(
                rows.last().copy(id = "bbbbbbbb-0000-4000-8000-000000000000", content = "new", timestamp = 1L),
                "local",
            )
            val older = repository.loadPage("local", latest.cursor, 2)
            assertEquals(rows.drop(1).map { it.id }, latest.messages.map { it.id })
            assertEquals(listOf(rows.first().id), older.messages.map { it.id })
            assertEquals("edited", older.messages.single().content)
            assertEquals(false, older.hasOlder)
            assertEquals(
                "new",
                repository
                    .loadPage("local", null, 1)
                    .messages
                    .single()
                    .content,
            )
        }

    @Test
    fun migration8to9ValidatesRealSchemaAndPreservesRowsMetadataAndPaging() =
        runBlocking {
            val canonical =
                listOf(9, 10, 100, 200).mapIndexed { index, id ->
                    restRow(id, listOf(900L, 100L, 800L, 200L)[index]).toEntity("session").copy(
                        reasoningText = "trace-$id",
                        toolName = "terminal",
                        toolCallId = "call-$id",
                        toolStatus = "COMPLETED",
                        isStreaming = true,
                        displayKind = "marker-$id",
                        tokenCount = id,
                        tps = 2.5,
                        completionId = "completion-$id",
                    )
                }
            val local =
                listOf("ffffffff", "00000000", "aaaaaaaa").mapIndexed { index, prefix ->
                    ChatMessageEntity(
                        id = "$prefix-0000-4000-8000-000000000000",
                        sessionId = "local",
                        role = "USER",
                        content = "local-$index",
                        timestamp = 100L - index,
                    )
                }
            val malformed =
                listOf(
                    "rest-session-",
                    "rest-session-10x",
                    "rest-other-50",
                    "rest-session-9223372036854775808",
                ).map {
                    ChatMessageEntity(
                        id = it,
                        sessionId = "session",
                        role = "USER",
                        content = it,
                        timestamp = 1L,
                    )
                }
            // REST pages can be inserted backwards; UUID order is physical insertion, not timestamp or text.
            val seeded = canonical.asReversed() + local + malformed
            createVersion8(seeded)
            openDatabase()
            val dao = database.chatMessageDao()
            val restored = dao.getMessagesForSession("session") + dao.getMessagesForSession("local")
            assertEquals(seeded.size, restored.size)
            seeded.forEach { original ->
                val row = restored.single { it.id == original.id }
                assertEquals(original, row.copy(sortGroup = 1, sortOrder = 0))
            }
            assertEquals(canonical.map { it.id }, restored.filter { it.sortGroup == 0 }.map { it.id })
            assertTrue(restored.filter { it.id in malformed.map { row -> row.id } }.all { it.sortGroup == 1 })
            assertEquals(local.map { it.id }, dao.getMessagesForSession("local").map { it.id })
            val first = repository.loadPage("session", null, 2)
            var cursor = first.cursor
            val pages = mutableListOf(first.messages)
            var hasOlder = first.hasOlder
            while (hasOlder) {
                val page = repository.loadPage("session", cursor, 2)
                pages += page.messages
                cursor = page.cursor
                hasOlder = page.hasOlder
            }
            assertEquals((canonical + malformed).map { it.id }, pages.asReversed().flatten().map { it.id })
            repository.persistMessage(local.first().toUiModel().copy(content = "edited", timestamp = 9999L), "local")
            database.close()
            openDatabase()
            assertEquals(local.map { it.id }, repository.loadPage("local", null, 3).messages.map { it.id })
            assertEquals(
                "edited",
                repository
                    .loadPage("local", null, 3)
                    .messages
                    .first()
                    .content,
            )
            assertPagingIndexUsed()
        }

    @Test
    fun migration8to9ValidatesEmptyDatabaseAndAllowsNewWrites() =
        runBlocking {
            createVersion8(emptyList())
            openDatabase()
            assertTrue(repository.loadPage("session", null, 1).messages.isEmpty())
            repository.persistMessage(restRow(100, 1L), "session")
            assertEquals(100L, database.chatMessageDao().getMessage("rest-session-100")?.sortOrder)
            assertPagingIndexUsed()
        }

    @Test
    fun confirmedAliasPreservesLivePayloadAndCanonicalOrderAcrossReopen() =
        runBlocking {
            val live =
                ChatMessage(
                    id = "ffffffff-0000-4000-8000-000000000000",
                    role = MessageRole.ASSISTANT,
                    content = "newer WS payload",
                    reasoningText = "live trace",
                    completionId = "completion-100",
                )
            repository.persistMessage(live, "session")
            repository.confirmIdentities(
                listOf(live.copy(content = "stale snapshot", restId = "rest-session-100")),
                "session",
            )
            // A later WS write with no alias must retain the confirmed cache identity.
            repository.persistMessage(live.copy(content = "final payload"), "session")
            repository.persistMessage(restRow(9, 9999L), "session")
            database.close()
            openDatabase()
            val rows = repository.loadPage("session", null, 2).messages
            assertEquals(listOf("rest-session-9", live.id), rows.map { it.id })
            assertEquals("rest-session-100", rows.last().restId)
            assertEquals("final payload", rows.last().content)
            assertEquals("live trace", rows.last().reasoningText)
            assertEquals("completion-100", rows.last().completionId)
        }

    @Test
    fun confirmingLastLocalRowDoesNotReuseItsCursorForALaterInsertion() =
        runBlocking {
            val live =
                ChatMessage(
                    id = "ffffffff-0000-4000-8000-000000000000",
                    role = MessageRole.ASSISTANT,
                    content = "first",
                )
            repository.persistMessage(live, "session")
            val cursor = repository.loadPage("session", null, 1).cursor
            repository.confirmIdentities(listOf(live.copy(restId = "rest-session-100")), "session")
            val next = live.copy(id = "00000000-0000-4000-8000-000000000000", content = "second")
            repository.persistMessage(next, "session")
            assertFalse(repository.loadPage("session", cursor, 10).messages.any { it.id == next.id })
            assertEquals(
                next.id,
                repository
                    .loadPage("session", null, 1)
                    .messages
                    .single()
                    .id,
            )
        }

    private fun driver() = SQLCipherDriver("paging-test-only".toByteArray(), null, null)

    /** Build the actual exported v8 schema, then let Room run AND validate its migration on open. */
    private fun createVersion8(rows: List<ChatMessageEntity>) {
        database.close()
        context.deleteDatabase(databaseName)
        val schema =
            InstrumentationRegistry
                .getInstrumentation()
                .context.assets
                .open("com.m57.hermescontrol.data.local.HermesDatabase/8.json")
                .bufferedReader()
                .use { JSONObject(it.readText()).getJSONObject("database") }
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()
        driver().open(file.absolutePath).use { connection ->
            val entity = schema.getJSONArray("entities").getJSONObject(0)
            connection.execSQL(
                entity.getString("createSql").replace("\${TABLE_NAME}", "chat_messages"),
            )
            val indices = entity.getJSONArray("indices")
            for (index in 0 until indices.length()) {
                connection.execSQL(
                    indices
                        .getJSONObject(index)
                        .getString("createSql")
                        .replace("\${TABLE_NAME}", "chat_messages"),
                )
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) connection.execSQL(setup.getString(index))
            connection
                .prepare(
                    "INSERT INTO chat_messages (id, session_id, role, content, reasoning_text, timestamp, " +
                        "tool_name, tool_call_id, tool_status, is_streaming, display_kind, " +
                        "token_count, tps, completion_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { insert ->
                    rows.forEach { row ->
                        insert.bindText(1, row.id)
                        insert.bindText(2, row.sessionId)
                        insert.bindText(3, row.role)
                        insert.bindText(4, row.content)
                        insert.bindText(5, row.reasoningText)
                        insert.bindLong(6, row.timestamp)
                        row.toolName?.let { insert.bindText(7, it) } ?: insert.bindNull(7)
                        insert.bindText(8, row.toolCallId)
                        row.toolStatus?.let { insert.bindText(9, it) } ?: insert.bindNull(9)
                        insert.bindLong(10, if (row.isStreaming) 1L else 0L)
                        row.displayKind?.let { insert.bindText(11, it) } ?: insert.bindNull(11)
                        row.tokenCount?.let { insert.bindLong(12, it.toLong()) } ?: insert.bindNull(12)
                        row.tps?.let { insert.bindDouble(13, it) } ?: insert.bindNull(13)
                        row.completionId?.let { insert.bindText(14, it) } ?: insert.bindNull(14)
                        insert.step()
                        insert.reset()
                        insert.clearBindings()
                    }
                }
            connection.execSQL("PRAGMA user_version = 8")
        }
    }

    private fun assertPagingIndexUsed() {
        database.close()
        driver().open(context.getDatabasePath(databaseName).absolutePath).use { connection ->
            for (bound in listOf("", "AND (sort_group, sort_order, id) < (1, 100, 'cursor')")) {
                connection
                    .prepare(
                        "EXPLAIN QUERY PLAN SELECT * FROM chat_messages WHERE session_id = 'session' $bound " +
                            "ORDER BY sort_group DESC, sort_order DESC, id DESC LIMIT 151",
                    ).use { query ->
                        val plan = buildList { while (query.step()) add(query.getText(3)) }.joinToString(" ")
                        assertTrue(plan, plan.contains("index_chat_messages_session_id_sort_group_sort_order_id"))
                        assertFalse(plan, plan.contains("TEMP B-TREE", ignoreCase = true))
                    }
            }
        }
    }

    private fun restRow(
        id: Int,
        timestamp: Long,
    ) = ChatMessage(
        id = "rest-session-$id",
        role = MessageRole.ASSISTANT,
        content = "answer-$id",
        timestamp = timestamp,
    )
}
