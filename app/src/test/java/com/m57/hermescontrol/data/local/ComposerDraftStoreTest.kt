package com.m57.hermescontrol.data.local

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ComposerDraftStoreTest {
    private val owner = DataScope("connection", "https://one.local", "main")
    private val key = ComposerDraftKey(owner, "session")
    private val values = mutableMapOf<String, String>()
    private var failCommit = false
    private var clock = 0L
    private val prefs = mockk<SharedPreferences>()

    init {
        every { prefs.getString(any(), any()) } answers { values[firstArg()] ?: secondArg() }
        every { prefs.edit() } answers {
            val pending = mutableMapOf<String, String?>()
            val editor = mockk<SharedPreferences.Editor>()
            every { editor.putString(any(), any()) } answers {
                pending[firstArg()] = secondArg()
                editor
            }
            every { editor.commit() } answers {
                if (failCommit) {
                    false
                } else {
                    pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                    true
                }
            }
            editor
        }
    }

    private fun store(scope: CoroutineScope): ComposerDraftStore = ComposerDraftStore({ prefs }, scope, { clock })

    @Test
    fun `operations run in invocation order even without awaiting saves`() =
        runTest {
            val store = store(backgroundScope)
            store.save(key, "first")
            val firstRead = store.load(key)
            store.save(key, "second")
            val secondRead = store.load(key)
            assertEquals("first", firstRead.await())
            assertEquals("second", secondRead.await())
        }

    @Test
    fun `same session on different connections urls and profiles stays separate`() =
        runTest {
            val store = store(backgroundScope)
            val keys =
                listOf(
                    key,
                    key.copy(scope = owner.copy(connectionProfileId = "other")),
                    key.copy(scope = owner.copy(baseUrl = "https://two.local")),
                    key.copy(scope = owner.copy(activeProfileId = "work")),
                    key.copy(sessionId = "other"),
                )
            keys.forEachIndexed { index, item -> store.save(item, "draft $index") }
            keys.forEachIndexed { index, item -> assertEquals("draft $index", store.load(item).await()) }
        }

    @Test
    fun `new chat key cannot collide with any special looking session id`() =
        runTest {
            val store = store(backgroundScope)
            val ids = listOf(null, "", "null", "__new_chat__", "\u0000__new_chat__", "draft:new")
            ids.forEachIndexed { index, id -> store.save(key.copy(sessionId = id), "draft $index") }
            ids.forEachIndexed {
                index,
                id,
                ->
                assertEquals("draft $index", store.load(key.copy(sessionId = id)).await())
            }
        }

    @Test
    fun `new store and new auth generation restore complete text from same prefs`() =
        runTest {
            val text = "  مرحبا 🚀\n\t" + "long text ".repeat(20_000)
            store(backgroundScope).save(key, text).await()
            val restarted = store(backgroundScope)
            assertEquals(text, restarted.load(key.copy(scope = owner.copy(inMemoryAuthGeneration = 2))).await())
        }

    @Test
    fun `blank removes only target and does not prevent typing another draft`() =
        runTest {
            val store = store(backgroundScope)
            val other = key.copy(sessionId = "other")
            store.save(key, "first").await()
            store.save(other, "untouched").await()
            store.save(key, " \n\t").await()
            assertNull(store.load(key).await())
            assertEquals("untouched", store.load(other).await())
            store.save(key, "new typing").await()
            assertEquals("new typing", store.load(key).await())
        }

    @Test
    fun `move new chat to stored id without losing destination when source absent`() =
        runTest {
            val store = store(backgroundScope)
            val source = key.copy(sessionId = null)
            store.save(source, "source").await()
            store.save(key, "destination").await()
            store.rekey(source, key).await()
            assertNull(store.load(source).await())
            assertEquals("source", store.load(key).await())
            store.rekey(source, key).await()
            assertEquals("source", store.load(key).await())
            store.rekey(key, key).await()
            assertEquals("source", store.load(key).await())
        }

    @Test
    fun `cross scope rekey fails without moving text`() =
        runTest {
            val store = store(backgroundScope)
            val other = key.copy(scope = owner.copy(activeProfileId = "other"))
            store.save(key, "private").await()
            try {
                store.rekey(key, other).await()
                fail("Cross-owner move must fail")
            } catch (_: IllegalArgumentException) {
                assertEquals("private", store.load(key).await())
                assertNull(store.load(other).await())
            }
        }

    @Test
    fun `expiry prunes old text and a move does not extend its lifetime`() =
        runTest {
            val store = store(backgroundScope)
            store.save(key, "expires").await()
            clock = 29L * 24 * 60 * 60 * 1000
            val moved = key.copy(sessionId = "moved")
            store.rekey(key, moved).await()
            assertEquals("expires", store.load(moved).await())
            clock = 30L * 24 * 60 * 60 * 1000
            assertNull(store.load(moved).await())
            assertTrue(values.values.none { "expires" in it })
        }

    @Test
    fun `retention keeps latest hundred per connection even with equal timestamps`() =
        runTest {
            val store = store(backgroundScope)
            for (index in 0..100) store.save(key.copy(sessionId = "$index"), "text $index")
            assertNull(store.load(key.copy(sessionId = "0")).await())
            assertEquals("text 100", store.load(key.copy(sessionId = "100")).await())
            for (index in 1..100) assertEquals("text $index", store.load(key.copy(sessionId = "$index")).await())
            store.save(key.copy(scope = owner.copy(connectionProfileId = "other")), "independent").await()
            assertEquals("text 1", store.load(key.copy(sessionId = "1")).await())
        }

    @Test
    fun `session deletion fences stale flush and rekey into deleted destination`() =
        runTest {
            val store = store(backgroundScope)
            val source = key.copy(sessionId = null)
            store.save(key, "deleted").await()
            store.remove(key).await()
            store.save(key, "stale flush").await()
            store.save(source, "new draft").await()
            store.rekey(source, key).await()
            assertNull(store.load(key).await())
            assertEquals("new draft", store.load(source).await())
        }

    @Test
    fun `logout fences unobserved old owners but allows next auth generation`() =
        runTest {
            val store = store(backgroundScope)
            val generation = AuthManager.authGenerationFlow.value
            val old = key.copy(scope = owner.copy(inMemoryAuthGeneration = generation))
            val other = old.copy(scope = old.scope.copy(connectionProfileId = "connection:other"))
            store.save(old, "private").await()
            store.save(other, "keep").await()
            store.clearConnection(owner.connectionProfileId).await()
            store.save(old.copy(sessionId = "unobserved"), "stale").await()
            assertNull(store.load(old.copy(sessionId = "unobserved")).await())
            assertEquals("keep", store.load(other).await())
            val fresh = old.copy(scope = old.scope.copy(inMemoryAuthGeneration = generation + 1))
            store.save(fresh, "fresh login").await()
            store.save(old, "late old flush").await()
            assertNull(store.load(old).await())
            assertEquals("fresh login", store.load(fresh).await())
        }

    @Test
    fun `failed commit is reported and later queued commands still work`() =
        runTest {
            val store = store(backgroundScope)
            failCommit = true
            try {
                store.save(key, "not saved").await()
                fail("Failed commit must fail the deferred")
            } catch (failure: IllegalStateException) {
                assertTrue(failure.message!!.contains("Could not save"))
            }
            failCommit = false
            assertNull(store.load(key).await())
            store.save(key, "saved").await()
            assertEquals("saved", store.load(key).await())
        }
}
