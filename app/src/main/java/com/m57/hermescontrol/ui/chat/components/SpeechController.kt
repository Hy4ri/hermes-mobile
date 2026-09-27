package com.m57.hermescontrol.ui.chat.components

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SpeechRequest(
    val scopeKey: String,
    val messageId: String,
    val text: String,
)

interface SpeechAudio {
    fun dispose()
}

interface SpeechSynthesizer {
    suspend fun synthesize(request: SpeechRequest): SpeechAudio
}

interface SpeechPlayer {
    fun play(
        audio: SpeechAudio,
        onEnded: () -> Unit,
        onError: () -> Unit,
    )

    fun stop()
}

class SpeechController(
    private val scope: CoroutineScope,
    private val synthesizer: SpeechSynthesizer,
    private val player: SpeechPlayer,
    private val onError: () -> Unit,
) {
    private val _speakingId = MutableStateFlow<String?>(null)
    val speakingId: StateFlow<String?> = _speakingId.asStateFlow()

    private var activeRequest: SpeechRequest? = null
    private var activeJob: Job? = null
    private var generation: Long = 0L

    private var cachedRequest: SpeechRequest? = null
    private var cachedAudio: SpeechAudio? = null

    fun toggle(request: SpeechRequest) {
        if (request.text.isBlank()) {
            return
        }

        if (activeRequest == request) {
            stop()
            return
        }

        start(request)
    }

    fun stop() {
        generation++
        activeJob?.cancel()
        activeJob = null
        activeRequest = null
        player.stop()
        _speakingId.value = null
    }

    fun reset() {
        stop()
        disposeCache()
    }

    private fun start(request: SpeechRequest) {
        generation++
        val currentGen = generation

        activeJob?.cancel()
        player.stop()

        if (cachedRequest != null && cachedRequest != request) {
            disposeCache()
        }

        activeRequest = request
        _speakingId.value = request.messageId

        val cached = cachedAudio
        if (cached != null && cachedRequest == request) {
            playAudio(cached, currentGen)
            return
        }

        activeJob =
            scope.launch {
                var synthesizedAudio: SpeechAudio? = null
                try {
                    val audio = synthesizer.synthesize(request)
                    synthesizedAudio = audio

                    if (generation != currentGen) {
                        audio.dispose()
                        return@launch
                    }

                    cachedAudio?.dispose()
                    cachedRequest = request
                    cachedAudio = audio
                    synthesizedAudio = null

                    playAudio(audio, currentGen)
                } catch (e: CancellationException) {
                    synthesizedAudio?.dispose()
                    throw e
                } catch (e: Exception) {
                    synthesizedAudio?.dispose()
                    if (generation == currentGen) {
                        handlePlaybackFailure()
                    }
                }
            }
    }

    private fun playAudio(
        audio: SpeechAudio,
        gen: Long,
    ) {
        try {
            var completed = false
            player.play(
                audio = audio,
                onEnded = {
                    if (generation == gen && !completed) {
                        completed = true
                        handlePlaybackFinished()
                    }
                },
                onError = {
                    if (generation == gen && !completed) {
                        completed = true
                        handlePlaybackFailure()
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (generation == gen) {
                handlePlaybackFailure()
            }
        }
    }

    private fun handlePlaybackFinished() {
        activeJob = null
        activeRequest = null
        player.stop()
        _speakingId.value = null
    }

    private fun handlePlaybackFailure() {
        activeJob = null
        activeRequest = null
        player.stop()
        _speakingId.value = null
        onError()
    }

    private fun disposeCache() {
        cachedAudio?.dispose()
        cachedAudio = null
        cachedRequest = null
    }
}
