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
import java.util.concurrent.ConcurrentHashMap

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
    /**
     * Single-flight tracking for canonical clarify batch submissions keyed by
     * `(connection/profile scope, sessionId, serverRequestId)`.
     */
    private data class InFlightKey(
        val scope: DataScope?,
        val sessionId: String,
        val serverRequestId: String,
    )

    private val inFlightCanonicalBatch = ConcurrentHashMap.newKeySet<InFlightKey>()

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
            val inFlightKey = InFlightKey(initialScope, sessionId, serverRequestId)
            if (!inFlightCanonicalBatch.add(inFlightKey)) {
                // Single-flight guard: a submission for this exact request in this scope is already in flight.
                return
            }

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

            val job =
                scope.launch(ioDispatcher) {
                    var lastRemaining: List<String>? = null
                    var callsMade = 0
                    try {
                        for (q in questions) {
                            // Scope/session/request identity guard BEFORE each call:
                            val currentSessionBefore = uiState.value.currentSessionId
                            val activeScopeBefore = currentDataScope()
                            val currentRequestIdBefore = uiState.value.clarifyRequest?.serverRequestId
                            if (currentSessionBefore != sessionId ||
                                activeScopeBefore != initialScope ||
                                currentRequestIdBefore != serverRequestId
                            ) {
                                return@launch
                            }

                            // Skip already-locked rows unless a truly changed nonblank answer is provided
                            val existingLocked = lockedAnswers[q.qid]
                            val rawAnswer = finalAnswers[q.qid]
                            val hasNewNonBlank = answers[q.qid]?.trim()?.isNotEmpty() == true
                            if (existingLocked != null && (!hasNewNonBlank || rawAnswer == existingLocked)) {
                                continue
                            }

                            callsMade++
                            val lockResult =
                                wsClient.call(
                                    RpcMethods.CLARIFY_LOCK,
                                    ClarifyLockParams(
                                        requestId = serverRequestId,
                                        questionId = q.qid,
                                        answer = rawAnswer,
                                    ),
                                )

                            // Scope/session/request identity guard AFTER suspension before state updates:
                            val currentSessionAfter = uiState.value.currentSessionId
                            val activeScopeAfter = currentDataScope()
                            val currentRequestIdAfter = uiState.value.clarifyRequest?.serverRequestId
                            if (currentSessionAfter != sessionId ||
                                activeScopeAfter != initialScope ||
                                currentRequestIdAfter != serverRequestId
                            ) {
                                return@launch
                            }

                            when (lockResult.status) {
                                "ok" -> {
                                    val acceptedAnswer = rawAnswer.orEmpty()
                                    lastRemaining = lockResult.remaining
                                    // Update accepted locks immediately after each successful response under identity guard:
                                    uiState.update { current ->
                                        if (current.currentSessionId == sessionId &&
                                            currentDataScope() == initialScope &&
                                            current.clarifyRequest?.serverRequestId == serverRequestId
                                        ) {
                                            val req = current.clarifyRequest
                                            val updatedLocked = req.lockedAnswers + (q.qid to acceptedAnswer)
                                            current.copy(clarifyRequest = req.copy(lockedAnswers = updatedLocked))
                                        } else {
                                            current
                                        }
                                    }
                                }

                                "expired" -> {
                                    uiState.update { current ->
                                        if (current.currentSessionId == sessionId &&
                                            currentDataScope() == initialScope &&
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
                                    throw IllegalStateException("Unexpected clarify.lock status: ${lockResult.status}")
                                }
                            }
                        }

                        // Settlement verification:
                        // 1. If backend reported remaining questions, request is not settled yet.
                        if (lastRemaining != null && lastRemaining.isNotEmpty()) {
                            uiState.update { current ->
                                if (current.currentSessionId == sessionId &&
                                    currentDataScope() == initialScope &&
                                    current.clarifyRequest?.serverRequestId == serverRequestId
                                ) {
                                    current.copy(
                                        isAgentTyping = false,
                                        errorMessage = "Questions still remaining: ${lastRemaining.joinToString(", ")}",
                                    )
                                } else {
                                    current
                                }
                            }
                            return@launch
                        }

                        // 2. If all questions were already locked locally and no RPC was made,
                        // do not infer completion from absence of calls without backend confirmation.
                        if (callsMade == 0) {
                            val probeQ = questions.firstOrNull()
                            if (probeQ != null) {
                                val probeAns = finalAnswers[probeQ.qid]
                                val probeResult =
                                    wsClient.call(
                                        RpcMethods.CLARIFY_LOCK,
                                        ClarifyLockParams(
                                            requestId = serverRequestId,
                                            questionId = probeQ.qid,
                                            answer = probeAns,
                                        ),
                                    )

                                if (currentDataScope() != initialScope ||
                                    uiState.value.currentSessionId != sessionId ||
                                    uiState.value.clarifyRequest?.serverRequestId != serverRequestId
                                ) {
                                    return@launch
                                }

                                when (probeResult.status) {
                                    "expired" -> {
                                        // The batch already ended on backend
                                        uiState.update { current ->
                                            if (current.currentSessionId == sessionId &&
                                                currentDataScope() == initialScope &&
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

                                    "ok" -> {
                                        if (probeResult.remaining?.isNotEmpty() == true) {
                                            uiState.update { current ->
                                                if (current.currentSessionId == sessionId &&
                                                    currentDataScope() == initialScope &&
                                                    current.clarifyRequest?.serverRequestId == serverRequestId
                                                ) {
                                                    val rem = probeResult.remaining.joinToString(", ")
                                                    current.copy(
                                                        isAgentTyping = false,
                                                        errorMessage = "Questions still remaining: $rem",
                                                    )
                                                } else {
                                                    current
                                                }
                                            }
                                            return@launch
                                        }
                                        // Empty remaining confirmed by server!
                                    }

                                    else -> {
                                        throw IllegalStateException(
                                            "Unexpected clarify.lock probe status: ${probeResult.status}",
                                        )
                                    }
                                }
                            }
                        }

                        // All locks settled and confirmed by server!
                        // Atomic compare-and-set loop without side-effects inside lambda:
                        var committed = false
                        while (true) {
                            val current = uiState.value
                            if (current.currentSessionId != sessionId ||
                                currentDataScope() != initialScope ||
                                current.clarifyRequest?.serverRequestId != serverRequestId
                            ) {
                                break
                            }
                            val next =
                                current.copy(
                                    clarifyRequest = null,
                                    messages = current.messages + userMessage,
                                    isAgentTyping = true,
                                )
                            if (uiState.compareAndSet(current, next)) {
                                committed = true
                                break
                            }
                        }

                        if (committed) {
                            persistMessage(userMessage, sessionId)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        uiState.update { current ->
                            if (current.currentSessionId == sessionId &&
                                currentDataScope() == initialScope &&
                                current.clarifyRequest?.serverRequestId == serverRequestId
                            ) {
                                current.copy(
                                    isAgentTyping = false,
                                    errorMessage = e.message ?: "Failed to lock clarify answer",
                                )
                            } else {
                                current
                            }
                        }
                    }
                }

            // Guaranteed single-flight cleanup even if scope is canceled before body begins:
            job.invokeOnCompletion {
                inFlightCanonicalBatch.remove(inFlightKey)
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
