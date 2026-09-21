package com.m57.hermescontrol.ui.chat.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.chat.ContextBreakdown
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for backend-reported context occupancy.
 *
 * Cumulative REST input-token accounting must never masquerade as the live
 * context-window numerator when the backend has not reported context_used.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class ContextUsageComponentsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun chip_missingBackendOccupancy_doesNotShowFakeZeroPercent() {
        composeTestRule.setContent {
            HermesControlTheme {
                ContextUsageChip(
                    usedTokens = null,
                    fullTokens = 128_000L,
                )
            }
        }

        composeTestRule
            .onNodeWithText("— / 128k context")
            .assertIsDisplayed()

        composeTestRule
            .onNodeWithText("0%")
            .assertDoesNotExist()
    }

    @Test
    fun chip_backendOccupancyAvailable_showsDeterminateUsage() {
        composeTestRule.setContent {
            HermesControlTheme {
                ContextUsageChip(
                    usedTokens = 64_000L,
                    fullTokens = 128_000L,
                )
            }
        }

        composeTestRule
            .onNodeWithText("64k / 128k context")
            .assertIsDisplayed()

        composeTestRule
            .onNodeWithText("50%")
            .assertIsDisplayed()
    }

    @Test
    fun detailSheet_missingBackendOccupancy_doesNotUseCumulativeInputTokensAsNumerator() {
        composeTestRule.setContent {
            HermesControlTheme {
                ContextDetailSheet(
                    breakdown =
                        ContextBreakdown(
                            inputTokens = 12_300L,
                            outputTokens = 2_000L,
                            cacheReadTokens = 500L,
                            cacheWriteTokens = 250L,
                            reasoningTokens = 100L,
                            messageCount = 4,
                        ),
                    usedTokens = null,
                    fullTokens = 128_000L,
                    onDismiss = {},
                )
            }
        }

        // The live numerator is unavailable, even though cumulative REST
        // input-token accounting exists below in the detail breakdown.
        composeTestRule
            .onNodeWithText("—")
            .assertIsDisplayed()

        composeTestRule
            .onNodeWithText("Context window usage unavailable")
            .assertIsDisplayed()

        // Cumulative accounting is still preserved as informational detail.
        composeTestRule
            .onNodeWithText("12.3k")
            .assertIsDisplayed()

        // Most important regression guard: null occupancy is not rendered as
        // a determinate zero-percent context state.
        composeTestRule
            .onNodeWithText("0% of context window used")
            .assertDoesNotExist()
    }
}
