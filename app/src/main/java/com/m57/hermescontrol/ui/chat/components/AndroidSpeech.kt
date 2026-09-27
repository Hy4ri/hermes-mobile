package com.m57.hermescontrol.ui.chat.components

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.m57.hermescontrol.data.model.TtsSpeakRequest
import com.m57.hermescontrol.data.remote.HermesApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.UUID

private const val TTS_CACHE_DIR_NAME = "speech_tts"
private const val TTS_FILE_PREFIX = "tts_"
private const val TTS_STALE_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours

class FileSpeechAudio(
    val file: File,
) : SpeechAudio {
    override fun dispose() {
        runCatching { file.delete() }
    }
}

/**
 * Android implementation of [SpeechSynthesizer] backed by Hermes TTS REST API.
 *
 * Audio is decoded from base64 data URLs directly to temporary files in application cache.
 * Stale orphan cache files (created in previous sessions or left behind by unexpected crashes)
 * older than [TTS_STALE_CACHE_MAX_AGE_MS] are opportunistically pruned during synthesis on the
 * IO dispatcher before creating new files.
 *
 * Limitation note: Single-active controller usage is expected. Active audio files in current use
 * by the active session are protected from cleanup by the age threshold, but concurrent multi-process
 * or out-of-band manipulation of cache files is not synchronized.
 */
class AndroidSpeechSynthesizer(
    context: Context,
    private val api: HermesApiService,
    private val profile: String?,
) : SpeechSynthesizer {
    private val appContext = context.applicationContext

    override suspend fun synthesize(request: SpeechRequest): SpeechAudio {
        val response =
            api.speakText(
                request = TtsSpeakRequest(request.text),
                profile = profile,
            )

        if (!response.isSuccessful) {
            throw IOException("TTS request failed with code ${response.code()}")
        }

        val body = response.body() ?: throw IOException("Empty TTS response body")
        if (!body.ok) {
            throw IOException("TTS synthesis reported failure")
        }

        val dataUrl = body.data_url
        if (dataUrl.isNullOrBlank()) {
            throw IOException("Missing data URL in TTS response")
        }

        var ownedFile: File? = null
        try {
            val audioFile =
                withContext(Dispatchers.IO) {
                    val comma = dataUrl.indexOf(',')
                    if (comma < 0) {
                        throw IOException("Malformed data URL in TTS response")
                    }

                    val meta = dataUrl.substring(0, comma)
                    if (!meta.startsWith("data:audio/", ignoreCase = true)) {
                        throw IOException("Expected audio data URL")
                    }
                    if (!meta.endsWith(";base64", ignoreCase = true)) {
                        throw IOException("Expected base64-encoded audio data URL")
                    }

                    val ext =
                        when {
                            meta.contains(
                                "audio/mpeg",
                                ignoreCase = true,
                            ) || meta.contains("audio/mp3", ignoreCase = true) -> "mp3"

                            meta.contains("audio/wav", ignoreCase = true) -> "wav"

                            meta.contains("audio/ogg", ignoreCase = true) -> "ogg"

                            meta.contains("audio/aac", ignoreCase = true) -> "aac"

                            meta.contains("audio/opus", ignoreCase = true) -> "opus"

                            meta.contains("audio/flac", ignoreCase = true) -> "flac"

                            else -> "audio"
                        }

                    val cacheDir = File(appContext.cacheDir, TTS_CACHE_DIR_NAME).apply { mkdirs() }
                    cleanStaleCacheFiles(cacheDir)

                    val file = File(cacheDir, "${TTS_FILE_PREFIX}${UUID.randomUUID()}.$ext")
                    ownedFile = file

                    val rawStream = CharSequenceInputStream(dataUrl, comma + 1)
                    val decodingStream = Base64.getDecoder().wrap(rawStream)

                    var totalBytesWritten = 0L
                    decodingStream.use { input ->
                        FileOutputStream(file).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                currentCoroutineContext().ensureActive()
                                output.write(buffer, 0, bytesRead)
                                totalBytesWritten += bytesRead
                            }
                            output.flush()
                        }
                    }

                    // Java's Base64.getDecoder().wrap() stops decoding upon encountering padding ('=').
                    // Verify that the underlying stream contains no unparsed trailing non-whitespace characters.
                    if (rawStream.read() != -1) {
                        throw IOException("Trailing garbage characters found after base64 data")
                    }

                    if (totalBytesWritten == 0L) {
                        throw IOException("Decoded audio file is empty")
                    }

                    file
                }

            ownedFile = null
            return FileSpeechAudio(audioFile)
        } catch (e: CancellationException) {
            ownedFile?.let { runCatching { it.delete() } }
            throw e
        } catch (t: Throwable) {
            ownedFile?.let { runCatching { it.delete() } }
            throw t
        }
    }

    private fun cleanStaleCacheFiles(cacheDir: File) {
        val now = System.currentTimeMillis()
        val files =
            cacheDir.listFiles { _, name ->
                name.startsWith(TTS_FILE_PREFIX)
            } ?: return

        for (file in files) {
            if (now - file.lastModified() > TTS_STALE_CACHE_MAX_AGE_MS) {
                runCatching { file.delete() }
            }
        }
    }

    private class CharSequenceInputStream(
        private val value: CharSequence,
        startIndex: Int,
    ) : InputStream() {
        private var index = startIndex

        override fun read(): Int {
            while (index < value.length) {
                val c = value[index++]
                if (!c.isWhitespace()) {
                    if (c.code > 127) {
                        throw IOException("Non-ASCII character in base64 data")
                    }
                    return c.code
                }
            }
            return -1
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (len == 0) return 0
            var bytesRead = 0
            while (bytesRead < len) {
                val c = read()
                if (c == -1) {
                    return if (bytesRead == 0) -1 else bytesRead
                }
                b[off + bytesRead] = c.toByte()
                bytesRead++
            }
            return bytesRead
        }
    }
}

class AndroidSpeechPlayer(
    context: Context,
) : SpeechPlayer {
    private val appContext = context.applicationContext
    private var exoPlayer: ExoPlayer? = null
    private var activeListener: Player.Listener? = null

    override fun play(
        audio: SpeechAudio,
        onEnded: () -> Unit,
        onError: () -> Unit,
    ) {
        if (audio !is FileSpeechAudio) {
            onError()
            return
        }

        val file = audio.file
        if (!file.exists() || file.length() == 0L) {
            onError()
            return
        }

        val player =
            exoPlayer ?: ExoPlayer
                .Builder(appContext)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true,
                ).setHandleAudioBecomingNoisy(true)
                .build()
                .also { exoPlayer = it }

        activeListener?.let { player.removeListener(it) }

        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        player.removeListener(this)
                        activeListener = null
                        onEnded()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    player.removeListener(this)
                    activeListener = null
                    onError()
                }
            }

        activeListener = listener
        player.addListener(listener)

        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
    }

    override fun stop() {
        val player = exoPlayer ?: return
        activeListener?.let { player.removeListener(it) }
        activeListener = null
        player.stop()
        player.clearMediaItems()
        player.release()
        exoPlayer = null
    }
}
