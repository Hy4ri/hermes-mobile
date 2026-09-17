package com.m57.hermescontrol.ui.sessions

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.sessions.components.SearchResultCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchResultCardTest {
    @get:Rule
    val compose = createComposeRule()

    private var clicks = 0

    private fun show(title: String?) {
        compose.setContent {
            HermesControlTheme {
                SearchResultCard(
                    session = SessionInfo(id = "fixture", title = title, preview = ">>>launch<<< notes excerpt"),
                    query = "launch notes",
                    isSelecting = false,
                    isSelected = false,
                    isDeleting = false,
                    highlightBackground = Color.Unspecified,
                    highlightForeground = Color.Unspecified,
                    onCardClick = { clicks++ },
                    onToggleSelection = {},
                    onSelect = {},
                    onRename = {},
                    onDelete = {},
                )
            }
        }
    }

    @Test
    fun titleAndExcerptAreDistinctAndTapOpensResult() {
        show("Launch checklist")
        compose.onNodeWithTag("search_title_fixture", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Launch checklist", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("launch notes excerpt", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("session_card_fixture").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun legacyResultDoesNotInventATitle() {
        show(null)
        compose.onNodeWithTag("search_title_fixture", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("launch notes excerpt", useUnmergedTree = true).assertIsDisplayed()
    }
}
