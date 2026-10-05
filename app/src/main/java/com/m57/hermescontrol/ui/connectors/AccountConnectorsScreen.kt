package com.m57.hermescontrol.ui.connectors

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.ExternalActivityLifecycleGuard
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.ConnectorAccount
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.notification.NotificationHelper
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.chat.components.ConnectionSetupSheet
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.util.ConnectorUrlValidator

@Composable
fun AccountConnectorsScreen(
    onBack: () -> Unit,
    vm: AccountConnectorsViewModel = viewModel { AccountConnectorsViewModel() },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val spacing = LocalSpacing.current
    var pendingRemoval by remember(state.scope) { mutableStateOf<ConnectorAccount?>(null) }
    var browserOperation by remember(state.scope) { mutableStateOf<String?>(null) }
    var departed by remember(state.scope) { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner, state.scope) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE && browserOperation != null) departed = true
                if (event == Lifecycle.Event.ON_RESUME && departed) {
                    val opId = browserOperation
                    browserOperation = null
                    departed = false
                    ExternalActivityLifecycleGuard.externalActivityReturned()
                    if (opId != null) vm.onBrowserReturn(opId)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (browserOperation != null) ExternalActivityLifecycleGuard.externalActivityReturned()
        }
    }
    HermesScaffold(
        title = { Text(stringResource(R.string.account_connectors_title)) },
        navigationIcon = NavIcon.Back(onBack),
        drawerGesturesEnabled = false,
        onRefresh = vm::refresh,
    ) {
        when {
            state.loading -> {
                LoadingState()
            }

            state.unavailable -> {
                EmptyState(
                    title = stringResource(R.string.account_connectors_unavailable_title),
                    subtitle = stringResource(R.string.account_connectors_unavailable_description),
                    icon = Icons.Filled.LinkOff,
                    actionLabel = stringResource(R.string.account_connectors_check_again),
                    onAction = vm::refresh,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }

            state.error != null && state.catalog.isEmpty() && state.accounts.isEmpty() -> {
                ErrorState(message = state.error.orEmpty(), onRetry = vm::refresh)
            }

            state.catalog.isEmpty() && state.accounts.isEmpty() -> {
                EmptyState(title = stringResource(R.string.account_connectors_no_accounts))
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                    items(state.catalog, key = { "catalog-${it.slug}" }) { connector ->
                        val current = state.connectorStates.firstOrNull { it.connector == connector.slug }
                        val policy = state.policy
                        val enabled = policy?.connectorEnabled(connector.slug) ?: current?.enabled
                        val locked = policy?.inherited?.any { !it.connectorEnabled(connector.slug) } == true
                        val editable = policy?.member != null && !state.busy && !locked
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(spacing.md),
                                verticalArrangement = Arrangement.spacedBy(spacing.sm),
                            ) {
                                Text(connector.name, style = MaterialTheme.typography.titleMedium)
                                if (connector.description.isNotBlank()) Text(connector.description)
                                Text(
                                    current?.connectionStatus
                                        ?: stringResource(R.string.account_connectors_not_connected),
                                )
                                // Stack actions so large fonts and narrow phones never hide a control.
                                Button(
                                    onClick = { vm.connect(connector.slug, reconnect = current?.isConnected == true) },
                                    enabled = !state.busy && connection.operation == null,
                                ) {
                                    Text(
                                        stringResource(
                                            if (current?.isConnected ==
                                                true
                                            ) {
                                                R.string.account_connectors_reconnect
                                            } else {
                                                R.string.account_connectors_connect
                                            },
                                        ),
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = enabled == true,
                                        enabled = editable,
                                        onCheckedChange = { vm.setEnabled(connector.slug, it) },
                                    )
                                    Text(stringResource(R.string.account_connectors_enabled))
                                }
                                if (locked ||
                                    policy?.member == null
                                ) {
                                    Text(stringResource(R.string.account_connectors_policy_locked))
                                }
                                OutlinedButton(
                                    onClick = { vm.loadTools(connector.slug) },
                                    enabled = connector.slug !in state.toolsLoading,
                                ) { Text(stringResource(R.string.account_connectors_tools)) }
                                state.tools[connector.slug]?.let { tools ->
                                    if (tools.isEmpty()) Text(stringResource(R.string.account_connectors_no_tools))
                                    tools.forEach { tool ->
                                        val disabled =
                                            tool.slug in
                                                policy
                                                    ?.member
                                                    ?.tools
                                                    ?.get(connector.slug)
                                                    .orEmpty()
                                        val toolLocked =
                                            policy?.inherited?.any { !it.toolEnabled(connector.slug, tool) } == true
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(
                                                checked = enabled == true && !disabled && !toolLocked,
                                                enabled = editable && enabled == true && !toolLocked,
                                                onCheckedChange = { checked ->
                                                    val updated =
                                                        state.policy
                                                            ?.member
                                                            ?.tools
                                                            ?.get(
                                                                connector.slug,
                                                            ).orEmpty()
                                                            .toMutableSet()
                                                    if (checked) updated.remove(tool.slug) else updated.add(tool.slug)
                                                    vm.setDisabledTools(connector.slug, updated.toList())
                                                },
                                            )
                                            Column(Modifier.weight(1f)) {
                                                Text(tool.name)
                                                if (toolLocked) {
                                                    Text(
                                                        stringResource(R.string.account_connectors_policy_locked),
                                                    )
                                                }
                                                Text(tool.description, style = MaterialTheme.typography.bodySmall)
                                                Text(tool.facet, style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            stringResource(R.string.account_connectors_accounts),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    items(state.accounts, key = { "account-${it.connectionId}" }) { account ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(spacing.md),
                                verticalArrangement = Arrangement.spacedBy(spacing.sm),
                            ) {
                                Text(account.label, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    state.catalog.firstOrNull { it.slug == account.connector }?.name
                                        ?: account.connector,
                                )
                                Text(account.status)
                                account.statusReason?.let { Text(it) }
                                account.alias?.let { Text(it) }
                                OutlinedButton(onClick = { pendingRemoval = account }, enabled = !state.busy) {
                                    Text(stringResource(R.string.account_connectors_remove))
                                }
                            }
                        }
                    }
                    if (state.accounts.isEmpty()) item { Text(stringResource(R.string.account_connectors_no_accounts)) }
                }
            }
        }
    }
    ConnectionSetupSheet(
        state = connection,
        onRespond = vm::respond,
        onContinue = vm::continueOperation,
        onDismiss = vm::continueOperation,
        onOpenBrowser = { opId, url ->
            if (ConnectorUrlValidator.isValidHttpsUrl(url)) {
                try {
                    ExternalActivityLifecycleGuard.launchExternalActivity(
                        acquireConnectionLease = HermesWsClient::acquireExternalActivityConnectionLease,
                        releaseConnectionLease = HermesWsClient::releaseExternalActivityConnectionLease,
                        prepareForBackground = { NotificationHelper.start(context) },
                        cleanupAfterLaunchFailure = { NotificationHelper.stop(context) },
                    ) {
                        browserOperation = opId
                        departed = false
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                } catch (_: Exception) {
                    browserOperation = null
                    ExternalActivityLifecycleGuard.externalActivityReturned()
                    vm.browserLaunchFailed()
                }
            }
        },
    )
    pendingRemoval?.let { account ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.account_connectors_remove_confirm_title)) },
            text = { Text(stringResource(R.string.account_connectors_remove_confirm_message, account.label)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.remove(account.connectionId)
                    pendingRemoval = null
                }, enabled = !state.busy) { Text(stringResource(R.string.account_connectors_remove)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingRemoval = null },
                ) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
