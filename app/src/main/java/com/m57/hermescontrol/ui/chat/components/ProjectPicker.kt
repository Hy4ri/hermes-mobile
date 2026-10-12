package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ModalBottomSheet
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
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = spacing.lg)) {
                Text(
                    stringResource(R.string.chat_project_picker_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
                )
                ProjectRow(stringResource(R.string.chat_project_default), null, selected == null) {
                    open = false
                    onSelect(null)
                }
                projects.forEach { project ->
                    ProjectRow(project.displayName(), project.color, selected?.id == project.id) {
                        open = false
                        onSelect(project)
                    }
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

/** Read-only project marker once the first message has locked the chat's workspace. */
@Composable
fun ProjectBadge(
    project: ProjectInfo,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = modifier.padding(horizontal = spacing.md, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProjectDot(project.color)
        Spacer(modifier = Modifier.width(spacing.sm))
        Text(
            project.displayName(),
            style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
