package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatStreamingControllerTest {
    @Test
    fun flushPendingReasoning_flushesThrottledReasoningOnTransition() =
        runTest {
            // isTestEnvironment = false so the ~33ms throttle is live. The FIRST delta
            // always flushes (lastFlushMs == 0), but a SECOND delta within 33ms is held
            // in the buffer and only pushed out by flushPendingReasoning() (called at
            // streaming transitions) — that is the exact drop we guard against.
            val uiState = MutableStateFlow(ChatUiState())
            val streamingState =
                MutableStateFlow(
                    StreamingState(
                        streamingMessage =
                            ChatMessage(
                                role = MessageRole.ASSISTANT,
                                content = "",
                                isStreaming = true,
                            ),
                    ),
                )
            val controller = controller(this, uiState, streamingState, isTestEnvironment = { false })

            controller.handleReasoningDelta(WsEvent.ReasoningDelta("A", "session"))
            // First delta flushes immediately.
            assertEquals("A", streamingState.value.streamingMessage?.reasoningText)

            // Second delta arrives <33ms later -> throttled, still buffered, not yet shown.
            controller.handleReasoningDelta(WsEvent.ReasoningDelta("B", "session"))
            assertEquals("A", streamingState.value.streamingMessage?.reasoningText)

            // A streaming transition forces the buffered reasoning onto the message.
            controller.flushPendingReasoning()

            assertEquals("AB", streamingState.value.streamingMessage?.reasoningText)
        }

    @Test
    fun flushPendingTokens_flushesThrottledTokensBeforeSeal() =
        runTest {
            // Issue #842: a delta landing <33ms before tool.start stays in the
            // throttled buffer; flushing the tokens before the reducer seals the
            // orphan must push the COMPLETE narration onto the message.
            val uiState = MutableStateFlow(ChatUiState())
            val streamingState =
                MutableStateFlow(
                    StreamingState(
                        streamingMessage =
                            ChatMessage(
                                role = MessageRole.ASSISTANT,
                                content = "",
                                isStreaming = true,
                            ),
                    ),
                )
            val controller = controller(this, uiState, streamingState, isTestEnvironment = { false })

            controller.handleMessageToken(WsEvent.MessageToken("tool's loaded! 🔍 now searchin'", "session"))
            // First delta flushes immediately.
            assertEquals("tool's loaded! 🔍 now searchin'", streamingState.value.streamingMessage?.content)

            // Second delta arrives <33ms later -> throttled, still buffered.
            controller.handleMessageToken(WsEvent.MessageToken(" for the best hummus", "session"))
            assertEquals("tool's loaded! 🔍 now searchin'", streamingState.value.streamingMessage?.content)

            // The tool.start transition forces the buffered tokens out so the
            // sealed orphan carries the complete narration.
            controller.flushPendingTokens()

            assertEquals(
                "tool's loaded! 🔍 now searchin' for the best hummus",
                streamingState.value.streamingMessage?.content,
            )
        }

    @Test
    fun resetStreaming_clearsReasoningBufferSoStaleReasoningDoesNotResurrect() =
        runTest {
            val uiState = MutableStateFlow(ChatUiState())
            val streamingState = MutableStateFlow(StreamingState())
            val controller = controller(this, uiState, streamingState, isTestEnvironment = { false })

            // Buffer some reasoning, then reset (e.g. on MessageStart of a new turn).
            controller.handleReasoningDelta(WsEvent.ReasoningDelta("Stale reasoning", "session"))
            controller.resetStreaming()

            // A fresh reasoning delta + flush must carry ONLY the new value, proving the
            // stale buffer was cleared and cannot be resurrected.
            controller.handleReasoningDelta(WsEvent.ReasoningDelta("Fresh reasoning", "session"))
            controller.flushPendingReasoning()

            assertEquals("Fresh reasoning", streamingState.value.reasoningText)
        }

    @Test
    fun resetStreaming_clearsStreamingReasoningState() =
        runTest {
            // Issue #755: resetStreaming must ALSO clear the state-level
            // reasoning (text + flag) so the next message can never inherit it.
            val uiState = MutableStateFlow(ChatUiState())
            val streamingState =
                MutableStateFlow(
                    StreamingState(
                        isReasoning = true,
                        reasoningText = "Stale reasoning",
                    ),
                )
            val controller = controller(this, uiState, streamingState, isTestEnvironment = { false })

            controller.resetStreaming()

            assertEquals("", streamingState.value.reasoningText)
            assertEquals(false, streamingState.value.isReasoning)
        }

    @Test
    fun resetStreaming_doesNotClearReasoningOnTheFinalizedMessage() =
        runTest {
            // The finalized message keeps its own reasoning copy (persisted to
            // Room) — only the shared streaming state is reset. This preserves
            // "reasoning card persists after streaming".
            val uiState = MutableStateFlow(ChatUiState())
            val streamingState =
                MutableStateFlow(
                    StreamingState(
                        streamingMessage =
                            ChatMessage(
                                role = MessageRole.ASSISTANT,
                                content = "final answer",
                                reasoningText = "real reasoning trace",
                                isStreaming = false,
                            ),
                        isReasoning = true,
                        reasoningText = "real reasoning trace",
                    ),
                )
            val controller = controller(this, uiState, streamingState, isTestEnvironment = { false })

            controller.resetStreaming()

            assertEquals("real reasoning trace", streamingState.value.streamingMessage?.reasoningText)
        }

    private fun controller(
        scope: CoroutineScope,
        uiState: MutableStateFlow<ChatUiState>,
        streamingState: MutableStateFlow<StreamingState>,
        isTestEnvironment: () -> Boolean,
    ) = ChatStreamingController(
        scope = scope,
        uiState = uiState,
        streamingState = streamingState,
        isCurrentSession = { true },
        isTestEnvironment = isTestEnvironment,
    )
}
