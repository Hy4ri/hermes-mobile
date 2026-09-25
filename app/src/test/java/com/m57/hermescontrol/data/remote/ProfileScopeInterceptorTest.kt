package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthManager
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ProfileScopeInterceptorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        // NOTE: deliberately NO mockkObject(AuthManager) — mocking the object
        // from a class that runs after real AuthManager init (AuthManagerTest
        // re-inits + leaks scopes) fails with "Missing mocked calls inside
        // every{}" in suite order. The real prefs-backed setActiveProfileId is
        // runCatching-safe pre-init, so tests use the REAL state instead.
        AuthManager.resetAuthStateForTest()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        AuthManager.resetAuthStateForTest()
    }

    /** Builds a real client with the interceptor + a real active profile state. */
    private fun clientFor(profile: String?): OkHttpClient {
        AuthManager.setActiveProfileId(profile)
        return OkHttpClient
            .Builder()
            .addInterceptor(ProfileScopeInterceptor)
            .build()
    }

    private fun lastRequestedPath(client: OkHttpClient): HttpUrl {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val req = Request.Builder().url(server.url("api/config")).build()
        client.newCall(req).execute().close()
        return server.takeRequest().requestUrl!!
    }

    private fun requestedUrl(
        client: OkHttpClient,
        path: String,
        method: String = "GET",
    ): HttpUrl {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val builder = Request.Builder().url(server.url(path))
        when (method) {
            "POST" -> builder.post("{}".toRequestBody("application/json".toMediaType()))
            "DELETE" -> builder.delete()
            else -> builder.get()
        }
        client.newCall(builder.build()).execute().close()
        return server.takeRequest().requestUrl!!
    }

    @Test
    fun scopedEndpoint_appendsProfile() {
        val client = clientFor("work")
        val url = lastRequestedPath(client)
        assertEquals("work", url.queryParameter("profile"))
        assertEquals("/api/config", url.encodedPath)
    }

    @Test
    fun noActiveProfile_passesThrough() {
        val client = clientFor(null)
        val url = lastRequestedPath(client)
        assertNull(url.queryParameter("profile"))
    }

    @Test
    fun explicitProfileParam_wins() {
        AuthManager.setActiveProfileId("work")
        val client = OkHttpClient.Builder().addInterceptor(ProfileScopeInterceptor).build()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val req =
            Request
                .Builder()
                .url(server.url("api/config?profile=other"))
                .build()
        client.newCall(req).execute().close()
        val url = server.takeRequest().requestUrl!!
        assertEquals("other", url.queryParameter("profile"))
    }

    @Test
    fun nonScopedEndpoint_untouched() {
        // Pairing is machine-global (not profile-scoped on the backend).
        AuthManager.setActiveProfileId("work")
        val client = OkHttpClient.Builder().addInterceptor(ProfileScopeInterceptor).build()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val req = Request.Builder().url(server.url("api/pairing")).build()
        client.newCall(req).execute().close()
        val url = server.takeRequest().requestUrl!!
        assertNull(url.queryParameter("profile"))
        assertEquals("/api/pairing", url.encodedPath)
    }

    @Test
    fun cronSessionsPluginsEndpoints_areScoped() {
        // Issue #781 follow-up: cron, sessions and plugin REST are all
        // profile-scoped on the backend — switching profiles must switch
        // what these screens show, so the interceptor rewrites them too.
        val client = clientFor("work")
        for (path in listOf("api/cron/jobs", "api/sessions", "api/plugins/hermes-achievements/achievements")) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val req = Request.Builder().url(server.url(path)).build()
            client.newCall(req).execute().close()
            val url = server.takeRequest().requestUrl!!
            assertEquals("work", url.queryParameter("profile"))
            assertEquals("/$path", url.encodedPath)
        }
    }

    @Test
    fun skillsEndpoint_isScoped() {
        val client = clientFor("alpha")
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val req = Request.Builder().url(server.url("api/skills")).build()
        client.newCall(req).execute().close()
        val url = server.takeRequest().requestUrl!!
        assertEquals("alpha", url.queryParameter("profile"))
    }

    @Test
    fun memoryEndpoints_areScoped() {
        val client = clientFor("yasmin")
        for (path in listOf("api/memory", "api/memory/learning-graph", "api/memory/providers/mem0/config")) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val req = Request.Builder().url(server.url(path)).build()
            client.newCall(req).execute().close()
            val url = server.takeRequest().requestUrl!!
            assertEquals("yasmin", url.queryParameter("profile"))
            assertEquals("/$path", url.encodedPath)
        }
    }

    @Test
    fun newlyScopedReads_followActiveProfileAcrossSwitches() {
        val client = clientFor("alpha")

        assertEquals("alpha", requestedUrl(client, "api/webhooks").queryParameter("profile"))
        AuthManager.setActiveProfileId("beta")
        assertEquals("beta", requestedUrl(client, "api/credentials/pool").queryParameter("profile"))
        AuthManager.setActiveProfileId("alpha")
        assertEquals("alpha", requestedUrl(client, "api/ops/hooks").queryParameter("profile"))
    }

    @Test
    fun newlyScopedMutations_followActiveProfileAcrossSwitches() {
        val client = clientFor("alpha")

        assertEquals(
            "alpha",
            requestedUrl(client, "api/webhooks/build-events", method = "DELETE").queryParameter("profile"),
        )
        AuthManager.setActiveProfileId("beta")
        assertEquals(
            "beta",
            requestedUrl(client, "api/credentials/pool/openai/1", method = "DELETE").queryParameter("profile"),
        )
        AuthManager.setActiveProfileId("alpha")
        assertEquals(
            "alpha",
            requestedUrl(client, "api/ops/hooks", method = "DELETE").queryParameter("profile"),
        )
    }

    @Test
    fun mobileUsedOpsRoutes_areScopedWithoutBlanketOpsMatch() {
        val client = clientFor("work")
        val paths =
            listOf(
                "api/ops/backup",
                "api/ops/backup/download",
                "api/ops/doctor",
                "api/ops/security-audit",
                "api/ops/prompt-size",
                "api/ops/dump",
                "api/ops/config-migrate",
                "api/ops/import-upload",
                "api/ops/debug-share",
                "api/ops/checkpoints",
                "api/ops/checkpoints/prune",
                "api/ops/hooks",
            )

        for (path in paths) {
            val url = requestedUrl(client, path)
            assertEquals("work", url.queryParameter("profile"))
        }

        val unclassified = requestedUrl(client, "api/ops/machine-global")
        assertNull(unclassified.queryParameter("profile"))
    }

    @Test
    fun destructiveWebhookDelete_avoidsMultiplexUnnamedProfile400() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.requestUrl?.queryParameter("profile").isNullOrBlank()) {
                        MockResponse().setResponseCode(400)
                    } else {
                        MockResponse().setResponseCode(200)
                    }
            }

        val unscopedClient = clientFor(null)
        val unscopedRequest =
            Request
                .Builder()
                .url(server.url("api/webhooks/build-events"))
                .delete()
                .build()
        unscopedClient.newCall(unscopedRequest).execute().use { response ->
            assertEquals(400, response.code)
        }

        val scopedClient = clientFor("beta")
        val scopedRequest =
            Request
                .Builder()
                .url(server.url("api/webhooks/build-events"))
                .delete()
                .build()
        scopedClient.newCall(scopedRequest).execute().use { response ->
            assertEquals(200, response.code)
        }
    }

    @Test
    fun lookalikePath_notScoped() {
        // Sourcery review (PR #540): `startsWith` must not match non-segment
        // suffixes like /api/statusXYZ or /api/gatewayExtra.
        AuthManager.setActiveProfileId("work")
        val client = OkHttpClient.Builder().addInterceptor(ProfileScopeInterceptor).build()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val req = Request.Builder().url(server.url("api/statusXYZ")).build()
        client.newCall(req).execute().close()
        val url = server.takeRequest().requestUrl!!
        assertNull(url.queryParameter("profile"))
        assertEquals("/api/statusXYZ", url.encodedPath)
    }

    @Test
    fun scopedSubPath_isScoped() {
        // A scoped prefix with a trailing segment (/api/status/health) MUST
        // still receive the profile param.
        AuthManager.setActiveProfileId("work")
        val client = OkHttpClient.Builder().addInterceptor(ProfileScopeInterceptor).build()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val req = Request.Builder().url(server.url("api/status/health")).build()
        client.newCall(req).execute().close()
        val url = server.takeRequest().requestUrl!!
        assertEquals("work", url.queryParameter("profile"))
        assertEquals("/api/status/health", url.encodedPath)
    }

    @Test
    fun transcribeRoute_getsTheActiveNonDefaultProfile() {
        // Review (PR #1250): /api/audio/transcribe resolves STT through the
        // request's profile on the backend — the desktop sends this route
        // `...profileScoped()`. Unscoped, a multi-profile host would
        // transcribe under the launch profile instead of the user's pick.
        val client = clientFor("work")

        val url = requestedUrl(client, "api/audio/transcribe", method = "POST")

        assertEquals("work", url.queryParameter("profile"))
        assertEquals("/api/audio/transcribe", url.encodedPath)
    }

    @Test
    fun lookalikeAudioPath_notScoped() {
        val client = clientFor("work")

        val url = requestedUrl(client, "api/audio-extras")

        assertNull(url.queryParameter("profile"))
    }
}
