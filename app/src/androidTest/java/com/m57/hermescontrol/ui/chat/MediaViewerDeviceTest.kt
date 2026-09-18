package com.m57.hermescontrol.ui.chat

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    fun saveButtonStreamsToDownloadsAndVerifiesMediaStoreItem() {
        open("player-tone.wav", "audio/wav")
        awaitTag("media_save_button")
        compose.onNodeWithTag("media_save_button").assertIsDisplayed().performClick()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver

        var savedUri: Uri? = null
        compose.waitUntil(15_000) {
            val cursor =
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.MIME_TYPE,
                    ),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf("player-tone.wav"),
                    "${MediaStore.MediaColumns._ID} DESC",
                )
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    savedUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                    val name = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                    val mime = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE))
                    assertEquals("player-tone.wav", name)
                    assertTrue(mime == "audio/wav" || mime == "audio/x-wav")
                    true
                } else {
                    false
                }
            } ?: false
        }

        try {
            assertNotNull(savedUri)
            val expectedBytes =
                instrumentation.context.assets
                    .open("player-tone.wav")
                    .use { it.readBytes() }
            val actualBytes = resolver.openInputStream(savedUri!!)?.use { it.readBytes() }
            assertArrayEquals(expectedBytes, actualBytes)
        } finally {
            savedUri?.let { resolver.delete(it, null, null) }
        }

        compose.onNodeWithTag("media_close_button").performClick()
    }

    @Test
    fun shareMediaBuildsValidIntentAndReadableStream() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val file = File(context.cacheDir, "player-tone.wav")
            instrumentation.context.assets.open("player-tone.wav").use { input ->
                file.outputStream().use(input::copyTo)
            }

            val intent =
                MediaExportHelper.shareMedia(
                    context = context,
                    uri = file.toURI().toString(),
                    fallbackMime = "audio/wav",
                    displayName = "player-tone.wav",
                )

            assertNotNull(intent)
            assertEquals(Intent.ACTION_SEND, intent!!.action)
            assertEquals("audio/wav", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

            @Suppress("DEPRECATION")
            val streamUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            assertNotNull(streamUri)

            val expectedSize = file.length()
            val actualSize =
                context.contentResolver.openInputStream(streamUri!!)?.use { stream ->
                    var count = 0L
                    val buf = ByteArray(8192)
                    while (true) {
                        val r = stream.read(buf)
                        if (r < 0) break
                        count += r
                    }
                    count
                }
            assertEquals(expectedSize, actualSize)
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
