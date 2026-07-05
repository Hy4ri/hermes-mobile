package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class SaveSkillContentRequest(
    val name: String,
    val content: String,
)
