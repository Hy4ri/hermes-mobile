package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.model.MessagingPlatformUpdate
import com.m57.hermescontrol.data.model.MessagingPlatformUpdateResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Blind tests for R1550 webhook toggle payload and platform configuration contract:
 * - Real Retrofit MockWebServer PUT /api/messaging/platforms/webhook
 * - JSON enabled=false serialized explicitly
 * - JSON enabled=true serialized explicitly
 * - Captured profile preserved and serialized
 * - No enable-only endpoint called
 * - Response parsing for MessagingPlatformUpdateResponse (ok=true / ok=false)
 */
class WebhookTogglePayloadTest {
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
    fun configurePlatform_webhook_enabledFalse_serializesExplicitFalseAndProfile() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"platform":"webhook","hot_served":false}"""),
            )

            val response =
                api.configurePlatform(
                    platformId = "webhook",
                    config =
                        MessagingPlatformUpdate(
                            enabled = false,
                            profile = "work",
                        ),
                )

            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertTrue(body!!.ok)
            assertEquals("webhook", body.platform)

            val recorded = server.takeRequest()
            assertEquals("PUT", recorded.method)
            assertEquals("/api/messaging/platforms/webhook", recorded.path)

            val requestJson = OkHttpProvider.json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
            assertTrue("JSON must contain 'enabled' key explicitly", requestJson.containsKey("enabled"))
            assertEquals(false, requestJson["enabled"]?.let { (it as? JsonPrimitive)?.booleanOrNull })
            assertEquals("work", requestJson["profile"]?.let { (it as? JsonPrimitive)?.contentOrNull })
        }

    @Test
    fun configurePlatform_webhook_enabledTrue_serializesExplicitTrueAndProfile() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"platform":"webhook","hot_served":false}"""),
            )

            val response =
                api.configurePlatform(
                    platformId = "webhook",
                    config =
                        MessagingPlatformUpdate(
                            enabled = true,
                            profile = "default",
                        ),
                )

            assertTrue(response.isSuccessful)
            val recorded = server.takeRequest()
            assertEquals("PUT", recorded.method)
            assertEquals("/api/messaging/platforms/webhook", recorded.path)

            val requestJson = OkHttpProvider.json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
            assertTrue("JSON must contain 'enabled' key explicitly", requestJson.containsKey("enabled"))
            assertEquals(true, requestJson["enabled"]?.let { (it as? JsonPrimitive)?.booleanOrNull })
            assertEquals("default", requestJson["profile"]?.let { (it as? JsonPrimitive)?.contentOrNull })
        }

    @Test
    fun configurePlatform_webhook_nullProfile_serializesEnabledWithoutProfile() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"platform":"webhook","hot_served":false}"""),
            )

            val response =
                api.configurePlatform(
                    platformId = "webhook",
                    config =
                        MessagingPlatformUpdate(
                            enabled = false,
                            profile = null,
                        ),
                )

            assertTrue(response.isSuccessful)
            val recorded = server.takeRequest()
            assertEquals("PUT", recorded.method)
            assertEquals("/api/messaging/platforms/webhook", recorded.path)

            val requestJson = OkHttpProvider.json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
            assertEquals(false, requestJson["enabled"]?.let { (it as? JsonPrimitive)?.booleanOrNull })
            val profileElement = requestJson["profile"]
            assertTrue(
                "profile should be omitted or null when not provided",
                profileElement == null || profileElement is JsonNull,
            )
        }

    @Test
    fun configurePlatform_webhook_responseOkFalse_parsesSuccessfully() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":false,"platform":"webhook","hot_served":false}"""),
            )

            val response =
                api.configurePlatform(
                    platformId = "webhook",
                    config =
                        MessagingPlatformUpdate(
                            enabled = true,
                            profile = "work",
                        ),
                )

            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertFalse(body!!.ok)
            assertEquals("webhook", body.platform)
        }
}
