package com.m57.hermescontrol.data.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Lenient boolean serializer that decodes both JSON booleans (`true`/`false`)
 * and raw SQLite numeric booleans (`1`/`0`), as well as string representations (`"true"`/`"1"`).
 *
 * This guards against backend versions (e.g. Hermes Agent <= 0.19.0) where database
 * boolean flags like `pinned` are returned as raw integer values (issue #966).
 */
@OptIn(ExperimentalSerializationApi::class)
object LenientNullableBooleanSerializer : KSerializer<Boolean?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientNullableBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean? {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element is JsonPrimitive) {
                element.booleanOrNull?.let { return it }
                element.intOrNull?.let { return it != 0 }
                val str = element.content.trim().lowercase()
                return when (str) {
                    "true", "1" -> true
                    "false", "0" -> false
                    "null", "" -> null
                    else -> null
                }
            }
            return null
        }
        return decoder.decodeBoolean()
    }

    override fun serialize(
        encoder: Encoder,
        value: Boolean?,
    ) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeBoolean(value)
        }
    }
}

@Serializable
data class SessionListResponse(
    val sessions: List<SessionInfo>,
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

@Serializable
data class SessionInfo(
    val id: String,
    val title: String? = null,
    val created_at: String? = null,
    val message_count: Int? = null,
    val status: String? = null,
    val preview: String? = null,
    val started_at: Double? = null,
    val source: String? = null,
    val parent_session_id: String? = null,
    val display_name: String? = null,
    val model: String? = null,
    val terminal_backend: String? = null,
    // Durable "keep" flag: pinned sessions are exempt from the auto-archive
    // sweep and are surfaced first in the history list (sorted client-side).
    // Uses LenientNullableBooleanSerializer to tolerate both JSON booleans and raw SQLite ints (issue #966).
    @Serializable(with = LenientNullableBooleanSerializer::class)
    val pinned: Boolean? = null,
)

@Serializable
data class SessionStatsResponse(
    val total: Int = 0,
    // Backend GET /api/sessions/stats returns "messages" (total stored
    // messages) — the value the Sessions stats row shows.
    val messages: Int = 0,
)

@Serializable
data class SessionRenameRequest(
    // Both fields are optional: the backend PATCH accepts any subset
    // (SessionRename model: title/archived/pinned, omitted = unchanged).
    val title: String? = null,
    val pinned: Boolean? = null,
)

@Serializable
data class BulkDeleteRequest(
    val ids: List<String>,
    val delete_all: Boolean = false,
)

@Serializable
data class BulkDeleteResponse(
    val ok: Boolean = false,
    val deleted: Int = 0,
)

@Serializable
data class PruneRequest(
    val days: Int,
)
