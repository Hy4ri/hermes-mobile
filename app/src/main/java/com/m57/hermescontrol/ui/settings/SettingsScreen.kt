package com.m57.hermescontrol.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.SettingsAbout
import com.m57.hermescontrol.SettingsAppearance
import com.m57.hermescontrol.SettingsBehavior
import com.m57.hermescontrol.SettingsChat
import com.m57.hermescontrol.SettingsConnection
import com.m57.hermescontrol.assistant.AssistantRole
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon

@Composable
fun SettingsScreen(
    onOpenDrawer: (() -> Unit)? = null,
    onNavigateToLogin: () -> Unit = {},
    onLogout: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel { SettingsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var assistantActive by remember { mutableStateOf(false) }

    // Refresh after returning from the system role dialog / settings screen.
    LifecycleResumeEffect(Unit) {
        assistantActive = AssistantRole.isDefaultAssistant(context)
        onPauseOrDispose { }
    }

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_settings)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
    ) {
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SettingsCategoryCard(
                items =
                    listOf(
                        SettingsRow(
                            icon = Icons.AutoMirrored.Filled.ListAlt,
                            label = stringResource(R.string.settings_sec_connection),
                            summary =
                                if (state.profiles.isNotEmpty()) {
                                    stringResource(
                                        R.string.settings_summary_profiles,
                                        state.profiles.size,
                                    )
                                } else {
                                    null
                                },
                            onClick = { NavigationController.navigateTo(SettingsConnection) },
                        ),
                        SettingsRow(
                            icon = Icons.Filled.Palette,
                            label = stringResource(R.string.settings_sec_appearance),
                            summary = appearanceSummary(state),
                            onClick = { NavigationController.navigateTo(SettingsAppearance) },
                        ),
                        SettingsRow(
                            icon = Icons.Filled.ChatBubbleOutline,
                            label = stringResource(R.string.settings_sec_chat),
                            summary =
                                if (state.typingEffectEnabled) {
                                    stringResource(R.string.settings_summary_typing_on)
                                } else {
                                    stringResource(R.string.settings_summary_typing_off)
                                },
                            onClick = { NavigationController.navigateTo(SettingsChat) },
                        ),
                        SettingsRow(
                            icon = Icons.Filled.Tune,
                            label = stringResource(R.string.settings_sec_behavior),
                            summary =
                                if (state.autoReconnect) {
                                    stringResource(R.string.settings_summary_on)
                                } else {
                                    stringResource(R.string.settings_summary_off)
                                },
                            onClick = { NavigationController.navigateTo(SettingsBehavior) },
                        ),
                        SettingsRow(
                            icon = Icons.Filled.SmartToy,
                            label = stringResource(R.string.settings_sec_assistant),
                            summary =
                                if (!AssistantRole.isSupported(context)) {
                                    stringResource(R.string.settings_summary_assistant_unsupported)
                                } else if (assistantActive) {
                                    stringResource(R.string.settings_summary_assistant_active)
                                } else {
                                    stringResource(R.string.settings_summary_assistant_inactive)
                                },
                            onClick = {
                                if (AssistantRole.isSupported(context)) {
                                    context.startActivity(AssistantRole.requestRoleIntent(context))
                                }
                            },
                        ),
                        SettingsRow(
                            icon = Icons.Filled.Info,
                            label = stringResource(R.string.settings_sec_about),
                            onClick = { NavigationController.navigateTo(SettingsAbout) },
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun appearanceSummary(state: SettingsUiState): String {
    val theme =
        when (state.themePreference) {
            ThemePreference.SYSTEM -> stringResource(R.string.settings_summary_theme_system)
            ThemePreference.LIGHT -> stringResource(R.string.settings_summary_theme_light)
            ThemePreference.DARK -> stringResource(R.string.settings_summary_theme_dark)
        }
    val lang =
        when (state.appLanguage) {
            "system" -> stringResource(R.string.language_system)
            "ko" -> stringResource(R.string.language_korean)
            else -> stringResource(R.string.language_english)
        }
    return "$theme · $lang"
}

private data class SettingsRow(
    val icon: ImageVector,
    val label: String,
    val summary: String? = null,
    val onClick: () -> Unit,
)

@Composable
private fun SettingsCategoryCard(items: List<SettingsRow>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Column {
            items.forEachIndexed { index, row ->
                ListItem(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = row.onClick),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        Icon(
                            imageVector = row.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    headlineContent = {
                        Text(
                            text = row.label,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    supportingContent =
                        row.summary?.let { summary ->
                            {
                                Text(
                                    text = summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                if (index < items.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp, end = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun InfoRow(
    label: String,
    value: String,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
internal fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}
