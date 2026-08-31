package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.chat.SubagentIndicator
import com.m57.hermescontrol.ui.chat.TodoItem

/**
 * Inspection sheet displaying active & completed subagent tasks and agent todos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubagentInspectionSheet(
    indicators: List<SubagentIndicator> = emptyList(),
    todos: List<TodoItem> = emptyList(),
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val todoTree = remember(todos) { buildTodoTree(todos) }
    var collapsedParentIds by remember { mutableStateOf(setOf<String>()) }
    val displayRows = remember(todoTree, collapsedParentIds) { flattenTodoTree(todoTree, collapsedParentIds) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier.testTag("subagent_inspection_sheet"),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.subagent_task_plan_inspection),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (indicators.isEmpty() && todos.isEmpty()) {
                Text(
                    text = stringResource(R.string.subagent_no_active),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (displayRows.isNotEmpty()) {
                        item(key = "todos_header") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.subagent_agent_plan),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        items(
                            items = displayRows,
                            key = { row -> "todo-${row.todo.id}_${row.depth}" },
                        ) { row ->
                            TodoInspectionCard(
                                row = row,
                                onToggleExpand =
                                    if (row.hasChildren) {
                                        {
                                            collapsedParentIds =
                                                if (row.todo.id in collapsedParentIds) {
                                                    collapsedParentIds - row.todo.id
                                                } else {
                                                    collapsedParentIds + row.todo.id
                                                }
                                        }
                                    } else {
                                        null
                                    },
                            )
                        }
                    }

                    if (indicators.isNotEmpty()) {
                        if (todos.isNotEmpty()) {
                            item(key = "subagents_header") {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Groups,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "SUBAGENTS",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        itemsIndexed(
                            items = indicators,
                            key = { index, indicator ->
                                "subagent-${indicator.subagentId ?: indicator.goal ?: "${indicator.type}_$index"}"
                            },
                        ) { _, indicator ->
                            InspectionItemCard(indicator = indicator)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Node in the hierarchical todo tree.
 */
data class TodoNode(
    val item: TodoItem,
    val children: List<TodoNode> = emptyList(),
    val depth: Int = 0,
)

/**
 * Flattened row representation ready for lazy column rendering with indentation and collapse state.
 */
data class TodoDisplayRow(
    val todo: TodoItem,
    val depth: Int,
    val hasChildren: Boolean,
    val isExpanded: Boolean,
    val completedSubtasksCount: Int,
    val totalSubtasksCount: Int,
)

/**
 * Constructs a hierarchical tree from a flat list of [TodoItem]s using the [TodoItem.parent] property.
 */
fun buildTodoTree(todos: List<TodoItem>): List<TodoNode> {
    val itemsById = todos.associateBy { it.id }
    val childrenByParent = mutableMapOf<String, MutableList<TodoItem>>()
    val rootItems = mutableListOf<TodoItem>()

    for (item in todos) {
        val p = item.parent
        if (p != null && itemsById.containsKey(p) && p != item.id) {
            childrenByParent.getOrPut(p) { mutableListOf() }.add(item)
        } else {
            rootItems.add(item)
        }
    }

    fun buildNode(
        item: TodoItem,
        depth: Int,
    ): TodoNode {
        val children = childrenByParent[item.id]?.map { buildNode(it, depth + 1) } ?: emptyList()
        return TodoNode(item = item, children = children, depth = depth)
    }

    return rootItems.map { buildNode(it, 0) }
}

fun getAllDescendantTodos(node: TodoNode): List<TodoItem> {
    val descendants = mutableListOf<TodoItem>()
    for (child in node.children) {
        descendants.add(child.item)
        descendants.addAll(getAllDescendantTodos(child))
    }
    return descendants
}

fun flattenTodoTree(
    nodes: List<TodoNode>,
    collapsedParentIds: Set<String>,
): List<TodoDisplayRow> {
    val result = mutableListOf<TodoDisplayRow>()

    fun traverse(node: TodoNode) {
        val isExpanded = node.item.id !in collapsedParentIds
        val descendants = getAllDescendantTodos(node)
        val completedCount = descendants.count { it.isCompleted }
        val totalCount = descendants.size

        result.add(
            TodoDisplayRow(
                todo = node.item,
                depth = node.depth,
                hasChildren = node.children.isNotEmpty(),
                isExpanded = isExpanded,
                completedSubtasksCount = completedCount,
                totalSubtasksCount = totalCount,
            ),
        )
        if (node.children.isNotEmpty() && isExpanded) {
            for (child in node.children) {
                traverse(child)
            }
        }
    }

    for (root in nodes) {
        traverse(root)
    }
    return result
}

@Composable
private fun TodoInspectionCard(
    row: TodoDisplayRow,
    onToggleExpand: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val todo = row.todo
    val depth = row.depth
    val startPadding = (depth * 20).coerceAtMost(60).dp

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = startPadding)
                .then(
                    if (onToggleExpand != null) {
                        Modifier.clickable(onClick = onToggleExpand)
                    } else {
                        Modifier
                    },
                ),
        shape = RoundedCornerShape(12.dp),
        color =
            if (depth > 0) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val statusColors = LocalHermesStatusColors.current
            val (statusIcon, statusTint) =
                when {
                    todo.isCompleted -> {
                        Icons.Filled.CheckCircle to statusColors.success
                    }

                    todo.isInProgress -> {
                        Icons.Filled.Autorenew to MaterialTheme.colorScheme.primary
                    }

                    todo.isCancelled -> {
                        Icons.Filled.Cancel to statusColors.warning
                    }

                    else -> {
                        Icons.Filled.RadioButtonUnchecked to
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    }
                }

            if (depth > 0) {
                Text(
                    text = "↳",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(end = 6.dp),
                )
            }

            Icon(
                imageVector = statusIcon,
                contentDescription = null,
                tint = statusTint,
                modifier = Modifier.size(if (depth > 0) 16.dp else 18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = todo.content,
                style =
                    if (depth > 0) {
                        MaterialTheme.typography.bodySmall.copy(
                            textDecoration = if (todo.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                        )
                    } else {
                        MaterialTheme.typography.bodyMedium.copy(
                            textDecoration = if (todo.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                        )
                    },
                fontWeight = if (todo.isInProgress) FontWeight.Bold else FontWeight.Normal,
                color =
                    if (todo.isCompleted) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                modifier = Modifier.weight(1f),
            )

            if (row.hasChildren) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        text = "${row.completedSubtasksCount}/${row.totalSubtasksCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (row.isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (row.isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun InspectionItemCard(indicator: SubagentIndicator) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val statusColors = LocalHermesStatusColors.current
                val (statusIcon, statusTint) =
                    when {
                        indicator.isComplete -> {
                            Icons.Filled.CheckCircle to statusColors.success
                        }

                        indicator.isFailed -> {
                            Icons.Filled.Cancel to statusColors.error
                        }

                        else -> {
                            Icons.Filled.Autorenew to MaterialTheme.colorScheme.primary
                        }
                    }
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = statusTint,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))

                val taskIndexStr = indicator.taskIndex?.let { "#$it " } ?: ""
                Text(
                    text = "$taskIndexStr${indicator.goal ?: "Subagent Task"}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }

            if (!indicator.summary.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.subagent_summary, indicator.summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (indicator.logs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.subagent_live_transcript),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 9.sp,
                    )
                    indicator.logs.forEach { logLine ->
                        Text(
                            text = "› ${logLine.text}",
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                ),
                            color =
                                if (logLine.isError) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
            }
        }
    }
}
