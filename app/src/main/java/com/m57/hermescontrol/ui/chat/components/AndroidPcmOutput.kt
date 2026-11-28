package com.m57.hermescontrol.ui.chat.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** PCM16 mono output; blocking writes run off-main and retain only the platform's small buffer. */
internal class AndroidPcmOutput(
    context: Context,
    sampleRate: Int,
) : PcmSpeechOutput {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(AudioManager::class.java)
    private val closed = AtomicBoolean(false)
    private val interrupted = AtomicBoolean(false)
    private val track: AudioTrack
    private var framesWritten = 0L
    private var registered = false
    private val focus: AudioFocusRequest
    private val noisyReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) interrupt()
            }
        }

    init {
        val minimum =
            AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        if (minimum <= 0) throw IOException("Unsupported audio output format")
        val attributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        track =
            AudioTrack
                .Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                ).setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minimum, sampleRate / 2))
                .build()
        focus =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(
                    { change -> if (change < 0) interrupt() },
                    Handler(Looper.getMainLooper()),
                ).build()
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) throw IOException("Could not initialize speech output")
            if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                throw IOException("Speech audio focus denied")
            }
            ContextCompat.registerReceiver(
                this.context,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            registered = true
            track.play()
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun interrupt() {
        interrupted.set(true)
        if (!closed.get()) {
            // A queued focus callback may race release; do not throw on the main looper.
            runCatching {
                track.pause()
                track.flush()
            }
        }
    }

    private fun checkOutput() {
        if (closed.get() || interrupted.get()) throw IOException("Speech output interrupted")
    }

    override suspend fun write(pcm: ByteArray) =
        withContext(Dispatchers.IO) {
            var offset = 0
            var lastProgress = SystemClock.elapsedRealtime()
            while (offset < pcm.size) {
                currentCoroutineContext().ensureActive()
                checkOutput()
                // Non-blocking writes keep cancellation/focus loss responsive even if the device stalls.
                val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                if (SystemClock.elapsedRealtime() - lastProgress > 15_000L) throw IOException("Speech output stalled")
                if (written < 0 || written % 2 != 0) throw IOException("Speech output write failed")
                if (written == 0) {
                    delay(10)
                } else {
                    lastProgress = SystemClock.elapsedRealtime()
                    framesWritten += written / 2
                    offset += written
                }
            }
        }

    override suspend fun drain() {
        withTimeout(15_000L) {
            while ((track.playbackHeadPosition.toLong() and 0xffffffffL) < framesWritten) {
                checkOutput()
                delay(10)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            if (registered) context.unregisterReceiver(noisyReceiver)
            manager.abandonAudioFocusRequest(focus)
        } finally {
            try {
                track.pause()
                track.flush()
            } finally {
                track.release()
            }
        }
    }
}
