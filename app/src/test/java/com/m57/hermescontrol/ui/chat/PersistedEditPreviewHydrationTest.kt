package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests verifying that persisted edit previews survive transcript loading into [ChatMessage]
 * and subsequent tool diff parsing via [parseToolOutput].
 */
class PersistedEditPreviewHydrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parseJson(raw: String): JsonElement = json.parseToJsonElement(raw)

    private fun toolSessionMessage(
        content: String,
        displayMetadata: JsonElement? = null,
        id: Int = 101,
        toolCallId: String = "call-101",
    ): SessionMessage =
        SessionMessage(
            id = id,
            role = "tool",
            content = JsonPrimitive(content),
            tool_call_id = toolCallId,
            display_metadata = displayMetadata,
        )

    private fun mapSingle(msg: SessionMessage): ChatMessage =
        mapServerMessages(
            sessionId = "sess-edit-preview",
            messages = listOf(msg),
            offset = 0,
            latestPaging = true,
            liveMessages = emptyList(),
        ).single()

    // ── Contract 1: ChatServerMessageMapper enrichment ────────────────────────

    @Test
    fun mapServerMessages_enrichesToolContentWithDisplayMetadataInlineDiff() {
        val diffPayload =
            """
            {
              "tool_result_metadata": {
                "inline_diff": "@@ -1 +1 @@\n-old\n+new"
              }
            }
            """.trimIndent()
        val originalContent = """{"output":"File updated successfully"}"""
        val serverMsg =
            toolSessionMessage(
                content = originalContent,
                displayMetadata = parseJson(diffPayload),
            )

        val chatMessage = mapSingle(serverMsg)

        val enriched = parseJson(chatMessage.content) as? JsonObject
        assertNotNull("Expected enriched content to parse as JSON object", enriched)
        assertEquals(
            "File updated successfully",
            (enriched?.get("output") as? JsonPrimitive)?.content,
        )
        val displayMeta = enriched?.get("display_metadata") as? JsonObject
        assertNotNull("Expected display_metadata to be added to content", displayMeta)
        val toolResultMeta = displayMeta?.get("tool_result_metadata") as? JsonObject
        assertNotNull("Expected tool_result_metadata inside display_metadata", toolResultMeta)
        val diff = (toolResultMeta?.get("inline_diff") as? JsonPrimitive)?.content
        assertEquals("@@ -1 +1 @@\n-old\n+new", diff)
    }

    @Test
    fun mapServerMessages_doesNotEnrichWhenDisplayMetadataIsNull() {
        val originalContent = """{"output":"no metadata"}"""
        val serverMsg =
            toolSessionMessage(
                content = originalContent,
                displayMetadata = null,
            )

        val chatMessage = mapSingle(serverMsg)

        assertEquals(originalContent, chatMessage.content)
    }

    @Test
    fun mapServerMessages_doesNotEnrichWhenRoleIsNotTool() {
        val metadata =
            """
            {
              "tool_result_metadata": {
                "inline_diff": "@@ -1 +1 @@\n-old\n+new"
              }
            }
            """.trimIndent()
        val originalContent = "Assistant says hello"
        val serverMsg =
            SessionMessage(
                id = 202,
                role = "assistant",
                content = JsonPrimitive(originalContent),
                display_metadata = parseJson(metadata),
            )

        val chatMessage = mapSingle(serverMsg)

        assertEquals(originalContent, chatMessage.content)
    }

    @Test
    fun mapServerMessages_doesNotOverwriteExistingDisplayMetadataInContent() {
        val content =
            """
            {
              "output": "ok",
              "display_metadata": {
                "existing": true
              }
            }
            """.trimIndent()
        val externalMetadata =
            """
            {
              "tool_result_metadata": {
                "inline_diff": "@@ -1 +1 @@\n-old\n+new"
              }
            }
            """.trimIndent()
        val serverMsg =
            toolSessionMessage(
                content = content,
                displayMetadata = parseJson(externalMetadata),
            )

        val chatMessage = mapSingle(serverMsg)

        val parsed = parseJson(chatMessage.content) as? JsonObject
        assertNotNull(parsed)
        val meta = parsed?.get("display_metadata") as? JsonObject
        assertEquals("true", (meta?.get("existing") as? JsonPrimitive)?.content)
        assertNull(meta?.get("tool_result_metadata"))
    }

    @Test
    fun mapServerMessages_ignoresNonJsonObjectContent() {
        val nonJsonContent = "Plain text output from tool"
        val metadata =
            """
            {
              "tool_result_metadata": {
                "inline_diff": "@@ -1 +1 @@\n-old\n+new"
              }
            }
            """.trimIndent()
        val serverMsg =
            toolSessionMessage(
                content = nonJsonContent,
                displayMetadata = parseJson(metadata),
            )

        val chatMessage = mapSingle(serverMsg)

        assertEquals(nonJsonContent, chatMessage.content)
    }

    // ── Contract 2: ToolResultParser / FileRenderers inline diff extraction ──

    @Test
    fun parseToolOutput_extractsInlineDiffFromNestedToolResultMetadata() {
        val rawDiff = "@@ -1,3 +1,3 @@\n-old line\n+new line"
        val content =
            """
            {
              "name": "patch",
              "args": {
                "path": "app/src/main/Utils.kt"
              },
              "display_metadata": {
                "tool_result_metadata": {
                  "inline_diff": "@@ -1,3 +1,3 @@\n-old line\n+new line"
                }
              }
            }
            """.trimIndent()

        val view = parseToolOutput(content = content, toolName = "patch", isRunning = false)

        assertNotNull("Expected parseToolOutput to return a ToolView", view)
        assertEquals(rawDiff, view?.inlineDiff)
        assertEquals("app/src/main/Utils.kt", view?.diffPath)
        assertEquals(1, view?.diffStats?.added)
        assertEquals(1, view?.diffStats?.removed)
    }

    @Test
    fun parseToolOutput_extractsInlineDiffFromFlatDisplayMetadata() {
        val rawDiff = "--- a/test.kt\n+++ b/test.kt\n@@ -1 +1 @@\n-alpha\n+beta"
        val content =
            """
            {
              "name": "edit_file",
              "args": {
                "path": "test.kt"
              },
              "display_metadata": {
                "inline_diff": "--- a/test.kt\n+++ b/test.kt\n@@ -1 +1 @@\n-alpha\n+beta"
              }
            }
            """.trimIndent()

        val view = parseToolOutput(content = content, toolName = "edit_file", isRunning = false)

        assertNotNull(view)
        assertEquals(rawDiff, view?.inlineDiff)
        assertEquals("test.kt", view?.diffPath)
        assertEquals(1, view?.diffStats?.added)
        assertEquals(1, view?.diffStats?.removed)
    }

    @Test
    fun parseToolOutput_extractsInlineDiffFromResultObjectDirectly() {
        val rawDiff = "@@ -1 +1 @@\n-before\n+after"
        val content =
            """
            {
              "name": "patch",
              "args": {
                "path": "file.txt"
              },
              "result": {
                "inline_diff": "@@ -1 +1 @@\n-before\n+after"
              }
            }
            """.trimIndent()

        val view = parseToolOutput(content = content, toolName = "patch", isRunning = false)

        assertNotNull(view)
        assertEquals(rawDiff, view?.inlineDiff)
        assertEquals("file.txt", view?.diffPath)
        assertEquals(1, view?.diffStats?.added)
        assertEquals(1, view?.diffStats?.removed)
    }

    @Test
    fun parseToolOutput_absentOrEmptyMetadataYieldsNullInlineDiff() {
        val emptyDiffContent =
            """
            {
              "name": "patch",
              "args": {
                "path": "file.txt"
              },
              "display_metadata": {
                "tool_result_metadata": {
                  "inline_diff": ""
                }
              }
            }
            """.trimIndent()
        val noDiffView = parseToolOutput(content = emptyDiffContent, toolName = "patch", isRunning = false)
        assertNotNull(noDiffView)
        assertNull(noDiffView?.inlineDiff)

        val missingDiffContent =
            """
            {
              "name": "patch",
              "args": {
                "path": "file.txt"
              },
              "result": {
                "success": true
              }
            }
            """.trimIndent()
        val missingDiffView = parseToolOutput(content = missingDiffContent, toolName = "patch", isRunning = false)
        assertNotNull(missingDiffView)
        assertNull(missingDiffView?.inlineDiff)
    }

    // ── End-to-end integration: Transcript hydration -> ToolView diff parsing ─

    @Test
    fun endToEnd_hydratedTranscriptProducesToolViewWithInlineDiff() {
        val serverDiff = "@@ -1,2 +1,2 @@\n-val x = 1\n+val x = 2"
        val sessionMsg =
            toolSessionMessage(
                content = """{"name":"patch","args":{"path":"src/Config.kt"},"result":{"success":true}}""",
                displayMetadata =
                    parseJson(
                        """
                        {
                          "tool_result_metadata": {
                            "inline_diff": "@@ -1,2 +1,2 @@\n-val x = 1\n+val x = 2"
                          }
                        }
                        """.trimIndent(),
                    ),
            )

        val chatMessage = mapSingle(sessionMsg)
        assertTrue(chatMessage.content.contains("display_metadata"))

        val toolView =
            parseToolOutput(
                content = chatMessage.content,
                toolName = "patch",
                isRunning = false,
            )

        assertNotNull("Expected parseToolOutput to succeed for hydrated message content", toolView)
        assertEquals(serverDiff, toolView?.inlineDiff)
        assertEquals("src/Config.kt", toolView?.diffPath)
        assertEquals(1, toolView?.diffStats?.added)
        assertEquals(1, toolView?.diffStats?.removed)
    }
}
