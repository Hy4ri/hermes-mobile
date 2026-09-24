package com.m57.hermescontrol.ui.chat.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.chat.ReplyFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReplyErrorCardTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun detailsStartCollapsedAndCopyIsScrubbedWithoutAutomaticSharing() {
        var copied = ""
        var shared = false
        compose.setContent {
            HermesControlTheme {
                Box(Modifier.width(320.dp)) {
                    ReplyErrorCard(
                        failure = ReplyFailure("HTTP 429\nAuthorization: Bearer private-key"),
                        onDismiss = {},
                        onOpenLogs = {},
                        onCopy = { copied = it },
                        onShare = { shared = true },
                    )
                }
            }
        }
        compose.onNodeWithText("HTTP 429", substring = true).assertDoesNotExist()

        compose.onNodeWithText(compose.activity.getString(R.string.chat_reply_failed_details)).performClick()
        compose.onNodeWithText("HTTP 429", substring = true).assertIsDisplayed()
        compose.onNodeWithText("private-key", substring = true).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.chat_reply_failed_copy)).performClick()
        compose.runOnIdle {
            assertEquals("HTTP 429\nAuthorization: [REDACTED]", copied)
            assertFalse(shared)
        }
    }

    @Test
    fun actionsRequireExplicitClicks() {
        var shared = ""
        var logsOpened = 0
        var dismissed = 0
        compose.setContent {
            HermesControlTheme {
                ReplyErrorCard(
                    failure = ReplyFailure("Provider failed"),
                    onDismiss = { dismissed++ },
                    onOpenLogs = { logsOpened++ },
                    onCopy = {},
                    onShare = { shared = it },
                )
            }
        }
        compose.runOnIdle {
            assertEquals("", shared)
            assertEquals(0, logsOpened)
            assertEquals(0, dismissed)
        }
        compose.onNodeWithText(compose.activity.getString(R.string.chat_reply_failed_share)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.chat_reply_failed_logs)).performClick()
        compose
            .onNodeWithContentDescription(
                compose.activity.getString(R.string.chat_reply_failed_dismiss),
            ).performClick()
        compose.runOnIdle {
            assertEquals("Provider failed", shared)
            assertEquals(1, logsOpened)
            assertEquals(1, dismissed)
        }
    }
}
