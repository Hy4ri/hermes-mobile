package com.m57.hermescontrol.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SkillHubPreviewResponse(
    val name: String,
    @SerialName("skill_md")
    val skillMd: String,
    val description: String = "",
    val identifier: String = "",
    val files: List<String> = emptyList(),
)
