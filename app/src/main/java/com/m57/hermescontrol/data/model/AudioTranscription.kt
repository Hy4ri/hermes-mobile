package com.m57.hermescontrol.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AudioTranscriptionRequest(
    @SerialName("data_url") val dataUrl: String,
    @SerialName("mime_type") val mimeType: String = "audio/wav",
)

@Serializable
data class AudioTranscriptionResponse(
    val transcript: String = "",
    val provider: String? = null,
)
