package com.m57.hermescontrol.ui.sessions

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionLiveStatus
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.sessions.components.SearchResultCard
import com.m57.hermescontrol.ui.sessions.components.SessionCard
import com.m57.hermescontrol.ui.sessions.components.SessionLiveStatusIndicator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class SessionLiveStatusIndicatorTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun workingStatus_displaysRunningIndicator() {
        composeTestRule.setContent {
            HermesControlTheme {
                SessionLiveStatusIndicator(
                    liveStatus = SessionLiveStatus.WORKING,
                    sessionId = "sess-working",
                )
            }
        }

        composeTestRule.onNodeWithTag("session_live_status_sess-working").assertIsDisplayed()
        composeTestRule.onNodeWithText("Running").assertIsDisplayed()
    }

    @Test
    fun waitingStatus_displaysNeedsInputIndicator() {
        composeTestRule.setContent {
            HermesControlTheme {
                SessionLiveStatusIndicator(
                    liveStatus = SessionLiveStatus.WAITING,
                    sessionId = "sess-waiting",
                )
            }
        }

        composeTestRule.onNodeWithTag("session_live_status_sess-waiting").assertIsDisplayed()
        composeTestRule.onNodeWithText("Needs input").assertIsDisplayed()
    }

    @Test
    fun sessionCard_withLiveStatus_displaysIndicator() {
        val session =
            SessionInfo(
                id = "sess-card-working",
                title = "Working session",
                message_count = 5,
            )

        composeTestRule.setContent {
            HermesControlTheme {
                SessionCard(
                    session = session,
                    displayTitle = "Working session",
                    branchStem = null,
                    isFork = false,
                    forkDepth = 0,
                    query = "",
                    isSelecting = false,
                    isSelected = false,
                    isDeleting = false,
                    isPinned = false,
                    isHidden = false,
                    liveStatus = SessionLiveStatus.WORKING,
                    highlightBackground = Color.Transparent,
                    highlightForeground = Color.Transparent,
                    onCardClick = {},
                    onToggleSelection = {},
                    onSelect = {},
                    onRename = {},
                    onTogglePin = {},
                    onToggleHide = {},
                    onDelete = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("session_card_sess-card-working").assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(
                "session_live_status_sess-card-working",
                useUnmergedTree = true,
            ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Running", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun sessionCard_withoutLiveStatus_hidesIndicator() {
        val session =
            SessionInfo(
                id = "sess-card-idle",
                title = "Idle session",
                message_count = 2,
            )

        composeTestRule.setContent {
            HermesControlTheme {
                SessionCard(
                    session = session,
                    displayTitle = "Idle session",
                    branchStem = null,
                    isFork = false,
                    forkDepth = 0,
                    query = "",
                    isSelecting = false,
                    isSelected = false,
                    isDeleting = false,
                    isPinned = false,
                    isHidden = false,
                    liveStatus = null,
                    highlightBackground = Color.Transparent,
                    highlightForeground = Color.Transparent,
                    onCardClick = {},
                    onToggleSelection = {},
                    onSelect = {},
                    onRename = {},
                    onTogglePin = {},
                    onToggleHide = {},
                    onDelete = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("session_card_sess-card-idle").assertIsDisplayed()
        composeTestRule.onNodeWithTag("session_live_status_sess-card-idle").assertDoesNotExist()
    }

    @Test
    fun searchResultCard_withLiveStatus_displaysIndicator() {
        val session =
            SessionInfo(
                id = "sess-search-waiting",
                preview = "Searching something",
                source = "web",
            )

        composeTestRule.setContent {
            HermesControlTheme {
                SearchResultCard(
                    session = session,
                    query = "Searching",
                    isSelecting = false,
                    isSelected = false,
                    isDeleting = false,
                    liveStatus = SessionLiveStatus.WAITING,
                    highlightBackground = Color.Transparent,
                    highlightForeground = Color.Transparent,
                    onCardClick = {},
                    onToggleSelection = {},
                    onSelect = {},
                    onRename = {},
                    onDelete = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("session_card_sess-search-waiting").assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(
                "session_live_status_sess-search-waiting",
                useUnmergedTree = true,
            ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Needs input", useUnmergedTree = true).assertIsDisplayed()
    }
}
