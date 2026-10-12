package com.m57.hermescontrol.data.remote

/** A chooser edits a draft only. Tokens reject callbacks after dismissal, clear or host edits. */
internal class CertificateEditSession {
    private var revision = 0L
    private var closed = false

    @Synchronized
    fun beginSelection(): Long = ++revision

    @Synchronized
    fun accept(token: Long): Boolean {
        if (closed || token != revision) return false
        revision++
        return true
    }

    @Synchronized
    fun invalidate() {
        revision++
    }

    @Synchronized
    fun close() {
        closed = true
        revision++
    }
}
