package com.m57.hermescontrol.ui.chat.fullbleed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.ToolStatus
import com.m57.hermescontrol.ui.chat.components.DiffViewCard

/**
 * Cursor-style "N files changed" summary closing out an agent turn — the mobile
 * port of the desktop app's `ChangedFilesCard` (apps/desktop/src/components/
 * assistant-ui/thread/changed-files-card.tsx, derived from changed-files.ts).
 *
 * One row per file the turn edited, in first-touched order, with that file's
 * +/- summed across every edit. A row tap expands that file's diff inline
 * (desktop opens the review pane; mobile has no pane, so it reveals the diff).
 * Files with no diff (diff-less `write_file` rehydrates) are skipped — they add
 * nothing to review.
 *
 * Emitted once per agent turn, after its tool rows, exactly like desktop's
 * end-of-turn card.
 */
data class ChangedFile(
    val path: String,
    val name: String,
    val added: Int,
    val removed: Int,
    val inlineDiff: String,
)

/**
 * Fold a turn's file-edit tool messages into one row per file it edited, with
 * the +/- of every edit to that file summed (desktop: deriveChangedFiles).
 */
fun deriveChangedFiles(messages: List<ChatMessage>): List<ChangedFile> {
    val byPath = LinkedHashMap<String, ChangedFile>()

    for (message in messages) {
        if (message.role != MessageRole.TOOL) continue
        val toolName = message.toolName ?: continue
        if (toolName !in setOf("edit_file", "patch", "write_file")) continue
        if (message.toolStatus == ToolStatus.RUNNING) continue

        val view = buildToolView(message)
        val diff = view.inlineDiff ?: continue
        val path = view.diffPath ?: continue
        val stats = view.diffStats?.let { it.added to it.removed } ?: (0 to 0)

        val existing = byPath[path]
        if (existing != null) {
            byPath[path] =
                existing.copy(
                    added = existing.added + stats.first,
                    removed = existing.removed + stats.second,
                )
        } else {
            byPath[path] =
                ChangedFile(
                    path = path,
                    name = path.split("/").lastOrNull()?.takeIf { it.isNotBlank() } ?: path,
                    added = stats.first,
                    removed = stats.second,
                    inlineDiff = diff,
                )
        }
    }

    return byPath.values.toList()
}

@Composable
internal fun ChangedFilesCard(
    files: List<ChangedFile>,
    modifier: Modifier = Modifier,
) {
    if (files.isEmpty()) return

    val statusColors = LocalHermesStatusColors.current
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    var expandedPath by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 3.dp)
                .testTag("changed_files_card"),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text =
                    if (files.size == 1) {
                        stringResource(R.string.files_changed_one)
                    } else {
                        stringResource(R.string.files_changed, files.size)
                    },
                style =
                    MaterialTheme.typography.labelMedium.copy(
                        color = contentColor,
                        fontWeight = FontWeight.SemiBold,
                    ),
            )
            Column(
                modifier = Modifier.padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                files.forEach { file ->
                    val expanded = expandedPath == file.path
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { expandedPath = if (expanded) null else file.path }
                                .padding(vertical = 4.dp, horizontal = 4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                                contentDescription = null,
                                tint = contentColor.copy(alpha = 0.6f),
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = file.name,
                                style =
                                    MaterialTheme.typography.bodySmall.copy(
                                        color = contentColor,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            if (file.added > 0) {
                                Text(
                                    text = "+${file.added}",
                                    style =
                                        MaterialTheme.typography.labelSmall.copy(
                                            color = statusColors.success,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        ),
                                )
                            }
                            if (file.removed > 0) {
                                Text(
                                    text = "-${file.removed}",
                                    style =
                                        MaterialTheme.typography.labelSmall.copy(
                                            color = statusColors.error,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        ),
                                )
                            }
                            Icon(
                                imageVector =
                                    if (expanded) {
                                        Icons.Filled.KeyboardArrowUp
                                    } else {
                                        Icons.Filled.KeyboardArrowDown
                                    },
                                contentDescription = null,
                                tint = contentColor.copy(alpha = 0.5f),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        AnimatedVisibility(visible = expanded) {
                            Box(modifier = Modifier.padding(top = 4.dp)) {
                                DiffViewCard(diffText = file.inlineDiff, filePath = file.path)
                            }
                        }
                    }
                }
            }
        }
    }
}
