package com.m57.hermescontrol.ui.chat.components

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.TtsSpeakRequest
import com.m57.hermescontrol.data.model.TtsSpeakResponse
import com.m57.hermescontrol.data.remote.ApiClient
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Per-message read-aloud for agent messages (TTS speak button).
 *
 * Triggers the EXISTING Hermes TTS engine — no new speech engine, no
 * on-device synthesis. The full message text is buffered client-side,
 * markdown/emoji is stripped for speech, and the cleartext is POSTed to
 * the Hermes server's `POST /api/audio/speak` endpoint, which runs the
 * configured `tts.*` provider chain (hermes-workspace TTS provider config)
 * through the agent's `text_to_speech` toolset. The returned base64 audio
 * data URL is played with ExoPlayer. One shared player: tapping a second
 * message replaces the first.
 */
object MessageSpeech {
    private const val TAG = "MessageSpeech"

    private val _speakingId = MutableStateFlow<String?>(null)
    val speakingId: StateFlow<String?> = _speakingId.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var player: ExoPlayer? = null
    private var playerListener: Player.Listener? = null

    /**
     * Speak a full agent message via the Hermes server TTS engine.
     *
     * Tapping the message that is currently speaking (or synthesizing) stops
     * it instead of re-requesting.
     */
    fun speak(
        context: Context,
        messageId: String,
        rawContent: String,
    ) {
        if (_speakingId.value == messageId) {
            stop()
            return
        }
        val text = SpeechText.stripMarkdownForSpeech(rawContent)
        if (text.isBlank()) return
        val appContext = context.applicationContext
        _speakingId.value = messageId
        scope.launch {
            try {
                val response = ApiClient.hermesApi.speakText(TtsSpeakRequest(text))
                if (_speakingId.value != messageId) return@launch // stopped mid-synthesis
                val body = response.body()
                if (!response.isSuccessful || body?.ok != true) {
                    val detail = response.errorBody()?.string()?.take(140)
                    fail(appContext, detail)
                    return@launch
                }
                val dataUrl = body.data_url
                if (dataUrl.isNullOrBlank()) {
                    fail(appContext, null)
                    return@launch
                }
                play(appContext, messageId, dataUrl, body.mime_type)
            } catch (e: Exception) {
                Log.w(TAG, "TTS talk failed", e)
                if (e is IOException) {
                    toast(appContext, R.string.tts_error_network)
                } else {
                    fail(appContext, e.message)
                }
                _speakingId.value = null
            }
        }
    }

    fun stop() {
        releasePlayer()
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private fun play(
        context: Context,
        messageId: String,
        dataUrl: String,
        mimeType: String?,
    ) {
        releasePlayer()
        _speakingId.value = messageId

        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        releasePlayer()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "TTS playback error", error)
                    toast(context, R.string.tts_error_playback)
                    releasePlayer()
                }
            }
        playerListener = listener

        val exo =
            ExoPlayer.Builder(context)
                .build()
                .apply {
                    setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                            .build(),
                        /* handleAudioFocus = */ true,
                    )
                    addListener(listener)
                    val item =
                        MediaItem
                            .Builder()
                            .setUri(dataUrl)
                            .apply { mimeType?.let { setMimeType(it) } }
                            .build()
                    setMediaItem(item)
                    prepare()
                    play()
                }
        player = exo
    }

    private fun releasePlayer() {
        player?.run {
            playerListener?.let { removeListener(it) }
            stop()
            release()
        }
        player = null
        playerListener = null
        _speakingId.value = null
    }

    private fun fail(context: Context, detail: String?) {
        Log.w(TAG, "TTS synthesis failed${detail?.let { ": $it" } ?: ""}")
        toast(context, R.string.tts_error_synthesis)
        _speakingId.value = null
    }

    private fun toast(context: Context, resId: Int) {
        Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
    }
}