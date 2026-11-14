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

/**
 * Bounded, thread-safe record of clarify request ids that are answered or closed
 * (#1779). Oldest ids drop first; ids are unique per request, so a small window
 * covers every replay a live session can still deliver.
 */
internal class SettledClarifyIds(
    private val capacity: Int = 256,
) {
    private val ids = LinkedHashSet<String>()

    @Synchronized
    fun add(id: String) {
        ids.remove(id)
        ids.add(id)
        while (ids.size > capacity) ids.remove(ids.first())
    }

    @Synchronized
    fun addAll(values: Collection<String>) = values.forEach(::add)

    @Synchronized
    operator fun contains(id: String): Boolean = id in ids
}

/**
 * Canonical owner prompts (`clarify.request` with `prompt_id` + `execution_generation`)
 * accept exactly `{session_id, execution_generation, prompt_id, answer}`; any other key
 * is refused as `invalid_params`. Returns null for legacy gateways (#1779).
 */
internal fun canonicalClarifyRespondParams(
    fallbackSessionId: String,
    clarify: ClarifyUi?,
    answer: String,
): Map<String, Any>? {
    val generation = clarify?.executionGeneration ?: return null
    val promptId = clarify.clarifyId ?: return null
    return mapOf(
        "session_id" to (clarify.sessionId ?: fallbackSessionId),
        "execution_generation" to generation,
        "prompt_id" to promptId,
        "answer" to answer,
    )
}

