package com.m57.hermescontrol.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.settings.components.AppearanceSection
import com.m57.hermescontrol.ui.settings.components.BehaviorSection
import com.m57.hermescontrol.ui.settings.components.ChatSection
import com.m57.hermescontrol.ui.settings.components.ConnectionSection
import com.m57.hermescontrol.ui.settings.components.NavBarSection
import com.m57.hermescontrol.ui.settings.components.SaveIndicator
import com.m57.hermescontrol.ui.settings.components.TestConnectionButton
import com.m57.hermescontrol.ui.settings.components.TestResultCard

enum class SettingsTab {
    CONNECTION,
    APPEARANCE,
    BEHAVIOR,
    ABOUT,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToLogin: () -> Unit = {},
    onLogout: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel { SettingsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.navigateToLogin) {
        if (state.navigateToLogin) {
            onNavigateToLogin()
            viewModel.clearNavigateToLogin()
        }
    }

    var passwordVisible by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(SettingsTab.CONNECTION) }

    BackHandler(onBack = onBack)

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_settings)) },
        navigationIcon = NavIcon.Back(onBack),
        actions = {
            TextButton(onClick = viewModel::save) {
                Text(stringResource(R.string.action_save))
            }
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize(),
        ) {
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                modifier = Modifier.fillMaxWidth(),
                edgePadding = 0.dp,
            ) {
                SettingsTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = {
                            Text(
                                when (tab) {
                                    SettingsTab.CONNECTION -> stringResource(R.string.settings_tab_connection)
                                    SettingsTab.APPEARANCE -> stringResource(R.string.settings_tab_appearance)
                                    SettingsTab.BEHAVIOR -> stringResource(R.string.settings_tab_behavior)
                                    SettingsTab.ABOUT -> stringResource(R.string.settings_tab_about)
                                },
                            )
                        },
                    )
                }
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (selectedTab) {
                    SettingsTab.CONNECTION -> {
                        ConnectionSection(
                            state = state,
                            viewModel = viewModel,
                            passwordVisible = passwordVisible,
                            onPasswordVisibilityToggle = { passwordVisible = !passwordVisible },
                        )

                        TestResultCard(testResult = state.testResult)
                        SaveIndicator(isSaved = state.isSaved)
                        TestConnectionButton(
                            isTesting = state.isTesting,
                            onTest = viewModel::testConnection,
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Button(
                            onClick = {
                                viewModel.logout()
                                onLogout()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                ),
                        ) {
                            Text(stringResource(R.string.settings_logout))
                        }
                    }

                    SettingsTab.BEHAVIOR -> {
                        BehaviorSection(
                            autoReconnect = state.autoReconnect,
                            onAutoReconnectChange = viewModel::onAutoReconnectChange,
                        )
                    }

                    SettingsTab.APPEARANCE -> {
                        AppearanceSection(
                            themePreference = state.themePreference,
                            onThemeChange = viewModel::onThemeChange,
                            useDynamicColors = state.useDynamicColors,
                            onUseDynamicColorsChange = viewModel::onUseDynamicColorsChange,
                            themePreset = state.themePreset,
                            onThemePresetChange = viewModel::onThemePresetChange,
                        )

                        ChatSection(
                            typingEffectEnabled = state.typingEffectEnabled,
                            onTypingEffectEnabledChange = viewModel::onTypingEffectEnabledChange,
                            typingEffectDelayMs = state.typingEffectDelayMs,
                            onTypingEffectDelayMsChange = viewModel::onTypingEffectDelayMsChange,
                        )

                        NavBarSection(
                            bottomNavDisplayMode = state.bottomNavDisplayMode,
                            onBottomNavDisplayModeChange = viewModel::onBottomNavDisplayModeChange,
                            selectedNavItems = state.selectedNavItems,
                            availableNavItems = viewModel.availableNavItems,
                            onReorderNavItems = viewModel::reorderNavItems,
                            onRemoveNavItem = viewModel::removeNavItem,
                            onAddNavItem = viewModel::addNavItem,
                        )
                    }

                    SettingsTab.ABOUT -> {
                        // ── About section ─────────────────────────────────────────
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
                        }
                    }
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
internal fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style =
                    MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    ),
                modifier = Modifier.padding(bottom = 12.dp),
            )
            content()
        }
    }
}
