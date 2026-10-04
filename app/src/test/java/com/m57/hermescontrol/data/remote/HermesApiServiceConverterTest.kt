package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.model.CredentialPoolAddRequest
import com.m57.hermescontrol.data.model.DebugShareRequest
import com.m57.hermescontrol.data.model.HookCreateRequest
import com.m57.hermescontrol.data.model.HookDeleteRequest
import com.m57.hermescontrol.data.model.McpCatalogInstallRequest
import com.m57.hermescontrol.data.model.MessagingPlatformUpdate
import com.m57.hermescontrol.data.model.replaceMcpEnvValue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Regression for #1402: `Map<String, Any>` bodies/results have no kotlinx serializer, so Retrofit failed to
 * create the converter before any request was sent. These call the real service methods so converter creation
 * (which JSON round-trip tests never exercise) is covered.
 */
class HermesApiServiceConverterTest {
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

    private fun ok(body: String = """{"ok":true}""") =
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))

    @Test
    fun catalogInstall_readsResponse() =
        runBlocking {
            ok("""{"ok":true,"name":"example","background":false}""")
            val r = api.installMcpCatalogEntry(McpCatalogInstallRequest(name = "example"))
            assertTrue(r.isSuccessful)
            assertEquals("example", r.body()?.name)
            assertEquals("/api/mcp/catalog/install", server.takeRequest().path)
        }

    @Test
    fun replaceMcpServers_preservesSavedDefinitionsAndDeletesEnvKey() =
        runBlocking {
            ok(
                """
                {"mcp_servers":{
                  "s":{"command":"tool","enabled":false,"headers":{"Authorization":"${'$'}{TOKEN}"},
                       "env":{"OLD":"value","KEEP":"${'$'}{KEY}"},"future":{"nested":true}},
                  "other":{"url":"https://example.com"}
                }}
                """.trimIndent(),
            )
            val config = api.getSavedConfig("work").body()!!
            assertEquals("/api/config?profile=work&include_defaults=false", server.takeRequest().path)
            ok()
            assertTrue(api.replaceMcpServers(replaceMcpEnvValue(config, "s", "OLD", null, "work")).isSuccessful)
            val req = server.takeRequest()
            assertEquals("PUT", req.method)
            assertEquals("/api/mcp/servers", req.path)
            val body = OkHttpProvider.json.parseToJsonElement(req.body.readUtf8()).jsonObject
            assertEquals(JsonPrimitive("work"), body["profile"])
            val savedServers = config.getValue("mcp_servers").jsonObject
            val replaced = body.getValue("servers").jsonObject
            assertEquals(savedServers["other"], replaced["other"])
            val original = savedServers.getValue("s").jsonObject
            val updated = replaced.getValue("s").jsonObject
            assertEquals(original - "env", updated - "env")
            assertEquals(original.getValue("env").jsonObject - "OLD", updated.getValue("env").jsonObject)
        }

    @Test
    fun disconnectPlatform_sendsScopedDisableAndCredentialClear() =
        runBlocking {
            ok("""{"ok":true,"platform":"telegram","hot_served":true}""")
            val response =
                api.configurePlatform(
                    "telegram",
                    MessagingPlatformUpdate(
                        enabled = false,
                        clearEnv = listOf("TELEGRAM_BOT_TOKEN"),
                        profile = "work",
                    ),
                )
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/api/messaging/platforms/telegram", request.path)
            assertEquals(
                """{"enabled":false,"clear_env":["TELEGRAM_BOT_TOKEN"],"profile":"work"}""",
                request.body.readUtf8(),
            )
            assertEquals(true, response.body()?.hotServed)
        }

    @Test
    fun curatorPaused_readsResponse() =
        runBlocking {
            ok("""{"ok":true,"paused":true}""")
            assertTrue(api.setCuratorPaused(mapOf("paused" to true)).isSuccessful)
            assertEquals("""{"paused":true}""", server.takeRequest().body.readUtf8())
        }

    @Test
    fun credentialPool_addSendsApiKeyField_removeReadsResponse() =
        runBlocking {
            ok("""{"ok":true,"provider":"openrouter","count":1}""")
            assertTrue(api.addCredentialPoolEntry(CredentialPoolAddRequest("openrouter", "sk", "lbl")).isSuccessful)
            assertEquals(
                """{"provider":"openrouter","api_key":"sk","label":"lbl"}""",
                server.takeRequest().body.readUtf8(),
            )
            ok("""{"ok":true,"provider":"openrouter","count":0,"cleaned":[],"hints":[]}""")
            assertTrue(api.removeCredentialPoolEntry("openrouter", 1).isSuccessful)
        }

    @Test
    fun hooks_createSendsApprove_deleteSendsBody() =
        runBlocking {
            ok("""{"ok":true,"event":"e","command":"c","approved":true}""")
            assertTrue(api.createHook(HookCreateRequest("e", "c", timeout = 5, approve = true)).isSuccessful)
            assertEquals(
                """{"event":"e","command":"c","timeout":5,"approve":true}""",
                server.takeRequest().body.readUtf8(),
            )
            ok()
            assertTrue(api.deleteHook(HookDeleteRequest("e", "c")).isSuccessful)
            val del = server.takeRequest()
            assertEquals("DELETE", del.method)
            assertEquals("""{"event":"e","command":"c"}""", del.body.readUtf8())
        }

    @Test
    fun debugShare_sendsRedact() =
        runBlocking {
            ok("""{"ok":true,"urls":{"report":"u"}}""")
            assertTrue(api.runDebugShare(DebugShareRequest(redact = false)).isSuccessful)
            assertEquals("""{"redact":false}""", server.takeRequest().body.readUtf8())
        }
}
