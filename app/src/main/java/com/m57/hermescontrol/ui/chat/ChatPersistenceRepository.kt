package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.ChatMessageDao
import com.m57.hermescontrol.data.local.toEntity
import com.m57.hermescontrol.data.local.toUiModel
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChatPersistenceRepository(
    private val dao: ChatMessageDao,
) {
    suspend fun loadCachedMessages(sessionId: String): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            dao.getMessagesForSession(sessionId).map { it.toUiModel() }
        }
    }

    suspend fun saveMessage(
        message: ChatMessage,
        sessionId: String,
    ) {
        withContext(Dispatchers.IO) {
            dao.upsert(message.toEntity(sessionId))
        }
    }

    suspend fun saveMessages(
        messages: List<ChatMessage>,
        sessionId: String,
    ) {
        withContext(Dispatchers.IO) {
            dao.upsertAll(messages.map { it.toEntity(sessionId) })
        }
    }

    suspend fun fetchSessionMessages(sessionId: String): NetworkResult<List<ChatMessage>> {
        val result =
            withContext(Dispatchers.IO) {
                safeApiCall { ApiClient.hermesApi.getSessionMessages(sessionId) }
            }

        return when (result) {
            is NetworkResult.Success -> {
                val messagesList = result.data.messages.orEmpty()
                val chatMessages =
                    messagesList.mapIndexed { index, msg ->
                        val role =
                            when (msg.role?.lowercase()) {
                                "user" -> MessageRole.USER
                                "system" -> MessageRole.SYSTEM
                                "tool" -> MessageRole.TOOL
                                else -> MessageRole.ASSISTANT
                            }
                        val stableId = "rest-$sessionId-$index"
                        val ts = msg.timestamp?.toLongOrNull() ?: System.currentTimeMillis()
                        ChatMessage(
                            id = stableId,
                            role = role,
                            content = msg.content.orEmpty(),
                            timestamp = ts,
                            isStreaming = false,
                        )
                    }

                NetworkResult.Success(chatMessages)
            }
            is NetworkResult.Failure -> {
                NetworkResult.Failure(result.error)
            }
        }
    }
}
