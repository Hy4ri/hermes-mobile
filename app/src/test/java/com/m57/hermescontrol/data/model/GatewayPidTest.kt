package com.m57.hermescontrol.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for StatusResponse gateway_pid decoding (S1559).
 *
 * Verifies that:
 * - Nullable positive gateway_pid decodes correctly when present in JSON.
 * - Missing gateway_pid field defaults safely to null.
 * - Explicit null gateway_pid field decodes to null.
 * - Round-trip serialization preserves gateway_pid.
 */
class GatewayPidTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    @Test
    fun `decodes positive gateway_pid when present in status response`() {
        val raw =
            """
            {
                "version": "1.0.0",
                "gateway_running": true,
                "active_sessions": 2,
                "gateway_pid": 12345
            }
            """.trimIndent()

        val response = json.decodeFromString<StatusResponse>(raw)

        assertEquals(12345, response.gateway_pid)
        assertEquals(true, response.gateway_running)
        assertEquals("1.0.0", response.version)
        assertEquals(2, response.active_sessions)
    }

    @Test
    fun `missing gateway_pid field defaults to null`() {
        val raw =
            """
            {
                "version": "1.0.0",
                "gateway_running": true,
                "active_sessions": 0
            }
            """.trimIndent()

        val response = json.decodeFromString<StatusResponse>(raw)

        assertNull(response.gateway_pid)
        assertEquals(true, response.gateway_running)
    }

    @Test
    fun `explicit null gateway_pid decodes to null`() {
        val raw =
            """
            {
                "gateway_pid": null,
                "gateway_running": false
            }
            """.trimIndent()

        val response = json.decodeFromString<StatusResponse>(raw)

        assertNull(response.gateway_pid)
        assertEquals(false, response.gateway_running)
    }

    @Test
    fun `empty json decodes gateway_pid as null`() {
        val response = json.decodeFromString<StatusResponse>("{}")

        assertNull(response.gateway_pid)
    }

    @Test
    fun `round-trip serialization preserves positive gateway_pid`() {
        val original =
            StatusResponse(
                version = "2.0.0",
                gateway_running = true,
                gateway_pid = 45678,
            )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<StatusResponse>(encoded)

        assertEquals(45678, decoded.gateway_pid)
        assertEquals("2.0.0", decoded.version)
        assertEquals(true, decoded.gateway_running)
    }
}
