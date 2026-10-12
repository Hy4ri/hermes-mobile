package com.m57.hermescontrol.ui.chat.components

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.BusySendMode
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.PendingSend
import com.m57.hermescontrol.ui.chat.PendingSendState
import com.m57.hermescontrol.ui.chat.UserBubble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class PendingSendRecoveryTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val uncertain =
        PendingSend(
            id = "uncertain",
            scope = "test",
            sessionId = "session",
            text = "Possibly delivered",
            mode = BusySendMode.QUEUE,
            state = PendingSendState.UNKNOWN,
        )

    private fun imageAttachment(): Attachment {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "photo.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(20, 100, 160))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Attachment(file.toURI().toString(), file.name, "image/png", file.length())
    }

    @Test
    fun uncertainRetryRequiresExplicitConfirmation() {
        var retried: String? = null
        compose.setContent {
            MaterialTheme {
                PendingSendRecovery(listOf(uncertain), true, false, { retried = it }, {}, {}, {})
            }
        }
        compose.onNodeWithTag("pending_send_recovery").assertIsDisplayed().performClick()
        compose.onNodeWithText("Possibly delivered").assertIsDisplayed()
        compose.onNodeWithTag("pending_retry_uncertain").performClick()
        compose.runOnIdle { assertNull(retried) }
        compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        compose.runOnIdle { assertNull(retried) }
        compose.onNodeWithTag("pending_retry_uncertain").performClick()
        compose.onNodeWithTag("pending_retry_confirm").performClick()
        compose.runOnIdle { assertEquals("uncertain", retried) }
    }

    @Test
    fun disconnectedSessionCanInspectAndDiscardQueuedButCannotRetry() {
        var dismissed: String? = null
        compose.setContent {
            MaterialTheme {
                PendingSendRecovery(
                    listOf(uncertain.copy(state = PendingSendState.QUEUED)),
                    false,
                    false,
                    {},
                    { dismissed = it },
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithTag("pending_send_recovery").performClick()
        compose.onNodeWithTag("pending_retry_uncertain").assertIsNotEnabled()
        compose.onNodeWithTag("pending_discard_uncertain").performClick()
        compose.runOnIdle { assertNull(dismissed) }
        compose
            .onNodeWithText(
                "Remove this message from the device queue? This does not cancel work or delete messages already received by the server.",
            ).assertIsDisplayed()
        compose.onNodeWithTag("pending_discard_confirm").performClick()
        compose.runOnIdle { assertEquals("uncertain", dismissed) }
    }

    @Test
    fun unknownIncludingAcknowledgedHasNoDestructiveDiscard() {
        var discarded: String? = null
        compose.setContent {
            MaterialTheme {
                PendingSendRecovery(
                    listOf(uncertain, uncertain.copy(id = "acknowledged", userOrderingReleased = true)),
                    false,
                    false,
                    {},
                    { discarded = it },
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithTag("pending_send_recovery").performClick()
        compose.onNodeWithTag("pending_discard_uncertain").assertDoesNotExist()
        compose.onNodeWithTag("pending_discard_acknowledged").assertDoesNotExist()
        compose.runOnIdle { assertNull(discarded) }
    }

    @Test
    fun emptyOutboxDoesNotAddAComposerControl() {
        compose.setContent {
            MaterialTheme {
                PendingSendRecovery(emptyList(), true, false, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("pending_send_recovery").assertDoesNotExist()
    }

    @Test
    fun acceptedPromptShowsTextImageAndStatusWithoutRecoveryControl() {
        val image = imageAttachment()
        val accepted = uncertain.copy(state = PendingSendState.ACCEPTED, attachments = listOf(image))
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    UserBubble(
                        ChatMessage(accepted.id, MessageRole.USER, accepted.text, attachments = accepted.attachments),
                        pendingSendState = accepted.state,
                    )
                    PendingSendRecovery(listOf(accepted), true, true, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText(accepted.text).assertIsDisplayed()
        compose.waitUntil(5_000) {
            compose
                .onNodeWithContentDescription("photo.png")
                .fetchSemanticsNode()
                .boundsInRoot.height > 0
        }
        compose.onNodeWithContentDescription("photo.png").assertIsDisplayed()
        compose.onNodeWithContentDescription("Accepted by server").assertIsDisplayed()
        compose.onNodeWithTag("pending_send_recovery").assertDoesNotExist()
    }

    @Test
    fun recoveryIdentifiesImagesAndFilesAndCanOpenAnImage() {
        val image = imageAttachment()
        val file = Attachment("file:///private/note", "note.txt", "text/plain", 4)
        var openedImage: String? = null
        var openedFile: String? = null
        compose.setContent {
            MaterialTheme {
                PendingSendRecovery(
                    listOf(uncertain.copy(attachments = listOf(image, file))),
                    false,
                    false,
                    {},
                    {},
                    onAcknowledge = {},
                    onRemoveAcknowledged = {},
                    onOpenAttachment = { openedFile = it.name },
                    onImageClick = { openedImage = it.name },
                )
            }
        }
        compose.onNodeWithTag("pending_send_recovery").performClick()
        compose.onNodeWithText("photo.png").assertIsDisplayed()
        compose.waitUntil(5_000) {
            compose
                .onNodeWithContentDescription("photo.png")
                .fetchSemanticsNode()
                .boundsInRoot.height > 0
        }
        compose.onNodeWithContentDescription("photo.png").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("photo.png", openedImage) }
        // The filename shares a lazy item with the image; scrollToIndex cannot reveal its lower children.
        if (!compose.onNodeWithText("note.txt").isDisplayed()) {
            compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        }
        compose.onNodeWithText("note.txt").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("note.txt", openedFile) }
    }

    @Test
    @Suppress("DEPRECATION")
    fun chineseRecoveryCountAndDismissAreLocalized() {
        // Native dialog windows resolve the Activity locale, not a synthetic LocalContext.
        val resources = compose.activity.resources
        val original = Configuration(resources.configuration)
        val chinese = Configuration(original).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        try {
            compose.runOnUiThread { resources.updateConfiguration(chinese, resources.displayMetrics) }
            compose.setContent {
                MaterialTheme {
                    PendingSendRecovery(
                        listOf(uncertain.copy(state = PendingSendState.QUEUED)),
                        false,
                        false,
                        {},
                        {},
                        {},
                        {},
                    )
                }
            }
            compose.onNodeWithText("1 条待发送消息").assertIsDisplayed().performClick()
            compose
                .onNodeWithText("移除")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            compose
                .onNodeWithText(
                    "要从本机队列中移除这条消息吗？这不会取消任务，也不会删除服务器已接收的消息。",
                ).assertIsDisplayed()
        } finally {
            compose.runOnUiThread { resources.updateConfiguration(original, resources.displayMetrics) }
        }
    }

    @Test
    fun longUncertainMessageKeepsConfirmationActionsVisible() {
        compose.setContent {
            MaterialTheme {
                PendingSendRecovery(
                    listOf(uncertain.copy(text = "Long diagnostic text ".repeat(200))),
                    true,
                    false,
                    {},
                    {},
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithTag("pending_send_recovery").performClick()
        compose.onNodeWithTag("pending_retry_uncertain").performScrollTo().performClick()
        compose.onNodeWithTag("pending_retry_confirm").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }
}