class ChatClarifyDelegate(
    private val uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val persistMessage: suspend (ChatMessage, String) -> Unit,
    private val wsClient: HermesWsClient = HermesWsClient,
    private val trackRequest: (String, String) -> Unit = { _, _ -> },
    private val respondToServerRequest: ((String, JsonElement) -> Unit)? = null,
    private val currentDataScope: () -> DataScope? = { runCatching { AuthManager.currentDataScope() }.getOrNull() },
    /** Records request ids that are answered or closed, so replays never re-show them (#1779). */
    private val markClarifySettled: (Collection<String>) -> Unit = {},
    /** True when a request id is already answered or closed (#1815). */
    private val isClarifySettled: (String) -> Boolean = { false },
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

    /** Ids of canonical batch requests whose answers are being locked right now (#1815). */
    private val submittingIds = ConcurrentHashMap.newKeySet<String>()

    /** True while this request's answers are in flight: a replay of it must not show the card again. */
    fun isSubmitting(id: String): Boolean = id in submittingIds

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

        if (clarify != null && serverRequestId != null && wsClient.canonicalClarifyBatch && isBatch) {
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

            // Clear the card and show the answer on submit, as Desktop does (#1815, #1824). The
            // echo goes in with the same update: an append after the lock round-trip races the
            // resumed turn's stream and can be lost. While the locks are in flight, replays of
            // this request stay hidden ([isSubmitting]). A failure restores the card and drops
            // the echo.
            val requestIds = clarify.requestIds
            submittingIds.addAll(requestIds)
            var card: ClarifyUi = clarify
            uiState.update { current ->
                if (current.currentSessionId == sessionId && current.clarifyRequest?.requestIds == requestIds) {
                    current.copy(
                        messages = current.messages + userMessage,
                        clarifyRequest = null,
                        isAgentTyping = true,
                        errorMessage = null,
                    )
                } else {
                    current
                }
            }
            // Set once the server confirms every answer; until then the echo is provisional.
            var accepted = false

            // Remove the provisional echo: the server did not take this answer.
            fun dropEcho() {
                uiState.update { current ->
                    if (current.messages.any { it.id == userMessage.id }) {
                        current.copy(messages = current.messages.filterNot { it.id == userMessage.id })
                    } else {
                        current
                    }
                }
            }

            // True while this session and scope are active and no other request is on screen.
            fun stillCurrent(): Boolean {
                val current = uiState.value
                if (current.currentSessionId != sessionId || currentDataScope() != initialScope) return false
                val onScreen = current.clarifyRequest ?: return true
                return onScreen.requestIds.any { it in requestIds }
            }

            // Put the card back after a failure, unless the request closed meanwhile or another
            // card took its place.
            fun restoreCard(errorMessage: String?) {
                if (requestIds.any(isClarifySettled)) return
                uiState.update { current ->
                    if (current.currentSessionId == sessionId &&
                        currentDataScope() == initialScope &&
                        (current.clarifyRequest == null || current.clarifyRequest.requestIds.any { it in requestIds })
                    ) {
                        current.copy(
                            clarifyRequest = card,
                            isAgentTyping = false,
                            errorMessage = errorMessage ?: current.errorMessage,
                        )
                    } else {
                        current
                    }
                }
            }

            // The request has ended on the server: no replay may show it again (#1779).
            fun settle() {
                markClarifySettled(requestIds)
                uiState.update { current ->
                    if (current.clarifyRequest?.requestIds?.any { it in requestIds } == true) {
                        current.copy(clarifyRequest = null)
                    } else {
                        current
                    }
                }
            }

            val job =
                scope.launch(ioDispatcher) {
                    var lastRemaining: List<String>? = null
                    var callsMade = 0
                    try {
                        for (q in questions) {
                            if (!stillCurrent()) return@launch

                            // Skip already-locked rows unless a truly changed nonblank answer is provided
                            val existingLocked = card.lockedAnswers[q.qid]
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

                            // Session/scope guard AFTER suspension. The card is already gone, and the
                            // lock that settles the batch races `request.cancel {resolved}` (#1779).
                            if (uiState.value.currentSessionId != sessionId || currentDataScope() != initialScope) {
                                return@launch
                            }

                            when (lockResult.status) {
                                "ok" -> {
                                    lastRemaining = lockResult.remaining
                                    card =
                                        card.copy(lockedAnswers = card.lockedAnswers + (q.qid to rawAnswer.orEmpty()))
                                }

                                "expired" -> {
                                    settle()
                                    uiState.update { current ->
                                        current.copy(isAgentTyping = false, errorMessage = "Clarify request expired")
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
                            restoreCard("Questions still remaining: ${lastRemaining.joinToString(", ")}")
                            return@launch
                        }

                        // 2. If all questions were already locked locally and no RPC was made,
                        // do not infer completion from absence of calls without backend confirmation.
                        if (callsMade == 0) {
                            val probeQ = questions.firstOrNull()
                            if (probeQ != null) {
                                val probeResult =
                                    wsClient.call(
                                        RpcMethods.CLARIFY_LOCK,
                                        ClarifyLockParams(
                                            requestId = serverRequestId,
                                            questionId = probeQ.qid,
                                            answer = finalAnswers[probeQ.qid],
                                        ),
                                    )

                                if (currentDataScope() != initialScope || uiState.value.currentSessionId != sessionId) {
                                    return@launch
                                }

                                when (probeResult.status) {
                                    "expired" -> {
                                        // The batch already ended on backend
                                        settle()
                                        uiState.update { current ->
                                            current.copy(
                                                isAgentTyping = false,
                                                errorMessage = "Clarify request expired",
                                            )
                                        }
                                        return@launch
                                    }

                                    "ok" -> {
                                        if (probeResult.remaining?.isNotEmpty() == true) {
                                            restoreCard(
                                                "Questions still remaining: ${probeResult.remaining.joinToString(
                                                    ", ",
                                                )}",
                                            )
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

                        // All locks settled and confirmed by server: the request is closed even
                        // when a racing `request.cancel` already removed the card (#1779).
                        settle()
                        accepted = true
                        // The echo is already on screen since submit; store it now that it is final.
                        if (uiState.value.currentSessionId == sessionId && currentDataScope() == initialScope) {
                            persistMessage(userMessage, sessionId)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        restoreCard(e.message ?: "Failed to lock clarify answer")
                    }
                }
            // Guaranteed single-flight cleanup even if scope is canceled before body begins:
            job.invokeOnCompletion { cause ->
                inFlightCanonicalBatch.remove(inFlightKey)
                submittingIds.removeAll(requestIds)
                if (!accepted) dropEcho()
                // Cancelled (also before it started): give the card back so it can be answered.
                if (cause is CancellationException) restoreCard(errorMessage = null)
            }
            return
        }

        // ── Fallback for non-canonical / older gateways ──────────────────────────
        clarify?.let { markClarifySettled(it.requestIds) }
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
                // Canonical owner prompts take exactly their own params (#1779).
                val params =
                    canonicalClarifyRespondParams(sessionId, clarify, ans.trim())
                        ?: buildMap {
                            put("session_id", sessionId)
                            put("response", ans)
                            put("answer", ans)
                            if (clarifyId != null) {
                                put("clarify_id", clarifyId)
                                put("request_id", clarifyId)
                            }
                            if (qid != null) put("question_id", qid)
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
