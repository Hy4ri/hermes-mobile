package com.m57.hermescontrol.data.model
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Skill(
    val name: String,
    val description: String? = null,
    val category: String? = null,
    val enabled: Boolean,
    val content: String? = null,
    val source: String? = null, // built-in, hub, optional
    val pinned: Boolean? = null,
    val linked_files: List<String>? = null,
)

@Serializable
data class HubSkill(
    val name: String,
    val description: String? = null,
    val source: String? = null,
    val category: String? = null,
    val identifier: String? = null,
    @SerialName("trust_level") val trustLevel: String? = null,
    val repo: String? = null,
    val tags: List<String>? = null,
)

@Serializable
data class SkillHubSearchResponse(
    val results: List<HubSkill>,
    @SerialName("source_counts") val sourceCounts: Map<String, Int>? = null,
    @SerialName("timed_out") val timedOut: List<String>? = null,
    val installed: Map<String, SkillHubInstalledEntry>? = null,
)

@Serializable
data class SkillHubInstalledEntry(
    val name: String? = null,
    @SerialName("trust_level") val trustLevel: String? = null,
    @SerialName("scan_verdict") val scanVerdict: String? = null,
)

@Serializable
data class SkillHubInstallRequest(
    val identifier: String,
    val profile: String? = null,
)

@Serializable
data class SkillHubUninstallRequest(
    val name: String,
    val profile: String? = null,
)

@Serializable
data class SkillScanResponse(
    val identifier: String,
    val name: String? = null,
    val source: String? = null,
    @SerialName("trust_level") val trustLevel: String? = null,
    val verdict: String? = null,
    val summary: String? = null,
    val policy: String? = null,
    @SerialName("policy_reason") val policyReason: String? = null,
    val findings: List<SkillScanFinding> = emptyList(),
    @SerialName("severity_counts") val severityCounts: Map<String, Int> = emptyMap(),
)

@Serializable
data class SkillScanFinding(
    val severity: String,
    val category: String,
    val file: String? = null,
    val line: Int? = null,
    val description: String? = null,
)

@Serializable
data class SkillHubSource(
    val id: String,
    val label: String? = null,
    @SerialName("rate_limited") val rateLimited: Boolean? = null,
    val available: Boolean? = null,
    val searchable: Boolean? = null,
)

@Serializable
data class SkillHubSourcesResponse(
    val sources: List<SkillHubSource> = emptyList(),
    @SerialName("index_available") val indexAvailable: Boolean? = null,
    val featured: List<HubSkill> = emptyList(),
    val installed: Map<String, SkillHubInstalledEntry>? = null,
)
