import re

path = 'app/src/main/java/com/m57/hermescontrol/ui/chat/ChatViewModel.kt'
with open(path, 'r') as f:
    text = f.read()

# Fix ChatUiState duplicated isAgentTyping and missing isLoading
text = text.replace(
"""    val isAgentTyping: Boolean = false,
    val isAgentTyping: Boolean = false,
    val errorMessage: String? = null,""",
"""    val isAgentTyping: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,"""
)

# Also there might be another occurrence from the diff block applying strangely.
text = re.sub(
r"""    val isAgentTyping: Boolean = false,\n    val isAgentTyping: Boolean = false,""",
r"""    val isAgentTyping: Boolean = false,\n    val isLoading: Boolean = false,""", text
)

# Now, update ChatWsEventReducer.reduce call
text = text.replace(
"""        val result = ChatWsEventReducer.reduce(_uiState.value, event, _uiState.value.currentSessionId)""",
"""        val result = ChatWsEventReducer.reduce(_uiState.value, _streamingState.value, event, _uiState.value.currentSessionId)"""
)

text = text.replace(
"""        _uiState.update { result.state }""",
"""        _uiState.update { result.state }
        _streamingState.value = result.streamingState"""
)

# Now update the handleMessageToken logic!
# Currently:
# _uiState.update { state ->
#     val current = state.streamingMessage
#     if (current != null) {
#         state.copy(
#             streamingMessage =
#                 current.copy(
#                     content = currentContent,
#                     isThinking = false,
#                 ),
#         )
#     } else {
#         state.copy(
#             streamingMessage = msg,
#             isThinking = false,
#         )
#     }
# }

text = re.sub(
r"""            _uiState\.update \{ state ->\n                val current = state\.streamingMessage\n                if \(current != null\) \{\n                    state\.copy\(\n                        streamingMessage =\n                            current\.copy\(\n                                content = currentContent,\n                                isThinking = false,\n                            \),\n                    \)\n                \} else \{\n                    state\.copy\(\n                        streamingMessage = msg,\n                        isThinking = false,\n                    \)\n                \}\n            \}""",
r"""            _streamingState.update { sState ->
                val current = sState.streamingMessage
                if (current != null) {
                    sState.copy(
                        streamingMessage =
                            current.copy(
                                content = currentContent,
                                isThinking = false,
                            ),
                    )
                } else {
                    sState.copy(
                        streamingMessage = msg,
                        isThinking = false,
                    )
                }
            }""", text
)

# _uiState.update { it.copy(isThinking = true, thinkingText = currentContent) }
text = re.sub(
r"""            _uiState\.update \{ it\.copy\(isThinking = true, thinkingText = currentContent\) \}""",
r"""            _streamingState.update { it.copy(isThinking = true, thinkingText = currentContent) }""", text
)

# Any other usages of streamingMessage inside _uiState.update?
text = re.sub(
r"""                    _uiState\.update \{\n                        it\.copy\(\n                            streamingMessage = null,\n                        \)\n                    \}""",
r"""                    _streamingState.update {
                        it.copy(
                            streamingMessage = null,
                        )
                    }""", text
)

text = re.sub(
r"""                    _uiState\.update \{\n                        it\.copy\(\n                            isThinking = false,\n                            streamingMessage = null,\n                        \)\n                    \}""",
r"""                    _streamingState.update {
                        it.copy(
                            isThinking = false,
                            streamingMessage = null,
                        )
                    }""", text
)

text = re.sub(
r"""                _uiState\.update \{\n                    it\.copy\(\n                        streamingMessage = null,\n                    \)\n                \}""",
r"""                _streamingState.update {
                    it.copy(
                        streamingMessage = null,
                    )
                }""", text
)

text = re.sub(
r"""                _uiState\.update \{\n                    it\.copy\(\n                        isThinking = false,\n                        thinkingText = "",\n                    \)\n                \}""",
r"""                _streamingState.update {
                    it.copy(
                        isThinking = false,
                        thinkingText = "",
                    )
                }""", text
)

# And missing import for update
if "import kotlinx.coroutines.flow.update" not in text:
    text = text.replace("import kotlinx.coroutines.flow.asStateFlow", "import kotlinx.coroutines.flow.asStateFlow\nimport kotlinx.coroutines.flow.update")

with open(path, 'w') as f:
    f.write(text)

