package com.m57.hermescontrol.ui.config

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SpinningIndicator
import com.m57.hermescontrol.ui.common.ToastEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: ConfigViewModel = viewModel { ConfigViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editingText by remember(state.yamlText) { mutableStateOf(state.yamlText ?: "") }

    LaunchedEffect(Unit) {
        viewModel.loadRawConfig()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    HermesScaffold(
        title = { Text(stringResource(R.string.config_screen_title)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadRawConfig() },
    ) { paddingValues ->
        when {
            state.isLoading && state.yamlText == null -> {
                LoadingState(modifier = Modifier.padding(paddingValues))
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadRawConfig() },
                    modifier = Modifier.padding(paddingValues),
                )
            }

            else -> {
                Box(Modifier.fillMaxSize()) {
                    if (state.isLoading && state.yamlText == null) {
                        SpinningIndicator()
                    } else if (state.errorMessage != null && state.yamlText == null) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(text = state.errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { viewModel.loadRawConfig() }) {
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    } else {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                        ) {
                            state.path?.let {
                                Text(
                                    text = "Path: $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }

                            OutlinedTextField(
                                value = editingText,
                                onValueChange = { editingText = it },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                placeholder = { Text(stringResource(R.string.config_placeholder_yaml)) },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                maxLines = Int.MAX_VALUE,
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { editingText = state.yamlText ?: "" },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(R.string.config_action_reset))
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                Button(
                                    onClick = { viewModel.saveRawConfig(editingText) },
                                    modifier = Modifier.weight(1f),
                                    enabled = !state.isSaving,
                                ) {
                                    if (state.isSaving) {
                                        SpinningIndicator(modifier = Modifier.width(16.dp))
                                    } else {
                                        Text(stringResource(R.string.config_action_save))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
