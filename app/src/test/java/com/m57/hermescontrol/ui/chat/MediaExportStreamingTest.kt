package com.m57.hermescontrol.ui.chat

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.GatewayFileClient
import com.m57.hermescontrol.data.remote.MediaDataSourceProvider
import com.m57.hermescontrol.data.remote.buildFakePersistentCookieJar
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

class MediaExportStreamingTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context = mockk<Context>(relaxed = true)
    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        MediaDataSourceProvider.testClient =
            okhttp3.OkHttpClient
                .Builder()
                .addInterceptor(
                    MediaDataSourceProvider.SameOriginAuthInterceptor(
                        baseUrlProvider = { mockWebServer.url("/").toString() },
                        tokenProvider = { "test-token" },
                        isGatedModeProvider = { false },
                    ),
                ).build()
    }

    @After
    fun tearDown() {
        MediaDataSourceProvider.testClient = null
        mockWebServer.shutdown()
    }

    // ── 1. API Level Strategy Tests ─────────────────────────────────────────

    @Test
    fun `mediaSaveStrategy resolves correctly for API 26-28 vs 29+`() {
        assertEquals(MediaSaveStrategy.CREATE_DOCUMENT, mediaSaveStrategy(26))
        assertEquals(MediaSaveStrategy.CREATE_DOCUMENT, mediaSaveStrategy(27))
        assertEquals(MediaSaveStrategy.CREATE_DOCUMENT, mediaSaveStrategy(28))
        assertEquals(MediaSaveStrategy.MEDIA_STORE, mediaSaveStrategy(29))
        assertEquals(MediaSaveStrategy.MEDIA_STORE, mediaSaveStrategy(30))
        assertEquals(MediaSaveStrategy.MEDIA_STORE, mediaSaveStrategy(34))
        assertEquals(MediaSaveStrategy.MEDIA_STORE, mediaSaveStrategy(37))
    }

    // ── 2. MIME Normalization Tests ─────────────────────────────────────────

    @Test
    fun `normalizeFrameworkMime strips parameters and handles fallbacks`() {
        assertEquals("audio/ogg", normalizeFrameworkMime("audio/ogg; codecs=opus"))
        assertEquals("video/mp4", normalizeFrameworkMime("video/mp4"))
        assertEquals("audio/mpeg", normalizeFrameworkMime(" audio/mpeg "))
        assertEquals("application/octet-stream", normalizeFrameworkMime(null))
        assertEquals("application/octet-stream", normalizeFrameworkMime(""))
        assertEquals("application/octet-stream", normalizeFrameworkMime("   "))
    }

    // ── 3. Filename Sanitization Tests ──────────────────────────────────────

    @Test
    fun `resolveDisplayName cleans traversal and respects extensions`() {
        assertEquals(
            "video.mp4",
            MediaExportHelper.resolveDisplayName("../../evil/video.mp4", "video/mp4", "https://x/"),
        )
        assertEquals(
            "recording.wav",
            MediaExportHelper.resolveDisplayName("C:\\Users\\x\\recording.wav", "audio/wav", "https://x/"),
        )
        assertEquals("clip.mp4", MediaExportHelper.resolveDisplayName("clip", "video/mp4", "https://x/"))
        assertEquals("recording.mp3", MediaExportHelper.resolveDisplayName("recording", "audio/mpeg", "https://x/"))
        assertEquals("song.m4a", MediaExportHelper.resolveDisplayName("song.m4a", "audio/mp4", "https://x/"))
        assertEquals("hermes-media.wav", MediaExportHelper.resolveDisplayName("", "audio/wav", ""))
    }

    // ── 4. Local File & Directory Source Validation ─────────────────────────

    @Test
    fun `useStream streams local file without whole-media buffering`() =
        runTest {
            val file = tempFolder.newFile("sample.wav")
            file.writeText("RIFF....WAVEfmt ")

            var readString = ""
            MediaStreamResolver.useStream(context, file.absolutePath, "audio/wav") { stream, info ->
                assertEquals("audio/wav", info.mimeType)
                assertEquals("wav", info.extension)
                assertEquals(file.length(), info.length)
                readString = stream.bufferedReader().readText()
            }
            assertEquals("RIFF....WAVEfmt ", readString)
        }

    @Test
    fun `useStream rejects directories cleanly for absolute and file URI sources`() =
        runTest {
            val dir = tempFolder.newFolder("test-dir")
            try {
                MediaStreamResolver.useStream(context, dir.absolutePath, "audio/wav") { _, _ -> }
                fail("Expected FileNotFoundException for directory path")
            } catch (e: FileNotFoundException) {
                assertTrue(e.message?.contains("Not a valid file") == true)
            }

            try {
                MediaStreamResolver.useStream(context, dir.toURI().toString(), "audio/wav") { _, _ -> }
                fail("Expected FileNotFoundException for directory file URI")
            } catch (e: FileNotFoundException) {
                assertTrue(e.message?.contains("Not a valid file") == true)
            }
        }

    // ── 5. Data URL Streaming Tests ─────────────────────────────────────────

    @Test
    fun `useStream decodes data URL on the fly without substring duplication`() =
        runTest {
            val expected = "streaming-test-content-audio-without-substring-allocations"
            val encoded = Base64.getEncoder().encodeToString(expected.toByteArray())
            val dataUrl = "data:audio/mp3;base64,  ${encoded.take(6)} \r\n ${encoded.drop(6)}  "

            var result = ""
            MediaStreamResolver.useStream(context, dataUrl, "audio/mp3") { stream, info ->
                assertEquals("audio/mp3", info.mimeType)
                assertEquals("mp3", info.extension)
                assertNull(info.length)
                result = stream.bufferedReader().readText()
            }
            assertEquals(expected, result)
        }

    // ── 6. MockWebServer HTTP Streaming Tests ───────────────────────────────

    @Test
    fun `useStream streams large HTTP response with verified digest and length`() =
        runTest {
            val chunkSize = 64 * 1024
            val chunkCount = 4 // 256 KB payload
            val expectedDigest = MessageDigest.getInstance("SHA-256")

            val buffer = okio.Buffer()
            val chunkPattern = ByteArray(chunkSize) { (it % 128).toByte() }
            repeat(chunkCount) {
                buffer.write(chunkPattern)
                expectedDigest.update(chunkPattern)
            }
            val expectedSha = expectedDigest.digest().joinToString("") { "%02x".format(it) }

            mockWebServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "video/mp4; codecs=avc1")
                    .setBody(buffer),
            )

            val url = mockWebServer.url("/media/video.mp4").toString()
            val actualDigest = MessageDigest.getInstance("SHA-256")
            val copyBuffer = ByteArray(16 * 1024)

            MediaStreamResolver.useStream(context, url, "video/mp4") { stream, info ->
                assertEquals("video/mp4", info.mimeType)
                assertEquals("mp4", info.extension)
                assertEquals((chunkSize * chunkCount).toLong(), info.length)

                while (true) {
                    val read = stream.read(copyBuffer)
                    if (read < 0) break
                    actualDigest.update(copyBuffer, 0, read)
                }
            }

            val actualSha = actualDigest.digest().joinToString("") { "%02x".format(it) }
            assertEquals(expectedSha, actualSha)
        }

    @Test
    fun `useStream handles chunked HTTP transfer with null length`() =
        runTest {
            val content = "chunked-audio-data-payload"
            mockWebServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "audio/mpeg")
                    .setChunkedBody(content, 4),
            )

            val url = mockWebServer.url("/media/stream").toString()
            var result = ""
            MediaStreamResolver.useStream(context, url, "audio/mpeg") { stream, info ->
                assertEquals("audio/mpeg", info.mimeType)
                assertNull(info.length)
                result = stream.bufferedReader().readText()
            }
            assertEquals(content, result)
        }

    @Test
    fun `useStream falls back from application octet-stream to provided fallback or URL inference`() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody("payload"),
            )
            val url1 = mockWebServer.url("/file/track").toString()
            MediaStreamResolver.useStream(context, url1, fallbackMime = "audio/ogg") { _, info ->
                assertEquals("audio/ogg", info.mimeType)
                assertEquals("ogg", info.extension)
            }

            mockWebServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody("payload"),
            )
            val url2 = mockWebServer.url("/downloads/song.mp3").toString()
            MediaStreamResolver.useStream(context, url2, fallbackMime = "application/octet-stream") { _, info ->
                assertEquals("audio/mpeg", info.mimeType)
                assertEquals("mp3", info.extension)
            }
        }

    @Test
    fun `useStream throws IOException and closes resources on non-success HTTP status`() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
            val url = mockWebServer.url("/missing.mp4").toString()

            try {
                MediaStreamResolver.useStream(context, url, "video/mp4") { _, _ -> }
                fail("Expected IOException on 404")
            } catch (e: IOException) {
                assertTrue(e.message?.contains("HTTP 404") == true)
            }
        }

    // ── 7. Real Copy Cancellation Tests ─────────────────────────────────────

    @Test
    fun `copyChunked cancels during copy and rethrows CancellationException`() =
        runTest {
            var chunksRead = 0
            var job: kotlinx.coroutines.Job? = null
            val slowInputStream =
                object : InputStream() {
                    override fun read(): Int = 42

                    override fun read(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int {
                        chunksRead++
                        if (chunksRead == 3) {
                            job?.cancel()
                        }
                        val toRead = minOf(len, 1024)
                        b.fill(1, off, off + toRead)
                        return toRead
                    }
                }

            val outStream = ByteArrayOutputStream()
            var caughtCancellation: CancellationException? = null

            job =
                launch {
                    try {
                        GatewayFileClient.copyChunked(slowInputStream, outStream)
                    } catch (e: CancellationException) {
                        caughtCancellation = e
                        throw e
                    }
                }

            job.join()

            assertNotNull(caughtCancellation)
            assertTrue(outStream.size() > 0)
        }

    // ── 8. Finalization & Cleanup Failure Tests ──────────────────────────────

    @Test
    fun `saveMediaToUri cleans up partial target if stream close throws`() =
        runTest {
            val sourceFile = tempFolder.newFile("source.mp3").also { it.writeText("sample-data") }
            val targetUri = mockk<Uri>(relaxed = true)

            val throwingStream =
                object : OutputStream() {
                    override fun write(b: Int) {}

                    override fun close(): Unit = throw IOException("Disk flush failure on close")
                }

            val mockResolver = mockk<ContentResolver>(relaxed = true)
            every { mockResolver.openOutputStream(targetUri) } returns throwingStream
            every { context.contentResolver } returns mockResolver

            try {
                MediaExportHelper.saveMediaToUri(context, sourceFile.absolutePath, targetUri, "audio/mpeg")
                fail("Expected IOException")
            } catch (_: IOException) {
                verify { mockResolver.delete(targetUri, null, null) }
            }
        }

    @Test
    fun `saveMediaToDownloads deletes item if MediaStore update returns zero`() =
        runTest {
            val mockValues = mockk<ContentValues>(relaxed = true)
            val sourceFile = tempFolder.newFile("track.wav").also { it.writeText("riff-data") }
            val insertedUri = mockk<Uri>(relaxed = true)

            val mockResolver = mockk<ContentResolver>(relaxed = true)
            every { mockResolver.insert(any(), any()) } returns insertedUri
            every { mockResolver.openOutputStream(insertedUri) } returns ByteArrayOutputStream()
            // Simulate update failure (row could not be published)
            every { mockResolver.update(insertedUri, any(), null, null) } returns 0
            every { context.contentResolver } returns mockResolver

            try {
                MediaExportHelper.saveMediaToDownloads(
                    context = context,
                    uri = sourceFile.absolutePath,
                    fallbackMime = "audio/wav",
                    currentSdk = 29,
                    valuesFactory = { _, _, _ -> mockValues },
                )
                fail("Expected IOException on publication failure")
            } catch (_: IOException) {
                verify { mockResolver.delete(insertedUri, null, null) }
            }
        }

    @Test
    fun `saveMediaToDownloads throws UnsupportedOperationException on API below 29`() =
        runTest {
            val sourceFile = tempFolder.newFile("track28.wav").also { it.writeText("riff-data") }
            try {
                MediaExportHelper.saveMediaToDownloads(
                    context = context,
                    uri = sourceFile.absolutePath,
                    fallbackMime = "audio/wav",
                    currentSdk = 28,
                )
                fail("Expected UnsupportedOperationException on API 28")
            } catch (e: UnsupportedOperationException) {
                assertTrue(e.message?.contains("API 29+") == true)
            }
        }

    // ── 9. Share Cache Collisions & TTL Sweep Tests ──────────────────────────

    @Test
    fun `shareMedia generates collision-proof distinct paths for identical filenames`() =
        runTest {
            val cacheDir = tempFolder.newFolder("cache")
            every { context.cacheDir } returns cacheDir
            every { context.packageName } returns "com.m57.hermescontrol"

            val sourceFile = tempFolder.newFile("song.mp3").also { it.writeText("audio-data") }
            val fakeUri = mockk<Uri>(relaxed = true)
            val fakeIntent = mockk<Intent>(relaxed = true)

            // First share
            val intent1 =
                MediaExportHelper.shareMedia(
                    context = context,
                    uri = sourceFile.absolutePath,
                    fallbackMime = "audio/mpeg",
                    displayName = "song.mp3",
                    fileUriProvider = { _, _ -> fakeUri },
                    intentFactory = { _, _ -> fakeIntent },
                )
            assertNotNull(intent1)
            val sharedDir = File(cacheDir, "shared_media")
            val sessionDirs = sharedDir.listFiles { f -> f.isDirectory } ?: emptyArray()
            assertEquals(1, sessionDirs.size)
            val file1 = File(sessionDirs[0], "song.mp3")
            assertTrue(file1.exists())

            // Second share of identical filename
            val intent2 =
                MediaExportHelper.shareMedia(
                    context = context,
                    uri = sourceFile.absolutePath,
                    fallbackMime = "audio/mpeg",
                    displayName = "song.mp3",
                    fileUriProvider = { _, _ -> fakeUri },
                    intentFactory = { _, _ -> fakeIntent },
                )
            assertNotNull(intent2)
            val sessionDirsAfter = sharedDir.listFiles { f -> f.isDirectory } ?: emptyArray()
            assertEquals(2, sessionDirsAfter.size)
            assertNotEquals(sessionDirsAfter[0].absolutePath, sessionDirsAfter[1].absolutePath)
        }

    @Test
    fun `sweepOldShareFiles deletes expired sessions but preserves fresh ones`() {
        val sharedDir = tempFolder.newFolder("shared_media")
        val now = 100_000_000L
        val ttl = 24 * 60 * 60 * 1000L

        val oldDir = File(sharedDir, "old-session").also { it.mkdirs() }
        File(oldDir, "old.mp4").writeText("old")
        oldDir.setLastModified(now - ttl - 5000L)

        val freshDir = File(sharedDir, "fresh-session").also { it.mkdirs() }
        File(freshDir, "fresh.mp4").writeText("fresh")
        freshDir.setLastModified(now - 1000L)

        MediaExportHelper.sweepOldShareFiles(sharedDir, now)

        assertFalse(oldDir.exists())
        assertTrue(freshDir.exists())
        assertTrue(File(freshDir, "fresh.mp4").exists())
    }

    // ── 10. Complete Auth Matrix Tests ───────────────────────────────────────

    private fun testAuthInterceptor(
        baseUrl: String,
        token: String?,
        isGated: Boolean,
        requestUrl: String,
        existingHeader: String? = null,
    ): String? {
        val interceptor =
            MediaDataSourceProvider.SameOriginAuthInterceptor(
                baseUrlProvider = { baseUrl },
                tokenProvider = { token },
                isGatedModeProvider = { isGated },
            )
        var executedRequest: Request? = null
        val chain =
            mockk<Interceptor.Chain> {
                val reqBuilder = Request.Builder().url(requestUrl)
                if (existingHeader != null) {
                    reqBuilder.header("Authorization", existingHeader)
                }
                every { request() } returns reqBuilder.build()
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
        return executedRequest?.header("Authorization")
    }

    @Test
    fun `SameOriginAuthInterceptor satisfies complete authorization matrix`() {
        // 1. gated mode, same origin -> no Bearer
        assertNull(
            testAuthInterceptor(
                baseUrl = "http://192.168.1.50:9119/",
                token = "secret",
                isGated = true,
                requestUrl = "http://192.168.1.50:9119/api/files/stream?path=audio.mp3",
            ),
        )

        // 2. gated mode, external origin -> no Bearer
        assertNull(
            testAuthInterceptor(
                baseUrl = "http://192.168.1.50:9119/",
                token = "secret",
                isGated = true,
                requestUrl = "https://external.com/audio.mp3",
            ),
        )

        // 3. token mode, same scheme/host/port, token set -> Bearer added
        assertEquals(
            "Bearer valid-token",
            testAuthInterceptor(
                baseUrl = "http://192.168.1.50:9119/",
                token = "valid-token",
                isGated = false,
                requestUrl = "http://192.168.1.50:9119/api/files/download?path=video.mp4",
            ),
        )

        // 4. token mode, token blank -> no Bearer
        assertNull(
            testAuthInterceptor(
                baseUrl = "http://192.168.1.50:9119/",
                token = "   ",
                isGated = false,
                requestUrl = "http://192.168.1.50:9119/api/files/download?path=video.mp4",
            ),
        )

        // 5. different host -> no Bearer
        assertNull(
            testAuthInterceptor(
                baseUrl = "http://192.168.1.50:9119/",
                token = "valid-token",
                isGated = false,
                requestUrl = "http://192.168.1.99:9119/api/files/download?path=video.mp4",
            ),
        )

        // 6. same host but different port -> no Bearer
        assertNull(
            testAuthInterceptor(
                baseUrl = "http://192.168.1.50:9119/",
                token = "valid-token",
                isGated = false,
                requestUrl = "http://192.168.1.50:8080/api/files/download?path=video.mp4",
            ),
        )

        // 7. same host/port but different scheme (https vs http) -> no Bearer
        assertNull(
            testAuthInterceptor(
                baseUrl = "http://192.168.1.50:9119/",
                token = "valid-token",
                isGated = false,
                requestUrl = "https://192.168.1.50:9119/api/files/download?path=video.mp4",
            ),
        )

        // 8. request already contains Authorization -> preserve existing value
        assertEquals(
            "Basic credentials",
            testAuthInterceptor(
                baseUrl = "http://192.168.1.50:9119/",
                token = "valid-token",
                isGated = false,
                requestUrl = "http://192.168.1.50:9119/api/files/download?path=video.mp4",
                existingHeader = "Basic credentials",
            ),
        )

        // 9. base URL invalid -> no Bearer
        assertNull(
            testAuthInterceptor(
                baseUrl = "not-a-valid-url",
                token = "valid-token",
                isGated = false,
                requestUrl = "http://192.168.1.50:9119/api/files/download?path=video.mp4",
            ),
        )
    }
}
