package com.m57.hermescontrol.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One emoji reaction on a message. Tapback semantics: at most one per [author] per message. */
data class MessageReaction(
    val emoji: String,
    val author: String,
)

/** Reactions carried by a live `message.reaction` event payload (`reactions` list of maps). */
internal fun parseMessageReactions(raw: Any?): List<MessageReaction> =
    (raw as? List<*>).orEmpty().mapNotNull { item ->
        val map = item as? Map<*, *> ?: return@mapNotNull null
        val emoji = (map["emoji"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        MessageReaction(emoji = emoji, author = map["author"] as? String ?: "")
    }

/** Reactions persisted under `display_metadata.reactions` on a REST transcript row. */
internal fun parseMessageReactions(displayMetadata: JsonElement?): List<MessageReaction> {
    val list = (displayMetadata as? JsonObject)?.get("reactions") as? JsonArray ?: return emptyList()
    return list.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val emoji =
            (obj["emoji"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
        val author = (obj["author"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        MessageReaction(emoji = emoji, author = author)
    }
}
