package com.m57.hermescontrol

import android.os.Handler
import android.os.Looper

/** Coordinates background reply handling and a bounded connection lease for external activities. */
object ExternalActivityLifecycleGuard {
    private const val CONNECTION_LEASE_TIMEOUT_MS = 10 * 60 * 1_000L

    private val lock = Any()
    private var generation = 0L
    private var externalActivityInFlight = false
    private var resultDelivered = false
    private var hostResumed = true
    private var releaseConnectionLease: (() -> Unit)? = null

    fun launchExternalActivity(
        acquireConnectionLease: () -> Unit,
        releaseConnectionLease: () -> Unit,
        prepareForBackground: () -> Unit,
        cleanupAfterLaunchFailure: () -> Unit,
        scheduleTimeout: (Long, () -> Unit) -> Unit = ::scheduleTimeout,
        launch: () -> Unit,
    ) {
        val launchGeneration =
            synchronized(lock) {
                generation += 1
                externalActivityInFlight = true
                resultDelivered = false
                this.releaseConnectionLease = releaseConnectionLease
                generation
            }

        try {
            acquireConnectionLease()
            scheduleTimeout(CONNECTION_LEASE_TIMEOUT_MS) {
                finishExternalActivity(launchGeneration)
            }
            prepareForBackground()
            launch()
        } catch (error: Throwable) {
            cleanupAfterLaunchFailure()
            finishExternalActivity(launchGeneration)
            throw error
        }
    }

    fun onHostPaused() {
        synchronized(lock) {
            hostResumed = false
        }
    }

    fun onHostResumed() {
        val release =
            synchronized(lock) {
                hostResumed = true
                if (externalActivityInFlight && resultDelivered) finishExternalActivityLocked() else null
            }
        release?.invoke()
    }

    fun externalActivityReturned() {
        val release =
            synchronized(lock) {
                if (!externalActivityInFlight) return
                resultDelivered = true
                if (hostResumed) finishExternalActivityLocked() else null
            }
        release?.invoke()
    }

    internal fun resetForTest() {
        synchronized(lock) {
            generation += 1
            externalActivityInFlight = false
            resultDelivered = false
            hostResumed = true
            releaseConnectionLease = null
        }
    }

    private fun finishExternalActivity(expectedGeneration: Long) {
        val release =
            synchronized(lock) {
                if (!externalActivityInFlight || generation != expectedGeneration) return
                finishExternalActivityLocked()
            }
        release?.invoke()
    }

    private fun finishExternalActivityLocked(): (() -> Unit)? {
        externalActivityInFlight = false
        resultDelivered = false
        return releaseConnectionLease.also { releaseConnectionLease = null }
    }

    private fun scheduleTimeout(
        delayMs: Long,
        action: () -> Unit,
    ) {
        Handler(Looper.getMainLooper()).postDelayed(action, delayMs)
    }
}
