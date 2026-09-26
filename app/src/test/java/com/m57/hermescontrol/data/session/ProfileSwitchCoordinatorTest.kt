package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ActiveProfileResponse
import com.m57.hermescontrol.data.model.SetActiveProfileRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.ws.HermesWsClient
import io.mockk.Ordering
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * The switch coordinator is the single atomic profile-switch flow — every
 * surface (Profiles screen, future quick-switch) routes through it. These
 * tests pin the ORDER of operations, because chat's fresh-session behavior
 * depends on the switch broadcast landing BEFORE the socket re-dial.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSwitchCoordinatorTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService

    @Before
    fun setUp() {
        // setMain is REQUIRED, and it is NOT the thing that used to bleed.
        //
        // The leak was mockkStatic(Dispatchers::class) -- absent here, keep it that
        // way. Avoiding setMain as well was the actual bug: with no setMain,
        // Dispatchers.Main is the JVM's "missing" dispatcher, and switchProfile's
        // _switched.tryEmit resumes collectors onto Main. Once ANY earlier class in
        // the same test JVM has installed and then reset a TestMainDispatcher,
        // dispatching to Main throws -- which is exactly why this class passed 5/5
        // in isolation but lost 4 tests in full-suite runs (2x DispatchException
        // "Dispatchers.Main threw an exception", 2x "test body did not run to
        // completion"). Every other test class here already does this.
        Dispatchers.setMain(testDispatcher)
        // Drive the coordinator's network hops on the test dispatcher instead of a
        // live IO thread pool. With real Dispatchers.IO the mocked calls were
        // recorded from a different thread and the Ordering.SEQUENCE assertions
        // became load-dependent -- green on an idle machine, 2 failures while the
        // emulator saturated the CPU.
        ProfileSwitchCoordinator.ioDispatcher = testDispatcher

        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi

        mockkObject(AuthManager)
        every { AuthManager.setActiveProfileId(any()) } returns Unit

        mockkObject(HermesWsClient)
        every { HermesWsClient.disconnect() } returns Unit
        every { HermesWsClient.connect() } returns Unit
    }

    @After
    fun tearDown() {
        ProfileSwitchCoordinator.ioDispatcher = Dispatchers.IO
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun coldStartBootstrapsMissingLocalScopeFromServerActiveProfile() =
        runTest {
            val active = MutableStateFlow<String?>(null)
            every { AuthManager.activeProfileId } returns active
            every { AuthManager.setActiveProfileId(any()) } answers {
                active.value = firstArg()
            }
            coEvery { mockApi.getActiveProfile() } returns
                Response.success(ActiveProfileResponse(active = "karellen"))

            val restored = ProfileSwitchCoordinator.restoreActiveProfileScopeIfMissing()

            assertEquals("karellen", restored)
            assertEquals("karellen", active.value)
            verify(exactly = 1) { AuthManager.setActiveProfileId("karellen") }
        }

    @Test
    fun coldStartKeepsExistingExplicitLocalProfileWithoutServerLookup() =
        runTest {
            val active = MutableStateFlow<String?>("work")
            every { AuthManager.activeProfileId } returns active

            val restored = ProfileSwitchCoordinator.restoreActiveProfileScopeIfMissing()

            assertEquals("work", restored)
            coVerify(exactly = 0) { mockApi.getActiveProfile() }
            verify(exactly = 0) { AuthManager.setActiveProfileId(any()) }
        }

    @Test
    fun `success flips server then persists selection then re-dials socket`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns Response.success(Unit)

            val result = ProfileSwitchCoordinator.switchProfile("meow")

            assertTrue(result is NetworkResult.Success)
            coVerify { mockApi.setActiveProfile(SetActiveProfileRequest("meow")) }
            // Ordering.ORDERED, not SEQUENCE: the point of this test is the relative
            // ORDER of the switch steps. SEQUENCE also demands that NO other call
            // touches the mocked objects in between, which is untestable in a shared
            // test JVM -- real-thread tests (HermesWsClientTest) leave background
            // ticket-mint retries calling AuthManager.getBaseUrl()/getServerStore()
            // for the rest of the process. Under load those 18 foreign calls landed
            // inside the verification window and failed the exact-count check even
            // though all 5 steps fired in the correct order (proven by the MockK
            // call trace: 1) setActiveProfileId 2) rebuild ... 21) sync 22)
            // disconnect 23) connect). ORDER keeps the order-pinning and ignores
            // foreign traffic.
            verify(ordering = Ordering.ORDERED) {
                AuthManager.setActiveProfileId("meow")
                HermesWsClient.disconnect()
                HermesWsClient.connect()
            }
        }

    @Test
    fun `success broadcasts the switch so chat wipes before the re-dial`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns Response.success(Unit)
            // Subscribe BEFORE the switch — exactly how ChatViewModel does it.
            val received = Channel<String>(Channel.UNLIMITED)
            backgroundScope.launch {
                ProfileSwitchCoordinator.switched.collect { received.send(it) }
            }
            runCurrent()

            ProfileSwitchCoordinator.switchProfile("meow")
            runCurrent()

            // The broadcast is buffered with capacity 1, so chat observes the
            // wipe BEFORE gateway.ready arrives on the re-dialed socket and
            // auto-creates the fresh session (desktop requestFreshSession).
            assertEquals("meow", received.tryReceive().getOrNull())
        }

    @Test
    fun `failure touches nothing`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns errorResponse(500)

            val result = ProfileSwitchCoordinator.switchProfile("meow")

            assertTrue(result is NetworkResult.Failure)
            verify(exactly = 0) { AuthManager.setSelectedProfileId(any()) }
            verify(exactly = 0) { AuthManager.setActiveProfileId(any()) }
            verify(exactly = 0) { HermesWsClient.disconnect() }
            verify(exactly = 0) { HermesWsClient.connect() }
        }

    @Test
    fun `connection switch re-homes selection retrofit and socket in order`() =
        runTest {
            every { AuthManager.setSelectedProfileId(any()) } returns Unit
            every { ApiClient.rebuild() } returns Unit
            coEvery { AuthManager.syncCookieStoreForProfile(any()) } returns Unit

            ProfileSwitchCoordinator.switchConnectionProfile("prof-2")

            // Load-bearing order: selection persists FIRST (token cache +
            // cookie scope follow), Retrofit re-points, the cookie store swap
            // is AWAITED (a racing dial mints the ticket with the previous
            // server's cookie → 401 → dead socket), then the socket re-dials
            // — so chat's wipe (via the broadcast) lands before the new
            // gateway's gateway.ready auto-creates the fresh session.
            //
            // ORDERED, not SEQUENCE -- see the sibling test above: SEQUENCE's
            // exact-count semantics fail under load in the shared test JVM when
            // leaked background retries interleave foreign AuthManager/ApiClient
            // calls into the window. ORDER still pins this 5-step sequence.
            coVerify(ordering = Ordering.ORDERED) {
                AuthManager.setSelectedProfileId("prof-2")
                ApiClient.rebuild()
                AuthManager.syncCookieStoreForProfile("prof-2")
                HermesWsClient.disconnect()
                HermesWsClient.connect()
            }
        }

    @Test
    fun `connection switch broadcasts so chat wipes before the re-dial`() =
        runTest {
            every { AuthManager.setSelectedProfileId(any()) } returns Unit
            every { ApiClient.rebuild() } returns Unit
            coEvery { AuthManager.syncCookieStoreForProfile(any()) } returns Unit
            // Subscribe BEFORE the switch — exactly how ChatViewModel does it.
            val received = Channel<String>(Channel.UNLIMITED)
            backgroundScope.launch {
                ProfileSwitchCoordinator.connectionSwitched.collect { received.send(it) }
            }
            runCurrent()

            ProfileSwitchCoordinator.switchConnectionProfile("prof-2")
            runCurrent()

            assertEquals("prof-2", received.tryReceive().getOrNull())
        }

    // ── Canonical session intent tests ─────────────────────────────

    @Test
    fun `canonical intent happy-path consume returns session id`() =
        runTest {
            val generation = ProfileSwitchCoordinator.setCanonicalIntent("sess-1", "karellen")

            val result = ProfileSwitchCoordinator.consumeCanonicalIntent("karellen", generation)

            assertEquals("sess-1", result)
        }

    @Test
    fun `canonical intent mismatch profile returns null`() =
        runTest {
            ProfileSwitchCoordinator.setCanonicalIntent("sess-1", "karellen")

            val result = ProfileSwitchCoordinator.consumeCanonicalIntent("default", 1L)

            assertEquals(null, result)
        }

    @Test
    fun `canonical intent mismatch generation returns null`() =
        runTest {
            val generation = ProfileSwitchCoordinator.setCanonicalIntent("sess-1", "karellen")

            val result = ProfileSwitchCoordinator.consumeCanonicalIntent("karellen", generation - 1)

            assertEquals(null, result)
        }

    @Test
    fun `canonical intent cleared on switch failure`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns errorResponse(500)
            ProfileSwitchCoordinator.setCanonicalIntent("sess-1", "karellen")

            ProfileSwitchCoordinator.switchProfile("karellen")
            runCurrent()

            // After a failed switch the intent must be cleared so no stale
            // gateway.ready can consume it under the wrong profile.
            val generation = ProfileSwitchCoordinator.canonicalIntentGeneration
            assertTrue(
                ProfileSwitchCoordinator.consumeCanonicalIntent("karellen", generation) == null,
            )
        }

    @Test
    fun `canonical intent cleared explicitly`() =
        runTest {
            ProfileSwitchCoordinator.setCanonicalIntent("sess-1", "karellen")

            ProfileSwitchCoordinator.clearCanonicalIntent()

            val result = ProfileSwitchCoordinator.consumeCanonicalIntent("karellen", 1L)
            assertEquals(null, result)
        }

    @Test
    fun `overlapping switch uses newest intent generation`() =
        runTest {
            // First switch starts setting intent for profile A
            val genA = ProfileSwitchCoordinator.setCanonicalIntent("sess-a", "alpha")
            // Overlapping switch B starts before A's gateway.ready
            val genB = ProfileSwitchCoordinator.setCanonicalIntent("sess-b", "beta")

            // Consume with A's generation — should fail (stale by B overwriting)
            assertEquals(
                null,
                ProfileSwitchCoordinator.consumeCanonicalIntent("alpha", genA),
            )
            // Consume with B's generation — should succeed
            assertEquals(
                "sess-b",
                ProfileSwitchCoordinator.consumeCanonicalIntent("beta", genB),
            )
            // B's intent consumed once — second consume returns null
            assertEquals(
                null,
                ProfileSwitchCoordinator.consumeCanonicalIntent("beta", genB),
            )
        }

    @Test
    fun `no canonical intent returns null`() =
        runTest {
            ProfileSwitchCoordinator.clearCanonicalIntent()

            val result = ProfileSwitchCoordinator.consumeCanonicalIntent("karellen", 0L)

            assertEquals(null, result)
        }

    private fun <T> errorResponse(code: Int): Response<T> = Response.error(code, "{}".toResponseBody(null))
}
