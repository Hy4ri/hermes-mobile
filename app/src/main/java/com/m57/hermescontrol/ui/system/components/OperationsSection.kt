package com.m57.hermescontrol.ui.system.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.Spacing
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.system.SystemUiState
import com.m57.hermescontrol.ui.system.SystemViewModel

fun LazyListScope.operationsSection(
    state: SystemUiState,
    spacing: Spacing,
    viewModel: SystemViewModel,
    onImportConfirm: (Boolean) -> Unit,
    onImportPick: () -> Unit,
    downloadBackup: () -> Unit,
) {
    item {
        SectionHeader(title = stringResource(R.string.system_sec_operations))
    }
    item {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
        ) {
            Column(modifier = Modifier.padding(spacing.md)) {
                // Operation buttons row 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    FilledTonalButton(
                        onClick = { viewModel.loadAll() },
                        modifier = Modifier.weight(1f),
                        contentPadding = ActionButtonPadding,
                    ) {
                        ActionButtonContent(
                            icon = Icons.Filled.HealthAndSafety,
                            text = stringResource(R.string.system_op_doctor),
                        )
                    }
                    FilledTonalButton(
                        onClick = { viewModel.runSecurityAudit() },
                        modifier = Modifier.weight(1f),
                        contentPadding = ActionButtonPadding,
                    ) {
                        ActionButtonContent(
                            icon = Icons.Filled.VerifiedUser,
                            text = stringResource(R.string.system_op_security_audit),
                        )
                    }
                    FilledTonalButton(
                        onClick = { viewModel.runUpdateSkills() },
                        modifier = Modifier.weight(1f),
                        contentPadding = ActionButtonPadding,
                    ) {
                        ActionButtonContent(
                            icon = Icons.Filled.Refresh,
                            text = stringResource(R.string.system_op_update_skills),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(spacing.sm))

                // Operation buttons row 2
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    FilledTonalButton(
                        onClick = { viewModel.runPromptSize() },
                        modifier = Modifier.weight(1f),
                        contentPadding = ActionButtonPadding,
                    ) {
                        ActionButtonContent(
                            icon = Icons.Filled.HealthAndSafety,
                            text = stringResource(R.string.system_op_prompt_size),
                        )
                    }
                    FilledTonalButton(
                        onClick = { viewModel.runDump() },
                        modifier = Modifier.weight(1f),
                        contentPadding = ActionButtonPadding,
                    ) {
                        ActionButtonContent(
                            icon = Icons.Filled.Build,
                            text = stringResource(R.string.system_op_dump),
                        )
                    }
                    FilledTonalButton(
                        onClick = { viewModel.runConfigMigrate() },
                        modifier = Modifier.weight(1f),
                        contentPadding = ActionButtonPadding,
                    ) {
                        ActionButtonContent(
                            icon = Icons.Filled.Build,
                            text = stringResource(R.string.system_op_config_migrate),
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))

                // Backup section
                Text(
                    text = stringResource(R.string.system_sec_backup),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(spacing.sm))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Button(
                        onClick = { viewModel.triggerBackup() },
                        modifier = Modifier.weight(1f),
                        contentPadding = ActionButtonPadding,
                    ) {
                        ActionButtonContent(
                            icon = Icons.Filled.Backup,
                            text = stringResource(R.string.system_op_backup_create),
                        )
                    }
                    OutlinedButton(
                        onClick = downloadBackup,
                        enabled = state.backupArchive != null && !state.isDownloading,
                        modifier = Modifier.weight(1f),
                        contentPadding = ActionButtonPadding,
                    ) {
                        if (state.isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.size(spacing.xs))
                        } else {
                            Icon(
                                Icons.Filled.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.size(spacing.xs))
                        }
                        Text(
                            text = stringResource(R.string.system_op_backup_download),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }

                // Restore from picked archive (issue #786: SAF picker → upload)
                Spacer(modifier = Modifier.height(spacing.sm))
                Text(
                    text = stringResource(R.string.system_sec_restore),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(spacing.sm))

                OutlinedButton(
                    onClick = onImportPick,
                    enabled = !state.isImporting,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = ActionButtonPadding,
                ) {
                    Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(spacing.xs))
                    Text(
                        text = stringResource(R.string.system_op_restore_pick),
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                state.importFileName?.let { name ->
                    Spacer(modifier = Modifier.height(spacing.sm))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Description,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(spacing.sm))

                Button(
                    onClick = { onImportConfirm(true) },
                    enabled = state.importFileName != null && !state.isImporting,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError,
                        )
                        Spacer(modifier = Modifier.size(spacing.xs))
                    } else {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(spacing.xs))
                    }
                    Text(stringResource(R.string.system_op_restore_upload), maxLines = 1, softWrap = false)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))

                // Debug share section
                SectionHeader(title = stringResource(R.string.system_sec_debug_share))
                Spacer(modifier = Modifier.height(spacing.sm))

                Text(
                    text = stringResource(R.string.system_debug_share_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(spacing.sm))

                Switch(
                    checked = state.shareRedact,
                    onCheckedChange = { viewModel.toggleShareRedact() },
                )
                Spacer(modifier = Modifier.size(spacing.sm))
                Text(
                    text = stringResource(R.string.system_debug_share_redact),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = spacing.xs),
                )

                Spacer(modifier = Modifier.height(spacing.sm))

                Button(
                    onClick = { viewModel.runDebugShare() },
                    enabled = !state.sharing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.sharing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.size(spacing.sm))
                    } else {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(spacing.xs))
                    }
                    Text(
                        if (state.sharing) {
                            stringResource(R.string.system_debug_share_uploading)
                        } else {
                            stringResource(R.string.system_debug_share_generate)
                        },
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                // Debug share results
                state.debugShare?.let { share ->
                    Spacer(modifier = Modifier.height(spacing.sm))
                    @Suppress("DEPRECATION")
                    val clipboardManager = LocalClipboardManager.current

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        StatusBadge(
                            text =
                                if (share.ok == true) {
                                    stringResource(R.string.system_debug_share_uploaded)
                                } else {
                                    stringResource(R.string.system_debug_share_failed)
                                },
                            status = if (share.ok == true) StatusBadgeType.SUCCESS else StatusBadgeType.ERROR,
                        )
                        share.redacted?.let { redacted ->
                            StatusBadge(
                                text =
                                    if (redacted) {
                                        stringResource(R.string.system_debug_share_redacted)
                                    } else {
                                        stringResource(R.string.system_debug_share_not_redacted)
                                    },
                                status = StatusBadgeType.INFO,
                            )
                        }
                        share.auto_delete_seconds?.let { secs ->
                            StatusBadge(
                                text = stringResource(R.string.system_debug_share_auto_delete, secs / 3600),
                                status = StatusBadgeType.NEUTRAL,
                            )
                        }
                    }

                    share.urls?.forEach { (name, url) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "$name: $url",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                @Suppress("DEPRECATION")
                                clipboardManager.setText(AnnotatedString(url))
                            }) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = stringResource(R.string.system_debug_share_copy_all),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
