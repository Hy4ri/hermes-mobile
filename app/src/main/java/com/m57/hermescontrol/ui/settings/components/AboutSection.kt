package com.m57.hermescontrol.ui.settings.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.settings.InfoRow
import com.m57.hermescontrol.ui.settings.SectionCard

@Composable
internal fun AboutSection(onLogout: () -> Unit) {
    SectionCard(title = stringResource(R.string.settings_sec_about)) {
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

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth(),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
        ) {
            Text(stringResource(R.string.settings_logout))
        }
    }
}
