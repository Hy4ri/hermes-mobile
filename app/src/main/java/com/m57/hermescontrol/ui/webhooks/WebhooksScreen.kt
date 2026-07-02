package com.m57.hermescontrol.ui.webhooks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.WebhookSubscription
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebhooksScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: WebhooksViewModel = viewModel { WebhooksViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }

    val filteredSubscriptions =
        remember(query, state.subscriptions) {
            state.subscriptions.filter { sub ->
                sub.name.contains(query, ignoreCase = true) ||
                    sub.description?.contains(query, ignoreCase = true) == true ||
                    (sub.events?.any { it.contains(query, ignoreCase = true) } == true)
            }
        }

    LaunchedEffect(Unit) {
        viewModel.loadWebhooks()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    // ── Delete Confirmation Dialog ──
    state.deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteDialog() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Delete Subscription")
                }
            },
            text = {
                Text(
                    "Are you sure you want to delete \"${target.name}\"? " +
                        "This action cannot be undone. All events sent to this URL " +
                        "will be rejected.",
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmDeleteSubscription() },
                    enabled = !state.isDeleting,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    if (state.isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissDeleteDialog() },
                    enabled = !state.isDeleting,
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // ── Create Subscription Dialog ──
    if (state.showCreateDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCreateDialog() },
            title = { Text("Create Subscription") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.createFormName,
                        onValueChange = viewModel::updateCreateFormName,
                        label = { Text("Name *") },
                        placeholder = { Text("e.g. github-push") },
                        singleLine = true,
                        isError = state.createFormError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = state.createFormDescription,
                        onValueChange = viewModel::updateCreateFormDescription,
                        label = { Text("Description") },
                        placeholder = { Text("What this webhook does...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = state.createFormEvents,
                        onValueChange = viewModel::updateCreateFormEvents,
                        label = { Text("Events") },
                        placeholder = { Text("comma separated (e.g. push, pull_request)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = state.createFormDeliver,
                        onValueChange = viewModel::updateCreateFormDeliver,
                        label = { Text("Deliver To") },
                        placeholder = { Text("log, telegram, discord, origin, all...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = state.createFormSecret,
                        onValueChange = viewModel::updateCreateFormSecret,
                        label = { Text("Secret") },
                        placeholder = { Text("auto-generated if empty") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    state.createFormError?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.createSubscription() },
                    enabled = !state.isActionRunning,
                ) {
                    if (state.isActionRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissCreateDialog() },
                    enabled = !state.isActionRunning,
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_webhooks)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadWebhooks() },
        actions = {
            IconButton(onClick = { viewModel.showCreateDialog() }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add subscription",
                )
            }
        },
    ) { paddingValues ->
        when {
            state.isLoading && state.baseUrl == null -> {
                LoadingState(modifier = Modifier.padding(paddingValues))
            }

            state.errorMessage != null && state.baseUrl == null -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadWebhooks() },
                    modifier = Modifier.padding(paddingValues),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listContentPadding,
                    verticalArrangement = listItemSpacing,
                ) {
                    // Global Status Switch
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        if (state.enabled) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                ),
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.webhooks_sec_status),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    state.baseUrl?.let {
                                        Text(
                                            text = "Receiver URL: $it",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Switch(
                                    checked = state.enabled,
                                    onCheckedChange = { viewModel.toggleWebhooks(it) },
                                )
                            }
                        }
                    }

                    // Subscriptions header
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.webhooks_sec_subscriptions),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "(${state.subscriptions.size})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    item {
                        SearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = "Search subscriptions...",
                        )
                    }

                    if (filteredSubscriptions.isEmpty()) {
                        item {
                            EmptyState(
                                title =
                                    if (state.subscriptions.isEmpty()) {
                                        "No subscriptions"
                                    } else {
                                        "No matching subscriptions"
                                    },
                                subtitle =
                                    if (state.subscriptions.isEmpty()) {
                                        stringResource(R.string.webhooks_empty_desc)
                                    } else {
                                        "Try adjusting your search."
                                    },
                            )
                        }
                    } else {
                        items(filteredSubscriptions, key = { it.name }) { sub ->
                            SubscriptionCard(
                                sub = sub,
                                isToggling = state.togglingName == sub.name,
                                onToggle = { enabled -> viewModel.toggleSubscription(sub.name, enabled) },
                                onDelete = { viewModel.promptDeleteSubscription(sub) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionCard(
    sub: WebhookSubscription,
    isToggling: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val isEnabled = sub.enabled ?: true

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isEnabled) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    },
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header row: Name + Toggle + Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = sub.name.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color =
                            if (isEnabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (sub.secret_set == true) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "Secret configured",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // Delivery target badge
                    sub.deliver?.let { deliver ->
                        if (deliver.isNotBlank() && deliver != "log") {
                            Spacer(modifier = Modifier.width(6.dp))
                            StatusBadge(
                                text = deliver,
                                status = StatusBadgeType.INFO,
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isToggling) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = onToggle,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete subscription",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            // Description
            sub.description?.let { desc ->
                if (desc.isNotBlank()) {
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Event chips
            sub.events?.let { events ->
                if (events.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        events.forEach { event ->
                            SuggestionChip(
                                onClick = {},
                                label = {
                                    Text(
                                        text = event,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            // URL
            Text(
                text = sub.url,
                style =
                    MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
