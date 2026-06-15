package com.m57.hermescontrol.data.model

data class ProfilesResponse(
    val profiles: List<ProfileInfo>,
)

data class ProfileInfo(
    val name: String,
    val path: String?,
    val is_default: Boolean?,
    val model: String?,
    val provider: String?,
    val has_env: Boolean?,
    val skill_count: Int?,
    val gateway_running: Boolean?,
    val description: String?,
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

data class UpdateProfileDescriptionRequest(
    val description: String,
)
