package com.m57.hermescontrol.ui.connectors

import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.AccountConnectorResult
import com.m57.hermescontrol.data.model.ConnectorCatalogEntry
import com.m57.hermescontrol.data.model.ConnectorError
import com.m57.hermescontrol.data.model.ConnectorListResult
import com.m57.hermescontrol.data.model.ConnectorPolicy
import com.m57.hermescontrol.data.ws.AccountConnectorRepository
import com.m57.hermescontrol.data.ws.ConnectionOperationParser
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.contract.ConnectionAnswer
import com.m57.hermescontrol.data.ws.contract.ConnectionRespondParams
import com.m57.hermescontrol.data.ws.contract.ConnectorOwner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountConnectorsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<AccountConnectorRepository>()
    private val scopes = MutableStateFlow<DataScope?>(DataScope("server", "http://host", "work", 1))
    private val events = MutableSharedFlow<WsEvent>()
    private lateinit var vm: AccountConnectorsViewModel
    private val policy = ConnectorPolicy("effective", "unrestricted", emptyList(), emptyList(), emptyMap())

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.listConnectors() } returns ConnectorListResult.Success(true, emptyList())
        coEvery { repo.catalog() } returns
            AccountConnectorResult.Success(listOf(ConnectorCatalogEntry("drive", "Google Drive", "Files", "storage")))
        coEvery { repo.accounts() } returns AccountConnectorResult.Success(emptyList())
        coEvery { repo.policy() } returns
            AccountConnectorResult.Success(policy.copy(member = policy.copy(revision = "member")))
        coEvery { repo.operationStatus("op-a") } returns AccountConnectorResult.Success(snapshot())
        vm = AccountConnectorsViewModel(repo, scopes, { scopes.value }, events)
    }

    @After fun teardown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun `unavailable connectors are not a generic error or an actionable catalog`() =
        runTest(dispatcher) {
            coEvery { repo.listConnectors() } returns ConnectorListResult.Error(ConnectorError.Unavailable())
            advanceUntilIdle()

            assertFalse(vm.state.value.loading)
            assertTrue(vm.state.value.unavailable)
            assertNull(vm.state.value.error)
            assertTrue(
                vm.state.value.catalog
                    .isEmpty(),
            )
            assertNull(vm.state.value.policy)
        }

    @Test fun `soft unavailable response clears stale data and recovers on refresh`() =
        runTest(dispatcher) {
            advanceUntilIdle()
            assertFalse(vm.state.value.unavailable)
            coEvery { repo.listConnectors() } returns ConnectorListResult.Success(false, emptyList())
            vm.refresh()
            advanceUntilIdle()
            assertTrue(vm.state.value.unavailable)
            assertNull(vm.state.value.error)
            assertTrue(
                vm.state.value.catalog
                    .isEmpty(),
            )
            assertTrue(
                vm.state.value.accounts
                    .isEmpty(),
            )
            assertTrue(
                vm.state.value.tools
                    .isEmpty(),
            )
            assertNull(vm.state.value.policy)

            vm.connect("drive")
            vm.loadTools("drive")
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.connect(any(), any()) }
            coVerify(exactly = 0) { repo.tools(any(), any()) }

            coEvery { repo.listConnectors() } returns ConnectorListResult.Success(true, emptyList())
            vm.refresh()
            advanceUntilIdle()
            assertFalse(vm.state.value.unavailable)
            assertNull(vm.state.value.error)
            assertEquals(
                "drive",
                vm.state.value.catalog
                    .single()
                    .slug,
            )
        }

    @Test fun `entitlement failure from any account endpoint takes precedence over generic failures`() =
        runTest(dispatcher) {
            val unavailable = AccountConnectorResult.Failure(ConnectorError.Unavailable())
            coEvery { repo.listConnectors() } returns ConnectorListResult.Error(ConnectorError.NetworkError())
            coEvery { repo.catalog() } returns unavailable
            advanceUntilIdle()
            assertTrue(vm.state.value.unavailable)
            assertNull(vm.state.value.error)

            coEvery { repo.catalog() } returns AccountConnectorResult.Success(emptyList())
            coEvery { repo.accounts() } returns unavailable
            vm.refresh()
            advanceUntilIdle()
            assertTrue(vm.state.value.unavailable)
            assertNull(vm.state.value.error)

            coEvery { repo.accounts() } returns AccountConnectorResult.Success(emptyList())
            coEvery { repo.policy() } returns unavailable
            vm.refresh()
            advanceUntilIdle()
            assertTrue(vm.state.value.unavailable)
            assertNull(vm.state.value.error)
        }

    @Test fun `losing access cancels pending tools and closes an existing authorization operation`() =
        runTest(dispatcher) {
            advanceUntilIdle()
            coEvery { repo.connect(any(), any()) } returns AccountConnectorResult.Success(snapshot())
            vm.connect("drive")
            advanceUntilIdle()
            assertEquals(
                "op-a",
                vm.connectionState.value.operation
                    ?.opId,
            )
            val pending =
                CompletableDeferred<AccountConnectorResult<List<com.m57.hermescontrol.data.model.ConnectorTool>>>()
            coEvery { repo.tools("drive", false) } coAnswers { pending.await() }
            vm.loadTools("drive")
            dispatcher.scheduler.runCurrent()

            coEvery { repo.listConnectors() } returns ConnectorListResult.Error(ConnectorError.Unavailable())
            vm.refresh()
            advanceUntilIdle()
            pending.complete(AccountConnectorResult.Success(emptyList()))
            advanceUntilIdle()
            assertTrue(vm.state.value.unavailable)
            assertTrue(
                vm.state.value.tools
                    .isEmpty(),
            )
            assertTrue(
                vm.state.value.toolsLoading
                    .isEmpty(),
            )
            assertNull(vm.connectionState.value.operation)
        }

    @Test fun `network errors remain retryable errors and not entitlement states`() =
        runTest(dispatcher) {
            coEvery { repo.listConnectors() } returns ConnectorListResult.Error(ConnectorError.NetworkError())
            advanceUntilIdle()
            assertFalse(vm.state.value.unavailable)
            assertEquals(ConnectorError.NetworkError().message, vm.state.value.error)
        }

    @Test fun `profile change clears prior unavailable state`() =
        runTest(dispatcher) {
            coEvery { repo.listConnectors() } returns ConnectorListResult.Error(ConnectorError.Unavailable())
            advanceUntilIdle()
            assertTrue(vm.state.value.unavailable)
            coEvery { repo.listConnectors() } returns ConnectorListResult.Success(true, emptyList())
            scopes.value = scopes.value?.copy(activeProfileId = "other")
            advanceUntilIdle()
            assertFalse(vm.state.value.unavailable)
            assertEquals(
                "other",
                vm.state.value.scope
                    ?.activeProfileId,
            )
        }

    @Test fun `no session required and connect retains the browser operation`() =
        runTest(dispatcher) {
            advanceUntilIdle()
            assertFalse(vm.state.value.loading)
            assertEquals(
                "Google Drive",
                vm.state.value.catalog
                    .single()
                    .name,
            )
            coEvery { repo.connect(listOf("drive"), false) } returns AccountConnectorResult.Success(snapshot())
            vm.connect("drive")
            vm.connect("drive")
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.connect(listOf("drive"), false) }
            assertEquals(
                "https://example.com/auth",
                vm.connectionState.value.operation
                    ?.targets
                    ?.single()
                    ?.safeConnectUrl,
            )
        }

    @Test fun `connect and remove errors remain visible rather than cleared by reload`() =
        runTest(dispatcher) {
            advanceUntilIdle()
            coEvery { repo.connect(any(), any()) } returns
                AccountConnectorResult.Failure(ConnectorError.RequestFailed("authorization failed"))
            vm.connect("drive")
            advanceUntilIdle()
            assertEquals("authorization failed", vm.state.value.error)
            coEvery { repo.remove("c1") } returns
                AccountConnectorResult.Failure(ConnectorError.RequestFailed("remove failed"))
            vm.remove("c1")
            advanceUntilIdle()
            assertEquals("remove failed", vm.state.value.error)
            assertFalse(vm.state.value.busy)
        }

    @Test fun `profile switch clears operation and rejects prior account update`() =
        runTest(dispatcher) {
            advanceUntilIdle()
            coEvery { repo.connect(any(), any()) } returns AccountConnectorResult.Success(snapshot())
            vm.connect("drive")
            advanceUntilIdle()
            scopes.value = scopes.value?.copy(activeProfileId = "other")
            advanceUntilIdle()
            events.emit(WsEvent.ConnectionUpdate(snapshot().copy(seq = 2)))
            advanceUntilIdle()
            assertNull(vm.connectionState.value.operation)
            assertEquals(
                "other",
                vm.state.value.scope
                    ?.activeProfileId,
            )
        }

    @Test fun `late tool response cannot populate a new account scope`() =
        runTest(dispatcher) {
            advanceUntilIdle()
            val pending =
                CompletableDeferred<AccountConnectorResult<List<com.m57.hermescontrol.data.model.ConnectorTool>>>()
            coEvery { repo.tools("drive", false) } coAnswers { pending.await() }
            vm.loadTools("drive")
            dispatcher.scheduler.runCurrent()
            scopes.value = scopes.value?.copy(inMemoryAuthGeneration = 2)
            advanceUntilIdle()
            pending.complete(AccountConnectorResult.Success(emptyList()))
            advanceUntilIdle()
            assertTrue(
                vm.state.value.tools
                    .isEmpty(),
            )
            assertTrue(
                vm.state.value.toolsLoading
                    .isEmpty(),
            )
        }

    @Test fun `continue answers account operation and settlement closes it`() =
        runTest(dispatcher) {
            advanceUntilIdle()
            coEvery { repo.connect(any(), any()) } returns AccountConnectorResult.Success(snapshot())
            coEvery { repo.operationRespond(any()) } returns mapOf("status" to "ok")
            coEvery { repo.operationStatus("op-a") } returns
                AccountConnectorResult.Success(snapshot().copy(seq = 2, settled = true))
            vm.connect("drive")
            advanceUntilIdle()
            vm.continueOperation()
            advanceUntilIdle()
            coVerify {
                repo.operationRespond(
                    ConnectionRespondParams(
                        owner = ConnectorOwner.account(),
                        opId = "op-a",
                        result = ConnectionAnswer(settledBy = "continue"),
                    ),
                )
            }
            assertNull(vm.connectionState.value.operation)
        }

    @Test fun `session update never changes account operation`() =
        runTest(dispatcher) {
            advanceUntilIdle()
            coEvery { repo.connect(any(), any()) } returns AccountConnectorResult.Success(snapshot())
            vm.connect("drive")
            advanceUntilIdle()
            events.emit(
                WsEvent.ConnectionUpdate(
                    snapshot().copy(seq = 3, settled = true, accountOwned = false, sessionId = "chat"),
                ),
            )
            advanceUntilIdle()
            assertEquals(
                1L,
                vm.connectionState.value.operation
                    ?.seq,
            )
        }

    private fun snapshot() =
        checkNotNull(
            ConnectionOperationParser.parse(
                mapOf(
                    "owner" to mapOf("type" to "account"),
                    "op_id" to "op-a",
                    "seq" to 1,
                    "deadline_at" to 2000000000.0,
                    "settled" to false,
                    "targets" to
                        listOf(
                            mapOf(
                                "name" to "drive",
                                "kind" to "connector",
                                "action" to "authorize",
                                "state" to "initiated",
                                "connect_url" to "https://example.com/auth",
                            ),
                        ),
                ),
            ),
        )
}
