package com.m57.hermescontrol.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayFileClientTest {
    private val base = "https://gw.example.com:9119"
    private val tok = "s e/cret"

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
}
