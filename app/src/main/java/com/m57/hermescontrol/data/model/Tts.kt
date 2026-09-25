package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

/**
 * Request/response pair for the Hermes server's existing TTS engine.
 *
 * Backed by `POST /api/audio/speak` (hermes_cli/web_routers/audio.py), which
 * drives the configured `tts.*` provider chain (hermes-workspace TTS provider
 * config) through the agent's `text_to_speech` tool and returns the
 * synthesized audio as a base64 data URL. No client-side speech engine.
 */
@Serializable
data class TtsSpeakRequest(
    val text: String,
)

@Serializable
data class TtsSpeakResponse(
    val ok: Boolean = false,
    val data_url: String? = null,
    val mime_type: String? = null,
    val provider: String? = null,
)