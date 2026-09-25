package com.m57.hermescontrol.ui.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.BusySendMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class ChatSectionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun busySendDefaultDropdownRoutesSelection() {
        var chosen: BusySendMode? = null
        composeTestRule.setContent {
            ChatSection(
                typingEffectEnabled = false,
                onTypingEffectEnabledChange = {},
                typingEffectDelayMs = 30,
                onTypingEffectDelayMsChange = {},
                busySendMode = BusySendMode.CORRECT,
                onBusySendModeChange = { chosen = it },
            )
        }
        composeTestRule.onNodeWithTag("busy_send_default").performClick()
        val queueLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_busy_queue)
        composeTestRule.onNodeWithText(queueLabel).performClick()
        assertEquals(BusySendMode.QUEUE, chosen)
    }

    @Test
    fun messageStatsMasterOff_keepsChildrenVisibleCheckedAndDisabled() {
        composeTestRule.setContent {
            ChatSection(
                typingEffectEnabled = false,
                onTypingEffectEnabledChange = {},
                typingEffectDelayMs = 30,
                onTypingEffectDelayMsChange = {},
                messageStatsEnabled = false,
                showUserMessageTokens = true,
                showAssistantMessageTokens = true,
                showTokensPerSecond = true,
            )
        }

        composeTestRule.onNodeWithTag("settings_message_stats").assertIsOff().assertIsEnabled()
        composeTestRule.onNodeWithTag("settings_user_message_tokens").assertIsOn().assertIsNotEnabled()
        composeTestRule.onNodeWithTag("settings_assistant_message_tokens").assertIsOn().assertIsNotEnabled()
        composeTestRule.onNodeWithTag("settings_tps").assertIsOn().assertIsNotEnabled()
    }

    @Test
    fun messageStatsMasterOn_enablesChildrenAndRoutesCallbacks() {
        var masterChanges = 0
        var userChanges = 0
        var assistantChanges = 0
        var tpsChanges = 0
        composeTestRule.setContent {
            // PR #1254: match the real settings page's scrollable viewport.
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ChatSection(
                    typingEffectEnabled = false,
                    onTypingEffectEnabledChange = {},
                    typingEffectDelayMs = 30,
                    onTypingEffectDelayMsChange = {},
                    messageStatsEnabled = true,
                    onMessageStatsEnabledChange = { masterChanges++ },
                    showUserMessageTokens = true,
                    onUserMessageTokensChange = { userChanges++ },
                    showAssistantMessageTokens = true,
                    onAssistantMessageTokensChange = { assistantChanges++ },
                    showTokensPerSecond = true,
                    onTokensPerSecondChange = { tpsChanges++ },
                )
            }
        }

        composeTestRule
            .onNodeWithTag("settings_user_message_tokens")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeTestRule
            .onNodeWithTag(
                "settings_assistant_message_tokens",
            ).performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeTestRule
            .onNodeWithTag("settings_tps")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(0, masterChanges)
        assertEquals(1, userChanges)
        assertEquals(1, assistantChanges)
        assertEquals(1, tpsChanges)

        composeTestRule.onNodeWithTag("settings_message_stats").performScrollTo().performClick()
        assertEquals(1, masterChanges)
        assertEquals(1, userChanges)
        assertEquals(1, assistantChanges)
        assertEquals(1, tpsChanges)
    }
}
