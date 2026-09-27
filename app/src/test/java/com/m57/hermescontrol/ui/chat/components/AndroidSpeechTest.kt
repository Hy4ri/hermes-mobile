package com.m57.hermescontrol.ui.chat.components

import android.content.Context
import com.m57.hermescontrol.data.model.TtsSpeakRequest
import com.m57.hermescontrol.data.model.TtsSpeakResponse
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSpeechTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createContext(): Context {
        val mockContext = mockk<Context>()
        every { mockContext.applicationContext } returns mockContext
        every { mockContext.cacheDir } returns tempFolder.root
        return mockContext
    }

    private fun listTempFiles(): List<File> =
        tempFolder.root
            .walkTopDown()
            .filter { it.isFile }
            .toList()

    @Test
    fun `synthesize decodes small real base64 audio bytes to exact file bytes and passes text and profile`() =
        runTest {
            val context = createContext()
            val api = mockk<HermesApiService>()
            val expectedBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00)
            val base64Data = Base64.getEncoder().encodeToString(expectedBytes)
            val dataUrl = "data:audio/wav;base64,$base64Data"

            coEvery { api.speakText(any(), any()) } returns
                Response.success(
                    TtsSpeakResponse(
                        ok = true,
                        data_url = dataUrl,
                        mime_type = "audio/wav",
                        provider = "piper",
                    ),
                )

            val synthesizer: SpeechSynthesizer =
                AndroidSpeechSynthesizer(
                    context = context,
                    api = api,
                    profile = "work-profile",
                )

            val request =
                SpeechRequest(
                    scopeKey = "session-42",
                    messageId = "msg-101",
                    text = "Hello world",
                )

            val resultAudio: SpeechAudio = synthesizer.synthesize(request)
            assertTrue("Expected result to be FileSpeechAudio", resultAudio is FileSpeechAudio)

            val fileAudio = resultAudio as FileSpeechAudio
            val audioFile = fileAudio.file

            assertTrue("Synthesized audio file must exist", audioFile.exists())
            assertArrayEquals("Decoded file bytes must match input bytes", expectedBytes, audioFile.readBytes())

            coVerify(exactly = 1) {
                api.speakText(
                    request = TtsSpeakRequest(text = "Hello world"),
                    profile = "work-profile",
                )
            }

            fileAudio.dispose()
            assertFalse("File must be deleted after dispose()", audioFile.exists())
        }

    @Test
    fun `synthesize passes null profile when synthesizer constructed without profile`() =
        runTest {
            val context = createContext()
            val api = mockk<HermesApiService>()
            val rawBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            val base64Data = Base64.getEncoder().encodeToString(rawBytes)

            coEvery { api.speakText(any(), any()) } returns
                Response.success(
                    TtsSpeakResponse(
                        ok = true,
                        data_url = "data:audio/mp3;base64,$base64Data",
                        mime_type = "audio/mp3",
                        provider = null,
                    ),
                )

            val synthesizer: SpeechSynthesizer =
                AndroidSpeechSynthesizer(
                    context = context,
                    api = api,
                    profile = null,
                )

            val request =
                SpeechRequest(
                    scopeKey = "session-1",
                    messageId = "msg-1",
                    text = "Testing null profile",
                )

            val audio = synthesizer.synthesize(request)
            assertTrue(audio is FileSpeechAudio)

            coVerify(exactly = 1) {
                api.speakText(
                    request = TtsSpeakRequest(text = "Testing null profile"),
                    profile = null,
                )
            }

            audio.dispose()
            assertEquals(0, listTempFiles().size)
        }

    @Test
    fun `HTTP or network failure throws exception and leaves no temp files`() =
        runTest {
            val context = createContext()
            val api = mockk<HermesApiService>()

            coEvery { api.speakText(any(), any()) } throws IOException("Network connection lost")

            val synthesizer: SpeechSynthesizer =
                AndroidSpeechSynthesizer(
                    context = context,
                    api = api,
                    profile = "test-profile",
                )

            val request =
                SpeechRequest(
                    scopeKey = "session-err",
                    messageId = "msg-err",
                    text = "Network fail",
                )

            try {
                synthesizer.synthesize(request)
                fail("Expected IOException to be thrown")
            } catch (e: Exception) {
                assertNotNull(e)
            }

            assertEquals("No temp files should remain after network failure", 0, listTempFiles().size)
        }

    @Test
    fun `response with ok false throws exception and leaves no temp files`() =
        runTest {
            val context = createContext()
            val api = mockk<HermesApiService>()

            coEvery { api.speakText(any(), any()) } returns
                Response.success(
                    TtsSpeakResponse(
                        ok = false,
                        data_url = null,
                        mime_type = null,
                        provider = null,
                    ),
                )

            val synthesizer: SpeechSynthesizer =
                AndroidSpeechSynthesizer(
                    context = context,
                    api = api,
                    profile = "profile-1",
                )

            val request =
                SpeechRequest(
                    scopeKey = "s1",
                    messageId = "m1",
                    text = "Failed ok=false",
                )

            try {
                synthesizer.synthesize(request)
                fail("Expected synthesize to fail when ok is false")
            } catch (e: Exception) {
                assertNotNull(e)
            }

            assertEquals("No temp files should remain after ok=false", 0, listTempFiles().size)
        }

    @Test
    fun `malformed data URI scheme throws exception and leaves no temp files`() =
        runTest {
            val context = createContext()
            val api = mockk<HermesApiService>()

            coEvery { api.speakText(any(), any()) } returns
                Response.success(
                    TtsSpeakResponse(
                        ok = true,
                        data_url = "not-a-data-uri",
                        mime_type = "audio/wav",
                        provider = "piper",
                    ),
                )

            val synthesizer: SpeechSynthesizer =
                AndroidSpeechSynthesizer(
                    context = context,
                    api = api,
                    profile = null,
                )

            val request =
                SpeechRequest(
                    scopeKey = "s1",
                    messageId = "m1",
                    text = "Malformed scheme",
                )

            try {
                synthesizer.synthesize(request)
                fail("Expected failure for malformed data URI")
            } catch (e: Exception) {
                assertNotNull(e)
            }

            assertEquals("No temp files should remain after malformed URI failure", 0, listTempFiles().size)
        }

    @Test
    fun `non-audio data URI throws exception and leaves no temp files`() =
        runTest {
            val context = createContext()
            val api = mockk<HermesApiService>()
            val textBase64 = Base64.getEncoder().encodeToString("hello world".toByteArray())

            coEvery { api.speakText(any(), any()) } returns
                Response.success(
                    TtsSpeakResponse(
                        ok = true,
                        data_url = "data:text/plain;base64,$textBase64",
                        mime_type = "text/plain",
                        provider = "text",
                    ),
                )

            val synthesizer: SpeechSynthesizer =
                AndroidSpeechSynthesizer(
                    context = context,
                    api = api,
                    profile = null,
                )

            val request =
                SpeechRequest(
                    scopeKey = "s1",
                    messageId = "m1",
                    text = "Non audio data URI",
                )

            try {
                synthesizer.synthesize(request)
                fail("Expected failure for non-audio data URI")
            } catch (e: Exception) {
                assertNotNull(e)
            }

            assertEquals("No temp files should remain after non-audio data URI failure", 0, listTempFiles().size)
        }

    @Test
    fun `invalid base64 payload throws exception and leaves no temp files`() =
        runTest {
            val context = createContext()
            val api = mockk<HermesApiService>()

            coEvery { api.speakText(any(), any()) } returns
                Response.success(
                    TtsSpeakResponse(
                        ok = true,
                        data_url = "data:audio/wav;base64,!!!NotBase64Chars!!!",
                        mime_type = "audio/wav",
                        provider = "piper",
                    ),
                )

            val synthesizer: SpeechSynthesizer =
                AndroidSpeechSynthesizer(
                    context = context,
                    api = api,
                    profile = null,
                )

            val request =
                SpeechRequest(
                    scopeKey = "s1",
                    messageId = "m1",
                    text = "Invalid base64",
                )

            try {
                synthesizer.synthesize(request)
                fail("Expected failure for invalid base64")
            } catch (e: Exception) {
                assertNotNull(e)
            }

            assertEquals("No temp files should remain after base64 decoding failure", 0, listTempFiles().size)
        }

    @Test
    fun `empty data URI payload throws exception and leaves no temp files`() =
        runTest {
            val context = createContext()
            val api = mockk<HermesApiService>()

            coEvery { api.speakText(any(), any()) } returns
                Response.success(
                    TtsSpeakResponse(
                        ok = true,
                        data_url = "data:audio/wav;base64,",
                        mime_type = "audio/wav",
                        provider = "piper",
                    ),
                )

            val synthesizer: SpeechSynthesizer =
                AndroidSpeechSynthesizer(
                    context = context,
                    api = api,
                    profile = null,
                )

            val request =
                SpeechRequest(
                    scopeKey = "s1",
                    messageId = "m1",
                    text = "Empty payload",
                )

            try {
                synthesizer.synthesize(request)
                fail("Expected failure for empty base64 payload")
            } catch (e: Exception) {
                assertNotNull(e)
            }

            assertEquals("No temp files should remain after empty payload failure", 0, listTempFiles().size)
        }

    @Test
    fun `null data_url throws exception and leaves no temp files`() =
        runTest {
            val context = createContext()
            val api = mockk<HermesApiService>()

            coEvery { api.speakText(any(), any()) } returns
                Response.success(
                    TtsSpeakResponse(
                        ok = true,
                        data_url = null,
                        mime_type = "audio/wav",
                        provider = "piper",
                    ),
                )

            val synthesizer: SpeechSynthesizer =
                AndroidSpeechSynthesizer(
                    context = context,
                    api = api,
                    profile = null,
                )

            val request =
                SpeechRequest(
                    scopeKey = "s1",
                    messageId = "m1",
                    text = "Null data url",
                )

            try {
                synthesizer.synthesize(request)
                fail("Expected failure when data_url is null")
            } catch (e: Exception) {
                assertNotNull(e)
            }

            assertEquals("No temp files should remain after null data_url failure", 0, listTempFiles().size)
        }

    @Test
    fun `padded valid base64 followed by garbage throws exception and leaves no temp files`() =
        runTest {
            val context = createContext()
            val api = mockk<HermesApiService>()
            val validPaddedBase64 = "AQ==" // 1 byte decoded (0x01), padded with ==
            val payloadWithTrailingGarbage = validPaddedBase64 + "garbage"

            coEvery { api.speakText(any(), any()) } returns
                Response.success(
                    TtsSpeakResponse(
                        ok = true,
                        data_url = "data:audio/wav;base64,$payloadWithTrailingGarbage",
                        mime_type = "audio/wav",
                        provider = "piper",
                    ),
                )

            val synthesizer: SpeechSynthesizer =
                AndroidSpeechSynthesizer(
                    context = context,
                    api = api,
                    profile = null,
                )

            val request =
                SpeechRequest(
                    scopeKey = "s1",
                    messageId = "m1",
                    text = "Garbage after padding",
                )

            try {
                synthesizer.synthesize(request)
                fail("Expected failure for base64 with trailing garbage after padding")
            } catch (e: Exception) {
                assertNotNull(e)
            }

            assertEquals("No temp files should remain after trailing garbage failure", 0, listTempFiles().size)
        }
}
