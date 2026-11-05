package com.m57.hermescontrol.data.remote

import android.app.Service
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class LoginKeepAliveServiceTest {
    @Test
    fun `service never requests a restart without its login coroutine`() {
        // Invoke the real command handler without constructing an Android Service on the JVM.
        val service = mockk<LoginKeepAliveService>()
        every { service.onStartCommand(any(), any(), any()) } answers { callOriginal() }

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(mockk<Intent>(), 0, 1))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 2))
    }
}
