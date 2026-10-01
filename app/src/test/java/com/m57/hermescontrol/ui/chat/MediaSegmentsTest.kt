package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSegmentsTest {
    private fun att(
        name: String,
        offset: Int?,
    ) = Attachment(uri = "u/$name", name = name, mimeType = "image/png", contentOffset = offset)

    private fun kinds(segments: List<MessageSegment>) =
        segments.map {
            when (it) {
                is MessageSegment.Text -> "T:${it.text}"
                is MessageSegment.Media -> "M:${it.attachment.name}"
            }
        }

    @Test
    fun extractRecordsOffsetsIntoStrippedText() {
        val text = "First: MEDIA:/a/1.png\nthen second: MEDIA:/a/2.png\nend"
        val stripped = HostMediaExtractor.strip(text)
        val items = HostMediaExtractor.extract(text)
        assertEquals(2, items.size)
        assertTrue(items[0].offset <= items[1].offset)
        assertTrue(items[1].offset <= stripped.length)
    }

    @Test
    fun mediaLandsBetweenTheParagraphsItSatBetween() {
        val text = "Chart one:\nMEDIA:/a/1.png\nChart two:\nMEDIA:/a/2.png\nDone."
        val stripped = HostMediaExtractor.strip(text)
        val items = HostMediaExtractor.extract(text)
        val atts = items.map { att(it.path.substringAfterLast('/'), it.offset) }
        val out = kinds(splitByMedia(stripped, atts))
        assertEquals(listOf("T:Chart one:", "M:1.png", "T:Chart two:", "M:2.png", "T:Done."), out)
    }

    @Test
    fun noOffsetAppendsAfterText() {
        val out = kinds(splitByMedia("Hello", listOf(att("x.png", null))))
        assertEquals(listOf("T:Hello", "M:x.png"), out)
    }

    @Test
    fun mediaOnlyMessageHasNoBlankText() {
        val out = kinds(splitByMedia("", listOf(att("x.png", 0))))
        assertEquals(listOf("M:x.png"), out)
    }

    @Test
    fun mediaDoesNotSplitAFencedCodeBlock() {
        val content = "Look:\n```\ncode line\nmore\n```\nafter"
        val out = kinds(splitByMedia(content, listOf(att("x.png", content.indexOf("code line")))))
        assertEquals(2, out.count { it.startsWith("T:") })
        assertTrue(out[0].contains("more") && out[0].endsWith("```"))
        assertEquals("M:x.png", out[1])
    }
}
