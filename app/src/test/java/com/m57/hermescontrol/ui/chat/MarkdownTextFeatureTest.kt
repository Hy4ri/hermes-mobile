package com.m57.hermescontrol.ui.chat

import androidx.compose.ui.graphics.Color
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.StatusBlue
import com.m57.hermescontrol.theme.StatusBlueContainer
import com.m57.hermescontrol.theme.StatusGreen
import com.m57.hermescontrol.theme.StatusGreenContainer
import com.m57.hermescontrol.theme.StatusRed
import com.m57.hermescontrol.theme.StatusRedContainer
import com.m57.hermescontrol.theme.StatusYellow
import com.m57.hermescontrol.theme.StatusYellowContainer
import com.m57.hermescontrol.theme.searchHighlightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val DEFAULT_HIGHLIGHTS =
    searchHighlightColors(
        HermesStatusColors(
            success = StatusGreen,
            successContainer = StatusGreenContainer,
            onSuccess = Color.White,
            warning = StatusYellow,
            warningContainer = StatusYellowContainer,
            onWarning = Color.White,
            error = StatusRed,
            errorContainer = StatusRedContainer,
            onError = Color.White,
            info = StatusBlue,
            infoContainer = StatusBlueContainer,
            onInfo = Color.White,
        ),
    )

/**
 * Verifies the hand-rolled Markdown parser covers the feature set requested for issue #572.
 * Each test asserts the block/inline structure actually parses —
 * not just that it compiles. Inline assertions avoid referencing Compose text types (FontWeight,
 * BaselineShift, etc.) that aren't on the unit-test classpath; we inspect SpanStyle fields
 * structurally instead.
 */
class MarkdownTextFeatureTest {
    // 1. TABLES
    @Test
    fun testTable_parsesHeaderAndRows() {
        val md =
            """
            | Name | Age | Role |
            |------|:---:|-----:|
            | Alice | 30 | dev |
            | Bob | 25 | ops |
            """.trimIndent()
        val blocks = parseBlocks(md)
        val table = blocks.singleOrNull { it is MdBlock.Table } as MdBlock.Table?
        assertTrue("table block expected", table != null)
        assertEquals(listOf("Name", "Age", "Role"), table!!.header)
        assertEquals(2, table.rows.size)
        assertEquals("Alice", table.rows[0][0])
        assertEquals("ops", table.rows[1][2])
        // alignment inference: center, left, right
        assertEquals(TableAlign.CENTER, table.alignments[1])
        assertEquals(TableAlign.RIGHT, table.alignments[2])
    }

    // 2. MATH
    @Test
    fun testDisplayMath_parsesToMathBlock() {
        val block = parseBlocks("\$\$\\frac{1}{2}\$\$").single()

        assertEquals("Math", block::class.simpleName)
    }

    @Test
    fun testInlineMath_stripsDelimitersFromFallbackText() {
        val parsed = parseInline("Energy \$E=mc^2\$.", Color.Black, "", false, Color.Blue, DEFAULT_HIGHLIGHTS)

        assertEquals("Energy E=mc^2.", parsed.toString())
    }

    @Test
    fun testBracketDisplayMath_parsesToMathBlock() {
        val block = parseBlocks("\\[\n\\frac{1}{2}\n\\]").single()

        assertEquals("Math", block::class.simpleName)
    }

    @Test
    fun testParenthesizedInlineMath_stripsDelimitersFromFallbackText() {
        val parsed = parseInline("Energy \\(E=mc^2\\).", Color.Black, "", false, Color.Blue, DEFAULT_HIGHLIGHTS)

        assertEquals("Energy E=mc^2.", parsed.toString())
    }

    // 3. STRIKETHROUGH
    @Test
    fun testStrikethrough_parses() {
        val an = parseInline("~~gone~~", Color.Black, "", false, Color.Blue, DEFAULT_HIGHLIGHTS)
        assertEquals("gone", an.toString())
        assertTrue(an.spanStyles.any { it.item.textDecoration != null })
    }

