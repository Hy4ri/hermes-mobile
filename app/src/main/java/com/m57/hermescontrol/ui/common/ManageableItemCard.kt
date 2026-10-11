package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.LocalSpacing

/**
 * Trailing switch state for [ManageableItemCard]. [busy] swaps the switch for a spinner (pending
 * restart / daemon reload); [enabled] = false disables it (read-only, auth required, plugin-owned).
 */
data class ManageableToggle(
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
    val busy: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * Slot-based card shared by Skills, Plugins, Toolsets, Channels and MCP lists (issue #1525).
 * Every slot is optional: uninstall/delete belongs in [actions] only when the entity allows it.
 * Callers render the shared [StatusBadge] inside [badges]; do not invent private badge surfaces.
 */
@Composable
fun ManageableItemCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badges: (@Composable RowScope.() -> Unit)? = null,
    toggle: ManageableToggle? = null,
    onClick: (() -> Unit)? = null,
    body: (@Composable ColumnScope.() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(modifier = Modifier.padding(spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (badges != null) {
                        Row(
                            modifier = Modifier.padding(top = spacing.xs),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                            content = badges,
                        )
                    }
                }
                if (toggle != null) {
                    Spacer(Modifier.width(spacing.sm))
                    if (toggle.busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Switch(
                            checked = toggle.checked,
                            onCheckedChange = toggle.onCheckedChange,
                            enabled = toggle.enabled,
                        )
                    }
                }
            }
            if (body != null) {
                Column(
                    modifier = Modifier.padding(top = spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    content = body,
                )
            }
            if (actions != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}
