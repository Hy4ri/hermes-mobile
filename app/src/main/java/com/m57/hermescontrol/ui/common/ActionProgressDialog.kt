package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing

/**
 * Reusable progress popup for backend actions tracked by
 * [ActionProgressController]: spinner while running, the live log tail
 * (monospace, auto-scrolled to the newest lines), then a success or failure
 * state with the exit code when the action finishes.
 *
 * Dismissing is always allowed and is cancel-safe — it stops tracking via
 * [ActionProgressController.dismiss]; the backend action keeps running.
 *
 * @param controller the tracker backing this dialog (e.g. owned by a
 *   ViewModel with `viewModelScope`).
 * @param title short human title for the action being tracked, e.g.
 *   "Software update".
 * @param onDismiss optional override; defaults to [ActionProgressController.dismiss].
 */
@Composable
fun ActionProgressDialog(
    controller: ActionProgressController,
    title: String,
    onDismiss: (() -> Unit)? = null,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    if (!state.visible) return

    val dismiss = onDismiss ?: controller::dismiss
    val spacing = LocalSpacing.current
    val statusColors = LocalHermesStatusColors.current

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                // Status row: spinner / success / failure + phase text
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    when (state.phase) {
                        ActionProgressPhase.STARTING,
                        ActionProgressPhase.RUNNING,
                        -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }

                        ActionProgressPhase.SUCCEEDED -> {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = statusColors.success,
                            )
                        }

                        ActionProgressPhase.FAILED -> {
                            Icon(
                                Icons.Filled.Error,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = statusColors.error,
                            )
                        }
                    }
                    // Delegated property — capture locally so the smart cast works.
                    val exitCode = state.exitCode
                    Text(
                        text =
                            when (state.phase) {
                                ActionProgressPhase.STARTING ->
                                    stringResource(R.string.action_progress_starting)

                                ActionProgressPhase.RUNNING ->
                                    stringResource(R.string.action_progress_running)

                                ActionProgressPhase.SUCCEEDED ->
                                    stringResource(R.string.action_progress_succeeded)

                                ActionProgressPhase.FAILED ->
                                    if (exitCode != null) {
                                        stringResource(R.string.action_progress_failed_exit, exitCode)
                                    } else {
                                        stringResource(R.string.action_progress_failed)
                                    }
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                // Live log tail (newest lines; backend returns the last `lines=N`)
                if (state.lines.isNotEmpty()) {
                    val scrollState = rememberScrollState()
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                    ) {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp)
                                    .verticalScroll(scrollState)
                                    .padding(spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            state.lines.takeLast(15).forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // Keep the newest lines in view as the tail grows.
                    LaunchedEffect(state.lines.size) {
                        scrollState.scrollTo(scrollState.maxValue)
                    }
                }

                state.error?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColors.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = dismiss) {
                Text(stringResource(R.string.action_done))
            }
        },
    )
}
