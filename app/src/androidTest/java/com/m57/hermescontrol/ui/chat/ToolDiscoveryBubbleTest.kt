package com.m57.hermescontrol.ui.chat

import androidx.compose.ui.test.junit4.createComposeRule
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
class ToolDiscoveryBubbleTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun tappingSearchBubbleShowsMatchedToolDetails() {
        showBubble(
            "tool_search",
            """{"name":"tool_search","args":{"queries":["process output"]},"result":{"results":[{"query":"process output","matches":["process_manage"]}],"tools":{"process_manage":{"description":"Poll a background process","source_name":"terminal","required":["action"]}}}}""",
        )

        composeRule.onNodeWithText("process output (1 match)", substring = true).performClick()
        composeRule.onNodeWithText("Poll a background process", substring = true).assertExists()
    }

    @Test
    fun tappingDescribeBubbleShowsSchema() {
        showBubble(
            "tool_describe",
            """{"name":"tool_describe","args":{"names":["process_manage"]},"result":{"tools":{"process_manage":{"description":"Poll a background process","parameters":{"type":"object","properties":{"action":{"type":"string","description":"Action to take"}},"required":["action"]}}}}}""",
        )

        composeRule.onNodeWithText("process_manage (1 tool)", substring = true).performClick()
        composeRule.onNodeWithText("Action to take", substring = true).assertExists()
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
