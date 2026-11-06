package com.m57.hermescontrol.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #461: hermes-agent v0.23.0 adds three Team Gateway flags to
 * `GET /api/messaging/platforms`. They must decode from the real row shape and
 * default to false on a backend older than that.
 */
class MessagingPlatformRelaySerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `relay flags decode from a relay fronted platform row`() {
        val platform =
            json.decodeFromString<MessagingPlatform>(
                """
                {
                    "id": "telegram",
                    "name": "Telegram",
                    "description": "Telegram bot",
                    "enabled": false,
                    "configured": true,
                    "state": "disabled",
                    "disabled_by_relay": true,
                    "relay_fronted": true,
                    "relay_exclusive": true
                }
                """.trimIndent(),
            )

        assertTrue(platform.disabledByRelay)
        assertTrue(platform.relayFronted)
        assertTrue(platform.relayExclusive)
    }

    @Test
    fun `relay flags default to false on an older backend`() {
        val platform =
            json.decodeFromString<MessagingPlatform>(
                """
                {
                    "id": "telegram",
                    "name": "Telegram",
                    "enabled": true,
                    "configured": true
                }
                """.trimIndent(),
            )

        assertFalse(platform.disabledByRelay)
        assertFalse(platform.relayFronted)
        assertFalse(platform.relayExclusive)
    }

    @Test
    fun `relay flags decode inside the platforms response`() {
        val response =
            json.decodeFromString<MessagingPlatformResponse>(
                """
                {
                    "env_path": "~/.hermes/.env",
                    "gateway_start_command": "hermes gateway start",
                    "platforms": [
                        {
                            "id": "slack",
                            "name": "Slack",
                            "enabled": false,
                            "configured": false,
                            "disabled_by_relay": false,
                            "relay_fronted": true,
                            "relay_exclusive": false
                        }
                    ]
                }
                """.trimIndent(),
            )

        assertEquals(1, response.platforms.size)
        val platform = response.platforms.single()
        assertTrue(platform.relayFronted)
        assertFalse(platform.relayExclusive)
        assertFalse(platform.disabledByRelay)
    }
}
