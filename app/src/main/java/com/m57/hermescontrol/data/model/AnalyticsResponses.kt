package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

/**
 * Usage analytics over a trailing window, returned by `GET /api/analytics/usage`.
 * Mirrors `web/src/lib/api.ts` `AnalyticsResponse`.
 */
@Serializable
data class AnalyticsResponse(
    val daily: List<AnalyticsDailyEntry> = emptyList(),
    val by_model: List<AnalyticsModelEntry> = emptyList(),
    val totals: AnalyticsTotals = AnalyticsTotals(),
    val period_days: Int = 0,
    val skills: AnalyticsSkills = AnalyticsSkills(),
    val tools: List<Map<String, String>> = emptyList(),
)

@Serializable
data class AnalyticsDailyEntry(
    val day: String = "",
    val input_tokens: Long = 0,
    val output_tokens: Long = 0,
    val cache_read_tokens: Long = 0,
    val reasoning_tokens: Long = 0,
    val estimated_cost: Double = 0.0,
    val actual_cost: Double = 0.0,
    val sessions: Int = 0,
    val api_calls: Int = 0,
)

@Serializable
data class AnalyticsModelEntry(
    val model: String = "",
    val input_tokens: Long = 0,
    val output_tokens: Long = 0,
    val estimated_cost: Double = 0.0,
    val sessions: Int = 0,
    val api_calls: Int = 0,
)

@Serializable
data class AnalyticsTotals(
    val total_input: Long = 0,
    val total_output: Long = 0,
    val total_cache_read: Long = 0,
    val total_reasoning: Long = 0,
    val total_estimated_cost: Double = 0.0,
    val total_actual_cost: Double = 0.0,
    val total_sessions: Int = 0,
    val total_api_calls: Int = 0,
)

@Serializable
data class AnalyticsSkills(
    val summary: AnalyticsSkillsSummary = AnalyticsSkillsSummary(),
    val top_skills: List<AnalyticsSkillEntry> = emptyList(),
)

@Serializable
data class AnalyticsSkillEntry(
    val skill: String = "",
    val view_count: Int = 0,
    val manage_count: Int = 0,
    val total_count: Int = 0,
    val percentage: Double = 0.0,
    val last_used_at: Double? = null,
)

@Serializable
data class AnalyticsSkillsSummary(
    val total_skill_loads: Int = 0,
    val total_skill_edits: Int = 0,
    val total_skill_actions: Int = 0,
    val distinct_skills_used: Int = 0,
)

/**
 * Per-model analytics over a trailing window, returned by
 * `GET /api/analytics/models`. Mirrors `web/src/lib/api.ts`
 * `ModelsAnalyticsResponse`.
 */
@Serializable
data class ModelsAnalyticsResponse(
    val models: List<ModelsAnalyticsModelEntry> = emptyList(),
    val totals: ModelsAnalyticsTotals = ModelsAnalyticsTotals(),
    val period_days: Int = 0,
)

@Serializable
data class ModelsAnalyticsModelEntry(
    val model: String = "",
    val provider: String = "",
    val input_tokens: Long = 0,
    val output_tokens: Long = 0,
    val cache_read_tokens: Long = 0,
    val reasoning_tokens: Long = 0,
    val estimated_cost: Double = 0.0,
    val actual_cost: Double = 0.0,
    val sessions: Int = 0,
    val api_calls: Int = 0,
    val tool_calls: Int = 0,
    val last_used_at: Double? = null,
    val avg_tokens_per_session: Double = 0.0,
)

@Serializable
data class ModelsAnalyticsTotals(
    val distinct_models: Int = 0,
    val total_input: Long = 0,
    val total_output: Long = 0,
    val total_cache_read: Long = 0,
    val total_reasoning: Long = 0,
    val total_estimated_cost: Double = 0.0,
    val total_actual_cost: Double = 0.0,
    val total_sessions: Int = 0,
    val total_api_calls: Int = 0,
)
