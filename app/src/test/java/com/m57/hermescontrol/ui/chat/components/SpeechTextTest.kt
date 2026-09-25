package com.m57.hermescontrol.ui.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    @Test
    fun stripMarkdown_removesFormattingButKeepsWords() {
        val stripped =
            SpeechText.stripMarkdownForSpeech(
                "# Title\n\nHello **bold** and *italic* with `code`.\n\n- item one\n- item two",
            )
        assertTrue(stripped.contains("Title"))
        assertTrue(stripped.contains("Hello bold and italic with code."))
        assertTrue(stripped.contains("item one"))
        assertTrue(!stripped.contains("#"))
        assertTrue(!stripped.contains("**"))
        assertTrue(!stripped.contains("`"))
    }

    @Test
    fun stripMarkdown_linksKeepTextDropTargets() {
        val stripped =
            SpeechText.stripMarkdownForSpeech(
                "See [the docs](https://example.com/x) and ![logo](https://example.com/l.png).",
            )
        assertTrue(stripped.contains("the docs"))
        assertTrue(stripped.contains("logo"))
        assertTrue(!stripped.contains("https://"))
    }

    @Test
    fun stripMarkdown_codeFencesKeepCode() {
        val stripped =
            SpeechText.stripMarkdownForSpeech("```kotlin\nval x = 1\n```\nDone.")
        assertTrue(stripped.contains("val x = 1"))
        assertTrue(!stripped.contains("```"))
        assertTrue(stripped.contains("Done."))
    }

    @Test
    fun stripMarkdown_blankForEmpty() {
        assertEquals("", SpeechText.stripMarkdownForSpeech(""))
        assertEquals("", SpeechText.stripMarkdownForSpeech("   "))
    }

    @Test
    fun splitForSpeech_shortTextIsSingleChunk() {
        assertEquals(listOf("hello"), SpeechText.splitForSpeech("hello"))
    }

    @Test
    fun splitForSpeech_longTextSplitsWithinLimit() {
        val sentence = "This is a spoken sentence. "
        val long = sentence.repeat(400)
        val chunks = SpeechText.splitForSpeech(long)
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue(it.length <= SpeechText.MAX_CHUNK_LENGTH) }
        assertEquals(
            long.replace(Regex("\\s+"), " ").trim(),
            chunks.joinToString(" ").replace(Regex("\\s+"), " ").trim(),
        )
    }
}
