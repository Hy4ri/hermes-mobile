import re
import os

path = 'app/src/main/java/com/m57/hermescontrol/ui/chat/ChatWsEventReducer.kt'
with open(path, 'r') as f:
    code = f.read()

# 1. Update ReducerResult
code = code.replace(
    'data class ReducerResult(\n    val state: ChatUiState,\n    val effects: List<ReducerEffect> = emptyList(),\n)',
    'data class ReducerResult(\n    val state: ChatUiState,\n    val streamingState: StreamingState,\n    val effects: List<ReducerEffect> = emptyList(),\n)'
)

# 2. Update reduce signature
code = code.replace(
    'fun reduce(\n        state: ChatUiState,\n        event: WsEvent,\n        currentSessionId: String?,\n    ): ReducerResult {',
    'fun reduce(\n        state: ChatUiState,\n        streamingState: StreamingState,\n        event: WsEvent,\n        currentSessionId: String?,\n    ): ReducerResult {'
)

# 3. Update all return ReducerResult(...) and state.copy inside reducer
# It's easier to just replace state.streamingMessage with streamingState.streamingMessage
code = code.replace('state.streamingMessage', 'streamingState.streamingMessage')

# We need to change how the reducer returns. Let's just do it manually with sed or string manipulation.
