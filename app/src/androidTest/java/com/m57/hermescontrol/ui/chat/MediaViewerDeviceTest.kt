package com.m57.hermescontrol.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.chat.components.MediaViewerDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MediaViewerDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private fun open(
        asset: String,
        mime: String,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, asset)
        instrumentation.context.assets
            .open(asset)
            .use { input -> file.outputStream().use(input::copyTo) }
        compose.setContent {
            var visible by remember { mutableStateOf(true) }
            HermesControlTheme {
                if (visible) {
                    MediaViewerDialog(file.toURI().toString(), { visible = false }, asset, mime)
                }
            }
        }
    }

    private fun awaitDescription(resource: Int) {
        val text = InstrumentationRegistry.getInstrumentation().targetContext.getString(resource)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun checkPlayback(
        asset: String,
        mime: String,
    ) {
        open(asset, mime)
        awaitDescription(R.string.media_player_pause)
        awaitTag("media_play_pause_button")
        compose.onNodeWithTag("media_play_pause_button").performClick()
        awaitDescription(R.string.media_player_play)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(instrumentation.targetContext.cacheDir, "$asset.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        compose.onNodeWithTag("media_seek_slider").performTouchInput {
            swipe(start = center.copy(x = width * 0.2f), end = center.copy(x = width * 0.9f))
        }
        compose.waitUntil(5_000) {
            val progress =
                compose
                    .onNodeWithTag("media_seek_slider")
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.ProgressBarRangeInfo]
            progress.current > progress.range.endInclusive * 0.75f
        }
        awaitTag("media_play_pause_button")
        compose.onNodeWithTag("media_play_pause_button").performClick()
        awaitDescription(R.string.media_player_replay)
        awaitTag("media_play_pause_button")
        compose.onNodeWithTag("media_play_pause_button").performClick()
        awaitDescription(R.string.media_player_pause)
        awaitTag("media_close_button")
        compose.onNodeWithTag("media_close_button").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("media_viewer_dialog").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun audioPlaysPausesSeeksReplaysAndCloses() = checkPlayback("player-tone.wav", "audio/wav")

    @Test
    fun videoPlaysPausesSeeksReplaysAndCloses() = checkPlayback("player-video.mp4", "video/mp4")

    @Test
    fun saveButtonStreamsToDownloadsWithoutCrash() {
        open("player-tone.wav", "audio/wav")
        awaitTag("media_save_button")
        compose.onNodeWithTag("media_save_button").assertIsDisplayed().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("media_save_button").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("media_close_button").performClick()
    }

    @Test
    fun invalidSourceShowsRetryAndCanClose() {
        compose.setContent {
            HermesControlTheme {
                MediaViewerDialog("file:///missing-media-test.mp3", {}, mimeType = "audio/mpeg")
            }
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("media_retry_button").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("media_retry_button").assertIsDisplayed().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("media_retry_button").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("media_close_button").assertIsDisplayed()
    }
}
