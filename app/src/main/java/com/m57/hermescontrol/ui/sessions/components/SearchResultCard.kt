package com.m57.hermescontrol.ui.sessions.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.sessions.cleanSearchSnippet
import com.m57.hermescontrol.ui.sessions.formatPlayedAt

/**
 * Search-result card. The backend search payload has no session title, so this card is
 * honest about it: it shows a "Match" label + the highlighted snippet as the body, plus
 * source / model / played-at metadata chips. It never presents the snippet as a name.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun SearchResultCard(
    session: SessionInfo,
    query: String,
    isSelecting: Boolean,
    isSelected: Boolean,
    isDeleting: Boolean,
    highlightBackground: Color,
    highlightForeground: Color,
    onCardClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val snippet = session.preview?.takeIf { it.isNotBlank() } ?: stringResource(R.string.history_untitled)
    val cleanSnippet = cleanSearchSnippet(snippet)
    val playedAt = formatPlayedAt(session.started_at)
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("session_card_${session.id}")
                .combinedClickable(
                    onClick = onCardClick,
                    onLongClick = { if (!isSelecting) menuExpanded = true },
                ),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        border =
            if (isSelected) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                null
            },
    ) {
        Box {
            Row(
                modifier = Modifier.padding(spacing.sm),
                verticalAlignment = Alignment.Top,
            ) {
                if (isSelecting) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelection() },
                        modifier = Modifier.testTag("session_checkbox_${session.id}"),
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                }

                Column(modifier = Modifier.weight(1f)) {
                    // Header row: "Match" label + source icon
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        val srcIcon = sourceIcon(session.source)
                        if (srcIcon != null && !isSelecting) {
                            Icon(
                                imageVector = srcIcon,
                                contentDescription = sourceLabel(session.source),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Text(
                            text = stringResource(R.string.sessions_search_match_label),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 0.5.sp,
                        )
                        if (!session.id.isNullOrBlank()) {
                            Text(
                                text = "· ${session.id.take(8)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(spacing.xs))

                    // The matched snippet, highlighted — shown as the body, NOT as a title.
                    Text(
                        text = highlightText(cleanSnippet, query, highlightBackground, highlightForeground),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(modifier = Modifier.height(spacing.xs))

                    // Metadata chips row
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        session.source?.let { src ->
                            StatusBadge(
                                text = sourceLabel(src),
                                status = StatusBadgeType.NEUTRAL,
                            )
                        }
                        session.model?.let { mdl ->
                            StatusBadge(
                                text = stringResource(R.string.sessions_search_model_label) + ": " + mdl,
                                status = StatusBadgeType.INFO,
                            )
                        }
                        playedAt?.let { time ->
                            StatusBadge(
                                text = stringResource(R.string.sessions_search_played_at, time),
                                status = StatusBadgeType.NEUTRAL,
                            )
                        }
                    }
                }
            }

            // Per-session action menu (hold the card to open)
            SessionActionMenu(
                expanded = menuExpanded,
                onDismiss = { menuExpanded = false },
                isDeleting = isDeleting,
                onSelect = {
                    menuExpanded = false
                    onSelect()
                },
                onRename = {
                    menuExpanded = false
                    onRename()
                },
                onDelete = {
                    menuExpanded = false
                    onDelete()
                },
            )
        }
    }
}
