package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.data.ws.contract.ClarifyLockParams
import com.m57.hermescontrol.data.ws.contract.ClarifyLockResult
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatPromptProtocolTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun clarifyServerResponse_mergesLockedAnswers_withoutEmptyOverwrite() {
        val scope = TestScope(dispatcher)
        val uiState =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = "session-1",
                    clarifyRequest =
                        ClarifyUi(
                            text = "",
                            questions =
                                listOf(
                                    ClarifyQuestionUi(qid = "q0", question = "First?"),
                                    ClarifyQuestionUi(qid = "q1", question = "Second?"),
                                ),
                            serverRequestId = "srq-clarify",
                            lockedAnswers = mapOf("q0" to "yes"),
                        ),
                ),
            )
        var responseId: String? = null
        var response: JsonElement? = null

        val delegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = scope,
                ioDispatcher = dispatcher,
                persistMessage = { _, _ -> },
                respondToServerRequest = { id, result ->
                    responseId = id
                    response = result
                },
            )

        delegate.respondToClarifyBatch(mapOf("q0" to "", "q1" to "new answer"))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("srq-clarify", responseId)
        val answers = response?.jsonObject?.get("answers")?.jsonObject
        assertNotNull(answers)
        assertEquals("yes", answers?.get("q0")?.jsonPrimitive?.content)
        assertEquals("new answer", answers?.get("q1")?.jsonPrimitive?.content)
    }

    @Test
    fun canonicalClarifyBatch_answersViaClarifyLock_sequentially() {
        val mockWsClient = mockk<HermesWsClient>(relaxed = true)
        every { mockWsClient.canonicalClarifyBatch } returns true
        val locks = mutableListOf<ClarifyLockParams>()
        coEvery {
            mockWsClient.call(RpcMethods.CLARIFY_LOCK, any())
        } answers {
            val params = secondArg<ClarifyLockParams>()
            locks.add(params)
            ClarifyLockResult(status = "ok", remaining = emptyList())
        }

        val scope = TestScope(dispatcher)
        val uiState =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = "session-1",
                    clarifyRequest =
                        ClarifyUi(
                            text = "",
                            questions =
                                listOf(
                                    ClarifyQuestionUi(qid = "q0", question = "First?"),
                                    ClarifyQuestionUi(qid = "q1", question = "Second?"),
                                ),
                            serverRequestId = "srq-batch-1",
                            lockedAnswers = mapOf("q0" to "already-locked"),
                        ),
                ),
            )
        var respondedServerRequestId: String? = null

        val delegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = scope,
                ioDispatcher = dispatcher,
                persistMessage = { _, _ -> },
                wsClient = mockWsClient,
                respondToServerRequest = { id, _ -> respondedServerRequestId = id },
            )

        // q0 is already in lockedAnswers and answer map has empty placeholder (""), so q0 is skipped
        // and only unlocked question q1 is submitted with clarify.lock
        delegate.respondToClarifyBatch(mapOf("q0" to "", "q1" to "second-answer"))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, locks.size)
        assertEquals("srq-batch-1", locks[0].requestId)
        assertEquals("q1", locks[0].questionId)
        assertEquals("second-answer", locks[0].answer)

        // respondToServerRequest is not invoked when canonical clarify.lock handles it
        assertNull(respondedServerRequestId)
    }

    @Test
    fun canonicalClarifyBatch_lastLockSettlesEvenWhenResolvedCancelClearedCardFirst() {
        // #1779: the lock that empties `remaining` settles the request; the server's
        // `request.cancel {reason: resolved}` can clear the card before the lock reply lands.
        val mockWsClient = mockk<HermesWsClient>(relaxed = true)
        every { mockWsClient.canonicalClarifyBatch } returns true
        val scope = TestScope(dispatcher)
        val uiState =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = "session-1",
                    isAgentTyping = false,
                    clarifyRequest =
                        ClarifyUi(
                            text = "",
                            questions = listOf(ClarifyQuestionUi(qid = "q0", question = "First?")),
                            serverRequestId = "srq-race",
                        ),
                ),
            )
        coEvery { mockWsClient.call(RpcMethods.CLARIFY_LOCK, any()) } answers {
            uiState.value = uiState.value.copy(clarifyRequest = null, isAgentTyping = true)
            ClarifyLockResult(status = "ok", remaining = emptyList())
        }
        val settled = mutableListOf<String>()
        val persisted = mutableListOf<ChatMessage>()
        val delegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = scope,
                ioDispatcher = dispatcher,
                persistMessage = { msg, _ -> persisted.add(msg) },
                wsClient = mockWsClient,
                markClarifySettled = { settled.addAll(it) },
            )

        delegate.respondToClarifyBatch(mapOf("q0" to "yes"))
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(uiState.value.clarifyRequest)
        assertEquals(listOf("srq-race"), settled)
        assertEquals(
            DisplayKind.CLARIFY_RESPONSE,
            uiState.value.messages
                .last()
                .displayKind,
        )
        assertEquals(1, persisted.size)
    }

    @Test
    fun canonicalClarifyBatch_lastLockClearsCardAndMarksSettled() {
        val mockWsClient = mockk<HermesWsClient>(relaxed = true)
        every { mockWsClient.canonicalClarifyBatch } returns true
        coEvery {
            mockWsClient.call(RpcMethods.CLARIFY_LOCK, any())
        } returnsMany
            listOf(
                ClarifyLockResult(status = "ok", remaining = listOf("q1")),
                ClarifyLockResult(status = "ok", remaining = emptyList()),
            )
        val scope = TestScope(dispatcher)
        val uiState =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = "session-1",
                    clarifyRequest =
                        ClarifyUi(
                            text = "",
                            questions =
                                listOf(
                                    ClarifyQuestionUi(qid = "q0", question = "First?"),
                                    ClarifyQuestionUi(qid = "q1", question = "Second?"),
                                ),
                            serverRequestId = "srq-two",
                        ),
                ),
            )
        val settled = mutableListOf<String>()
        val delegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = scope,
                ioDispatcher = dispatcher,
                persistMessage = { _, _ -> },
                wsClient = mockWsClient,
                markClarifySettled = { settled.addAll(it) },
            )

        delegate.respondToClarifyBatch(mapOf("q0" to "a", "q1" to "b"))
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(uiState.value.clarifyRequest)
        assertEquals(listOf("srq-two"), settled)
    }

    @Test
    fun clarify1815_replayOfSameRequestInFlightIsClearedOnSettle() {
        // #1815: a replay that re-created the card mid-flight carries other id fields (or none).
        // The settle clears any card that names the same request, not only the original object.
        val mockWsClient = mockk<HermesWsClient>(relaxed = true)
        every { mockWsClient.canonicalClarifyBatch } returns true
        val scope = TestScope(dispatcher)
        val original =
            ClarifyUi(
                text = "",
                clarifyId = "prompt-1815",
                questions = listOf(ClarifyQuestionUi(qid = "q0", question = "First?")),
                serverRequestId = "srq-1815",
            )
        val uiState = MutableStateFlow(ChatUiState(currentSessionId = "session-1", clarifyRequest = original))
        coEvery { mockWsClient.call(RpcMethods.CLARIFY_LOCK, any()) } answers {
            // Replay keyed only by the prompt id, with no server request id.
            uiState.value = uiState.value.copy(clarifyRequest = original.copy(serverRequestId = null))
            ClarifyLockResult(status = "ok", remaining = emptyList())
        }
        val settled = mutableListOf<String>()
        val delegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = scope,
                ioDispatcher = dispatcher,
                persistMessage = { _, _ -> },
                wsClient = mockWsClient,
                markClarifySettled = { settled.addAll(it) },
            )

        delegate.respondToClarifyBatch(mapOf("q0" to "yes"))
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(uiState.value.clarifyRequest)
        assertEquals(setOf("srq-1815", "prompt-1815"), settled.toSet())
        assertEquals(1, uiState.value.messages.size)
    }

    @Test
    fun clarify1815_cardClearsOnSubmitAndComesBackOnlyOnFailure() {
        val mockWsClient = mockk<HermesWsClient>(relaxed = true)
        every { mockWsClient.canonicalClarifyBatch } returns true
        val scope = TestScope(dispatcher)
        val uiState =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = "session-1",
                    clarifyRequest =
                        ClarifyUi(
                            text = "",
                            questions = listOf(ClarifyQuestionUi(qid = "q0", question = "First?")),
                            serverRequestId = "srq-1815-fail",
                        ),
                ),
            )
        var cardDuringLock: ClarifyUi? = null
        var submittingDuringLock = false
        lateinit var delegate: ChatClarifyDelegate
        coEvery { mockWsClient.call(RpcMethods.CLARIFY_LOCK, any()) } answers {
            cardDuringLock = uiState.value.clarifyRequest
            submittingDuringLock = delegate.isSubmitting("srq-1815-fail")
            throw java.io.IOException("socket closed")
        }
        delegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = scope,
                ioDispatcher = dispatcher,
                persistMessage = { _, _ -> },
                wsClient = mockWsClient,
            )

        delegate.respondToClarifyBatch(mapOf("q0" to "yes"))
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(cardDuringLock)
        org.junit.Assert.assertTrue(submittingDuringLock)
        org.junit.Assert.assertFalse(delegate.isSubmitting("srq-1815-fail"))
        assertEquals("srq-1815-fail", uiState.value.clarifyRequest?.serverRequestId)
        assertEquals("socket closed", uiState.value.errorMessage)
        org.junit.Assert.assertFalse(uiState.value.isAgentTyping)
    }

    @Test
    fun clarify1824_answerEchoShowsOnSubmitBeforeTheLockSettles() {
        val mockWsClient = mockk<HermesWsClient>(relaxed = true)
        every { mockWsClient.canonicalClarifyBatch } returns true
        val scope = TestScope(dispatcher)
        val uiState =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = "session-1",
                    clarifyRequest =
                        ClarifyUi(
                            text = "",
                            questions = listOf(ClarifyQuestionUi(qid = "q0", question = "First?")),
                            serverRequestId = "srq-1824",
                        ),
                ),
            )
        var echoDuringLock: List<ChatMessage> = emptyList()
        coEvery { mockWsClient.call(RpcMethods.CLARIFY_LOCK, any()) } answers {
            echoDuringLock = uiState.value.messages
            ClarifyLockResult(status = "ok", remaining = emptyList())
        }
        val persisted = mutableListOf<ChatMessage>()
        val delegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = scope,
                ioDispatcher = dispatcher,
                persistMessage = { msg, _ -> persisted.add(msg) },
                wsClient = mockWsClient,
            )

        delegate.respondToClarifyBatch(mapOf("q0" to "yes"))

        // Visible at once, before any lock round-trip runs.
        val echo = uiState.value.messages.single()
        assertEquals(DisplayKind.CLARIFY_RESPONSE, echo.displayKind)
        assertEquals("yes", echo.content)
        assertNull(uiState.value.clarifyRequest)

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(echo.id), echoDuringLock.map { it.id })
        // One echo only: the settle path must not append it a second time.
        assertEquals(listOf(echo.id), uiState.value.messages.map { it.id })
        assertEquals(listOf(echo.id), persisted.map { it.id })
    }

    @Test
    fun clarify1824_answerEchoIsDroppedWhenTheLockFails() {
        val mockWsClient = mockk<HermesWsClient>(relaxed = true)
        every { mockWsClient.canonicalClarifyBatch } returns true
        coEvery { mockWsClient.call(RpcMethods.CLARIFY_LOCK, any()) } throws java.io.IOException("socket closed")
        val scope = TestScope(dispatcher)
        val uiState =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = "session-1",
                    clarifyRequest =
                        ClarifyUi(
                            text = "",
                            questions = listOf(ClarifyQuestionUi(qid = "q0", question = "First?")),
                            serverRequestId = "srq-1824-fail",
                        ),
                ),
            )
        val persisted = mutableListOf<ChatMessage>()
        val delegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = scope,
                ioDispatcher = dispatcher,
                persistMessage = { msg, _ -> persisted.add(msg) },
                wsClient = mockWsClient,
            )

        delegate.respondToClarifyBatch(mapOf("q0" to "yes"))
        assertEquals(1, uiState.value.messages.size)
        dispatcher.scheduler.advanceUntilIdle()

        org.junit.Assert.assertTrue(uiState.value.messages.isEmpty())
        org.junit.Assert.assertTrue(persisted.isEmpty())
        assertEquals("srq-1824-fail", uiState.value.clarifyRequest?.serverRequestId)
    }

    @Test
    fun clarify1824_answerEchoIsDroppedWhenTheRequestExpired() {
        val mockWsClient = mockk<HermesWsClient>(relaxed = true)
        every { mockWsClient.canonicalClarifyBatch } returns true
        coEvery { mockWsClient.call(RpcMethods.CLARIFY_LOCK, any()) } returns
            ClarifyLockResult(status = "expired", remaining = null)
        val scope = TestScope(dispatcher)
        val uiState =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = "session-1",
                    clarifyRequest =
                        ClarifyUi(
                            text = "",
                            questions = listOf(ClarifyQuestionUi(qid = "q0", question = "First?")),
                            serverRequestId = "srq-1824-expired",
                        ),
                ),
            )
        val persisted = mutableListOf<ChatMessage>()
        val delegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = scope,
                ioDispatcher = dispatcher,
                persistMessage = { msg, _ -> persisted.add(msg) },
                wsClient = mockWsClient,
            )

        delegate.respondToClarifyBatch(mapOf("q0" to "yes"))
        dispatcher.scheduler.advanceUntilIdle()

        org.junit.Assert.assertTrue(uiState.value.messages.isEmpty())
        org.junit.Assert.assertTrue(persisted.isEmpty())
        assertEquals("Clarify request expired", uiState.value.errorMessage)
    }

    @Test
    fun canonicalClarifyBatch_stopsWhenStatusExpired() {
        val mockWsClient = mockk<HermesWsClient>(relaxed = true)
        every { mockWsClient.canonicalClarifyBatch } returns true
        val locks = mutableListOf<ClarifyLockParams>()
        coEvery {
            mockWsClient.call(RpcMethods.CLARIFY_LOCK, any())
        } answers {
            val params = secondArg<ClarifyLockParams>()
            locks.add(params)
            ClarifyLockResult(status = "expired", remaining = null)
        }

        val scope = TestScope(dispatcher)
        val uiState =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = "session-1",
                    clarifyRequest =
                        ClarifyUi(
                            text = "",
                            questions =
                                listOf(
                                    ClarifyQuestionUi(qid = "q0", question = "First?"),
                                    ClarifyQuestionUi(qid = "q1", question = "Second?"),
                                ),
                            serverRequestId = "srq-batch-2",
                        ),
                ),
            )

        val delegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = scope,
                ioDispatcher = dispatcher,
                persistMessage = { _, _ -> },
                wsClient = mockWsClient,
            )

        delegate.respondToClarifyBatch(mapOf("q0" to "ans0", "q1" to "ans1"))
        dispatcher.scheduler.advanceUntilIdle()

        // The first lock returns expired; second lock should be skipped.
        assertEquals(1, locks.size)
        assertEquals("q0", locks[0].questionId)
    }

    @Test
    fun legacyClarifyBatchReplay_skipsLockedAnswers_withoutEmptyOverwrite() {
        val responses =
            buildLegacyClarifyBatchResponses(
                questions =
                    listOf(
                        ClarifyQuestionUi(qid = "q0", question = "Already answered?"),
                        ClarifyQuestionUi(qid = "q1", question = "Still waiting?"),
                        ClarifyQuestionUi(qid = "q2", question = "Optional skip?"),
                    ),
                answers =
                    mapOf(
                        "q0" to "",
                        "q1" to "  new answer  ",
                        "q2" to "",
                    ),
                lockedAnswers = mapOf("q0" to "yes"),
            )

        assertEquals(listOf("q1" to "new answer", "q2" to ""), responses)
    }

    @Test
    fun credentialPrompt_serverRequestId_controls_response_and_cancellation() {
        val scope = TestScope(dispatcher)
        val uiState = MutableStateFlow(ChatUiState(currentSessionId = "session-1"))
        val sentMethods = mutableListOf<String>()
        var responseId: String? = null
        var response: JsonElement? = null
        val delegate =
            ChatCredentialPromptsDelegate(
                scope = scope,
                ioDispatcher = dispatcher,
                uiState = uiState,
                wsSend = { method, _, _ -> sentMethods += method },
                trackRequest = { _, _ -> },
                respondToServerRequest = { id, result ->
                    responseId = id
                    response = result
                },
            )

        delegate.handleSudoRequest(
            WsEvent.SudoRequest(
                requestId = "legacy-sudo-id",
                sessionId = "session-1",
                serverRequestId = "srq-sudo",
            ),
        )
        delegate.handleSudoExpire(WsEvent.SudoExpire(null, "session-1", serverRequestId = "srq-other"))
        assertNotNull(uiState.value.sudoPrompt)

        delegate.respondToSudo("password")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("srq-sudo", responseId)
        assertEquals(
            "password",
            response
                ?.jsonObject
                ?.get("value")
                ?.jsonPrimitive
                ?.content,
        )
        assertNull(uiState.value.sudoPrompt)
        assertEquals(emptyList<String>(), sentMethods)

        delegate.handleSudoRequest(WsEvent.SudoRequest("legacy-sudo-2", "session-1"))
        delegate.respondToSudo("legacy-password")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(WsMethods.SUDO_RESPOND), sentMethods)
    }

    @Test
    fun secretAndVaultServerRequests_useValueResultPayloads() {
        val scope = TestScope(dispatcher)
        val uiState = MutableStateFlow(ChatUiState(currentSessionId = "session-1"))
        val responses = mutableListOf<Pair<String, JsonElement>>()
        val delegate =
            ChatCredentialPromptsDelegate(
                scope = scope,
                ioDispatcher = dispatcher,
                uiState = uiState,
                wsSend = { _, _, _ -> error("new prompt must not use legacy response") },
                trackRequest = { _, _ -> },
                respondToServerRequest = { id, result -> responses += id to result },
            )

        delegate.handleSecretRequest(WsEvent.SecretRequest(null, "session-1", serverRequestId = "srq-secret"))
        delegate.respondToSecret("secret-value")
        delegate.handleVaultCodeRequest(WsEvent.VaultCodeRequest(null, "session-1", serverRequestId = "srq-code"))
        delegate.respondToVaultCode("123456")
        delegate.handleVaultUnlockRequest(
            WsEvent.VaultUnlockRequest(null, "session-1", serverRequestId = "srq-unlock"),
        )
        delegate.respondToVaultUnlock("vault-password")
        delegate.handleVaultSaveLoginRequest(
            WsEvent.VaultSaveLoginRequest(null, "session-1", serverRequestId = "srq-save"),
        )
        delegate.respondToVaultSaveLogin("alice", "login-password")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("srq-secret", "srq-code", "srq-unlock", "srq-save"), responses.map { it.first })
        assertEquals(
            "secret-value",
            responses[0]
                .second.jsonObject["value"]
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "123456",
            responses[1]
                .second.jsonObject["value"]
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "vault-password",
            responses[2]
                .second.jsonObject["value"]
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "{\"identifier\":\"alice\",\"password\":\"login-password\"}",
            responses[3]
                .second.jsonObject["value"]
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun legacyCredentialPrompts_withoutServerRequestId_fallBackToRespondVerbs() {
        val scope = TestScope(dispatcher)
        val uiState = MutableStateFlow(ChatUiState(currentSessionId = "session-1"))
        val sent = mutableListOf<Pair<String, Map<String, Any>>>()
        val delegate =
            ChatCredentialPromptsDelegate(
                scope = scope,
                ioDispatcher = dispatcher,
                uiState = uiState,
                wsSend = { method, params, _ -> sent += method to params },
                trackRequest = { _, _ -> },
                respondToServerRequest = { _, _ -> error("legacy prompt must not use a same-id response") },
            )

        // Older gateways (no serverRequestId): answer and dismiss each prompt.
        delegate.handleSecretRequest(WsEvent.SecretRequest("sec-1", "session-1"))
        delegate.respondToSecret("secret-value")
        delegate.handleSecretRequest(WsEvent.SecretRequest("sec-2", "session-1"))
        delegate.dismissSecret()
        delegate.handleSudoRequest(WsEvent.SudoRequest("sudo-1", "session-1"))
        delegate.dismissSudo()
        delegate.handleVaultUnlockRequest(WsEvent.VaultUnlockRequest("unl-1", "session-1"))
        delegate.respondToVaultUnlock("vault-password")
        delegate.handleVaultSaveLoginRequest(WsEvent.VaultSaveLoginRequest("save-1", "session-1"))
        delegate.dismissVaultSaveLogin()
        delegate.handleVaultCodeRequest(WsEvent.VaultCodeRequest("code-1", "session-1"))
        delegate.respondToVaultCode("123456")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(
                WsMethods.SECRET_RESPOND,
                WsMethods.SECRET_RESPOND,
                WsMethods.SUDO_RESPOND,
                WsMethods.VAULT_UNLOCK_RESPOND,
                WsMethods.VAULT_SAVE_LOGIN_RESPOND,
                WsMethods.VAULT_CODE_RESPOND,
            ),
            sent.map { it.first },
        )
        assertEquals(
            listOf("sec-1", "sec-2", "sudo-1", "unl-1", "save-1", "code-1"),
            sent.map { it.second["request_id"] },
        )
        assertEquals("secret-value", sent[0].second["value"])
        assertEquals("", sent[1].second["value"])
        assertEquals("", sent[2].second["password"])
        assertEquals("vault-password", sent[3].second["password"])
        assertEquals("", sent[4].second["login"])
        assertEquals("123456", sent[5].second["code"])
    }

    @Test
    fun legacyRespondVerbs_keepTheirWireNames() {
        assertEquals("sudo.respond", WsMethods.SUDO_RESPOND)
        assertEquals("secret.respond", WsMethods.SECRET_RESPOND)
        assertEquals("vault.unlock.respond", WsMethods.VAULT_UNLOCK_RESPOND)
        assertEquals("vault.save_login.respond", WsMethods.VAULT_SAVE_LOGIN_RESPOND)
        assertEquals("vault.code.respond", WsMethods.VAULT_CODE_RESPOND)
    }

    @Test
    fun canonicalClarifyBatch_deterministicFailureAfterFirstAck_preservesAcceptedAnswersOnRetry() {
        val mockWsClient = mockk<HermesWsClient>(relaxed = true)
        every { mockWsClient.canonicalClarifyBatch } returns true
        val locks = mutableListOf<ClarifyLockParams>()
        var callCount = 0
        coEvery {
            mockWsClient.call(RpcMethods.CLARIFY_LOCK, any())
        } answers {
            callCount++
            val params = secondArg<ClarifyLockParams>()
            locks.add(params)
            if (callCount == 1) {
                ClarifyLockResult(status = "ok", remaining = listOf("q1"))
            } else {
                throw java.io.IOException("Network error on second lock")
            }
        }

        val scope = TestScope(dispatcher)
        val uiState =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = "session-1",
                    clarifyRequest =
                        ClarifyUi(
                            text = "",
                            questions =
                                listOf(
                                    ClarifyQuestionUi(qid = "q0", question = "First?"),
                                    ClarifyQuestionUi(qid = "q1", question = "Second?"),
                                ),
                            serverRequestId = "srq-batch-fail",
                        ),
                ),
            )

        val delegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = scope,
                ioDispatcher = dispatcher,
                persistMessage = { _, _ -> },
                wsClient = mockWsClient,
            )

        delegate.respondToClarifyBatch(mapOf("q0" to "ans0", "q1" to "ans1"))
        dispatcher.scheduler.advanceUntilIdle()

        val activeClarify = uiState.value.clarifyRequest
        assertNotNull(activeClarify)
        assertEquals("srq-batch-fail", activeClarify?.serverRequestId)
        assertEquals("ans0", activeClarify?.lockedAnswers?.get("q0"))
        assertEquals("Network error on second lock", uiState.value.errorMessage)
        org.junit.Assert.assertFalse(uiState.value.isAgentTyping)
        assertEquals(0, uiState.value.messages.size)

        coEvery {
            mockWsClient.call(RpcMethods.CLARIFY_LOCK, any())
        } answers {
            val params = secondArg<ClarifyLockParams>()
            locks.add(params)
            ClarifyLockResult(status = "ok", remaining = emptyList())
        }

        delegate.respondToClarifyBatch(mapOf("q1" to "ans1-retry"))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(3, locks.size)
        assertEquals("q1", locks[2].questionId)
        assertEquals("ans1-retry", locks[2].answer)

        assertNull(uiState.value.clarifyRequest)
        assertEquals(1, uiState.value.messages.size)
        org.junit.Assert.assertTrue(uiState.value.isAgentTyping)
    }

    @Test
    fun canonicalClarifyBatch_rethrowsCancellationException_withoutSwallowingIntoErrorMessage() {
        val mockWsClient = mockk<HermesWsClient>(relaxed = true)
        every { mockWsClient.canonicalClarifyBatch } returns true
        coEvery {
            mockWsClient.call(RpcMethods.CLARIFY_LOCK, any())
        } throws kotlinx.coroutines.CancellationException("Coroutines cancelled")

        val scope = TestScope(dispatcher)
        val uiState =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = "session-1",
                    clarifyRequest =
                        ClarifyUi(
                            text = "",
                            questions =
                                listOf(
                                    ClarifyQuestionUi(qid = "q0", question = "First?"),
                                ),
                            serverRequestId = "srq-batch-cancel",
                        ),
                ),
            )

        val delegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = scope,
                ioDispatcher = dispatcher,
                persistMessage = { _, _ -> },
                wsClient = mockWsClient,
            )

        delegate.respondToClarifyBatch(mapOf("q0" to "ans0"))
        dispatcher.scheduler.advanceUntilIdle()

        // CancellationException must NOT be caught by generic catch (which sets errorMessage)
        assertNull(uiState.value.errorMessage)
    }

    @Test
    fun canonicalClarifyBatch_scopeSwitch_abortsPendingLocks() {
        val mockWsClient = mockk<HermesWsClient>(relaxed = true)
        every { mockWsClient.canonicalClarifyBatch } returns true
        val locks = mutableListOf<ClarifyLockParams>()
        var scopeState: DataScope? =
            DataScope("ep1", "https://gw", "prof1")

        coEvery {
            mockWsClient.call(RpcMethods.CLARIFY_LOCK, any())
        } answers {
            locks.add(secondArg<ClarifyLockParams>())
            scopeState = DataScope("ep1", "https://gw", "prof2")
            ClarifyLockResult(status = "ok", remaining = listOf("q1"))
        }

        val scope = TestScope(dispatcher)
        val uiState =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = "session-1",
                    clarifyRequest =
                        ClarifyUi(
                            text = "",
                            questions =
                                listOf(
                                    ClarifyQuestionUi(qid = "q0", question = "First?"),
                                    ClarifyQuestionUi(qid = "q1", question = "Second?"),
                                ),
                            serverRequestId = "srq-batch-scope",
                        ),
                ),
            )

        val delegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = scope,
                ioDispatcher = dispatcher,
                persistMessage = { _, _ -> },
                wsClient = mockWsClient,
                currentDataScope = { scopeState },
            )

        delegate.respondToClarifyBatch(mapOf("q0" to "ans0", "q1" to "ans1"))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, locks.size)
        assertEquals("q0", locks[0].questionId)
        assertEquals(0, uiState.value.messages.size)
    }

    @Test
    fun canonicalClarifyBatch_requestReplacement_doesNotOverwriteNewerClarifyRequest() {
        val mockWsClient = mockk<HermesWsClient>(relaxed = true)
        every { mockWsClient.canonicalClarifyBatch } returns true

        val scope = TestScope(dispatcher)
        val uiState =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = "session-1",
                    clarifyRequest =
                        ClarifyUi(
                            text = "",
                            questions =
                                listOf(
                                    ClarifyQuestionUi(qid = "q0", question = "Old?"),
                                ),
                            serverRequestId = "srq-old",
                        ),
                ),
            )

        coEvery {
            mockWsClient.call(RpcMethods.CLARIFY_LOCK, any())
        } answers {
            uiState.value =
                uiState.value.copy(
                    clarifyRequest =
                        ClarifyUi(
                            text = "",
                            questions = listOf(ClarifyQuestionUi(qid = "qNew", question = "New?")),
                            serverRequestId = "srq-new",
                        ),
                )
            ClarifyLockResult(status = "ok", remaining = emptyList())
        }

        val delegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = scope,
                ioDispatcher = dispatcher,
                persistMessage = { _, _ -> },
                wsClient = mockWsClient,
            )

        delegate.respondToClarifyBatch(mapOf("q0" to "old-answer"))
        dispatcher.scheduler.advanceUntilIdle()

        val activeClarify = uiState.value.clarifyRequest
        assertNotNull(activeClarify)
        assertEquals("srq-new", activeClarify?.serverRequestId)
    }

    @Test
    fun canonicalClarifyBatch_doubleSubmit_abortsSecondSubmission() {
        val mockWsClient = mockk<HermesWsClient>(relaxed = true)
        every { mockWsClient.canonicalClarifyBatch } returns true
        val locks = mutableListOf<ClarifyLockParams>()

        coEvery {
            mockWsClient.call(RpcMethods.CLARIFY_LOCK, any())
        } coAnswers {
            locks.add(secondArg<ClarifyLockParams>())
            ClarifyLockResult(status = "ok", remaining = emptyList())
        }

        val scope = TestScope(dispatcher)
        val uiState =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = "session-1",
                    clarifyRequest =
                        ClarifyUi(
                            text = "",
                            questions = listOf(ClarifyQuestionUi(qid = "q0", question = "First?")),
                            serverRequestId = "srq-double",
                        ),
                ),
            )

        val delegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = scope,
                ioDispatcher = dispatcher,
                persistMessage = { _, _ -> },
                wsClient = mockWsClient,
            )

        // Double submit in rapid succession
        delegate.respondToClarifyBatch(mapOf("q0" to "ans1"))
        delegate.respondToClarifyBatch(mapOf("q0" to "ans2"))
        dispatcher.scheduler.advanceUntilIdle()

        // Exactly one call should be processed, not duplicate submissions
        assertEquals(1, locks.size)
        assertEquals("ans1", locks[0].answer)
    }

    @Test
    fun canonicalClarifyBatch_replacementBetweenAcks_abortsOldLoopAndDoesNotPersistOldAnswer() {
        val mockWsClient = mockk<HermesWsClient>(relaxed = true)
        every { mockWsClient.canonicalClarifyBatch } returns true
        val locks = mutableListOf<ClarifyLockParams>()
        var persisted = false

        val scope = TestScope(dispatcher)
        val uiState =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = "session-1",
                    clarifyRequest =
                        ClarifyUi(
                            text = "",
                            questions =
                                listOf(
                                    ClarifyQuestionUi(qid = "q0", question = "Old Q0?"),
                                    ClarifyQuestionUi(qid = "q1", question = "Old Q1?"),
                                ),
                            serverRequestId = "srq-replace-test",
                        ),
                ),
            )

        coEvery {
            mockWsClient.call(RpcMethods.CLARIFY_LOCK, any())
        } answers {
            val params = secondArg<ClarifyLockParams>()
            locks.add(params)
            // While q0 lock was in flight, a replacement clarify request arrives from server
            uiState.value =
                uiState.value.copy(
                    clarifyRequest =
                        ClarifyUi(
                            text = "",
                            questions = listOf(ClarifyQuestionUi(qid = "qNew", question = "New Q?")),
                            serverRequestId = "srq-new-arrived",
                        ),
                )
            ClarifyLockResult(status = "ok", remaining = listOf("q1"))
        }

        val delegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = scope,
                ioDispatcher = dispatcher,
                persistMessage = { _, _ -> persisted = true },
                wsClient = mockWsClient,
            )

        delegate.respondToClarifyBatch(mapOf("q0" to "ans0", "q1" to "ans1"))
        dispatcher.scheduler.advanceUntilIdle()

        // Only first lock q0 was made; after request replaced, subsequent old RPC q1 was NOT sent
        assertEquals(1, locks.size)
        assertEquals("q0", locks[0].questionId)
        // No message persistence for the replaced old request
        org.junit.Assert.assertFalse(persisted)
        // Active request remains the new one
        assertEquals("srq-new-arrived", uiState.value.clarifyRequest?.serverRequestId)
    }

    @Test
    fun canonicalClarifyBatch_scopeSwitchBeforeErrorOrExpired_doesNotMutateChangedScope() {
        val mockWsClient = mockk<HermesWsClient>(relaxed = true)
        every { mockWsClient.canonicalClarifyBatch } returns true
        var scopeState: DataScope? = DataScope("ep1", "https://gw", "prof1")

        val scope = TestScope(dispatcher)
        val uiState =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = "session-1",
                    clarifyRequest =
                        ClarifyUi(
                            text = "",
                            questions = listOf(ClarifyQuestionUi(qid = "q0", question = "Q0?")),
                            serverRequestId = "srq-scope-err",
                        ),
                ),
            )

        coEvery {
            mockWsClient.call(RpcMethods.CLARIFY_LOCK, any())
        } answers {
            // Scope switches while lock is in flight
            scopeState = DataScope("ep1", "https://gw", "prof2")
            ClarifyLockResult(status = "expired")
        }

        val delegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = scope,
                ioDispatcher = dispatcher,
                persistMessage = { _, _ -> },
                wsClient = mockWsClient,
                currentDataScope = { scopeState },
            )

        delegate.respondToClarifyBatch(mapOf("q0" to "ans0"))
        dispatcher.scheduler.advanceUntilIdle()

        // The card cleared on submit (#1815). Since scope changed, the expired result must
        // NOT touch the new scope/state: no error, and no card restored into it.
        assertNull(uiState.value.clarifyRequest)
        assertNull(uiState.value.errorMessage)
    }

    @Test
    fun canonicalClarifyBatch_nonemptyRemaining_doesNotClaimSettledSuccess() {
        val mockWsClient = mockk<HermesWsClient>(relaxed = true)
        every { mockWsClient.canonicalClarifyBatch } returns true
        var persisted = false

        coEvery {
            mockWsClient.call(RpcMethods.CLARIFY_LOCK, any())
        } answers {
            // Backend reports that qExtra is still remaining unanswered
            ClarifyLockResult(status = "ok", remaining = listOf("qExtra"))
        }

        val scope = TestScope(dispatcher)
        val uiState =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = "session-1",
                    clarifyRequest =
                        ClarifyUi(
                            text = "",
                            questions = listOf(ClarifyQuestionUi(qid = "q0", question = "Q0?")),
                            serverRequestId = "srq-remaining-test",
                        ),
                ),
            )

        val delegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = scope,
                ioDispatcher = dispatcher,
                persistMessage = { _, _ -> persisted = true },
                wsClient = mockWsClient,
            )

        delegate.respondToClarifyBatch(mapOf("q0" to "ans0"))
        dispatcher.scheduler.advanceUntilIdle()

        // Must not claim settled success:
        org.junit.Assert.assertFalse(persisted)
        assertEquals(0, uiState.value.messages.size)
        // Remains in clarifyRequest
        assertNotNull(uiState.value.clarifyRequest)
        assertEquals("Questions still remaining: qExtra", uiState.value.errorMessage)
        org.junit.Assert.assertFalse(uiState.value.isAgentTyping)
    }

    @Test
    fun canonicalClarifyBatch_cancellationAfterFirstAck_preservesAcceptedLocksImmediately() {
        val mockWsClient = mockk<HermesWsClient>(relaxed = true)
        every { mockWsClient.canonicalClarifyBatch } returns true
        var callCount = 0

        coEvery {
            mockWsClient.call(RpcMethods.CLARIFY_LOCK, any())
        } answers {
            callCount++
            if (callCount == 1) {
                ClarifyLockResult(status = "ok", remaining = listOf("q1"))
            } else {
                throw kotlinx.coroutines.CancellationException("Job cancelled after first lock")
            }
        }

        val scope = TestScope(dispatcher)
        val uiState =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = "session-1",
                    clarifyRequest =
                        ClarifyUi(
                            text = "",
                            questions =
                                listOf(
                                    ClarifyQuestionUi(qid = "q0", question = "First?"),
                                    ClarifyQuestionUi(qid = "q1", question = "Second?"),
                                ),
                            serverRequestId = "srq-cancel-ack",
                        ),
                ),
            )

        val delegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = scope,
                ioDispatcher = dispatcher,
                persistMessage = { _, _ -> },
                wsClient = mockWsClient,
            )

        delegate.respondToClarifyBatch(mapOf("q0" to "first-ack", "q1" to "second-ans"))
        dispatcher.scheduler.advanceUntilIdle()

        // Even though cancellation occurred on second call, q0 was committed immediately after its ok response!
        val activeClarify = uiState.value.clarifyRequest
        assertNotNull(activeClarify)
        assertEquals("first-ack", activeClarify?.lockedAnswers?.get("q0"))
        assertNull(uiState.value.errorMessage)
    }

    @Test
    fun canonicalClarifyBatch_cancelBeforeStartThenRetry_releasesInFlightReservation() {
        val mockWsClient = mockk<HermesWsClient>(relaxed = true)
        every { mockWsClient.canonicalClarifyBatch } returns true
        val locks = mutableListOf<ClarifyLockParams>()

        coEvery {
            mockWsClient.call(RpcMethods.CLARIFY_LOCK, any())
        } answers {
            locks.add(secondArg<ClarifyLockParams>())
            ClarifyLockResult(status = "ok", remaining = emptyList())
        }

        val testJob = kotlinx.coroutines.Job().apply { cancel() }
        val canceledScope = kotlinx.coroutines.CoroutineScope(dispatcher + testJob)

        val uiState =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = "session-1",
                    clarifyRequest =
                        ClarifyUi(
                            text = "",
                            questions = listOf(ClarifyQuestionUi(qid = "q0", question = "First?")),
                            serverRequestId = "srq-cancel-start",
                        ),
                ),
            )

        val delegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = canceledScope,
                ioDispatcher = dispatcher,
                persistMessage = { _, _ -> },
                wsClient = mockWsClient,
            )

        // First attempt on canceled scope
        delegate.respondToClarifyBatch(mapOf("q0" to "ans1"))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, locks.size)

        // Now retry with active scope
        val activeScope = TestScope(dispatcher)
        val retryDelegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = activeScope,
                ioDispatcher = dispatcher,
                persistMessage = { _, _ -> },
                wsClient = mockWsClient,
            )

        // Reservation must have been released by invokeOnCompletion, so retry succeeds!
        retryDelegate.respondToClarifyBatch(mapOf("q0" to "ans-retry"))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, locks.size)
        assertEquals("ans-retry", locks[0].answer)
    }

    @Test
    fun canonicalClarifyBatch_casConflict_doesNotCausePhantomPersist() {
        val mockWsClient = mockk<HermesWsClient>(relaxed = true)
        every { mockWsClient.canonicalClarifyBatch } returns true
        var persisted = false

        coEvery {
            mockWsClient.call(RpcMethods.CLARIFY_LOCK, any())
        } answers {
            ClarifyLockResult(status = "ok", remaining = emptyList())
        }

        val scope = TestScope(dispatcher)
        val originalClarify =
            ClarifyUi(
                text = "",
                questions = listOf(ClarifyQuestionUi(qid = "q0", question = "First?")),
                serverRequestId = "srq-cas-conflict",
            )
        val uiState =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = "session-1",
                    clarifyRequest = originalClarify,
                ),
            )

        // Spy on uiState to inject a concurrent CAS change that swaps out the clarifyRequest
        // right when compareAndSet is about to execute!
        val delegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = scope,
                ioDispatcher = dispatcher,
                persistMessage = { _, _ -> persisted = true },
                wsClient = mockWsClient,
            )

        // Before coroutine commits settlement, swap out the request
        delegate.respondToClarifyBatch(mapOf("q0" to "answer"))
        // Simulate concurrent state replacement right before advance:
        uiState.value =
            uiState.value.copy(
                clarifyRequest =
                    ClarifyUi(
                        text = "",
                        questions = listOf(ClarifyQuestionUi(qid = "qOther", question = "Replaced?")),
                        serverRequestId = "srq-different",
                    ),
            )

        dispatcher.scheduler.advanceUntilIdle()

        // Because clarifyRequest was replaced, CAS loop fails matching request guard and aborts without persisting phantom message!
        org.junit.Assert.assertFalse(persisted)
        assertEquals(0, uiState.value.messages.size)
        assertEquals("srq-different", uiState.value.clarifyRequest?.serverRequestId)
    }

    @Test
    fun canonicalClarifyBatch_allAlreadyLocked_probesBackendForSettlementConfirmation() {
        val mockWsClient = mockk<HermesWsClient>(relaxed = true)
        every { mockWsClient.canonicalClarifyBatch } returns true
        val locks = mutableListOf<ClarifyLockParams>()
        var persisted = false

        coEvery {
            mockWsClient.call(RpcMethods.CLARIFY_LOCK, any())
        } answers {
            locks.add(secondArg<ClarifyLockParams>())
            // Server confirms that remaining is empty
            ClarifyLockResult(status = "ok", remaining = emptyList())
        }

        val scope = TestScope(dispatcher)
        val uiState =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = "session-1",
                    clarifyRequest =
                        ClarifyUi(
                            text = "",
                            questions =
                                listOf(
                                    ClarifyQuestionUi(qid = "q0", question = "First?"),
                                    ClarifyQuestionUi(qid = "q1", question = "Second?"),
                                ),
                            serverRequestId = "srq-all-locked",
                            lockedAnswers = mapOf("q0" to "ans0", "q1" to "ans1"),
                        ),
                ),
            )

        val delegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = scope,
                ioDispatcher = dispatcher,
                persistMessage = { _, _ -> persisted = true },
                wsClient = mockWsClient,
            )

        // Pass empty answers so all questions match already-locked state
        delegate.respondToClarifyBatch(emptyMap())
        dispatcher.scheduler.advanceUntilIdle()

        // Probe call was sent to backend to confirm server settlement!
        assertEquals(1, locks.size)
        assertEquals("q0", locks[0].questionId)
        assertEquals("ans0", locks[0].answer)
        // Settlement confirmed by server response, so message is persisted!
        org.junit.Assert.assertTrue(persisted)
        assertNull(uiState.value.clarifyRequest)
        assertEquals(1, uiState.value.messages.size)
    }
}
