package com.m57.hermescontrol.ui.sessions.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType

fun sourceIcon(source: String?): ImageVector? =
    when (source?.lowercase()) {
        "telegram", "tg" -> Icons.AutoMirrored.Filled.Send
        "web", "dashboard" -> Icons.Filled.Language
        "api", "rest" -> Icons.Filled.Code
        "cli", "terminal" -> Icons.Filled.Terminal
        else -> null
    }

fun sourceLabel(source: String?): String =
    when (source?.lowercase()) {
        "telegram", "tg" -> "Telegram"
        "web", "dashboard" -> "Web"
        "api", "rest" -> "API"
        "cli", "terminal" -> "CLI"
        else -> source ?: "Unknown"
    }

fun highlightText(
    text: String,
    query: String,
    highlightBackground: Color,
    highlightForeground: Color,
): AnnotatedString =
    buildAnnotatedString {
        if (query.isBlank()) {
            append(text)
            return@buildAnnotatedString
        }
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        var currentIndex = 0
        while (currentIndex < text.length) {
            val matchIndex = lowerText.indexOf(lowerQuery, currentIndex)
            if (matchIndex == -1) {
                append(text.substring(currentIndex))
                break
            }
            if (matchIndex > currentIndex) {
                append(text.substring(currentIndex, matchIndex))
            }
            withStyle(
                SpanStyle(
                    background = highlightBackground,
                    color = highlightForeground,
                    fontWeight = FontWeight.Bold,
                ),
            ) {
                append(text.substring(matchIndex, matchIndex + query.length))
            }
            currentIndex = matchIndex + query.length
        }
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionCard(
    session: SessionInfo,
    displayTitle: String,
    branchStem: String?,
    isFork: Boolean = false,
    forkDepth: Int = 0,
    query: String,
    isSelecting: Boolean,
    isSelected: Boolean,
    isDeleting: Boolean,
    isPinned: Boolean,
    isHidden: Boolean = false,
    highlightBackground: Color,
    highlightForeground: Color,
    onCardClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleHide: () -> Unit,
    onDelete: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val statusColors = LocalHermesStatusColors.current
    val isActive = session.status?.lowercase() == "active" || session.status?.lowercase() == "streaming"
    val srcIcon = sourceIcon(session.source)
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
            if (isActive && !isSelecting) {
                BorderStroke(2.dp, statusColors.success)
            } else if (isSelected) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                null
            },
    ) {
        Box {
            Row(
                modifier = Modifier.padding(spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSelecting) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelection() },
                        modifier = Modifier.testTag("session_checkbox_${session.id}"),
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                }

                if (isFork && !isSelecting) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.CallSplit,
                        contentDescription = stringResource(R.string.sessions_fork_indicator),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    if (forkDepth > 0) {
                        Spacer(modifier = Modifier.width(spacing.xs))
                        Text(
                            text = forkDepth.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(modifier = Modifier.width(spacing.sm))
                }

                if (srcIcon != null && !isSelecting) {
                    Icon(
                        imageVector = srcIcon,
                        contentDescription = sourceLabel(session.source),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text =
                                if (query.isNotBlank()) {
                                    highlightText(displayTitle, query, highlightBackground, highlightForeground)
                                } else {
                                    AnnotatedString(displayTitle)
                                },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (isPinned) {
                            Spacer(modifier = Modifier.width(spacing.xs))
                            Icon(
                                imageVector = Icons.Filled.PushPin,
                                contentDescription = stringResource(R.string.sessions_pinned_indicator),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(spacing.xs))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.history_message_count, session.message_count ?: 0),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (session.hidden == true) {
                            Spacer(modifier = Modifier.width(spacing.sm))
                            StatusBadge(
                                text = stringResource(R.string.sessions_hidden_badge),
                                status = StatusBadgeType.NEUTRAL,
                            )
                        }
                        if (!session.status.isNullOrBlank()) {
                            Spacer(modifier = Modifier.width(spacing.sm))
                            StatusBadge(
                                text = session.status,
                                status = if (isActive) StatusBadgeType.SUCCESS else StatusBadgeType.NEUTRAL,
                            )
                        }
                    }
                }
            }

            SessionActionMenu(
                expanded = menuExpanded,
                onDismiss = { menuExpanded = false },
                isDeleting = isDeleting,
                isPinned = isPinned,
                onTogglePin = {
                    menuExpanded = false
                    onTogglePin()
                },
                isHidden = isHidden,
                onToggleHide = {
                    menuExpanded = false
                    onToggleHide()
                },
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
