package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.model.CredentialPoolAddRequest
import com.m57.hermescontrol.data.model.DebugShareRequest
import com.m57.hermescontrol.data.model.HookCreateRequest
import com.m57.hermescontrol.data.model.HookDeleteRequest
import com.m57.hermescontrol.data.model.McpCatalogInstallRequest
import com.m57.hermescontrol.data.model.McpServerUpdateRequest
import kotlinx.coroutines.runBlocking
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
    fun updateMcpServer_sendsEnvBody() =
        runBlocking {
            ok("""{"name":"s","enabled":true}""")
            api.updateMcpServer("s", McpServerUpdateRequest(env = mapOf("K" to "v")))
            val req = server.takeRequest()
            assertEquals("PUT", req.method)
            assertEquals("""{"env":{"K":"v"}}""", req.body.readUtf8())
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
