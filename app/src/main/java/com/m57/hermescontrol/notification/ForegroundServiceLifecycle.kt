package com.m57.hermescontrol.notification

/**
 * Orders foreground-service requests and retirement (rotation/start-stop regression).
 * A stop before creation is deferred until promotion. Android's stopSelfResult(startId)
 * protects a delivered start from tearing down a newer start still queued by the system.
 */
internal class ForegroundServiceLifecycle {
    private var requested = false
    private var owner: Any? = null
    private var stopCurrent: (() -> Boolean)? = null

    var generation = 0L
        @Synchronized get
        private set

    @Synchronized
    fun start(request: () -> Unit) {
        val wasRequested = requested
        val previousGeneration = generation
        generation++
        requested = true
        try {
            request()
        } catch (error: Throwable) {
            requested = wasRequested
            generation = previousGeneration
            throw error
        }
    }

    @Synchronized
    fun stop() {
        requested = false
        retire()
    }

    @Synchronized
    fun onStart(
        owner: Any,
        promote: () -> Unit,
        stopLatest: () -> Boolean,
    ) {
        // Fulfil each startForegroundService request before allowing any retirement.
        promote()
        this.owner = owner
        stopCurrent = stopLatest
        if (!requested) retire()
    }

    @Synchronized
    fun complete(expectedGeneration: Long) {
        if (generation != expectedGeneration) return
        requested = false
        retire()
    }

    @Synchronized
    fun onDestroyed(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            stopCurrent = null
        }
    }

    private fun retire() {
        if (stopCurrent?.invoke() == true) {
            owner = null
            stopCurrent = null
        }
    }
}
