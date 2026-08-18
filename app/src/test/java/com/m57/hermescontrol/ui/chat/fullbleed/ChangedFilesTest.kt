package com.m57.hermescontrol.ui.chat.fullbleed

import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [deriveChangedFiles] — the desktop-parity fold of a turn's
 * file-edit tool rows into one row per file with summed +/-.
 */
class ChangedFilesTest {
    private fun patch(
        path: String,
        added: Int,
        removed: Int,
    ): ChatMessage {
        // inline_diff must carry the real hunks so ToolViewBuilder can derive
        // diffStats from the text (it counts +/- lines, not the @@ header).
        val plus = (1..added).joinToString("\n") { "+y" }
        val minus = (1..removed).joinToString("\n") { "-x" }
        return ChatMessage(
            role = MessageRole.TOOL,
            toolName = "patch",
            toolStatus = ToolStatus.COMPLETED,
            content =
                """{"args":{"path":"$path"},"result":{"inline_diff":"--- a/$path
+++ b/$path
@@ -1,$removed +1,$added @@
$minus
$plus"}}""",
        )
    }

    @Test
    fun `single patch yields one changed file`() {
        val files = deriveChangedFiles(listOf(patch("/repo/a.kt", 2, 1)))
        assertEquals(1, files.size)
        assertEquals("/repo/a.kt", files[0].path)
        assertEquals("a.kt", files[0].name)
        assertEquals(2, files[0].added)
        assertEquals(1, files[0].removed)
    }

    @Test
    fun `two edits to same file sum their plus-minus`() {
        val files = deriveChangedFiles(listOf(patch("/repo/a.kt", 2, 1), patch("/repo/a.kt", 3, 0)))
        assertEquals(1, files.size)
        assertEquals(5, files[0].added)
        assertEquals(1, files[0].removed)
    }

    @Test
    fun `edits to different files stay separate`() {
        val files = deriveChangedFiles(listOf(patch("/repo/a.kt", 2, 1), patch("/repo/b.kt", 1, 4)))
        assertEquals(2, files.size)
        assertTrue(files.any { it.path == "/repo/a.kt" })
        assertTrue(files.any { it.path == "/repo/b.kt" })
    }

    @Test
    fun `running and diff-less writes are skipped`() {
        val running =
            ChatMessage(
                role = MessageRole.TOOL,
                toolName = "patch",
                toolStatus = ToolStatus.RUNNING,
                content = """{"args":{"path":"/repo/a.kt"},"result":{}}""",
            )
        val noDiff =
            ChatMessage(
                role = MessageRole.TOOL,
                toolName = "write_file",
                toolStatus = ToolStatus.COMPLETED,
                content = """{"args":{"path":"/repo/a.kt"},"result":{"content":"x"}}""",
            )
        assertTrue(deriveChangedFiles(listOf(running, noDiff)).isEmpty())
    }

    @Test
    fun `non-file-edit tools are ignored`() {
        val terminal =
            ChatMessage(
                role = MessageRole.TOOL,
                toolName = "terminal",
                toolStatus = ToolStatus.COMPLETED,
                content = """{"args":{"command":"ls"},"result":{"output":"x","exit_code":0}}""",
            )
        assertTrue(deriveChangedFiles(listOf(terminal)).isEmpty())
    }
}
