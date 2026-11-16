package com.m57.hermescontrol.ui.kanban.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R

/** Localized column name for the well-known statuses; other names fall back to a capitalized raw name. */
@Composable
internal fun kanbanColumnLabel(name: String): String {
    val res =
        when (name.lowercase()) {
            "triage" -> R.string.kanban_column_triage
            "todo" -> R.string.kanban_column_todo
            "scheduled" -> R.string.kanban_column_scheduled
            "ready" -> R.string.kanban_column_ready
            "running" -> R.string.kanban_column_running
            "blocked" -> R.string.kanban_column_blocked
            "review" -> R.string.kanban_column_review
            "done" -> R.string.kanban_column_done
            else -> null
        }
    return res?.let { stringResource(it) } ?: name.replaceFirstChar { it.uppercase() }
}

/** Expanded column header title: full localized name plus the task count, one line. */
@Composable
internal fun KanbanColumnTitle(
    name: String,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "${kanbanColumnLabel(name)} ($count)",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Collapsed column label: full localized name, rotated to read along the narrow rail. */
@Composable
internal fun KanbanCollapsedColumnLabel(
    name: String,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = kanbanColumnLabel(name),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.requiredWidth(maxHeight).rotate(90f),
        )
    }
}

/** Board selector tabs with explicit start padding so the first tab is fully visible at rest. */
@Composable
internal fun KanbanBoardTabs(
    boardNames: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    PrimaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        edgePadding = 16.dp,
        modifier = modifier,
    ) {
        boardNames.forEachIndexed { index, name ->
            Tab(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}
