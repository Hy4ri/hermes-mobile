package com.m57.hermescontrol.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GatewayMigrationPlan(
    @SerialName("default_home") val defaultHome: String? = null,
    val profiles: List<GatewayMigrationProfile> = emptyList(),
    @SerialName("multiplex_flag_on") val multiplexFlagOn: Boolean = false,
    @SerialName("live_served") val liveServed: List<String>? = null,
    @SerialName("already_multiplexed") val alreadyMultiplexed: Boolean = false,
    val interrupted: Boolean = false,
    val blockers: List<String> = emptyList(),
    val notices: List<String> = emptyList(),
    val eligible: Boolean = false,
    val command: String? = null,
)

@Serializable
data class GatewayMigrationProfile(
    val profile: String? = null,
    val home: String? = null,
    val pid: Int? = null,
    val service: GatewayMigrationService? = null,
    val services: List<String> = emptyList(),
    @SerialName("run_as_user") val runAsUser: String? = null,
    val uid: Int? = null,
    @SerialName("runtime_home") val runtimeHome: String? = null,
)

@Serializable
data class GatewayMigrationService(
    val kind: String? = null,
    val system: Boolean? = null,
)

@Serializable
data class GatewayMigrationStartResponse(
    val ok: Boolean = false,
    val pid: Int? = null,
    val name: String? = null,
)
