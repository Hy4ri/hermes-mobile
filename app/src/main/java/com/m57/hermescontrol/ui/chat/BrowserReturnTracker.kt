package com.m57.hermescontrol.ui.chat

/** Tracks a launched browser across pause/resume and screen disposal (issue #1321). */
internal class BrowserReturnTracker {
    data class Returned(
        val operationId: String?,
    )

    private var inFlight = false
    private var departed = false
    private var operationId: String? = null

    fun start(operationId: String? = null) {
        this.operationId = operationId
        inFlight = true
        departed = false
    }

    fun onPause() {
        if (inFlight) departed = true
    }

    /** Only an actual departure followed by resume counts as returning from the browser. */
    fun onResume(): Returned? {
        if (!inFlight || !departed) return null
        val returned = Returned(operationId)
        cancel()
        return returned
    }

    /** Drop the tracking after a failed launch; the lifecycle guard handles the failed lease. */
    fun cancel() {
        inFlight = false
        departed = false
        operationId = null
    }

    /** Non-configuration disposal must release an outstanding departed activity lease. */
    fun abandon(): Boolean {
        val outstanding = inFlight && departed
        cancel()
        return outstanding
    }
}
