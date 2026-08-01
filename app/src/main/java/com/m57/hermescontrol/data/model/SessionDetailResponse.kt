package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

/**
 * Response from `GET /api/sessions/{id}` (gated — rides the app's existing
 * cookie auth). Only the fields the chat screen's context detail sheet needs
 * are modelled; the backend serializes many more and [kotlinx.serialization]
 * ignores the rest.
 *
 * VERIFIED against the live gateway SQLite store (`/files/agent-vault/hermes/
 * state.db`, 2026-07-26): the `sessions` table exposes `input_tokens`,
 * `output_tokens`, `cache_read_tokens`, `cache_write_tokens`, `reasoning_tokens`,
 * `message_count`, etc. — but it does NOT have a `last_prompt_tokens` column
 * (that field lives only on the in-memory `SessionEntry` in the gateway
 * process, never persisted to the REST response).
 *
 * NOTE (issue #756): this endpoint is NO LONGER the chat meter's numerator
 * source — `input_tokens` is a cumulative lifetime counter that never drops
 * after context compression. The meter reads live occupancy from the
 * `session.context_breakdown` WS RPC ([ContextBreakdownResponse]); this
 * response feeds only the detail sheet's cumulative token accounting.
 */
@Serializable
data class SessionDetailResponse(
    val session_id: String? = null,
    val session_key: String? = null,
    /** Cumulative prompt tokens for the session (lifetime accounting). */
    val input_tokens: Long? = null,
    val output_tokens: Long? = null,
    val cache_read_tokens: Long? = null,
    val cache_write_tokens: Long? = null,
    val reasoning_tokens: Long? = null,
    val message_count: Int? = null,
)
