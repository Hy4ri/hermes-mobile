package com.m57.hermescontrol.ui.kanban

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.data.model.KanbanColumn
import com.m57.hermescontrol.data.model.KanbanProfile
import com.m57.hermescontrol.data.model.KanbanTask
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.kanban.components.KanbanCreateTaskDialog
import com.m57.hermescontrol.ui.kanban.components.KanbanFilterSheet
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class KanbanScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testShowArchivedToggleInvokesCallback() {
        var toggled = false
        composeTestRule.setContent {
            HermesControlTheme {
                KanbanFilterSheet(
                    assignees = emptyList(),
                    tenants = emptyList(),
                    selectedAssignee = null,
                    selectedTenant = null,
                    includeArchived = false,
                    groupRunning = false,
                    onSelectAssignee = {},
                    onSelectTenant = {},
                    onToggleIncludeArchived = { toggled = it },
                    onToggleGroupRunning = {},
                    onClearFilters = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Show archived").performClick()
        assertTrue(toggled)
    }

    @Test
    fun testWorkspacePathVisibilityInCreateTaskDialog() {
        composeTestRule.setContent {
            HermesControlTheme {
                KanbanCreateTaskDialog(
                    columns = listOf(KanbanColumn(name = "todo")),
                    defaultColumn = "todo",
                    profiles = listOf(KanbanProfile(name = "researcher")),
                    existingTasks = emptyList(),
                    isCreating = false,
                    onDismiss = {},
                    onConfirm = { _, _ -> },
                )
            }
        }

        // Expand advanced options
        composeTestRule.onNodeWithText("Advanced Options").performClick()

        // By default scratch is selected, workspace path is not visible
        composeTestRule.onNodeWithText("Workspace Path Override").assertDoesNotExist()

        // Select Worktree
        composeTestRule.onNodeWithText("Worktree").performClick()
        composeTestRule.onNodeWithText("Workspace Path Override").assertIsDisplayed()

        // Select Scratch again
        composeTestRule.onNodeWithText("Scratch (Default)").performClick()
        composeTestRule.onNodeWithText("Workspace Path Override").assertDoesNotExist()
    }

    @Test
    fun testDefaultAssigneeOptionPresent() {
        composeTestRule.setContent {
            HermesControlTheme {
                KanbanCreateTaskDialog(
                    columns = listOf(KanbanColumn(name = "todo")),
                    defaultColumn = "todo",
                    profiles = listOf(KanbanProfile(name = "researcher")),
                    existingTasks = emptyList(),
                    isCreating = false,
                    defaultAssignee = "researcher",
                    onDismiss = {},
                    onConfirm = { _, _ -> },
                )
            }
        }

        composeTestRule.onNodeWithText("researcher (Default)").assertIsDisplayed()
    }
}
