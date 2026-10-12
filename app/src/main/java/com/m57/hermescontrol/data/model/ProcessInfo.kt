package com.m57.hermescontrol.data.model

/**
 * A single background process owned by a session, as returned by the gateway
 * `process.list` RPC.
 *
 * Field names match the gateway JSON exactly (Gson deserializes by name,
 * snake_case). See `tui_gateway/server.py::_session_processes` and
 * `tools/process_registry.list_sessions` for the canonical shape.
 *
 * @property sessionId Stable process id used by `process.kill` as `process_id`.
 * @property command First 200 chars of the spawned command line.
 * @property cwd Working directory the process was launched in.
 * @property pid OS process id (0 / null for detached or not-yet-spawned).
 * @property startedAt ISO-8601 local timestamp string from the backend.
 * @property uptimeSeconds Seconds since spawn (backend-computed).
 * @property status `"running"` or `"exited"`.
 * @property outputPreview Last 200 chars of buffered output.
 * @property outputTail Last 4000 chars of buffered output (richer preview).
 * @property exitCode Present only when [status] == `"exited"`.
 * @property sessionScoped True when surfaced only because it shares the gateway
 *   session (not the current task) — a possibly-forgotten long-lived process.
 */
data class ProcessInfo(
    val sessionId: String,
    val command: String? = null,
    val cwd: String? = null,
    val pid: Int? = null,
    val startedAt: String? = null,
    val uptimeSeconds: Int? = null,
    val status: String? = null,
    val outputPreview: String? = null,
    val outputTail: String? = null,
    val exitCode: Int? = null,
    val sessionScoped: Boolean = false,
) {
    val isRunning: Boolean get() = status != "exited"

    /** A short, human-readable title derived from the command line. */
    val title: String
        get() =
            (command ?: "")
                .lineSequence()
                .firstOrNull()
                ?.trim()
                .orEmpty()
                .ifEmpty { "background process" }

    companion object {
        /** Build a [ProcessInfo] from a raw `process.list` entry map; null when `session_id` is missing. */
        fun fromMap(raw: Map<*, *>): ProcessInfo? {
            val m = raw.mapKeys { it.key.toString() }
            val id = (m["session_id"] as? String)?.takeIf { it.isNotBlank() } ?: return null

            fun str(key: String) = (m[key] as? String)?.takeIf { it.isNotBlank() }

            fun int(key: String) = (m[key] as? Number)?.toInt()
            return ProcessInfo(
                sessionId = id,
                command = str("command"),
                cwd = str("cwd"),
                pid = int("pid"),
                startedAt = str("started_at"),
                uptimeSeconds = int("uptime_seconds"),
                status = str("status"),
                outputPreview = str("output_preview"),
                outputTail = str("output_tail"),
                exitCode = int("exit_code"),
                sessionScoped = (m["session_scoped"] as? Boolean) ?: false,
            )
        }
    }
}
