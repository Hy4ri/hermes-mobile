package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.ws.ConnectionHealth
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class OkHttpProviderWebSocketTest {
    @Before
    fun setUp() {
        mockSystemServerTrust()
        CookieManager.setJarForTest(buildFakePersistentCookieJar())
    }

    @After
    fun tearDown() {
        unmockkObject(ServerTrust)
    }

    @Test
    fun websocketPongToleranceMatchesHeartbeatStaleLimit() {
        // Issue #1701: OkHttp fails the socket when a pong is one interval late. Both timers must
        // allow the same silence, or the shorter one drops slow-link sockets on its own.
        assertEquals(ConnectionHealth.STALE_THRESHOLD_MS, OkHttpProvider.websocket.pingIntervalMillis.toLong())
    }
}
