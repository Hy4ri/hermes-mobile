package com.m57.hermescontrol.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression net for the About-tab crash (issue: opening About threw
 * NoSuchMethodException AppUpdateViewModel.<init>(Application) — the default
 * AndroidViewModelFactory resolves ctors via reflection, which cannot see the
 * Kotlin default-arg synthetic constructor). The test deliberately does NOT
 * inject a view model: the default `viewModel { ... }` factory lambda must
 * build the real [AppUpdateViewModel] without crashing.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class SettingsAboutPageTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun aboutPage_opensWithoutCrashing() {
        composeTestRule.setContent {
            SettingsAboutPage(onBack = {})
        }

        composeTestRule.onNodeWithText("About").assertIsDisplayed()
    }
}
