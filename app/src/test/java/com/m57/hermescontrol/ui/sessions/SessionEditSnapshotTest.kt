package com.m57.hermescontrol.ui.sessions

import com.m57.hermescontrol.data.model.SessionMutationSnapshot
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class SessionEditSnapshotTest {
    private val api = mockk<HermesApiService>()

    @Test
    fun readsNumericAndStringCountersWithoutIntOverflow() =
        runTest {
            for (counters in listOf("2147483648", "\"2147483648\"")) {
                coEvery { api.getSessionMutationSnapshot("s") } returns
                    Response.success(
                        Json.decodeFromString<SessionMutationSnapshot>(
                            """{"session_id":"s","exists":true,"runtime_revision":$counters,"runtime_generation":$counters}""",
                        ),
                    )
                assertEquals(SessionEditSnapshot(true, 2147483648, 2147483648), SessionEditSnapshot.read(api, "s"))
            }
        }

    @Test
    fun onlyUnsupportedRoutesReturnNull() =
        runTest {
            for (code in listOf(404, 405)) {
                coEvery { api.getSessionMutationSnapshot("s") } returns Response.error(code, "old".toResponseBody(null))
                assertNull(SessionEditSnapshot.read(api, "s"))
            }
            for (code in listOf(401, 403, 409, 500, 503)) {
                coEvery { api.getSessionMutationSnapshot("s") } returns
                    Response.error(code, "failed".toResponseBody(null))
                assertTrue(runCatching { SessionEditSnapshot.read(api, "s") }.isFailure)
            }
            coEvery { api.getSessionMutationSnapshot("s") } throws IOException("offline")
            assertTrue(runCatching { SessionEditSnapshot.read(api, "s") }.isFailure)
        }

    @Test
    fun rejectsMalformedCountersAndWrongIdentity() =
        runTest {
            for (json in listOf(
                """{"session_id":"s","exists":true}""",
                """{"session_id":"s","exists":true,"runtime_revision":-1}""",
                """{"session_id":"s","exists":true,"runtime_revision":{}}""",
                """{"session_id":"s","exists":true,"runtime_revision":1,"runtime_generation":"bad"}""",
                """{"session_id":"other","exists":true,"runtime_revision":1}""",
            )) {
                coEvery { api.getSessionMutationSnapshot("s") } returns
                    Response.success(Json.decodeFromString<SessionMutationSnapshot>(json))
                assertTrue(json, runCatching { SessionEditSnapshot.read(api, "s") }.isFailure)
            }
        }

    @Test
    fun missingSessionRetainsItsZeroRevisionAndNullGeneration() =
        runTest {
            coEvery { api.getSessionMutationSnapshot("s") } returns
                Response.success(
                    Json.decodeFromString<SessionMutationSnapshot>(
                        """{"session_id":"s","exists":false,"runtime_revision":0,"runtime_generation":null}""",
                    ),
                )
            assertEquals(SessionEditSnapshot(false, 0, null), SessionEditSnapshot.read(api, "s"))
        }
}
