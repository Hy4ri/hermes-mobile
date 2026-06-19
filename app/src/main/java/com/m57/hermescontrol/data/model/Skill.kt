package com.m57.hermescontrol.data.model

data class Skill(
    val name: String,
    val description: String?,
    val category: String?,
    val enabled: Boolean,
    val content: String? = null,
)
