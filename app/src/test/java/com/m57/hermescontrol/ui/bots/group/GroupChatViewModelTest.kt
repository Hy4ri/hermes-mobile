package com.m57.hermescontrol.ui.bots.group

import android.util.Log
import com.m57.hermescontrol.data.model.BotAvatarMeta
import com.m57.hermescontrol.data.model.BotRosterMeta
import com.m57.hermescontrol.data.model.GroupChatRoomMeta
import com.m57.hermescontrol.data.model.GroupChatSyncFrom
import com.m57.hermescontrol.data.model.GroupChatSyncLogEntry
import com.m57.hermescontrol.data.model.GroupChatSyncSnapshot
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.ProfilesResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsEvent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class GroupChatViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService
    private val eventsFlow = MutableSharedFlow<WsEvent>()
    private val json = Json { ignoreUnknownKeys = true }

    private val botA =
        ProfileInfo(
            name = "scout",
            ui_meta =
                mapOf(
                    "hermes-bots" to
                        json.encodeToJsonElement(
                            BotRosterMeta(
                                title = "Scout Bot",
                                groups = listOf("Dev Team"),
                                avatar = BotAvatarMeta(icon = "science"),
                            ),
                        ),
                ),
        )

    private val botB =
        ProfileInfo(
            name = "coder",
            ui_meta =
                mapOf(
                    "hermes-bots" to
                        json.encodeToJsonElement(
                            BotRosterMeta(
                                title = "Dev Bot",
                                groups = listOf("Dev Team"),
                                avatar = BotAvatarMeta(icon = "code"),
                            ),
                        ),
                ),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi

        coEvery { mockApi.getProfiles() } returns
            Response.success(
                ProfilesResponse(
                    profiles = listOf(botA, botB),
                ),
            )

        mockkObject(HermesWsClient)
        every { HermesWsClient.events } returns eventsFlow
        every { HermesWsClient.send(any(), any(), any()) } returns "req-1"
        every { HermesWsClient.sendMessage(any(), any(), any()) } returns "req-msg"

        val createDeferred = CompletableDeferred<Any?>()
        createDeferred.complete(mapOf("session_id" to "session-scout-1"))
        every { HermesWsClient.request(any(), any(), any()) } returns createDeferred
    }

    @After
    fun tearDown() {
        unmockkObject(ApiClient)
        unmockkObject(HermesWsClient)
        unmockkStatic(Log::class)
        Dispatchers.resetMain()
    }

    @Test
    fun testLoadGroup_resolvesMembers() =
        runTest(testDispatcher) {
            val viewModel = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertNull(state.errorMessage)
            assertEquals(2, state.members.size)
            assertEquals("scout", state.members[0].name)
            assertEquals("coder", state.members[1].name)
        }

    @Test
    fun testLoadGroup_hydratesFromSyncSnapshot() =
        runTest(testDispatcher) {
            val syncSnapshot =
                GroupChatSyncSnapshot(
                    version = 3,
                    updatedAt = 1724000000L,
                    rooms =
                        mapOf(
                            "name:Dev Team" to
                                GroupChatRoomMeta(
                                    name = "Dev Team",
                                    members = listOf(JsonPrimitive("scout"), JsonPrimitive("coder")),
                                    log =
                                        listOf(
                                            GroupChatSyncLogEntry(
                                                id = "msg-prev-1",
                                                from = GroupChatSyncFrom(kind = "user", name = "You"),
                                                text = "Initial design spec",
                                                at = 1724000000L,
                                            ),
                                            GroupChatSyncLogEntry(
                                                id = "msg-prev-2",
                                                from = GroupChatSyncFrom(kind = "member", name = "scout"),
                                                text = "Looks solid, ready to build.",
                                                at = 1724000010L,
                                            ),
                                        ),
                                ),
                        ),
                )

            val defaultProfileWithSnapshot =
                ProfileInfo(
                    name = "default",
                    is_default = true,
                    ui_meta = mapOf("hermes-bots-groups" to json.encodeToJsonElement(syncSnapshot)),
                )

            coEvery { mockApi.getProfiles() } returns
                Response.success(
                    ProfilesResponse(
                        profiles = listOf(defaultProfileWithSnapshot, botA, botB),
                    ),
                )

            val viewModel = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(2, state.messages.size)
            assertEquals("msg-prev-1", state.messages[0].id)
            assertTrue(state.messages[0].isUser)
            assertEquals("Initial design spec", state.messages[0].text)

            assertEquals("msg-prev-2", state.messages[1].id)
            assertFalse(state.messages[1].isUser)
            assertEquals("scout", state.messages[1].senderName)
            assertEquals("Looks solid, ready to build.", state.messages[1].text)
        }

    @Test
    fun testSendMessage_streamsTokensAndCompletes() =
        runTest(testDispatcher) {
            val viewModel = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher)
            testScheduler.runCurrent()

            // Send message targeting only @scout
            viewModel.sendMessage("@scout what's the status?")
            testScheduler.runCurrent()

            // State should contain user message
            val userMsg = viewModel.uiState.value.messages.first()
            assertTrue(userMsg.isUser)
            assertEquals("@scout what's the status?", userMsg.text)

            // Stream tokens
            eventsFlow.emit(WsEvent.MessageToken(token = "Building ", sessionId = "session-scout-1"))
            testScheduler.runCurrent()

            val streamingMsg = viewModel.uiState.value.messages.last()
            assertFalse(streamingMsg.isUser)
            assertEquals("scout", streamingMsg.senderName)
            assertEquals("Building ", streamingMsg.text)
            assertTrue(streamingMsg.isStreaming)

            eventsFlow.emit(WsEvent.MessageToken(token = "the APK...", sessionId = "session-scout-1"))
            testScheduler.runCurrent()

            val updatedStreaming = viewModel.uiState.value.messages.last()
            assertEquals("Building the APK...", updatedStreaming.text)

            // Complete turn
            eventsFlow.emit(
                WsEvent.MessageComplete(
                    text = "Building the APK now! Everything looks green.",
                    sessionId = "session-scout-1",
                ),
            )
            testScheduler.runCurrent()

            val finalState = viewModel.uiState.value
            assertEquals(2, finalState.messages.size)
            val botMsg = finalState.messages[1]
            assertEquals("Building the APK now! Everything looks green.", botMsg.text)
            assertFalse(botMsg.isStreaming)
            assertNull(finalState.activeSpeaker)
        }

    @Test
    fun testSendMessage_filtersOutPass() =
        runTest(testDispatcher) {
            val viewModel = GroupChatViewModel("Dev Team", ioDispatcher = testDispatcher)
            testScheduler.runCurrent()

            viewModel.sendMessage("@scout any updates?")
            testScheduler.runCurrent()

            eventsFlow.emit(
                WsEvent.MessageComplete(
                    text = "(pass)",
                    sessionId = "session-scout-1",
                ),
            )
            testScheduler.runCurrent()

            val finalState = viewModel.uiState.value
            // Only the user message should remain; (pass) is filtered out
            assertEquals(1, finalState.messages.size)
            assertTrue(finalState.messages.first().isUser)
        }
}
