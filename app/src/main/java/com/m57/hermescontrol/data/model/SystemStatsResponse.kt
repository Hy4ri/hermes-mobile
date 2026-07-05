package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class SystemStatsResponse(
    val os: String? = null,
    val os_release: String? = null,
    val os_version: String? = null,
    val platform: String? = null,
    val arch: String? = null,
    val hostname: String? = null,
    val python_version: String? = null,
    val python_impl: String? = null,
    val hermes_version: String? = null,
    val cpu_count: Int? = null,
    val cpu_percent: Double? = null,
    val psutil: Boolean? = null,
    val load_avg: List<Double>? = null,
    val uptime_seconds: Double? = null,
    val memory: MemoryStats? = null,
    val disk: DiskStats? = null,
    val process: ProcessStats? = null,
)

@Serializable
data class MemoryStats(
    val total: Long? = null,
    val available: Long? = null,
    val used: Long? = null,
    val percent: Double? = null,
)

@Serializable
data class DiskStats(
    val total: Long? = null,
    val used: Long? = null,
    val free: Long? = null,
    val percent: Double? = null,
)

@Serializable
data class ProcessStats(
    val pid: Int? = null,
    val rss: Long? = null,
    val create_time: Double? = null,
    val num_threads: Int? = null,
)
