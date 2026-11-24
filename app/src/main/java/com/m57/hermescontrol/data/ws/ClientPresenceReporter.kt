package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.ws.contract.ClientActivityParams
import com.m57.hermescontrol.data.ws.contract.ClientIdentity

/** Identity hello name, matching the gateway's `[a-z0-9][a-z0-9._-]{0,63}`. */
internal const val CLIENT_NAME = "hermes-mobile"

/** `client.activity` takes at most this many session ids. */
internal const val MAX_ACTIVITY_SESSIONS = 16

/** While the app is in the foreground, interaction alone re-reports at most this often. */
internal const val ACTIVITY_REPORT_INTERVAL_MS = 60_000L

private const val VERSION_MAX = 64

/** The hello label; [version] is cut to 1-64 printable ASCII without spaces, or omitted when nothing is left. */
internal fun clientIdentity(version: String): ClientIdentity =
    ClientIdentity(
        name = CLIENT_NAME,
        version = version.filter { it in '!'..'~' }.take(VERSION_MAX).ifEmpty { null },
        kind = "mobile",
    )

/**
 * Tells a notification plugin whether the user is at this client (`client.activity`). Reports only
 * once the backend advertised `client_activity: true`. Not thread-safe by itself: every call is
 * serialized on this object's monitor.
 */
internal class ClientPresenceReporter(
    private val clock: () -> Long,
    private val send: (ClientActivityParams) -> Unit,
) {
    private var supported = false
    private var foreground = false
    private var canAlert = true
    private var sessions: List<String> = emptyList()
    private var lastInteractionMs = clock()
    private var lastReportMs: Long? = null

    @Synchronized
    fun setSupported(value: Boolean) {
        if (supported == value) return
        supported = value
        lastReportMs = null
        if (value) report()
    }

    @Synchronized
    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        // Coming back to the app is itself user input.
        if (value) lastInteractionMs = clock()
        report()
    }

    @Synchronized
    fun setSessions(ids: List<String>) {
        val next = ids.distinct().take(MAX_ACTIVITY_SESSIONS)
        if (sessions == next) return
        sessions = next
        report()
    }

    @Synchronized
    fun setCanAlert(value: Boolean) {
        if (canAlert == value) return
        canAlert = value
        report()
    }

    @Synchronized
    fun onInteraction() {
        val now = clock()
        lastInteractionMs = now
        val last = lastReportMs
        if (foreground && (last == null || now - last >= ACTIVITY_REPORT_INTERVAL_MS)) report()
    }

    private fun report() {
        if (!supported) return
        val now = clock()
        lastReportMs = now
        send(
            ClientActivityParams(
                sessions = sessions,
                idleSeconds = ((now - lastInteractionMs).coerceAtLeast(0L)) / 1000.0,
                basis = "page",
                canAlert = canAlert,
                away = !foreground,
            ),
        )
    }
}
