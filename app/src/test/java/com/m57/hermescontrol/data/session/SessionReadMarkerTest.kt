package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.data.model.SessionMutationSnapshot
import com.m57.hermescontrol.data.model.SessionRenameRequest
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SessionReadMarkerTest {
    private val api = mockk<HermesApiService>()
    private val failures = mutableListOf<Throwable?>()

    private fun markerIn(scope: kotlinx.coroutines.CoroutineScope) =
        SessionReadMarker(
            api = { api },
            profileId = { "work" },
            nowSeconds = { 1234.5 },
            scope = scope,
            logFailure = { _, t -> failures += t },
        )

    private fun fenceReturns(revision: Int?) {
        coEvery { api.getSessionMutationSnapshot(any()) } returns
            if (revision == null) {
                Response.error(404, okhttp3.ResponseBody.create(null, ""))
            } else {
                Response.success(
                    SessionMutationSnapshot(
                        sessionId = "s",
                        exists = true,
                        runtimeRevision = JsonPrimitive(revision),
                        runtimeGeneration = JsonPrimitive(2),
                    ),
                )
            }
    }

    @Test
    fun `body is unread false with the request_id fence and profile`() =
        runTest(UnconfinedTestDispatcher()) {
            fenceReturns(7)
            coEvery { api.setSessionRead(any(), any()) } returns Response.success(Unit)
            markerIn(backgroundScope).markRead("s")
            coVerify {
                api.setSessionRead(
                    "s",
                    match {
                        it.unread == false && it.profile == "work" && it.expectedRevision == 7 &&
                            it.expectedGeneration == 2 && !it.requestId.isNullOrBlank()
                    },
                )
            }
        }

    @Test
    fun `wire body carries only unread false and the fence`() {
        val json = Json { encodeDefaults = false }
        val body = json.encodeToString(SessionRenameRequest(unread = false, requestId = "r", profile = "p"))
        assertEquals(setOf("unread", "request_id", "profile"), json.parseToJsonElement(body).jsonObject.keys)
        assertEquals("false", json.parseToJsonElement(body).jsonObject["unread"].toString())
    }

    @Test
    fun `older backend without snapshot endpoint gets an unfenced body`() =
        runTest(UnconfinedTestDispatcher()) {
            fenceReturns(null)
            coEvery { api.setSessionRead(any(), any()) } returns Response.success(Unit)
            markerIn(backgroundScope).markRead("s")
            coVerify { api.setSessionRead("s", SessionRenameRequest(unread = false, profile = "work")) }
        }

    @Test
    fun `local watermark is set at once and survives a failed PATCH without surfacing an error`() =
        runTest(UnconfinedTestDispatcher()) {
            fenceReturns(1)
            coEvery { api.setSessionRead(any(), any()) } throws IOException("boom")
            val marker = markerIn(backgroundScope)
            marker.markRead("s")
            assertEquals(mapOf("s" to 1234.5), marker.localReads.value)
            assertEquals(1, failures.size)
        }

    @Test
    fun `http failure is logged not thrown`() =
        runTest(UnconfinedTestDispatcher()) {
            fenceReturns(1)
            coEvery { api.setSessionRead(any(), any()) } returns
                Response.error(409, okhttp3.ResponseBody.create(null, ""))
            val marker = markerIn(backgroundScope)
            marker.markRead("s")
            assertEquals(1, failures.size)
        }

    @Test
    fun `blank or null id is ignored`() =
        runTest(UnconfinedTestDispatcher()) {
            val marker = markerIn(backgroundScope)
            marker.markRead(null)
            marker.markRead(" ")
            assertTrue(marker.localReads.value.isEmpty())
            coVerify(exactly = 0) { api.setSessionRead(any(), any()) }
            assertNull(marker.localReads.value["x"])
        }

    @Test
    fun `chat resume marks read and leaving marks read again`() =
        runTest(UnconfinedTestDispatcher()) {
            fenceReturns(1)
            coEvery { api.setSessionRead(any(), any()) } returns Response.success(Unit)
            val marker = markerIn(backgroundScope)
            val onLeave = marker.chatOpened("s")
            coVerify(exactly = 1) { api.setSessionRead("s", any()) }
            onLeave()
            coVerify(exactly = 2) { api.setSessionRead("s", any()) }
        }
}
