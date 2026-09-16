package com.m57.hermescontrol.ui.chat.fullbleed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatSearchState
import com.m57.hermescontrol.ui.chat.ChatViewModel
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.StreamingState
import com.m57.hermescontrol.ui.chat.ToolStatus
import com.m57.hermescontrol.ui.chat.components.ChatScrollController
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI tests for the full-bleed chat renderer (issue #866).
 *
 * Validates the parallel renderer's hierarchy contract:
 * - user messages KEEP the bubble (universal anchor)
 * - assistant prose renders full-bleed (no bubble), one turn header per turn
 * - tool rows render as distinct compact rows, NOT full-bleed prose
 * - streaming + clarify still render
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class FullBleedChatListTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun msg(
        id: String,
        role: MessageRole,
        content: String = "content-$id",
        toolStatus: ToolStatus? = null,
        isStreaming: Boolean = false,
        tokenCount: Int? = null,
        tps: Double? = null,
    ) = ChatMessage(
        id = id,
        role = role,
        content = content,
        toolStatus = toolStatus,
        isStreaming = isStreaming,
        tokenCount = tokenCount,
        tps = tps,
    )

    private fun render(
        messages: List<ChatMessage>,
        streamingMessage: ChatMessage? = null,
        streamingState: StreamingState = StreamingState(streamingMessage = streamingMessage),
        isAgentTyping: Boolean = streamingMessage?.isStreaming == true,
        clarify: Boolean = false,
        messageStatsEnabled: Boolean = false,
        showUserMessageTokens: Boolean = true,
        showAssistantMessageTokens: Boolean = true,
        showTokensPerSecond: Boolean = true,
    ) {
        composeTestRule.setContent {
            val listState = LazyListState()
            FullBleedChatList(
                messages = messages,
                streamingState = streamingState,
                isAgentTyping = isAgentTyping,
                searchState = ChatSearchState(),
                typingEffectEnabled = false,
                typingEffectDelayMs = 30,
                messageStatsEnabled = messageStatsEnabled,
                showUserMessageTokens = showUserMessageTokens,
                showAssistantMessageTokens = showAssistantMessageTokens,
                showTokensPerSecond = showTokensPerSecond,
                isLoading = false,
                isLoadingOlder = false,
                listState = listState,
                scrollController =
                    ChatScrollController(
                        listState = listState,
                        scope = CoroutineScope(Dispatchers.Main.immediate),
                    ),
                lastAnimatedMessageId = null,
                onLastAnimatedMessageIdChange = {},
                viewModel = mockk<ChatViewModel>(relaxed = true),
                clarifyRequest =
                    if (clarify) {
                        com.m57.hermescontrol.ui.chat.ClarifyUi(
                            text = "pick",
                            options = listOf("a"),
                        )
                    } else {
                        null
                    },
            )
        }
    }

    @Test
    fun userMessages_keepBubble_agentProse_isFullBleed() {
        render(
            listOf(
                msg("u1", MessageRole.USER),
                msg("a1", MessageRole.ASSISTANT),
            ),
        )
        composeTestRule.onNodeWithTag("chat_bubble_user").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fullbleed_agent_message").assertIsDisplayed()
    }

    @Test
    fun agentProse_hasNoBubbleContainer() {
        render(listOf(msg("a1", MessageRole.ASSISTANT)))
        composeTestRule.onNodeWithTag("chat_bubble_assistant").assertDoesNotExist()
        composeTestRule.onNodeWithTag("fullbleed_agent_message").assertIsDisplayed()
    }

    @Test
    fun toolRows_renderCompact_notFullBleed() {
        render(
            listOf(
                msg("a1", MessageRole.ASSISTANT),
                msg("t1", MessageRole.TOOL, toolStatus = ToolStatus.COMPLETED),
            ),
        )
        composeTestRule.onNodeWithTag("fullbleed_tool_row").assertIsDisplayed()
        // exactly one full-bleed message (the prose), not the tool row
        composeTestRule.onAllNodesWithTag("fullbleed_agent_message").assertCountEquals(1)
    }

    @Test
    fun assistantHeader_isRemovedFromEveryAgentTurn() {
        render(
            listOf(
                msg("a1", MessageRole.ASSISTANT),
                msg("t1", MessageRole.TOOL, toolStatus = ToolStatus.COMPLETED),
                msg("a2", MessageRole.ASSISTANT),
                msg("u2", MessageRole.USER),
                msg("a3", MessageRole.ASSISTANT),
            ),
        )
        composeTestRule.onAllNodesWithTag("fullbleed_agent_header").assertCountEquals(0)
        composeTestRule.onNodeWithTag("fullbleed_finish_time").assertDoesNotExist()
    }

    @Test
    fun messageStats_masterOff_hidesAllMetadata() {
        render(
            messages =
                listOf(
                    msg("u1", MessageRole.USER, tokenCount = 123),
                    msg("a1", MessageRole.ASSISTANT, tokenCount = 456, tps = 42.5),
                ),
        )
        composeTestRule.onNodeWithTag("bubble_token_count").assertDoesNotExist()
        composeTestRule.onNodeWithTag("fullbleed_token_count").assertDoesNotExist()
        composeTestRule.onNodeWithTag("fullbleed_tps").assertDoesNotExist()
    }

    @Test
    fun messageStats_userTokensOnly_showsOnlyUserTokens() {
        render(
            messages =
                listOf(
                    msg("u1", MessageRole.USER, tokenCount = 123),
                    msg("a1", MessageRole.ASSISTANT, tokenCount = 456, tps = 42.5),
                ),
            messageStatsEnabled = true,
            showAssistantMessageTokens = false,
            showTokensPerSecond = false,
        )
        composeTestRule.onNodeWithTag("bubble_token_count").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fullbleed_token_count").assertDoesNotExist()
        composeTestRule.onNodeWithTag("fullbleed_tps").assertDoesNotExist()
    }

    @Test
    fun messageStats_assistantTokensOnly_showsOnlyAssistantTokens() {
        render(
            messages =
                listOf(
                    msg("u1", MessageRole.USER, tokenCount = 123),
                    msg("a1", MessageRole.ASSISTANT, tokenCount = 456, tps = 42.5),
                ),
            messageStatsEnabled = true,
            showUserMessageTokens = false,
            showTokensPerSecond = false,
        )
        composeTestRule.onNodeWithTag("bubble_token_count").assertDoesNotExist()
        composeTestRule.onNodeWithTag("fullbleed_token_count").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fullbleed_tps").assertDoesNotExist()
    }

    @Test
    fun messageStats_tpsOnly_showsOnlyAssistantTps() {
        render(
            messages =
                listOf(
                    msg("u1", MessageRole.USER, tokenCount = 123),
                    msg("a1", MessageRole.ASSISTANT, tokenCount = 456, tps = 42.5),
                ),
            messageStatsEnabled = true,
            showUserMessageTokens = false,
            showAssistantMessageTokens = false,
        )
        composeTestRule.onNodeWithTag("bubble_token_count").assertDoesNotExist()
        composeTestRule.onNodeWithTag("fullbleed_token_count").assertDoesNotExist()
        composeTestRule.onNodeWithTag("fullbleed_tps").assertIsDisplayed()
    }

    @Test
    fun messageStats_allEnabled_showsUserTokensAssistantTokensAndTps() {
        render(
            messages =
                listOf(
                    msg("u1", MessageRole.USER, tokenCount = 123),
                    msg("a1", MessageRole.ASSISTANT, tokenCount = 456, tps = 42.5),
                ),
            messageStatsEnabled = true,
        )
        composeTestRule.onNodeWithTag("bubble_token_count").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fullbleed_token_count").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fullbleed_tps").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("fullbleed_agent_header").assertCountEquals(0)
        composeTestRule.onNodeWithTag("fullbleed_finish_time").assertDoesNotExist()
    }

    @Test
    fun messageStats_nullAndZeroValues_areHidden() {
        render(
            messages =
                listOf(
                    msg("u1", MessageRole.USER, tokenCount = 0),
                    msg("a1", MessageRole.ASSISTANT, tokenCount = 0, tps = 0.0),
                ),
            messageStatsEnabled = true,
        )
        composeTestRule.onNodeWithTag("bubble_token_count").assertDoesNotExist()
        composeTestRule.onNodeWithTag("fullbleed_token_count").assertDoesNotExist()
        composeTestRule.onNodeWithTag("fullbleed_tps").assertDoesNotExist()
    }

    @Test
    fun messageStats_streamingAssistant_hidesFinalMetadata() {
        render(
            messages = listOf(msg("u1", MessageRole.USER)),
            streamingMessage =
                msg(
                    "s1",
                    MessageRole.ASSISTANT,
                    content = "",
                    isStreaming = true,
                    tokenCount = 456,
                    tps = 42.5,
                ),
            isAgentTyping = true,
            messageStatsEnabled = true,
        )
        composeTestRule.onNodeWithTag("fullbleed_token_count").assertDoesNotExist()
        composeTestRule.onNodeWithTag("fullbleed_tps").assertDoesNotExist()
        composeTestRule.onNodeWithTag("typing_indicator").assertIsDisplayed()
    }

    @Test
    fun assistantStats_wrapInsideConstrainedWidth() {
        composeTestRule.setContent {
            Box(
                modifier = Modifier.width(220.dp).testTag("stats_host"),
            ) {
                FullBleedAgentMessage(
                    message = msg("a1", MessageRole.ASSISTANT, tokenCount = 123456789, tps = 9876.5),
                    messageStatsEnabled = true,
                )
            }
        }
        composeTestRule.waitForIdle()
        val host = composeTestRule.onNodeWithTag("stats_host").getUnclippedBoundsInRoot()
        val token = composeTestRule.onNodeWithTag("fullbleed_token_count").getUnclippedBoundsInRoot()
        val tps = composeTestRule.onNodeWithTag("fullbleed_tps").getUnclippedBoundsInRoot()
        assert(token.left >= host.left)
        assert(token.right <= host.right)
        assert(tps.left >= host.left)
        assert(tps.right <= host.right)
    }

    @Test
    fun streaming_and_clarify_stillRender() {
        render(
            messages = listOf(msg("u1", MessageRole.USER)),
            streamingMessage = msg("s1", MessageRole.ASSISTANT, content = "streaming", isStreaming = true),
            clarify = true,
        )
        composeTestRule.onNodeWithTag("fullbleed_agent_message").assertIsDisplayed()
        composeTestRule.onNodeWithTag("clarify_bubble").assertIsDisplayed()
    }

    @Test
    fun emptyStreamingMessage_showsTypingStatus_withoutEmptyAssistantProse() {
        render(
            messages = listOf(msg("u1", MessageRole.USER)),
            streamingMessage = msg("s1", MessageRole.ASSISTANT, content = "", isStreaming = true),
            isAgentTyping = true,
        )
        composeTestRule.onNodeWithTag("typing_indicator").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fullbleed_agent_message").assertDoesNotExist()
    }

    @Test
    fun reasoningStreaming_showsThinkingStatus_andKeepsReasoningCard() {
        val streaming =
            msg(
                "s1",
                MessageRole.ASSISTANT,
                content = "",
                isStreaming = true,
            ).copy(reasoningText = "step")
        render(
            messages = listOf(msg("u1", MessageRole.USER)),
            streamingMessage = streaming,
            streamingState =
                StreamingState(
                    streamingMessage = streaming,
                    isReasoning = true,
                    reasoningText = "step",
                ),
            isAgentTyping = true,
        )
        composeTestRule.onNodeWithTag("agent_status_thinking").assertIsDisplayed()
        composeTestRule.onNodeWithTag("reasoning_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("typing_indicator").assertDoesNotExist()
    }

    @Test
    fun runningTool_showsToolStatus_insteadOfTypingDots() {
        render(
            messages =
                listOf(
                    msg("u1", MessageRole.USER),
                    msg("t1", MessageRole.TOOL, toolStatus = ToolStatus.RUNNING).copy(toolName = "web_search"),
                ),
            isAgentTyping = true,
        )
        composeTestRule.onNodeWithTag("agent_status_tool").assertIsDisplayed()
        composeTestRule.onNodeWithText("Searching…").assertIsDisplayed()
        composeTestRule.onNodeWithTag("typing_indicator").assertDoesNotExist()
    }
}
