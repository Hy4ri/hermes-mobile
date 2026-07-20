package com.m57.hermescontrol

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.theme.BottomNavDisplayMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [Modifier.bottomNavigationHeight].
 *
 * Each compact bottom-nav display mode maps to a specific bar height:
 *  - [BottomNavDisplayMode.ICON_ONLY]      -> 56.dp (fixed)
 *  - [BottomNavDisplayMode.TEXT_ONLY]      -> 44.dp (fixed)
 *  - [BottomNavDisplayMode.ICON_AND_TEXT]  -> min 80.dp (can grow)
 *
 * We apply the modifier to a tagged [Box] and assert the resulting height on
 * a real Compose host (this requires the instrumentation test runner, hence
 * androidTest rather than the Robolectric-free JVM test source set).
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class BottomNavigationLayoutTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun iconOnly_barHeightIs56dp() {
        composeTestRule.setContent {
            Box(Modifier.testTag("bar").bottomNavigationHeight(BottomNavDisplayMode.ICON_ONLY))
        }
        composeTestRule.onNodeWithTag("bar").assertHeightIsEqualTo(56.dp)
    }

    @Test
    fun textOnly_barHeightIs44dp() {
        composeTestRule.setContent {
            Box(Modifier.testTag("bar").bottomNavigationHeight(BottomNavDisplayMode.TEXT_ONLY))
        }
        composeTestRule.onNodeWithTag("bar").assertHeightIsEqualTo(44.dp)
    }

    @Test
    fun iconAndText_barHeightIsAtLeast80dp() {
        composeTestRule.setContent {
            Box(Modifier.testTag("bar").bottomNavigationHeight(BottomNavDisplayMode.ICON_AND_TEXT))
        }
        // heightIn(min = 80.dp) — assert the measured height meets the minimum.
        composeTestRule.onNodeWithTag("bar").assertHeightIsAtLeast(80.dp)
    }
}
