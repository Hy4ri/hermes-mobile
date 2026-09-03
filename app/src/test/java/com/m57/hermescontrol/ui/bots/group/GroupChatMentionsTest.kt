package com.m57.hermescontrol.ui.bots.group

import com.m57.hermescontrol.data.model.BotAvatarMeta
import com.m57.hermescontrol.data.model.BotRosterMeta
import com.m57.hermescontrol.data.model.ProfileInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupChatMentionsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val botA =
        ProfileInfo(
            name = "scoutbot",
            ui_meta =
                mapOf(
                    "hermes-bots" to
                        json.encodeToJsonElement(
                            BotRosterMeta(title = "Scout Bot", avatar = BotAvatarMeta()),
                        ),
                ),
        )

    private val botB =
        ProfileInfo(
            name = "coder",
            ui_meta =
                mapOf(
                    "hermes-bots" to
                        json.encodeToJsonElement(
                            BotRosterMeta(title = "Dev Bot", avatar = BotAvatarMeta()),
                        ),
                ),
        )

    private val members = listOf(botA, botB)

    @Test
    fun parseMentions_detectsDirectBotHandle() {
        val result = GroupChatMentions.parseMentions("Hey @scoutbot can you check this?", members)
        assertFalse(result.isEveryone)
        assertTrue(result.mentionedBots.contains("scoutbot"))
        assertFalse(result.mentionedBots.contains("coder"))
    }

    @Test
    fun parseMentions_detectsEveryoneAndAll() {
        val resEveryone = GroupChatMentions.parseMentions("Hello @everyone!", members)
        assertTrue(resEveryone.isEveryone)

        val resAll = GroupChatMentions.parseMentions("@all status update please", members)
        assertTrue(resAll.isEveryone)
    }

    @Test
    fun resolveResponders_defaultsToAllWhenNoMention() {
        val responders = GroupChatMentions.resolveResponders("General discussion message", members)
        assertEquals(2, responders.size)
    }

    @Test
    fun resolveResponders_filtersToMentionedOnly() {
        val responders = GroupChatMentions.resolveResponders("@coder build the apk", members)
        assertEquals(1, responders.size)
        assertEquals("coder", responders.first().name)
    }

    @Test
    fun groupChatMentionAutocomplete_endToEndIntegration() {
        // 1. User types "@c"
        val input =
            androidx.compose.ui.text.input
                .TextFieldValue(
                    "hey @c",
                    androidx.compose.ui.text
                        .TextRange(6),
                )
        val query =
            com.m57.hermescontrol.ui.chat.ChatInputPolicy
                .extractMentionQuery(input.text, input.selection.end)
        assertEquals("c", query)

        // 2. Candidate filtering
        val matchingBots =
            members.filter {
                it.name.startsWith(query!!, ignoreCase = true) || it.effectiveTitle.contains(query, ignoreCase = true)
            }
        assertEquals(1, matchingBots.size)
        assertEquals("coder", matchingBots.first().name)

        // 3. User taps chip for coder
        val completed =
            com.m57.hermescontrol.ui.chat.ChatInputPolicy
                .applyMention(input, matchingBots.first().name)
        assertEquals("hey @coder ", completed.text)
        assertEquals(11, completed.selection.end)

        // 4. Mention query is now null after space
        val nextQuery =
            com.m57.hermescontrol.ui.chat.ChatInputPolicy.extractMentionQuery(
                completed.text,
                completed.selection.end,
            )
        org.junit.Assert.assertNull(nextQuery)

        // 5. Group chat mentions resolver picks up the mentioned bot
        val responders = GroupChatMentions.resolveResponders(completed.text, members)
        assertEquals(1, responders.size)
        assertEquals("coder", responders.first().name)
    }
}
