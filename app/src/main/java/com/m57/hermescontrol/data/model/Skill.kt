package com.m57.hermescontrol.data.model

import com.google.gson.annotations.SerializedName

data class Skill(
    val name: String,
    val description: String?,
    val category: String?,
    val enabled: Boolean,
    val content: String? = null,
    val source: String? = null, // built-in, hub, optional
    val pinned: Boolean? = null,
    val linked_files: List<String>? = null,
)

data class HubSkill(
    val name: String,
    val description: String?,
    val source: String?,
    val category: String? = null,
    val identifier: String? = null,
    @SerializedName("trust_level") val trustLevel: String? = null,
    val repo: String? = null,
    val tags: List<String>? = null,
)

data class SkillHubSearchResponse(
    val results: List<HubSkill>,
    @SerializedName("source_counts") val sourceCounts: Map<String, Int>? = null,
    @SerializedName("timed_out") val timedOut: List<String>? = null,
    val installed: Map<String, SkillHubInstalledEntry>? = null,
)

data class SkillHubInstalledEntry(
    val name: String? = null,
    @SerializedName("trust_level") val trustLevel: String? = null,
    @SerializedName("scan_verdict") val scanVerdict: String? = null,
)

data class SkillHubInstallRequest(
    val identifier: String,
    val profile: String? = null,
)

data class SkillHubUninstallRequest(
    val name: String,
    val profile: String? = null,
)

data class SkillScanResponse(
    val identifier: String,
    val passed: Boolean? = null,
    val issues: List<String>? = null,
    val warnings: List<String>? = null,
)
