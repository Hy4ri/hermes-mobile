package com.m57.hermescontrol.ui.plugins

import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: PluginsViewModel = viewModel { PluginsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }

    val filteredPlugins =
        remember(query, state.plugins) {
            state.plugins.filter { plugin ->
                plugin.name.contains(query, ignoreCase = true) ||
                    plugin.description?.contains(query, ignoreCase = true) == true
            }
        }

    LaunchedEffect(Unit) {
        viewModel.loadPlugins()
    }

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_plugins)) },
        onOpenDrawer = onOpenDrawer,
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadPlugins() },
    ) { paddingValues ->
        when {
            state.isLoading && state.plugins.isEmpty() -> {
                LoadingState(modifier = Modifier.padding(paddingValues))
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadPlugins() },
                    modifier = Modifier.padding(paddingValues),
                )
            }

            state.plugins.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.plugins_empty_title),
                    subtitle = stringResource(R.string.plugins_empty_desc),
                    onAction = { viewModel.loadPlugins() },
                    actionLabel = stringResource(R.string.content_desc_refresh),
                    modifier = Modifier.padding(paddingValues),
                )
            }

            else -> {
                Box(Modifier.fillMaxSize()) {
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
                            contentPadding = listContentPadding,
                            verticalArrangement = listItemSpacing,
                        ) {
                            item {
                                SearchBar(
                                    query = query,
                                    onQueryChange = { query = it },
                                    placeholder = "Search plugins...",
                                )
                            }
                            items(filteredPlugins) { plugin ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(text = plugin.name, style = MaterialTheme.typography.titleMedium)
                                                plugin.version?.let {
                                                    Text(
                                                        text = "v$it",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                            if (plugin.installed) {
                                                Switch(
                                                    checked = plugin.enabled,
                                                    onCheckedChange = { viewModel.togglePlugin(plugin) },
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = plugin.description ?: stringResource(R.string.plugins_no_desc),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                        ) {
                                            if (plugin.installed) {
                                                OutlinedButton(onClick = { viewModel.updatePlugin(plugin.name) }) {
                                                    Text(stringResource(R.string.plugins_action_update))
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                OutlinedButton(
                                                    onClick = { viewModel.uninstallPlugin(plugin.name) },
                                                    colors =
                                                        androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                                            contentColor = MaterialTheme.colorScheme.error,
                                                        ),
                                                ) {
                                                    Text(stringResource(R.string.plugins_action_uninstall))
                                                }
                                            } else {
                                                Button(onClick = { viewModel.installPlugin(plugin.name) }) {
                                                    Text(stringResource(R.string.plugins_action_install))
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
