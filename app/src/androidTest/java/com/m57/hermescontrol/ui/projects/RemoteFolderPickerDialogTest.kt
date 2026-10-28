package com.m57.hermescontrol.ui.projects

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.ManagedFileEntry
import com.m57.hermescontrol.data.model.ManagedFilesListResponse
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.theme.HermesControlTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class RemoteFolderPickerDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun loading_disablesConfirmAction() {
        val state =
            RemoteFolderPickerState(
                currentPath = "/opt/data",
                root = "/opt/data",
                isLoading = true,
                isConfirmed = false,
            )

        composeTestRule.setContent {
            HermesControlTheme {
                RemoteFolderPickerDialogView(
                    state = state,
                    canConfirm = state.canSelectCurrent,
                    onNavigateUp = {},
                    onSelectEntry = {},
                    onRetry = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule
            .onNodeWithTag("remote_folder_picker_confirm")
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun dialogActions_folderClick_up_cancel_invokeCallbacks() {
        var navigatedUp = false
        var selectedEntry: ManagedFileEntry? = null
        var dismissed = false

        val testFolder =
            ManagedFileEntry(
                name = "my workspace",
                path = "/opt/data/my workspace",
                isDirectory = true,
            )
        val state =
            RemoteFolderPickerState(
                currentPath = "/opt/data",
                parentPath = "/opt",
                root = "/opt",
                directories = listOf(testFolder),
                isLoading = false,
                errorMessage = null,
                isConfirmed = true,
            )

        composeTestRule.setContent {
            HermesControlTheme {
                RemoteFolderPickerDialogView(
                    state = state,
                    canConfirm = state.canSelectCurrent,
                    onNavigateUp = { navigatedUp = true },
                    onSelectEntry = { selectedEntry = it },
                    onRetry = {},
                    onConfirm = {},
                    onDismiss = { dismissed = true },
                )
            }
        }

        // Folder click invokes onSelectEntry
        composeTestRule
            .onNodeWithText("my workspace")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        assertEquals(testFolder, selectedEntry)

        // Up navigation invokes onNavigateUp
        composeTestRule
            .onNodeWithTag("remote_folder_picker_up")
            .assertIsDisplayed()
            .performClick()
        assertTrue(navigatedUp)

        // Cancel invokes onDismiss
        composeTestRule
            .onNodeWithTag("remote_folder_picker_cancel")
            .assertIsDisplayed()
            .performClick()
        assertTrue(dismissed)
    }

    @Test
    fun failedChildErrorAndRetry_visibleWithOldDirsDisabled() {
        var retried = false
        var clickedStaleFolder = false

        val staleFolder =
            ManagedFileEntry(
                name = "src",
                path = "/opt/data/src",
                isDirectory = true,
            )
        val state =
            RemoteFolderPickerState(
                currentPath = "/opt/data/src/sub",
                parentPath = "/opt/data/src",
                root = "/opt/data",
                directories = listOf(staleFolder),
                isLoading = false,
                errorMessage = "Failed to load directory",
                isConfirmed = false,
            )

        composeTestRule.setContent {
            HermesControlTheme {
                RemoteFolderPickerDialogView(
                    state = state,
                    canConfirm = state.canSelectCurrent,
                    onNavigateUp = {},
                    onSelectEntry = { clickedStaleFolder = true },
                    onRetry = { retried = true },
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        // Error message must be visible
        composeTestRule.onNodeWithText("Failed to load directory").assertIsDisplayed()

        // Retry icon is displayed and invokes onRetry using localized string description
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.action_retry))
            .assertIsDisplayed()
            .performClick()
        assertTrue(retried)

        // Stale folder is visible but disabled for clicks
        composeTestRule.onNodeWithText("src").assertIsDisplayed().performClick()
        assertFalse(clickedStaleFolder)
    }

    @Test
    fun integration_navigationAndSelection_selectsExactAbsoluteHostPath() {
        var selectedFolderResult: String? = null
        var dismissed = false

        val scope =
            DataScope(
                connectionProfileId = "conn_1",
                baseUrl = "https://gw.test",
                activeProfileId = "p1",
            )
        val scopesFlow = MutableStateFlow<DataScope?>(scope)

        val childFolder =
            ManagedFileEntry(
                name = "project folder with space",
                path = "/opt/data/project folder with space",
                isDirectory = true,
            )
        val response =
            ManagedFilesListResponse(
                path = "/opt/data/project folder with space",
                parent = "/opt/data",
                root = "/opt/data",
                entries = emptyList(),
            )

        composeTestRule.setContent {
            HermesControlTheme {
                RemoteFolderPickerDialog(
                    onSelectFolder = { selectedFolderResult = it },
                    onDismiss = { dismissed = true },
                    scopes = scopesFlow,
                    loader = { path ->
                        if (path == null) {
                            NetworkResult.Success(
                                ManagedFilesListResponse(
                                    path = "/opt/data",
                                    root = "/opt/data",
                                    entries = listOf(childFolder),
                                ),
                            )
                        } else {
                            NetworkResult.Success(response)
                        }
                    },
                )
            }
        }

        // Wait until initial root folder is rendered on screen (Dispatchers.IO load completes)
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasText("project folder with space"),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Click child folder
        composeTestRule.onNodeWithText("project folder with space").assertIsDisplayed().performClick()

        // Wait until child response loads and confirm button is enabled
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("remote_folder_picker_confirm") and
                        androidx.compose.ui.test
                            .isEnabled(),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeTestRule.onNodeWithTag("remote_folder_picker_confirm").assertIsDisplayed().performClick()
        assertEquals("/opt/data/project folder with space", selectedFolderResult)
        assertTrue(dismissed)
    }

    @Test
    fun integration_cancel_emitsNoSelection() {
        var selectedFolderResult: String? = null
        var dismissed = false

        val scope =
            DataScope(
                connectionProfileId = "conn_1",
                baseUrl = "https://gw.test",
                activeProfileId = "p1",
            )
        val scopesFlow = MutableStateFlow<DataScope?>(scope)

        composeTestRule.setContent {
            HermesControlTheme {
                RemoteFolderPickerDialog(
                    onSelectFolder = { selectedFolderResult = it },
                    onDismiss = { dismissed = true },
                    scopes = scopesFlow,
                    loader = {
                        NetworkResult.Success(
                            ManagedFilesListResponse(
                                path = "/opt/data",
                                root = "/opt/data",
                                entries = emptyList(),
                            ),
                        )
                    },
                )
            }
        }

        composeTestRule.onNodeWithTag("remote_folder_picker_cancel").assertIsDisplayed().performClick()
        assertTrue(dismissed)
        assertEquals(null, selectedFolderResult)
    }
}
