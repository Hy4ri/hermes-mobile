package com.m57.hermescontrol.ui.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    @Test
    fun `blank input returns blank string`() {
        assertEquals("", SpeechText.stripMarkdownForSpeech(""))
        assertEquals("", SpeechText.stripMarkdownForSpeech("   "))
        assertEquals("", SpeechText.stripMarkdownForSpeech("\n\t\n  "))
    }

    @Test
    fun `plain text is preserved without changes`() {
        val input = "Hello world this is a test"
        val result = SpeechText.stripMarkdownForSpeech(input)
        assertEquals("Hello world this is a test", result.trim())
    }

    @Test
    fun `bold and italic markers are removed keeping prose`() {
        val input = "This is **bold** text and *italic* text and ***bold italic*** text"
        val result = SpeechText.stripMarkdownForSpeech(input)
        assertFalse(result.contains("*"))
        assertTrue(result.contains("bold"))
        assertTrue(result.contains("italic"))
        assertTrue(result.contains("bold italic"))
    }

    @Test
    fun `strikethrough markers are removed keeping inner text`() {
        val input = "This is ~~cancelled~~ completed"
        val result = SpeechText.stripMarkdownForSpeech(input)
        assertFalse(result.contains("~"))
        assertTrue(result.contains("cancelled"))
        assertTrue(result.contains("completed"))
    }

    @Test
    fun `headers keep title text without hash characters`() {
        val input =
            """
            # Header 1
            ## Header 2
            ### Header 3
            """.trimIndent()
        val result = SpeechText.stripMarkdownForSpeech(input)
        assertFalse(result.contains("#"))
        assertTrue(result.contains("Header 1"))
        assertTrue(result.contains("Header 2"))
        assertTrue(result.contains("Header 3"))
    }

    @Test
    fun `inline code keeps code content without backticks`() {
        val input = "Run `val my_value = 42` to configure"
        val result = SpeechText.stripMarkdownForSpeech(input)
        assertFalse(result.contains("`"))
        assertTrue(result.contains("val my_value = 42"))
        assertTrue(result.contains("my_value"))
        assertTrue(result.contains("to configure"))
        assertFalse(result.contains("  "))
    }

    @Test
    fun `fenced code block keeps code body and removes fences and language identifier`() {
        val input =
            """
            Here is some code:
            ```kotlin
            fun main() {
                println("Hello")
            }
            ```
            Done.
            """.trimIndent()
        val result = SpeechText.stripMarkdownForSpeech(input)
        assertFalse(result.contains("```"))
        assertFalse(result.contains("kotlin"))
        assertTrue(result.contains("fun main()"))
        assertTrue(result.contains("""println("Hello")"""))
        assertTrue(result.contains("Here is some code:"))
        assertTrue(result.contains("Done."))
    }

    @Test
    fun `fenced code block body retains underscores, generics, multiplication, and pipes`() {
        val input =
            """
            Here is complex code:
            ```typescript
            const a_b_c: List<T> = items.map(x => x * 2 | 0);
            ```
            Done...
            """.trimIndent()
        val result = SpeechText.stripMarkdownForSpeech(input)
        assertFalse(result.contains("```"))
        assertFalse(result.contains("typescript"))
        assertTrue(result.contains("a_b_c"))
        assertTrue(result.contains("<T>"))
        assertTrue(result.contains("* 2"))
        assertTrue(result.contains("| 0"))
        assertTrue(result.contains("Here is complex code:"))
        assertTrue(result.contains("Done..."))
    }

    @Test
    fun `markdown links keep label text and strip destination url`() {
        val input = "Check out [Hermes Documentation](https://hermes-agent.nousresearch.com/docs) for info"
        val result = SpeechText.stripMarkdownForSpeech(input)
        assertTrue(result.contains("Hermes Documentation"))
        assertFalse(result.contains("https://hermes-agent.nousresearch.com/docs"))
        assertFalse(result.contains("[") || result.contains("]"))
    }

    @Test
    fun `image markdown keeps alt text and strips image url`() {
        val input = "Here is a diagram: ![Architecture diagram](https://example.com/arch.png)"
        val result = SpeechText.stripMarkdownForSpeech(input)
        assertTrue(result.contains("Architecture diagram"))
        assertFalse(result.contains("https://example.com/arch.png"))
        assertFalse(result.contains("!["))
    }

    @Test
    fun `bare urls are removed`() {
        val input = "Visit https://example.com or http://test.org/path?arg=1 today"
        val result = SpeechText.stripMarkdownForSpeech(input)
        assertFalse(result.contains("https://example.com"))
        assertFalse(result.contains("http://test.org/path?arg=1"))
        assertTrue(result.contains("Visit"))
        assertTrue(result.contains("today"))
    }

    @Test
    fun `html tags are stripped keeping inner text`() {
        val input = "<p>This is a <b>bold</b> paragraph with a <br/> break.</p>"
        val result = SpeechText.stripMarkdownForSpeech(input)
        assertFalse(result.contains("<"))
        assertFalse(result.contains(">"))
        assertTrue(result.contains("This is a"))
        assertTrue(result.contains("bold"))
        assertTrue(result.contains("paragraph with a"))
        assertTrue(result.contains("break."))
    }

    @Test
    fun `blockquote markers are removed keeping content`() {
        val input =
            """
            > This is a quote.
            > It spans multiple lines.
            """.trimIndent()
        val result = SpeechText.stripMarkdownForSpeech(input)
        assertFalse(result.contains(">"))
        assertTrue(result.contains("This is a quote."))
        assertTrue(result.contains("It spans multiple lines."))
    }

    @Test
    fun `unordered and ordered list markers are handled sensibly`() {
        val input =
            """
            Items:
            * First item
            * Second item
            1. Step one
            2. Step two
            """.trimIndent()
        val result = SpeechText.stripMarkdownForSpeech(input)
        assertFalse(result.contains("*"))
        assertFalse(result.contains("1."))
        assertFalse(result.contains("2."))
        assertTrue(result.contains("First item"))
        assertTrue(result.contains("Second item"))
        assertTrue(result.contains("Step one"))
        assertTrue(result.contains("Step two"))
    }

    @Test
    fun `arabic text is preserved intact`() {
        val input = "مرحبا بك في تطبيق هيرمس! **أهلا وسهلا**"
        val result = SpeechText.stripMarkdownForSpeech(input)
        assertFalse(result.contains("*"))
        assertTrue(result.contains("مرحبا بك في تطبيق هيرمس!"))
        assertTrue(result.contains("أهلا وسهلا"))
    }

    @Test
    fun `unicode and emoji are preserved`() {
        val input = "Hello 👋 world! 🚀 Enjoy the day ☀️"
        val result = SpeechText.stripMarkdownForSpeech(input)
        assertTrue(result.contains("👋"))
        assertTrue(result.contains("🚀"))
        assertTrue(result.contains("☀️"))
        assertTrue(result.contains("Hello"))
        assertTrue(result.contains("world!"))
    }

    @Test
    fun `punctuation and code punctuation edge cases are handled without double punctuation`() {
        val input = "Wait... is that it?! Yes, it is: done."
        val result = SpeechText.stripMarkdownForSpeech(input)
        assertTrue(result.contains("Wait..."))
        assertTrue(result.contains("done."))
        assertFalse(result.contains("..,"))
        assertFalse(result.contains(",,"))
    }

    @Test
    fun `newlines retain punctuation without introducing duplicate or mixed punctuation and normalize whitespace`() {
        val input =
            """
            Done.
            Next!
            مرحبا؟
            Again
            """.trimIndent()
        val result = SpeechText.stripMarkdownForSpeech(input)
        assertFalse(result.contains(". ."))
        assertFalse(result.contains("!."))
        assertFalse(result.contains("؟."))
        assertFalse(result.contains("\n\n"))
        assertTrue(result.contains("Done."))
        assertTrue(result.contains("Next!"))
        assertTrue(result.contains("مرحبا؟"))
        assertTrue(result.contains("Again"))
    }

    @Test
    fun `horizontal rules are removed`() {
        val input =
            """
            Section 1
            ---
            Section 2
            ***
            Section 3
            """.trimIndent()
        val result = SpeechText.stripMarkdownForSpeech(input)
        assertTrue(result.contains("Section 1"))
        assertTrue(result.contains("Section 2"))
        assertTrue(result.contains("Section 3"))
        assertFalse(result.contains("---"))
        assertFalse(result.contains("***"))
    }
}
