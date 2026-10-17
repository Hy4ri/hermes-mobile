package com.m57.hermescontrol.ui.chat

import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatKeepScreenAwakeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun menuTogglesOnceAndReflectsExternalChanges() {
        var enabled by mutableStateOf(false)
        var calls = 0
        compose.setContent {
            ChatKeepScreenAwakeMenuItem(enabled) {
                calls++
                enabled = it
            }
        }
        compose.onNodeWithTag("chat_menu_keep_awake").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat_menu_keep_awake").assertIsOn()
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onNodeWithTag("chat_menu_keep_awake").performClick()
        compose.onNodeWithTag("chat_menu_keep_awake").assertIsOff()
        compose.runOnIdle {
            assertEquals(2, calls)
            enabled = true
        }
        compose.onNodeWithTag("chat_menu_keep_awake").assertIsOn()
    }

    @Test
    fun releasesOnPauseDisableAndLeavingChat() {
        var enabled by mutableStateOf(false)
        var visible by mutableStateOf(true)
        lateinit var view: View
        lateinit var registry: LifecycleRegistry
        val owner =
            object : LifecycleOwner {
                override val lifecycle: Lifecycle get() = registry
            }
        compose.runOnIdle {
            registry = LifecycleRegistry(owner)
            registry.currentState = Lifecycle.State.RESUMED
        }
        compose.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (visible) ChatKeepScreenAwakeEffect(enabled)
            }
        }
        compose.runOnIdle {
            assertFalse(view.keepScreenOn)
            enabled = true
        }
        compose.runOnIdle {
            assertTrue(view.keepScreenOn)
            registry.currentState = Lifecycle.State.STARTED
            assertFalse(view.keepScreenOn)
            registry.currentState = Lifecycle.State.RESUMED
            assertTrue(view.keepScreenOn)
            enabled = false
        }
        compose.runOnIdle {
            assertFalse(view.keepScreenOn)
            enabled = true
        }
        compose.runOnIdle {
            assertTrue(view.keepScreenOn)
            visible = false
        }
        compose.runOnIdle { assertFalse(view.keepScreenOn) }
    }

    @Test
    fun preservesExistingKeepScreenOnOwner() {
        var enabled by mutableStateOf(true)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            view.keepScreenOn = true
            ChatKeepScreenAwakeEffect(enabled)
        }
        compose.runOnIdle { enabled = false }
        compose.runOnIdle {
            assertTrue(view.keepScreenOn)
            view.keepScreenOn = false
        }
    }
}
