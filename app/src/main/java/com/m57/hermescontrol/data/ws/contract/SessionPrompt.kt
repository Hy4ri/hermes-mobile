package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SessionCreateParams(
    @SerialName("source") val source: String? = null,
    @SerialName("profile") val profile: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("hidden") val hidden: Boolean? = null,
    @SerialName("cwd") val cwd: String? = null,
    @SerialName("cwd_explicit") val cwdExplicit: Boolean? = null,
)

/**
 * The `source` this client declares for every session it mints or resumes.
 *
 * Mobile presents the desktop surface, so it claims `"desktop"` on BOTH `session.create` and
 * `session.resume`. The gateway resolves an omitted `source` from the host environment
 * (`_resolve_session_source`), which on a headless backend is `"tui"`; a resumed runtime then
 * reads as a surface switch and loses the `desktop_ui` toolset (#1450). One constant so the
 * create and resume call sites cannot drift apart again.
 *
 * The session-authority gateway accepts only creation labels on `session.create`; there the
 * Desktop surface is `"gui"` (stored as `"desktop"`). A create it refuses for `source` is sent
 * again once with [GUI_CREATE_SOURCE], and that host keeps the label (#1711, `RpcChannel`).
 */
const val DESKTOP_SESSION_SOURCE = "desktop"

/** The gateway's `session.create` label for the Desktop surface (`session_policy.SURFACES`). */
const val GUI_CREATE_SOURCE = "gui"

@Serializable
data class SessionResumeParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("source") val source: String? = null,
    @SerialName("omit_messages") val omitMessages: Boolean? = null,
    @SerialName("profile") val profile: String? = null,
)

@Serializable
data class PromptSubmitParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("text") val text: String,
    @SerialName("queued") val queued: Boolean? = null,
)

// tolerant readers: all nullable with null defaults, only fields callers read
@Serializable
data class SessionCreateResult(
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("stored_session_id") val storedSessionId: String? = null,
)

@Serializable
data class SessionResumeResult(
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
data class PromptSubmitResult(
    val status: String? = null,
    @SerialName("admission_id") val admissionId: String? = null,
)
