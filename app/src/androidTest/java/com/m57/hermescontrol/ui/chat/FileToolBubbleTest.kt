package com.m57.hermescontrol.ui.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.theme.HermesControlTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class FileToolBubbleTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tappingReadFileBubbleShowsFileViewCardWithSyntaxViewer() {
        val payload =
            """
            {
              "args": {"path": "/repo/src/Main.kt", "offset": 1, "limit": 2},
              "result": {"content": "1|fun main() {\n2|    println(\"hi\")\n3|}"}
            }
            """.trimIndent()

        showBubble("read_file", payload)

        composeRule.onNodeWithText("Read Main.kt L1-2", substring = true).performClick()
        composeRule.onNodeWithTag("file_view_card", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("/repo/src/Main.kt", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("3 lines", useUnmergedTree = true).assertExists()
    }

    @Test
    fun tappingWriteFileBubbleShowsFileViewCardWhenNoDiff() {
        val payload =
            """
            {
              "args": {
                "path": "/repo/src/Config.json",
                "content": "{\n  \"enabled\": true\n}"
              },
              "result": {"verified": true, "success": true}
            }
            """.trimIndent()

        showBubble("write_file", payload)

        composeRule.onNodeWithText("Config.json", substring = true).performClick()
        composeRule.onNodeWithTag("file_view_card", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("/repo/src/Config.json", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("3 lines", useUnmergedTree = true).assertExists()
    }

    @Test
    fun tappingWriteFileWithDiffShowsDiffViewCard() {
        val payload =
            """
            {
              "args": {"path": "/repo/src/Utils.kt"},
              "result": {
                "inline_diff": "--- a/Utils.kt\n+++ b/Utils.kt\n@@ -1,1 +1,1 @@\n-old\n+new"
              }
            }
            """.trimIndent()

        showBubble("write_file", payload)

        composeRule.onNodeWithText("Utils.kt", substring = true).performClick()
        composeRule.onNodeWithTag("diff_view_card", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Utils.kt", useUnmergedTree = true).assertExists()
    }

    private fun showBubble(
        name: String,
        content: String,
    ) {
        composeRule.setContent {
            HermesControlTheme {
                ToolBubble(
                    ChatMessage(
                        role = MessageRole.TOOL,
                        content = content,
                        toolName = name,
                        toolStatus = ToolStatus.COMPLETED,
                    ),
                )
            }
        }
    }
}
