package com.m57.hermescontrol.data.model

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSerializationTest {
    private val json = OkHttpProvider.json

    @Test
    fun testProfileInfoDeserializationWithBotMetadata() {
        val jsonStr =
            """
            {
                "name": "researcher",
                "path": "/home/user/.hermes/profiles/researcher",
                "is_default": false,
                "model": "anthropic/claude-3-7-sonnet",
                "provider": "anthropic",
                "skill_count": 5,
                "description": "Base description",
                "display_name": "Researcher Agent",
                "has_avatar": true,
                "canonical_session": {
                    "id": "sess-canon-123",
                    "resolved_id": "sess-canon-tip",
                    "root_title": "Bot Chat",
                    "title": "Bot Chat",
                    "preview": "Latest research report ready.",
                    "started_at": 1700000000,
                    "last_active": 1700005000,
                    "message_count": 42
                },
                "worker_session": {
                    "id": "worker-456",
                    "source": "kanban",
                    "title": "Background analysis",
                    "last_active": 1700006000
                },
                "last_session": {
                    "id": "last-789",
                    "title": "Ad-hoc task",
                    "preview": "Done!",
                    "started_at": 1700001000,
                    "last_active": 1700002000,
                    "message_count": 10
                },
                "ui_meta": {
                    "hermes-bots": {
                        "title": "Dr. Researcher",
                        "description": "Deep literature scout",
                        "avatar": {
                            "shape": "hexagon",
                            "color": "#4A90E2",
                            "icon": "science",
                            "image_url": "https://example.com/avatar.png"
                        },
                        "hidden": false,
                        "groups": ["science-team", "daily-sync"]
                    }
                }
            }
            """.trimIndent()

        val profile = json.decodeFromString<ProfileInfo>(jsonStr)
        assertEquals("researcher", profile.name)
        assertEquals("Researcher Agent", profile.display_name)
        assertTrue(profile.has_avatar == true)

        // Canonical session
        val canon = profile.canonical_session
        assertNotNull(canon)
        assertEquals("sess-canon-123", canon?.id)
        assertEquals("sess-canon-tip", canon?.resolved_id)
        assertEquals("Latest research report ready.", canon?.preview)
        assertEquals(42, canon?.message_count)

        // Worker session
        val worker = profile.worker_session
        assertNotNull(worker)
        assertEquals("worker-456", worker?.id)
        assertEquals("kanban", worker?.source)

        // Bot metadata decoding
        val botMeta = profile.botMeta(json)
        assertNotNull(botMeta)
        assertEquals("Dr. Researcher", botMeta?.title)
        assertEquals("Deep literature scout", botMeta?.description)
        assertEquals("hexagon", botMeta?.avatar?.shape)
        assertEquals("#4A90E2", botMeta?.avatar?.color)
        assertEquals(listOf("science-team", "daily-sync"), botMeta?.groups)

        // Helpers
        assertEquals("Dr. Researcher", profile.effectiveTitle)
        assertEquals("Deep literature scout", profile.effectiveDescription)
        assertFalse(profile.isHidden)
    }

    @Test
    fun testProfileInfoBackwardCompatibility() {
        val legacyJson =
            """
            {
                "name": "default",
                "is_default": true,
                "model": "openai/gpt-4o",
                "provider": "openai",
                "description": "Default Assistant"
            }
            """.trimIndent()

        val profile = json.decodeFromString<ProfileInfo>(legacyJson)
        assertEquals("default", profile.name)
        assertTrue(profile.is_default == true)
        assertNull(profile.canonical_session)
        assertNull(profile.worker_session)
        assertNull(profile.last_session)
        assertNull(profile.ui_meta)
        assertNull(profile.botMeta(json))
        assertEquals("default", profile.effectiveTitle)
        assertEquals("Default Assistant", profile.effectiveDescription)
        assertFalse(profile.isHidden)
    }

    @Test
    fun testGroupChatSyncSnapshotSerialization() {
        val snapshot =
            GroupChatSyncSnapshot(
                version = 3,
                updatedAt = 1724000000L,
                rooms =
                    mapOf(
                        "room-1" to
                            GroupChatRoomMeta(
                                id = "room-1",
                                name = "War Room",
                                members = listOf("default", "researcher"),
                                picture = null,
                                updatedAt = 1724000000L,
                                createdAt = 1723000000L,
                            ),
                    ),
                deleted = mapOf("old-room" to 1723500000L),
            )

        val encoded = json.encodeToString(snapshot)
        val decoded = json.decodeFromString<GroupChatSyncSnapshot>(encoded)
        assertEquals(3, decoded.version)
        assertEquals(1, decoded.rooms?.size)
        assertEquals("War Room", decoded.rooms?.get("room-1")?.name)
        assertEquals(listOf("default", "researcher"), decoded.rooms?.get("room-1")?.members)
        assertEquals(1723500000L, decoded.deleted?.get("old-room"))
    }
}
