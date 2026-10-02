package com.m57.hermescontrol.ui.chat.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextRange
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.ws.CommandCatalog
import com.m57.hermescontrol.ui.chat.ChatAttachmentTarget
import com.m57.hermescontrol.ui.chat.ChatPastedImageStore
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real BasicTextField paste and InputConnection entry points; no direct listener invocation. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
class ComposerImagePasteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val attachments = mutableStateListOf<Attachment>()
    private val errors = mutableListOf<Int>()
    private val ownedFiles = mutableListOf<File>()
    private val input = TextFieldState("keep draft", TextRange(1, 3))
    private var sends = 0
    private var connection: InputConnection? = null
    private var editorInfo: EditorInfo? = null
    private lateinit var controller: ChatImagePasteController

    private fun prepareComposer(interceptIme: Boolean = false) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            controller =
                remember {
                    ChatImagePasteController(
                        scope = scope,
                        importImage = ChatPastedImageStore(context)::importImage,
                        captureTarget = { ChatAttachmentTarget("test", 1, "http://test.local", null, null) },
                        commit = { _, images ->
                            attachments.addAll(images)
                            true
                        },
                        onError = errors::add,
                        isImageUri = { uri ->
                            context.contentResolver.getType(uri)?.startsWith("image/", ignoreCase = true) == true
                        },
                    )
                }
            InterceptPlatformTextInput(interceptor = { request, next ->
                if (interceptIme) {
                    val info = EditorInfo()
                    connection = request.createInputConnection(info)
                    editorInfo = info
                    awaitCancellation()
                } else {
                    next.startInputMethod(request)
                }
            }) {
                ChatInputBar(
                    inputState = input,
                    onSend = { sends++ },
                    onMicTap = {},
                    isListening = false,
                    isAgentTyping = false,
                    isConnected = true,
                    isSessionReady = true,
                    commandCatalog = CommandCatalog(),
                    pendingAttachments = attachments,
                    receiveContentListener = controller,
                    isReceivingContent = controller.isReceiving,
                )
            }
        }
        compose.onNodeWithTag("chat_input").performClick()
        compose.runOnIdle { input.edit { selection = TextRange(1, 3) } }
    }

    private fun imageUri(): Uri {
        val file = File(context.cacheDir, "clipboard-test-${UUID.randomUUID()}.png")
        ownedFiles.add(file)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun pdfUri(): Uri {
        val file = File(context.cacheDir, "clipboard-test-${UUID.randomUUID()}.pdf")
        ownedFiles.add(file)
        file.writeText("%PDF-1.4\nfixture\n")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun paste(clip: ClipData) {
        compose.runOnIdle {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        }
        compose.onNodeWithTag("chat_input").performSemanticsAction(SemanticsActions.PasteText) { it() }
    }

    private fun assertImported(source: Uri) {
        compose.waitUntil(timeoutMillis = 10_000) { attachments.size == 1 && !controller.isReceiving }
        compose.runOnIdle {
            assertEquals("keep draft", input.text.toString())
            assertEquals(TextRange(1, 3), input.selection)
            assertEquals(0, sends)
            assertTrue(errors.isEmpty())
            assertNotEquals(source.toString(), attachments.single().uri)
            val copy = File(context.cacheDir, "pasted_images/${attachments.single().name}")
            assertTrue(copy.isFile)
            assertEquals(copy.length(), attachments.single().size)
        }
    }

    @Test
    fun clipboardImage_becomesPrivateAttachmentWithoutReplacingSelectionOrSending() {
        prepareComposer()
        val source = imageUri()
        paste(ClipData.newUri(context.contentResolver, "test image", source))
        assertImported(source)
        // Clipboard replacement cannot revoke access to the independently owned chip.
        compose.runOnIdle {
            context
                .getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("replacement", "different"))
            assertTrue(File(context.cacheDir, "pasted_images/${attachments.single().name}").isFile)
        }
    }

    @Test
    fun imeCommitContent_usesRealInputConnectionAndPreservesDraft() {
        prepareComposer(interceptIme = true)
        compose.waitUntil(timeoutMillis = 5_000) { connection != null }
        val source = imageUri()
        compose.runOnIdle {
            assertTrue(
                editorInfo!!.contentMimeTypes.orEmpty().any { ClipDescription.compareMimeTypes("image/png", it) },
            )
            assertTrue(
                connection!!.commitContent(
                    InputContentInfo(source, ClipDescription("image", arrayOf("image/png")), null),
                    0,
                    null,
                ),
            )
        }
        assertImported(source)
    }

    @Test
    fun mixedClipboard_keepsTextItemsOnTheNormalPastePath() {
        prepareComposer()
        val source = imageUri()
        val clip = ClipData(ClipDescription("mixed", arrayOf("image/png", "text/plain")), ClipData.Item("hello"))
        clip.addItem(ClipData.Item(source))
        paste(clip)
        compose.waitUntil(timeoutMillis = 10_000) { attachments.size == 1 && !controller.isReceiving }
        compose.runOnIdle {
            assertEquals("khellop draft", input.text.toString())
            assertFalse(input.text.contains("content://"))
            assertEquals(0, sends)
            assertTrue(errors.isEmpty())
        }
    }

    @Test
    fun dualRepresentationItem_preservesTextPasteAndImportsImage() {
        prepareComposer()
        val source = imageUri()
        val clip =
            ClipData(
                ClipDescription("dual", arrayOf("image/png", "text/plain")),
                ClipData.Item("hello", null, null, source),
            )
        paste(clip)
        compose.waitUntil(timeoutMillis = 10_000) { attachments.size == 1 && !controller.isReceiving }
        compose.runOnIdle {
            assertEquals("khellop draft", input.text.toString())
            assertEquals("image/png", attachments.single().mimeType)
            assertNotEquals(source.toString(), attachments.single().uri)
            assertTrue(File(context.cacheDir, "pasted_images/${attachments.single().name}").isFile)
            assertTrue(errors.isEmpty())
            assertEquals(0, sends)
        }
    }

    @Test
    fun mixedClipboard_importsImageDespitePdfAndPreservesText() {
        prepareComposer()
        val image = imageUri()
        val pdf = pdfUri()
        val clip =
            ClipData(
                ClipDescription("mixed", arrayOf("image/png", "application/pdf", "text/plain")),
                ClipData.Item("hello"),
            )
        clip.addItem(ClipData.Item(image))
        clip.addItem(ClipData.Item(pdf))
        paste(clip)
        compose.waitUntil(timeoutMillis = 10_000) { attachments.size == 1 && !controller.isReceiving }
        compose.runOnIdle {
            assertEquals("khellop draft", input.text.toString())
            assertEquals("image/png", attachments.single().mimeType)
            assertNotEquals(image.toString(), attachments.single().uri)
            assertTrue(File(context.cacheDir, "pasted_images/${attachments.single().name}").isFile)
            assertTrue(errors.isEmpty())
            assertEquals(0, sends)
        }
    }

    @Test
    fun plainTextPaste_stillReplacesOnlyTheSelection() {
        prepareComposer()
        paste(ClipData.newPlainText("text", "hello"))
        compose.runOnIdle {
            assertEquals("khellop draft", input.text.toString())
            assertTrue(attachments.isEmpty())
            assertEquals(0, sends)
        }
    }

    @After
    fun cleanupOnlyTestOwnedFiles() {
        if (::controller.isInitialized) compose.runOnIdle { controller.close() }
        ownedFiles.forEach { it.delete() }
        attachments.forEach { File(context.cacheDir, "pasted_images/${it.name}").delete() }
    }
}
