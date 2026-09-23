package com.m57.hermescontrol.ui.kanban

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
                    workflowTemplateIds = emptyList(),
                    currentStepKeys = emptyList(),
                    selectedAssignee = null,
                    selectedTenant = null,
                    selectedWorkflowTemplateId = null,
                    selectedCurrentStepKey = null,
                    includeArchived = false,
                    groupRunning = false,
                    onSelectAssignee = {},
                    onSelectTenant = {},
                    onSelectWorkflowFilters = { _, _ -> },
                    onToggleIncludeArchived = { toggled = it },
                    onToggleGroupRunning = {},
                    onClearFilters = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("kanban_show_archived").performClick()
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
        composeTestRule.onNodeWithTag("kanban_advanced_options").performClick()

        // By default scratch is selected, workspace path is not visible
        composeTestRule.onNodeWithTag("kanban_workspace_path").assertDoesNotExist()

        // Select Worktree
        composeTestRule.onNodeWithTag("kanban_workspace_kinds").performScrollTo()
        composeTestRule.onNodeWithTag("kanban_workspace_kind_worktree").performClick()
        composeTestRule.onNodeWithTag("kanban_workspace_kind_worktree").assertIsSelected()
        composeTestRule.onNodeWithTag("kanban_workspace_path").performScrollTo().assertIsDisplayed()

        // Select Scratch again
        composeTestRule.onNodeWithTag("kanban_workspace_kinds").performScrollTo()
        composeTestRule.onNodeWithTag("kanban_workspace_kind_scratch").performClick()
        composeTestRule.onNodeWithTag("kanban_workspace_path").assertDoesNotExist()
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
