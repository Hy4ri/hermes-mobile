package com.m57.hermescontrol.ui.common

import com.m57.hermescontrol.data.ws.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineBannerTest {
    @Test
    fun `only healthy states hide the banner`() {
        val offline = ConnectionStatus.entries.filter { it.isOffline() }.toSet()
        assertEquals(
            setOf(
                ConnectionStatus.DISCONNECTED,
                ConnectionStatus.RECONNECTING,
                ConnectionStatus.NO_NETWORK,
                ConnectionStatus.AUTH_EXPIRED,
            ),
            offline,
        )
    }
}
