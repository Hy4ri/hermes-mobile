package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyFailureDetailsTest {
    @Test(timeout = 2_000)
    fun longNonMatchingDiagnosticDoesNotBlockUi() {
        assertEquals("x".repeat(8_192), sanitizeReplyErrorDetails("x".repeat(100_000)))
    }

    @Test
    fun redactsCredentialsAndUrlsBeforeDisplayOrExport() {
        val raw =
            """
            Provider failed with HTTP 429
            Authorization: Bearer private-bearer
            Cookie: sid=private-cookie; csrf=private-csrf
            {"api_key":"private-key", "password": "private password", "access_token": "private-access"}
            https://user:private-pass@example.test/path?token=private-query
            OPENAI_API_KEY=private-env
            -----BEGIN PRIVATE KEY-----
            private-key-material
            -----END PRIVATE KEY-----
            """.trimIndent()
        val details = sanitizeReplyErrorDetails(raw)
        assertTrue(details.contains("HTTP 429"))
        assertTrue(details.contains("[REDACTED]"))
        assertFalse(details.contains("private-"))
        assertFalse(details.contains("private password"))
    }

    @Test
    fun limitsDetailsAndHandlesMissingMessage() {
        assertTrue(sanitizeReplyErrorDetails("x".repeat(100_000)).length <= 8_192)
        assertEquals("Unknown gateway error", sanitizeReplyErrorDetails(null))
        assertEquals("Unknown gateway error", sanitizeReplyErrorDetails("   "))
    }

    @Test
    fun structuredSurfaceExportsOnlyAllowedMetadataAndSanitizedError() {
        val failure =
            replyFailureFromPayload(
                mapOf(
                    "status" to "error",
                    "error" to "HTTP 429",
                    "error_surface" to
                        mapOf(
                            "provider" to "example",
                            "code" to "rate_limit",
                            "unknown_field" to "private-information",
                            "message" to "private-conversation",
                        ),
                ),
                "fallback",
            )
        assertEquals("code: rate_limit\nprovider: example\nHTTP 429", failure?.details)
    }
}
