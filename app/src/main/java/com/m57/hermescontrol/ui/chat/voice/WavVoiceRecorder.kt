package com.m57.hermescontrol.ui.chat.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

internal class WavVoiceRecorder(
    context: Context,
    private val onLevel: (Float) -> Unit,
    private val onElapsed: (Long) -> Unit,
    private val onMaximumDuration: () -> Unit,
) {
    private val cacheDir = context.applicationContext.cacheDir
    private val recording = AtomicBoolean(false)

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var recordingThread: Thread? = null

    @Volatile
    private var outputFile: File? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        cancel()
        val minimumBuffer =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        if (minimumBuffer <= 0) return false

        val bufferSize = max(minimumBuffer, SAMPLE_RATE)
        val recorder =
            try {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    ).setBufferSizeInBytes(bufferSize * 2)
                    .build()
            } catch (_: RuntimeException) {
                return false
            }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }

        val file = File.createTempFile("cassy-voice-", ".wav", cacheDir)
        FileOutputStream(file).use { output -> output.write(ByteArray(WAV_HEADER_SIZE)) }

        return try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                recorder.release()
                file.delete()
                false
            } else {
                audioRecord = recorder
                outputFile = file
                recording.set(true)
                recordingThread =
                    Thread(
                        { recordLoop(recorder, file, bufferSize) },
                        "cassy-wav-recorder",
                    ).apply {
                        isDaemon = true
                        start()
                    }
                true
            }
        } catch (_: RuntimeException) {
            recorder.release()
            file.delete()
            false
        }
    }

    suspend fun stop(): File? =
        withContext(Dispatchers.IO) {
            stopInternal(deleteFile = false)
        }

    fun cancel() {
        stopInternal(deleteFile = true)
    }

    private fun recordLoop(
        recorder: AudioRecord,
        file: File,
        bufferSize: Int,
    ) {
        val buffer = ByteArray(bufferSize)
        var dataBytes = 0L
        var lastElapsedSecond = -1L
        try {
            FileOutputStream(file, true).use { output ->
                while (recording.get()) {
                    val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0) continue
                    output.write(buffer, 0, count)
                    dataBytes += count
                    onLevel(calculateLevel(buffer, count))
                    val elapsedMs = dataBytes * 1_000L / BYTES_PER_SECOND
                    val elapsedSecond = elapsedMs / 1_000L
                    if (elapsedSecond != lastElapsedSecond) {
                        lastElapsedSecond = elapsedSecond
                        onElapsed(elapsedMs)
                    }
                    if (dataBytes >= MAX_DATA_BYTES) {
                        recording.set(false)
                        onMaximumDuration()
                    }
                }
            }
        } catch (_: RuntimeException) {
            recording.set(false)
        } finally {
            if (file.exists() && file.length() >= WAV_HEADER_SIZE) {
                finalizeWavHeader(file)
            }
        }
    }

    @Synchronized
    private fun stopInternal(deleteFile: Boolean): File? {
        val recorder = audioRecord
        val file = outputFile
        recording.set(false)
        if (recorder != null) {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } catch (_: IllegalStateException) {
                // The recorder may already have stopped at the duration cap.
            }
        }
        recordingThread?.join(THREAD_JOIN_TIMEOUT_MS)
        recorder?.release()
        audioRecord = null
        recordingThread = null
        outputFile = null

        if (file != null && file.exists()) {
            finalizeWavHeader(file)
            if (deleteFile || file.length() <= WAV_HEADER_SIZE) {
                file.delete()
                return null
            }
        }
        return file
    }

    private fun calculateLevel(
        buffer: ByteArray,
        count: Int,
    ): Float {
        var sumSquares = 0.0
        var samples = 0
        var index = 0
        while (index + 1 < count) {
            val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xFF)).toShort().toInt()
            sumSquares += sample.toDouble() * sample.toDouble()
            samples += 1
            index += 2
        }
        if (samples == 0) return 0.05f
        val rms = sqrt(sumSquares / samples)
        val decibels = 20.0 * log10((rms / Short.MAX_VALUE).coerceAtLeast(0.0001))
        return ((decibels + 55.0) / 45.0).toFloat().coerceIn(0.05f, 1f)
    }

    private fun finalizeWavHeader(file: File) {
        val dataSize = (file.length() - WAV_HEADER_SIZE).coerceAtLeast(0L)
        if (dataSize <= 0L || dataSize > Int.MAX_VALUE) return
        RandomAccessFile(file, "rw").use { wav ->
            wav.seek(0)
            wav.write(createWavHeader(dataSize.toInt()))
        }
    }

    companion object {
        internal const val SAMPLE_RATE = 16_000
        internal const val WAV_HEADER_SIZE = 44
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_SECOND = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        private const val MAX_DURATION_SECONDS = 120
        private const val MAX_DATA_BYTES = BYTES_PER_SECOND.toLong() * MAX_DURATION_SECONDS
        private const val THREAD_JOIN_TIMEOUT_MS = 3_000L

        internal fun cleanupStaleRecordings(context: Context) {
            context.applicationContext.cacheDir
                .listFiles { file -> file.name.startsWith("cassy-voice-") && file.extension == "wav" }
                ?.forEach(File::delete)
        }

        internal fun createWavHeader(dataSize: Int): ByteArray =
            ByteBuffer
                .allocate(WAV_HEADER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put("RIFF".toByteArray(Charsets.US_ASCII))
                    putInt(36 + dataSize)
                    put("WAVE".toByteArray(Charsets.US_ASCII))
                    put("fmt ".toByteArray(Charsets.US_ASCII))
                    putInt(16)
                    putShort(1.toShort())
                    putShort(CHANNELS.toShort())
                    putInt(SAMPLE_RATE)
                    putInt(BYTES_PER_SECOND)
                    putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort())
                    putShort(BITS_PER_SAMPLE.toShort())
                    put("data".toByteArray(Charsets.US_ASCII))
                    putInt(dataSize)
                }.array()
    }
}
