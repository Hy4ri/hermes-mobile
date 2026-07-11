package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.PathSuggestion
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsMethods
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PathCompletionTest {
    @Before
    fun setUp() {
        mockkObject(HermesWsClient)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── findPathToken ──────────────────────────────────────────────────────

    @Test
    fun `findPathToken returns trailing @-token`() {
        assertEquals("@file:", findPathToken("attach @file:"))
        assertEquals("@file:src/", findPathToken("check @file:src/"))
        assertEquals("@", findPathToken("type @"))
        assertEquals("@folder:", findPathToken("with @folder:"))
    }

    @Test
    fun `findPathToken returns null when no @ token or @ followed by space`() {
        assertNull(findPathToken("no token here"))
        assertNull(findPathToken("type @ done"))
        // A space after the `@` run ends the token, so it's not active
        assertNull(findPathToken("ping @ and then text"))
        // An `@` glued to a preceding word (email, annotation) is NOT a trigger
        assertNull(findPathToken("email me@host.com"))
        assertNull(findPathToken("call foo@bar now"))
    }

    // ── replaceActivePathToken ──────────────────────────────────────────────

    @Test
    fun `replaceActivePathToken swaps the trailing token`() {
        assertEquals(
            "attach @file:src/main.kt",
            replaceActivePathToken("attach @file:", "@file:src/main.kt"),
        )
        assertEquals(
            "check @folder:docs",
            replaceActivePathToken("check @folder:", "@folder:docs"),
        )
        // A token in the middle of the text (caret not at a trailing token) is
        // replaced at its own position; only the trailing @-run is the trigger.
        assertEquals(
            "use @file:a.kt",
            replaceActivePathToken("use @file:", "@file:a.kt"),
        )
    }

    @Test
    fun `replaceActivePathToken leaves text unchanged with no token`() {
        assertEquals("plain text", replaceActivePathToken("plain text", "@file:x"))
    }

    // ── PathSuggestion.fromMap ──────────────────────────────────────────────

    @Test
    fun `fromMap builds suggestion with dir flag from trailing slash`() {
        val s = PathSuggestion.fromMap(mapOf("text" to "@folder:src/", "display" to "src", "meta" to "dir"))
        assertEquals("@folder:src/", s?.text)
        assertEquals("src", s?.display)
        assertEquals(true, s?.isDirectory)
    }

    @Test
    fun `fromMap builds file suggestion and infers dir from meta`() {
        val s = PathSuggestion.fromMap(mapOf("text" to "@file:README.md", "display" to "README.md"))
        assertEquals("@file:README.md", s?.text)
        assertEquals("README.md", s?.display)
        assertEquals(false, s?.isDirectory)
    }

    @Test
    fun `fromMap returns null without text`() {
        assertNull(PathSuggestion.fromMap(mapOf("display" to "x")))
        assertNull(PathSuggestion.fromMap(mapOf("text" to "")))
    }

    // ── fetchPathSuggestions (RPC wiring) ───────────────────────────────────

    @Test
    fun `fetchPathSuggestions calls complete_path with session_id and word`() =
        runTest {
            val captured = mutableListOf<Map<String, Any>>()
            every {
                HermesWsClient.request(WsMethods.COMPLETE_PATH, capture(captured))
            } answers {
                CompletableDeferred(
                    mapOf(
                        "items" to
                            listOf(
                                mapOf("text" to "@file:src/main.kt", "display" to "main.kt"),
                                mapOf("text" to "@file:src/util.kt", "display" to "util.kt"),
                            ),
                    ),
                )
            }

            val result = fetchPathSuggestions("session-1", "@file:src/")

            assertEquals(1, captured.size)
            assertEquals("session-1", captured[0]["session_id"])
            assertEquals("@file:src/", captured[0]["word"])
            assertEquals(2, result.size)
            assertEquals("@file:src/main.kt", result[0].text)
            assertEquals("@file:src/util.kt", result[1].text)
        }

    @Test
    fun `fetchPathSuggestions returns empty list on RPC failure`() =
        runTest {
            every {
                HermesWsClient.request(WsMethods.COMPLETE_PATH, any())
            } answers {
                CompletableDeferred<Any?>(null).also { it.completeExceptionally(RuntimeException("boom")) }
            }

            val result = fetchPathSuggestions("session-1", "@file:")
            assertEquals(emptyList<PathSuggestion>(), result)
        }
}
