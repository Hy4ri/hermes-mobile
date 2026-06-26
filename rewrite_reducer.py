import re

path = 'app/src/main/java/com/m57/hermescontrol/ui/chat/ChatWsEventReducer.kt'
with open(path, 'r') as f:
    text = f.read()

# 1. Update reduce signature
text = text.replace(
"""    fun reduce(
        state: ChatUiState,
        event: WsEvent,
        currentSessionId: String?,
    ): ReducerResult {""",
"""    fun reduce(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent,
        currentSessionId: String?,
    ): ReducerResult {"""
)

text = text.replace(
"""data class ReducerResult(
    val state: ChatUiState,
    val effects: List<ReducerEffect> = emptyList(),
)""",
"""data class ReducerResult(
    val state: ChatUiState,
    val streamingState: StreamingState,
    val effects: List<ReducerEffect> = emptyList(),
)"""
)

# 2. Add 'streamingState = streamingState,' to all ReducerResult constructors where not present
# Actually let's just use regex to add streamingState if it's missing in ReducerResult(...)
def repl_result(m):
    inner = m.group(1)
    if 'streamingState' not in inner:
        return f"ReducerResult(\n            streamingState = streamingState,{inner})"
    return m.group(0)

# Replace simple ReducerResult(state)
text = text.replace("return ReducerResult(state)", "return ReducerResult(state = state, streamingState = streamingState)")
text = text.replace("return ReducerResult(newState)", "return ReducerResult(state = newState, streamingState = streamingState)")
text = text.replace("return ReducerResult(state, effects)", "return ReducerResult(state = state, streamingState = streamingState, effects = effects)")
text = text.replace("return ReducerResult(newState, effects)", "return ReducerResult(state = newState, streamingState = streamingState, effects = effects)")

# 3. Replace state.copy(...) for streaming properties
# We need to extract them from state.copy and put them in streamingState
# This is tricky with regex. Let's write out the known occurrences.

# In onMessageStart
text = text.replace("state.streamingMessage", "streamingState.streamingMessage")

# Replace large blocks
text = re.sub(
r"""                state\.copy\(\n                    messages = state\.messages \+ finalized,\n                    streamingMessage = null,\n                    isAgentTyping = true,\n                    isThinking = false,\n                    thinkingText = "",\n                \)""",
r"""                state.copy(
                    messages = state.messages + finalized,
                    isAgentTyping = true,
                )""", text
)

text = re.sub(
r"""                state\.copy\(\n                    isAgentTyping = true,\n                    isThinking = false,\n                    thinkingText = "",\n                \)""",
r"""                state.copy(
                    isAgentTyping = true,
                )""", text
)

text = re.sub(
r"""        val newState = preState\.copy\(streamingMessage = msg\)""",
r"""        val newState = preState
        val newStreamingState = streamingState.copy(
            streamingMessage = msg,
            isThinking = false,
            thinkingText = ""
        )""", text
)
text = text.replace("ReducerResult(newState, effects)", "ReducerResult(state = newState, streamingState = newStreamingState, effects = effects)")


# In onMessageComplete
text = re.sub(
r"""                state\.copy\(\n                    messages = state\.messages \+ msg,\n                    streamingMessage = null,\n                    isAgentTyping = false,\n                    isThinking = false,\n                    thinkingText = "",\n                \)""",
r"""                state.copy(
                    messages = state.messages + msg,
                    isAgentTyping = false,
                )""", text
)

text = re.sub(
r"""        return ReducerResult\(\n            state =\n                state\.copy\(\n                    messages = state\.messages \+ msg,\n                    isAgentTyping = false,\n                \),\n            effects = effects,\n        \)""",
r"""        return ReducerResult(
            state = state.copy(
                messages = state.messages + msg,
                isAgentTyping = false,
            ),
            streamingState = StreamingState(),
            effects = effects,
        )""", text
)


# In onMessageDone
text = re.sub(
r"""        return ReducerResult\(\n            state =\n                state\.copy\(\n                    messages = state\.messages \+ msg,\n                    streamingMessage = null,\n                    isAgentTyping = false,\n                    isThinking = false,\n                    thinkingText = "",\n                \),\n            effects = effects,\n        \)""",
r"""        return ReducerResult(
            state = state.copy(
                messages = state.messages + msg,
                isAgentTyping = false,
            ),
            streamingState = StreamingState(),
            effects = effects,
        )""", text
)

# In onClarifyRequest
text = re.sub(
r"""                state\.copy\(\n                    messages = state\.messages \+ finalized,\n                    streamingMessage = null,\n                \)""",
r"""                state.copy(
                    messages = state.messages + finalized,
                )""", text
)

text = re.sub(
r"""                state\.copy\(streamingMessage = null\)""",
r"""                state""", text
)

text = re.sub(
r"""        return ReducerResult\(\n            state = newState\.copy\(messages = newState\.messages \+ clarifyMsg\),\n            effects = effects,\n        \)""",
r"""        return ReducerResult(
            state = newState.copy(messages = newState.messages + clarifyMsg),
            streamingState = StreamingState(),
            effects = effects,
        )""", text
)

# finalizer logic
text = re.sub(
r"""    private fun finalizeStreamingMessage\(\n        state: ChatUiState,\n        newMessage: ChatMessage,\n    \): Pair<ChatUiState, ChatMessage\?> \{""",
r"""    private fun finalizeStreamingMessage(
        state: ChatUiState,
        streamingState: StreamingState,
        newMessage: ChatMessage,
    ): Triple<ChatUiState, StreamingState, ChatMessage?> {""", text
)

text = re.sub(
r"""        if \(existing != null && existing\.content\.isNotEmpty\(\)\) \{\n            val finalized = existing\.copy\(isStreaming = false\)\n            return Pair\(\n                state\.copy\(\n                    messages = state\.messages \+ finalized,\n                    streamingMessage = newMessage,\n                \),\n                finalized,\n            \)\n        \}\n        return Pair\(\n            state\.copy\(streamingMessage = newMessage\),\n            null,\n        \)""",
r"""        if (existing != null && existing.content.isNotEmpty()) {
            val finalized = existing.copy(isStreaming = false)
            return Triple(
                state.copy(messages = state.messages + finalized),
                streamingState.copy(streamingMessage = newMessage),
                finalized,
            )
        }
        return Triple(
            state,
            streamingState.copy(streamingMessage = newMessage),
            null,
        )""", text
)

# onThinkingDelta (wait, this isn't in Reducer, or is it? No, it's not)
# onMessageToken (not in Reducer)

with open(path, 'w') as f:
    f.write(text)

