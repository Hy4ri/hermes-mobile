package com.m57.hermescontrol.ui.bots.group

import com.m57.hermescontrol.data.model.BotAvatarMeta
import com.m57.hermescontrol.data.model.ProfileInfo

/**
 * Single message entry in a multi-agent group room.
 */
data class GroupChatMessage(
    val id: String,
    val senderName: String,
    val senderDisplayName: String,
    val isUser: Boolean,
    val avatarMeta: BotAvatarMeta? = null,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Pure helper for mention extraction and responder resolution matching Desktop parity.
 */
object GroupChatMentions {
    data class MentionResult(
        val isEveryone: Boolean,
        val mentionedBots: Set<String>,
    )

    /**
     * Parse `@botname`, `@all`, `@everyone` from text against the member roster.
     */
    fun parseMentions(
        text: String,
        members: List<ProfileInfo>,
    ): MentionResult {
        val lowerText = text.lowercase()
        val isEveryone = lowerText.contains("@all") || lowerText.contains("@everyone")

        val mentioned = mutableSetOf<String>()
        for (member in members) {
            val handle = member.name.lowercase()
            val titleHandle = member.effectiveTitle.lowercase().replace(" ", "")
            if (lowerText.contains("@$handle") || lowerText.contains("@$titleHandle")) {
                mentioned.add(member.name)
            }
        }

        return MentionResult(
            isEveryone = isEveryone,
            mentionedBots = mentioned,
        )
    }

    /**
     * Determine which bots should take a turn. If @all or no specific mention, all members participate.
     */
    fun resolveResponders(
        text: String,
        members: List<ProfileInfo>,
    ): List<ProfileInfo> {
        val parsed = parseMentions(text, members)
        return if (parsed.isEveryone || parsed.mentionedBots.isEmpty()) {
            members
        } else {
            members.filter { parsed.mentionedBots.contains(it.name) }
        }
    }

    /**
     * Build the room delta prompt injected into each bot's session.
     */
    fun buildTurnPrompt(
        groupName: String,
        viewer: ProfileInfo,
        peers: List<ProfileInfo>,
        recentLog: List<GroupChatMessage>,
    ): String {
        val viewerHandle = viewer.name
        val peerNames = peers.joinToString(", ") { "@${it.name}" }
        val lines =
            recentLog.takeLast(10).joinToString("\n") { msg ->
                val speaker = if (msg.isUser) "You" else "@${msg.senderName}"
                "  $speaker: ${msg.text}"
            }

        return """
[Group chat: "$groupName"] You are @$viewerHandle, one participant in a group chat with $peerNames and the user.

Recent messages in the room:
$lines

Rules for this room:
- Reply with ONE conversational message ONLY if you have something new worth adding: build on what was just said, answer a question aimed at you, or report a real result.
- Keep chatter short (1-3 sentences), but deliver full quality for real questions/results.
- If you have nothing new to add, reply with exactly "(pass)". Passing is good and lets the conversation settle.
- Mention a teammate as @name to pull them in.
- Your reply goes to the room verbatim — no preamble.
            """.trimIndent()
    }
}
