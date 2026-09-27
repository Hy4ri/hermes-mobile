package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SessionCompressResponse(
    val status: String? = null,
    val removed: Int? = null,
    val before_messages: Int? = null,
    val after_messages: Int? = null,
    val before_tokens: Long? = null,
    val after_tokens: Long? = null,
    val summary: CompressionSummary? = null,
    val compressed: Boolean? = null,
    val lock_held: Boolean? = null,
    val message: String? = null,
    val messages: List<SessionMessage>? = null,
    val usage: JsonElement? = null,
    val info: JsonElement? = null,
)

@Serializable
data class CompressionSummary(
    val noop: Boolean = false,
    val aborted: Boolean = false,
    val refused_would_grow: Boolean? = null,
    val fallback_used: Boolean? = null,
    val headline: String = "",
    val token_line: String = "",
    val note: String? = null,
)
