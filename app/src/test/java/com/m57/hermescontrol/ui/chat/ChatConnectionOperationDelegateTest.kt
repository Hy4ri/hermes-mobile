package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.ConnectionEnvField
import com.m57.hermescontrol.data.model.ConnectionOperationSnapshot
import com.m57.hermescontrol.data.model.ConnectionOperationTarget
import com.m57.hermescontrol.data.model.ConnectionTargetAction
import com.m57.hermescontrol.data.model.ConnectionTargetKind
import com.m57.hermescontrol.data.model.ConnectionTargetState
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.contract.ConnectionAnswer
import com.m57.hermescontrol.data.ws.contract.ConnectionAnswerTarget
import com.m57.hermescontrol.data.ws.contract.ConnectionRespondParams
import com.m57.hermescontrol.data.ws.contract.ConnectorOwner
import com.m57.hermescontrol.data.ws.contract.ConnectorsOperationStatusParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatConnectionOperationDelegateTest {
    @Test
    fun requestAndUpdate_areStrictlyOrderedAndCorrelated() {
        val delegate = delegate()
        delegate.reset("session-a")

        assertTrue(delegate.acceptRequest(snapshot(opId = "op-a", seq = 1L)))
        assertFalse(delegate.acceptUpdate(snapshot(opId = "op-a", seq = 1L)))
        assertFalse(delegate.acceptUpdate(snapshot(opId = "op-old", seq = 99L)))
        assertEquals(
            "op-a",
            delegate.state.value.operation
                ?.opId,
        )
        assertTrue(delegate.acceptUpdate(snapshot(opId = "op-a", seq = 2L)))
        assertEquals(
            2L,
            delegate.state.value.operation
                ?.seq,
        )

        assertTrue(delegate.acceptRequest(snapshot(opId = "op-b", seq = 1L)))
        assertFalse(delegate.acceptUpdate(snapshot(opId = "op-a", seq = 3L)))
        assertEquals(
            "op-b",
            delegate.state.value.operation
                ?.opId,
        )
    }

    @Test
    fun wrongSessionAndSettledOperation_areNeverReopened() {
        val delegate = delegate()
        delegate.reset("session-a")

        assertFalse(delegate.acceptRequest(snapshot(sessionId = "session-b")))
        assertTrue(delegate.acceptRequest(snapshot(opId = "op-a", seq = 1L)))
        assertTrue(delegate.acceptUpdate(snapshot(opId = "op-a", seq = 2L, settled = true)))
        assertNull(delegate.state.value.operation)
        assertFalse(delegate.acceptRequest(snapshot(opId = "op-a", seq = 3L)))
    }

    @Test
    fun resumeReconciliation_cannotRegressOrClearNewerLiveRequest() {
        val delegate = delegate()
        delegate.reset("session-a")
        delegate.acceptRequest(snapshot(opId = "op-a", seq = 2L))
        val checkpoint = delegate.resumeCheckpoint()

        delegate.acceptRequest(snapshot(opId = "op-b", seq = 1L))

        assertFalse(delegate.reconcileResume(null, checkpoint))
        assertFalse(delegate.reconcileResume(snapshot(opId = "op-a", seq = 2L), checkpoint))
        assertEquals(
            "op-b",
            delegate.state.value.operation
                ?.opId,
        )
    }

    @Test
    fun absentResumeSnapshot_clearsOnlyUnchangedOperation() {
        val delegate = delegate()
        delegate.reset("session-a")
        delegate.acceptRequest(snapshot(opId = "op-a", seq = 2L))
        val checkpoint = delegate.resumeCheckpoint()

        assertTrue(delegate.reconcileResume(null, checkpoint))
        assertNull(delegate.state.value.operation)
    }

    @Test
    fun approvePayload_isExactAndSecretsNeverEnterUiState() =
        runTest {
            val requester = RecordingRequester()
            val delegate = delegate(requester)
            delegate.reset("session-a")
            delegate.acceptRequest(snapshot(requiredEnv = listOf(envField("TOKEN", secret = true))))
            val canary = "issue1218-secret-canary"

            delegate.respond(
                target = "github",
                env = mapOf("TOKEN" to canary, "UNDECLARED" to "drop-me"),
                approved = true,
            )

            assertEquals(1, requester.calls.size)
            val action = requester.calls.single()
            assertTrue(action is ConnectionOperationRequest.Respond)
            val respond = action as ConnectionOperationRequest.Respond

            assertEquals(
                ConnectionRespondParams(
                    owner = ConnectorOwner.session("session-a"),
                    opId = "op-a",
                    result =
                        ConnectionAnswer(
                            targets =
                                listOf(
                                    ConnectionAnswerTarget(
                                        name = "github",
                                        status = "approved",
                                        env = mapOf("TOKEN" to canary),
                                    ),
                                ),
                        ),
                ),
                respond.params,
            )

            val encoded = OkHttpProvider.json.encodeToJsonElement(ConnectionRespondParams.serializer(), respond.params)
            val expectedWire =
                buildJsonObject {
                    put(
                        "owner",
                        buildJsonObject {
                            put("type", "session")
                            put("session_id", "session-a")
                        },
                    )
                    put("op_id", "op-a")
                    put(
                        "result",
                        buildJsonObject {
                            put(
                                "targets",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("name", "github")
                                            put("status", "approved")
                                            put(
                                                "env",
                                                buildJsonObject {
                                                    put("TOKEN", canary)
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                }
            assertEquals(expectedWire, encoded)

            assertFalse(
                delegate.state.value
                    .toString()
                    .contains(canary),
            )
            assertTrue(delegate.state.value.pendingAction is ConnectionPendingAction.Respond)
        }

    @Test
    fun rpcSuccessKeepsExactlyOnceLock_untilNewerSnapshotArrives() =
        runTest {
            val requester = RecordingRequester()
            val delegate = delegate(requester)
            delegate.reset("session-a")
            delegate.acceptRequest(snapshot(seq = 1L))

            delegate.respond("github", emptyMap(), approved = false)
            delegate.respond("github", emptyMap(), approved = false)

            assertEquals(1, requester.calls.size)
            assertEquals(
                ConnectionTargetState.PENDING,
                delegate.state.value.operation
                    ?.targets
                    ?.single()
                    ?.state,
            )
            assertTrue(delegate.state.value.pendingAction is ConnectionPendingAction.Respond)

            delegate.acceptUpdate(snapshot(seq = 2L, state = ConnectionTargetState.PENDING))
            assertNull(delegate.state.value.pendingAction)
            delegate.respond("github", emptyMap(), approved = false)
            assertEquals(2, requester.calls.size)
        }

    @Test
    fun skipAndContinuePayloads_matchBackendContract() =
        runTest {
            val skipRequester = RecordingRequester()
            val skipDelegate = delegate(skipRequester)
            skipDelegate.reset("session-a")
            skipDelegate.acceptRequest(snapshot())
            skipDelegate.respond("github", mapOf("TOKEN" to "must-drop"), approved = false)

            val skipAction = skipRequester.calls.single()
            assertTrue(skipAction is ConnectionOperationRequest.Respond)
            val skipRespond = skipAction as ConnectionOperationRequest.Respond
            assertEquals(
                ConnectionRespondParams(
                    owner = ConnectorOwner.session("session-a"),
                    opId = "op-a",
                    result =
                        ConnectionAnswer(
                            targets =
                                listOf(
                                    ConnectionAnswerTarget(
                                        name = "github",
                                        status = "skipped",
                                    ),
                                ),
                        ),
                ),
                skipRespond.params,
            )

            val encodedSkip =
                OkHttpProvider.json.encodeToJsonElement(ConnectionRespondParams.serializer(), skipRespond.params)
            val expectedSkipWire =
                buildJsonObject {
                    put(
                        "owner",
                        buildJsonObject {
                            put("type", "session")
                            put("session_id", "session-a")
                        },
                    )
                    put("op_id", "op-a")
                    put(
                        "result",
                        buildJsonObject {
                            put(
                                "targets",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("name", "github")
                                            put("status", "skipped")
                                        },
                                    )
                                },
                            )
                        },
                    )
                }
            assertEquals(expectedSkipWire, encodedSkip)

            val continueRequester = RecordingRequester()
            val continueDelegate = delegate(continueRequester)
            continueDelegate.reset("session-a")
            continueDelegate.acceptRequest(snapshot())
            continueDelegate.continueOperation()

            val continueAction = continueRequester.calls.single()
            assertTrue(continueAction is ConnectionOperationRequest.Respond)
            val continueRespond = continueAction as ConnectionOperationRequest.Respond
            assertEquals(
                ConnectionRespondParams(
                    owner = ConnectorOwner.session("session-a"),
                    opId = "op-a",
                    result = ConnectionAnswer(settledBy = "continue"),
                ),
                continueRespond.params,
            )

            val encodedContinue =
                OkHttpProvider.json.encodeToJsonElement(ConnectionRespondParams.serializer(), continueRespond.params)
            val expectedContinueWire =
                buildJsonObject {
                    put(
                        "owner",
                        buildJsonObject {
                            put("type", "session")
                            put("session_id", "session-a")
                        },
                    )
                    put("op_id", "op-a")
                    put(
                        "result",
                        buildJsonObject {
                            put("settled_by", "continue")
                        },
                    )
                }
            assertEquals(expectedContinueWire, encodedContinue)
        }

    @Test
    fun wakePayload_sendsSessionOwnerNotTopLevelSessionId() =
        runTest {
            val requester = RecordingRequester()
            val delegate = delegate(requester)
            delegate.reset("session-a")
            delegate.acceptRequest(snapshot())

            delegate.wake("op-a")

            val action = requester.calls.single()
            assertTrue(action is ConnectionOperationRequest.Wake)
            val wake = action as ConnectionOperationRequest.Wake
            assertEquals(
                ConnectorsOperationStatusParams(
                    owner = ConnectorOwner.session("session-a"),
                    opId = "op-a",
                ),
                wake.params,
            )

            val encoded =
                OkHttpProvider.json.encodeToJsonElement(ConnectorsOperationStatusParams.serializer(), wake.params)
            val expectedWire =
                buildJsonObject {
                    put(
                        "owner",
                        buildJsonObject {
                            put("type", "session")
                            put("session_id", "session-a")
                        },
                    )
                    put("op_id", "op-a")
                }
            assertEquals(expectedWire, encoded)
        }

    @Test
    fun ownershipAndRuntimeFailures_mapToTypedNonRetryableErrors() =
        runTest {
            val cases =
                listOf(
                    HermesWsClient.HermesRpcException("x", code = 4001) to
                        ChatConnectionOperationDelegate.NOT_OWNER,
                    HermesWsClient.HermesRpcException(
                        "x",
                        code = 5033,
                        data = JsonObject(mapOf("reason" to JsonPrimitive("UNSUPPORTED_RUNTIME"))),
                    ) to ChatConnectionOperationDelegate.UNSUPPORTED_RUNTIME,
                )
            for ((failure, expected) in cases) {
                val delegate = delegate(RecordingRequester(failure = failure))
                delegate.reset("session-a")
                delegate.acceptRequest(snapshot())

                delegate.continueOperation()

                val error = delegate.state.value.error
                assertEquals(expected, error?.message)
                assertFalse(error?.retryable ?: true)
                assertNull(delegate.state.value.pendingAction)
            }
        }

    @Test
    fun rpcFailureUnlocksWithSanitizedError() =
        runTest {
            val requester = RecordingRequester(failure = IllegalStateException("secret backend detail"))
            val delegate = delegate(requester)
            delegate.reset("session-a")
            delegate.acceptRequest(snapshot())

            delegate.respond("github", emptyMap(), approved = false)

            assertNull(delegate.state.value.pendingAction)
            assertEquals(
                "request_failed",
                delegate.state.value.error
                    ?.message,
            )
            assertFalse(
                delegate.state.value
                    .toString()
                    .contains("secret backend detail"),
            )
        }

    @Test
    fun lateOldGenerationFailure_cannotMutateResetState() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val requester =
                ConnectionOperationRequester {
                    gate.await()
                    throw IllegalStateException("old failure")
                }
            val delegate = ChatConnectionOperationDelegate(requester)
            delegate.reset("session-a")
            delegate.acceptRequest(snapshot())

            val action = launch { delegate.respond("github", emptyMap(), approved = false) }
            runCurrent()
            delegate.reset("session-b")
            gate.complete(Unit)
            action.join()

            assertNull(delegate.state.value.operation)
            assertNull(delegate.state.value.error)
        }

    @Test
    fun wakeUnknownOperation_isHarmlessSettledRace() =
        runTest {
            val requester =
                RecordingRequester(
                    failure = HermesWsClient.HermesRpcException("gone", code = 4004),
                )
            val delegate = delegate(requester)
            delegate.reset("session-a")
            delegate.acceptRequest(snapshot())

            delegate.wake("op-a")

            assertNull(delegate.state.value.operation)
            assertNull(delegate.state.value.error)
        }

    private fun delegate(requester: RecordingRequester = RecordingRequester()): ChatConnectionOperationDelegate =
        ChatConnectionOperationDelegate(requester)

    private fun snapshot(
        sessionId: String = "session-a",
        opId: String = "op-a",
        seq: Long = 1L,
        state: ConnectionTargetState = ConnectionTargetState.PENDING,
        settled: Boolean = false,
        requiredEnv: List<ConnectionEnvField> = emptyList(),
    ): ConnectionOperationSnapshot =
        ConnectionOperationSnapshot(
            sessionId = sessionId,
            opId = opId,
            seq = seq,
            deadlineAt = 2_000_000_000.0,
            timeoutSeconds = 300.0,
            toolCallId = "tool-a",
            settled = settled,
            settledBy = if (settled) "all_resolved" else null,
            targets =
                listOf(
                    ConnectionOperationTarget(
                        name = "github",
                        kind = ConnectionTargetKind.CONNECTOR,
                        action = ConnectionTargetAction.AUTHORIZE,
                        state = state,
                        detail = null,
                        instructions = null,
                        discoveryError = null,
                        connectUrl = null,
                        connectionId = null,
                        attempt = null,
                        requiredEnv = requiredEnv,
                        tools = emptyList(),
                        hint = null,
                    ),
                ),
        )

    private fun envField(
        name: String,
        secret: Boolean,
    ): ConnectionEnvField =
        ConnectionEnvField(
            name = name,
            required = true,
            secret = secret,
            defaultValue = null,
            prompt = null,
        )

    private class RecordingRequester(
        private val failure: Throwable? = null,
    ) : ConnectionOperationRequester {
        val calls = mutableListOf<ConnectionOperationRequest>()

        override suspend fun request(action: ConnectionOperationRequest): Any? {
            calls += action
            failure?.let { throw it }
            return Unit
        }
    }
}
