package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.local.AuthManager
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [WsProfileParams] — the WS-layer analog of
 * [com.m57.hermescontrol.data.remote.ProfileScopeInterceptor].
 *
 * Covers the three injection rules:
 *  1. Scoped method + active profile → `"profile"` injected.
 *  2. Non-scoped method → params unchanged (same reference).
 *  3. Explicit `"profile"` in params → preserved, never overwritten.
 *  4. Null selected profile → params unchanged (same reference).
 */
class WsProfileParamsTest {
    @Before
    fun setUp() {
        mockkObject(AuthManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── Rule 1: scoped method gets profile injected ─────────────────────

    @Test
    fun `scoped method injects profile when active profile is set`() {
        every { AuthManager.getSelectedProfileId() } returns "meow"

        val original = mapOf("session_id" to "abc123")
        val result = WsProfileParams.decorate(WsMethods.SESSION_LIST, original)

        assertEquals("meow", result["profile"])
        assertEquals("abc123", result["session_id"])
    }

    @Test
    fun `session_create gets profile injected`() {
        every { AuthManager.getSelectedProfileId() } returns "work"

        val original = mapOf("cols" to 80)
        val result = WsProfileParams.decorate(WsMethods.SESSION_CREATE, original)

        assertEquals("work", result["profile"])
        assertEquals(80, result["cols"])
    }

    @Test
    fun `session_resume gets profile injected`() {
        every { AuthManager.getSelectedProfileId() } returns "meow"

        val original = mapOf("session_id" to "s1")
        val result = WsProfileParams.decorate(WsMethods.SESSION_RESUME, original)

        assertEquals("meow", result["profile"])
        assertEquals("s1", result["session_id"])
    }

    @Test
    fun `session_delete gets profile injected`() {
        every { AuthManager.getSelectedProfileId() } returns "work"

        val original = mapOf("session_id" to "s2")
        val result = WsProfileParams.decorate(WsMethods.SESSION_DELETE, original)

        assertEquals("work", result["profile"])
    }

    @Test
    fun `session_status gets profile injected`() {
        every { AuthManager.getSelectedProfileId() } returns "meow"

        val original = mapOf("session_id" to "s3")
        val result = WsProfileParams.decorate(WsMethods.SESSION_STATUS, original)

        assertEquals("meow", result["profile"])
    }

    @Test
    fun `profile_scoped decorator method gets profile injected`() {
        every { AuthManager.getSelectedProfileId() } returns "meow"

        val original = emptyMap<String, Any>()
        val result = WsProfileParams.decorate("verification.status", original)

        assertEquals("meow", result["profile"])
    }

    @Test
    fun `empty params map gets profile injected for scoped method`() {
        every { AuthManager.getSelectedProfileId() } returns "meow"

        val result = WsProfileParams.decorate(WsMethods.SESSION_LIST, emptyMap())

        assertEquals(1, result.size)
        assertEquals("meow", result["profile"])
    }

    // ── Rule 2: non-scoped method left untouched ────────────────────────

    @Test
    fun `non-scoped method returns same params reference`() {
        every { AuthManager.getSelectedProfileId() } returns "meow"

        val original = mapOf("session_id" to "s1", "text" to "hello")
        val result = WsProfileParams.decorate(WsMethods.PROMPT_SUBMIT, original)

        assertSame(original, result)
        assertNull(result["profile"])
    }

    @Test
    fun `subscription method is not profile scoped`() {
        every { AuthManager.getSelectedProfileId() } returns "meow"

        val original = mapOf("plan" to "pro")
        val result = WsProfileParams.decorate(WsMethods.SUBSCRIPTION_STATE, original)

        assertSame(original, result)
    }

    @Test
    fun `clarify_respond is not profile scoped`() {
        every { AuthManager.getSelectedProfileId() } returns "work"

        val original = mapOf("answer" to "yes")
        val result = WsProfileParams.decorate(WsMethods.CLARIFY_RESPOND, original)

        assertSame(original, result)
    }

    @Test
    fun `image_attach_bytes is not profile scoped`() {
        every { AuthManager.getSelectedProfileId() } returns "meow"

        val original = mapOf("data" to "base64data")
        val result = WsProfileParams.decorate(WsMethods.IMAGE_ATTACH_BYTES, original)

        assertSame(original, result)
    }

    @Test
    fun `commands_catalog is not profile scoped`() {
        every { AuthManager.getSelectedProfileId() } returns "meow"

        val original = emptyMap<String, Any>()
        val result = WsProfileParams.decorate(WsMethods.COMMANDS_CATALOG, original)

        assertSame(original, result)
    }

    @Test
    fun `unknown method is not profile scoped`() {
        every { AuthManager.getSelectedProfileId() } returns "meow"

        val original = mapOf("foo" to "bar")
        val result = WsProfileParams.decorate("setup.wizard", original)

        assertSame(original, result)
    }

    // ── Rule 3: explicit profile param wins ─────────────────────────────

    @Test
    fun `explicit profile in params is never overwritten`() {
        every { AuthManager.getSelectedProfileId() } returns "meow"

        val original = mapOf("session_id" to "s1", "profile" to "custom")
        val result = WsProfileParams.decorate(WsMethods.SESSION_LIST, original)

        assertSame(original, result)
        assertEquals("custom", result["profile"])
    }

    @Test
    fun `explicit empty-string profile is preserved`() {
        every { AuthManager.getSelectedProfileId() } returns "meow"

        // Caller explicitly passes profile="" — the containsKey check
        // preserves this so the server sees the explicit value.
        val original = mapOf("session_id" to "s1", "profile" to "")
        val result = WsProfileParams.decorate(WsMethods.SESSION_LIST, original)

        assertSame(original, result)
        assertEquals("", result["profile"])
    }

    // ── Rule 4: null selected profile → pass-through ────────────────────

    @Test
    fun `null selected profile returns same params reference for scoped method`() {
        every { AuthManager.getSelectedProfileId() } returns null

        val original = mapOf("session_id" to "s1")
        val result = WsProfileParams.decorate(WsMethods.SESSION_LIST, original)

        assertSame(original, result)
        assertNull(result["profile"])
    }

    @Test
    fun `null selected profile returns same params for non-scoped method`() {
        every { AuthManager.getSelectedProfileId() } returns null

        val original = mapOf("text" to "hello")
        val result = WsProfileParams.decorate(WsMethods.PROMPT_SUBMIT, original)

        assertSame(original, result)
    }

    // ── Coverage: all scoped methods in the set ─────────────────────────

    @Test
    fun `all PROFILE_SCOPED_METHODS entries get profile injected`() {
        every { AuthManager.getSelectedProfileId() } returns "testprofile"

        for (method in WsMethods.PROFILE_SCOPED_METHODS) {
            val result = WsProfileParams.decorate(method, emptyMap())
            assertEquals(
                "Expected profile injection for method $method",
                "testprofile",
                result["profile"],
            )
        }
    }

    @Test
    fun `PROFILE_SCOPED_METHODS set is non-empty and contains expected core methods`() {
        assertTrue(WsMethods.PROFILE_SCOPED_METHODS.isNotEmpty())
        assertTrue(WsMethods.SESSION_LIST in WsMethods.PROFILE_SCOPED_METHODS)
        assertTrue(WsMethods.SESSION_CREATE in WsMethods.PROFILE_SCOPED_METHODS)
        assertTrue(WsMethods.SESSION_RESUME in WsMethods.PROFILE_SCOPED_METHODS)
        assertTrue(WsMethods.SESSION_DELETE in WsMethods.PROFILE_SCOPED_METHODS)
        assertTrue(WsMethods.SESSION_STATUS in WsMethods.PROFILE_SCOPED_METHODS)
        assertTrue("verification.status" in WsMethods.PROFILE_SCOPED_METHODS)
    }

    // ── Interaction: sendMessage() flows through send() ─────────────────
    // sendMessage() calls send(PROMPT_SUBMIT, ...) — PROMPT_SUBMIT is NOT
    // in the scoped set, so profile is correctly NOT injected.  The server
    // resolves prompt.submit against the already-resumed session, which
    // carries its own profile_home.

    @Test
    fun `prompt_submit is not in scoped set confirming sendMessage path is correct`() {
        every { AuthManager.getSelectedProfileId() } returns "meow"

        val params = mapOf("session_id" to "s1", "text" to "hello world")
        val result = WsProfileParams.decorate(WsMethods.PROMPT_SUBMIT, params)

        // prompt.submit is session-bound, not profile-scoped.
        assertSame(params, result)
    }
}
