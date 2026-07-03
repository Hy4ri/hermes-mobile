package com.m57.hermescontrol.data.model

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
