package com.m57.hermescontrol.ui.sessions.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionLiveStatus
import com.m57.hermescontrol.data.model.SessionTreeItem
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.theme.parseProjectColor
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.sessions.SessionProject
import com.m57.hermescontrol.ui.sessions.activityEpochSeconds
import com.m57.hermescontrol.ui.sessions.sessionAge
import java.time.Instant
import java.time.ZoneId

@Composable
fun BranchRow(
    item: SessionTreeItem,
    modifier: Modifier = Modifier,
    card: @Composable (SessionTreeItem) -> Unit,
) {
    val indent = 12.dp * minOf(item.depth, 2)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.branchStem != null) {
            Text(
                text = item.branchStem,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = indent, end = 4.dp),
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            card(item)
        }
    }
}

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

private val ProjectDotSize = 8.dp
private val MetaIconSize = 14.dp
private val WhitespaceRun = Regex("\\s+")

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
    liveStatus: SessionLiveStatus? = null,
    project: SessionProject? = null,
    nowMillis: Long = System.currentTimeMillis(),
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
    val palette = sessionCardPalette()
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
        colors = CardDefaults.cardColors(containerColor = palette.card),
        border =
            if (isSelected) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else if (!isSelecting && liveStatus == SessionLiveStatus.WORKING) {
                BorderStroke(2.dp, statusColors.success)
            } else if (!isSelecting && liveStatus == SessionLiveStatus.WAITING) {
                BorderStroke(2.dp, statusColors.warning)
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

                Column(modifier = Modifier.weight(1f)) {
                    SessionCardHeader(
                        session = session,
                        project = project,
                        isFork = isFork && !isSelecting,
                        forkDepth = forkDepth,
                        isPinned = isPinned,
                        nowMillis = nowMillis,
                        palette = palette,
                    )

                    Spacer(modifier = Modifier.height(spacing.xs))

                    Text(
                        text =
                            if (query.isNotBlank()) {
                                highlightText(displayTitle, query, highlightBackground, highlightForeground)
                            } else {
                                AnnotatedString(displayTitle)
                            },
                        style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                        fontWeight = FontWeight.SemiBold,
                        color = palette.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    val preview =
                        session.preview
                            ?.replace(WhitespaceRun, " ")
                            ?.trim()
                            .orEmpty()
                    if (preview.isNotEmpty() && preview != displayTitle) {
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                            color = palette.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("session_preview_${session.id}"),
                        )
                    }

                    Spacer(modifier = Modifier.height(spacing.sm))

                    SessionCardFooter(
                        session = session,
                        showSource = !isSelecting,
                        liveStatus = liveStatus,
                        palette = palette,
                    )
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

/** Project (with its color dot), fork and pin markers, and the activity age on the right. */
@Composable
private fun SessionCardHeader(
    session: SessionInfo,
    project: SessionProject?,
    isFork: Boolean,
    forkDepth: Int,
    isPinned: Boolean,
    nowMillis: Long,
    palette: SessionCardPalette,
) {
    val spacing = LocalSpacing.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            val dotColor = remember(project?.color) { parseProjectColor(project?.color) }
            if (dotColor != null) {
                Box(
                    modifier =
                        Modifier
                            .size(ProjectDotSize)
                            .clip(CircleShape)
                            .background(dotColor),
                )
                Spacer(modifier = Modifier.width(spacing.sm))
            }
            Text(
                text = project?.label ?: stringResource(R.string.sessions_project_home),
                style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Content),
                color = palette.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).testTag("session_project_${session.id}"),
            )
            if (isFork) {
                Spacer(modifier = Modifier.width(spacing.sm))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.CallSplit,
                    contentDescription = stringResource(R.string.sessions_fork_indicator),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MetaIconSize),
                )
                if (forkDepth > 0) {
                    Spacer(modifier = Modifier.width(spacing.xs))
                    Text(
                        text = forkDepth.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (isPinned) {
                Spacer(modifier = Modifier.width(spacing.sm))
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = stringResource(R.string.sessions_pinned_indicator),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MetaIconSize),
                )
            }
        }
        Spacer(modifier = Modifier.width(spacing.md))
        val age = sessionAge(session.activityEpochSeconds(), Instant.ofEpochMilli(nowMillis), ZoneId.systemDefault())
        if (age != null) {
            val description = sessionAgeDescription(age)
            Text(
                text = sessionAgeLabel(age),
                style = MaterialTheme.typography.labelMedium,
                color = palette.secondary,
                maxLines = 1,
                modifier =
                    Modifier
                        .testTag("session_age_${session.id}")
                        .semantics { contentDescription = description },
            )
        }
    }
}

/**
 * Origin, model and message count, then the hidden badge and live status. The badges wrap to
 * a second line when they don't fit (large font scales), so the model never collapses to "…".
 */
@Composable
private fun SessionCardFooter(
    session: SessionInfo,
    showSource: Boolean,
    liveStatus: SessionLiveStatus?,
    palette: SessionCardPalette,
) {
    val spacing = LocalSpacing.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val srcIcon = sourceIcon(session.source)
            if (srcIcon != null && showSource) {
                Icon(
                    imageVector = srcIcon,
                    contentDescription = sourceLabel(session.source),
                    tint = palette.secondary,
                    modifier = Modifier.size(MetaIconSize),
                )
                Spacer(modifier = Modifier.width(spacing.sm))
            }
            val messageCount = session.message_count ?: 0
            val model = session.model?.trim().orEmpty()
            if (model.isNotEmpty()) {
                Text(
                    text = model,
                    style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Content),
                    color = palette.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = " · ",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.secondary,
                )
            }
            Text(
                text = pluralStringResource(R.plurals.sessions_card_message_count, messageCount, messageCount),
                style = MaterialTheme.typography.labelMedium,
                color = palette.secondary,
                maxLines = 1,
            )
        }
        if (session.hidden == true) {
            StatusBadge(
                text = stringResource(R.string.sessions_hidden_badge),
                status = StatusBadgeType.NEUTRAL,
            )
        }
        if (liveStatus != null) {
            SessionLiveStatusIndicator(
                liveStatus = liveStatus,
                sessionId = session.id,
            )
        }
    }
}
