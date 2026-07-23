package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthManager
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DashboardSessionTokenRefresherTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        mockkObject(OkHttpProvider)
        every { OkHttpProvider.probe } returns OkHttpClient()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        unmockkAll()
    }

    @Test
    fun fetchExtractsInjectedDashboardToken() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<script>window.__HERMES_SESSION_TOKEN__ = "new-token";</script>"""),
        )

        val token = DashboardSessionTokenRefresher.fetch(server.url("/").toString(), OkHttpClient())

        assertEquals("new-token", token)
        assertEquals("/", server.takeRequest().path)
    }

    @Test
    fun fetchReturnsNullWhenDashboardDoesNotInjectToken() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html></html>"))

        val token = DashboardSessionTokenRefresher.fetch(server.url("/").toString(), OkHttpClient())

        assertNull(token)
    }

    @Test
    fun fetchReturnsNullForFailedResponse() {
        server.enqueue(MockResponse().setResponseCode(500))

        val token = DashboardSessionTokenRefresher.fetch(server.url("/").toString(), OkHttpClient())

        assertNull(token)
    }

    @Test
    fun fetchReturnsNullWhenTokenIsBlank() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<script>window.__HERMES_SESSION_TOKEN__ = "";</script>"""),
        )

        val token = DashboardSessionTokenRefresher.fetch(server.url("/").toString(), OkHttpClient())

        assertNull(token)
    }

    @Test
    fun refreshUpdatesAuthManagerOnSuccess() {
        mockkObject(AuthManager)
        every { AuthManager.baseUrl() } returns server.url("/").toString()
        every { AuthManager.setToken(any()) } returns Unit

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<script>window.__HERMES_SESSION_TOKEN__ = "mocked-token";</script>"""),
        )

        val token = DashboardSessionTokenRefresher.refresh()

        assertEquals("mocked-token", token)
        verify { AuthManager.setToken("mocked-token") }
    }

    @Test
    fun refreshReturnsNullOnException() {
        mockkObject(AuthManager)
        every { AuthManager.baseUrl() } throws RuntimeException("Network Error")

        val token = DashboardSessionTokenRefresher.refresh()

        assertNull(token)
    }

    @Test
    fun refreshReturnsNullWhenFetchReturnsNull() {
        mockkObject(AuthManager)
        every { AuthManager.baseUrl() } returns server.url("/").toString()
        // No token returned from fetch
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html></html>"))

        val token = DashboardSessionTokenRefresher.refresh()

        assertNull(token)
        verify(exactly = 0) { AuthManager.setToken(any()) }
    }
}
