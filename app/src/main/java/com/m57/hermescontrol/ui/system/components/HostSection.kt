package com.m57.hermescontrol.ui.system.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.Spacing
import com.m57.hermescontrol.ui.common.InfoRow
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.common.StatCard
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.system.SystemUiState
import com.m57.hermescontrol.ui.system.SystemViewModel
import com.m57.hermescontrol.util.formatBytes
import com.m57.hermescontrol.util.formatDuration

@OptIn(ExperimentalLayoutApi::class)
fun LazyListScope.hostSection(
    state: SystemUiState,
    spacing: Spacing,
    viewModel: SystemViewModel,
) {
    state.stats?.let { stats ->
        item {
            SectionHeader(title = stringResource(R.string.system_sec_host))
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
                    // OS, Arch, Hostname
                    stats.os?.let { InfoRow(stringResource(R.string.system_label_os), it) }
                    stats.arch?.let { InfoRow(stringResource(R.string.system_label_arch), it) }
                    stats.hostname?.let { InfoRow(stringResource(R.string.system_label_hostname), it) }
                    stats.python_version?.let { python ->
                        val impl = stats.python_impl?.let { " ($it)" } ?: ""
                        InfoRow(stringResource(R.string.system_label_python), "$python$impl")
                    }

                    // Hermes version + update badge
                    stats.hermes_version?.let { ver ->
                        val updateBadge =
                            state.updateInfo?.let { info ->
                                when {
                                    info.update_available == true && info.behind != null && info.behind > 0 -> {
                                        " (${stringResource(
                                            R.string.system_version_update_available,
                                        )}: ${stringResource(R.string.system_version_behind, info.behind)})"
                                    }

                                    info.update_available == true -> {
                                        " (${stringResource(
                                            R.string.system_version_update_available,
                                        )})"
                                    }

                                    else -> {
                                        " (${stringResource(R.string.system_version_latest)})"
                                    }
                                }
                            } ?: ""
                        InfoRow(stringResource(R.string.system_label_hermes_version), "$ver$updateBadge")
                    }

                    // Update buttons
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.checkForUpdate(false) },
                            enabled = !state.checkingUpdate,
                            modifier = Modifier.weight(1f),
                            contentPadding = ActionButtonPadding,
                        ) {
                            if (state.checkingUpdate) {
                                ActionButtonContent(
                                    icon = null,
                                    text = stringResource(R.string.system_action_check_updates),
                                    loading = true,
                                )
                            } else {
                                ActionButtonContent(
                                    icon = Icons.Filled.Update,
                                    text = stringResource(R.string.system_action_check_updates),
                                )
                            }
                        }
                        state.updateInfo?.let { info ->
                            if (info.update_available == true && info.can_apply == true) {
                                Button(
                                    onClick = { viewModel.openUpdateConfirm() },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = ActionButtonPadding,
                                ) {
                                    ActionButtonContent(
                                        icon = Icons.Filled.PlayArrow,
                                        text = stringResource(R.string.system_action_update_now),
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))

                    // CPU / Memory / Disk stat cards
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        stats.cpu_percent?.let { cpu ->
                            StatCard(
                                label = stringResource(R.string.system_label_cpu),
                                value = "${cpu.toInt()}%",
                                icon = Icons.Filled.Speed,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        stats.memory?.percent?.let { mem ->
                            StatCard(
                                label = stringResource(R.string.system_label_memory),
                                value = "${mem.toInt()}%",
                                icon = Icons.Filled.Memory,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        stats.disk?.percent?.let { disk ->
                            StatCard(
                                label = stringResource(R.string.system_label_disk),
                                value = "${disk.toInt()}%",
                                icon = Icons.Filled.Storage,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    // Memory details
                    stats.memory?.let { mem ->
                        InfoRow(
                            label = stringResource(R.string.system_label_memory),
                            value =
                                stringResource(
                                    R.string.system_label_used_total_percent,
                                    formatBytes(mem.used ?: 0L),
                                    formatBytes(mem.total ?: 0L),
                                    "${(mem.percent ?: 0.0).toInt()}",
                                ),
                        )
                    }

                    // Disk details
                    stats.disk?.let { disk ->
                        InfoRow(
                            label = stringResource(R.string.system_label_disk),
                            value =
                                stringResource(
                                    R.string.system_label_used_total_percent,
                                    formatBytes(disk.used ?: 0L),
                                    formatBytes(disk.total ?: 0L),
                                    "${(disk.percent ?: 0.0).toInt()}",
                                ),
                        )
                    }

                    // Uptime
                    stats.uptime_seconds?.let { secs ->
                        InfoRow(
                            label = stringResource(R.string.system_label_uptime),
                            value = formatDuration(secs),
                        )
                    }

                    // Load avg
                    stats.load_avg?.let { loads ->
                        if (loads.isNotEmpty()) {
                            val coresText =
                                stats.cpu_count?.let {
                                    " ($it ${stringResource(
                                        R.string.system_label_cores,
                                    )})"
                                } ?: ""
                            InfoRow(
                                label = stringResource(R.string.system_label_load_avg),
                                value = loads.joinToString(", ") { "%.2f".format(it) } + coresText,
                            )
                        }
                    } ?: stats.cpu_count?.let { cores ->
                        InfoRow(
                            label = stringResource(R.string.system_label_cpu),
                            value = "$cores ${stringResource(R.string.system_label_cores)}",
                        )
                    }

                    // psutil warning
                    if (stats.psutil == false) {
                        Spacer(modifier = Modifier.height(spacing.sm))
                        StatusBadge(
                            text = stringResource(R.string.system_psutil_warning),
                            status = StatusBadgeType.WARNING,
                        )
                    }
                }
            }
        }
    }
}
