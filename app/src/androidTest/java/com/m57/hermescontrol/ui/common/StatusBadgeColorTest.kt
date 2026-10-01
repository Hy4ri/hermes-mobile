package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.data.model.SessionLiveStatus
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.theme.ThemePreset
import com.m57.hermescontrol.theme.resolveStatusColors
import com.m57.hermescontrol.ui.sessions.components.SessionLiveStatusIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class StatusBadgeColorTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun latteBadgesRenderStatusFillsAndMatchingOnColors() {
        val types = StatusBadgeType.entries.filter { it != StatusBadgeType.NEUTRAL }
        composeTestRule.setContent {
            HermesControlTheme(themePreference = ThemePreference.LIGHT, themePreset = ThemePreset.CATPPUCCIN) {
                Column {
                    types.forEach { type ->
                        StatusBadge(type.name, type, Modifier.testTag("badge_$type"))
                    }
                }
            }
        }
        val colors = resolveStatusColors(ThemePreset.CATPPUCCIN, darkTheme = false)
        types.forEach { type ->
            val (background, foreground) = requireNotNull(statusBadgeColors(type, colors))
            assertRenderedPair("badge_$type", background.toArgb(), foreground.toArgb())
        }
    }

    @Test
    fun nordLiveIndicatorsRenderStatusFillsAndMatchingOnColors() {
        composeTestRule.setContent {
            HermesControlTheme(themePreference = ThemePreference.LIGHT, themePreset = ThemePreset.NORD) {
                Column {
                    SessionLiveStatusIndicator(SessionLiveStatus.WORKING, "working")
                    SessionLiveStatusIndicator(SessionLiveStatus.WAITING, "waiting")
                }
            }
        }
        val colors = resolveStatusColors(ThemePreset.NORD, darkTheme = false)
        assertRenderedPair("session_live_status_working", colors.success.toArgb(), colors.onSuccess.toArgb())
        assertRenderedPair("session_live_status_waiting", colors.warning.toArgb(), colors.onWarning.toArgb())
    }

    private fun assertRenderedPair(
        tag: String,
        background: Int,
        foreground: Int,
    ) {
        // PR #1417: verify pixels as well as the palette mapping, so a renderer
        // switching back to accent-on-container cannot leave the contrast test green.
        val pixels = composeTestRule.onNodeWithTag(tag).captureToImage().toPixelMap()
        assertEquals("$tag must render the status fill", background, pixels[pixels.width / 2, 1].toArgb())
        assertTrue(
            "$tag must render the matching on-status text/icon",
            (0 until pixels.height).any { y ->
                (0 until pixels.width).any { x -> pixels[x, y].toArgb() == foreground }
            },
        )
    }
}
