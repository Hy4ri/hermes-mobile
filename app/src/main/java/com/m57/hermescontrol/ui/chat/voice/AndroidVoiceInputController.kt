package com.m57.hermescontrol.ui.chat.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

sealed interface VoiceState {
    data object Idle : VoiceState

    data object Preparing : VoiceState

    data class Listening(
        val partialText: String = "",
        val rmsDb: Float = 0f,
        val isOnDevice: Boolean = false,
        val elapsedMs: Long = 0L,
    ) : VoiceState

    data object Finalizing : VoiceState

    data class Result(
        val text: String,
        val provider: String = "android",
    ) : VoiceState

    data class Error(
        val kind: VoiceError,
        val deviceFallbackAvailable: Boolean = false,
    ) : VoiceState
}

enum class VoiceError {
    AUDIO,
    BUSY,
    LANGUAGE_UNAVAILABLE,
    NETWORK,
    NO_MATCH,
    SERVER,
    UNAVAILABLE,
}

/**
 * Lifecycle-bound, in-app speech recognizer. Unlike ACTION_RECOGNIZE_SPEECH
 * activity hand-off, this exposes partial text and microphone level directly
 * to Compose and prefers Android's on-device recognizer when available.
 */
class AndroidVoiceInputController(
    context: Context,
) : RecognitionListener {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var cancelled = false
    private var usesOnDeviceRecognizer = false

    @SuppressLint("MissingPermission")
    fun start(languageTag: String = Locale.getDefault().toLanguageTag()): Boolean {
        destroyRecognizer()
        cancelled = false
        _state.value = VoiceState.Preparing

        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            _state.value = VoiceState.Error(VoiceError.UNAVAILABLE)
            return false
        }

        usesOnDeviceRecognizer =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

        recognizer =
            try {
                if (usesOnDeviceRecognizer && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(appContext)
                }
            } catch (_: Throwable) {
                usesOnDeviceRecognizer = false
                try {
                    SpeechRecognizer.createSpeechRecognizer(appContext)
                } catch (_: Throwable) {
                    null
                }
            }

        val activeRecognizer = recognizer
        if (activeRecognizer == null) {
            _state.value = VoiceState.Error(VoiceError.UNAVAILABLE)
            return false
        }

        activeRecognizer.setRecognitionListener(this)
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, usesOnDeviceRecognizer)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            }
        _state.value = VoiceState.Listening(isOnDevice = usesOnDeviceRecognizer)
        activeRecognizer.startListening(intent)
        return true
    }

    fun stop() {
        if (_state.value !is VoiceState.Listening) return
        _state.value = VoiceState.Finalizing
        recognizer?.stopListening()
    }

    fun cancel() {
        cancelled = true
        recognizer?.cancel()
        destroyRecognizer()
        _state.value = VoiceState.Idle
    }

    fun consumeResult() {
        if (_state.value is VoiceState.Result || _state.value is VoiceState.Error) {
            destroyRecognizer()
            _state.value = VoiceState.Idle
        }
    }

    fun destroy() {
        cancelled = true
        destroyRecognizer()
        _state.value = VoiceState.Idle
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) {
        val current = _state.value as? VoiceState.Listening ?: return
        _state.value = current.copy(rmsDb = rmsdB.coerceIn(-2f, 12f))
    }

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        if (!cancelled) _state.value = VoiceState.Finalizing
    }

    override fun onError(error: Int) {
        if (cancelled) return
        val mapped =
            when (error) {
                SpeechRecognizer.ERROR_AUDIO -> VoiceError.AUDIO
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceError.BUSY
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                -> VoiceError.LANGUAGE_UNAVAILABLE
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                -> VoiceError.NETWORK
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> VoiceError.NO_MATCH
                SpeechRecognizer.ERROR_SERVER,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                -> VoiceError.SERVER
                else -> VoiceError.UNAVAILABLE
            }
        destroyRecognizer()
        _state.value = VoiceState.Error(mapped)
    }

    override fun onResults(results: Bundle?) {
        if (cancelled) return
        val text = results.bestTranscript()
        destroyRecognizer()
        _state.value = if (text.isBlank()) VoiceState.Error(VoiceError.NO_MATCH) else VoiceState.Result(text)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val current = _state.value as? VoiceState.Listening ?: return
        val partial = partialResults.bestTranscript()
        if (partial.isNotBlank()) _state.value = current.copy(partialText = partial)
    }

    override fun onEvent(
        eventType: Int,
        params: Bundle?,
    ) = Unit

    private fun destroyRecognizer() {
        recognizer?.setRecognitionListener(null)
        recognizer?.destroy()
        recognizer = null
    }
}

internal fun Bundle?.bestTranscript(): String =
    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()

internal fun mergeVoiceTranscript(
    existingText: String,
    transcript: String,
): String {
    val before = existingText.trimEnd()
    val spoken = transcript.trim()
    return when {
        spoken.isBlank() -> existingText
        before.isBlank() -> spoken
        else -> "$before $spoken"
    }
}
