package com.m57.hermescontrol.ui.chat

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.AttachmentSource
import com.m57.hermescontrol.data.remote.GatewayFile
import com.m57.hermescontrol.data.remote.GatewayFileClient
import com.m57.hermescontrol.data.remote.GatewayFileResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatMediaDelegateTest {
    @get:Rule
    val files = TemporaryFolder()

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `gateway HTML opens with content URI and MIME without HTTP parameters`() =
        runTest {
            val app = mockk<Application>()
            val uri = mockk<Uri>()
            val cacheDir = files.newFolder()
            val file = files.newFile("report.html").apply { writeText("<html>report</html>") }
            every { app.applicationContext } returns app
            every { app.cacheDir } returns cacheDir
            every { app.packageName } returns "com.m57.hermescontrol"
            every { app.startActivity(any()) } returns Unit
            mockkObject(GatewayFileClient)
            coEvery { GatewayFileClient.fetch("/reports/report.html", any()) } returns
                GatewayFileResult.Success(GatewayFile("report.html", "text/html; charset=utf-8", file))
            mockkStatic(FileProvider::class)
            every { FileProvider.getUriForFile(app, "com.m57.hermescontrol.fileprovider", file) } returns uri
            mockkConstructor(Intent::class)
            every { anyConstructed<Intent>().setDataAndType(any(), any()) } answers { self as Intent }
            every { anyConstructed<Intent>().addFlags(any()) } answers { self as Intent }
            val state = MutableStateFlow(ChatUiState())
            val delegate = ChatMediaDelegate(state, { app }, this, coroutineContext)

            delegate.openAttachment(
                Attachment(
                    uri = "gateway:/reports/report.html",
                    name = "report.html",
                    mimeType = "application/octet-stream",
                    source = AttachmentSource.GATEWAY,
                ),
            )
            testScheduler.advanceUntilIdle()

            verify(exactly = 1) { anyConstructed<Intent>().setDataAndType(uri, "text/html") }
            verify { anyConstructed<Intent>().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            verify(exactly = 1) { app.startActivity(any()) }
            assertNull(state.value.openError)
            assertNull(state.value.openingAttachmentPath)
        }
}
