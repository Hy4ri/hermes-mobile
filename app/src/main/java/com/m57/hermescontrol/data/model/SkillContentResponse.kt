package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class SkillContentResponse(
    val name: String,
    val content: String,
)
