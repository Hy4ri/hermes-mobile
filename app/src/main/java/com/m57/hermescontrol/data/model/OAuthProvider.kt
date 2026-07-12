package com.m57.hermescontrol.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OAuth provider catalog entry returned by `GET /api/providers/oauth`.
 *
 * Wire schema (hermes_cli/web_server.py `list_oauth_providers`, ~:8415):
 *   id, name, flow ("pkce" | "device_code" | "external"), cli_command,
 *   docs_url, disconnect_hint, disconnect_command, disconnectable, status.
 *
 * `expires_at` in the status comes back as an ISO timestamp string.
 */
@Serializable
data class OAuthProvider(
    val id: String,
    val name: String,
    val flow: String,
    @SerialName("cli_command") val cliCommand: String = "",
    @SerialName("docs_url") val docsUrl: String? = null,
    @SerialName("disconnect_hint") val disconnectHint: String? = null,
    @SerialName("disconnect_command") val disconnectCommand: String? = null,
    val disconnectable: Boolean = true,
    val status: OAuthProviderStatus = OAuthProviderStatus(),
)

@Serializable
data class OAuthProviderStatus(
    @SerialName("logged_in") val loggedIn: Boolean = false,
    val source: String? = null,
    @SerialName("source_label") val sourceLabel: String? = null,
    @SerialName("token_preview") val tokenPreview: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("has_refresh_token") val hasRefreshToken: Boolean? = null,
    @SerialName("last_refresh") val lastRefresh: String? = null,
    val error: String? = null,
)

@Serializable
data class OAuthProvidersResponse(
    val providers: List<OAuthProvider> = emptyList(),
)

/**
 * `POST /api/providers/oauth/{provider_id}/start`
 *
 * Discriminated by `flow`:
 *   - pkce:         { session_id, flow:"pkce", auth_url, expires_in }
 *   - device_code:  { session_id, flow:"device_code", user_code,
 *                     verification_url, expires_in, poll_interval }
 *
 * `expires_in` is an int (seconds). We keep the union fields nullable so a
 * single model parses both shapes.
 */
@Serializable
data class OAuthStartResponse(
    @SerialName("session_id") val sessionId: String = "",
    val flow: String = "",
    @SerialName("auth_url") val authUrl: String? = null,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("verification_url") val verificationUrl: String? = null,
    @SerialName("expires_in") val expiresIn: Int? = null,
    @SerialName("poll_interval") val pollInterval: Int? = null,
)

@Serializable
data class OAuthSubmitRequest(
    @SerialName("session_id") val sessionId: String,
    val code: String,
)

/** `POST /api/providers/oauth/{provider_id}/submit` */
@Serializable
data class OAuthSubmitResponse(
    val ok: Boolean = false,
    val status: String? = null,
    val message: String? = null,
)

/**
 * `GET /api/providers/oauth/{provider_id}/poll/{session_id}`
 *
 * `expires_at` is a float epoch (web_server.py `time.time()`), kept as Double?.
 */
@Serializable
data class OAuthPollResponse(
    @SerialName("session_id") val sessionId: String = "",
    val status: String = "pending",
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("expires_at") val expiresAt: Double? = null,
)

/** `DELETE /api/providers/oauth/sessions/{session_id}` */
@Serializable
data class OAuthCancelResponse(
    val ok: Boolean = false,
    @SerialName("session_id") val sessionId: String? = null,
    val message: String? = null,
)
