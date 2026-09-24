package com.m57.hermescontrol.data.repository

import com.m57.hermescontrol.data.model.AudioTranscriptionRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import java.io.File
import java.io.IOException
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
 * The server resolves STT through the active profile's configured provider
 * (the request is profile-scoped by ProfileScopeInterceptor, matching the
 * desktop's `...profileScoped()` on this route), so the transcript matches
 * the rest of Hermes instead of the phone's on-device recognizer.
 *
 * Two contracts differ from a plain REST call (review, PR #1250):
 *  - [serviceProvider] resolves the Retrofit service at CALL time, so a
 *    connection/profile switch (ApiClient.rebuild) applies to the next
 *    transcription instead of pinning the construction-time service.
 *  - The STT POST runs on a dedicated long-timeout client with `retries = 0`:
 *    re-submitting after a timeout or 5xx would repeat expensive provider
 *    work although the request may already have reached the server.
 *
 * Open for test substitution (mirrors [ChatPersistenceRepository]'s pattern).
 */
open class VoiceNoteRepository(
    private val serviceProvider: (timeoutMs: Long) -> HermesApiService = { timeoutMs ->
        ApiClient.transcriptionService(timeoutMs)
    },
) {
    open suspend fun transcribe(file: File): NetworkResult<String> {
        val encoded =
            try {
                file.readBytes()
            } catch (e: IOException) {
                // A local read failure must return a Failure instead of
                // escaping as an exception — the caller resets its
                // transcription state from this result (review, PR #1250).
                return NetworkResult.Failure(
                    NetworkError.Unknown("Failed to read the voice note: ${e.message}", e),
                )
            }
        val dataUrl = "data:$VOICE_NOTE_MIME_TYPE;base64," + Base64.getEncoder().encodeToString(encoded)
        val result =
            safeApiCall(retries = 0) {
                serviceProvider(transcriptionTimeoutMs(dataUrl)).transcribeAudio(
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

    companion object {
        /** Floor for STT requests — desktop parity (`AUDIO_TRANSCRIBE_MIN_REQUEST_TIMEOUT_MS`). */
        const val MIN_REQUEST_TIMEOUT_MS = 180_000L

        /** Cap so a hung transcription cannot pin the call forever — desktop parity. */
        const val MAX_REQUEST_TIMEOUT_MS = 600_000L

        private const val BASE64_CHARS_PER_TIMEOUT_MS = 10L

        /**
         * Timeout for the blocking STT endpoint, mirroring the desktop's
         * `audioTranscribeRequestTimeoutMs`: ~0.1ms of budget per base64 char
         * keeps short clips at the floor while multi-minute recordings scale
         * toward the cap. Integer ceil() keeps the arithmetic exact.
         */
        fun transcriptionTimeoutMs(dataUrl: String): Long =
            ((dataUrl.length + BASE64_CHARS_PER_TIMEOUT_MS - 1) / BASE64_CHARS_PER_TIMEOUT_MS)
                .coerceIn(MIN_REQUEST_TIMEOUT_MS, MAX_REQUEST_TIMEOUT_MS)
    }
}
