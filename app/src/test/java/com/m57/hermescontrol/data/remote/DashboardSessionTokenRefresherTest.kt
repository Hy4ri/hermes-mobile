package com.m57.hermescontrol.data.remote

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
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
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
}
