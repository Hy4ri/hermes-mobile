package com.m57.hermescontrol.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the per-server token resolution that makes profile switching
 * restart-safe without a re-login.
 *
 * Rule: a profile without its own token inherits the connection (default)
 * token — same dashboard = same auth.
 */
class AuthManagerTokenTest {
    @Test
    fun `selected profile with own token keeps it`() {
        val token = AuthManager.resolveConnectionToken("meow") { id -> if (id == "meow") "tok-meow" else null }
        assertEquals("tok-meow", token)
    }

    @Test
    fun `selected profile without own token inherits default token`() {
        val token =
            AuthManager.resolveConnectionToken("meow") { id ->
                if (id == AuthManager.DEFAULT_PROFILE_ID) "tok-conn" else null
            }
        assertEquals("tok-conn", token)
    }

    @Test
    fun `default profile uses its own token`() {
        val token =
            AuthManager.resolveConnectionToken(AuthManager.DEFAULT_PROFILE_ID) { id ->
                if (id == AuthManager.DEFAULT_PROFILE_ID) "tok-conn" else null
            }
        assertEquals("tok-conn", token)
    }

    @Test
    fun `null selection falls back to default token`() {
        val token =
            AuthManager.resolveConnectionToken(null) { id ->
                if (id == AuthManager.DEFAULT_PROFILE_ID) "tok-conn" else null
            }
        assertEquals("tok-conn", token)
    }

    @Test
    fun `blank selection treated as default`() {
        val token =
            AuthManager.resolveConnectionToken("  ") { id ->
                if (id == AuthManager.DEFAULT_PROFILE_ID) "tok-conn" else null
            }
        assertEquals("tok-conn", token)
    }

    @Test
    fun `no tokens anywhere returns null`() {
        assertNull(AuthManager.resolveConnectionToken("meow") { null })
        assertNull(AuthManager.resolveConnectionToken(null) { null })
    }

    @Test
    fun `own token beats default token when both exist`() {
        val token =
            AuthManager.resolveConnectionToken("meow") { id ->
                when (id) {
                    "meow" -> "tok-meow"
                    AuthManager.DEFAULT_PROFILE_ID -> "tok-conn"
                    else -> null
                }
            }
        assertEquals("tok-meow", token)
    }
}
