package com.m57.hermescontrol.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayMultiplexSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `status decodes shared gateway profile names`() {
        val response =
            json.decodeFromString<StatusResponse>(
                """
                {
                    "gateway_running": true,
                    "gateway_shared_with": ["default", "coding"]
                }
                """.trimIndent(),
            )

        assertEquals(listOf("default", "coding"), response.gatewaySharedWith)
    }

    @Test
    fun `status accepts null and absent shared gateway field`() {
        val nullResponse = json.decodeFromString<StatusResponse>("""{"gateway_shared_with": null}""")
        val oldResponse = json.decodeFromString<StatusResponse>("""{"gateway_running": true}""")

        assertNull(nullResponse.gatewaySharedWith)
        assertNull(oldResponse.gatewaySharedWith)
    }

    @Test
    fun `messaging platform decodes backend ingress url`() {
        val platform =
            json.decodeFromString<MessagingPlatform>(
                """
                {
                    "id": "telegram",
                    "name": "Telegram",
                    "enabled": true,
                    "configured": true,
                    "ingress_url": "/some/backend/value"
                }
                """.trimIndent(),
            )

        assertEquals("/some/backend/value", platform.ingressUrl)
    }

    @Test
    fun `messaging platform update decodes hot served and old response safely`() {
        val hotServed =
            json.decodeFromString<MessagingPlatformUpdateResponse>(
                """{"ok": true, "platform": "telegram", "hot_served": true}""",
            )
        val oldResponse =
            json.decodeFromString<MessagingPlatformUpdateResponse>("""{"ok": true, "platform": "telegram"}""")

        assertTrue(hotServed.ok)
        assertEquals("telegram", hotServed.platform)
        assertTrue(hotServed.hotServed)
        assertFalse(oldResponse.hotServed)
    }

    @Test
    fun `migration plan decodes operational states and ignores low level fields in ui`() {
        val plan =
            json.decodeFromString<GatewayMigrationPlan>(
                """
                {
                    "default_home": "/home/hermes",
                    "profiles": [
                        {
                            "profile": "default",
                            "home": "/home/hermes",
                            "pid": 1234,
                            "service": {"kind": "systemd", "system": false},
                            "uid": 1000,
                            "runtime_home": "/home/hermes"
                        },
                        {"profile": "coding"}
                    ],
                    "multiplex_flag_on": false,
                    "live_served": null,
                    "already_multiplexed": false,
                    "interrupted": true,
                    "blockers": ["duplicate credential"],
                    "notices": ["callback URL changes"],
                    "eligible": false,
                    "command": "hermes gateway migrate --multiplex"
                }
                """.trimIndent(),
            )

        assertEquals(listOf("default", "coding"), plan.profiles.map { it.profile })
        assertEquals(1234, plan.profiles.first().pid)
        assertTrue(plan.interrupted)
        assertEquals(listOf("duplicate credential"), plan.blockers)
        assertEquals(listOf("callback URL changes"), plan.notices)
        assertFalse(plan.eligible)
    }
}
