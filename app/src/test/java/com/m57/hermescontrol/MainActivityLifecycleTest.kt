package com.m57.hermescontrol

import com.m57.hermescontrol.notification.NotificationHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class MainActivityLifecycleTest {
    @After
    fun tearDown() {
        unmockkAll()
        ExternalActivityLifecycleGuard.resetForTest()
    }

    @Test
    fun `pause prepares notifications without declaring the app backgrounded`() {
        mockkObject(NotificationHelper)
        every { NotificationHelper.start(any()) } returns Unit
        every { NotificationHelper.setAppForeground(any(), any()) } returns Unit
        val activity = mockk<MainActivity>(relaxed = true)
        every { activity invoke "onPause" withArguments emptyList() } answers { callOriginal() }

        invokeLifecycle(activity, "onPause")

        verify(exactly = 1) { NotificationHelper.start(activity) }
        verify(exactly = 0) { NotificationHelper.setAppForeground(activity, false) }
    }

    @Test
    fun `configuration stop keeps the shared connection foregrounded`() {
        mockkObject(NotificationHelper)
        every { NotificationHelper.setAppForeground(any(), any()) } returns Unit
        val activity = mockk<MainActivity>(relaxed = true)
        every { activity.isChangingConfigurations } returns true
        every { activity invoke "onStop" withArguments emptyList() } answers { callOriginal() }

        invokeLifecycle(activity, "onStop")

        verify(exactly = 0) { NotificationHelper.setAppForeground(activity, false) }
    }

    @Test
    fun `real background stop still enables idle socket cleanup`() {
        mockkObject(NotificationHelper)
        every { NotificationHelper.setAppForeground(any(), any()) } returns Unit
        val activity = mockk<MainActivity>(relaxed = true)
        every { activity.isChangingConfigurations } returns false
        every { activity invoke "onStop" withArguments emptyList() } answers { callOriginal() }

        invokeLifecycle(activity, "onStop")

        verify(exactly = 1) { NotificationHelper.setAppForeground(activity, false) }
    }

    private fun invokeLifecycle(
        activity: MainActivity,
        method: String,
    ) {
        try {
            MainActivity::class.java
                .getDeclaredMethod(method)
                .apply { isAccessible = true }
                .invoke(activity)
        } catch (error: InvocationTargetException) {
            // Execute real app code; only the trailing Android framework stub is unavailable on the JVM.
            val expected = "Method $method in android.app.Activity not mocked."
            if (error.targetException.message?.startsWith(expected) != true) throw error
        }
    }
}
