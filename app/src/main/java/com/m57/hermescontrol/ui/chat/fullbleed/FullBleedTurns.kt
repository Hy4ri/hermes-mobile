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
