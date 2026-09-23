package com.m57.hermescontrol.data.repository

import com.m57.hermescontrol.data.model.AudioTranscriptionRequest
import com.m57.hermescontrol.data.model.AudioTranscriptionResponse
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.NetworkResult
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.File

class VoiceNoteRepositoryTest {
    private val api = mockk<HermesApiService>()

    @Test
    fun transcribe_uploadsBase64DataUrlAndReturnsTrimmedTranscript() =
        runTest {
            val file =
                File.createTempFile("voice-note-test", ".m4a").apply {
                    writeBytes(byteArrayOf(1, 2, 3))
                    deleteOnExit()
                }
            val bodySlot = slot<AudioTranscriptionRequest>()
            coEvery { api.transcribeAudio(capture(bodySlot)) } returns
                Response.success(
                    AudioTranscriptionResponse(ok = true, transcript = "  hello world  "),
                )

            val result = VoiceNoteRepository(api).transcribe(file)

            val success = result as NetworkResult.Success
            assertEquals("hello world", success.data)
            assertEquals("audio/mp4", bodySlot.captured.mimeType)
            assertEquals("data:audio/mp4;base64,AQID", bodySlot.captured.dataUrl)
        }

    @Test
    fun transcribe_propagatesServerFailure() =
        runTest {
            val file =
                File.createTempFile("voice-note-test", ".m4a").apply {
                    writeBytes(byteArrayOf(1))
                    deleteOnExit()
                }
            coEvery { api.transcribeAudio(any()) } returns
                Response.error(422, "no speech".toResponseBody("text/plain".toMediaType()))

            val result = VoiceNoteRepository(api).transcribe(file)

            assertTrue(result is NetworkResult.Failure)
        }
}
