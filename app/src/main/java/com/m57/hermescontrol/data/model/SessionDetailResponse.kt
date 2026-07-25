package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

/**
 * Response from `GET /api/sessions/{id}` (gated — rides the app's existing
 * cookie auth). Only the fields the chat screen needs for the context meter
 * are modelled; the backend serializes many more (title, input/output token
 * totals, cost, expiry flags, …) and [kotlinx.serialization] ignores the rest.
 *
 * VERIFIED against `gateway/session.py` `SessionEntry.to_dict()` (2026-07-25):
 * `last_prompt_tokens` is the size of the prompt sent on the most recent turn
 * for this session — i.e. the *used* portion of the context window. It is the
 * natural numerator for a "used / full" context meter alongside
 * [ModelInfoResponse.effective_context_length] as the denominator.
 */
@Serializable
data class SessionDetailResponse(
    val session_id: String? = null,
    val session_key: String? = null,
    /** Tokens in the prompt on the latest turn — the *used* context window. */
    val last_prompt_tokens: Long? = null,
    val input_tokens: Long? = null,
    val output_tokens: Long? = null,
    val total_tokens: Long? = null,
)
