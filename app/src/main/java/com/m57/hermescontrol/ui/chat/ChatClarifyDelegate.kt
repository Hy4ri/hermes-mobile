package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.data.ws.contract.ClarifyLockParams
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun buildLegacyClarifyBatchResponses(
    questions: List<ClarifyQuestionUi>,
    answers: Map<String, String>,
    lockedAnswers: Map<String, String>,
): List<Pair<String, String>> =
    questions
        .asSequence()
        // A replayed pending_clarify can contain answers already locked by the
        // server. ClarifyBubble has no editable input for those rows, so its
        // answer map carries an empty placeholder. Never send that placeholder
        // back through legacy clarify.respond and overwrite accepted state.
        .filterNot { it.qid in lockedAnswers }
        .map { it.qid to answers[it.qid]?.trim().orEmpty() }
        .toList()

class ChatClarifyDelegate(
    private val uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val persistMessage: suspend (ChatMessage, String) -> Unit,
    private val wsClient: HermesWsClient = HermesWsClient,
    private val trackRequest: (String, String) -> Unit = { _, _ -> },
    private val respondToServerRequest: ((String, JsonElement) -> Unit)? = null,
    private val currentDataScope: () -> DataScope? = { runCatching { AuthManager.currentDataScope() }.getOrNull() },
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
        val initialScope = currentDataScope()

        if (serverRequestId != null && wsClient.canonicalClarifyBatch && isBatch) {
            val finalAnswers =
                buildMap {
                    putAll(lockedAnswers)
                    answers.forEach { (qid, answer) ->
                        answer.trim().takeIf { it.isNotEmpty() }?.let { put(qid, it) }
                    }
                }

            val displayContent =
                if (questions.size > 1) {
                    questions
                        .mapIndexed { index, q ->
                            val ans = finalAnswers[q.qid].orEmpty()
                            "${index + 1}. ${ans.ifEmpty { "(Skipped)" }}"
                        }.joinToString("\n")
                } else {
                    val loneAns = finalAnswers.values.firstOrNull() ?: singleFallbackAnswer?.trim().orEmpty()
                    loneAns
                }

            val userMessage =
                ChatMessage(
                    role = MessageRole.USER,
                    content = displayContent,
                    displayKind = DisplayKind.CLARIFY_RESPONSE,
                )

            scope.launch(ioDispatcher) {
                val acknowledgedLocks = mutableMapOf<String, String>()
                try {
                    for (q in questions) {
                        // Scope/session guard before each call:
                        // Ensure session and profile/connection scope have not changed.
                        val currentSession = uiState.value.currentSessionId
                        val activeScope = currentDataScope()
                        if (currentSession != sessionId || activeScope != initialScope) {
                            return@launch
                        }

                        // If already locked on server, skip re-locking unless a new answer is provided
                        if (q.qid in lockedAnswers && q.qid !in answers) {
                            acknowledgedLocks[q.qid] = lockedAnswers[q.qid].orEmpty()
                            continue
                        }

                        val rawAnswer = finalAnswers[q.qid]
                        val lockResult =
                            wsClient.call(
                                RpcMethods.CLARIFY_LOCK,
                                ClarifyLockParams(
                                    requestId = serverRequestId,
                                    questionId = q.qid,
                                    answer = rawAnswer,
                                ),
                            )

                        when (lockResult.status) {
                            "ok" -> {
                                acknowledgedLocks[q.qid] = rawAnswer.orEmpty()
                            }

                            "expired" -> {
                                uiState.update { current ->
                                    if (current.currentSessionId == sessionId &&
                                        current.clarifyRequest?.serverRequestId == serverRequestId
                                    ) {
                                        current.copy(
                                            clarifyRequest = null,
                                            isAgentTyping = false,
                                            errorMessage = "Clarify request expired",
                                        )
                                    } else {
                                        current
                                    }
                                }
                                return@launch
                            }

                            else -> {
                                // Unknown status is rejected as an error, preserving acknowledged locks
                                throw IllegalStateException("Unexpected clarify.lock status: ${lockResult.status}")
                            }
                        }
                    }

                    // All questions locked successfully!
                    val sessionStillMatches = uiState.value.currentSessionId == sessionId
                    val activeScope = currentDataScope()
                    if (sessionStillMatches && activeScope == initialScope) {
                        uiState.update { current ->
                            if (current.currentSessionId == sessionId &&
                                current.clarifyRequest?.serverRequestId == serverRequestId
                            ) {
                                current.copy(
                                    clarifyRequest = null,
                                    messages = current.messages + userMessage,
                                    isAgentTyping = true,
                                )
                            } else {
                                current
                            }
                        }
                        persistMessage(userMessage, sessionId)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    uiState.update { current ->
                        if (current.currentSessionId == sessionId &&
                            current.clarifyRequest?.serverRequestId == serverRequestId
                        ) {
                            val updatedLocked = current.clarifyRequest.lockedAnswers + acknowledgedLocks
                            current.copy(
                                clarifyRequest = current.clarifyRequest.copy(lockedAnswers = updatedLocked),
                                isAgentTyping = false,
                                errorMessage = e.message ?: "Failed to lock clarify answer",
                            )
                        } else {
                            current
                        }
                    }
                }
            }
            return
        }

        // ── Fallback for non-canonical / older gateways ──────────────────────────
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
                displayKind = DisplayKind.CLARIFY_RESPONSE,
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
                for ((questionId, ans) in buildLegacyClarifyBatchResponses(
                    questions = questions,
                    answers = answers,
                    lockedAnswers = lockedAnswers,
                )) {
                    val params =
                        mutableMapOf<String, Any>(
                            "session_id" to sessionId,
                            "response" to ans,
                            "answer" to ans,
                            "question_id" to questionId,
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
