package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthManager
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ProfileScopeInterceptorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        mockkObject(AuthManager)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkAll()
    }

    /** Builds a real client with the interceptor + a stubbed AuthManager profile. */
    private fun clientFor(profile: String?): OkHttpClient {
        every { AuthManager.getSelectedProfileId() } returns profile
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
        every { AuthManager.getSelectedProfileId() } returns "work"
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
        every { AuthManager.getSelectedProfileId() } returns "work"
        val client = OkHttpClient.Builder().addInterceptor(ProfileScopeInterceptor).build()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val req = Request.Builder().url(server.url("api/cron/jobs")).build()
        client.newCall(req).execute().close()
        val url = server.takeRequest().requestUrl!!
        assertNull(url.queryParameter("profile"))
        assertEquals("/api/cron/jobs", url.encodedPath)
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
}
