package com.m57.hermescontrol.ui.chat.components

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** A completed stream becomes a WAV so the existing file player/cache can replay it without TTS. */
internal class SpeechPcmCache(
    cacheRoot: File,
    private val sampleRate: Int,
) : AutoCloseable {
    private val file: File
    private val writer: RandomAccessFile
    private var bytesWritten = 0L
    private var sealed = false
    private var transferred = false

    init {
        val directory = File(cacheRoot, "speech_tts").apply { mkdirs() }
        val oldest = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        directory
            .listFiles { _, name -> name.startsWith("tts_") }
            ?.filter { it.lastModified() < oldest }
            ?.forEach { it.delete() }
        file = File.createTempFile("tts_", ".wav", directory)
        try {
            val opened = RandomAccessFile(file, "rw")
            try {
                opened.write(ByteArray(44))
            } catch (e: Exception) {
                opened.close()
                throw e
            }
            writer = opened
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    suspend fun append(bytes: ByteArray) =
        withContext(Dispatchers.IO) {
            // Bound the disk cache, not playback. Failure simply disables caching this reply.
            if (bytesWritten + bytes.size > MAX_CACHE_BYTES) throw IOException("Speech cache limit exceeded")
            writer.write(bytes)
            bytesWritten += bytes.size
        }

    suspend fun seal() =
        withContext(Dispatchers.IO) {
            writer.seek(0)
            writer.writeBytes("RIFF")
            writer.writeInt(Integer.reverseBytes((bytesWritten + 36).toInt()))
            writer.writeBytes("WAVEfmt ")
            writer.writeInt(Integer.reverseBytes(16))
            writer.writeShort(
                java.lang.Short
                    .reverseBytes(1.toShort())
                    .toInt(),
            )
            writer.writeShort(
                java.lang.Short
                    .reverseBytes(1.toShort())
                    .toInt(),
            )
            writer.writeInt(Integer.reverseBytes(sampleRate))
            writer.writeInt(Integer.reverseBytes(sampleRate * 2))
            writer.writeShort(
                java.lang.Short
                    .reverseBytes(2.toShort())
                    .toInt(),
            )
            writer.writeShort(
                java.lang.Short
                    .reverseBytes(16.toShort())
                    .toInt(),
            )
            writer.writeBytes("data")
            writer.writeInt(Integer.reverseBytes(bytesWritten.toInt()))
            writer.close()
            sealed = true
        }

    /** Transfer only after the suspending seal resumes: cancelled dispatch must still delete the file. */
    fun takeAudio(): SpeechAudio {
        check(sealed && !transferred)
        transferred = true
        return FileSpeechAudio(file)
    }

    override fun close() {
        try {
            writer.close()
        } finally {
            if (!transferred) file.delete()
        }
    }

    companion object {
        private const val MAX_CACHE_BYTES = 64L * 1024 * 1024
    }
}
