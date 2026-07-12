package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopMediaTest {
    @Test
    fun `parses Windows MEDIA image path`() {
        val media = DesktopMedia.parse("MEDIA:C:\\Users\\example\\Downloads\\photo.png")

        assertEquals("photo.png", media?.fileName)
        assertTrue(media?.isImage == true)
    }

    @Test
    fun `parses non-image media without treating normal text as media`() {
        val video = DesktopMedia.parse("  MEDIA:C:\\Users\\example\\Videos\\clip.mp4  ")

        assertEquals("clip.mp4", video?.fileName)
        assertFalse(video?.isImage ?: true)
        assertNull(DesktopMedia.parse("The text mentions MEDIA: but is not a directive"))
    }
}
