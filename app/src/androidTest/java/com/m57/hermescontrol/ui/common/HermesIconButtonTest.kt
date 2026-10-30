package com.m57.hermescontrol.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.theme.HermesControlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** #1651: a HermesIconButton's label is its tooltip and its TalkBack name. */
@RunWith(AndroidJUnit4::class)
@MediumTest
class HermesIconButtonTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun longPress_showsTheLabelTooltip() {
        composeTestRule.setContent {
            HermesControlTheme {
                HermesIconButton(
                    imageVector = Icons.Filled.Refresh,
                    label = "Refresh",
                    onClick = {},
                )
            }
        }

        // Only the icon exists before the long press; the tooltip text does not.
        composeTestRule.onNodeWithText("Refresh").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Refresh").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("Refresh").assertIsDisplayed()
    }

    @Test
    fun tap_invokesOnClick_andKeepsTheTooltipHidden() {
        var clicks = 0
        composeTestRule.setContent {
            HermesControlTheme {
                HermesIconButton(
                    imageVector = Icons.Filled.Refresh,
                    label = "Refresh",
                    onClick = { clicks++ },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Refresh").performClick()
        composeTestRule.runOnIdle { assertEquals(1, clicks) }
        composeTestRule.onNodeWithText("Refresh").assertDoesNotExist()
    }

    @Test
    fun label_isTheSingleAccessibilityAnnouncement() {
        composeTestRule.setContent {
            HermesControlTheme {
                HermesIconButton(
                    imageVector = Icons.Filled.Refresh,
                    label = "Refresh",
                    onClick = {},
                )
            }
        }

        // One node announces the label: no duplicate TalkBack description.
        composeTestRule.onAllNodesWithContentDescription("Refresh").assertCountEquals(1)
    }

    @Test
    fun hermesTooltip_longPressShowsLabel_andKeepsInnerDescriptionSingle() {
        composeTestRule.setContent {
            HermesControlTheme {
                HermesTooltip("Delete") {
                    IconButton(onClick = {}) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Delete")
                    }
                }
            }
        }

        composeTestRule.onAllNodesWithContentDescription("Delete").assertCountEquals(1)
        composeTestRule.onNodeWithText("Delete").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Delete").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("Delete").assertIsDisplayed()
    }
}
