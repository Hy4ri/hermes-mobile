package com.m57.hermescontrol.ui.chat.fullbleed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatSearchState
import com.m57.hermescontrol.ui.chat.ChatTimelineState
import com.m57.hermescontrol.ui.chat.ChatUiState
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.SearchTarget
import com.m57.hermescontrol.ui.chat.StreamingState
import com.m57.hermescontrol.ui.chat.ToolStatus
import com.m57.hermescontrol.ui.chat.components.rememberChatScrollController
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullBleedToolGroupTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun consecutiveToolsCollapseByDefaultAndToggleWithoutChangingToolBubbles() {
        showTools(2)
        compose.onNodeWithTag("fullbleed_tool_group_t1").assertIsDisplayed()
        compose.onAllNodesWithTag("fullbleed_tool_row").assertCountEquals(0)
        compose.onNodeWithTag("fullbleed_tool_group_t1").performClick()
        compose.onAllNodesWithTag("fullbleed_tool_row").assertCountEquals(2)
        compose.onNodeWithTag("fullbleed_tool_group_t1").performClick()
        compose.onAllNodesWithTag("fullbleed_tool_row").assertCountEquals(0)
    }

    @Test
    fun singletonRendersTheOriginalToolRow() {
        showTools(1)
        compose.onAllNodesWithTag("fullbleed_tool_group_t1").assertCountEquals(0)
        compose.onNodeWithTag("fullbleed_tool_row").assertIsDisplayed()
    }

    @Test
    fun searchOpensAnOffscreenGroupAndScrollsToTheSpecificTool() {
        val search = ChatSearchState()
        val listState = showTools(20, search)
        compose.runOnIdle {
            search.isActive = true
            search.query = "output 18"
            search.matchIndices = listOf(17)
            search.matchOffsets = listOf(0)
            search.matchTargets = listOf(SearchTarget.TOOL)
            search.currentIndex = 0
            search.matchedIds = setOf("t18")
            search.currentMatchId = "t18"
        }
        compose.waitUntil(10_000) {
            listState.layoutInfo.visibleItemsInfo.any { it.key == "tool-t18" }
        }
        compose.runOnIdle {
            assertTrue(listState.layoutInfo.visibleItemsInfo.any { it.key == "tool-t18" })
        }
    }

    @Test
    fun changingSessionResetsGroupExpansion() {
        val session = mutableStateOf("first")
        showTools(2, sessionId = { session.value })
        compose.onNodeWithTag("fullbleed_tool_group_t1").performClick()
        compose.onAllNodesWithTag("fullbleed_tool_row").assertCountEquals(2)
        compose.runOnIdle { session.value = "second" }
        compose.onAllNodesWithTag("fullbleed_tool_row").assertCountEquals(0)
    }

    private fun showTools(
        count: Int,
        search: ChatSearchState = ChatSearchState(),
        sessionId: () -> String = { "session" },
    ): LazyListState {
        val tools =
            (1..count).map {
                ChatMessage(
                    "t$it",
                    MessageRole.TOOL,
                    "output $it",
                    toolName = "terminal",
                    toolStatus = ToolStatus.COMPLETED,
                )
            }
        val listState = LazyListState()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = rememberChatScrollController(listState, scope)
            HermesControlTheme {
                Box(Modifier.size(width = 320.dp, height = 480.dp)) {
                    FullBleedChatList(
                        transcript =
                            TranscriptUiState.resolve(
                                chat = ChatUiState(messages = tools, currentSessionId = sessionId()),
                                timeline = ChatTimelineState(),
                                streaming = StreamingState(),
                                savingAttachmentPath = null,
                                speakingMessageId = null,
                            ),
                        actions = testTranscriptActions(),
                        searchState = search,
                        listState = listState,
                        scrollController = controller,
                    )
                }
            }
        }
        return listState
    }
}
