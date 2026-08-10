package com.m57.hermescontrol.ui.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/**
 * In-app update row (issue #867): tap to check GitHub releases; when a newer
 * release exists, download + install from here. Idle/up-to-date/error states
 * are tappable to re-check; actionable states show a labeled button.
 */
@Composable
private fun UpdateRow(
    state: AppUpdateState,
    onCheckUpdate: () -> Unit,
    onStartUpdate: () -> Unit,
    onOpenInstallSettings: () -> Unit,
) {
    val tappable =
        state is AppUpdateState.Idle ||
            state is AppUpdateState.UpToDate ||
            state is AppUpdateState.Error
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
                ValueText(stringResource(R.string.settings_about_update_uptodate, state.latestTag))
            }

            is AppUpdateState.UpdateAvailable -> {
                ValueText(stringResource(R.string.settings_about_update_available, state.latestTag))
                TextButton(onClick = onStartUpdate) {
                    Text(stringResource(R.string.settings_about_update_action))
                }
            }

            is AppUpdateState.Downloading -> {
                ValueText(
                    stringResource(
                        R.string.settings_about_update_downloading,
                        (state.progress * 100).toInt(),
                    ),
                )
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.width(80.dp).padding(start = 8.dp),
                )
            }

            is AppUpdateState.Installing -> {
                ValueText(stringResource(R.string.settings_about_update_installing, state.latestTag))
            }

            is AppUpdateState.NeedsUnknownSourcesPermission -> {
                ValueText(stringResource(R.string.settings_about_update_allow_sources))
                TextButton(onClick = onOpenInstallSettings) {
                    Text(stringResource(R.string.settings_about_update_open_settings))
                }
            }

            is AppUpdateState.Error -> {
                ValueText(state.message)
                TextButton(onClick = onCheckUpdate) {
                    Text(stringResource(R.string.settings_about_update_retry))
                }
            }
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
