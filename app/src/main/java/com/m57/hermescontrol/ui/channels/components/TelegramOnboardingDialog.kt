package com.m57.hermescontrol.ui.channels.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.channels.ChannelsUiState
import com.m57.hermescontrol.ui.channels.OnboardingPhase

@Composable
internal fun TelegramOnboardingDialog(
    state: ChannelsUiState,
    onDismiss: () -> Unit,
    onAddAllowedId: () -> Unit,
    onRemoveAllowedId: (String) -> Unit,
    onNewAllowedIdChange: (String) -> Unit,
    onApply: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text =
                    when (state.onboardingPhase) {
                        OnboardingPhase.STARTING -> "Starting…"
                        OnboardingPhase.WAITING -> "Scan QR code"
                        OnboardingPhase.READY -> "Telegram connected"
                        OnboardingPhase.APPLYING -> "Saving…"
                        OnboardingPhase.IDLE -> "Telegram Setup"
                    },
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.onboardingError?.let { error ->
                    Surface(
                        color = LocalHermesStatusColors.current.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                when (state.onboardingPhase) {
                    OnboardingPhase.STARTING -> {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    OnboardingPhase.WAITING -> {
                        Text(
                            text = "打开 Telegram 扫描应用内显示的二维码。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (state.onboardingExpiresIn.isNotEmpty()) {
                            Text(
                                text = "二维码将在 ${state.onboardingExpiresIn} 后过期",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    OnboardingPhase.READY -> {
                        state.onboardingBotUsername?.let { username ->
                            Text(
                                text = "已连接为 @$username",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "允许的用户：",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        state.onboardingDetectedOwnerId?.let { ownerId ->
                            if (state.onboardingAllowedIds.contains(ownerId)) {
                                Text(
                                    text = "$ownerId (owner detected)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        state.onboardingAllowedIds.forEach { id ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = id,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                IconButton(onClick = { onRemoveAllowedId(id) }) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = state.onboardingNewAllowedId,
                                onValueChange = onNewAllowedIdChange,
                                placeholder = { Text("Telegram 用户 ID") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = onAddAllowedId) {
                                Icon(Icons.Filled.Add, contentDescription = "添加用户 ID")
                            }
                        }
                    }

                    OnboardingPhase.APPLYING -> {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "正在保存配置…",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }

                    OnboardingPhase.IDLE -> { }
                }
            }
        },
        confirmButton = {
            when (state.onboardingPhase) {
                OnboardingPhase.READY -> {
                    Button(
                        onClick = onApply,
                        enabled = state.onboardingAllowedIds.isNotEmpty(),
                    ) {
                        Text("保存并重启")
                    }
                }

                else -> {}
            }
        },
        dismissButton = {
            when (state.onboardingPhase) {
                OnboardingPhase.READY -> {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }

                OnboardingPhase.WAITING -> {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }

                OnboardingPhase.IDLE, OnboardingPhase.STARTING, OnboardingPhase.APPLYING -> {}
            }
        },
    )
}
