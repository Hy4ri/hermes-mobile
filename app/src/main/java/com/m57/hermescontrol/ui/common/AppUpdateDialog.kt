package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.update.AppUpdateState
import com.m57.hermescontrol.data.update.releaseTag

@Composable
fun AppUpdateDialog(
    state: AppUpdateState,
    onDismiss: () -> Unit,
    onLater: () -> Unit = onDismiss,
    onStartUpdate: () -> Unit,
    onCancelDownload: () -> Unit,
    onNeverAskAgain: () -> Unit,
    onOpenSettings: () -> Unit,
    onCheckUpdate: () -> Unit = {},
) {
    val available = state as? AppUpdateState.UpdateAvailable
    var cachedAvailable by remember { mutableStateOf(available) }
    if (available != null) {
        cachedAvailable = available
    }
    val currentAvailable = available ?: cachedAvailable
    val tag = state.releaseTag() ?: currentAvailable?.latestTag.orEmpty()
    val sizeMb =
        currentAvailable?.let {
            val mb = it.sizeBytes / (1024 * 1024.0)
            "%.1f MB".format(mb)
        }
    val releaseNotes = currentAvailable?.releaseNotes?.trim().orEmpty()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.update_dialog_title, tag),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (sizeMb != null) {
                            Text(
                                text = sizeMb,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_dismiss),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Release notes / content preview
                if (releaseNotes.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.update_dialog_changelog_header),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = releaseNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Progress / Action States
                when (state) {
                    AppUpdateState.Checking -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.settings_about_update_checking),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }

                    is AppUpdateState.UpToDate -> {
                        Text(
                            text = stringResource(R.string.settings_about_update_uptodate, state.latestTag),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is AppUpdateState.Downloading -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val progressPercent = (state.progress * 100).toInt()
                                Text(
                                    text =
                                        stringResource(
                                            R.string.settings_about_update_downloading,
                                            progressPercent,
                                        ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (currentAvailable != null && currentAvailable.sizeBytes > 0) {
                                    val downloadedMb = (state.progress * currentAvailable.sizeBytes) / (1024 * 1024.0)
                                    val totalMb = currentAvailable.sizeBytes / (1024 * 1024.0)
                                    Text(
                                        text = "%.1f / %.1f MB".format(downloadedMb, totalMb),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            if (state.progress > 0f) {
                                LinearProgressIndicator(
                                    progress = { state.progress },
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp)),
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp)),
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = onCancelDownload,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    }

                    is AppUpdateState.Installing -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.settings_about_update_installing, tag),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                            )
                        }
                    }

                    is AppUpdateState.NeedsUnknownSourcesPermission -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.settings_about_update_allow_sources),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = onOpenSettings,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.settings_about_update_open_settings))
                            }
                        }
                    }

                    is AppUpdateState.Error -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = if (state.isCheckError) onCheckUpdate else onStartUpdate,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.settings_about_update_retry))
                            }
                        }
                    }

                    is AppUpdateState.UpdateAvailable -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = onStartUpdate,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.update_dialog_action_install))
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                TextButton(onClick = onLater) {
                                    Text(stringResource(R.string.update_dialog_action_later))
                                }
                                TextButton(onClick = onNeverAskAgain) {
                                    Text(
                                        text = stringResource(R.string.update_dialog_action_skip),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }

                    AppUpdateState.Idle -> {
                        Button(onClick = onCheckUpdate, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.settings_about_update_check))
                        }
                    }
                }
            }
        }
    }
}
