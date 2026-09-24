package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatVerifierFooterTest {
    private val sampleFooter =
        """
        ⚠️ File-mutation verifier: 1 file edit(s) FAILED this turn despite any wording above that may suggest otherwise. Run git status or read_file to confirm what actually landed.
          • /tmp/skill_lang_audit.py — [write_file] Write denied: '/tmp/skill_lang_audit.py' is outside HERMES_WRITE_SAFE_ROOT (/opt/data). Unset the variable or add this path's directory prefix.
        """.trimIndent()

    private val backtickedFooter =
        """
        ⚠️ File-mutation verifier: 1 file edit(s) FAILED this turn despite any wording above that may suggest otherwise. Run `git status` or `read_file` to confirm what actually landed.
          • `/tmp/skill_lang_audit.py` — [write_file] Write denied: '/tmp/skill_lang_audit.py' is outside HERMES_WRITE_SAFE_ROOT (/opt/data). Unset the variable or add this path's directory prefix.
        """.trimIndent()

    @Test
    fun `split extracts body and footer for exact reporter format`() {
        val body = "I completed the audit. Here are the findings."
        val full = "$body\n\n$sampleFooter"

        val result = ChatVerifierFooter.split(full)
        assertNotNull(result)
        assertEquals(body, result?.body)
        assertEquals(sampleFooter, result?.footer?.trim())
    }

    @Test
    fun `split extracts body and footer for backend backticked format`() {
        val body = "Audit summary:\n- item 1\n- item 2"
        val full = "$body\n\n$backtickedFooter"

        val result = ChatVerifierFooter.split(full)
        assertNotNull(result)
        assertEquals(body, result?.body)
        assertEquals(backtickedFooter, result?.footer?.trim())
    }

    @Test
    fun `split handles multiple bullets and overflow note`() {
        val footer =
            """
            ⚠️ File-mutation verifier: 3 file edit(s) FAILED this turn despite any wording above that may suggest otherwise. Run `git status` or `read_file` to confirm what actually landed.
              • `/a.py` — [patch] failed
              • `/b.py` — [patch] failed
              • … and 1 more
            """.trimIndent()
        val body = "Done"
        val result = ChatVerifierFooter.split("$body\n\n$footer")
        assertNotNull(result)
        assertEquals("Done", result?.body)
    }

    @Test
    fun `split rejects footer when body is empty`() {
        val result = ChatVerifierFooter.split(sampleFooter)
        assertNull(result)
    }

    @Test
    fun `split rejects footer when inside a line without newline before it`() {
        val text = "Look at this: ⚠️ File-mutation verifier: 1 file edit(s) FAILED this turn despite any wording..."
        val result = ChatVerifierFooter.split(text)
        assertNull(result)
    }

    @Test
    fun `split rejects text with unrelated trailing prose`() {
        val full = "Body text\n\n$sampleFooter\nAnd here is more stuff after the footer."
        val result = ChatVerifierFooter.split(full)
        assertNull(result)
    }

    @Test
    fun `matchesBase compares body ignoring verifier footer`() {
        val body = "Here is the response without errors."
        val withFooter = "$body\n\n$sampleFooter"

        assertTrue(ChatVerifierFooter.matchesBase(body, withFooter))
        assertTrue(ChatVerifierFooter.matchesBase(withFooter, body))
        assertTrue(ChatVerifierFooter.matchesBase(withFooter, withFooter))
        assertFalse(ChatVerifierFooter.matchesBase("Different body", withFooter))
    }
}
