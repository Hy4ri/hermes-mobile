package com.m57.hermescontrol.ui.skills.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.applock.Dialog
import com.m57.hermescontrol.data.model.Skill
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.common.DetailRowTone
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.toDetailRows

@Composable
fun SkillDetailDialog(
    skill: Skill,
    isLockSaving: Boolean,
    onToggleLock: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        SkillDetailCard(
            skill = skill,
            isLockSaving = isLockSaving,
            onToggleLock = onToggleLock,
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun SkillDetailCard(
    skill: Skill,
    isLockSaving: Boolean,
    onToggleLock: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColors = LocalHermesStatusColors.current
    val isLocked = skill.locked == true

    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    ) {
        HermesScaffold(
            title = { Text(skill.name, style = MaterialTheme.typography.titleLarge) },
            navigationIcon = NavIcon.Back(onDismiss),
            isRefreshing = false,
        ) {
            val visibleRows = skill.toDetailRows().filter { !it.value.isNullOrBlank() }
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                visibleRows.forEachIndexed { index, (label, value, tone) ->
                    if (index > 0) {
                        HorizontalDivider()
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = value ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color =
                                when (tone) {
                                    DetailRowTone.SUCCESS -> statusColors.success
                                    DetailRowTone.WARNING -> statusColors.warning
                                    DetailRowTone.ERROR -> statusColors.error
                                    DetailRowTone.INFO -> statusColors.info
                                    DetailRowTone.NEUTRAL -> statusColors.neutral
                                    DetailRowTone.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }

                HorizontalDivider()

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                enabled = !isLockSaving,
                                role = Role.Switch,
                                onClick = { onToggleLock(!isLocked) },
                            ).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f).padding(end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.skills_lock_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.skills_lock_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (isLockSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Switch(
                            checked = isLocked,
                            onCheckedChange = null,
                            enabled = !isLockSaving,
                        )
                    }
                }
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 360)
@Composable
private fun SkillDetailDialogSavingPreview() {
    com.m57.hermescontrol.theme.HermesControlTheme {
        androidx.compose.material3.Surface {
            SkillDetailCard(
                skill =
                    Skill(
                        name = "test-skill",
                        description = "Test skill description",
                        category = "coding",
                        enabled = true,
                        locked = false,
                    ),
                isLockSaving = true,
                onToggleLock = {},
                onDismiss = {},
            )
        }
    }
}
