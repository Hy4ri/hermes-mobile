package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class ToggleSkillRequest(
    val name: String,
    val enabled: Boolean,
)
