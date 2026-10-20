package com.m57.hermescontrol.data.session

import android.content.SharedPreferences
import com.m57.hermescontrol.data.model.SessionLiveStatus
import com.m57.hermescontrol.data.ws.WsEvent
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Issue #1642: Done/Failed marks, their persistence, and every path that clears them. */
class SessionAttentionStoreTest {
    private val storage = mutableMapOf<String, Any?>()
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        storage.clear()
        prefs = fakePrefs(storage)
        SessionAttentionStore.resetForTesting(prefs)
    }

    @After
    fun tearDown() {
        SessionAttentionStore.resetForTesting(null)
    }

    @Test
    fun `completion marks Done and error marks Failed`() {
        SessionAttentionStore.markFinished("s", "a", failed = false)
        SessionAttentionStore.markFinished("s", "b", failed = true)
        assertEquals(
            mapOf("a" to SessionLiveStatus.DONE, "b" to SessionLiveStatus.FAILED),
            SessionAttentionStore.marksFor("s"),
        )
    }

    @Test
    fun `marks survive a restart`() {
        SessionAttentionStore.markFinished("s", "a", failed = true)
        SessionAttentionStore.resetForTesting(prefs)
        assertEquals(mapOf("a" to SessionLiveStatus.FAILED), SessionAttentionStore.marksFor("s"))
    }

    @Test
    fun `opening the session in Chat returns it to a normal row`() {
        SessionAttentionStore.markFinished("s", "a", failed = false)
        SessionAttentionStore.setViewing("s", "a")
        assertTrue(SessionAttentionStore.marksFor("s").isEmpty())
        // The cleared state is persisted too.
        SessionAttentionStore.resetForTesting(prefs)
        assertTrue(SessionAttentionStore.marksFor("s").isEmpty())
    }

    @Test
    fun `a completion for the session on screen is not marked`() {
        SessionAttentionStore.setViewing("s", "a")
        SessionAttentionStore.markFinished("s", "a", failed = false)
        assertTrue(SessionAttentionStore.marksFor("s").isEmpty())
    }

    @Test
    fun `leaving Chat lets later completions mark the session again`() {
        SessionAttentionStore.setViewing("s", "a")
        SessionAttentionStore.setViewing(null, null)
        SessionAttentionStore.markFinished("s", "a", failed = false)
        assertEquals(mapOf("a" to SessionLiveStatus.DONE), SessionAttentionStore.marksFor("s"))
    }

    @Test
    fun `viewing a session in another scope does not clear or suppress this scope`() {
        SessionAttentionStore.markFinished("s1", "a", failed = false)
        SessionAttentionStore.setViewing("s2", "a")
        SessionAttentionStore.markFinished("s1", "b", failed = false)
        assertEquals(setOf("a", "b"), SessionAttentionStore.marksFor("s1").keys)
    }

    @Test
    fun `snapshot guess never overwrites an event mark`() {
        SessionAttentionStore.markFinished("s", "a", failed = true)
        SessionAttentionStore.markFinished("s", "a", failed = false, overwrite = false)
        assertEquals(mapOf("a" to SessionLiveStatus.FAILED), SessionAttentionStore.marksFor("s"))
    }

    @Test
    fun `clear and clearAll remove marks`() {
        SessionAttentionStore.markFinished("s", "a", failed = false)
        SessionAttentionStore.markFinished("s", "b", failed = false)
        SessionAttentionStore.clear("s", "a")
        assertEquals(setOf("b"), SessionAttentionStore.marksFor("s").keys)
        SessionAttentionStore.clearAll()
        assertTrue(SessionAttentionStore.marks.value.isEmpty())
        assertTrue(storage.isEmpty())
    }

    // ── Recorder ───────────────────────────────────────────────────────

    private fun recorder(active: Map<String, String> = emptyMap()) =
        SessionAttentionRecorder(currentScopeKey = { "s" }, activeRuntimeToStored = { active[it] })

    private fun complete(
        runtimeId: String?,
        status: String? = "complete",
        storedId: String? = null,
    ) = WsEvent.MessageComplete(
        text = "ok",
        sessionId = runtimeId,
        storedSessionId = storedId,
        rawPayload = status?.let { mapOf("status" to it) },
    )

    @Test
    fun `recorder resolves the runtime id through session info`() {
        val r = recorder()
        r.onEvent(WsEvent.SessionInfo(mapOf("session_id" to "rt-1", "stored_session_id" to "stored-1")))
        r.onEvent(complete("rt-1"))
        assertEquals(mapOf("stored-1" to SessionLiveStatus.DONE), SessionAttentionStore.marksFor("s"))
    }

    @Test
    fun `recorder marks an error completion as Failed`() {
        recorder().onEvent(complete("rt-1", status = "error", storedId = "stored-1"))
        assertEquals(mapOf("stored-1" to SessionLiveStatus.FAILED), SessionAttentionStore.marksFor("s"))
    }

    @Test
    fun `recorder ignores an interrupted turn`() {
        recorder().onEvent(complete("rt-1", status = "interrupted", storedId = "stored-1"))
        assertTrue(SessionAttentionStore.marksFor("s").isEmpty())
    }

    @Test
    fun `recorder falls back to the active chat mapping and skips unknown runtimes`() {
        val r = recorder(active = mapOf("rt-active" to "stored-active"))
        r.onEvent(complete("rt-active"))
        r.onEvent(complete("rt-unknown"))
        assertEquals(mapOf("stored-active" to SessionLiveStatus.DONE), SessionAttentionStore.marksFor("s"))
    }

    @Test
    fun `recorder clears an old mark when a new turn starts`() {
        SessionAttentionStore.markFinished("s", "stored-1", failed = true)
        val r = recorder()
        r.onEvent(WsEvent.SessionInfo(mapOf("session_id" to "rt-1", "stored_session_id" to "stored-1")))
        r.onEvent(WsEvent.MessageStart(sessionId = "rt-1"))
        assertTrue(SessionAttentionStore.marksFor("s").isEmpty())
    }

    private fun fakePrefs(map: MutableMap<String, Any?>): SharedPreferences {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val p = mockk<SharedPreferences>(relaxed = true)
        every { p.edit() } returns editor
        every { p.all } answers { map.toMap() }
        every { editor.putStringSet(any(), any()) } answers {
            map[firstArg()] = secondArg<Set<String>>().toSet()
            editor
        }
        every { editor.remove(any()) } answers {
            map.remove(firstArg<String>())
            editor
        }
        every { editor.clear() } answers {
            map.clear()
            editor
        }
        return p
    }
}
