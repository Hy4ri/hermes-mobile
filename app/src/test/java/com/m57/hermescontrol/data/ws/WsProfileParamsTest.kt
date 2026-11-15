package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.local.AuthManager
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
        // NOTE: deliberately NO mockkObject(AuthManager) — see
        // ProfileScopeInterceptorTest for why; the real prefs-backed state is
        // used instead (setActiveProfileId is runCatching-safe pre-init).
        AuthManager.resetAuthStateForTest()
    }

    @After
    fun tearDown() {
        AuthManager.resetAuthStateForTest()
    }

    // ── Rule 1: scoped method gets profile injected ─────────────────────

    @Test
    fun `scoped method injects profile when active profile is set`() {
        AuthManager.setActiveProfileId("meow")

        val original = mapOf("session_id" to "abc123")
        val result = WsProfileParams.decorate(WsMethods.SESSION_LIST, original)

        assertEquals("meow", result["profile"])
        assertEquals("abc123", result["session_id"])
    }

    @Test
    fun `session_create gets profile injected`() {
        AuthManager.setActiveProfileId("work")

        val original = mapOf("cols" to 80)
        val result = WsProfileParams.decorate(WsMethods.SESSION_CREATE, original)

        assertEquals("work", result["profile"])
        assertEquals(80, result["cols"])
    }

    @Test
    fun `session_resume gets profile injected`() {
        AuthManager.setActiveProfileId("meow")

        val original = mapOf("session_id" to "s1")
        val result = WsProfileParams.decorate(WsMethods.SESSION_RESUME, original)

        assertEquals("meow", result["profile"])
        assertEquals("s1", result["session_id"])
    }

    @Test
    fun `session_delete gets profile injected`() {
        AuthManager.setActiveProfileId("work")

        val original = mapOf("session_id" to "s2")
        val result = WsProfileParams.decorate(WsMethods.SESSION_DELETE, original)

        assertEquals("work", result["profile"])
    }

    @Test
    fun `session_status gets profile injected`() {
        AuthManager.setActiveProfileId("meow")

        val original = mapOf("session_id" to "s3")
        val result = WsProfileParams.decorate(WsMethods.SESSION_STATUS, original)

        assertEquals("meow", result["profile"])
    }

    @Test
    fun `config get and set are profile scoped`() {
        AuthManager.setActiveProfileId("work")

        val getResult = WsProfileParams.decorate(WsMethods.CONFIG_GET, mapOf("key" to "reasoning"))
        val setResult =
            WsProfileParams.decorate(
                WsMethods.CONFIG_SET,
                mapOf("key" to "reasoning", "value" to "high"),
            )

        assertEquals("work", getResult["profile"])
        assertEquals("work", setResult["profile"])
    }

    @Test
    fun `profile_scoped decorator method gets profile injected`() {
        AuthManager.setActiveProfileId("meow")

        val original = emptyMap<String, Any>()
        val result = WsProfileParams.decorate("verification.status", original)

        assertEquals("meow", result["profile"])
    }

    @Test
    fun `empty params map gets profile injected for scoped method`() {
        AuthManager.setActiveProfileId("meow")

        val result = WsProfileParams.decorate(WsMethods.SESSION_LIST, emptyMap())

        assertEquals(1, result.size)
        assertEquals("meow", result["profile"])
    }

    // ── Rule 2: non-scoped method left untouched ────────────────────────

    @Test
    fun `non-scoped method returns same params reference`() {
        AuthManager.setActiveProfileId("meow")

        // prompt.submit is profile-scoped now; use a global method to check the rule.
        val original = mapOf("plan" to "pro")
        val result = WsProfileParams.decorate(WsMethods.USAGE_BARS, original)

        assertSame(original, result)
        assertNull(result["profile"])
    }

    @Test
    fun `subscription method is not profile scoped`() {
        AuthManager.setActiveProfileId("meow")

        val original = mapOf("plan" to "pro")
        val result = WsProfileParams.decorate(WsMethods.SUBSCRIPTION_STATE, original)

        assertSame(original, result)
    }

    @Test
    fun `clarify_respond is profile scoped`() {
        // clarify.respond is in _PROFILE_IMPLICIT: for its own profile the server strips the key,
        // for another one it routes the answer to the session's owning profile.
        AuthManager.setActiveProfileId("work")

        val original = mapOf("answer" to "yes")
        val result = WsProfileParams.decorate(WsMethods.CLARIFY_RESPOND, original)

        assertEquals(mapOf("answer" to "yes", "profile" to "work"), result)
    }

    @Test
    fun `image_attach_bytes is profile scoped`() {
        // The attachment is bound to session_id: without profile a non-launch profile's session is not found.
        AuthManager.setActiveProfileId("meow")

        val original = mapOf("session_id" to "s1", "data" to "base64data")
        val result = WsProfileParams.decorate(WsMethods.IMAGE_ATTACH_BYTES, original)

        assertEquals("meow", result["profile"])
        assertEquals("base64data", result["data"])
    }

    @Test
    fun `commands_catalog is profile scoped`() {
        // #1556: methods_tools.py binds a session-less draft catalog to params['profile'].
        AuthManager.setActiveProfileId("meow")

        val result = WsProfileParams.decorate(WsMethods.COMMANDS_CATALOG, emptyMap())

        assertEquals(mapOf("profile" to "meow"), result)
    }

    @Test
    fun `connectors_list is profile scoped`() {
        // The params carry session_id, so the session's owning profile is needed.
        AuthManager.setActiveProfileId("meow")

        val original = mapOf("session_id" to "sess-123")
        val result = WsProfileParams.decorate(WsMethods.CONNECTORS_LIST, original)

        assertEquals(mapOf("session_id" to "sess-123", "profile" to "meow"), result)
    }

    @Test
    fun `connectors_connect is profile scoped`() {
        AuthManager.setActiveProfileId("meow")

        val original = mapOf("session_id" to "sess-123", "connectors" to listOf("linear"), "reconnect" to false)
        val result = WsProfileParams.decorate(WsMethods.CONNECTORS_CONNECT, original)

        assertEquals("meow", result["profile"])
        assertEquals("sess-123", result["session_id"])
    }

    @Test
    fun `unknown method is not profile scoped`() {
        AuthManager.setActiveProfileId("meow")

        val original = mapOf("foo" to "bar")
        val result = WsProfileParams.decorate("setup.wizard", original)

        assertSame(original, result)
    }

    // ── Rule 3: explicit profile param wins ─────────────────────────────

    @Test
    fun `explicit profile in params is never overwritten`() {
        AuthManager.setActiveProfileId("meow")

        val original = mapOf("session_id" to "s1", "profile" to "custom")
        val result = WsProfileParams.decorate(WsMethods.SESSION_LIST, original)

        assertSame(original, result)
        assertEquals("custom", result["profile"])
    }

    @Test
    fun `explicit empty-string profile is preserved`() {
        AuthManager.setActiveProfileId("meow")

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
        AuthManager.setActiveProfileId(null)

        val original = mapOf("session_id" to "s1")
        val result = WsProfileParams.decorate(WsMethods.SESSION_LIST, original)

        assertSame(original, result)
        assertNull(result["profile"])
    }

    @Test
    fun `null selected profile returns same params for non-scoped method`() {
        AuthManager.setActiveProfileId(null)

        val original = mapOf("text" to "hello")
        val result = WsProfileParams.decorate(WsMethods.PROMPT_SUBMIT, original)

        assertSame(original, result)
    }

    // ── Coverage: all scoped methods in the set ─────────────────────────

    @Test
    fun `all PROFILE_SCOPED_METHODS entries get profile injected`() {
        AuthManager.setActiveProfileId("testprofile")

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

    /**
     * Phase-3 audit guard: EVERY scope-selecting RPC the app sends must be in
     * the scoped set. If a future WS method selects a profile (list/create/
     * resume/status/delete of sessions, or anything reading the profile's own
     * home) without being listed here, this test fails — the set cannot drift.
     *
     * Methods that operate on an ALREADY-BOUND session (session.usage,
     * session.interrupt, session.branch, session.branch_whole, session.redirect,
     * session.context_breakdown, prompt.submit, slash/command dispatch,
     * approval/clarify/secret/sudo responses, file attach, process.*,
     * subscription.*, commands_catalog, usage.bars) resolve via the
     * connection's session, which carries profile_home from create/resume —
     * they are profile-correct by construction and must NOT be listed here
     * (server evidence: methods_session.py — session.get() based).
     *
     * Note: on the multi-profile gateway (session_controls.py, 0.23.x) this no longer
     * holds: session-bound methods must carry `profile` too, so they are in
     * PROFILE_SCOPED_METHODS (see `every session-bound method carries the active profile`).
     * Legacy sudo/secret/vault *.respond and subscription.* / usage.bars stay unscoped.
     */
    @Test
    fun `every scope-selecting method the app sends is in the scoped set`() {
        val scopeSelectingMethods =
            setOf(
                WsMethods.SESSION_CREATE,
                WsMethods.SESSION_LIST,
                WsMethods.SESSION_RESUME,
                WsMethods.SESSION_DELETE,
                WsMethods.SESSION_STATUS,
                "verification.status",
                "pet.info",
                "pet.info.meta",
                "pet.cells",
                "pet.gallery",
                "pet.select",
            )
        for (method in scopeSelectingMethods) {
            assertTrue(
                "$method is scope-selecting but missing from PROFILE_SCOPED_METHODS",
                method in WsMethods.PROFILE_SCOPED_METHODS,
            )
        }
    }

    // ── Interaction: sendMessage() flows through send() ─────────────────
    // sendMessage() calls send(PROMPT_SUBMIT, ...). The multi-profile gateway
    // (session_controls.py) looks a session up in the WS ticket's profile without `profile`,
    // so prompt.submit on a non-launch profile got 4001 permission_denied.
    // The profile is injected now; for its own profile the server strips the key itself
    // (_PROFILE_IMPLICIT).

    @Test
    fun `prompt_submit carries the active profile`() {
        AuthManager.setActiveProfileId("hermisa")

        val params = mapOf("session_id" to "s1", "text" to "hello world")
        val result = WsProfileParams.decorate(WsMethods.PROMPT_SUBMIT, params)

        assertEquals(mapOf("session_id" to "s1", "text" to "hello world", "profile" to "hermisa"), result)
    }

    /**
     * Regression: on a non-launch profile session-bound RPCs went out without `profile`, and the
     * server resolved them against the default profile (not_found / permission_denied). Every method
     * bound to a specific session, process or request must carry the profile.
     */
    @Test
    fun `every session-bound method carries the active profile`() {
        AuthManager.setActiveProfileId("hermisa")
        val sessionBound =
            listOf(
                WsMethods.SESSION_INTERRUPT,
                WsMethods.SESSION_REDIRECT,
                WsMethods.SESSION_STEER,
                WsMethods.SESSION_TITLE,
                WsMethods.SESSION_BRANCH,
                WsMethods.SESSION_BRANCH_WHOLE,
                WsMethods.SESSION_COMPRESS,
                WsMethods.SESSION_EVENTS_SINCE,
                WsMethods.SESSION_CONTEXT_BREAKDOWN,
                WsMethods.SESSION_USAGE,
                WsMethods.SESSION_STATS,
                WsMethods.SESSION_MUTATE,
                WsMethods.SESSION_ACTIVE_LIST,
                WsMethods.PROMPT_SUBMIT,
                WsMethods.PROMPT_CANCEL,
                WsMethods.PROMPT_RECEIPT,
                WsMethods.PROMPT_BTW,
                WsMethods.CLARIFY_RESPOND,
                WsMethods.CLARIFY_LOCK,
                WsMethods.APPROVAL_RESPOND,
                WsMethods.APPROVAL_RECEIVED,
                WsMethods.APPROVAL_PENDING,
                WsMethods.COMMAND_DISPATCH,
                WsMethods.SLASH_EXEC,
                WsMethods.IMAGE_ATTACH_BYTES,
                WsMethods.FILE_ATTACH,
                WsMethods.PROCESS_LIST,
                WsMethods.PROCESS_KILL,
                WsMethods.PROCESS_STOP,
                WsMethods.SUBAGENT_LIST,
                WsMethods.SUBAGENT_TAIL,
                WsMethods.SUBAGENT_INTERRUPT,
                WsMethods.SUBAGENT_STEER,
            )
        for (method in sessionBound) {
            val result = WsProfileParams.decorate(method, mapOf("session_id" to "s1"))
            assertEquals("$method must carry the active profile", "hermisa", result["profile"])
            assertEquals("s1", result["session_id"])
        }
    }

    @Test
    fun `session-bound method without active profile is unchanged`() {
        // No profile selected (single-profile mode): behavior is unchanged.
        AuthManager.setActiveProfileId(null)

        val params = mapOf("session_id" to "s1")
        for (method in listOf(WsMethods.PROMPT_SUBMIT, WsMethods.PROCESS_LIST, WsMethods.SESSION_USAGE)) {
            assertSame(params, WsProfileParams.decorate(method, params))
        }
    }
}
