package com.m57.hermescontrol.ui.chat.voice

import android.content.Context
import android.util.Base64
import com.m57.hermescontrol.data.model.AudioTranscriptionRequest
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

class CassyVoiceInputController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val deviceRecognizer = AndroidVoiceInputController(appContext)
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var source = VoiceSource.NONE
    private var languageTag = Locale.getDefault().toLanguageTag()
    private var recorder: WavVoiceRecorder? = null

    init {
        WavVoiceRecorder.cleanupStaleRecordings(appContext)
        scope.launch {
            deviceRecognizer.state.collectLatest { deviceState ->
                if (source == VoiceSource.DEVICE) _state.value = deviceState
            }
        }
    }

    fun start(languageTag: String = Locale.getDefault().toLanguageTag()): Boolean {
        if (_state.value.isBusy) return false
        this.languageTag = languageTag
        source = VoiceSource.NAS
        _state.value = VoiceState.Preparing
        val newRecorder =
            WavVoiceRecorder(
                context = appContext,
                onLevel = { level ->
                    scope.launch {
                        val current = _state.value as? VoiceState.Listening ?: return@launch
                        _state.value = current.copy(rmsDb = level * 14f - 2f)
                    }
                },
                onElapsed = { elapsedMs ->
                    scope.launch {
                        val current = _state.value as? VoiceState.Listening ?: return@launch
                        _state.value = current.copy(elapsedMs = elapsedMs)
                    }
                },
                onMaximumDuration = { scope.launch { stop() } },
            )
        recorder = newRecorder
        return if (newRecorder.start()) {
            _state.value = VoiceState.Listening(isOnDevice = false)
            true
        } else {
            recorder = null
            startDeviceFallback(languageTag)
        }
    }

    fun startDeviceFallback(languageTag: String = this.languageTag): Boolean {
        recorder?.cancel()
        recorder = null
        source = VoiceSource.DEVICE
        _state.value = VoiceState.Preparing
        return deviceRecognizer.start(languageTag)
    }

    fun stop() {
        when (source) {
            VoiceSource.NAS -> stopNasRecording()
            VoiceSource.DEVICE -> deviceRecognizer.stop()
            VoiceSource.NONE -> Unit
        }
    }

    fun cancel() {
        recorder?.cancel()
        recorder = null
        deviceRecognizer.cancel()
        source = VoiceSource.NONE
        _state.value = VoiceState.Idle
    }

    fun consumeResult() {
        if (_state.value is VoiceState.Result || _state.value is VoiceState.Error) {
            source = VoiceSource.NONE
            _state.value = VoiceState.Idle
        }
    }

    fun destroy() {
        cancel()
        deviceRecognizer.destroy()
        scope.cancel()
    }

    private fun stopNasRecording() {
        if (_state.value !is VoiceState.Listening) return
        _state.value = VoiceState.Finalizing
        val activeRecorder = recorder ?: return publishError(VoiceError.AUDIO)
        recorder = null
        scope.launch {
            val file = activeRecorder.stop()
            if (file == null) {
                publishError(VoiceError.NO_MATCH)
                return@launch
            }
            transcribe(file)
        }
    }

    private suspend fun transcribe(file: File) {
        try {
            val audio = withContext(Dispatchers.IO) { file.readBytes() }
            val request =
                AudioTranscriptionRequest(
                    dataUrl = "data:audio/wav;base64," + Base64.encodeToString(audio, Base64.NO_WRAP),
                )
            val response = withContext(Dispatchers.IO) { ApiClient.hermesApi.transcribeAudio(request) }
            val body = response.body()
            val transcript = body?.transcript.orEmpty().trim()
            if (response.isSuccessful && transcript.isNotBlank()) {
                _state.value =
                    VoiceState.Result(
                        text = transcript,
                        provider = body?.provider ?: "cassy-nas",
                    )
            } else {
                publishError(
                    if (response.code() == 408 || response.code() == 504) VoiceError.NETWORK else VoiceError.SERVER,
                )
            }
        } catch (_: IOException) {
            publishError(VoiceError.NETWORK)
        } catch (_: RuntimeException) {
            publishError(VoiceError.SERVER)
        } finally {
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    private fun publishError(error: VoiceError) {
        source = VoiceSource.NONE
        _state.value = VoiceState.Error(error, deviceFallbackAvailable = true)
    }

    private enum class VoiceSource {
        NONE,
        NAS,
        DEVICE,
    }
}

internal val VoiceState.isBusy: Boolean
    get() = this is VoiceState.Preparing || this is VoiceState.Listening || this is VoiceState.Finalizing
