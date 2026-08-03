package com.m57.hermescontrol.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DM pairing management (issue #776).
 *
 * Backend contract (hermes_cli/web_server.py + gateway/pairing.py):
 * - `GET /api/pairing` → `{pending: [...], approved: [...]}`
 * - Pending entries: `{platform, request_id, user_id, user_name, age_minutes}`
 * - Approved entries: `{platform, user_id, user_name, approved_at}`
 *
 * Codes are stored hashed server-side and never returned; pending requests are
 * approved by their `request_id` (the desktop dashboard does the same).
 */
@Serializable
data class PairingResponse(
    val pending: List<PairingItem> = emptyList(),
    val approved: List<PairingItem> = emptyList(),
)

@Serializable
data class PairingItem(
    val platform: String,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("user_name") val userName: String? = null,
    @SerialName("age_minutes") val ageMinutes: Int? = null,
) {
    /** Stable key for LazyColumn + per-item action state. */
    fun key(): String = "${platform}_${requestId ?: userId ?: ""}"
}

@Serializable
data class PairingApproveRequest(
    val platform: String,
    @SerialName("request_id") val requestId: String? = null,
    val code: String? = null,
)

@Serializable
data class PairingRevokeRequest(
    val platform: String,
    @SerialName("user_id") val userId: String,
)
