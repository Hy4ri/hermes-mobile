package com.m57.hermescontrol.ui.chat

import coil3.network.HttpException
import coil3.network.NetworkResponse
import com.m57.hermescontrol.data.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ChatImageDiagnosticsTest {
    @Test
    fun loadMetadataKeepsStatusAndExceptionTypesWithoutLeakingPayloads() {
        val url = "https://user:password@private.example/image?path=/private/photo.jpg&token=secret#fragment"
        val error = IOException("request failed: $url", HttpException(NetworkResponse(code = 403)))
        val line = ChatImageDiagnostics.loadLine("error", url, "opaque-row:0", error)
        assertEquals("phase=error source=https row=opaque-row:0 error=IOException>HttpException http=403", line)
        listOf("secret", "private", "password", "fragment", "request failed").forEach {
            assertFalse(line.contains(it))
        }
    }

    @Test
    fun localAndEmbeddedSourcesNeverPrintUrisOrImageBytes() {
        mapOf(
            "content://private.provider/photo/secret" to "content",
            "file:///private/photo.jpg" to "file",
            "data:image/png;base64,SECRET" to "data",
            "http://private.example/a?token=SECRET" to "http",
            "unrecognized-secret" to "other",
        ).forEach { (uri, source) ->
            assertEquals(source, ChatImageDiagnostics.source(uri))
            val line = ChatImageDiagnostics.loadLine("start", uri, null)
            assertFalse(line.contains(uri))
            assertFalse(line.contains("secret", ignoreCase = true))
        }
    }

    @Test
    fun mergeDiagnosticsExposeAttachmentLossWithoutContentPathsOrRawIdentity() {
        val before =
            ChatMessage(
                id = "private-session-id",
                role = MessageRole.USER,
                content = "private caption\n@image:/private/photo.jpg",
                attachments = listOf(Attachment("content://private/secret", "private.jpg", "image/jpeg")),
            )
        val after = before.copy(content = "private caption", attachments = null)
        val lines = ChatImageDiagnostics.historyLines(listOf(before), listOf(after), listOf(after), cached = false)
        assertTrue(lines.any { it.contains("stage=before") && it.contains("attachments=1 sources=content") })
        assertTrue(lines.any { it.contains("stage=after") && it.contains("refs=false attachments=0") })
        val text = lines.joinToString()
        assertFalse(text.contains("private"))
        assertFalse(text.contains("secret"))
        assertTrue(text.contains("row=${ChatImageDiagnostics.rowKey(before.id)}"))
    }

    @Test
    fun textOnlyHistoryProducesNoDiagnosticNoise() {
        val message = ChatMessage(role = MessageRole.USER, content = "text only")
        assertTrue(ChatImageDiagnostics.historyLines(listOf(message), listOf(message), listOf(message), true).isEmpty())
    }
}
