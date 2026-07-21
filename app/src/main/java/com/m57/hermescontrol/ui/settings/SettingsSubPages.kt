package com.m57.hermescontrol.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.settings.components.AboutSection
import com.m57.hermescontrol.ui.settings.components.AppearanceSection
import com.m57.hermescontrol.ui.settings.components.BehaviorSection
import com.m57.hermescontrol.ui.settings.components.ChatSection
import com.m57.hermescontrol.ui.settings.components.ConnectionSection
import com.m57.hermescontrol.ui.settings.components.TestConnectionButton
import com.m57.hermescontrol.ui.settings.components.TestResultCard

/**
 * Drill-down sub-pages for Settings. Each is its own NavKey destination
 * (see Navigation.kt) so the native back stack handles navigation — no
 * manual routing. The SettingsViewModel stays the single source of truth.
 *
 * All sub-pages pass `drawerGesturesEnabled = false` to [HermesScaffold] so the
 * modal drawer's swipe gestures are disabled and the drawer auto-closes when a
 * sub-page is entered — the single source of truth introduced in issue #619.
 */

@Composable
internal fun SettingsConnectionPage(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    viewModel: SettingsViewModel = viewModel { SettingsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }

    HermesScaffold(
        title = { Text(stringResource(R.string.settings_sec_connection)) },
        navigationIcon = NavIcon.Back(onBack),
        // Non-primary drill-down: opt out of drawer gestures so the scrim can't
        // get stuck open (issue #619). DrawerGestureController handles the close.
        drawerGesturesEnabled = false,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConnectionSection(
                state = state,
                viewModel = viewModel,
                passwordVisible = passwordVisible,
                onPasswordVisibilityToggle = { passwordVisible = !passwordVisible },
            )

            TestResultCard(testResult = state.testResult)
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
    }
}

@Composable
internal fun SettingsAppearancePage(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel { SettingsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    HermesScaffold(
        title = { Text(stringResource(R.string.settings_sec_appearance)) },
        navigationIcon = NavIcon.Back(onBack),
        drawerGesturesEnabled = false,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppearanceSection(
                themePreference = state.themePreference,
                onThemeChange = viewModel::onThemeChange,
                useDynamicColors = state.useDynamicColors,
                onUseDynamicColorsChange = viewModel::onUseDynamicColorsChange,
                themePreset = state.themePreset,
                onThemePresetChange = viewModel::onThemePresetChange,
                appLanguage = state.appLanguage,
                onAppLanguageChange = viewModel::onAppLanguageChange,
            )
        }
    }
}

@Composable
internal fun SettingsChatPage(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel { SettingsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    HermesScaffold(
        title = { Text(stringResource(R.string.settings_sec_chat)) },
        navigationIcon = NavIcon.Back(onBack),
        drawerGesturesEnabled = false,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ChatSection(
                typingEffectEnabled = state.typingEffectEnabled,
                onTypingEffectEnabledChange = viewModel::onTypingEffectEnabledChange,
                typingEffectDelayMs = state.typingEffectDelayMs,
                onTypingEffectDelayMsChange = viewModel::onTypingEffectDelayMsChange,
            )
        }
    }
}

@Composable
internal fun SettingsBehaviorPage(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel { SettingsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    HermesScaffold(
        title = { Text(stringResource(R.string.settings_sec_behavior)) },
        navigationIcon = NavIcon.Back(onBack),
        drawerGesturesEnabled = false,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BehaviorSection(
                autoReconnect = state.autoReconnect,
                onAutoReconnectChange = viewModel::onAutoReconnectChange,
            )
        }
    }
}

@Composable
internal fun SettingsAboutPage(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel { SettingsViewModel() },
) {
    HermesScaffold(
        title = { Text(stringResource(R.string.settings_sec_about)) },
        navigationIcon = NavIcon.Back(onBack),
        drawerGesturesEnabled = false,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AboutSection()
        }
    }
}
