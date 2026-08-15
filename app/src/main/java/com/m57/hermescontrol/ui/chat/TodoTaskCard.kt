package com.m57.hermescontrol.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalHermesStatusColors

@Composable
internal fun TodoTaskCard(
    items: List<TodoItem>,
    isRunning: Boolean,
    riskData: ToolOutputRiskData?,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(true) }
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val completed = items.count { it.isCompleted }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 1.dp)
                .testTag("todo_task_card"),
    ) {
        Card(
            onClick = { expanded = !expanded },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().animateContentSize().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        text = stringResource(R.string.todo_task_count, completed, items.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse tasks" else "Expand tasks",
                        tint = contentColor.copy(alpha = 0.7f),
                    )
                }

                if (riskData != null && (riskData.risk == "medium" || riskData.risk == "high" || riskData.redacted)) {
                    SecurityRiskChip(riskData = riskData, contentColor = contentColor)
                }

                if (expanded) {
                    items.forEach { item ->
                        TodoTaskRow(item = item, contentColor = contentColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoTaskRow(
    item: TodoItem,
    contentColor: Color,
) {
    val statusColors = LocalHermesStatusColors.current
    val finished = item.isCompleted || item.isCancelled
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            item.isCompleted ->
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Completed",
                    tint = statusColors.success,
                    modifier = Modifier.size(20.dp),
                )

            item.isInProgress ->
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )

            item.isCancelled ->
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Cancelled",
                    tint = statusColors.warning,
                    modifier = Modifier.size(20.dp),
                )

            else ->
                Surface(
                    modifier = Modifier.size(20.dp),
                    shape = RoundedCornerShape(50),
                    color = Color.Transparent,
                    border = BorderStroke(1.5.dp, contentColor.copy(alpha = 0.45f)),
                ) {}
        }

        Text(
            text = item.content,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            textDecoration = if (finished) TextDecoration.LineThrough else TextDecoration.None,
            modifier = Modifier.alpha(if (finished) 0.55f else 1f),
        )
    }
}
