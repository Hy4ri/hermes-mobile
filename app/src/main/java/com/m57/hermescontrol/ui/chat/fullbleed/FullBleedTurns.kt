package com.m57.hermescontrol.ui.chat.fullbleed

import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MessageRole

/**
 * Turn model for the full-bleed chat renderer (issue #866).
 *
 * A turn is a unit of conversation for spacing + header purposes:
 * - [ChatTurn.User]: one user message — always its own turn (bubble anchor).
 * - [ChatTurn.Agent]: everything between user messages — assistant prose,
 *   tool rows, and system events, in original order.
 */
sealed interface ChatTurn {
    /** User message — always its own turn (bubble anchor). */
    data class User(val message: ChatMessage) : ChatTurn

    /** One agent turn: prose, tool rows, and system events in order. */
    data class Agent(val entries: List<AgentEntry>) : ChatTurn
}

sealed interface AgentEntry {
    data class Prose(val message: ChatMessage) : AgentEntry

    data class ToolRow(val message: ChatMessage) : AgentEntry

    data class SystemEvent(val message: ChatMessage) : AgentEntry
}

/**
 * Split a flat message list into turns for the full-bleed renderer.
 *
 * Each USER message closes the current agent turn (if any) and opens a User
 * turn; all non-user messages belong to the surrounding agent turn. A new
 * agent turn starts after each user turn or at the start of the list.
 */
fun groupIntoTurns(messages: List<ChatMessage>): List<ChatTurn> {
    val turns = mutableListOf<ChatTurn>()
    val agentEntries = mutableListOf<AgentEntry>()

    fun flushAgent() {
        if (agentEntries.isNotEmpty()) {
            turns += ChatTurn.Agent(agentEntries.toList())
            agentEntries.clear()
        }
    }

    messages.forEach { message ->
        when (message.role) {
            MessageRole.USER -> {
                flushAgent()
                turns += ChatTurn.User(message)
            }

            MessageRole.ASSISTANT -> agentEntries += AgentEntry.Prose(message)
            MessageRole.TOOL -> agentEntries += AgentEntry.ToolRow(message)
            MessageRole.SYSTEM -> agentEntries += AgentEntry.SystemEvent(message)
        }
    }
    flushAgent()
    return turns
}

/**
 * Like [groupIntoTurns] but folds the in-flight streaming assistant message
 * into the current agent turn, so it renders as part of the turn (reasoning
 * hoist, turn headers, spacing) instead of as a detached tail item.
 *
 * Defensive: if the streaming message's id is already present in [messages]
 * (commit race — the message landed while the UI still held the streaming
 * copy), it is not appended again; a duplicate prose entry would produce a
 * LazyColumn duplicate-key crash.
 */
fun groupIntoTurnsWithStreaming(
    messages: List<ChatMessage>,
    streamingMessage: ChatMessage?,
): List<ChatTurn> {
    if (streamingMessage == null || messages.any { it.id == streamingMessage.id }) {
        return groupIntoTurns(messages)
    }
    val turns = groupIntoTurns(messages).toMutableList()
    val prose = AgentEntry.Prose(streamingMessage)
    val last = turns.lastOrNull()
    if (last is ChatTurn.Agent) {
        turns[turns.lastIndex] = ChatTurn.Agent(last.entries + prose)
    } else {
        turns += ChatTurn.Agent(listOf(prose))
    }
    return turns
}
