package com.m57.hermescontrol.data.model

import com.google.gson.annotations.SerializedName

data class AchievementsResponse(
    val achievements: List<Achievement>? = emptyList(),
    @SerializedName("unlocked_count") val unlockedCount: Int = 0,
    @SerializedName("discovered_count") val discoveredCount: Int = 0,
    @SerializedName("secret_count") val secretCount: Int = 0,
    @SerializedName("total_count") val totalCount: Int = 0,
    @SerializedName("is_stale") val isStale: Boolean = false,
    @SerializedName("generated_at") val generatedAt: Long? = null,
    @SerializedName("scan_meta") val scanMeta: ScanMeta? = null,
    val error: String? = null,
)

data class ScanMeta(
    val status: ScanStatus? = null,
)

data class ScanStatus(
    val state: String = "idle",
    @SerializedName("started_at") val startedAt: Long? = null,
    @SerializedName("finished_at") val finishedAt: Long? = null,
    @SerializedName("last_error") val lastError: String? = null,
    @SerializedName("last_duration_ms") val lastDurationMs: Long? = null,
    @SerializedName("run_count") val runCount: Int = 0,
)

data class RecentUnlock(
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
    @SerializedName("next_tier") val nextTier: String?,
    @SerializedName("next_threshold") val nextThreshold: Double?,
    @SerializedName("progress_pct") val progressPct: Double?,
    @SerializedName("unlocked_at") val unlockedAt: Long?,
    val secret: Boolean?,
    val criteria: String?,
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
    @SerializedName("next_tier") val nextTier: String?,
    @SerializedName("next_threshold") val nextThreshold: Double?,
    @SerializedName("progress_pct") val progressPct: Double?,
    @SerializedName("unlocked_at") val unlockedAt: Long?,
    val secret: Boolean?,
    val criteria: String?,
)