    // 4. BOLD + ITALIC in same word (***bolditalic***)
    @Test
    fun testBoldItalic_combined() {
        val an = parseInline("***both***", Color.Black, "", false, Color.Blue, DEFAULT_HIGHLIGHTS)
        assertEquals("both", an.toString())
        assertTrue(an.spanStyles.isNotEmpty())
    }

    // 5. TASK LIST / CHECKBOX
    @Test
    fun testTaskList_parsesCheckedAndUnchecked() {
        val md =
            """
            - [x] done
            - [ ] todo
            """.trimIndent()
        val blocks = parseBlocks(md)
        assertEquals(2, blocks.size)
        val done = blocks[0] as MdBlock.Task
        val todo = blocks[1] as MdBlock.Task
        assertTrue(done.checked)
        assertFalse(todo.checked)
        assertEquals("done", done.text)
        assertEquals("todo", todo.text)
    }

    // 6. HORIZONTAL RULE (---)
    @Test
    fun testHorizontalRule_parses() {
        val md =
            """
            above

            ---

            below
            """.trimIndent()
        val blocks = parseBlocks(md)
        assertTrue(blocks.any { it is MdBlock.Hr })
    }

    // 7. FOOTNOTES
    @Test
    fun testFootnotes_collectedAndRendered() {
        val md =
            """
            Science is cool.[^1]

            [^1]: A famous claim.
            """.trimIndent()
        val blocks = parseBlocks(md)
        val fn = blocks.singleOrNull { it is MdBlock.Footnotes } as MdBlock.Footnotes?
        assertTrue(fn != null)
        assertEquals("1", fn!!.notes[0].id)
        assertEquals("A famous claim.", fn.notes[0].text)
    }

    // 8. DEFINITION LIST
    @Test
    fun testDefinitionList_parses() {
        val md =
            """
            Term
            : first definition
            : second definition
            """.trimIndent()
        val dl = parseBlocks(md).singleOrNull { it is MdBlock.DefList } as MdBlock.DefList?
        assertTrue(dl != null)
        assertEquals("Term", dl!!.items[0].term)
        assertEquals(2, dl.items[0].definitions.size)
        assertEquals("first definition", dl.items[0].definitions[0])
    }

    // 9. HIGHLIGHT
    @Test
    fun testHighlight_parses() {
        val an = parseInline("==mark==", Color.Black, "", false, Color.Blue, DEFAULT_HIGHLIGHTS)
        assertEquals("mark", an.toString())
        assertTrue(an.spanStyles.any { it.item.background != Color.Unspecified })
    }

    // 10. SUPERSCRIPT / SUBSCRIPT
    @Test
    fun testSuperscriptAndSubscript_parse() {
        val sup = parseInline("x^2^", Color.Black, "", false, Color.Blue, DEFAULT_HIGHLIGHTS)
        assertTrue(sup.spanStyles.any { it.item.baselineShift != null })
        val sub = parseInline("H~2~O", Color.Black, "", false, Color.Blue, DEFAULT_HIGHLIGHTS)
        assertTrue(sub.spanStyles.any { it.item.baselineShift != null })
    }

    // 11. KEYBOARD KEYS <kbd>
    @Test
    fun testKbd_parses() {
        val an =
            parseInline("Press <kbd>Ctrl</kbd>+<kbd>C</kbd>", Color.Black, "", false, Color.Blue, DEFAULT_HIGHLIGHTS)
        assertTrue(an.toString().contains("Ctrl"))
        assertTrue(an.toString().contains("C"))
        assertTrue(an.spanStyles.any { it.item.fontFamily != null })
    }

    // --- regression: issue ref "#572" must NOT become a heading ---
    @Test
    fun testHashRef_notHeading() {
        val blocks = parseBlocks("#572 should stay text")
        assertTrue(blocks.single() is MdBlock.Paragraph)
    }

    // --- regression: streaming gate handled in composable, parser must not split inline code ---
    @Test
    fun testInlineCode_preserved() {
        val an = parseInline("use `val x = 1` here", Color.Black, "", false, Color.Blue, DEFAULT_HIGHLIGHTS)
        assertEquals("use val x = 1 here", an.toString())
        assertTrue(an.spanStyles.any { it.item.fontFamily != null })
    }

