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
