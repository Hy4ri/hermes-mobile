package com.m57.hermescontrol.ui.chat

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the hand-rolled Markdown parser covers the feature set requested for issue #572
 * (math excluded by decision). Each test asserts the block/inline structure actually parses —
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

    // 2. MATH — excluded; ensure a math-looking string stays plain, not crashed
    @Test
    fun testMath_isTreatedAsPlainText() {
        val md = "E = mc^2^ and `x = (-b ± √(b²-4ac)) / 2a`"
        val block = parseBlocks(md).single() as MdBlock.Paragraph
        assertEquals(md, block.text)
    }

    // 3. STRIKETHROUGH
    @Test
    fun testStrikethrough_parses() {
        val an = parseInline("~~gone~~", Color.Black, "", false, Color.Blue)
        assertEquals("gone", an.toString())
        assertTrue(an.spanStyles.any { it.item.textDecoration != null })
    }

    // 4. BOLD + ITALIC in same word (***bolditalic***)
    @Test
    fun testBoldItalic_combined() {
        val an = parseInline("***both***", Color.Black, "", false, Color.Blue)
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
        val an = parseInline("==mark==", Color.Black, "", false, Color.Blue)
        assertEquals("mark", an.toString())
        assertTrue(an.spanStyles.any { it.item.background != null })
    }

    // 10. SUPERSCRIPT / SUBSCRIPT
    @Test
    fun testSuperscriptAndSubscript_parse() {
        val sup = parseInline("x^2^", Color.Black, "", false, Color.Blue)
        assertTrue(sup.spanStyles.any { it.item.baselineShift != null })
        val sub = parseInline("H~2~O", Color.Black, "", false, Color.Blue)
        assertTrue(sub.spanStyles.any { it.item.baselineShift != null })
    }

    // 11. KEYBOARD KEYS <kbd>
    @Test
    fun testKbd_parses() {
        val an = parseInline("Press <kbd>Ctrl</kbd>+<kbd>C</kbd>", Color.Black, "", false, Color.Blue)
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
        val an = parseInline("use `val x = 1` here", Color.Black, "", false, Color.Blue)
        assertEquals("use val x = 1 here", an.toString())
        assertTrue(an.spanStyles.any { it.item.fontFamily != null })
    }
}
