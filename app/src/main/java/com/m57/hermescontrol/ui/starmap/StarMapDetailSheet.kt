package com.m57.hermescontrol.ui.starmap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.LearningNodeDetailResponse
import com.m57.hermescontrol.theme.StarMapDefaultNode
import com.m57.hermescontrol.theme.StarMapMemoryNode
import com.m57.hermescontrol.theme.StarMapSkillNode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StarMapDetailSheet(
    node: StarNodeUi,
    nodeDetail: LearningNodeDetailResponse?,
    isLoadingDetail: Boolean,
    allNodes: List<StarNodeUi>,
    onDismiss: () -> Unit,
    onSelectNode: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            // Header: Kind icon + Category chip + Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (kindColor, kindIcon, kindLabel) =
                        when (node.kind) {
                            "memory" ->
                                Triple(
                                    StarMapMemoryNode,
                                    Icons.Filled.Memory,
                                    stringResource(R.string.starmap_node_kind_memory),
                                )
                            "skill" ->
                                Triple(
                                    StarMapSkillNode,
                                    Icons.Filled.Extension,
                                    stringResource(R.string.starmap_node_kind_skill),
                                )
                            else ->
                                Triple(
                                    StarMapDefaultNode,
                                    Icons.Filled.Memory,
                                    node.kind.replaceFirstChar { it.uppercase() },
                                )
                        }

                    Box(
                        modifier =
                            Modifier
                                .size(32.dp)
                                .background(kindColor.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = kindIcon,
                            contentDescription = null,
                            tint = kindColor,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = kindLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = kindColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }

                    if (node.category.isNotBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = node.category,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }

                    if (node.pinned) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Filled.Pin,
                            contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.chat_action_close_search),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Title
            Text(
                text = nodeDetail?.title ?: node.label,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Usage & Timestamp Info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (node.useCount > 0) {
                    Text(
                        text = "${stringResource(R.string.starmap_use_count)}: ${node.useCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                node.timestamp?.let { ts ->
                    val dateStr =
                        remember(ts) {
                            val fmt = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
                            fmt.format(Date(if (ts > 1e11) ts else ts * 1000))
                        }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = dateStr,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Node Body / Details
            if (isLoadingDetail) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else {
                val bodyContent =
                    nodeDetail?.content
                        ?: nodeDetail?.message
                        ?: "No detailed description available."

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = bodyContent,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            // Connected Subgraph Nodes
            if (node.connectedIds.isNotEmpty()) {
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = stringResource(R.string.starmap_connected_nodes),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    node.connectedIds.forEach { connId ->
                        val connNode = allNodes.find { it.id == connId }
                        val label = connNode?.label ?: connId
                        val chipColor =
                            when (connNode?.kind) {
                                "memory" -> StarMapMemoryNode
                                "skill" -> StarMapSkillNode
                                else -> StarMapDefaultNode
                            }

                        Surface(
                            color = chipColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(16.dp),
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { onSelectNode(connId) },
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = chipColor,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
