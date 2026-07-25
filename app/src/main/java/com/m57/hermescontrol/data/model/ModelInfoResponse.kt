package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

/**
 * Response from `GET /api/model/info` (PUBLIC, no auth required).
 *
 * VERIFIED against the live gateway (:9119, 2026-07-25): the payload carries
 * `model`, `provider`, `auto_context_length`, `config_context_length`, and
 * `effective_context_length`. The latter is the authoritative full context
 * window for the active model — what the chat screen surfaces as "full context"
 * in the used/full context meter. `context_length` (raw, per-session) is not
 * part of this endpoint; per-session *used* context comes from
 * [SessionDetailResponse.last_prompt_tokens] instead.
 *
 * Decoded with kotlinx `Json { ignoreUnknownKeys = true }` (see
 * [com.m57.hermescontrol.data.remote.OkHttpProvider.json]), so unknown backend
 * fields are tolerated.
 */
@Serializable
data class ModelInfoResponse(
    val model: String? = null,
    val provider: String? = null,
    /** Context window the runtime resolved from the provider catalog. */
    val auto_context_length: Long? = null,
    /** User-configured override (0 when unset). */
    val config_context_length: Long? = null,
    /** Effective context window actually in use (max of auto/config + caps). */
    val effective_context_length: Long? = null,
)
