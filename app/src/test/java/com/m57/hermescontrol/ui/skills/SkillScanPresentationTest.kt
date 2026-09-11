package com.m57.hermescontrol.ui.skills

import androidx.compose.ui.graphics.Color
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesStatusColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillScanPresentationTest {
    private val dummyColors =
        HermesStatusColors(
            success = Color(0xFF00FF00),
            successContainer = Color(0xFF003300),
            onSuccess = Color.White,
            warning = Color(0xFFFFFF00),
            warningContainer = Color(0xFF333300),
            onWarning = Color.Black,
            error = Color(0xFFFF0000),
            errorContainer = Color(0xFF330000),
            onError = Color.White,
            info = Color(0xFF0000FF),
            infoContainer = Color(0xFF000033),
            onInfo = Color.White,
            neutral = Color.Gray,
        )

    @Test
    fun `resolveVerdictKind maps safe dangerous and fallback`() {
        assertEquals(ScanVerdictKind.SAFE, SkillScanPresentation.resolveVerdictKind("safe"))
        assertEquals(ScanVerdictKind.DANGEROUS, SkillScanPresentation.resolveVerdictKind("dangerous"))
        assertEquals(ScanVerdictKind.WARNING, SkillScanPresentation.resolveVerdictKind("unknown"))
        assertEquals(ScanVerdictKind.WARNING, SkillScanPresentation.resolveVerdictKind(null))
    }

    @Test
    fun `resolveVerdictColor returns corresponding semantic color`() {
        assertEquals(dummyColors.success, SkillScanPresentation.resolveVerdictColor("safe", dummyColors))
        assertEquals(dummyColors.error, SkillScanPresentation.resolveVerdictColor("dangerous", dummyColors))
        assertEquals(dummyColors.warning, SkillScanPresentation.resolveVerdictColor("suspicious", dummyColors))
    }

    @Test
    fun `resolvePolicyKind and labels map allow ask block and fallback`() {
        assertEquals(ScanPolicyKind.ALLOW, SkillScanPresentation.resolvePolicyKind("allow"))
        assertEquals(ScanPolicyKind.ASK, SkillScanPresentation.resolvePolicyKind("ask"))
        assertEquals(ScanPolicyKind.BLOCK, SkillScanPresentation.resolvePolicyKind("block"))
        assertEquals(ScanPolicyKind.OTHER, SkillScanPresentation.resolvePolicyKind("custom"))

        assertEquals(R.string.skills_hub_scan_policy_allow, SkillScanPresentation.resolvePolicyLabelRes("allow"))
        assertEquals(R.string.skills_hub_scan_policy_ask, SkillScanPresentation.resolvePolicyLabelRes("ask"))
        assertEquals(R.string.skills_hub_scan_policy_block, SkillScanPresentation.resolvePolicyLabelRes("block"))
        assertNull(SkillScanPresentation.resolvePolicyLabelRes("other"))
    }

    @Test
    fun `resolveSeverityColor maps critical high medium low and fallback`() {
        assertEquals(dummyColors.error, SkillScanPresentation.resolveSeverityColor("critical", dummyColors))
        assertEquals(dummyColors.error, SkillScanPresentation.resolveSeverityColor("high", dummyColors))
        assertEquals(dummyColors.warning, SkillScanPresentation.resolveSeverityColor("medium", dummyColors))
        assertEquals(dummyColors.neutral, SkillScanPresentation.resolveSeverityColor("low", dummyColors))
        assertEquals(dummyColors.neutral, SkillScanPresentation.resolveSeverityColor("info", dummyColors))
    }
}
