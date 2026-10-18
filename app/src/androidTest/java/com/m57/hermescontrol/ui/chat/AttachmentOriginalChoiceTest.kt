package com.m57.hermescontrol.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.chat.components.AttachmentChip
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AttachmentOriginalChoiceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun originalControlReflectsChoiceAndChangesItOncePerTap() {
        var attachment by mutableStateOf(Attachment("content://photo", "photo.jpg", "image/jpeg"))
        var calls = 0
        compose.setContent {
            HermesControlTheme {
                AttachmentChip(
                    attachment = attachment,
                    onPreview = {},
                    onRemove = {},
                    onSendOriginalChange = {
                        calls++
                        attachment = attachment.copy(sendOriginal = it)
                    },
                )
            }
        }
        compose
            .onNodeWithTag("attachment_send_original")
            .assertIsNotSelected()
            .performClick()
            .assertIsSelected()
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onNodeWithTag("attachment_send_original").performClick().assertIsNotSelected()
        compose.runOnIdle { assertEquals(2, calls) }
    }
}
