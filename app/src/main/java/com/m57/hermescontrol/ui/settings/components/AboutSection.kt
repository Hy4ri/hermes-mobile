package com.m57.hermescontrol.ui.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.settings.AppUpdateState
import com.m57.hermescontrol.ui.settings.InfoRow
import com.m57.hermescontrol.ui.settings.SectionCard

@Composable
internal fun AboutSection(
    updateState: AppUpdateState = AppUpdateState.Idle,
    onCheckUpdate: () -> Unit = {},
    onStartUpdate: () -> Unit = {},
    onOpenInstallSettings: () -> Unit = {},
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_about_app_name),
                style =
                    MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(8.dp))

        InfoRow(
            label = stringResource(R.string.settings_about_version),
            value = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        )
        UpdateRow(
            state = updateState,
            onCheckUpdate = onCheckUpdate,
            onStartUpdate = onStartUpdate,
            onOpenInstallSettings = onOpenInstallSettings,
        )
        InfoRow(
            label = stringResource(R.string.settings_about_build),
            value =
                if (BuildConfig.DEBUG) {
                    stringResource(R.string.settings_about_debug)
                } else {
                    stringResource(R.string.settings_about_release)
                },
        )
        if (BuildConfig.GIT_SHA.isNotBlank()) {
            InfoRow(
                label = stringResource(R.string.settings_about_commit),
                value = BuildConfig.GIT_SHA,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "https://github.com/Hy4ri/hermes-mobile",
            style =
                MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.primary,
                ),
        )
    }
}

@Composable
private fun UpdateRow(
    state: AppUpdateState,
    onCheckUpdate: () -> Unit,
    onStartUpdate: () -> Unit,
    onOpenInstallSettings: () -> Unit,
) {
    // GitHub release tags carry a leading "v" (e.g. "v1.21.1"); the string
    // templates below already prepend one, so strip it here to avoid "vv".
    val tag =
        (state as? AppUpdateState.UpToDate)?.latestTag
            ?: (state as? AppUpdateState.UpdateAvailable)?.latestTag
            ?: (state as? AppUpdateState.Installing)?.latestTag
    val displayTag = tag?.trimStart('v').orEmpty()
    val tappable =
        state is AppUpdateState.Idle ||
            state is AppUpdateState.UpToDate ||
            state is AppUpdateState.Error
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable(enabled = tappable) { onCheckUpdate() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_about_update),
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )
            Spacer(modifier = Modifier.weight(1f))
            when (state) {
                is AppUpdateState.Idle -> {
                    ValueText(stringResource(R.string.settings_about_update_check))
                }

                is AppUpdateState.Checking -> {
                    ValueText(stringResource(R.string.settings_about_update_checking))
                }

                is AppUpdateState.UpToDate -> {
                    ValueText(
                        stringResource(R.string.settings_about_update_uptodate, displayTag),
                    )
                }

                is AppUpdateState.UpdateAvailable -> {
                    ValueText(
                        stringResource(R.string.settings_about_update_available, displayTag),
                    )
                }

                is AppUpdateState.Downloading -> {
                    ValueText(
                        stringResource(
                            R.string.settings_about_update_downloading,
                            (state.progress * 100).toInt(),
                        ),
                    )
                }

                is AppUpdateState.Installing -> {
                    ValueText(
                        stringResource(R.string.settings_about_update_installing, displayTag),
                    )
                }

                is AppUpdateState.NeedsUnknownSourcesPermission -> {
                    ValueText(stringResource(R.string.settings_about_update_allow_sources))
                }

                is AppUpdateState.Error -> {
                    ValueText(state.message)
                }
            }
        }

        // Action area: the button/progress gets its own full-width line so a
        // long status text or error message never fights it for space.
        when (state) {
            is AppUpdateState.UpdateAvailable -> {
                Button(
                    onClick = onStartUpdate,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.settings_about_update_action))
                }
            }

            is AppUpdateState.Downloading -> {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }

            is AppUpdateState.NeedsUnknownSourcesPermission -> {
                OutlinedButton(
                    onClick = onOpenInstallSettings,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.settings_about_update_open_settings))
                }
            }

            is AppUpdateState.Error -> {
                OutlinedButton(
                    onClick = onCheckUpdate,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.settings_about_update_retry))
                }
            }

            else -> {}
        }
    }
}

@Composable
private fun ValueText(text: String) {
    Text(
        text = text,
        style =
            MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
    )
}
