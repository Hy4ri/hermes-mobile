package com.m57.hermescontrol.ui.chat.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.data.model.Attachment
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class AttachmentChipTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun imageThumbnailClick_opensPreviewWithoutRemovingAttachment() {
        var previewed = false
        var removed = false
        val attachment =
            Attachment(
                uri = "content://image/preview",
                name = "preview.jpg",
                mimeType = "image/jpeg",
                size = 123L,
            )

        composeTestRule.setContent {
            AttachmentChip(
                attachment = attachment,
                onPreview = { previewed = true },
                onRemove = { removed = true },
            )
        }

        composeTestRule.onNodeWithTag("attachment_preview").performClick()
        composeTestRule.runOnIdle {
            assertTrue("image tap must open preview", previewed)
            assertTrue("image tap must not remove attachment", !removed)
        }
    }
}
