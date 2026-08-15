package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class StatusResponse(
    val version: String? = null,
    val gateway_running: Boolean? = null,
    val active_sessions: Int? = null,
    val auth_required: Boolean? = null,
    val gateway_platforms: Map<String, PlatformStatus?>? = null,
    val memory: MemoryPressureStatus? = null,
    val disk: DiskPressureStatus? = null,
)

@Serializable
data class PlatformStatus(
    val state: String? = null,
    val error_code: String? = null,
)

/**
 * Host memory-pressure rollup from `GET /api/status` (backend NS-656).
 * Advisory only — deliberately NOT folded into the endpoint's `overall`
 * verdict. `pressure` is `ok` | `elevated` | `critical` | `unknown`;
 * `unknown` means the sample could not be read (or is stale), NOT "fine".
 */
@Serializable
data class MemoryPressureStatus(
    val pressure: String? = null,
    val gateway_rss_mb: Int? = null,
    val system_total_mb: Int? = null,
    val system_available_mb: Int? = null,
    val swap_used_mb: Int? = null,
    val sampled_at: String? = null,
    val last_boot_unclean: Boolean? = null,
    val last_boot_suspected_oom: Boolean? = null,
    val boot_id: String? = null,
)

/**
 * Host disk-usage rollup from `GET /api/status` (backend NS-656).
 * Same advisory semantics as [MemoryPressureStatus]: coarse MB numbers
 * plus a `pressure` enum, never a liveness verdict.
 */
@Serializable
data class DiskPressureStatus(
    val pressure: String? = null,
    val total_mb: Int? = null,
    val free_mb: Int? = null,
    val used_percent: Double? = null,
)
