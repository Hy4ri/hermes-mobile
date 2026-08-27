package com.m57.hermescontrol

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExternalActivityLifecycleGuardTest {
    @Before
    fun setUp() {
        ExternalActivityLifecycleGuard.resetForTest()
    }

    @After
    fun tearDown() {
        ExternalActivityLifecycleGuard.resetForTest()
    }

    @Test
    fun externalActivityLaunch_acquiresConnectionLeaseBeforePreparingServiceAndLaunching() {
        val calls = mutableListOf<String>()

        ExternalActivityLifecycleGuard.launchExternalActivity(
            acquireConnectionLease = { calls += "acquire" },
            releaseConnectionLease = { calls += "release" },
            prepareForBackground = { calls += "prepare" },
            cleanupAfterLaunchFailure = { calls += "cleanup" },
            scheduleTimeout = { _, _ -> },
            launch = { calls += "launch" },
        )

        assertEquals(listOf("acquire", "prepare", "launch"), calls)
    }

    @Test
    fun transientResumeDuringPicker_doesNotReleaseLeaseUntilResultReturns() {
        var released = false

        ExternalActivityLifecycleGuard.launchExternalActivity(
            acquireConnectionLease = {},
            releaseConnectionLease = { released = true },
            prepareForBackground = {},
            cleanupAfterLaunchFailure = {},
            scheduleTimeout = { _, _ -> },
            launch = {},
        )
        ExternalActivityLifecycleGuard.onHostPaused()
        ExternalActivityLifecycleGuard.onHostResumed()

        assertFalse(released)

        ExternalActivityLifecycleGuard.onHostPaused()
        ExternalActivityLifecycleGuard.externalActivityReturned()

        assertFalse(released)

        ExternalActivityLifecycleGuard.onHostResumed()

        assertTrue(released)
    }

    @Test
    fun abandonedPicker_timeoutReleasesConnectionLease() {
        var timeoutAction: (() -> Unit)? = null
        var released = false

        ExternalActivityLifecycleGuard.launchExternalActivity(
            acquireConnectionLease = {},
            releaseConnectionLease = { released = true },
            prepareForBackground = {},
            cleanupAfterLaunchFailure = {},
            scheduleTimeout = { _, action -> timeoutAction = action },
            launch = {},
        )
        ExternalActivityLifecycleGuard.onHostPaused()

        timeoutAction?.invoke()

        assertTrue(released)
    }

    @Test
    fun failedExternalActivityLaunch_cleansUpServiceAndConnectionLease() {
        val calls = mutableListOf<String>()

        runCatching {
            ExternalActivityLifecycleGuard.launchExternalActivity(
                acquireConnectionLease = { calls += "acquire" },
                releaseConnectionLease = { calls += "release" },
                prepareForBackground = { calls += "prepare" },
                cleanupAfterLaunchFailure = { calls += "cleanup" },
                scheduleTimeout = { _, _ -> },
                launch = { error("picker unavailable") },
            )
        }

        assertEquals(listOf("acquire", "prepare", "cleanup", "release"), calls)
    }
}
