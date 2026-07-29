package com.m57.hermescontrol.ui.chat

import android.app.Activity
import android.net.Uri
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentSavePolicyTest {
    @Test
    fun `save result accepts a destination only for RESULT_OK`() {
        val destination = mockk<Uri>()

        assertSame(destination, acceptedSaveDestination(Activity.RESULT_OK, destination))
        assertNull(acceptedSaveDestination(Activity.RESULT_CANCELED, destination))
        assertNull(acceptedSaveDestination(Activity.RESULT_OK, null))
    }

    @Test
    fun `picker cannot start while picker or save is pending`() {
        assertTrue(canStartAttachmentSave(null, null))
        assertFalse(canStartAttachmentSave("/tmp/picker.pdf", null))
        assertFalse(canStartAttachmentSave(null, "/tmp/saving.pdf"))
    }
}
