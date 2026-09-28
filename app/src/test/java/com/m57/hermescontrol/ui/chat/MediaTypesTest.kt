package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTypesTest {
    @Test
    fun `classifies known image extensions`() {
        assertEquals(MediaKind.IMAGE, mediaKindForPath("/tmp/foo.png"))
        assertEquals(MediaKind.IMAGE, mediaKindForPath("a/b.jpg"))
        assertEquals(MediaKind.IMAGE, mediaKindForPath("x.WEBP"))
    }

    @Test
    fun `classifies audio and video`() {
        assertEquals(MediaKind.AUDIO, mediaKindForPath("/tmp/clip.mp3"))
        assertEquals(MediaKind.VIDEO, mediaKindForPath("/tmp/mov.mkv"))
    }

    @Test
    fun `unknown extension falls back to file`() {
        assertEquals(MediaKind.FILE, mediaKindForPath("/tmp/notes.txt"))
        assertEquals(MediaKind.FILE, mediaKindForPath("/tmp/noext"))
    }

    @Test
    fun `mime lookup matches kind`() {
        assertEquals("image/png", mediaMimeForPath("/tmp/x.png"))
        assertEquals("audio/mpeg", mediaMimeForPath("/tmp/x.mp3"))
        assertEquals("application/octet-stream", mediaMimeForPath("/tmp/x.weird"))
    }

    @Test
    fun `extension for mime uses one table plus aliases`() {
        // Table entries, including params and case.
        assertEquals("png", extensionForMime("image/png"))
        assertEquals("png", extensionForMime(" IMAGE/PNG; charset=binary"))
        assertEquals("ogg", extensionForMime("audio/ogg; codecs=opus"))
        assertEquals("mkv", extensionForMime("video/x-matroska"))
        assertEquals("pdf", extensionForMime("application/pdf"))
        // Aliases win over first-table-match.
        assertEquals("jpg", extensionForMime("image/jpeg"))
        assertEquals("jpg", extensionForMime("image/jpg"))
        assertEquals("heic", extensionForMime("image/heif"))
        assertEquals("mp3", extensionForMime("audio/mp3"))
        assertEquals("m4a", extensionForMime("audio/x-m4a"))
        assertEquals("wav", extensionForMime("audio/wave"))
        assertEquals("flac", extensionForMime("application/x-flac"))
        assertEquals("ogg", extensionForMime("application/ogg"))
        // Family fallbacks.
        assertEquals("mp4", extensionForMime("video/unknown"))
        assertEquals("mp3", extensionForMime("audio/unknown"))
        assertEquals("img", extensionForMime("image/unknown"))
        assertEquals("bin", extensionForMime("application/unknown"))
    }

    @Test
    fun `name extraction handles urls and paths`() {
        assertEquals("report.pdf", mediaNameFromPath("https://gw/api/files/download?path=%2Ftmp%2Freport.pdf"))
        assertEquals("a.png", mediaNameFromPath("/home/u/a.png"))
    }
}
