package com.m57.hermescontrol.ui.chat

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.AttachmentSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentOpenIntentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule
    val files = TemporaryFolder(context.cacheDir)

    @Test
    fun htmlAttachmentRetainsReadableContentUriAndMatchesHtmlViewer() =
        runBlocking {
            val content = "<!doctype html><title>Attachment test</title>"
            val file = files.newFile("report.html").apply { writeText(content) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            var launched: Intent? = null
            val capturingContext =
                object : ContextWrapper(context) {
                    override fun startActivity(intent: Intent) {
                        launched = intent
                    }
                }
            val app =
                object : Application() {
                    override fun getApplicationContext(): Context = capturingContext
                }
            val state = MutableStateFlow(ChatUiState())
            val delegate = ChatMediaDelegate(state, { app }, this, Dispatchers.Unconfined)

            delegate.openAttachment(
                Attachment(
                    uri = uri.toString(),
                    name = "report.html",
                    mimeType = "text/html; charset=utf-8",
                    source = AttachmentSource.LOCAL,
                ),
            )

            assertNotNull(launched)
            val intent = launched!!
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(uri, intent.data)
            assertEquals("content", intent.data?.scheme)
            assertEquals("text/html", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertEquals(
                content,
                context.contentResolver
                    .openInputStream(intent.data!!)!!
                    .bufferedReader()
                    .use { it.readText() },
            )
            val htmlViewer =
                IntentFilter(Intent.ACTION_VIEW).apply {
                    addDataScheme("content")
                    addDataType("text/html")
                }
            assertTrue(htmlViewer.match(context.contentResolver, intent, false, "AttachmentOpenIntentTest") >= 0)
            assertNull(state.value.openError)
        }
}
