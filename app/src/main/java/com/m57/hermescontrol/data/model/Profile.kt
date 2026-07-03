package com.m57.hermescontrol.data.model

data class ProfilesResponse(
    val profiles: List<ProfileInfo>,
)

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

data class ActiveProfileResponse(
    val active: String,
    val current: String?,
)

data class SetActiveProfileRequest(
    val name: String,
)

data class ProfileSoulResponse(
    val content: String,
)

data class UpdateProfileSoulRequest(
    val content: String,
)

data class UpdateProfileModelRequest(
    val provider: String,
    val model: String,
)

data class CloneProfileRequest(
    val name: String,
)

data class UpdateProfileDescriptionRequest(
    val description: String,
)

data class CreateProfileRequest(
    val name: String,
    val description: String?,
    val provider: String?,
    val model: String?,
    val mcp_servers: List<McpServerConfigInput>?,
    val keep_skills: Boolean?,
    val hub_skills: List<String>?,
)

data class McpServerConfigInput(
    val name: String,
    val transport: String,
    val url: String? = null,
    val command: String? = null,
    val args: List<String>? = null,
)
