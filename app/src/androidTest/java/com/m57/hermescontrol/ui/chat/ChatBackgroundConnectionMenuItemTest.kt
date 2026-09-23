package com.m57.hermescontrol.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatBackgroundConnectionMenuItemTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun menuReflectsStateAndTogglesInBothDirections() {
        var enabled by mutableStateOf(false)
        compose.setContent {
            ChatBackgroundConnectionMenuItem(
                checked = enabled,
                onToggle = { enabled = it },
            )
        }

        compose.onNodeWithTag("chat_menu_keep_connected").assertIsDisplayed()
        compose.onNodeWithTag("chat_menu_keep_connected_checkbox", useUnmergedTree = true).assertIsOff()
        compose.onNodeWithTag("chat_menu_keep_connected").performClick()
        compose.onNodeWithTag("chat_menu_keep_connected_checkbox", useUnmergedTree = true).assertIsOn()
        compose.runOnIdle { assertEquals(true, enabled) }

        // The next opening reads the externally changed value rather than a stale menu snapshot.
        compose.runOnIdle { enabled = false }
        compose.onNodeWithTag("chat_menu_keep_connected_checkbox", useUnmergedTree = true).assertIsOff()
        compose.runOnIdle { enabled = true }
        compose.onNodeWithTag("chat_menu_keep_connected_checkbox", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("chat_menu_keep_connected_checkbox", useUnmergedTree = true).assertIsOff()
        compose.runOnIdle { assertEquals(false, enabled) }
    }
}
