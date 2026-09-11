package com.m57.hermescontrol.ui.skills.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.SkillScanFinding
import com.m57.hermescontrol.data.model.SkillScanResponse
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.skills.SkillScanPresentation

@Composable
fun SkillScanSection(
    scanResult: SkillScanResponse?,
    isScanning: Boolean,
    scanError: String?,
    onScan: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onScan,
            enabled = !isScanning,
        ) {
            if (isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.skills_hub_scan))
            }
        }

        when {
            isScanning -> {
                Unit
            }

            scanError != null -> {
                Surface(
                    color = LocalHermesStatusColors.current.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = scanError,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            scanResult != null -> {
                ScanResultCard(result = scanResult)
            }
        }
    }
}

@Composable
fun ScanResultCard(result: SkillScanResponse) {
    val statusColors = LocalHermesStatusColors.current
    val verdictColor = SkillScanPresentation.resolveVerdictColor(result.verdict, statusColors)
    val policyLabel =
        SkillScanPresentation.resolvePolicyLabelRes(result.policy)?.let { stringResource(it) }
            ?: result.policy.orEmpty()
    val policyColor = SkillScanPresentation.resolvePolicyColor(result.policy, statusColors)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── Verdict header ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = null,
                    tint = verdictColor,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.skills_hub_scan_verdict_label, result.verdict.orEmpty()),
                    style = MaterialTheme.typography.titleSmall,
                    color = verdictColor,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    color = policyColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        text = policyLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = policyColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            // ── Trust + finding count ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!result.trustLevel.isNullOrBlank()) {
                    Text(
                        text = result.trustLevel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text =
                        pluralStringResource(
                            R.plurals.skills_hub_scan_findings_count,
                            result.findings.size,
                            result.findings.size,
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Severity tally ──
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("critical", "high", "medium", "low").forEach { sev ->
                    val count = result.severityCounts[sev] ?: 0
                    if (count > 0) {
                        val sevColor = SkillScanPresentation.resolveSeverityColor(sev, statusColors)
                        Surface(
                            color = sevColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(50),
                        ) {
                            Text(
                                text = "$count $sev",
                                style = MaterialTheme.typography.labelSmall,
                                color = sevColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                if (result.findings.isEmpty()) {
                    Text(
                        text = stringResource(R.string.skills_hub_scan_no_findings),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColors.success,
                    )
                }
            }

            // ── Policy reason ──
            if (!result.policyReason.isNullOrBlank()) {
                Text(
                    text = result.policyReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Findings ──
            if (result.findings.isNotEmpty()) {
                HorizontalDivider()
                result.findings.forEach { finding ->
                    ScanFindingRow(finding = finding)
                }
            }
        }
    }
}

@Composable
fun ScanFindingRow(finding: SkillScanFinding) {
    val statusColors = LocalHermesStatusColors.current
    val severityColor = SkillScanPresentation.resolveSeverityColor(finding.severity, statusColors)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                color = severityColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    text = finding.severity,
                    style = MaterialTheme.typography.labelSmall,
                    color = severityColor,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Text(
                text = finding.category,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
            )
            val fileLocation =
                buildString {
                    finding.file?.let { append(it) }
                    finding.line?.let { append(":").append(it) }
                }
            if (fileLocation.isNotEmpty()) {
                Text(
                    text = fileLocation,
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        finding.description?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
