package com.m57.hermescontrol.ui.bots

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.BotAvatarMeta
import com.m57.hermescontrol.theme.parseHexColor
import com.m57.hermescontrol.ui.common.BotAvatar

private val AVAILABLE_SHAPES = listOf("circle", "square", "rounded", "hexagon")
private val PALETTE_COLORS =
    listOf(
        "#4F46E5", // Indigo
        "#2563EB", // Blue
        "#0D9488", // Teal
        "#16A34A", // Green
        "#D97706", // Amber
        "#EA580C", // Orange
        "#DC2626", // Red
        "#DB2777", // Pink
        "#9333EA", // Purple
        "#4B5563", // Gray
    )

@Composable
fun CreateBotDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, title: String, description: String, shape: String, color: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedShape by remember { mutableStateOf("circle") }
    var selectedColor by remember { mutableStateOf(PALETTE_COLORS[0]) }

    val isValid = name.isNotBlank() && name.trim().matches(Regex("^[a-zA-Z0-9_-]+$"))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.bots_create_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
            ) {
                // Live Avatar Preview Header
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BotAvatar(
                        name = name.ifBlank { "bot" },
                        avatar =
                            BotAvatarMeta(
                                shape = selectedShape,
                                color = selectedColor,
                            ),
                        size = 64.dp,
                        showPresence = false,
                    )
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' } },
                    label = { Text(stringResource(R.string.bots_create_name_label)) },
                    placeholder = { Text(stringResource(R.string.bots_create_name_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.bots_create_title_label)) },
                    placeholder = { Text(stringResource(R.string.bots_create_title_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.bots_create_desc_label)) },
                    placeholder = { Text(stringResource(R.string.bots_create_desc_placeholder)) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.bots_create_avatar_shape),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AVAILABLE_SHAPES.forEach { shape ->
                        FilterChip(
                            selected = selectedShape == shape,
                            onClick = { selectedShape = shape },
                            label = { Text(shape.replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.bots_create_avatar_color),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PALETTE_COLORS.take(5).forEach { hex ->
                        val color = parseHexColor(hex, Color.Unspecified)
                        Box(
                            modifier =
                                Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .clickable { selectedColor = hex }
                                    .then(
                                        if (selectedColor == hex) {
                                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        } else {
                                            Modifier
                                        },
                                    ),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PALETTE_COLORS.drop(5).forEach { hex ->
                        val color = parseHexColor(hex, Color.Unspecified)
                        Box(
                            modifier =
                                Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .clickable { selectedColor = hex }
                                    .then(
                                        if (selectedColor == hex) {
                                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        } else {
                                            Modifier
                                        },
                                    ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isValid) {
                        onCreate(name.trim(), title.trim(), description.trim(), selectedShape, selectedColor)
                    }
                },
                enabled = isValid,
            ) {
                Text(stringResource(R.string.bots_create_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
