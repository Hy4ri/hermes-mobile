package com.m57.hermescontrol.ui.starmap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.theme.StarMapDefaultNode
import com.m57.hermescontrol.theme.StarMapEdgeConnected
import com.m57.hermescontrol.theme.StarMapEdgeDefault
import com.m57.hermescontrol.theme.StarMapEdgeDimmed
import com.m57.hermescontrol.theme.StarMapGridDot
import com.m57.hermescontrol.theme.StarMapMemoryNode
import com.m57.hermescontrol.theme.StarMapNodeSelectedRing
import com.m57.hermescontrol.theme.StarMapNodeTextDimmed
import com.m57.hermescontrol.theme.StarMapNodeTextNormal
import com.m57.hermescontrol.theme.StarMapSkillNode
import com.m57.hermescontrol.theme.StarMapSpaceBackground
import kotlin.math.sqrt

@Composable
fun StarMapCanvas(
    nodes: List<StarNodeUi>,
    edges: List<StarEdgeUi>,
    selectedNodeId: String?,
    onNodeSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val textMeasurer = rememberTextMeasurer()
    val selectedNode = remember(selectedNodeId, nodes) { nodes.find { it.id == selectedNodeId } }
    val connectedIds = remember(selectedNode) { selectedNode?.connectedIds?.toSet() ?: emptySet() }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(StarMapSpaceBackground)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.3f, 3.5f)
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                }
                .pointerInput(nodes, scale, offsetX, offsetY) {
                    detectTapGestures { tapOffset ->
                        val centerScreenX = size.width / 2f
                        val centerScreenY = size.height / 2f

                        val canvasX = (tapOffset.x - centerScreenX - offsetX) / scale
                        val canvasY = (tapOffset.y - centerScreenY - offsetY) / scale

                        val hitNode =
                            nodes.find { node ->
                                val dx = node.x - canvasX
                                val dy = node.y - canvasY
                                val dist = sqrt(dx * dx + dy * dy)
                                dist <= (node.radius + 14f)
                            }

                        onNodeSelected(hitNode?.id)
                    }
                },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerScreenX = size.width / 2f
            val centerScreenY = size.height / 2f

            drawStarfieldGrid(centerScreenX + offsetX, centerScreenY + offsetY, scale)

            val nodeMap = nodes.associateBy { it.id }

            edges.forEach { edge ->
                val source = nodeMap[edge.sourceId]
                val target = nodeMap[edge.targetId]

                if (source != null && target != null) {
                    val startX = centerScreenX + offsetX + source.x * scale
                    val startY = centerScreenY + offsetY + source.y * scale
                    val endX = centerScreenX + offsetX + target.x * scale
                    val endY = centerScreenY + offsetY + target.y * scale

                    val isConnected =
                        selectedNodeId != null &&
                            (edge.sourceId == selectedNodeId || edge.targetId == selectedNodeId)
                    val isDimmed = selectedNodeId != null && !isConnected

                    val edgeColor =
                        when {
                            isConnected -> StarMapEdgeConnected
                            isDimmed -> StarMapEdgeDimmed
                            else -> StarMapEdgeDefault
                        }
                    val strokeWidth = if (isConnected) 2.5f * scale else 1f * scale

                    drawLine(
                        color = edgeColor,
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = strokeWidth.coerceAtLeast(1f),
                    )
                }
            }

            nodes.forEach { node ->
                val screenX = centerScreenX + offsetX + node.x * scale
                val screenY = centerScreenY + offsetY + node.y * scale

                val isSelected = node.id == selectedNodeId
                val isConnected = connectedIds.contains(node.id)
                val isDimmed = selectedNodeId != null && !isSelected && !isConnected

                val baseColor =
                    when (node.kind) {
                        "memory" -> StarMapMemoryNode
                        "skill" -> StarMapSkillNode
                        else -> StarMapDefaultNode
                    }

                val nodeAlpha = if (isDimmed) 0.25f else 1.0f
                val drawnRadius = (node.radius * scale).coerceAtLeast(6f)

                if (isSelected || isConnected) {
                    drawCircle(
                        color = baseColor.copy(alpha = if (isSelected) 0.35f else 0.20f),
                        radius = drawnRadius * 2.2f,
                        center = Offset(screenX, screenY),
                    )
                    drawCircle(
                        color = baseColor.copy(alpha = if (isSelected) 0.6f else 0.35f),
                        radius = drawnRadius * 1.5f,
                        center = Offset(screenX, screenY),
                    )
                } else if (!isDimmed) {
                    drawCircle(
                        color = baseColor.copy(alpha = 0.15f),
                        radius = drawnRadius * 1.6f,
                        center = Offset(screenX, screenY),
                    )
                }

                drawCircle(
                    color = baseColor.copy(alpha = nodeAlpha),
                    radius = drawnRadius,
                    center = Offset(screenX, screenY),
                )

                if (isSelected) {
                    drawCircle(
                        color = StarMapNodeSelectedRing,
                        radius = drawnRadius + 4f * scale,
                        center = Offset(screenX, screenY),
                        style = Stroke(width = 2f * scale),
                    )
                }

                if (scale >= 0.7f || isSelected || isConnected) {
                    val labelText = node.label
                    val textLayoutResult =
                        textMeasurer.measure(
                            text = AnnotatedString(labelText),
                            style =
                                TextStyle(
                                    color = if (isDimmed) StarMapNodeTextDimmed else StarMapNodeTextNormal,
                                    fontSize = (11f * scale.coerceAtMost(1.3f)).coerceAtLeast(9f).sp,
                                ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )

                    val textX = screenX - textLayoutResult.size.width / 2f
                    val textY = screenY + drawnRadius + 4f

                    drawText(
                        textLayoutResult = textLayoutResult,
                        topLeft = Offset(textX, textY),
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawStarfieldGrid(
    originX: Float,
    originY: Float,
    scale: Float,
) {
    val gridSpacing = 120f * scale
    if (gridSpacing < 20f) return

    val startX = (originX % gridSpacing)
    val startY = (originY % gridSpacing)

    var x = startX
    while (x < size.width) {
        var y = startY
        while (y < size.height) {
            drawCircle(
                color = StarMapGridDot,
                radius = 1.2f,
                center = Offset(x, y),
            )
            y += gridSpacing
        }
        x += gridSpacing
    }
}
