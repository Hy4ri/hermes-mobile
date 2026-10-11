package com.m57.hermescontrol.ui.skills.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.Skill
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.common.ManageableItemCard
import com.m57.hermescontrol.ui.common.ManageableToggle

@Composable
fun SkillCard(
    skill: Skill,
    onToggle: () -> Unit,
    onAction: () -> Unit,
    onUninstall: (() -> Unit)?,
    isUninstalling: Boolean,
    onClick: () -> Unit,
) {
    ManageableItemCard(
        title = skill.name,
        badges =
            skill.source?.let { source ->
                { SourceBadge(source = source) }
            },
        toggle =
            ManageableToggle(
                checked = skill.enabled,
                onCheckedChange = { onToggle() },
            ),
        onClick = onClick,
        body = {
            skill.category?.let { cat ->
                Text(
                    text = cat,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            skill.description?.let { desc ->
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            IconButton(onClick = onAction) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.content_desc_edit_skill),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onUninstall != null) {
                val statusColors = LocalHermesStatusColors.current
                IconButton(
                    onClick = onUninstall,
                    enabled = !isUninstalling,
                ) {
                    if (isUninstalling) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.CloudOff,
                            contentDescription = stringResource(R.string.content_desc_uninstall_skill),
                            tint = statusColors.error,
                        )
                    }
                }
            }
        },
    )
}
