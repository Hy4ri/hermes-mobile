package com.m57.hermescontrol.ui.chat.tool.render

import org.junit.Assert.assertEquals
import org.junit.Test

class FileEditSupportTest {
    @Test
    fun testCountDiffLineStats_emptyDiff() {
        val stats = FileEditSupport.countDiffLineStats("")
        assertEquals(0, stats.added)
        assertEquals(0, stats.removed)
    }

    @Test
    fun testCountDiffLineStats_headerFiltering() {
        val diff =
            """
            --- a/file.txt
            +++ b/file.txt
            @@ -1,5 +1,5 @@
             unchanged
            -removed line
            +added line
            """.trimIndent()
        val stats = FileEditSupport.countDiffLineStats(diff)
        assertEquals(1, stats.added)
        assertEquals(1, stats.removed)
    }

    @Test
    fun testCountDiffLineStats_crlfHandling() {
        val diff = "---\r\n+++\r\n+added\r\n-removed\r\n+another\r\n"
        val stats = FileEditSupport.countDiffLineStats(diff)
        assertEquals(2, stats.added)
        assertEquals(1, stats.removed)
    }

    @Test
    fun testCountDiffLineStats_largeDiff() {
        val sb = java.lang.StringBuilder()
        sb.append("--- a/file.txt\n")
        sb.append("+++ b/file.txt\n")

        val count = 5000
        for (i in 0 until count) {
            sb.append("-old line $i\n")
            sb.append("+new line $i\n")
        }

        val stats = FileEditSupport.countDiffLineStats(sb.toString())
        assertEquals(count, stats.added)
        assertEquals(count, stats.removed)
    }
}
