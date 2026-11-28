package com.m57.hermescontrol.data.update

import android.content.Context
import com.m57.hermescontrol.data.local.AuthManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class WhatsNewNoticeTest {
    private val context = mockk<Context>(relaxed = true)
    private val checker = mockk<AppUpdateChecker>()

    @Before
    fun setUp() {
        UpdateNoticeManager.enabled = true
        mockkObject(AuthManager)
        every { AuthManager.getLastSeenVersion() } returns "3.3.0"
        every { AuthManager.setLastSeenVersion(any()) } returns Unit
    }

    @After
    fun tearDown() {
        WhatsNewNotice.dismiss("3.4.0")
        UpdateNoticeManager.enabled = false
        unmockkAll()
    }

    @Test
    fun releaseWithOnlyAChangelogLink_isMarkedSeenWithoutADialog() =
        runTest {
            val changelogOnly = "**Full Changelog**: https://github.com/o/r/compare/v3.3.0...v3.4.0"
            coEvery { checker.fetchReleaseByTag("v3.4.0") } returns UpdateInfo(tagName = "v3.4.0", body = changelogOnly)

            WhatsNewNotice.checkOnLaunch(context, checker, currentVersion = "3.4.0")

            assertNull(WhatsNewNotice.release)
            verify { AuthManager.setLastSeenVersion("3.4.0") }
        }

    @Test
    fun releaseWithUserFacingNotes_showsTheDialog() =
        runTest {
            val info =
                UpdateInfo(
                    tagName = "v3.4.0",
                    body = "* fix(chat): keep scroll position by @a in https://github.com/o/r/pull/1",
                )
            coEvery { checker.fetchReleaseByTag("v3.4.0") } returns info

            WhatsNewNotice.checkOnLaunch(context, checker, currentVersion = "3.4.0")

            assertEquals(info, WhatsNewNotice.release)
            verify(exactly = 0) { AuthManager.setLastSeenVersion(any()) }
        }
}
