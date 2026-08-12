package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression tests for the ReasoningCard "Show full" crash (issue #882).
 *
 * Inside a LazyColumn item the main axis is unbounded, so a verticalScroll
 * measured with an infinite max height throws
 * IllegalStateException ("Vertically scrollable component was measured with an
 * infinity maximum height constraints"). The old implementation passed
 * `heightIn(max = Dp.Unspecified)` once "Show full" was tapped, which lifted
 * the cap entirely and reproduced exactly that crash.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class ReasoningCardTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    /** Long enough to overflow the ~40%-of-screen collapsed cap (~22 lines). */
    private val longReasoning =
        buildString {
            repeat(60) { index -> appendLine("reasoning line $index") }
        }

    private fun renderInLazyColumn(reasoningText: String) {
        composeTestRule.setContent {
            LazyColumn {
                item(key = "reasoning") {
                    ReasoningCard(reasoningText = reasoningText)
                }
            }
        }
    }

    @Test
    fun showFull_insideLazyColumn_doesNotCrash() {
        renderInLazyColumn(longReasoning)

        // Expand the card.
        composeTestRule.onNodeWithTag("reasoning_card").performClick()
        composeTestRule.onNodeWithText("Show full").assertIsDisplayed()

        // "Show full" lifts the cap — must NOT crash the layout pass.
        composeTestRule.onNodeWithText("Show full").performClick()
        // The card now grows taller than the viewport, so the toggle button
        // sits below the fold — scroll it into view before asserting/clicking.
        composeTestRule.onNodeWithText("Show less").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Show less").performClick()

        // And back down again.
        composeTestRule.onNodeWithText("Show full").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun shortReasoning_noOverflow_noShowFullButton() {
        renderInLazyColumn("short reasoning")

        composeTestRule.onNodeWithTag("reasoning_card").performClick()
        composeTestRule.onNodeWithText("Show full").assertDoesNotExist()
    }
}
