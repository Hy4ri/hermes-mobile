package com.m57.hermescontrol.ui.bots.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class GroupChatUiState(
    val groupName: String = "",
    val members: List<ProfileInfo> = emptyList(),
    val messages: List<GroupChatMessage> = emptyList(),
    val activeSpeaker: String? = null,
    val isLoading: Boolean = true,
    val inputText: String = "",
    val errorMessage: String? = null,
)

class GroupChatViewModel(
    private val groupName: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow(GroupChatUiState(groupName = groupName))
    val uiState: StateFlow<GroupChatUiState> = _uiState.asStateFlow()

    init {
        loadGroup()
    }

    fun loadGroup() {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = safeApiCall { ApiClient.hermesApi.getProfiles() }
            if (result is NetworkResult.Success) {
                val allProfiles = result.data.profiles
                val groupMembers =
                    allProfiles.filter {
                        it.botMeta()?.groups.orEmpty().contains(groupName)
                    }
                _uiState.update {
                    it.copy(
                        members = groupMembers,
                        isLoading = false,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load group members",
                    )
                }
            }
        }
    }

    fun onInputTextChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank()) return

        val members = _uiState.value.members
        if (members.isEmpty()) return

        val userMessage =
            GroupChatMessage(
                id = UUID.randomUUID().toString(),
                senderName = "user",
                senderDisplayName = "You",
                isUser = true,
                text = text,
            )

        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                inputText = "",
            )
        }

        viewModelScope.launch(ioDispatcher) {
            val responders = GroupChatMentions.resolveResponders(text, members)
            for (bot in responders) {
                _uiState.update { it.copy(activeSpeaker = bot.effectiveTitle) }
                val prompt =
                    GroupChatMentions.buildTurnPrompt(
                        groupName = groupName,
                        viewer = bot,
                        peers = members.filter { it.name != bot.name },
                        recentLog = _uiState.value.messages,
                    )

                try {
                    val sessionId = bot.canonical_session?.resolved_id ?: bot.canonical_session?.id
                    val params =
                        buildMap<String, Any> {
                            put("text", prompt)
                            if (!sessionId.isNullOrBlank()) {
                                put("session_id", sessionId)
                            }
                        }

                    val deferred =
                        HermesWsClient.request(
                            method = WsMethods.PROMPT_SUBMIT,
                            params = params,
                        )
                    val response = deferred.await()
                    val replyText = extractReplyText(response)

                    if (replyText != null && !isPass(replyText)) {
                        val botMessage =
                            GroupChatMessage(
                                id = UUID.randomUUID().toString(),
                                senderName = bot.name,
                                senderDisplayName = bot.effectiveTitle,
                                isUser = false,
                                avatarMeta = bot.botMeta()?.avatar,
                                text = replyText,
                            )
                        _uiState.update { it.copy(messages = it.messages + botMessage) }
                    }
                } catch (_: Exception) {
                    // Turn failed or timed out, continue to next responder
                }
            }
            _uiState.update { it.copy(activeSpeaker = null) }
        }
    }

    private fun isPass(text: String): Boolean {
        val clean = text.trim().lowercase()
        return clean == "(pass)" || clean == "pass"
    }

    private fun extractReplyText(response: Any?): String? {
        if (response == null) return null
        if (response is String) return response
        if (response is Map<*, *>) {
            val res = response["result"] ?: response["message"] ?: response["text"]
            return res?.toString()
        }
        return response.toString()
    }
}
