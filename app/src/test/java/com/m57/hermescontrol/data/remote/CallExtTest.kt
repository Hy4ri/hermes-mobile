package com.m57.hermescontrol.data.remote

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CallExtTest {
    @Test
    fun testAwaitSuccess() =
        runTest {
            val call = mockk<Call>()
            val request = Request.Builder().url("http://localhost").build()
            val response =
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .build()

            val callbackSlot = slot<Callback>()
            every { call.enqueue(capture(callbackSlot)) } answers {
                callbackSlot.captured.onResponse(call, response)
            }
            every { call.cancel() } returns Unit

            val result = call.await()
            assertEquals(200, result.code)
        }

    @Test
    fun testAwaitFailure() =
        runTest {
            val call = mockk<Call>()
            val exception = IOException("Failed")

            val callbackSlot = slot<Callback>()
            every { call.isCanceled() } returns false
            every { call.enqueue(capture(callbackSlot)) } answers {
                callbackSlot.captured.onFailure(call, exception)
            }
            every { call.cancel() } returns Unit

            try {
                call.await()
            } catch (e: Exception) {
                assertEquals("Failed", e.message)
            }
        }
}
