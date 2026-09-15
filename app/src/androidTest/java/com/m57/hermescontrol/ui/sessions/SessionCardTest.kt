package com.m57.hermescontrol.ui.sessions

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.sessions.components.SessionCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * History card content: project, activity age (with a spoken description), preview and footer.
 * The card is clickable, so it merges its children; finders read the unmerged tree.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class SessionCardTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val nowMillis = 1_800_000_000_000L
    private var clicks = 0

    private fun setCard(
        session: SessionInfo,
        project: SessionProject?,
    ) {
        composeTestRule.setContent {
            HermesControlTheme {
                SessionCard(
                    session = session,
                    displayTitle = session.title ?: "Untitled",
                    branchStem = null,
                    query = "",
                    isSelecting = false,
                    isSelected = false,
                    isDeleting = false,
                    isPinned = false,
                    project = project,
                    nowMillis = nowMillis,
                    highlightBackground = Color.Unspecified,
                    highlightForeground = Color.Unspecified,
                    onCardClick = { clicks++ },
                    onToggleSelection = {},
                    onSelect = {},
                    onRename = {},
                    onTogglePin = {},
                    onToggleHide = {},
                    onDelete = {},
                )
            }
        }
    }

    @Test
    fun rendersProjectAgePreviewAndFooter() {
        setCard(
            session =
                SessionInfo(
                    id = "s1",
                    title = "Restyle the palette",
                    preview = "Can you check\nthe contrast?",
                    model = "gpt-5.5",
                    message_count = 24,
                    last_active = nowMillis / 1000.0 - 2 * 3600,
                ),
            project = SessionProject(label = "Hermes", color = "hsl(120 68% 58%)"),
        )

        composeTestRule.onNodeWithTag("session_project_s1", useUnmergedTree = true).assertTextEquals("Hermes")
        composeTestRule
            .onNodeWithTag("session_age_s1", useUnmergedTree = true)
            .assertTextEquals("2h")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Active 2 hours ago")))
        composeTestRule.onNodeWithText("Restyle the palette", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(
                "session_preview_s1",
                useUnmergedTree = true,
            ).assertTextEquals("Can you check the contrast?")
        composeTestRule.onNodeWithText("gpt-5.5", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("24 messages", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun sessionWithoutWorkspace_showsHome_andNoPreviewLine() {
        setCard(
            session = SessionInfo(id = "s2", title = "Quick question", message_count = 1),
            project = null,
        )

        composeTestRule.onNodeWithTag("session_project_s2", useUnmergedTree = true).assertTextEquals("Home")
        composeTestRule.onNodeWithTag("session_preview_s2", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("session_age_s2", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("1 message", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun tappingTheCard_opensIt() {
        setCard(session = SessionInfo(id = "s3", title = "Open me"), project = null)

        composeTestRule.onNodeWithTag("session_card_s3").performClick()

        composeTestRule.runOnIdle { assertEquals(1, clicks) }
    }
}
