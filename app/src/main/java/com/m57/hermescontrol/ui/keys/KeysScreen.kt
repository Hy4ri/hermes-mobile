package com.m57.hermescontrol.ui.keys

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: KeysViewModel = viewModel { KeysViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.loadKeys()
    }

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    HermesScaffold(
        title = "Keys & Credentials",
        onOpenDrawer = onOpenDrawer,
        onRefresh = { viewModel.loadKeys() },
    ) { paddingValues ->
        when {
            state.isLoading -> {
                LoadingState(modifier = Modifier.padding(paddingValues))
            }
            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadKeys() },
                    modifier = Modifier.padding(paddingValues),
                )
            }
            else -> Box(Modifier.fillMaxSize()) {
                if (state.isLoading) {
                    CircularProgressIndicator()
                } else if (state.errorMessage != null) {
                    Text(
                        text = state.errorMessage ?: "",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.envVars.toList()) { (key, config) ->
                            var isEditing by remember { mutableStateOf(false) }
                            val revealedVal = state.revealedValues[key]
                            val isRevealed = revealedVal != null
                            var editedValue by remember { mutableStateOf(revealedVal ?: "") }

                            val displayValue =
                                if (isRevealed) {
                                    revealedVal.orEmpty()
                                } else if (config.isSet) {
                                    config.redactedValue ?: "********"
                                } else {
                                    "Not configured"
                                }

                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = key,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    if (!config.description.isNullOrBlank()) {
                                        Text(
                                            text = config.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            modifier = Modifier.padding(vertical = 4.dp),
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    if (isEditing) {
                                        OutlinedTextField(
                                            value = editedValue,
                                            onValueChange = { editedValue = it },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                        ) {
                                            Button(onClick = {
                                                viewModel.updateKey(key, editedValue)
                                                isEditing = false
                                            }) {
                                                Text("Save")
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Button(onClick = {
                                                isEditing = false
                                            }) {
                                                Text("Cancel")
                                            }
                                        }
                                    } else {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = displayValue,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Row {
                                                if (config.isSet) {
                                                    IconButton(onClick = {
                                                        if (isRevealed) {
                                                            viewModel.hideKey(key)
                                                        } else {
                                                            viewModel.revealKey(key)
                                                        }
                                                    }) {
                                                        Icon(
                                                            imageVector =
                                                                if (isRevealed) {
                                                                    Icons.Filled.VisibilityOff
                                                                } else {
                                                                    Icons.Filled.Visibility
                                                                },
                                                            contentDescription = "Toggle Visibility",
                                                        )
                                                    }
                                                }
                                                IconButton(onClick = {
                                                    editedValue = if (isRevealed) revealedVal.orEmpty() else ""
                                                    isEditing = true
                                                }) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Edit,
                                                        contentDescription = "Edit",
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
