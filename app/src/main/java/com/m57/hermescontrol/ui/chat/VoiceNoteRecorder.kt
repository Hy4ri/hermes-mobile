package com.m57.hermescontrol.ui.chat

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Records a hold-to-talk voice note as an MPEG-4/AAC file in the app cache.
 *
 * One instance drives one in-flight recording; the call site owns UI state and
 * decides what to do with the clip. 16 kHz mono AAC keeps the payload small
 * (roughly 0.5 MB at [MAX_DURATION_MS]) so it uploads quickly to the
 * dashboard's transcription endpoint.
 */
class VoiceNoteRecorder(
    private val context: Context,
) {
    companion object {
        private const val TAG = "VoiceNoteRecorder"

        /** Clips shorter than this are treated as accidental and discarded. */
        const val MIN_DURATION_MS = 500L

        /**
         * Hard cap. The call site (ChatMediaLaunchers) owns the timer and
         * drives the single stop path — the recorder itself no longer stops
         * recording, so MediaRecorder's auto-stop cannot race the
         * hold-release stop (review, PR #1250).
         */
        const val MAX_DURATION_MS = 120_000
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMs = 0L

    val isActive: Boolean
        get() = recorder != null

    /** Starts recording. Returns false when the microphone is unavailable. */
    fun start(): Boolean {
        if (recorder != null) return false
        val file =
            runCatching { File.createTempFile("voice_", ".m4a", context.cacheDir) }
                .getOrNull()
                ?: return false
        return try {
            val mediaRecorder =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(32_000)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = mediaRecorder
            outputFile = file
            startedAtMs = SystemClock.elapsedRealtime()
            Log.i(TAG, "Voice note recording started: ${file.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start voice note recording", e)
            releaseQuietly()
            file.delete()
            false
        }
    }

    /**
     * Stops recording and returns the clip when it is long enough to send.
     * Returns null for too-short or failed recordings — the temp file is
     * removed in both cases.
     */
    fun stop(): File? {
        val active = recorder ?: return null
        val file = outputFile
        val durationMs = SystemClock.elapsedRealtime() - startedAtMs
        val stopped = runCatching { active.stop() }.isSuccess
        releaseQuietly()
        val byteCount = file?.length() ?: -1L
        Log.i(TAG, "Voice note stop: durationMs=$durationMs stopped=$stopped bytes=$byteCount")
        if (!stopped || file == null || durationMs < MIN_DURATION_MS) {
            file?.delete()
            return null
        }
        return file
    }

    /** Aborts the recording and deletes the clip. */
    fun cancel() {
        if (recorder != null) {
            runCatching { recorder?.stop() }
        }
        releaseQuietly()
        outputFile?.delete()
    }

    /** Peak amplitude of the current recording window — 0 when idle. */
    fun currentAmplitude(): Int = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    private fun releaseQuietly() {
        runCatching { recorder?.release() }
        recorder = null
        outputFile = null
        startedAtMs = 0L
    }
}
