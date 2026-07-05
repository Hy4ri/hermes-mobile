package com.m57.hermescontrol.data.model
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AchievementsResponse(
    val achievements: List<Achievement>? = emptyList(),
    @SerialName("unlocked_count") val unlockedCount: Int = 0,
    @SerialName("discovered_count") val discoveredCount: Int = 0,
    @SerialName("secret_count") val secretCount: Int = 0,
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("is_stale") val isStale: Boolean = false,
    @SerialName("generated_at") val generatedAt: Long? = null,
    @SerialName("scan_meta") val scanMeta: ScanMeta? = null,
    val error: String? = null,
)

@Serializable
data class ScanMeta(
    val status: ScanStatus? = null,
)

@Serializable
data class ScanStatus(
    val state: String = "idle",
    @SerialName("started_at") val startedAt: Long? = null,
    @SerialName("finished_at") val finishedAt: Long? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("last_duration_ms") val lastDurationMs: Long? = null,
    @SerialName("run_count") val runCount: Int = 0,
)

@Serializable
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
    @SerialName("next_tier") val nextTier: String?,
    @SerialName("next_threshold") val nextThreshold: Double?,
    @SerialName("progress_pct") val progressPct: Double?,
    @SerialName("unlocked_at") val unlockedAt: Long?,
    val secret: Boolean?,
    val criteria: String?,
)

@Serializable
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
    @SerialName("next_tier") val nextTier: String?,
    @SerialName("next_threshold") val nextThreshold: Double?,
    @SerialName("progress_pct") val progressPct: Double?,
    @SerialName("unlocked_at") val unlockedAt: Long?,
    val secret: Boolean?,
    val criteria: String?,
)
