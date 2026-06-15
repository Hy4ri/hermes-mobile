package com.m57.hermescontrol.data.model

data class AchievementsResponse(
    val achievements: List<Achievement>,
)

data class Achievement(
    val id: String,
    val name: String,
    val description: String?,
    val category: String?,
    val kind: String?,
    val icon: String?,
    val unlocked: Boolean,
    val discovered: Boolean,
    val state: String?,
    val tier: String?,
    val progress: Double?,
    val next_tier: String?,
    val next_threshold: Double?,
    val progress_pct: Double?,
)
