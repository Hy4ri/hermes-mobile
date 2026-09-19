package com.m57.hermescontrol.ui.settings.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.data.update.AppUpdateState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The opt-in release-candidate switch on the About tab: stable-only default,
 * one callback per tap, and no channel flip while an APK is in flight.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class AboutSectionReleaseCandidateTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun stableOnlyByDefault_tapReportsOptIn() {
        var reported: Boolean? = null
        composeTestRule.setContent {
            AboutSection(
                updateState = AppUpdateState.Idle,
                checkReleaseCandidateUpdates = false,
                onCheckReleaseCandidateUpdatesChange = { reported = it },
            )
        }

        composeTestRule.onNodeWithText(RC_TITLE).assertIsDisplayed()
        composeTestRule.onNode(isToggleable()).assertIsOff()

        composeTestRule.onNode(isToggleable()).performClick()

        assertEquals(true, reported)
    }

    @Test
    fun optedIn_switchShowsChecked() {
        composeTestRule.setContent {
            AboutSection(
                updateState = AppUpdateState.Idle,
                checkReleaseCandidateUpdates = true,
            )
        }

        composeTestRule.onNode(isToggleable()).assertIsOn()
    }

    @Test
    fun downloading_disablesTheChannelSwitch() {
        composeTestRule.setContent {
            AboutSection(
                updateState = AppUpdateState.Downloading(0.5f),
                checkReleaseCandidateUpdates = false,
            )
        }

        composeTestRule.onNode(isToggleable()).assertIsNotEnabled()
    }

    @Test
    fun installing_disablesTheChannelSwitch() {
        composeTestRule.setContent {
            AboutSection(
                updateState = AppUpdateState.Installing("v1.25.0-rc.3"),
                checkReleaseCandidateUpdates = true,
            )
        }

        composeTestRule.onNode(isToggleable()).assertIsNotEnabled()
    }

    @Test
    fun idle_keepsTheChannelSwitchEnabled() {
        composeTestRule.setContent {
            AboutSection(updateState = AppUpdateState.UpToDate("v1.24.2"))
        }

        composeTestRule.onNode(isToggleable()).assertIsEnabled()
    }

    private companion object {
        const val RC_TITLE = "Check release candidate updates"
    }
}
