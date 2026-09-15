package com.m57.hermescontrol.ui.bots.group.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.m57.hermescontrol.ui.bots.group.DEFAULT_MAX_BOT_MESSAGES
import com.m57.hermescontrol.ui.bots.group.DEFAULT_MAX_CONTINUATION_PASSES
import com.m57.hermescontrol.ui.bots.group.MAX_ALLOWED_BOT_MESSAGES
import com.m57.hermescontrol.ui.bots.group.MAX_ALLOWED_CONTINUATION_PASSES
import com.m57.hermescontrol.ui.bots.group.WARN_MAX_BOT_MESSAGES
import com.m57.hermescontrol.ui.bots.group.WARN_MAX_CONTINUATION_PASSES
import kotlin.math.roundToInt

@Composable
fun GroupChatSettingsDialog(
    currentMaxMessages: Int,
    currentMaxPasses: Int,
    currentSystemPrompt: String?,
    onDismiss: () -> Unit,
    onSave: (Int, Int, String?) -> Unit,
) {
    var maxMessages by remember(currentMaxMessages) { mutableStateOf(currentMaxMessages.toFloat()) }
    var maxPasses by remember(currentMaxPasses) { mutableStateOf(currentMaxPasses.toFloat()) }
    val systemPromptTextFieldState = rememberTextFieldState(currentSystemPrompt.orEmpty())
    var showHighLimitsConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(currentSystemPrompt) {
        val loaded = currentSystemPrompt.orEmpty()
        if (systemPromptTextFieldState.text.toString() != loaded) {
            systemPromptTextFieldState.edit {
                replace(0, length, loaded)
            }
        }
    }

    val isHighLimit =
        maxMessages.roundToInt() > WARN_MAX_BOT_MESSAGES ||
            maxPasses.roundToInt() > WARN_MAX_CONTINUATION_PASSES

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.group_chat_settings_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Max Bot Messages Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.group_chat_settings_max_messages),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${maxMessages.roundToInt()}",
                            style = MaterialTheme.typography.labelLarge,
                            color =
                                if (maxMessages.roundToInt() > WARN_MAX_BOT_MESSAGES) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text =
                            stringResource(
                                R.string.group_chat_settings_max_messages_desc,
                                maxMessages.roundToInt(),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = maxMessages,
                        onValueChange = { maxMessages = it },
                        valueRange = 1f..MAX_ALLOWED_BOT_MESSAGES.toFloat(),
                        steps = MAX_ALLOWED_BOT_MESSAGES - 2,
                        modifier = Modifier.testTag("max_messages_slider"),
                    )
                }

                // Max Continuation Handoffs Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.group_chat_settings_max_handoffs),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${maxPasses.roundToInt()}",
                            style = MaterialTheme.typography.labelLarge,
                            color =
                                if (maxPasses.roundToInt() > WARN_MAX_CONTINUATION_PASSES) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text =
                            stringResource(
                                R.string.group_chat_settings_max_handoffs_desc,
                                maxPasses.roundToInt(),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = maxPasses,
                        onValueChange = { maxPasses = it },
                        valueRange = 0f..MAX_ALLOWED_CONTINUATION_PASSES.toFloat(),
                        steps = MAX_ALLOWED_CONTINUATION_PASSES - 1,
                        modifier = Modifier.testTag("max_handoffs_slider"),
                    )
                }

                // Live In-Dialog Warning Banner (Option C)
                AnimatedVisibility(
                    visible = isHighLimit,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("high_limits_warning_banner"),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text =
                                    stringResource(
                                        R.string.group_chat_settings_high_limits_warning_desc,
                                        maxMessages.roundToInt(),
                                        maxPasses.roundToInt(),
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                // Room Instructions / System Prompt
                Column {
                    Text(
                        text = stringResource(R.string.group_chat_settings_system_prompt),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.group_chat_settings_system_prompt_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        state = systemPromptTextFieldState,
                        placeholder = {
                            Text(
                                text = stringResource(R.string.group_chat_settings_system_prompt_hint),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 80.dp, max = 160.dp)
                                .testTag("room_system_prompt_input"),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isHighLimit) {
                        showHighLimitsConfirmation = true
                    } else {
                        onSave(
                            maxMessages.roundToInt(),
                            maxPasses.roundToInt(),
                            systemPromptTextFieldState.text
                                .toString()
                                .trim()
                                .ifBlank { null },
                        )
                    }
                },
                modifier = Modifier.testTag("save_settings_button"),
            ) {
                Text(stringResource(R.string.group_chat_settings_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        maxMessages = DEFAULT_MAX_BOT_MESSAGES.toFloat()
                        maxPasses = DEFAULT_MAX_CONTINUATION_PASSES.toFloat()
                        systemPromptTextFieldState.clearText()
                    },
                ) {
                    Text(stringResource(R.string.group_chat_settings_reset_default))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        },
    )

    if (showHighLimitsConfirmation) {
        AlertDialog(
            onDismissRequest = { showHighLimitsConfirmation = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.group_chat_settings_high_limits_warning_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text =
                        stringResource(
                            R.string.group_chat_settings_high_limits_warning_desc,
                            maxMessages.roundToInt(),
                            maxPasses.roundToInt(),
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showHighLimitsConfirmation = false
                        onSave(
                            maxMessages.roundToInt(),
                            maxPasses.roundToInt(),
                            systemPromptTextFieldState.text
                                .toString()
                                .trim()
                                .ifBlank { null },
                        )
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    modifier = Modifier.testTag("confirm_high_limits_button"),
                ) {
                    Text(stringResource(R.string.group_chat_settings_high_limits_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showHighLimitsConfirmation = false },
                    modifier = Modifier.testTag("dismiss_high_limits_button"),
                ) {
                    Text(stringResource(R.string.group_chat_settings_high_limits_adjust))
                }
            },
        )
    }
}
