package com.m57.hermescontrol.ui.chat.components

import com.m57.hermescontrol.ExternalActivityLifecycleGuard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PendingExternalLaunchTest {
    @Before
    fun setUp() {
        ExternalActivityLifecycleGuard.resetForTest()
    }

    @After
    fun tearDown() {
        ExternalActivityLifecycleGuard.resetForTest()
    }

    @Test
    fun disposeWithOutstandingLaunch_releasesTheLease() {
        var releases = 0
        val pending = PendingExternalLaunch(ExternalActivityLifecycleGuard::externalActivityReturned)
        launchWithFakeLease { releases++ }
        pending.launched()

        pending.disposed(changingConfigurations = false)

        assertEquals(1, releases)
    }

    @Test
    fun disposeForConfigurationChange_keepsTheLeaseForTheRedeliveredResult() {
        var releases = 0
        val pending = PendingExternalLaunch(ExternalActivityLifecycleGuard::externalActivityReturned)
        launchWithFakeLease { releases++ }
        pending.launched()

        pending.disposed(changingConfigurations = true)

        assertEquals(0, releases)
    }

    @Test
    fun disposeAfterResult_doesNotSignalAgain() {
        var signals = 0
        val pending = PendingExternalLaunch { signals++ }
        pending.launched()
        pending.returned()

        pending.disposed(changingConfigurations = false)

        assertEquals(1, signals)
    }

    @Test
    fun disposeWithoutLaunch_doesNotTouchAnotherOwnersLease() {
        var releases = 0
        val pending = PendingExternalLaunch(ExternalActivityLifecycleGuard::externalActivityReturned)
        launchWithFakeLease { releases++ }

        pending.disposed(changingConfigurations = false)

        assertEquals(0, releases)
    }

    private fun launchWithFakeLease(release: () -> Unit) {
        ExternalActivityLifecycleGuard.launchExternalActivity(
            acquireConnectionLease = {},
            releaseConnectionLease = release,
            prepareForBackground = {},
            cleanupAfterLaunchFailure = {},
            scheduleTimeout = { _, _ -> },
            launch = {},
        )
    }
}
