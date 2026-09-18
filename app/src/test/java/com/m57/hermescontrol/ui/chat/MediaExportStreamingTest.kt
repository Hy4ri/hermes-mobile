package com.m57.hermescontrol.ui.chat

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.m57.hermescontrol.data.remote.MediaDataSourceProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.OutputStream
import java.util.Base64

class MediaExportStreamingTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context = mockk<Context>(relaxed = true)

    @Test
    fun `useStream streams local file without loading into whole-media buffer`() =
        runTest {
            val file = tempFolder.newFile("sample.wav")
            file.writeText("RIFF....WAVEfmt ")

            var readString = ""
            MediaStreamResolver.useStream(context, file.absolutePath, "audio/wav") { stream, info ->
                assertEquals("audio/wav", info.mimeType)
                assertEquals("wav", info.extension)
                assertEquals(file.length(), info.length)
                val reader = stream.bufferedReader()
                readString = reader.readText()
            }
            assertEquals("RIFF....WAVEfmt ", readString)
        }

    @Test
    fun `useStream decodes data URL on the fly and handles whitespace`() =
        runTest {
            val expected = "streaming-test-content-audio"
            val encoded = Base64.getEncoder().encodeToString(expected.toByteArray())
            val dataUrl = "data:audio/mp3;base64,  ${encoded.take(5)} \n ${encoded.drop(5)}  "

            var result = ""
            MediaStreamResolver.useStream(context, dataUrl, "audio/mp3") { stream, info ->
                assertEquals("audio/mp3", info.mimeType)
                assertEquals("mp3", info.extension)
                assertNull(info.length)
                result = stream.bufferedReader().readText()
            }
            assertEquals(expected, result)
        }

    @Test
    fun `useStream rethrows CancellationException rather than catching as user error`() =
        runTest {
            val file = tempFolder.newFile("cancel.mp4")
            file.writeText("sample")

            try {
                MediaStreamResolver.useStream(context, file.absolutePath, "video/mp4") { _, _ ->
                    throw CancellationException("user cancelled operation")
                }
                fail("Expected CancellationException to be rethrown")
            } catch (e: CancellationException) {
                assertEquals("user cancelled operation", e.message)
            }
        }

    @Test
    fun `resolveDisplayName cleans traversal characters and guarantees valid extension`() {
        val resolved = MediaExportHelper.resolveDisplayName("../../evil:name/track", "audio/mpeg", "https://x/track")
        assertEquals("______evil_name_track.mp3", resolved)
        assertFalse(resolved.contains("/"))
        assertFalse(resolved.contains("\\"))
        assertFalse(resolved.contains(":"))
    }

    @Test
    fun `SameOriginAuthInterceptor does not stamp bearer token in gated mode`() {
        val interceptor =
            MediaDataSourceProvider.SameOriginAuthInterceptor(
                baseUrlProvider = { "http://192.168.1.50:9119/" },
                tokenProvider = { "secret-token" },
                isGatedModeProvider = { true },
            )

        var executedRequest: Request? = null
        val chain =
            mockk<Interceptor.Chain> {
                every { request() } returns
                    Request
                        .Builder()
                        .url(
                            "http://192.168.1.50:9119/api/files/stream?path=test.mp3",
                        ).build()
                every { proceed(any()) } answers {
                    executedRequest = firstArg()
                    Response
                        .Builder()
                        .request(firstArg())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
            }

        interceptor.intercept(chain)
        assertNull(executedRequest?.header("Authorization"))
    }

    @Test
    fun `SameOriginAuthInterceptor does not stamp bearer token on external origins`() {
        val interceptor =
            MediaDataSourceProvider.SameOriginAuthInterceptor(
                baseUrlProvider = { "http://192.168.1.50:9119/" },
                tokenProvider = { "secret-token" },
                isGatedModeProvider = { false },
            )

        var executedRequest: Request? = null
        val chain =
            mockk<Interceptor.Chain> {
                every { request() } returns Request.Builder().url("https://external-cdn.com/audio.mp3").build()
                every { proceed(any()) } answers {
                    executedRequest = firstArg()
                    Response
                        .Builder()
                        .request(firstArg())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
            }

        interceptor.intercept(chain)
        assertNull(executedRequest?.header("Authorization"))
    }

    @Test
    fun `SameOriginAuthInterceptor stamps bearer token for non-gated same-origin requests`() {
        val interceptor =
            MediaDataSourceProvider.SameOriginAuthInterceptor(
                baseUrlProvider = { "http://192.168.1.50:9119/" },
                tokenProvider = { "secret-token" },
                isGatedModeProvider = { false },
            )

        var executedRequest: Request? = null
        val chain =
            mockk<Interceptor.Chain> {
                every { request() } returns
                    Request
                        .Builder()
                        .url(
                            "http://192.168.1.50:9119/api/files/download?path=test.mp4",
                        ).build()
                every { proceed(any()) } answers {
                    executedRequest = firstArg()
                    Response
                        .Builder()
                        .request(firstArg())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
            }

        interceptor.intercept(chain)
        assertEquals("Bearer secret-token", executedRequest?.header("Authorization"))
    }

    @Test
    fun `saveMediaToUri cleans up partial target if stream copy fails`() =
        runTest {
            val sourceFile = tempFolder.newFile("failing-source.mp3")
            sourceFile.writeText("partial-content")

            val targetUri = mockk<Uri>(relaxed = true)
            val failingStream =
                object : OutputStream() {
                    override fun write(b: Int): Unit = throw IOException("Disk write failure mid-stream")
                }

            val mockResolver = mockk<ContentResolver>(relaxed = true)
            every { mockResolver.openOutputStream(targetUri) } returns failingStream
            every { context.contentResolver } returns mockResolver

            try {
                MediaExportHelper.saveMediaToUri(
                    context = context,
                    sourceUri = sourceFile.absolutePath,
                    targetUri = targetUri,
                    fallbackMime = "audio/mpeg",
                )
                fail("Expected IOException")
            } catch (_: IOException) {
                // Verified: partial item must be deleted on failure
                verify { mockResolver.delete(targetUri, null, null) }
            }
        }
}
