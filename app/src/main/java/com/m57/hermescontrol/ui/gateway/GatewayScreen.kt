package com.m57.hermescontrol.ui.gateway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.ToastEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GatewayScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: GatewayViewModel = viewModel { GatewayViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadStatus()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_gateway)) },
        navigationIcon = NavIcon.Menu(onOpenDrawer),
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadStatus() },
    ) { paddingValues ->
        when {
            state.isLoading && state.status == null -> {
                LoadingState(modifier = Modifier.padding(paddingValues))
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadStatus() },
                    modifier = Modifier.padding(paddingValues),
                )
            }

            else -> {
                Box(Modifier.fillMaxSize()) {
                    if (state.isLoading && state.status == null) {
                        CircularProgressIndicator()
                    } else if (state.errorMessage != null && state.status == null) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = state.errorMessage ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { viewModel.loadStatus() }) {
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    } else {
                        val status = state.status
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            val isRunning = status?.gateway_running == true

                            // Status Overview Card
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors =
                                    CardDefaults.cardColors(
                                        containerColor =
                                            if (isRunning) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.errorContainer
                                            },
                                    ),
                            ) {
                                Column(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        text =
                                            if (isRunning) {
                                                stringResource(
                                                    R.string.gateway_status_running,
                                                )
                                            } else {
                                                stringResource(R.string.gateway_status_stopped)
                                            },
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        color =
                                            if (isRunning) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onErrorContainer
                                            },
                                    )
                                    status?.version?.let {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = stringResource(R.string.gateway_label_version, it),
                                            style = MaterialTheme.typography.bodySmall,
                                            color =
                                                if (isRunning) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                                } else {
                                                    MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                                                },
                                        )
                                    }
                                }
                            }

                            // Action Controls Card
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.gateway_sec_controls),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Button(
                                            onClick = { viewModel.startGateway() },
                                            modifier = Modifier.weight(1f),
                                            enabled = !isRunning && !state.isActionRunning,
                                            colors =
                                                ButtonDefaults.buttonColors(
                                                    containerColor = Color(0xFF4CAF50), // Green for start
                                                    contentColor = Color.White,
                                                ),
                                        ) {
                                            Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(stringResource(R.string.gateway_action_start))
                                        }

                                        Button(
                                            onClick = { viewModel.stopGateway() },
                                            modifier = Modifier.weight(1f),
                                            enabled = isRunning && !state.isActionRunning,
                                            colors =
                                                ButtonDefaults.buttonColors(
                                                    containerColor = Color(0xFFF44336), // Red for stop
                                                    contentColor = Color.White,
                                                ),
                                        ) {
                                            Text(stringResource(R.string.gateway_action_stop))
                                        }
                                    }

                                    Button(
                                        onClick = { viewModel.restartGateway() },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !state.isActionRunning,
                                        colors =
                                            ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.secondary,
                                            ),
                                    ) {
                                        Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.gateway_action_restart))
                                    }

                                    if (state.isActionRunning) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        CircularProgressIndicator(
                                            modifier = Modifier.align(Alignment.CenterHorizontally),
                                        )
                                    }
                                }
                            }

                            // Platforms Card
                            status?.gateway_platforms?.let { platforms ->
                                if (platforms.isNotEmpty()) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text(
                                                text = stringResource(R.string.gateway_sec_active_platforms),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold,
                                            )

                                            platforms.forEach { (platform, pStatus) ->
                                                Row(
                                                    modifier =
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 4.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(
                                                        text = platform.replaceFirstChar { it.uppercase() },
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        fontWeight = FontWeight.Medium,
                                                    )
                                                    val stateText = pStatus.state?.uppercase() ?: "UNKNOWN"
                                                    val badgeColor =
                                                        when (stateText) {
                                                            "RUNNING" -> Color(0xFF4CAF50)
                                                            "STOPPED" -> Color(0xFFF44336)
                                                            "ERROR" -> Color(0xFFFF9800)
                                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                        }
                                                    Text(
                                                        text = stateText,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = badgeColor,
                                                        fontWeight = FontWeight.Bold,
                                                    )
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
        }
    }
}