    // 12. STANDALONE MARKDOWN IMAGE -> MdBlock.Image (agent-media rendering)
    @Test
    fun testStandaloneImage_parsesToImageBlock() {
        val md = "![cute cat](https://example.com/cat.jpg)"
        val block = parseBlocks(md).singleOrNull() as? MdBlock.Image
        assertTrue("expected a single Image block", block != null)
        assertEquals("https://example.com/cat.jpg", block!!.uri)
        assertEquals("cute cat", block.alt)
    }

    // 12b. data: URL (base64 inline media) also accepted as an image uri
    @Test
    fun testImage_dataUrl_accepted() {
        val uri = "data:image/jpeg;base64,/9j/abc"
        val md = "![pic]($uri)"
        val block = parseBlocks(md).singleOrNull() as? MdBlock.Image
        assertTrue("data: URL should parse to Image block", block != null)
        assertEquals(uri, block!!.uri)
    }

    // 12c. inline image inside a paragraph is left as text (scope guard)
    @Test
    fun testInlineImage_inParagraph_staysText() {
        val md = "see this ![cat](https://x/cat.png) for reference"
        val blocks = parseBlocks(md)
        assertTrue("should remain a paragraph", blocks.single() is MdBlock.Paragraph)
    }

    // 13. Attachment isGif check (issue #721)
    @Test
    fun testAttachment_isGif() {
        val gifAttachment =
            com.m57.hermescontrol.data.model.Attachment(
                uri = "content://media/1.gif",
                name = "test.gif",
                mimeType = "image/gif",
            )
        assertTrue(gifAttachment.isImage)
        assertTrue(gifAttachment.isGif)

        val nonGifAttachment =
            com.m57.hermescontrol.data.model.Attachment(
                uri = "content://media/1.jpg",
                name = "test.jpg",
                mimeType = "image/jpeg",
            )
        assertTrue(nonGifAttachment.isImage)
        assertFalse(nonGifAttachment.isGif)
    }

    // 14. Standalone video parsing (issue #722)
    @Test
    fun testStandaloneVideo_parsesToVideoBlock() {
        val md = "![demo](https://example.com/demo.mp4)"
        val block = parseBlocks(md).singleOrNull() as? MdBlock.Video
        assertTrue("video URL should parse to Video block", block != null)
        assertEquals("https://example.com/demo.mp4", block!!.uri)
        assertEquals("demo", block.alt)
    }

    // 15. Attachment isVideo check (issue #722)
    @Test
    fun testAttachment_isVideo() {
        val videoAttachment =
            com.m57.hermescontrol.data.model.Attachment(
                uri = "content://media/1.mp4",
                name = "test.mp4",
                mimeType = "video/mp4",
            )
        assertTrue(videoAttachment.isVideo)
        assertFalse(videoAttachment.isImage)
    }

    // 16. ORDERED LIST — loose lists must preserve numbering (issue #959)
    @Test
    fun testOrderedList_looseListPreservesNumbers() {
        val md =
            """
            1. First

            2. Second

            3. Third
            """.trimIndent()
        val blocks = parseBlocks(md).filterIsInstance<MdBlock.Ordered>()
        assertEquals(3, blocks.size)
        assertEquals(1, blocks[0].index)
        assertEquals(2, blocks[1].index)
        assertEquals(3, blocks[2].index)
        assertEquals("First", blocks[0].text)
        assertEquals("Third", blocks[2].text)
    }

    // 16b. ORDERED LIST — custom starting number is preserved (issue #959)
    @Test
    fun testOrderedList_customStartNumberPreserved() {
        val md =
            """
            2. Two
            3. Three
            """.trimIndent()
        val blocks = parseBlocks(md).filterIsInstance<MdBlock.Ordered>()
        assertEquals(2, blocks.size)
        assertEquals(2, blocks[0].index)
        assertEquals(3, blocks[1].index)
    }

