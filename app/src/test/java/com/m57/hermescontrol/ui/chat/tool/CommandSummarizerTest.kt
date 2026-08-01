package com.m57.hermescontrol.ui.chat.tool

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandSummarizerTest {
    @Test
    fun `simple command passes through`() {
        assertEquals("ls -la", CommandSummarizer.summarizeShellCommand("ls -la"))
    }

    @Test
    fun `plumbing wrapper peels to the real command`() {
        val summarized =
            CommandSummarizer.summarizeShellCommand(
                """cd /tmp && sleep 70 2>&1 | tail -5; echo "x_exit=${'$'}{PIPESTATUS[0]}"""",
            )

        assertEquals("sleep 70", summarized)
    }

    @Test
    fun `multiple real commands become first plus count`() {
        // Faithful to the TS port: the FIRST surviving segment leads the
        // count line — echo is not a silent head, so it leads here.
        assertEquals("echo hi + 2 commands", CommandSummarizer.summarizeShellCommand("echo hi && sleep 2 && ls"))
    }

    @Test
    fun `redirects are cleaned`() {
        // Env-prefix removal happens in headWord (classification only), so
        // the output keeps FOO=bar — mirrors the TS behavior exactly.
        assertEquals(
            "FOO=bar python run.py",
            CommandSummarizer.summarizeShellCommand("FOO=bar python run.py > /tmp/out.log 2>&1"),
        )
    }

    @Test
    fun `single-segment commands keep their pipe tail`() {
        // Faithful to the TS port: the single-segment path re-cleans the
        // ORIGINAL command (redirects only, no pipe handling) — pipe-tail
        // stripping applies in the multi-segment path below.
        assertEquals(
            "grep foo bar.txt | tail -20",
            CommandSummarizer.summarizeShellCommand("grep foo bar.txt | tail -20"),
        )
    }

    @Test
    fun `multi-segment commands strip pipe tails`() {
        assertEquals(
            "grep foo bar.txt + 1 command",
            CommandSummarizer.summarizeShellCommand("cd /tmp && grep foo bar.txt | tail -20 && echo done"),
        )
    }

    @Test
    fun `quote-aware compound split keeps echo arguments intact`() {
        // The `&&` inside quotes is not a separator, so this is two segments
        // and echo (not silent) leads the count line.
        val summarized = CommandSummarizer.summarizeShellCommand("""echo "a && b" && git status""")

        assertEquals("""echo "a && b" + 1 command""", summarized)
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals("", CommandSummarizer.summarizeShellCommand("  "))
    }
}
