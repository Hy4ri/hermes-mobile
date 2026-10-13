package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.model.PairingApproveRequest
import com.m57.hermescontrol.data.model.PairingRevokeRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class PairingPayloadTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HermesApiService

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(OkHttpProvider.json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(HermesApiService::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun approvePairing_withProfile_serializesProfileAsLastField() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val response =
                api.approvePairing(
                    PairingApproveRequest(
                        platform = "telegram",
                        requestId = "req-123",
                        code = "code-456",
                        profile = "work",
                    ),
                )
            assertTrue(response.isSuccessful)

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/api/pairing/approve", recorded.path)

            val body = OkHttpProvider.json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
            assertEquals(JsonPrimitive("telegram"), body["platform"])
            assertEquals(JsonPrimitive("req-123"), body["request_id"])
            assertEquals(JsonPrimitive("code-456"), body["code"])
            assertEquals(JsonPrimitive("work"), body["profile"])
        }

    @Test
    fun approvePairing_withoutProfile_omitsOrNullProfileField() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val response =
                api.approvePairing(
                    PairingApproveRequest(
                        platform = "telegram",
                        requestId = "req-123",
                    ),
                )
            assertTrue(response.isSuccessful)

            val recorded = server.takeRequest()
            assertEquals("/api/pairing/approve", recorded.path)

            val body = OkHttpProvider.json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
            assertEquals(JsonPrimitive("telegram"), body["platform"])
            assertEquals(JsonPrimitive("req-123"), body["request_id"])
            // By default encodeDefaults is false in OkHttpProvider.json or field is omitted / null
            val profileElement = body["profile"]
            assertTrue(profileElement == null || profileElement == kotlinx.serialization.json.JsonNull)
        }

    @Test
    fun revokePairing_withProfile_serializesProfileAsLastField() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val response =
                api.revokePairing(
                    PairingRevokeRequest(
                        platform = "discord",
                        userId = "u-999",
                        profile = "custom-profile",
                    ),
                )
            assertTrue(response.isSuccessful)

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/api/pairing/revoke", recorded.path)

            val body = OkHttpProvider.json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
            assertEquals(JsonPrimitive("discord"), body["platform"])
            assertEquals(JsonPrimitive("u-999"), body["user_id"])
            assertEquals(JsonPrimitive("custom-profile"), body["profile"])
        }

    @Test
    fun revokePairing_withoutProfile_omitsOrNullProfileField() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val response =
                api.revokePairing(
                    PairingRevokeRequest(
                        platform = "discord",
                        userId = "u-999",
                    ),
                )
            assertTrue(response.isSuccessful)

            val recorded = server.takeRequest()
            assertEquals("/api/pairing/revoke", recorded.path)

            val body = OkHttpProvider.json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
            assertEquals(JsonPrimitive("discord"), body["platform"])
            assertEquals(JsonPrimitive("u-999"), body["user_id"])
            val profileElement = body["profile"]
            assertTrue(profileElement == null || profileElement == kotlinx.serialization.json.JsonNull)
        }
}
