package com.m57.hermescontrol.ui.skills

import androidx.compose.ui.graphics.Color
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesStatusColors

enum class ScanVerdictKind {
    SAFE,
    DANGEROUS,
    WARNING,
}

enum class ScanPolicyKind {
    ALLOW,
    ASK,
    BLOCK,
    OTHER,
}

object SkillScanPresentation {
    fun resolveVerdictKind(verdict: String?): ScanVerdictKind =
        when (verdict) {
            "safe" -> ScanVerdictKind.SAFE
            "dangerous" -> ScanVerdictKind.DANGEROUS
            else -> ScanVerdictKind.WARNING
        }

    fun resolveVerdictColor(
        verdict: String?,
        statusColors: HermesStatusColors,
    ): Color =
        when (resolveVerdictKind(verdict)) {
            ScanVerdictKind.SAFE -> statusColors.success
            ScanVerdictKind.DANGEROUS -> statusColors.error
            ScanVerdictKind.WARNING -> statusColors.warning
        }

    fun resolvePolicyKind(policy: String?): ScanPolicyKind =
        when (policy) {
            "allow" -> ScanPolicyKind.ALLOW
            "ask" -> ScanPolicyKind.ASK
            "block" -> ScanPolicyKind.BLOCK
            else -> ScanPolicyKind.OTHER
        }

    fun resolvePolicyLabelRes(policy: String?): Int? =
        when (policy) {
            "allow" -> R.string.skills_hub_scan_policy_allow
            "ask" -> R.string.skills_hub_scan_policy_ask
            "block" -> R.string.skills_hub_scan_policy_block
            else -> null
        }

    fun resolvePolicyColor(
        policy: String?,
        statusColors: HermesStatusColors,
    ): Color =
        when (policy) {
            "allow" -> statusColors.success
            "ask" -> statusColors.warning
            "block" -> statusColors.error
            else -> statusColors.neutral
        }

    fun resolveSeverityColor(
        severity: String?,
        statusColors: HermesStatusColors,
    ): Color =
        when (severity) {
            "critical", "high" -> statusColors.error
            "medium" -> statusColors.warning
            "low" -> statusColors.neutral
            else -> statusColors.neutral
        }
}
