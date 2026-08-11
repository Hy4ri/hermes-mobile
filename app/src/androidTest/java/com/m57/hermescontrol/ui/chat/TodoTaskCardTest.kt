package com.m57.hermescontrol.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.theme.HermesControlTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class TodoTaskCardTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun todoTool_displaysExpandedTaskCard() {
        val message =
            ChatMessage(
                role = MessageRole.TOOL,
                toolName = null,
                toolStatus = ToolStatus.COMPLETED,
                toolOutputRiskData = ToolOutputRiskData(risk = "high", findings = emptyList(), redacted = false),
                content =
                    """{
                        "name": "todo",
                        "result": {
                            "todos": [
                                {"id": "one", "content": "Finished", "status": "completed"},
                                {"id": "two", "content": "Working", "status": "in_progress"},
                                {"id": "three", "content": "Later", "status": "pending"}
                            ],
                            "summary": {"total": 3, "pending": 1, "in_progress": 1, "completed": 1, "cancelled": 0}
                        }
                    }""",
            )

        composeTestRule.setContent {
            HermesControlTheme {
                ToolBubble(message = message)
            }
        }

        composeTestRule.onNodeWithTag("todo_task_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tasks 1/3").assertIsDisplayed()
        composeTestRule.onNodeWithText("Finished").assertIsDisplayed()
        composeTestRule.onNodeWithText("Working").assertIsDisplayed()
        composeTestRule.onNodeWithText("Later").assertIsDisplayed()
        composeTestRule.onNodeWithText("Risky output").assertIsDisplayed()
    }

    @Test
    fun emptyTodoOutput_usesGenericToolBubble() {
        val message =
            ChatMessage(
                role = MessageRole.TOOL,
                toolName = "todo",
                toolStatus = ToolStatus.COMPLETED,
                content = """{"name":"todo","result":{"todos":[]}}""",
            )

        composeTestRule.setContent {
            HermesControlTheme {
                ToolBubble(message = message)
            }
        }

        composeTestRule.onNodeWithTag("todo_task_card").assertDoesNotExist()
    }

    @Test
    fun gatewayAliases_renderStructuredStatuses() {
        val message =
            ChatMessage(
                role = MessageRole.TOOL,
                toolStatus = ToolStatus.COMPLETED,
                content =
                    """{"tool_name":"todo_write","result":{"todos":[
                        {"id":"one","content":"Done alias","status":"done"},
                        {"id":"two","content":"Running alias","status":"running"},
                        {"id":"three","content":"Failed alias","status":"failed"},
                        {"id":"four","content":"Queued alias","status":"queued"}
                    ]}}""",
            )

        composeTestRule.setContent {
            HermesControlTheme {
                ToolBubble(message = message)
            }
        }

        composeTestRule.onNodeWithText("Tasks 1/4").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Completed").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Cancelled").assertIsDisplayed()
        composeTestRule.onNodeWithText("Running alias").assertIsDisplayed()
        composeTestRule.onNodeWithText("Queued alias").assertIsDisplayed()
    }

    @Test
    fun malformedTodoOutput_usesGenericToolBubble() {
        val message =
            ChatMessage(
                role = MessageRole.TOOL,
                toolName = "todo",
                toolStatus = ToolStatus.COMPLETED,
                content = "not json",
            )

        composeTestRule.setContent {
            HermesControlTheme {
                ToolBubble(message = message)
            }
        }

        composeTestRule.onNodeWithTag("todo_task_card").assertDoesNotExist()
    }
}