    // 16c. ORDERED LIST — tight list still numbers sequentially
    @Test
    fun testOrderedList_tightList() {
        val md =
            """
            1. one
            2. two
            3. three
            """.trimIndent()
        val blocks = parseBlocks(md).filterIsInstance<MdBlock.Ordered>()
        assertEquals(listOf(1, 2, 3), blocks.map { it.index })
    }

    // 17. NESTED LISTS (issue #965)
    @Test
    fun testNestedLists_mixedBulletsAndOrdered() {
        val md =
            """
            - top bullet A
              - sub bullet A1
                1. deep number 1
                2. deep number 2
                   - deeper bullet x
                3. deep number 3
              - sub bullet A2
            - top bullet B
              1. number under B
                 - bullet under that number
                   1. numbered deep
              2. second number under B

            1. one
               - a bullet
                 - nested bullet
               - b bullet
            2. two
            """.trimIndent()

        val blocks = parseBlocks(md)
        assertTrue("Blocks should not be empty", blocks.isNotEmpty())

        // Top bullet A
        val b0 = blocks[0] as MdBlock.Bullet
        assertEquals("top bullet A", b0.text)
        assertEquals(0, b0.level)

        // Sub bullet A1
        val b1 = blocks[1] as MdBlock.Bullet
        assertEquals("sub bullet A1", b1.text)
        assertEquals(1, b1.level)

        // Deep number 1 & 2
        val o2 = blocks[2] as MdBlock.Ordered
        assertEquals("deep number 1", o2.text)
        assertEquals(1, o2.index)
        assertEquals(2, o2.level)

        val o3 = blocks[3] as MdBlock.Ordered
        assertEquals("deep number 2", o3.text)
        assertEquals(2, o3.index)
        assertEquals(2, o3.level)

        // Deeper bullet x
        val b4 = blocks[4] as MdBlock.Bullet
        assertEquals("deeper bullet x", b4.text)
        assertEquals(3, b4.level)

        // Deep number 3
        val o5 = blocks[5] as MdBlock.Ordered
        assertEquals("deep number 3", o5.text)
        assertEquals(3, o5.index)
        assertEquals(2, o5.level)

        // Sub bullet A2
        val b6 = blocks[6] as MdBlock.Bullet
        assertEquals("sub bullet A2", b6.text)
        assertEquals(1, b6.level)

        // Top bullet B
        val b7 = blocks[7] as MdBlock.Bullet
        assertEquals("top bullet B", b7.text)
        assertEquals(0, b7.level)

        // Number under B
        val o8 = blocks[8] as MdBlock.Ordered
        assertEquals("number under B", o8.text)
        assertEquals(1, o8.index)
        assertEquals(1, o8.level)

        // Bullet under that number
        val b9 = blocks[9] as MdBlock.Bullet
        assertEquals("bullet under that number", b9.text)
        assertEquals(2, b9.level)

        // Numbered deep
        val o10 = blocks[10] as MdBlock.Ordered
        assertEquals("numbered deep", o10.text)
        assertEquals(1, o10.index)
        assertEquals(3, o10.level)

        // Second number under B
        val o11 = blocks[11] as MdBlock.Ordered
        assertEquals("second number under B", o11.text)
        assertEquals(2, o11.index)
        assertEquals(1, o11.level)

        // List 2: starting with 1. one
        val o12 = blocks[12] as MdBlock.Ordered
        assertEquals("one", o12.text)
        assertEquals(1, o12.index)
        assertEquals(0, o12.level)

        val b13 = blocks[13] as MdBlock.Bullet
        assertEquals("a bullet", b13.text)
        assertEquals(1, b13.level)

        val b14 = blocks[14] as MdBlock.Bullet
        assertEquals("nested bullet", b14.text)
        assertEquals(2, b14.level)

        val b15 = blocks[15] as MdBlock.Bullet
        assertEquals("b bullet", b15.text)
        assertEquals(1, b15.level)

        val o16 = blocks[16] as MdBlock.Ordered
        assertEquals("two", o16.text)
        assertEquals(2, o16.index)
        assertEquals(0, o16.level)
    }
}
