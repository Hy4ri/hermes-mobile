package com.m57.hermescontrol.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testBotRosterMetaSerialization() {
        val meta =
            BotRosterMeta(
                title = "Literature Scout",
                description = "Scrapes arXiv and summarizes papers",
                avatar =
                    BotAvatarMeta(
                        shape = "circle",
                        color = "#4A90E2",
                        icon = "science",
                    ),
                hidden = false,
                groups = listOf("research", "ai-lab"),
                group = "research",
                created = 1718000000.0,
            )

        val encoded = json.encodeToString(meta)
        val decoded = json.decodeFromString<BotRosterMeta>(encoded)

        assertEquals("Literature Scout", decoded.title)
        assertEquals("Scrapes arXiv and summarizes papers", decoded.description)
        assertEquals("circle", decoded.avatar?.shape)
        assertEquals("#4A90E2", decoded.avatar?.color)
        assertEquals("science", decoded.avatar?.icon)
        assertEquals(false, decoded.hidden)
        assertEquals(listOf("research", "ai-lab"), decoded.groups)
        assertEquals("research", decoded.group)
        assertEquals(1718000000.0, decoded.created)
    }

    @Test
    fun testProfileInfoWithBotMetaExtraction() {
        val botMeta =
            BotRosterMeta(
                title = "Custom Bot Title",
                description = "Custom Bot Description",
                avatar = BotAvatarMeta(shape = "hexagon", color = "#E91E63", icon = "robot"),
                hidden = false,
            )

        val profile =
            ProfileInfo(
                name = "custom_bot",
                display_name = "Original Name",
                description = "Original Description",
                ui_meta = mapOf("hermes-bots" to json.encodeToJsonElement(botMeta)),
                canonical_session =
                    CanonicalSessionInfo(
                        id = "sess-123",
                        last_active = 1720000000.0,
                    ),
                worker_session =
                    ProfileWorkerSummary(
                        id = "worker-456",
                        source = "kanban",
                        title = "Task #42",
                        last_active = 1720000100.0,
                    ),
            )

        val extractedMeta = profile.botMeta(json)
        assertNotNull(extractedMeta)
        assertEquals("Custom Bot Title", extractedMeta?.title)
        assertEquals("Custom Bot Title", profile.effectiveTitle)
        assertEquals("Custom Bot Description", profile.effectiveDescription)
        assertFalse(profile.isHidden)
        assertEquals("sess-123", profile.canonical_session?.id)
        assertEquals("worker-456", profile.worker_session?.id)
    }

    @Test
    fun testProfileInfoWithoutBotMeta_fallsBackToProfileFields() {
        val profile =
            ProfileInfo(
                name = "regular_profile",
                display_name = "My Profile",
                description = "Just a profile",
            )

        assertNull(profile.botMeta(json))
        assertEquals("My Profile", profile.effectiveTitle)
        assertEquals("Just a profile", profile.effectiveDescription)
        assertFalse(profile.isHidden)
    }

    @Test
    fun testProfileInfoHidden_derivedFromBotMeta() {
        val hiddenBot =
            ProfileInfo(
                name = "hidden_bot",
                ui_meta =
                    mapOf(
                        "hermes-bots" to
                            json.encodeToJsonElement(
                                BotRosterMeta(title = "Hidden", hidden = true),
                            ),
                    ),
            )

        val visibleBot =
            ProfileInfo(
                name = "visible_bot",
                ui_meta =
                    mapOf(
                        "hermes-bots" to
                            json.encodeToJsonElement(
                                BotRosterMeta(title = "Visible", hidden = false),
                            ),
                    ),
            )

        assertTrue(hiddenBot.isHidden)
        assertFalse(visibleBot.isHidden)
    }

    @Test
    fun testProfileInfoDefault_fallbackNames() {
        val profile = ProfileInfo(name = "default")

        assertNull(profile.botMeta(json))
        assertEquals("default", profile.effectiveTitle)
        assertEquals("", profile.effectiveDescription)
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
                                members = listOf(JsonPrimitive("default"), JsonPrimitive("researcher")),
                                picture = null,
                                log =
                                    listOf(
                                        GroupChatSyncLogEntry(
                                            id = "msg-1",
                                            from = GroupChatSyncFrom(kind = "user", name = "You"),
                                            text = "Let's review the code",
                                            at = 1724000000L,
                                        ),
                                    ),
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
        val room = decoded.rooms?.get("room-1")
        assertEquals("War Room", room?.name)
        assertEquals(listOf("default", "researcher"), room?.memberNames)
        assertEquals(1, room?.log?.size)
        assertEquals("Let's review the code", room?.log?.first()?.text)
        assertEquals(1723500000L, decoded.deleted?.get("old-room"))
    }
}
