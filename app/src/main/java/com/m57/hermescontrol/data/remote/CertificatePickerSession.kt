package com.m57.hermescontrol.data.remote

/** One picker invocation. Recovery and late callbacks cannot finish a newer invocation. */
internal class CertificatePickerSession(
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val result: (String?, Boolean) -> Unit,
) {
    private var finished = false
    private var paused = false
    private var awaitingCallback = true
    private var cancelRecovery: (() -> Unit)? = null
    private var recoveryRevision = 0L

    @Synchronized
    fun launched() {
        // A launch that never leaves the resumed host must not disable selection forever.
        recoverAfter(5_000, valid = false)
    }

    @Synchronized
    fun paused() {
        paused = true
        if (awaitingCallback) {
            recoveryRevision++
            cancelRecovery?.invoke()
        }
    }

    @Synchronized
    fun resumed() {
        if (paused && awaitingCallback && !finished) {
            paused = false
            // KeyChain's Binder callback can arrive just after onResume.
            recoverAfter(1_000, valid = true)
        }
    }

    @Synchronized
    fun callbackReceived(): Boolean {
        if (finished || !awaitingCallback) return false
        awaitingCallback = false
        // Reading KeyChain key/chain access is also bounded from the UI's perspective.
        recoverAfter(15_000, valid = false)
        return true
    }

    @Synchronized
    fun finish(
        alias: String?,
        valid: Boolean,
    ) {
        if (finished) return
        finished = true
        recoveryRevision++
        cancelRecovery?.invoke()
        result(alias, valid)
    }

    private fun recoverAfter(
        delayMillis: Long,
        valid: Boolean,
    ) {
        if (finished) return
        cancelRecovery?.invoke()
        val attempt = ++recoveryRevision
        cancelRecovery =
            schedule(delayMillis) {
                synchronized(this) {
                    if (attempt == recoveryRevision) finish(null, valid)
                }
            }
    }
}
