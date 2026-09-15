package com.m57.hermescontrol.ui.chat.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentTrayPositionProviderTest {
    private val provider = AttachmentTrayPositionProvider(marginPx = 16)
    private val windowSize = IntSize(width = 500, height = 1_000)
    private val popupSize = IntSize(width = 280, height = 100)

    @Test
    fun ltr_clampsToVisibleWindowWithHorizontalMargin() {
        val position =
            provider.calculatePosition(
                anchorBounds = IntRect(left = 400, top = 700, right = 500, bottom = 900),
                windowSize = windowSize,
                layoutDirection = LayoutDirection.Ltr,
                popupContentSize = popupSize,
            )

        assertEquals(IntOffset(x = 204, y = 584), position)
    }

    @Test
    fun rtl_clampsToVisibleWindowWithHorizontalMargin() {
        val position =
            provider.calculatePosition(
                anchorBounds = IntRect(left = 0, top = 700, right = 100, bottom = 900),
                windowSize = windowSize,
                layoutDirection = LayoutDirection.Rtl,
                popupContentSize = popupSize,
            )

        assertEquals(IntOffset(x = 16, y = 584), position)
    }
}
