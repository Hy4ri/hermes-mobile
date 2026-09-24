package com.m57.hermescontrol.data.repository

import com.m57.hermescontrol.data.model.AudioTranscriptionRequest
import com.m57.hermescontrol.data.model.AudioTranscriptionResponse
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.NetworkResult
import io.mockk.coEvery
import io.mockk.coVerify
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
import java.net.SocketTimeoutException

class VoiceNoteRepositoryTest {
    private val api = mockk<HermesApiService>()

    private fun tempVoiceFile(bytes: ByteArray = byteArrayOf(1)): File =
        File.createTempFile("voice-note-test", ".m4a").apply {
            writeBytes(bytes)
            deleteOnExit()
        }

    @Test
    fun transcribe_uploadsBase64DataUrlAndReturnsTrimmedTranscript() =
        runTest {
            val file = tempVoiceFile(byteArrayOf(1, 2, 3))
            val bodySlot = slot<AudioTranscriptionRequest>()
            coEvery { api.transcribeAudio(capture(bodySlot)) } returns
                Response.success(
                    AudioTranscriptionResponse(ok = true, transcript = "  hello world  "),
                )

            val result = VoiceNoteRepository { _ -> api }.transcribe(file)

            val success = result as NetworkResult.Success
            assertEquals("hello world", success.data)
            assertEquals("audio/mp4", bodySlot.captured.mimeType)
            assertEquals("data:audio/mp4;base64,AQID", bodySlot.captured.dataUrl)
        }

    @Test
    fun transcribe_propagatesServerFailureWithoutRetrying() =
        runTest {
            val file = tempVoiceFile()
            coEvery { api.transcribeAudio(any()) } returns
                Response.error(422, "no speech".toResponseBody("text/plain".toMediaType()))

            val result = VoiceNoteRepository { _ -> api }.transcribe(file)

            assertTrue(result is NetworkResult.Failure)
            coVerify(exactly = 1) { api.transcribeAudio(any()) }
        }

    @Test
    fun transcribe_doesNotRetryThePostAfterASocketTimeout() =
        runTest {
            // safeApiCall's default policy retries socket timeouts and 5xx; a
            // blind re-POST would repeat provider STT work after the request
            // may already have reached the server (review, PR #1250).
            val file = tempVoiceFile()
            coEvery { api.transcribeAudio(any()) } throws SocketTimeoutException("stt timeout")

            val result = VoiceNoteRepository { _ -> api }.transcribe(file)

            assertTrue(result is NetworkResult.Failure)
            coVerify(exactly = 1) { api.transcribeAudio(any()) }
        }

    @Test
    fun transcribe_resolvesTheServiceAtCallTime_soAConnectionSwitchUsesTheNewService() =
        runTest {
            val firstApi = mockk<HermesApiService>()
            val secondApi = mockk<HermesApiService>()
            coEvery { firstApi.transcribeAudio(any()) } returns
                Response.success(AudioTranscriptionResponse(ok = true, transcript = "old server"))
            coEvery { secondApi.transcribeAudio(any()) } returns
                Response.success(AudioTranscriptionResponse(ok = true, transcript = "new server"))
            var currentApi = firstApi
            val repository = VoiceNoteRepository { _ -> currentApi }

            val before = repository.transcribe(tempVoiceFile())
            // Simulate ApiClient.rebuild() after a connection/profile switch:
            // the next call must use the new service, not the captured one.
            currentApi = secondApi
            val after = repository.transcribe(tempVoiceFile())

            assertEquals("old server", (before as NetworkResult.Success).data)
            assertEquals("new server", (after as NetworkResult.Success).data)
            coVerify(exactly = 1) { firstApi.transcribeAudio(any()) }
            coVerify(exactly = 1) { secondApi.transcribeAudio(any()) }
        }

    @Test
    fun transcribe_requestsTheDesktopParitySttTimeout() =
        runTest {
            val requestedTimeouts = mutableListOf<Long>()
            coEvery { api.transcribeAudio(any()) } returns
                Response.success(AudioTranscriptionResponse(ok = true, transcript = "ok"))

            VoiceNoteRepository { timeout ->
                requestedTimeouts += timeout
                api
            }.transcribe(tempVoiceFile())

            // A tiny clip must still get the 180s floor, not the shared 30s client.
            assertEquals(listOf(VoiceNoteRepository.MIN_REQUEST_TIMEOUT_MS), requestedTimeouts)
        }

    @Test
    fun transcriptionTimeoutMs_scalesWithPayloadThenClamps() {
        assertEquals(180_000L, VoiceNoteRepository.transcriptionTimeoutMs("data:audio/mp4;base64,AQID"))
        assertEquals(200_000L, VoiceNoteRepository.transcriptionTimeoutMs("x".repeat(2_000_000)))
        assertEquals(600_000L, VoiceNoteRepository.transcriptionTimeoutMs("x".repeat(10_000_000)))
    }

    @Test
    fun transcribe_unreadableFileReturnsFailureWithoutUploading() =
        runTest {
            val missing = tempVoiceFile().apply { delete() }

            val result = VoiceNoteRepository { _ -> api }.transcribe(missing)

            assertTrue(result is NetworkResult.Failure)
            coVerify(exactly = 0) { api.transcribeAudio(any()) }
        }
}
