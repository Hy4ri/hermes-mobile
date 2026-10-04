package com.m57.hermescontrol.ui.chat.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.theme.HermesControlTheme
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class GifImageThumbnailTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val files = TemporaryFolder(ApplicationProvider.getApplicationContext<Context>().cacheDir)

    @get:Rule
    val server = MockWebServer()

    @Before
    fun resetFrames() = ImageFrameStore.clearForTest()

    @Test
    fun frameBoundsStayIdenticalAcrossErrorRetrySuccessAndDisposal() {
        server.enqueue(MockResponse().setResponseCode(403))
        val bytes = ByteArrayOutputStream()
        // Portrait image so a natural-size swap would visibly change the frame.
        val bitmap = Bitmap.createBitmap(30, 90, Bitmap.Config.ARGB_8888)
        try {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        } finally {
            bitmap.recycle()
        }
        server.enqueue(
            MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes.toByteArray())),
        )
        val url = server.url("/api/files/download?path=/private/frame.png").toString()
        compose.setContent {
            HermesControlTheme {
                GifImageThumbnail(
                    model = url,
                    contentDescription = "Frame attachment",
                    isGif = false,
                    frameKey = "msg-1:0",
                    onClick = {},
                )
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Could not load image").fetchSemanticsNodes().isNotEmpty()
        }
        val errored = compose.onNodeWithTag("chat_image_frame").getUnclippedBoundsInRoot()
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Frame attachment").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        val loaded = compose.onNodeWithTag("chat_image_frame").getUnclippedBoundsInRoot()
        assertEquals("frame must not change when the image loads", errored, loaded)
        assertEquals(ImageFrameStore.FALLBACK_RATIO, ImageFrameStore.get("msg-1:0")!!, 0.001f)
    }

    @Test
    fun forbiddenImageReportsStatusAndRetryRecoversWithoutOpeningViewer() {
        server.enqueue(MockResponse().setResponseCode(403))
        val bytes = ByteArrayOutputStream()
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        try {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        } finally {
            bitmap.recycle()
        }
        server.enqueue(
            MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes.toByteArray())),
        )
        val url = server.url("/api/files/download?path=/private/image.png&token=DO_NOT_LOG").toString()
        var viewerOpens = 0
        compose.setContent {
            HermesControlTheme {
                GifImageThumbnail(
                    model = url,
                    contentDescription = "Remote attachment",
                    isGif = false,
                    diagnosticId = "http-retry",
                    onClick = { viewerOpens++ },
                )
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Could not load image").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Could not load image").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Remote attachment").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Remote attachment").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, viewerOpens) }
        assertEquals(2, server.requestCount)
        val logs = diagnosticLogs()
        assertTrue(logs, logs.contains("phase=error source=http row=http-retry error=HttpException http=403"))
        assertTrue(logs, logs.contains("phase=success source=http row=http-retry"))
        assertFalse(logs.contains("DO_NOT_LOG"))
        assertFalse(logs.contains("/private/image.png"))
    }

    @Test
    fun failedImageStaysVisibleAndRetryLoadsRecoveredFile() {
        val image = File(files.root, "recovered.png")
        var viewerOpens = 0
        compose.setContent {
            HermesControlTheme {
                GifImageThumbnail(
                    model = image.toURI().toString(),
                    contentDescription = "Attached test image",
                    isGif = false,
                    onClick = { viewerOpens++ },
                )
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Could not load image").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Could not load image").assertIsDisplayed()
        compose.onNodeWithText("Attached test image").assertIsDisplayed()
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        try {
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Attached test image").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Attached test image").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, viewerOpens) }
        val logs = diagnosticLogs()
        assertTrue(logs, logs.contains("phase=error source=file"))
        assertTrue(logs, logs.contains("FileNotFoundException"))
        assertTrue(logs, logs.contains("phase=success source=file"))
        assertFalse(logs.contains(image.absolutePath))
    }

    private fun diagnosticLogs(): String {
        val descriptor =
            InstrumentationRegistry
                .getInstrumentation()
                .uiAutomation
                .executeShellCommand("logcat -d -s ChatImageDiag:D '*:S'")
        return android.os.ParcelFileDescriptor
            .AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { it.readText() }
    }
}
