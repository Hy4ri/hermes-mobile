package com.m57.hermescontrol.ui.sessions.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCardFooterTest {
    @Test
    fun `everything fits at its natural width`() {
        assertTrue(
            footerFitsOneLine(maxWidth = 400, leadWidth = 120, countWidth = 90, badgesWidth = 100, leadMinWidth = 80),
        )
    }

    @Test
    fun `a long model shrinks instead of pushing the badges down`() {
        assertTrue(
            footerFitsOneLine(maxWidth = 400, leadWidth = 380, countWidth = 90, badgesWidth = 150, leadMinWidth = 80),
        )
    }

    @Test
    fun `badges wrap only when the model would drop below its minimum`() {
        assertFalse(
            footerFitsOneLine(maxWidth = 300, leadWidth = 380, countWidth = 120, badgesWidth = 150, leadMinWidth = 80),
        )
    }

    @Test
    fun `a short model only needs its own width`() {
        assertTrue(
            footerFitsOneLine(maxWidth = 300, leadWidth = 40, countWidth = 120, badgesWidth = 140, leadMinWidth = 80),
        )
    }

    @Test
    fun `no badges always fits one line`() {
        assertTrue(
            footerFitsOneLine(maxWidth = 100, leadWidth = 380, countWidth = 120, badgesWidth = 0, leadMinWidth = 80),
        )
    }
}
