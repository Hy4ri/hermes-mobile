package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUrlRewriterTest {
    private val base = "https://gw.example.com:9119"
    private val tok = "s e/cret"

    @Test
    fun `rewrites absolute image path to gateway download url`() {
        val text = "Here is the result: MEDIA:/tmp/foo.png"
        val out = MediaUrlRewriter.rewriteMediaToGatewayUrls(text, base, tok)
        val expected =
            "Here is the result: ![image]($base/api/files/download?" +
                "path=%2Ftmp%2Ffoo.png&token=${java.net.URLEncoder.encode(tok, "UTF-8")})"
        assertEquals(expected, out)
        assertTrue(out.contains("![image]($base/api/files/download?path=%2Ftmp%2Ffoo.png&token="))
    }

    @Test
    fun `leaves non-media text unchanged`() {
        val text = "just some normal text with no image"
        assertEquals(text, MediaUrlRewriter.rewriteMediaToGatewayUrls(text, base, tok))
    }

    @Test
    fun `does not rewrite relative paths`() {
        val text = "MEDIA:relative/path.png"
        assertEquals(text, MediaUrlRewriter.rewriteMediaToGatewayUrls(text, base, tok))
    }

    @Test
    fun `handles quoted path`() {
        val text = "MEDIA:\"/tmp/a b.png\""
        val out = MediaUrlRewriter.rewriteMediaToGatewayUrls(text, base, tok)
        assertTrue(out.startsWith("![image]($base/api/files/download?path=%2Ftmp%2Fa%20b.png&token="))
        assertFalse(out.contains("MEDIA:"))
    }

    @Test
    fun `rewrites url-encoded path correctly`() {
        val text = "MEDIA:/home/user/my img.jpg"
        val out = MediaUrlRewriter.rewriteMediaToGatewayUrls(text, base, tok)
        assertTrue(out.contains("path=%2Fhome%2Fuser%2Fmy%20img.jpg"))
        assertTrue(out.contains("![image]"))
    }
}
