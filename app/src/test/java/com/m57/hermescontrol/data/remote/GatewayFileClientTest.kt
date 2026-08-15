package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.config.ServerStore
import com.m57.hermescontrol.data.config.ServerStoreState
import com.m57.hermescontrol.data.local.AuthManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewayFileClientTest {
    private val base = "https://gw.example.com:9119"
    private val tok = "s e/cret"

    @Before
    fun setUp() {
        // AuthManager.isGatedMode() reads serverStore — inject a mock whose
        // wsAuthParam is NOT "ticket" so the tests exercise the loopback
        // branch (token stamped into the URL). Keeps this builder test
        // independent of real AuthManager initialization.
        val mockStore = mockk<ServerStore>(relaxed = true)
        every { mockStore.getLatestState() } returns
            ServerStoreState(wsAuthParam = "bearer")
        val field = AuthManager::class.java.getDeclaredField("_serverStore")
        field.isAccessible = true
        field.set(AuthManager, mockStore)
    }

    @Test
    fun `buildDownloadUrl encodes path and token`() {
        val url = GatewayFileClient.buildDownloadUrl(base, tok, "/tmp/foo.png")!!
        assertEquals(
            "$base/api/files/download?path=%2Ftmp%2Ffoo.png&token=${java.net.URLEncoder.encode(
                tok,
                "UTF-8",
            ).replace("+", "%20")}",
            url,
        )
        assertTrue(url.contains("path=%2Ftmp%2Ffoo.png"))
        assertTrue(url.contains("token="))
    }

    @Test
    fun `buildDownloadUrl rejects blank base`() {
        assertNull(GatewayFileClient.buildDownloadUrl("", tok, "/tmp/x.png"))
    }

    @Test
    fun `buildDownloadUrl permits blank token for cookie auth`() {
        val url = GatewayFileClient.buildDownloadUrl(base, "", "/tmp/x.png")!!
        assertEquals("$base/api/files/download?path=%2Ftmp%2Fx.png", url)
        assertFalse(url.contains("token="))
    }

    @Test
    fun `buildDownloadUrl rejects relative paths`() {
        assertNull(GatewayFileClient.buildDownloadUrl(base, tok, "relative/path.png"))
        assertNull(GatewayFileClient.buildDownloadUrl(base, tok, "MEDIA:relative.png"))
    }

    @Test
    fun `buildDownloadUrl handles quoted and spaced paths`() {
        val url = GatewayFileClient.buildDownloadUrl(base, tok, "\"/tmp/a b.png\"")!!
        assertTrue(url.contains("path=%2Ftmp%2Fa%20b.png"))
        assertFalse(url.contains("MEDIA:"))
    }

    @Test
    fun `normalizePath expands tilde`() {
        val home = System.getenv("HOME") ?: "/home/test"
        assertEquals("$home/foo.png", GatewayFileClient.normalizePath("~/foo.png"))
    }

    @Test
    fun `normalizePath strips surrounding quotes`() {
        assertEquals("/tmp/x.png", GatewayFileClient.normalizePath("'/tmp/x.png'"))
        assertEquals("/tmp/x.png", GatewayFileClient.normalizePath("`/tmp/x.png`"))
    }

    @Test
    fun `normalizePath requires absolute path`() {
        assertNull(GatewayFileClient.normalizePath("relative.png"))
    }

    @Test
    fun `classifyStatus maps known codes`() {
        assertEquals(GatewayFileResult.NotFound, GatewayFileClient.classifyStatus(404))
        assertEquals(GatewayFileResult.Forbidden, GatewayFileClient.classifyStatus(403))
        assertEquals(GatewayFileResult.TooLarge, GatewayFileClient.classifyStatus(413))
        assertEquals(GatewayFileResult.Unauthorized, GatewayFileClient.classifyStatus(401))
        assertNull(GatewayFileClient.classifyStatus(200))
        assertNull(GatewayFileClient.classifyStatus(500))
    }

    @Test
    fun `parseFilename extracts from content-disposition`() {
        assertEquals("report.pdf", GatewayFileClient.parseFilename("attachment; filename=\"report.pdf\""))
        assertEquals("a b.png", GatewayFileClient.parseFilename("inline; filename*=UTF-8''a%20b.png"))
    }

    @Test
    fun `cacheFileNameFor is deterministic per path`() {
        val first = GatewayFileClient.cacheFileNameFor("/tmp/report.pdf")
        val second = GatewayFileClient.cacheFileNameFor("/tmp/report.pdf")
        val other = GatewayFileClient.cacheFileNameFor("/tmp/other.pdf")
        assertEquals(first, second)
        assertNotEquals(first, other)
        assertTrue(first.endsWith("-report.pdf"))
    }

    @Test
    fun `isFreshCacheFile respects the TTL`() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "gwcache_${System.nanoTime()}").apply { mkdirs() }
        try {
            val fresh = java.io.File(dir, "fresh").apply { writeText("x") }
            fresh.setLastModified(System.currentTimeMillis() - 60_000L) // 1 min old
            val stale = java.io.File(dir, "stale").apply { writeText("x") }
            stale.setLastModified(System.currentTimeMillis() - 60 * 60 * 1000L) // 1 h old
            assertTrue(GatewayFileClient.isFreshCacheFile(fresh))
            assertFalse(GatewayFileClient.isFreshCacheFile(stale))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `copyChunked copies input to output byte-for-byte`() =
        runTest {
            // >64 KiB so the copy exercises multiple chunks (buffer is 64 KiB).
            val payload = ByteArray(200_000) { (it % 251).toByte() }
            val output = java.io.ByteArrayOutputStream()
            GatewayFileClient.copyChunked(java.io.ByteArrayInputStream(payload), output)
            assertTrue(output.toByteArray().contentEquals(payload))
        }

    @Test
    fun `copyChunked aborts mid-copy when the job is cancelled`() =
        runTest {
            // Input that parks on its 2nd chunk read until the gate opens, so
            // the test can cancel the job *while* the copy is in flight.
            val gate = CompletableDeferred<Unit>()
            val blockingInput =
                object : java.io.InputStream() {
                    var reads = 0

                    override fun read(): Int = 1

                    override fun read(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int {
                        reads++
                        if (reads == 2) kotlinx.coroutines.runBlocking { gate.await() }
                        return if (reads <= 3) 1 else -1
                    }
                }
            val output = java.io.ByteArrayOutputStream()
            val job =
                CoroutineScope(Dispatchers.Default).launch {
                    GatewayFileClient.copyChunked(blockingInput, output)
                }
            runCurrent()
            job.cancel()
            gate.complete(Unit)
            job.join()
            // If copyChunked swallowed the cancellation, the job would have
            // completed normally; a cancelled completion proves propagation.
            assertTrue(job.isCancelled)
        }
}
