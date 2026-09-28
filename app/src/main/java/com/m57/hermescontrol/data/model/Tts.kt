package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

/**
 * Request body for `POST /api/audio/speak` — the dashboard's server-side
 * text-to-speech relay.
 */
@Serializable
data class TtsSpeakRequest(
    val text: String,
)

/**
 * Response of `POST /api/audio/speak`.
 *
 * Returns base64 encoded [data_url] with [mime_type] and the [provider] used,
 * or [ok] = false on failure.
 */
@Serializable
data class TtsSpeakResponse(
    val ok: Boolean = false,
    val data_url: String? = null,
    val mime_type: String? = null,
    val provider: String? = null,
)
