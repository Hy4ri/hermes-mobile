package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

/**
 * Request body for `POST /api/audio/transcribe` — the dashboard's server-side
 * transcription relay, the same endpoint the desktop client's voice path uses.
 *
 * The recording rides inline as a base64 `data:` URL (the desktop wire shape).
 * [OkHttpProvider.json] carries [kotlinx.serialization.json.JsonNamingStrategy.SnakeCase],
 * so [dataUrl]/[mimeType] serialize as `data_url`/`mime_type`.
 */
@Serializable
data class AudioTranscriptionRequest(
    val dataUrl: String,
    val mimeType: String,
)

/**
 * Response of `POST /api/audio/transcribe`.
 *
 * [transcript] is empty (not an error) when the server detects no speech in
 * the recording.
 */
@Serializable
data class AudioTranscriptionResponse(
    val ok: Boolean = false,
    val transcript: String? = null,
    val provider: String? = null,
)
