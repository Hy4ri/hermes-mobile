package com.m57.hermescontrol.ui.chat.fullbleed

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.AttachmentSource
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatSearchState
import com.m57.hermescontrol.ui.chat.ChatTimelineState
import com.m57.hermescontrol.ui.chat.ChatUiState
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.StreamingState
import com.m57.hermescontrol.ui.chat.components.ImageFrameStore
import com.m57.hermescontrol.ui.chat.components.rememberChatScrollController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * #1459: an image row must not move the reader's position while it loads, fails, retries or is disposed and
 * re-entered. The anchor is the stable lazy key plus its pixel offset, measured while no gesture is active.
 */
@RunWith(AndroidJUnit4::class)
class FullBleedImageScrollTest {
    @get:Rule
    val compose = createComposeRule()

    private val server = MockWebServer()

    @Before
    fun setUp() {
        ImageFrameStore.clearForTest()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun png(
        width: Int,
        height: Int,
    ): Buffer {
        val bytes = ByteArrayOutputStream()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        } finally {
            bitmap.recycle()
        }
        return Buffer().write(bytes.toByteArray())
    }

    @Test
    fun portraitImageLoadingLateNeverMovesTheReader() = run(width = 30, height = 120, fail = false)

    @Test
    fun landscapeImageLoadingLateNeverMovesTheReader() = run(width = 160, height = 40, fail = false)

    @Test
    fun failedImageNeverMovesTheReader() = run(width = 30, height = 120, fail = true)

    private fun run(
        width: Int,
        height: Int,
        fail: Boolean,
    ) {
        repeat(CYCLES + 2) {
            server.enqueue(
                if (fail) {
                    MockResponse().setResponseCode(403).setBodyDelay(600, TimeUnit.MILLISECONDS)
                } else {
                    MockResponse()
                        .setHeader("Content-Type", "image/png")
                        .setBody(png(width, height))
                        .setBodyDelay(600, TimeUnit.MILLISECONDS)
                },
            )
        }
        val url = server.url("/api/files/download?path=/private/photo.png").toString()
        val attachment =
            Attachment(
                uri = url,
                name = "photo.png",
                mimeType = "image/png",
                gatewayUrl = url,
                source = AttachmentSource.GATEWAY,
            )

        fun text(id: Int) = ChatMessage("row-$id", MessageRole.USER, "Message $id\nSecond line\nThird line")
        val messages =
            (0 until 20).map(::text) +
                ChatMessage("row-img", MessageRole.USER, "photo caption", attachments = listOf(attachment)) +
                (21 until 60).map(::text)
        val listState = LazyListState()
        lateinit var scope: CoroutineScope
        compose.setContent {
            scope = rememberCoroutineScope()
            val controller = rememberChatScrollController(listState, scope)
            HermesControlTheme {
                Box(Modifier.size(width = 320.dp, height = 400.dp)) {
                    FullBleedChatList(
                        transcript =
                            TranscriptUiState.resolve(
                                chat =
                                    ChatUiState(
                                        messages = messages,
                                        typingEffectEnabled = false,
                                        typingEffectDelayMs = 30,
                                        currentSessionId = "session-one",
                                    ),
                                timeline = ChatTimelineState(),
                                streaming = StreamingState(),
                                savingAttachmentPath = null,
                                speakingMessageId = null,
                            ),
                        actions = testTranscriptActions(),
                        searchState = ChatSearchState(),
                        listState = listState,
                        scrollController = controller,
                    )
                }
            }
        }

        val imageKey = "user-row-img"
        val belowKey = "user-row-21"

        fun scrollTo(index: Int) {
            compose.runOnIdle { scope.launch { listState.scrollToItem(index) } }
            compose.waitForIdle()
        }

        fun belowOffset(): Int? =
            compose.runOnIdle {
                listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key == belowKey }
                    ?.offset
            }

        fun imageIndex(): Int =
            compose.runOnIdle {
                // The row sits after 20 single-item user turns.
                20
            }

        scrollTo(imageIndex())
        val visible =
            compose.runOnIdle { listState.layoutInfo.visibleItemsInfo.any { it.key == imageKey } }
        assertTrue("image row must be on screen to start", visible)
        // Anchor: the row BELOW the image. Anything that resizes the image row shifts this row.
        val anchor = belowOffset()
        assertNotNull("anchor row below the image must be visible", anchor)

        // Let the delayed request finish (success or error) and the row recompose.
        Thread.sleep(1500)
        compose.waitForIdle()
        assertEquals("image settled (success or error) moved the reader", anchor, belowOffset())

        repeat(CYCLES) { cycle ->
            // Dispose the image row completely, then re-enter at the same position.
            scrollTo(55)
            compose.runOnIdle {
                assertTrue(
                    "cycle $cycle: image row must really leave composition",
                    listState.layoutInfo.visibleItemsInfo.none { it.key == imageKey },
                )
            }
            scrollTo(imageIndex())
            Thread.sleep(800)
            compose.waitForIdle()
            assertEquals("cycle $cycle: re-entering the image row moved the reader", anchor, belowOffset())
        }
    }

    private companion object {
        const val CYCLES = 3
    }
}
