package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class PairingResponse(
    val pending: List<PairingItem>,
    val approved: List<PairingItem>,
)

@Serializable
data class PairingItem(
    val platform: String,
    val user_id: String?,
    val username: String?,
    val display_name: String?,
    val code: String?,
    val created_at: String?,
)

@Serializable
data class PairingApproveRequest(
    val platform: String,
    val code: String,
)

@Serializable
data class PairingRevokeRequest(
    val platform: String,
    val user_id: String,
)
