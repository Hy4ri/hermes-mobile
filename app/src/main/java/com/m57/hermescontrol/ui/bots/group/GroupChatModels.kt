package com.m57.hermescontrol.ui.bots.group

import com.m57.hermescontrol.data.model.BotAvatarMeta
import com.m57.hermescontrol.data.model.ProfileInfo
import java.util.UUID

/**
 * Lightweight tool execution indicator for group chat turns.
 */
data class GroupChatToolCall(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val summary: String? = null,
    val isRunning: Boolean = false,
    val command: String? = null,
    val output: String? = null,
    val exitCode: Int? = null,
    val isError: Boolean = false,
)

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
    val isStreaming: Boolean = false,
    val thread: String? = null,
    val isSystem: Boolean = false,
    val isPass: Boolean = false,
    val toolCalls: List<GroupChatToolCall> = emptyList(),
)

const val STOPPED_SYSTEM_TEXT = "Discussion stopped"
const val CAPPED_SYSTEM_TEXT = "Discussion paused (turn limit reached) — reply to continue"

/**
 * Pure helper for mention extraction and responder resolution matching Desktop parity.
 */
object GroupChatMentions {
    private val MENTION_REGEX = Regex("""@([a-zA-Z0-9._-]+)""")

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
        excludedSpeaker: String? = null,
    ): MentionResult {
        var isEveryone = false
        val mentioned = mutableSetOf<String>()

        val handles = mutableMapOf<String, String>()
        for (member in members) {
            val name = member.name.lowercase()
            handles[name] = member.name
            handles[name.replace(Regex("""[\s_-]+"""), "")] = member.name

            val title = member.effectiveTitle.lowercase().trim()
            if (title.isNotEmpty()) {
                handles[title.replace(" ", "")] = member.name
                val firstWord = title.split(Regex("""\s+""")).firstOrNull().orEmpty()
                if (firstWord.isNotEmpty()) {
                    handles[firstWord] = member.name
                }
            }
        }

        for (match in MENTION_REGEX.findAll(text)) {
            val rawHandle = match.groupValues[1].lowercase()
            if (rawHandle == "all" || rawHandle == "everyone") {
                isEveryone = true
                continue
            }
            if (rawHandle == "user" || rawHandle == "you") {
                continue
            }

            val resolved = handles[rawHandle] ?: handles[rawHandle.replace(Regex("""[._-]+"""), "")]
            if (resolved != null) {
                if (excludedSpeaker == null || !resolved.equals(excludedSpeaker, ignoreCase = true)) {
                    mentioned.add(resolved)
                }
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
        excludedSpeaker: String? = null,
    ): List<ProfileInfo> {
        val parsed = parseMentions(text, members, excludedSpeaker)
        return if (parsed.isEveryone || parsed.mentionedBots.isEmpty()) {
            if (excludedSpeaker != null) {
                members.filter { !it.name.equals(excludedSpeaker, ignoreCase = true) }
            } else {
                members
            }
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
        historyLimit: Int = 10,
        customRoomPrompt: String? = null,
    ): String {
        val viewerLabel =
            if (viewer.effectiveTitle.isNotBlank() && !viewer.effectiveTitle.equals(viewer.name, ignoreCase = true)) {
                "@${viewer.name} (${viewer.effectiveTitle})"
            } else {
                "@${viewer.name}"
            }
        val peerNames =
            peers.joinToString(", ") {
                if (it.effectiveTitle.isNotBlank() && !it.effectiveTitle.equals(it.name, ignoreCase = true)) {
                    "@${it.name} (${it.effectiveTitle})"
                } else {
                    "@${it.name}"
                }
            }
        val lines =
            recentLog.filter { !it.isSystem }.takeLast(historyLimit).joinToString("\n") { msg ->
                val speaker = if (msg.isUser) "User" else "@${msg.senderName}"
                "  $speaker: ${msg.text}"
            }

        val lastUserMsg = recentLog.lastOrNull { it.isUser }?.text.orEmpty()
        val parsed = parseMentions(lastUserMsg, peers + viewer)
        val isDirectOrAll = parsed.isEveryone || parsed.mentionedBots.contains(viewer.name)

        val actionGuidance =
            if (isDirectOrAll) {
                "- You were explicitly addressed (via @${viewer.name} or @all). Do NOT pass — reply conversationally with your greeting, insight, answer, or status."
            } else {
                "- If you have nothing new to add, reply with exactly \"(pass)\". Passing is good and lets the conversation settle."
            }

        val customInstructions =
            if (!customRoomPrompt.isNullOrBlank()) {
                "\nRoom Instructions:\n${customRoomPrompt.trim()}\n"
            } else {
                ""
            }

        val defaultSentenceRule =
            if (customRoomPrompt.isNullOrBlank()) {
                "- Reply with ONE conversational message (1-3 sentences).\n"
            } else {
                ""
            }

        return """
[Group chat: "$groupName"] You are $viewerLabel, one participant in a group chat with $peerNames and the user.
$customInstructions
Recent messages in the room:
$lines

Rules for this room:
$defaultSentenceRule$actionGuidance
- Mention a teammate as @name if you want to pull them in.
- Your reply goes to the room verbatim — no preamble.
            """.trimIndent()
    }
}
