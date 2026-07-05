package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class ProfilesResponse(
    val profiles: List<ProfileInfo>,
)

@Serializable
data class ProfileInfo(
    val name: String,
    val path: String? = null,
    val is_default: Boolean? = null,
    val model: String? = null,
    val provider: String? = null,
    val has_env: Boolean? = null,
    val skill_count: Int? = null,
    val gateway_running: Boolean? = null,
    val description: String? = null,
    val description_auto: Boolean? = null,
)

@Serializable
data class ActiveProfileResponse(
    val active: String,
    val current: String?,
)

@Serializable
data class SetActiveProfileRequest(
    val name: String,
)

@Serializable
data class ProfileSoulResponse(
    val content: String,
)

@Serializable
data class UpdateProfileSoulRequest(
    val content: String,
)

@Serializable
data class UpdateProfileModelRequest(
    val provider: String,
    val model: String,
)

@Serializable
data class CloneProfileRequest(
    val name: String,
)

@Serializable
data class UpdateProfileDescriptionRequest(
    val description: String,
)

@Serializable
data class CreateProfileRequest(
    val name: String,
    val description: String?,
    val provider: String?,
    val model: String?,
    val mcp_servers: List<McpServerConfigInput>?,
    val keep_skills: Boolean?,
    val hub_skills: List<String>?,
)

@Serializable
data class McpServerConfigInput(
    val name: String,
    val transport: String,
    val url: String? = null,
    val command: String? = null,
    val args: List<String>? = null,
)
