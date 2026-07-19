package com.m57.hermescontrol.ui.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.SubscriptionCurrent
import com.m57.hermescontrol.data.model.SubscriptionStateResponse
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing

@Composable
fun BillingScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: BillingViewModel = viewModel { BillingViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (state.subscription == null && state.usage == null && !state.featureUnavailable) {
            viewModel.load()
        }
    }

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_billing)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.load() },
        modifier = modifier,
    ) {
        when {
            state.featureUnavailable -> {
                FeatureUnavailableState(onRetry = { viewModel.load() })
            }

            state.isLoading && state.subscription == null && state.usage == null -> {
                LoadingState(modifier = Modifier.fillMaxSize())
            }

            state.errorMessage != null && state.subscription == null && state.usage == null -> {
                ErrorState(
                    message = state.errorMessage ?: stringResource(R.string.error_unknown),
                    onRetry = { viewModel.load() },
                )
            }

            else -> {
                BillingContent(
                    state = state,
                    onUpgrade = viewModel::upgrade,
                    onChange = viewModel::change,
                    onResume = viewModel::resume,
                    onPreview = viewModel::preview,
                )
            }
        }
    }
}

@Composable
private fun BillingContent(
    state: BillingUiState,
    onUpgrade: (String) -> Unit,
    onChange: (String?, Boolean?) -> Unit,
    onResume: () -> Unit,
    onPreview: (String) -> Unit,
) {
    val subscription = state.subscription
    val usage = state.usage
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = listContentPadding,
        verticalArrangement = listItemSpacing,
    ) {
        val sub = subscription
        if (sub != null && sub.logged_in == true && sub.current != null) {
            item { PlanCard(sub.current) }
            if (sub.can_change_plan == true) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onChange(null, true) },
                            enabled = !state.isActionInFlight,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.billing_cancel))
                        }
                    }
                }
            }
        } else if (sub != null) {
            item { NoActivePlanCard(sub) }
        }

        if (state.errorMessage != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = state.errorMessage ?: "",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        if (state.actionMessage != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = state.actionMessage ?: "",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (usage != null && usage.available == true && (usage.plan_bar != null || usage.topup_bar != null)) {
            item { SectionTitle(stringResource(R.string.billing_usage)) }
            usage.plan_bar?.let { bar ->
                item { UsageBarRow(label = stringResource(R.string.billing_plan), bar = bar) }
            }
            usage.topup_bar?.let { bar ->
                item { UsageBarRow(label = stringResource(R.string.billing_topup), bar = bar) }
            }
        }
    }
}

@Composable
private fun PlanCard(subscription: SubscriptionCurrent) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = subscription.tier_name ?: stringResource(R.string.billing_free_plan),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (subscription.credits_remaining != null) {
                Text(
                    text =
                        stringResource(
                            R.string.billing_credits_remaining,
                            subscription.credits_remaining,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (subscription.cycle_ends_at != null) {
                Text(
                    text = stringResource(R.string.billing_renews, subscription.cycle_ends_at),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (subscription.pending_downgrade_display != null) {
                Text(
                    text = subscription.pending_downgrade_display ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun NoActivePlanCard(subscription: SubscriptionStateResponse) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.billing_free_plan),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text =
                    if (subscription.logged_in == true) {
                        stringResource(R.string.billing_no_active_plan)
                    } else {
                        stringResource(R.string.billing_login_required)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun UsageBarRow(
    label: String,
    bar: com.m57.hermescontrol.data.model.UsageBar,
) {
    val fraction = (bar.fill_fraction ?: 0.0).toFloat().coerceIn(0f, 1f)
    val summary = bar.remaining_display ?: bar.total_display ?: ""
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun FeatureUnavailableState(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = androidx.compose.material.icons.Icons.Filled.AccountBalanceWallet,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(bottom = 16.dp).size(48.dp),
        )
        ErrorState(
            message = stringResource(R.string.billing_feature_unavailable),
            onRetry = onRetry,
        )
    }
}
