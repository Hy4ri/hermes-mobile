package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AttachmentBase64EncoderTest {
    @Test
    fun inputOverLimit_isRejectedWithoutGrowingTheEncodedOutput() {
        val output = ByteArrayOutputStream()
        val result =
            encodeAttachmentBase64(
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)),
                output = output,
                maxBytes = 4,
            )

        assertEquals(AttachmentSizeResult.TOO_LARGE, result)
        assertTrue(output.size() <= 8)
    }

    @Test
    fun inputAtLimit_isEncodedWithoutLineBreaks() {
        val output = ByteArrayOutputStream()
        val result =
            encodeAttachmentBase64(
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
                output = output,
                maxBytes = 4,
            )

        assertEquals(AttachmentSizeResult.WITHIN_LIMIT, result)
        assertEquals("AQIDBA==", output.toString(Charsets.US_ASCII.name()))
    }
}
