package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class LockSkillRequest(
    val name: String,
    val locked: Boolean,
    val profile: String? = null,
)

@Serializable
data class LockSkillResponse(
    val ok: Boolean,
    val name: String,
    val locked: Boolean,
)
