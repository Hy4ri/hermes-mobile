package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

/**
 * Response from `GET /api/sessions/{id}` (gated — rides the app's existing
 * cookie auth). Only the fields the chat screen needs for the context meter
 * are modelled; the backend serializes many more and [kotlinx.serialization]
 * ignores the rest.
 *
 * VERIFIED against the live gateway SQLite store (`/files/agent-vault/hermes/
 * state.db`, 2026-07-26): the `sessions` table exposes `input_tokens`,
 * `output_tokens`, `cache_read_tokens`, `cache_write_tokens`, `reasoning_tokens`,
 * `message_count`, etc. — but it does NOT have a `last_prompt_tokens` column
 * (that field lives only on the in-memory `SessionEntry` in the gateway
 * process, never persisted to the REST response). So the *used* context window
 * is sourced from `input_tokens` (cumulative prompt tokens for the session),
 * paired with [ModelInfoResponse.effective_context_length] as the denominator.
 */
@Serializable
data class SessionDetailResponse(
    val session_id: String? = null,
    val session_key: String? = null,
    /** Cumulative prompt tokens for the session — the *used* context window. */
    val input_tokens: Long? = null,
    val output_tokens: Long? = null,
    val cache_read_tokens: Long? = null,
    val cache_write_tokens: Long? = null,
    val reasoning_tokens: Long? = null,
    val message_count: Int? = null,
)
