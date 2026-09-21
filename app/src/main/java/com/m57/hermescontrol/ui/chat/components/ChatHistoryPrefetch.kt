package com.m57.hermescontrol.ui.chat.components

/** One older-page request per deliberate upward gesture, including an overscroll at index zero. */
internal class ChatHistoryPrefetch {
    private var upwardPixels = 0f
    private var requested = false

    fun onScroll(
        firstVisibleIndex: Int,
        deltaY: Float,
        userInput: Boolean,
        canLoad: Boolean,
    ): Boolean {
        if (userInput && deltaY < 0f) upwardPixels = 0f
        if (deltaY <= 0f || !canLoad || requested) return false
        // A fling retains the drag's intent until scrolling stops. Programmatic
        // scrolling without an upward user gesture must never load history.
        if (!userInput && upwardPixels == 0f) return false
        upwardPixels += deltaY
        if (firstVisibleIndex > 25 || upwardPixels < 24f) return false
        requested = true
        return true
    }

    fun endGesture() {
        upwardPixels = 0f
        requested = false
    }
}
