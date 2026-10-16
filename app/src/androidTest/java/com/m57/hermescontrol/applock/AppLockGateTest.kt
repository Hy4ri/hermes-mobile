package com.m57.hermescontrol.applock

import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalMaterial3Api::class)
class AppLockGateTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun coldStartDoesNotComposePrivateContent() {
        var compositions = 0
        compose.setContent {
            MaterialTheme {
                AppLockGate(true, false, content = {
                    compositions++
                    Text("private")
                }) { Text("locked") }
            }
        }
        compose.onNodeWithText("locked").assertIsDisplayed()
        compose.onNodeWithText("private", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, compositions) }
    }

    @Test
    fun dialogIsHiddenAndNonSaveableDraftSurvives() = verifyWindow("dialog")

    @Test
    fun alertDialogIsHiddenAndNonSaveableDraftSurvives() = verifyWindow("alert")

    @Test
    fun sheetIsHiddenAndNonSaveableDraftSurvives() = verifyWindow("sheet")

    @Test
    fun dropdownIsHiddenAndNonSaveableDraftSurvives() = verifyWindow("dropdown")

    @Test
    fun popupIsHiddenAndNonSaveableDraftSurvives() = verifyWindow("popup")

    @Test
    fun exposedDropdownIsHiddenAndNonSaveableDraftSurvives() = verifyWindow("exposed")

    @Test
    fun newlyArrivingDialogCannotOpenAWindowWhileLocked() {
        val show = mutableStateOf(false)
        val blocked = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                AppLockGate(blocked.value, true, content = {
                    if (show.value) Dialog({}) { Text("private asynchronous request") }
                }) { Text("locked") }
            }
        }
        compose.runOnIdle { show.value = true }
        compose.onNodeWithText("private asynchronous request").assertDoesNotExist()
        assertNoPrivateWindows()
        compose.onNodeWithText("locked").assertIsDisplayed()
        compose.runOnIdle { blocked.value = false }
        compose.onNodeWithText("private asynchronous request").assertIsDisplayed()
    }

    private fun verifyWindow(kind: String) {
        val blocked = mutableStateOf(false)
        var sends = 0
        var privateRoot: View? = null
        compose.setContent {
            MaterialTheme {
                AppLockGate(blocked.value, true, content = {
                    val body: @Composable () -> Unit = {
                        val root = LocalView.current.rootView
                        SideEffect { privateRoot = root }
                        var draft by remember { mutableStateOf("") }
                        BasicTextField(draft, { draft = it }, Modifier.testTag("private_draft"))
                        Button(onClick = { sends++ }) { Text("private send") }
                    }
                    Box {
                        when (kind) {
                            "dialog" -> {
                                Dialog({}, content = body)
                            }

                            "alert" -> {
                                AlertDialog({}, confirmButton = {}, text = body)
                            }

                            "sheet" -> {
                                ModalBottomSheet({}) { body() }
                            }

                            "dropdown" -> {
                                DropdownMenu(true, {}) { body() }
                            }

                            "popup" -> {
                                Popup(content = body)
                            }

                            "exposed" -> {
                                ExposedDropdownMenuBox(true, {}) {
                                    OutlinedTextField(
                                        "anchor",
                                        {},
                                        modifier =
                                            Modifier.menuAnchor(
                                                ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                            ),
                                    )
                                    PrivateExposedDropdownMenu(true, {}) { body() }
                                }
                            }
                        }
                    }
                }) { Text("locked") }
            }
        }
        compose.onNodeWithTag("private_draft").performTextInput("unsent draft")
        compose.runOnIdle { blocked.value = true }
        compose.onNodeWithText("locked").assertIsDisplayed()
        // Retain the original native composition, but hide its Android decor and remove input/a11y.
        compose.onNodeWithTag("private_draft").assertIsNotDisplayed()
        compose.onNodeWithText("private send").assertIsNotDisplayed()
        compose.runOnIdle {
            val root = requireNotNull(privateRoot)
            assertEquals(View.GONE, root.visibility)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, root.importantForAccessibility)
            val flags = (root.layoutParams as WindowManager.LayoutParams).flags
            val mask = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            assertEquals(mask, flags and mask)
            assertEquals(0, sends)
            blocked.value = false
        }
        compose.onNodeWithTag("private_draft").assertTextContains("unsent draft")
        compose.onNodeWithTag("private_draft").assertIsDisplayed()
        compose.onNodeWithTag("private_draft").performTextInput(" retained")
        compose.onNodeWithTag("private_draft").assertTextContains("unsent draft retained")
        compose.runOnIdle { assertEquals(0, sends) }
    }

    private fun assertNoPrivateWindows() {
        val window =
            SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog) or
                SemanticsMatcher.keyIsDefined(SemanticsProperties.IsPopup)
        compose.onAllNodes(window, useUnmergedTree = true).assertCountEquals(0)
    }
}
