package com.m57.hermescontrol.ui.common

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI tests for [LoadingState], [ErrorState], and [EmptyState].
 *
 * This is the **first Compose UI test in the repo** (TEST-15, issue #293).
 * It establishes the testing pattern for future composable tests.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class StateViewsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // ── LoadingState ────────────────────────────────────────────────────

    @Test
    fun loadingState_displaysCenteredSpinner() {
        composeTestRule.setContent { LoadingState() }

        composeTestRule.onNodeWithTag("loading_state").assertIsDisplayed()
        composeTestRule.onNodeWithTag("loading_spinner").assertIsDisplayed()
    }

    // ── ErrorState ──────────────────────────────────────────────────────

    @Test
    fun errorState_displaysMessage() {
        val message = "Something went wrong"
        composeTestRule.setContent { ErrorState(message = message) }

        composeTestRule.onNodeWithText(message).assertIsDisplayed()
    }

    @Test
    fun errorState_withRetry_showsRetryButton() {
        var retryClicked = false
        composeTestRule.setContent {
            ErrorState(
                message = "Try again",
                onRetry = { retryClicked = true },
            )
        }

        composeTestRule.onNodeWithTag("error_retry_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("error_retry_button").assertHasClickAction()
        composeTestRule.onNodeWithTag("error_retry_button").performClick()
        assert(retryClicked) { "Retry callback was not invoked on click" }
    }

    @Test
    fun errorState_withoutRetry_hidesRetryButton() {
        composeTestRule.setContent { ErrorState(message = "No retry") }

        composeTestRule.onNodeWithTag("error_retry_button").assertDoesNotExist()
    }

    // ── EmptyState ──────────────────────────────────────────────────────

    @Test
    fun emptyState_displaysTitle() {
        val title = "No items"
        composeTestRule.setContent { EmptyState(title = title) }

        composeTestRule.onNodeWithText(title).assertIsDisplayed()
    }

    @Test
    fun emptyState_withSubtitle_displaysSubtitle() {
        composeTestRule.setContent { EmptyState(title = "Empty", subtitle = "Add something") }

        composeTestRule.onNodeWithText("Add something").assertIsDisplayed()
    }

    @Test
    fun emptyState_withoutSubtitle_doesNotShowSubtitle() {
        composeTestRule.setContent { EmptyState(title = "Empty") }

        composeTestRule.onNodeWithText("Add something").assertDoesNotExist()
    }

    @Test
    fun emptyState_withAction_showsActionButton() {
        var actionClicked = false
        composeTestRule.setContent {
            EmptyState(
                title = "No data",
                actionLabel = "Create",
                onAction = { actionClicked = true },
            )
        }

        composeTestRule.onNodeWithTag("empty_state_action").assertIsDisplayed()
        composeTestRule.onNodeWithTag("empty_state_action").assertHasClickAction()
        composeTestRule.onNodeWithTag("empty_state_action").performClick()
        assert(actionClicked) { "Action callback was not invoked on click" }
    }

    @Test
    fun emptyState_withoutAction_hidesActionButton() {
        composeTestRule.setContent { EmptyState(title = "Just text") }

        composeTestRule.onNodeWithTag("empty_state_action").assertDoesNotExist()
    }
}
