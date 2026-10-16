package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.applock.ModalBottomSheet
import com.m57.hermescontrol.data.model.ProjectInfo
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.theme.parseProjectColor

private fun ProjectInfo.displayName(): String = name.trim().ifEmpty { slug ?: id }

@Composable
private fun ProjectDot(color: String?) {
    val parsed = remember(color) { parseProjectColor(color) }
    if (parsed != null) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(parsed))
    }
}

/**
 * Workspace chip for a chat that has no messages yet: opens a project picker. Renders nothing when the
 * gateway offers no startable project, so users without projects see no extra control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectPickerChip(
    projects: List<ProjectInfo>,
    selected: ProjectInfo?,
    onSelect: (ProjectInfo?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (projects.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val spacing = LocalSpacing.current
    AssistChip(
        onClick = { open = true },
        leadingIcon = selected?.let { { ProjectDot(it.color) } },
        label = {
            Text(
                selected?.displayName() ?: stringResource(R.string.chat_project_default),
                style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Content),
            )
        },
        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        modifier = modifier.padding(horizontal = spacing.md).testTag("chat_project_chip"),
    )
    if (open) {
        ProjectPickerSheet(
            title = stringResource(R.string.chat_project_picker_title),
            projects = projects,
            selected = selected,
            allowDefault = true,
            onSelect = onSelect,
            onDismiss = { open = false },
        )
    }
}

/** Bottom sheet listing [projects]; [allowDefault] adds the "no project" row (invalid for a move: it needs a folder). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectPickerSheet(
    title: String,
    projects: List<ProjectInfo>,
    selected: ProjectInfo?,
    allowDefault: Boolean,
    onSelect: (ProjectInfo?) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = spacing.lg)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
            )
            if (allowDefault) {
                ProjectRow(stringResource(R.string.chat_project_default), null, selected == null) {
                    onDismiss()
                    onSelect(null)
                }
            }
            projects.forEach { project ->
                ProjectRow(project.displayName(), project.color, selected?.id == project.id) {
                    onDismiss()
                    onSelect(project)
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(
    label: String,
    color: String?,
    checked: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(label, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
        },
        leadingContent = { ProjectDot(color) },
        trailingContent = { if (checked) Icon(Icons.Filled.Check, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * Project marker once the first message has locked the chat's workspace. [project] null is the default workspace.
 * With [onLongClick] the badge is the entry point for moving the chat to another project (long-press, also
 * exposed to accessibility services under [R.string.chat_project_move_action_label]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProjectBadge(
    project: ProjectInfo?,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    val gesture =
        if (onLongClick != null) {
            Modifier.combinedClickable(
                onClick = {},
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.chat_project_move_action_label),
            )
        } else {
            Modifier
        }
    Row(
        modifier = modifier.then(gesture).padding(horizontal = spacing.md, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProjectDot(project?.color)
        if (project?.color != null) Spacer(modifier = Modifier.width(spacing.sm))
        Text(
            project?.displayName() ?: stringResource(R.string.chat_project_default),
            style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
