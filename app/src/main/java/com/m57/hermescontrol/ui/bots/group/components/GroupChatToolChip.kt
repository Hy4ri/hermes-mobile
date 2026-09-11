package com.m57.hermescontrol.ui.bots.group.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.bots.group.GroupChatToolCall
import com.m57.hermescontrol.util.BidiUtils

@Composable
fun GroupChatToolChip(
    tool: GroupChatToolCall,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(tool.id) { mutableStateOf(false) }
    val isTerminal =
        tool.name.equals("terminal", ignoreCase = true) ||
            tool.name.equals("execute_code", ignoreCase = true)

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        border =
            BorderStroke(
                1.dp,
                if (tool.isError) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                },
            ),
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val icon =
                    when (tool.name.lowercase()) {
                        "terminal", "execute_code" -> Icons.Filled.Terminal
                        "web_search", "search_files" -> Icons.Filled.Search
                        "read_file", "write_file", "patch" -> Icons.Filled.Description
                        else -> Icons.Filled.Build
                    }
                Icon(
                    imageVector = icon,
                    contentDescription = tool.name,
                    modifier = Modifier.size(13.dp),
                    tint =
                        if (tool.isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                )
                val label =
                    if (!tool.summary.isNullOrBlank()) {
                        "${tool.name}: ${tool.summary}"
                    } else {
                        tool.name
                    }
                val isLabelRtl = remember(label) { BidiUtils.isRtlText(label) }
                Text(
                    text = label,
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            textDirection = if (isLabelRtl) TextDirection.Rtl else TextDirection.Ltr,
                        ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (tool.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (!tool.command.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SelectionContainer {
                                Text(
                                    text = if (isTerminal) "$ ${tool.command}" else tool.command,
                                    style =
                                        MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            textDirection = TextDirection.Ltr,
                                        ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }

                    val outputText =
                        when {
                            tool.isRunning -> stringResource(R.string.group_chat_tool_running)
                            !tool.output.isNullOrBlank() -> tool.output.trim()
                            tool.exitCode == 0 -> stringResource(R.string.group_chat_tool_no_output_success)
                            else -> stringResource(R.string.group_chat_tool_no_output)
                        }
                    val isOutputRtl = remember(outputText) { BidiUtils.isRtlText(outputText) }

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SelectionContainer {
                            Text(
                                text = outputText,
                                style =
                                    MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        textDirection =
                                            if (isOutputRtl) {
                                                TextDirection.Rtl
                                            } else {
                                                TextDirection.Ltr
                                            },
                                    ),
                                color =
                                    if (tool.isError) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                maxLines = 15,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
