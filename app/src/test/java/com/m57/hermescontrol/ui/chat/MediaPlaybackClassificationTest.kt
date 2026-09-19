package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlaybackClassificationTest {
    @Test
    fun `audio MIME wins over mp4 extension`() {
        val attachment = Attachment("content://media/123", "recording.mp4", "audio/mp4")
        assertTrue(attachment.isAudio)
        assertFalse(attachment.isVideo)
    }

    @Test
    fun `audio extensions route generic attachments into player`() {
        for (ext in listOf("mp3", "m4a", "wav", "ogg", "opus", "flac", "aac")) {
            assertTrue(Attachment("content://media/123", "recording.$ext", "application/octet-stream").isAudio)
        }
    }

    @Test
    fun `video signed URL ignores query and fragment`() {
        assertEquals(MediaKind.VIDEO, classifyMedia(uri = "https://example.org/clip.MP4?token=test#fragment"))
        assertEquals(MediaKind.FILE, classifyMedia(uri = "https://example.org/report.txt?note=clip.mp4"))
    }

    @Test
    fun `gateway encoded path classifies audio`() {
        assertEquals(MediaKind.AUDIO, classifyMedia(uri = "https://gateway/api/files/download?path=%2Ftmp%2Fa.mp3"))
    }

    @Test
    fun `media exports use media extensions not image fallback`() {
        assertEquals("mp3", extensionForMime("audio/mpeg"))
        assertEquals("m4a", extensionForMime("audio/mp4"))
        assertEquals("mp4", extensionForMime("video/mp4"))
        assertEquals("wav", extensionForMime("audio/x-wav"))
    }
}
