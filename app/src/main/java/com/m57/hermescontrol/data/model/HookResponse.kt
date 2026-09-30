package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class HookResponse(
    val hooks: List<HookEntry>? = null,
    val valid_events: List<String>? = null,
)

@Serializable
data class HookEntry(
    val event: String? = null,
    val matcher: String? = null,
    val command: String? = null,
    val timeout: Int? = null,
    val allowed: Boolean? = null,
    val approved_at: String? = null,
    val executable: Boolean? = null,
)

/** Body for POST api/ops/hooks. The backend field is `approve` (writes the consent allowlist entry). */
@Serializable
data class HookCreateRequest(
    val event: String,
    val command: String,
    val matcher: String? = null,
    val timeout: Int? = null,
    val approve: Boolean? = null,
)

@Serializable
data class HookDeleteRequest(
    val event: String,
    val command: String,
)
