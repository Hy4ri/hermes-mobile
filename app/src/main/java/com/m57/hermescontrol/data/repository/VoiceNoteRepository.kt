package com.m57.hermescontrol.data.repository

import com.m57.hermescontrol.data.model.AudioTranscriptionRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import java.io.File
import java.util.Base64

/**
 * MIME type of the recorder's output: MPEG-4 container with AAC audio — see
 * `VoiceNoteRecorder` in the chat UI layer.
 */
const val VOICE_NOTE_MIME_TYPE = "audio/mp4"

/**
 * Transcribes a recorded voice note through the dashboard's server-side
 * transcription endpoint (`POST /api/audio/transcribe`).
 *
 * The wire shape is the desktop client's: base64 `data:` URL plus MIME type.
 * The server resolves STT through the active profile's configured provider,
 * so the transcript matches the rest of Hermes instead of the phone's
 * on-device recognizer.
 *
 * Open for test substitution (mirrors [ChatPersistenceRepository]'s pattern).
 */
open class VoiceNoteRepository(
    private val api: HermesApiService = ApiClient.hermesApi,
) {
    open suspend fun transcribe(file: File): NetworkResult<String> {
        val encoded = file.readBytes()
        val dataUrl = "data:$VOICE_NOTE_MIME_TYPE;base64," + Base64.getEncoder().encodeToString(encoded)
        val result =
            safeApiCall {
                api.transcribeAudio(
                    AudioTranscriptionRequest(dataUrl = dataUrl, mimeType = VOICE_NOTE_MIME_TYPE),
                )
            }
        return when (result) {
            is NetworkResult.Success -> {
                NetworkResult.Success(
                    result.data
                        ?.transcript
                        .orEmpty()
                        .trim(),
                )
            }

            is NetworkResult.Failure -> {
                result
            }
        }
    }
}
