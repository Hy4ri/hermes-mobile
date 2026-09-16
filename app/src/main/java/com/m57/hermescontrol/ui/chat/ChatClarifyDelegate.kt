package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ChatClarifyDelegate(
    private val uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val persistMessage: suspend (ChatMessage, String) -> Unit,
    private val wsClient: HermesWsClient = HermesWsClient,
    private val trackRequest: (String, String) -> Unit = { _, _ -> },
    private val respondToServerRequest: ((String, JsonElement) -> Unit)? = null,
) {
    fun respondToClarify(option: String) {
        val clarify = uiState.value.clarifyRequest
        // Only use synthesized qid when this is a true batch or legacy with explicit qid.
        val qid =
            if (clarify != null &&
                (clarify.questionId != null || clarify.questions.isNotEmpty())
            ) {
                clarify.resolvedQuestions.firstOrNull()?.qid ?: clarify.questionId
            } else {
                null
            }
        if (qid != null && clarify?.resolvedQuestions?.size == 1) {
            respondToClarifyBatch(mapOf(qid to option))
        } else {
            respondToClarifyBatch(emptyMap(), singleFallbackAnswer = option)
        }
    }

    fun respondToClarifyBatch(
        answers: Map<String, String>,
        singleFallbackAnswer: String? = null,
    ) {
        val sessionId = uiState.value.currentSessionId ?: return
        val clarify = uiState.value.clarifyRequest
        val clarifyId = clarify?.clarifyId
        val serverRequestId = clarify?.serverRequestId
        val lockedAnswers = clarify?.lockedAnswers.orEmpty()
        val isBatch = !clarify?.questions.isNullOrEmpty()
        val questions = clarify?.resolvedQuestions.orEmpty()
        uiState.update { it.copy(clarifyRequest = null) }

        val displayContent =
            if (questions.size > 1) {
                questions
                    .mapIndexed { index, q ->
                        val ans =
                            answers[q.qid]?.trim()?.takeIf { it.isNotEmpty() }
                                ?: lockedAnswers[q.qid].orEmpty()
                        "${index + 1}. ${ans.ifEmpty { "(Skipped)" }}"
                    }.joinToString("\n")
            } else {
                val loneAns = answers.values.firstOrNull()?.trim() ?: singleFallbackAnswer?.trim().orEmpty()
                loneAns
            }

        val userMessage =
            ChatMessage(
                role = MessageRole.USER,
                content = displayContent,
            )

        uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                isAgentTyping = true,
            )
        }

        scope.launch(ioDispatcher) {
            persistMessage(userMessage, sessionId)
        }

        scope.launch(ioDispatcher) {
            if (serverRequestId != null && respondToServerRequest != null) {
                val finalAnswers =
                    buildMap {
                        putAll(lockedAnswers)
                        answers.forEach { (qid, answer) ->
                            answer.trim().takeIf { it.isNotEmpty() }?.let { put(qid, it) }
                        }
                    }
                val result =
                    if (isBatch) {
                        buildJsonObject {
                            put(
                                "answers",
                                buildJsonObject {
                                    for ((qid, answer) in finalAnswers) {
                                        put(qid, answer)
                                    }
                                },
                            )
                        }
                    } else {
                        buildJsonObject {
                            put("answer", answers.values.firstOrNull()?.trim() ?: singleFallbackAnswer.orEmpty())
                        }
                    }
                respondToServerRequest.invoke(serverRequestId, result)
                return@launch
            }

            if (isBatch) {
                for (q in questions) {
                    val ans = answers[q.qid]?.trim().orEmpty()
                    val params =
                        mutableMapOf<String, Any>(
                            "session_id" to sessionId,
                            "response" to ans,
                            "answer" to ans,
                            "question_id" to q.qid,
                        )
                    if (clarifyId != null) {
                        params["clarify_id"] = clarifyId
                        params["request_id"] = clarifyId
                    }
                    wsClient.send(
                        method = WsMethods.CLARIFY_RESPOND,
                        params = params,
                        onSent = { id -> trackRequest(id, WsMethods.CLARIFY_RESPOND) },
                    )
                }
            } else {
                // Legacy single: only include question_id when explicitly present.
                val qid = clarify?.questionId
                val ans = answers.values.firstOrNull() ?: singleFallbackAnswer.orEmpty()
                val params =
                    mutableMapOf<String, Any>(
                        "session_id" to sessionId,
                        "response" to ans,
                        "answer" to ans,
                    )
                if (clarifyId != null) {
                    params["clarify_id"] = clarifyId
                    params["request_id"] = clarifyId
                }
                if (qid != null) {
                    params["question_id"] = qid
                }
                wsClient.send(
                    method = WsMethods.CLARIFY_RESPOND,
                    params = params,
                    onSent = { id -> trackRequest(id, WsMethods.CLARIFY_RESPOND) },
                )
            }
        }
    }
}
