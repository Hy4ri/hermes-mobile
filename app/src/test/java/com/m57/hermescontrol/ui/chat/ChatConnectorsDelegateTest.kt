package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.ConnectorConnectItem
import com.m57.hermescontrol.data.model.ConnectorConnectResult
import com.m57.hermescontrol.data.model.ConnectorConnectStatus
import com.m57.hermescontrol.data.model.ConnectorConnectSummary
import com.m57.hermescontrol.data.model.ConnectorError
import com.m57.hermescontrol.data.model.ConnectorItem
import com.m57.hermescontrol.data.model.ConnectorListResult
import com.m57.hermescontrol.data.ws.ConnectorRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatConnectorsDelegateTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val mockRepository = mockk<ConnectorRepository>()
    private var currentSessionId: String? = "session-A"

    private lateinit var delegate: ChatConnectorsDelegate

    @Before
    fun setup() {
        currentSessionId = "session-A"
        coEvery { mockRepository.listConnectors(any()) } returns
            ConnectorListResult.Success(available = true, connectors = emptyList())
        coEvery { mockRepository.connect(any(), any(), any()) } returns
            ConnectorConnectResult.Success(results = emptyList(), summary = ConnectorConnectSummary())

        delegate =
            ChatConnectorsDelegate(
                scope = testScope,
                ioDispatcher = testDispatcher,
                repository = mockRepository,
                runtimeSessionId = { currentSessionId },
            )
    }

    private fun mockCatalog(vararg items: ConnectorItem) {
        coEvery { mockRepository.listConnectors("session-A") } returns
            ConnectorListResult.Success(available = true, connectors = items.toList())
    }

    @Test
    fun testInitialState_isNotVisible_andIdle() {
        val state = delegate.uiState.value
        assertFalse(state.isVisible)
        assertEquals(ConnectorsLoadPhase.Initial, state.loadPhase)
        assertTrue(state.items.isEmpty())
        assertEquals(ConnectorActionState.Idle, state.actionState)
        assertNull(state.browserLaunchEvent)
        assertNull(state.errorMessage)
        assertFalse(state.isOldBackend)
    }

    @Test
    fun testShow_triggersRefreshWhenForeground() =
        testScope.runTest {
            mockCatalog(
                ConnectorItem(
                    connector = "github",
                    name = "GitHub",
                    connected = false,
                    enabled = true,
                ),
            )

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            val state = delegate.uiState.value
            assertTrue(state.isVisible)
            assertTrue(state.loadPhase is ConnectorsLoadPhase.Loaded)
            assertEquals(1, state.items.size)
            assertEquals("github", state.items[0].slug)
            assertEquals("GitHub", state.items[0].name)
            assertFalse(state.items[0].isConnected)
            coVerify(atLeast = 1) { mockRepository.listConnectors("session-A") }
        }

    @Test
    fun testNoSession_doesNotIssueRpc() =
        testScope.runTest {
            currentSessionId = null
            delegate.onResume()
            delegate.show()
            delegate.refresh(force = true)
            delegate.connect("github")
            advanceUntilIdle()

            coVerify(exactly = 0) { mockRepository.listConnectors(any()) }
            coVerify(exactly = 0) { mockRepository.connect(any(), any(), any()) }
        }

    @Test
    fun testHide_cancelsJobs_andResetsActionState() =
        testScope.runTest {
            val deferred = CompletableDeferred<ConnectorListResult>()
            coEvery { mockRepository.listConnectors("session-A") } coAnswers { deferred.await() }

            delegate.onResume()
            delegate.show()
            testDispatcher.scheduler.runCurrent()

            assertTrue(delegate.uiState.value.loadPhase is ConnectorsLoadPhase.Loading)

            delegate.hide()
            advanceUntilIdle()

            assertFalse(delegate.uiState.value.isVisible)
            assertEquals(ConnectorActionState.Idle, delegate.uiState.value.actionState)
            assertNull(delegate.uiState.value.browserLaunchEvent)
        }

    @Test
    fun testSessionReload_generationGuard_ignoresStaleSessionAResult() =
        testScope.runTest {
            val sessionADeferred1 = CompletableDeferred<ConnectorListResult>()
            val sessionADeferred2 = CompletableDeferred<ConnectorListResult>()
            var callCount = 0

            coEvery { mockRepository.listConnectors("session-A") } coAnswers {
                callCount++
                if (callCount == 1) {
                    sessionADeferred1.await()
                } else {
                    sessionADeferred2.await()
                }
            }
            coEvery { mockRepository.listConnectors("session-B") } returns
                ConnectorListResult.Success(
                    available = true,
                    connectors =
                        listOf(
                            ConnectorItem(connector = "session-b-connector", name = "B"),
                        ),
                )

            delegate.onResume()
            delegate.show()
            testDispatcher.scheduler.runCurrent()

            // Transition: session A -> session B
            currentSessionId = "session-B"
            delegate.onActiveSessionChanged("session-B")
            advanceUntilIdle()

            assertEquals(1, delegate.uiState.value.items.size)
            assertEquals(
                "session-b-connector",
                delegate.uiState.value.items[0]
                    .slug,
            )

            // Transition: session B -> session A (new generation)
            currentSessionId = "session-A"
            delegate.onActiveSessionChanged("session-A")
            testDispatcher.scheduler.runCurrent()

            // First session A call completes late!
            sessionADeferred1.complete(
                ConnectorListResult.Success(
                    available = true,
                    connectors = listOf(ConnectorItem(connector = "stale-item", name = "Stale")),
                ),
            )
            testDispatcher.scheduler.runCurrent()

            // Second session A call completes
            sessionADeferred2.complete(
                ConnectorListResult.Success(
                    available = true,
                    connectors = listOf(ConnectorItem(connector = "fresh-item", name = "Fresh")),
                ),
            )
            advanceUntilIdle()

            // Should have fresh-item, stale-item from previous generation must be dropped
            assertEquals(1, delegate.uiState.value.items.size)
            assertEquals(
                "fresh-item",
                delegate.uiState.value.items[0]
                    .slug,
            )
        }

    @Test
    fun testConnectUrl_validHttps_emitsBrowserEvent_awaitsAuthorization() =
        testScope.runTest {
            mockCatalog(
                ConnectorItem(connector = "slack", name = "Slack", enabled = true),
            )
            coEvery { mockRepository.connect("session-A", listOf("slack"), false) } returns
                ConnectorConnectResult.Success(
                    results =
                        listOf(
                            ConnectorConnectItem(
                                connector = "slack",
                                status = ConnectorConnectStatus.INITIATED,
                                connectUrl = "https://slack.com/oauth/v2/authorize?client_id=123",
                            ),
                        ),
                    summary = ConnectorConnectSummary(total = 1, initiated = 1),
                )

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            delegate.connect("slack")
            advanceUntilIdle()

            val state = delegate.uiState.value
            assertTrue(state.actionState is ConnectorActionState.AwaitingAuthorization)
            assertEquals("slack", (state.actionState as ConnectorActionState.AwaitingAuthorization).slug)

            assertNotNull(state.browserLaunchEvent)
            val event = state.browserLaunchEvent!!
            assertEquals("slack", event.slug)
            assertEquals("https://slack.com/oauth/v2/authorize?client_id=123", event.url)
            assertTrue(event.toString().contains("[REDACTED]"))
            assertFalse(event.toString().contains("client_id=123"))

            // Consume event
            delegate.consumeBrowserEvent()
            assertNull(delegate.uiState.value.browserLaunchEvent)
            // Action state stays AwaitingAuthorization
            assertTrue(delegate.uiState.value.actionState is ConnectorActionState.AwaitingAuthorization)
        }

    @Test
    fun testConnectUrl_unsafeOrInvalid_resultsInRetryableFailure() =
        testScope.runTest {
            mockCatalog(
                ConnectorItem(connector = "bad", name = "Bad", enabled = true),
            )
            coEvery { mockRepository.connect("session-A", listOf("bad"), false) } returns
                ConnectorConnectResult.Success(
                    results =
                        listOf(
                            ConnectorConnectItem(
                                connector = "bad",
                                status = ConnectorConnectStatus.INITIATED,
                                connectUrl = "http://insecure.com/oauth", // Insecure HTTP
                            ),
                        ),
                    summary = ConnectorConnectSummary(total = 1, initiated = 1),
                )

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            delegate.connect("bad")
            advanceUntilIdle()

            val state = delegate.uiState.value
            assertEquals(ConnectorActionState.Idle, state.actionState)
            assertNull(state.browserLaunchEvent)
            assertEquals("Invalid or unsafe authorization URL received.", state.errorMessage)
        }

    @Test
    fun testResume_whileAwaitingAuthorization_triggersRefreshOnce() =
        testScope.runTest {
            mockCatalog(
                ConnectorItem(connector = "github", name = "GitHub", enabled = true, connected = false),
            )
            coEvery { mockRepository.connect("session-A", listOf("github"), false) } returns
                ConnectorConnectResult.Success(
                    results =
                        listOf(
                            ConnectorConnectItem(
                                connector = "github",
                                status = ConnectorConnectStatus.INITIATED,
                                connectUrl = "https://github.com/login/oauth/authorize",
                            ),
                        ),
                    summary = ConnectorConnectSummary(total = 1, initiated = 1),
                )

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            delegate.connect("github")
            advanceUntilIdle()

            assertTrue(delegate.uiState.value.actionState is ConnectorActionState.AwaitingAuthorization)
            assertNotNull(delegate.takeBrowserEvent())

            // Simulate catalog returning connected = true on next fetch
            mockCatalog(
                ConnectorItem(connector = "github", name = "GitHub", enabled = true, connected = true),
            )

            // Simulate user backgrounding app to browser
            delegate.onPause()
            // Returning from browser
            delegate.onResume()
            advanceUntilIdle()

            // List refresh confirmed it is now connected! Action state resets to Idle
            val state = delegate.uiState.value
            assertEquals(ConnectorActionState.Idle, state.actionState)
            assertEquals(1, state.items.size)
            assertTrue(state.items[0].isConnected)
            coVerify(atLeast = 1) { mockRepository.listConnectors("session-A") }
        }

    @Test
    fun testConnectActive_immediatelyRefreshesList() =
        testScope.runTest {
            mockCatalog(
                ConnectorItem(connector = "local", name = "Local Service", enabled = true, connected = false),
            )
            coEvery { mockRepository.connect("session-A", listOf("local"), false) } returns
                ConnectorConnectResult.Success(
                    results =
                        listOf(
                            ConnectorConnectItem(
                                connector = "local",
                                status = ConnectorConnectStatus.ACTIVE,
                            ),
                        ),
                    summary = ConnectorConnectSummary(total = 1, active = 1),
                )

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            // Update catalog to show connected = true after active connect
            mockCatalog(
                ConnectorItem(connector = "local", name = "Local Service", enabled = true, connected = true),
            )

            delegate.connect("local")
            advanceUntilIdle()

            val state = delegate.uiState.value
            assertEquals(ConnectorActionState.Idle, state.actionState)
            assertEquals(1, state.items.size)
            assertTrue(state.items[0].isConnected)
            coVerify(atLeast = 1) { mockRepository.listConnectors("session-A") }
        }

    @Test
    fun testFailures_retainCurrentItems_andCanRetry() =
        testScope.runTest {
            val initialItems =
                listOf(
                    ConnectorItem(
                        connector = "existing",
                        name = "Existing Connector",
                        connected = false,
                    ),
                )
            coEvery { mockRepository.listConnectors("session-A") } returns
                ConnectorListResult.Success(available = true, connectors = initialItems) andThen
                ConnectorListResult.Error(ConnectorError.NetworkError())

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            assertEquals(1, delegate.uiState.value.items.size)

            // Second refresh fails with network error
            delegate.refresh(force = true)
            advanceUntilIdle()

            val state = delegate.uiState.value
            // Existing items are preserved
            assertEquals(1, state.items.size)
            assertEquals("existing", state.items[0].slug)
            assertTrue(state.loadPhase is ConnectorsLoadPhase.Error)
            val errorPhase = state.loadPhase as ConnectorsLoadPhase.Error
            assertTrue(errorPhase.canRetry)
            assertTrue(errorPhase.error is ConnectorError.NetworkError)
        }

    @Test
    fun testOldBackend_setsFlag_andPreventsAutoRetries() =
        testScope.runTest {
            coEvery { mockRepository.listConnectors("session-A") } returns
                ConnectorListResult.Error(ConnectorError.UnsupportedBackend())

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            val state = delegate.uiState.value
            assertTrue(state.isOldBackend)
            assertTrue(state.loadPhase is ConnectorsLoadPhase.UnsupportedBackend)

            // Auto-retries (like onResume or onTransportReconnected) must NOT issue RPC
            delegate.onPause()
            delegate.onResume()
            delegate.onTransportReconnected()
            advanceUntilIdle()

            coVerify(exactly = 1) { mockRepository.listConnectors("session-A") }
        }

    @Test
    fun testUnavailableAndUnsupportedRuntime_distinctPhases() =
        testScope.runTest {
            coEvery { mockRepository.listConnectors("session-A") } returns
                ConnectorListResult.Error(ConnectorError.Unavailable("Unavailable for this session.")) andThen
                ConnectorListResult.Error(ConnectorError.UnsupportedRuntime("Must run on host."))

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            assertTrue(delegate.uiState.value.loadPhase is ConnectorsLoadPhase.Unavailable)
            assertEquals(
                "Unavailable for this session.",
                (delegate.uiState.value.loadPhase as ConnectorsLoadPhase.Unavailable).message,
            )

            delegate.refresh(force = true)
            advanceUntilIdle()

            assertTrue(delegate.uiState.value.loadPhase is ConnectorsLoadPhase.UnsupportedRuntime)
            assertEquals(
                "Must run on host.",
                (delegate.uiState.value.loadPhase as ConnectorsLoadPhase.UnsupportedRuntime).message,
            )
        }

    @Test
    fun testNotOwner_explainsReconnectSession() =
        testScope.runTest {
            coEvery { mockRepository.listConnectors("session-A") } returns
                ConnectorListResult.Error(ConnectorError.NotOwner())

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            assertTrue(delegate.uiState.value.loadPhase is ConnectorsLoadPhase.NotOwner)
            val msg = (delegate.uiState.value.loadPhase as ConnectorsLoadPhase.NotOwner).message
            assertTrue(msg.contains("Please reconnect the session"))
        }

    @Test
    fun testRepeatTaps_connectDeduplication() =
        testScope.runTest {
            mockCatalog(
                ConnectorItem(connector = "github", name = "GitHub", enabled = true),
            )
            val deferred = CompletableDeferred<ConnectorConnectResult>()
            coEvery { mockRepository.connect("session-A", listOf("github"), false) } coAnswers { deferred.await() }

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            delegate.connect("github")
            testDispatcher.scheduler.runCurrent()

            assertTrue(delegate.uiState.value.actionState is ConnectorActionState.Connecting)

            // Second tap during Connecting should be ignored
            delegate.connect("github")
            testDispatcher.scheduler.runCurrent()

            coVerify(exactly = 1) { mockRepository.connect("session-A", listOf("github"), false) }

            deferred.complete(
                ConnectorConnectResult.Success(
                    results = listOf(ConnectorConnectItem("github", ConnectorConnectStatus.FAILED)),
                    summary = ConnectorConnectSummary(1, failed = 1),
                ),
            )
            advanceUntilIdle()
        }

    @Test
    fun testRefreshDuringMutation_isDeferredUntilMutationCompletes() =
        testScope.runTest {
            mockCatalog(
                ConnectorItem(connector = "github", name = "GitHub", enabled = true, connected = false),
            )
            val connectDeferred = CompletableDeferred<ConnectorConnectResult>()
            coEvery { mockRepository.connect("session-A", listOf("github"), false) } coAnswers
                { connectDeferred.await() }

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            // 1 call from show
            coVerify(exactly = 1) { mockRepository.listConnectors("session-A") }

            delegate.connect("github")
            testDispatcher.scheduler.runCurrent()

            assertTrue(delegate.uiState.value.actionState is ConnectorActionState.Connecting)

            // Call refresh while connecting
            delegate.refresh()
            testDispatcher.scheduler.runCurrent()

            // Refresh should not have run yet while mutation is active (still exactly 1)
            coVerify(exactly = 1) { mockRepository.listConnectors("session-A") }

            // Finish connect
            connectDeferred.complete(
                ConnectorConnectResult.Success(
                    results =
                        listOf(
                            ConnectorConnectItem(
                                "github",
                                ConnectorConnectStatus.INITIATED,
                                "https://github.com/login/oauth/authorize",
                            ),
                        ),
                    summary = ConnectorConnectSummary(1, initiated = 1),
                ),
            )
            advanceUntilIdle()

            // Deferred refresh should have executed! (now 2)
            coVerify(exactly = 2) { mockRepository.listConnectors("session-A") }
        }

    @Test
    fun testLaunchError_resetsActionStateAndSetsError() =
        testScope.runTest {
            mockCatalog(
                ConnectorItem(connector = "github", name = "GitHub", enabled = true),
            )
            coEvery { mockRepository.connect("session-A", listOf("github"), false) } returns
                ConnectorConnectResult.Success(
                    results =
                        listOf(
                            ConnectorConnectItem(
                                "github",
                                ConnectorConnectStatus.INITIATED,
                                "https://github.com/login/oauth/authorize",
                            ),
                        ),
                    summary = ConnectorConnectSummary(1, initiated = 1),
                )

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            delegate.connect("github")
            advanceUntilIdle()

            assertTrue(delegate.uiState.value.actionState is ConnectorActionState.AwaitingAuthorization)
            assertNotNull(delegate.uiState.value.browserLaunchEvent)

            delegate.launchError("No browser app found to handle OAuth.")

            val state = delegate.uiState.value
            assertEquals(ConnectorActionState.Idle, state.actionState)
            assertNull(state.browserLaunchEvent)
            assertEquals("No browser app found to handle OAuth.", state.errorMessage)
        }

    @Test
    fun testOnPause_clearsPendingBrowserEvent() =
        testScope.runTest {
            mockCatalog(
                ConnectorItem(connector = "github", name = "GitHub", enabled = true),
            )
            coEvery { mockRepository.connect("session-A", listOf("github"), false) } returns
                ConnectorConnectResult.Success(
                    results =
                        listOf(
                            ConnectorConnectItem(
                                "github",
                                ConnectorConnectStatus.INITIATED,
                                "https://github.com/login/oauth/authorize",
                            ),
                        ),
                    summary = ConnectorConnectSummary(1, initiated = 1),
                )

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            delegate.connect("github")
            advanceUntilIdle()

            assertNotNull(delegate.uiState.value.browserLaunchEvent)

            delegate.onPause()
            assertNull(delegate.uiState.value.browserLaunchEvent)
        }

    @Test
    fun testConnect_notOwner_neverReplaysMutationIntoSessionB() =
        testScope.runTest {
            mockCatalog(
                ConnectorItem(connector = "github", name = "GitHub", enabled = true),
            )
            coEvery { mockRepository.connect("session-A", listOf("github"), false) } returns
                ConnectorConnectResult.Error(ConnectorError.NotOwner())

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            // Connect is invoked for session-A
            delegate.connect("github")
            // Active session shifts to session-B
            currentSessionId = "session-B"
            advanceUntilIdle()

            // Critical regression check: MUST NEVER execute connect mutation on session-B!
            coVerify(exactly = 0) { mockRepository.connect("session-B", any(), any()) }
            assertEquals(ConnectorActionState.Idle, delegate.uiState.value.actionState)
        }

    @Test
    fun testConnect_notOwner_setsNotOwnerPhaseAndErrorWithoutMutationRetry() =
        testScope.runTest {
            mockCatalog(
                ConnectorItem(connector = "github", name = "GitHub", enabled = true),
            )
            coEvery { mockRepository.connect("session-A", listOf("github"), false) } returns
                ConnectorConnectResult.Error(ConnectorError.NotOwner())

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            delegate.connect("github")
            advanceUntilIdle()

            coVerify(exactly = 1) { mockRepository.connect("session-A", listOf("github"), false) }
            val state = delegate.uiState.value
            assertEquals(ConnectorActionState.Idle, state.actionState)
            assertTrue(state.loadPhase is ConnectorsLoadPhase.NotOwner)
            assertTrue(state.errorMessage?.contains("reconnect the session") == true)
        }

    @Test
    fun testConnect_fallbackFirstUnrelatedConnectorRemoved_requiresExactSlugMatch() =
        testScope.runTest {
            mockCatalog(
                ConnectorItem(connector = "slack", name = "Slack", enabled = true),
            )
            // Backend returns unrelated connector "other" instead of "slack"
            coEvery { mockRepository.connect("session-A", listOf("slack"), false) } returns
                ConnectorConnectResult.Success(
                    results =
                        listOf(
                            ConnectorConnectItem(
                                connector = "other",
                                status = ConnectorConnectStatus.INITIATED,
                                connectUrl = "https://other.com/oauth",
                            ),
                        ),
                    summary = ConnectorConnectSummary(total = 1, initiated = 1),
                )

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            delegate.connect("slack")
            advanceUntilIdle()

            val state = delegate.uiState.value
            // Fallback must NOT trigger authorization for "other"
            assertEquals(ConnectorActionState.Idle, state.actionState)
            assertNull(state.browserLaunchEvent)
            assertEquals("Connector 'slack' not found in response.", state.errorMessage)
        }

    @Test
    fun testConnect_guardsDisabledAndNonCatalogSlug() =
        testScope.runTest {
            mockCatalog(
                ConnectorItem(connector = "enabled-item", name = "Enabled", enabled = true),
                ConnectorItem(connector = "disabled-item", name = "Disabled", enabled = false),
            )

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            // Slug not in catalog
            delegate.connect("unknown-item")
            advanceUntilIdle()
            assertEquals("Connector 'unknown-item' not found in catalog.", delegate.uiState.value.errorMessage)
            coVerify(exactly = 0) { mockRepository.connect(any(), listOf("unknown-item"), any()) }

            // Slug disabled in catalog
            delegate.connect("disabled-item")
            advanceUntilIdle()
            assertEquals("Connector 'disabled-item' is disabled.", delegate.uiState.value.errorMessage)
            coVerify(exactly = 0) { mockRepository.connect(any(), listOf("disabled-item"), any()) }
        }

    @Test
    fun testConnect_guardsBackgroundAndOldBackend() =
        testScope.runTest {
            mockCatalog(
                ConnectorItem(connector = "github", name = "GitHub", enabled = true),
            )
            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            // Background guard
            delegate.onPause()
            delegate.connect("github")
            advanceUntilIdle()
            coVerify(exactly = 0) { mockRepository.connect(any(), any(), any()) }

            // UnsupportedBackend guard
            delegate.onResume()
            coEvery { mockRepository.listConnectors("session-A") } returns
                ConnectorListResult.Error(ConnectorError.UnsupportedBackend("Old server"))
            delegate.refresh(force = true)
            advanceUntilIdle()

            delegate.connect("github")
            advanceUntilIdle()
            coVerify(exactly = 0) { mockRepository.connect(any(), any(), any()) }
            assertEquals("Connectors are not supported by this backend.", delegate.uiState.value.errorMessage)
        }

    @Test
    fun testRefresh_coalescesAndDeduplicatesForceCalls() =
        testScope.runTest {
            val deferred = CompletableDeferred<ConnectorListResult>()
            coEvery { mockRepository.listConnectors("session-A") } coAnswers { deferred.await() }

            delegate.onResume()
            delegate.show()
            testDispatcher.scheduler.runCurrent()

            // Rapid repeat calls to refresh(force = true) while in flight
            delegate.refresh(force = true)
            delegate.refresh(force = true)
            testDispatcher.scheduler.runCurrent()

            coVerify(exactly = 1) { mockRepository.listConnectors("session-A") }

            deferred.complete(
                ConnectorListResult.Success(available = true, connectors = emptyList()),
            )
            advanceUntilIdle()
        }

    @Test
    fun testOnResume_duplicateCallsDoNotRepeatForcedRefresh() =
        testScope.runTest {
            mockCatalog(
                ConnectorItem(connector = "github", name = "GitHub", enabled = true),
            )
            coEvery { mockRepository.connect("session-A", listOf("github"), false) } returns
                ConnectorConnectResult.Success(
                    results =
                        listOf(
                            ConnectorConnectItem(
                                "github",
                                ConnectorConnectStatus.INITIATED,
                                "https://github.com/login/oauth/authorize",
                            ),
                        ),
                    summary = ConnectorConnectSummary(1, initiated = 1),
                )

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            delegate.connect("github")
            advanceUntilIdle()

            // 1 refresh from show
            coVerify(exactly = 1) { mockRepository.listConnectors("session-A") }

            // Take browser event to simulate launching external browser
            assertNotNull(delegate.takeBrowserEvent())

            // App goes to background (user in browser)
            delegate.onPause()
            // App returns: true paused -> resume transition triggers refresh once
            delegate.onResume()
            advanceUntilIdle()
            coVerify(exactly = 2) { mockRepository.listConnectors("session-A") }

            // Duplicate onResume while already foreground MUST NOT fire forced refresh again
            delegate.onResume()
            delegate.onResume()
            advanceUntilIdle()
            coVerify(exactly = 2) { mockRepository.listConnectors("session-A") }
        }

    @Test
    fun testConsumeBrowserEvent_validatesIdentitySessionGenerationAndForeground() =
        testScope.runTest {
            mockCatalog(
                ConnectorItem(connector = "github", name = "GitHub", enabled = true),
            )
            coEvery { mockRepository.connect("session-A", listOf("github"), false) } returns
                ConnectorConnectResult.Success(
                    results =
                        listOf(
                            ConnectorConnectItem(
                                "github",
                                ConnectorConnectStatus.INITIATED,
                                "https://github.com/login/oauth/authorize",
                            ),
                        ),
                    summary = ConnectorConnectSummary(1, initiated = 1),
                )

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            delegate.connect("github")
            advanceUntilIdle()

            val event = delegate.uiState.value.browserLaunchEvent
            assertNotNull(event)
            val eventId = event!!.eventId

            // 1. Wrong event ID returns null
            assertNull(delegate.consumeBrowserEvent(999999L))
            assertNotNull(delegate.uiState.value.browserLaunchEvent)

            // 2. Consume while backgrounded rejects handoff and clears stale event
            delegate.onPause()
            assertNull(delegate.consumeBrowserEvent(eventId))
            assertNull(delegate.uiState.value.browserLaunchEvent)
        }

    @Test
    fun testCatalogRefresh_removedConnectorEndsAuthorization() =
        testScope.runTest {
            mockCatalog(
                ConnectorItem(connector = "github", name = "GitHub", enabled = true, connected = false),
            )
            coEvery { mockRepository.connect("session-A", listOf("github"), false) } returns
                ConnectorConnectResult.Success(
                    results =
                        listOf(
                            ConnectorConnectItem(
                                "github",
                                ConnectorConnectStatus.INITIATED,
                                "https://github.com/login/oauth/authorize",
                            ),
                        ),
                    summary = ConnectorConnectSummary(1, initiated = 1),
                )

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            delegate.connect("github")
            advanceUntilIdle()

            assertTrue(delegate.uiState.value.actionState is ConnectorActionState.AwaitingAuthorization)
            assertNotNull(delegate.takeBrowserEvent())

            // Refresh returns catalog where "github" was completely removed
            mockCatalog(
                ConnectorItem(connector = "slack", name = "Slack", enabled = true),
            )
            delegate.onPause()
            delegate.onResume()
            advanceUntilIdle()

            // Authorization should end cleanly, avoiding infinite spinner
            assertEquals(ConnectorActionState.Idle, delegate.uiState.value.actionState)
        }

    @Test
    fun testRefreshSuccess_clearsPreviousErrorMessage() =
        testScope.runTest {
            coEvery { mockRepository.listConnectors("session-A") } returns
                ConnectorListResult.Error(ConnectorError.NetworkError("Network lost")) andThen
                ConnectorListResult.Success(available = true, connectors = emptyList())

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            delegate.launchError("Temporary failure")
            assertEquals("Temporary failure", delegate.uiState.value.errorMessage)

            delegate.refresh(force = true)
            advanceUntilIdle()

            assertNull(delegate.uiState.value.errorMessage)
        }

    @Test
    fun testAvailableFalse_mapsToUnavailablePhaseAndClearsItems() =
        testScope.runTest {
            coEvery { mockRepository.listConnectors("session-A") } returns
                ConnectorListResult.Success(
                    available = false,
                    connectors = listOf(ConnectorItem("github", "GitHub")),
                )

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            val state = delegate.uiState.value
            assertTrue(state.loadPhase is ConnectorsLoadPhase.Unavailable)
            assertTrue(state.items.isEmpty())
        }

    @Test
    fun testHide_incrementsGeneration_andIgnoresLateResponses() =
        testScope.runTest {
            val deferred = CompletableDeferred<ConnectorListResult>()
            coEvery { mockRepository.listConnectors("session-A") } coAnswers { deferred.await() }

            delegate.onResume()
            delegate.show()
            testDispatcher.scheduler.runCurrent()

            delegate.hide()

            deferred.complete(
                ConnectorListResult.Success(
                    available = true,
                    connectors = listOf(ConnectorItem("github", "GitHub")),
                ),
            )
            advanceUntilIdle()

            // State must remain hidden with initial/empty items
            assertFalse(delegate.uiState.value.isVisible)
            assertTrue(
                delegate.uiState.value.items
                    .isEmpty(),
            )
        }

    @Test
    fun testReadCompletesPaused_deliversCatalog_noPermanentSpinnerOnResume() =
        testScope.runTest {
            val deferred = CompletableDeferred<ConnectorListResult>()
            coEvery { mockRepository.listConnectors("session-A") } coAnswers { deferred.await() }

            delegate.onResume()
            delegate.show()
            testDispatcher.scheduler.runCurrent()

            assertTrue(delegate.uiState.value.loadPhase is ConnectorsLoadPhase.Loading)
            assertTrue(delegate.uiState.value.isLoading)

            // Background while read is in-flight
            delegate.onPause()

            // Read completes while app is paused
            deferred.complete(
                ConnectorListResult.Success(
                    available = true,
                    connectors = listOf(ConnectorItem(connector = "github", name = "GitHub", enabled = true)),
                ),
            )
            advanceUntilIdle()

            // State delivered while paused; not stuck in permanent spinner!
            val pausedState = delegate.uiState.value
            assertTrue(pausedState.loadPhase is ConnectorsLoadPhase.Loaded)
            assertFalse(pausedState.isLoading)
            assertEquals(1, pausedState.items.size)
            assertEquals("github", pausedState.items[0].slug)

            // Returning to foreground retains loaded catalog without infinite reload spinner
            delegate.onResume()
            advanceUntilIdle()

            val resumedState = delegate.uiState.value
            assertTrue(resumedState.loadPhase is ConnectorsLoadPhase.Loaded)
            assertFalse(resumedState.isLoading)
            assertEquals(1, resumedState.items.size)
            coVerify(exactly = 1) { mockRepository.listConnectors("session-A") }
        }

    @Test
    fun testConnectActive_completedWhilePaused_defersRefreshUntilResume() =
        testScope.runTest {
            mockCatalog(
                ConnectorItem(connector = "local", name = "Local Service", enabled = true, connected = false),
            )
            val connectDeferred = CompletableDeferred<ConnectorConnectResult>()
            coEvery { mockRepository.connect("session-A", listOf("local"), false) } coAnswers {
                connectDeferred.await()
            }

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            coVerify(exactly = 1) { mockRepository.listConnectors("session-A") }

            delegate.connect("local")
            testDispatcher.scheduler.runCurrent()

            assertTrue(delegate.uiState.value.actionState is ConnectorActionState.Connecting)

            // App is paused while connect mutation is pending
            delegate.onPause()

            // Mutation completes with ACTIVE while app is in background
            connectDeferred.complete(
                ConnectorConnectResult.Success(
                    results =
                        listOf(
                            ConnectorConnectItem(
                                connector = "local",
                                status = ConnectorConnectStatus.ACTIVE,
                            ),
                        ),
                    summary = ConnectorConnectSummary(total = 1, active = 1),
                ),
            )
            advanceUntilIdle()

            // Critical: NO background refresh RPC issued while paused!
            coVerify(exactly = 1) { mockRepository.listConnectors("session-A") }
            assertEquals(ConnectorActionState.Idle, delegate.uiState.value.actionState)

            // Update mock catalog so refresh returns connected = true
            mockCatalog(
                ConnectorItem(connector = "local", name = "Local Service", enabled = true, connected = true),
            )

            // Resume triggers the deferred refresh
            delegate.onResume()
            advanceUntilIdle()

            coVerify(exactly = 2) { mockRepository.listConnectors("session-A") }
            val state = delegate.uiState.value
            assertEquals(1, state.items.size)
            assertTrue(state.items[0].isConnected)
            assertEquals(ConnectorActionState.Idle, state.actionState)
        }

    @Test
    fun testMintedUrl_unconsumedOnPause_retainedTransiently_onlyActualBrowserReturnTriggersVerification() =
        testScope.runTest {
            mockCatalog(
                ConnectorItem(connector = "github", name = "GitHub", enabled = true, connected = false),
            )
            coEvery { mockRepository.connect("session-A", listOf("github"), false) } returns
                ConnectorConnectResult.Success(
                    results =
                        listOf(
                            ConnectorConnectItem(
                                connector = "github",
                                status = ConnectorConnectStatus.INITIATED,
                                connectUrl = "https://github.com/login/oauth/authorize",
                            ),
                        ),
                    summary = ConnectorConnectSummary(total = 1, initiated = 1),
                )

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            // 1 call from show
            coVerify(exactly = 1) { mockRepository.listConnectors("session-A") }

            delegate.connect("github")
            advanceUntilIdle()

            val mintedEvent = delegate.uiState.value.browserLaunchEvent
            assertNotNull(mintedEvent)
            assertTrue(delegate.uiState.value.actionState is ConnectorActionState.AwaitingAuthorization)

            // App paused BEFORE event is consumed/launched (e.g. system dialog or user home tap)
            delegate.onPause()

            // In background: transient event is hidden from UI state
            assertNull(delegate.uiState.value.browserLaunchEvent)

            // App resumed WITHOUT having launched the browser
            delegate.onResume()
            advanceUntilIdle()

            // Critical: MUST NOT trigger verification refresh before launch!
            coVerify(exactly = 1) { mockRepository.listConnectors("session-A") }

            // Minted unconsumed event is restored on resume
            val restoredEvent = delegate.uiState.value.browserLaunchEvent
            assertNotNull(restoredEvent)
            assertEquals(mintedEvent!!.eventId, restoredEvent!!.eventId)
            assertTrue(delegate.uiState.value.actionState is ConnectorActionState.AwaitingAuthorization)

            // Now UI actually consumes event to launch browser
            val taken = delegate.takeBrowserEvent()
            assertNotNull(taken)
            assertEquals(mintedEvent.eventId, taken!!.eventId)
            assertNull(delegate.uiState.value.browserLaunchEvent)

            // Taking again returns null (taken only once)
            assertNull(delegate.takeBrowserEvent())

            // Simulate catalog returning connected = true on next fetch
            mockCatalog(
                ConnectorItem(connector = "github", name = "GitHub", enabled = true, connected = true),
            )

            // App paused as user transitions to browser
            delegate.onPause()

            // App resumed upon returning from external browser authorization
            delegate.onResume()
            advanceUntilIdle()

            // Verification refresh IS triggered upon actual browser return!
            coVerify(exactly = 2) { mockRepository.listConnectors("session-A") }
            val finalState = delegate.uiState.value
            assertEquals(ConnectorActionState.Idle, finalState.actionState)
            assertTrue(finalState.items[0].isConnected)
        }

    @Test
    fun testForcedRefresh_coalescesToOneTrailingRead_notCancelRestartStorm() =
        testScope.runTest {
            val deferred1 = CompletableDeferred<ConnectorListResult>()
            val deferred2 = CompletableDeferred<ConnectorListResult>()
            var listCallCount = 0

            coEvery { mockRepository.listConnectors("session-A") } coAnswers {
                listCallCount++
                if (listCallCount == 1) {
                    deferred1.await()
                } else {
                    deferred2.await()
                }
            }

            delegate.onResume()
            delegate.show()
            testDispatcher.scheduler.runCurrent()

            assertEquals(1, listCallCount)

            // Rapid storm of forced refresh calls while read is in-flight
            delegate.refresh(force = true)
            delegate.refresh(force = true)
            delegate.refresh(force = true)
            testDispatcher.scheduler.runCurrent()

            // In-flight read was NOT cancelled or multiplied
            assertEquals(1, listCallCount)
            coVerify(exactly = 1) { mockRepository.listConnectors("session-A") }

            // Complete first read
            deferred1.complete(
                ConnectorListResult.Success(available = true, connectors = emptyList()),
            )
            testDispatcher.scheduler.runCurrent()

            // Exactly ONE trailing read was started, not 3!
            assertEquals(2, listCallCount)
            coVerify(exactly = 2) { mockRepository.listConnectors("session-A") }

            // Complete second (trailing) read
            deferred2.complete(
                ConnectorListResult.Success(
                    available = true,
                    connectors = listOf(ConnectorItem(connector = "done", name = "Done")),
                ),
            )
            advanceUntilIdle()

            // No additional reads
            assertEquals(2, listCallCount)
            assertEquals(1, delegate.uiState.value.items.size)
            assertEquals(
                "done",
                delegate.uiState.value.items[0]
                    .slug,
            )
        }

    @Test
    fun testActivePendingMutation_sessionChanged_doesNotReplayIntoSessionB() =
        testScope.runTest {
            mockCatalog(
                ConnectorItem(connector = "github", name = "GitHub", enabled = true),
            )
            val connectDeferred = CompletableDeferred<ConnectorConnectResult>()
            coEvery { mockRepository.connect("session-A", listOf("github"), false) } coAnswers {
                connectDeferred.await()
            }
            coEvery { mockRepository.listConnectors("session-B") } returns
                ConnectorListResult.Success(
                    available = true,
                    connectors = listOf(ConnectorItem(connector = "slack", name = "Slack", enabled = true)),
                )

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            // Start connect mutation on session-A
            delegate.connect("github")
            testDispatcher.scheduler.runCurrent()

            assertTrue(delegate.uiState.value.actionState is ConnectorActionState.Connecting)

            // Switch active session to session-B while mutation is still pending
            currentSessionId = "session-B"
            delegate.onActiveSessionChanged("session-B")
            testDispatcher.scheduler.runCurrent()

            // Now pending connect mutation for session-A finishes
            connectDeferred.complete(
                ConnectorConnectResult.Success(
                    results =
                        listOf(
                            ConnectorConnectItem(
                                connector = "github",
                                status = ConnectorConnectStatus.INITIATED,
                                connectUrl = "https://github.com/login/oauth/authorize",
                            ),
                        ),
                    summary = ConnectorConnectSummary(total = 1, initiated = 1),
                ),
            )
            advanceUntilIdle()

            // Critical: MUST NEVER replay mutation into session-B!
            coVerify(exactly = 0) { mockRepository.connect("session-B", any(), any()) }

            // State reflects session-B, session-A's pending mutation is completely discarded
            val state = delegate.uiState.value
            assertEquals("session-B", state.sessionId)
            assertEquals(ConnectorActionState.Idle, state.actionState)
            assertNull(state.browserLaunchEvent)
            assertEquals(1, state.items.size)
            assertEquals("slack", state.items[0].slug)
        }

    @Test
    fun testPendingBrowserEvent_publishedResumedTakenOnlyOnce_sessionSwitchDiscards() =
        testScope.runTest {
            mockCatalog(
                ConnectorItem(connector = "github", name = "GitHub", enabled = true),
            )
            coEvery { mockRepository.connect("session-A", listOf("github"), false) } returns
                ConnectorConnectResult.Success(
                    results =
                        listOf(
                            ConnectorConnectItem(
                                connector = "github",
                                status = ConnectorConnectStatus.INITIATED,
                                connectUrl = "https://github.com/login/oauth/authorize",
                            ),
                        ),
                    summary = ConnectorConnectSummary(total = 1, initiated = 1),
                )

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            delegate.connect("github")
            advanceUntilIdle()

            // 1. Published into UI state
            assertNotNull(delegate.uiState.value.browserLaunchEvent)

            // 2. Background app: unconsumed event retained transiently
            delegate.onPause()
            assertNull(delegate.uiState.value.browserLaunchEvent)

            // 3. Switch active session while backgrounded
            currentSessionId = "session-B"
            delegate.onActiveSessionChanged("session-B")

            // 4. Resume in new session: old session's pending event MUST be discarded
            delegate.onResume()
            advanceUntilIdle()

            assertNull(delegate.uiState.value.browserLaunchEvent)
            assertNull(delegate.takeBrowserEvent())

            // Switch back to session-A
            currentSessionId = "session-A"
            delegate.onActiveSessionChanged("session-A")
            advanceUntilIdle()

            delegate.connect("github")
            advanceUntilIdle()

            // Published again in session-A
            assertNotNull(delegate.uiState.value.browserLaunchEvent)

            // Background & Resume
            delegate.onPause()
            delegate.onResume()
            assertNotNull(delegate.uiState.value.browserLaunchEvent)

            // Taken only once
            val taken = delegate.takeBrowserEvent()
            assertNotNull(taken)
            assertNull(delegate.uiState.value.browserLaunchEvent)
            assertNull(delegate.takeBrowserEvent())

            // Subsequent onResume does not re-publish already-taken event
            delegate.onPause()
            delegate.onResume()
            assertNull(delegate.uiState.value.browserLaunchEvent)
        }

    @Test
    fun testLifecycle_repeatedCallsDoNotDuplicateEventsOrRefreshes() =
        testScope.runTest {
            mockCatalog(
                ConnectorItem(connector = "github", name = "GitHub", enabled = true),
            )
            coEvery { mockRepository.connect("session-A", listOf("github"), false) } returns
                ConnectorConnectResult.Success(
                    results =
                        listOf(
                            ConnectorConnectItem(
                                connector = "github",
                                status = ConnectorConnectStatus.INITIATED,
                                connectUrl = "https://github.com/login/oauth/authorize",
                            ),
                        ),
                    summary = ConnectorConnectSummary(total = 1, initiated = 1),
                )

            delegate.onResume()
            delegate.show()
            advanceUntilIdle()

            coVerify(exactly = 1) { mockRepository.listConnectors("session-A") }

            delegate.connect("github")
            advanceUntilIdle()

            val originalEvent = delegate.uiState.value.browserLaunchEvent
            assertNotNull(originalEvent)

            // Repeated onPause calls do not corrupt or lose the event
            delegate.onPause()
            delegate.onPause()
            delegate.onPause()
            assertNull(delegate.uiState.value.browserLaunchEvent)

            // Repeated onResume calls do not duplicate event or trigger repeated refresh
            delegate.onResume()
            delegate.onResume()
            delegate.onResume()
            advanceUntilIdle()

            assertEquals(
                originalEvent!!.eventId,
                delegate.uiState.value.browserLaunchEvent
                    ?.eventId,
            )
            coVerify(exactly = 1) { mockRepository.listConnectors("session-A") }

            // Take the event
            assertNotNull(delegate.takeBrowserEvent())

            // Repeated pause/resume cycle after launch triggers verification refresh exactly once
            delegate.onPause()
            delegate.onResume()
            delegate.onResume()
            delegate.onResume()
            advanceUntilIdle()

            coVerify(exactly = 2) { mockRepository.listConnectors("session-A") }
        }
}
