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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.SubscriptionCurrent
import com.m57.hermescontrol.data.model.SubscriptionStateResponse
import com.m57.hermescontrol.data.model.SubscriptionTier
import com.m57.hermescontrol.data.model.UsageBar
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing
import java.util.Locale

@Composable
fun BillingScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: BillingViewModel = viewModel { BillingViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        onDispose { viewModel.clearTransientState() }
    }

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
                    onDismissPreview = viewModel::clearPreview,
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
    onDismissPreview: () -> Unit,
) {
    val subscription = state.subscription
    val usage = state.usage
    val uriHandler = LocalUriHandler.current

    var showCancelDialog by remember { mutableStateOf(false) }

    if (showCancelDialog) {
        val currentPlanName = subscription?.current?.tier_name ?: stringResource(R.string.billing_plan)
        val effectiveDate =
            subscription?.current?.cancellation_effective_display
                ?: subscription?.current?.cycle_ends_at
                ?: stringResource(R.string.billing_renews, "")
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text(stringResource(R.string.billing_cancel_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.billing_cancel_dialog_message,
                        currentPlanName,
                        effectiveDate,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCancelDialog = false
                        onChange(null, true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.billing_cancel_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    val preview = state.preview
    if (preview != null) {
        val targetTier =
            subscription?.tiers?.find {
                it.tier_id == preview.target_tier_id || it.tier_id == state.previewingTierId
            }
        val targetName =
            preview.target_tier_name
                ?: targetTier?.name
                ?: preview.subscription_type_name
                ?: stringResource(R.string.billing_plan)
        val effect = preview.effect ?: "scheduled"
        val isUpgrade = effect == "charge_now"
        val isBlocked = effect == "blocked"
        val isNoOp = effect == "no_op"

        AlertDialog(
            onDismissRequest = onDismissPreview,
            title = { Text(stringResource(R.string.billing_preview_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = targetName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    when {
                        isUpgrade -> {
                            val amountStr =
                                preview.amount_due_now_cents?.let {
                                    String.format(Locale.US, "$%.2f", it / 100.0)
                                } ?: preview.price ?: ""
                            Text(
                                stringResource(
                                    R.string.billing_preview_upgrade_message,
                                    targetName,
                                    amountStr,
                                ),
                            )
                        }

                        isBlocked -> {
                            Text(
                                text = preview.reason ?: preview.message ?: "This change cannot be made here.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        isNoOp -> {
                            Text(text = "You are already on $targetName — nothing to change.")
                        }

                        else -> {
                            val whenDate = preview.effective_at ?: "the end of the billing period"
                            Text(
                                stringResource(
                                    R.string.billing_preview_downgrade_message,
                                    targetName,
                                    whenDate,
                                ),
                            )
                        }
                    }
                    if (preview.monthly_credits_delta != null) {
                        Text(
                            text =
                                stringResource(
                                    R.string.billing_preview_credits_delta,
                                    preview.monthly_credits_delta,
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                if (!isBlocked && !isNoOp) {
                    Button(
                        enabled = !state.isActionInFlight,
                        onClick = {
                            val tid = preview.target_tier_id ?: state.previewingTierId ?: ""
                            if (isUpgrade) {
                                onUpgrade(tid)
                            } else {
                                onChange(tid, null)
                            }
                        },
                    ) {
                        Text(
                            if (isUpgrade) {
                                stringResource(R.string.billing_preview_confirm_upgrade)
                            } else {
                                stringResource(R.string.billing_preview_confirm_change)
                            },
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissPreview) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = listContentPadding,
        verticalArrangement = listItemSpacing,
    ) {
        val sub = subscription
        if (sub != null && sub.context == "team") {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.billing_team_notice, sub.org_name ?: "Team"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        if (sub != null && sub.logged_in == true && sub.current != null) {
            item {
                PlanCard(
                    subscription = sub.current,
                    allTiers = sub.tiers,
                    orgName = if (sub.context != "team") sub.org_name else null,
                    role = if (sub.context != "team") sub.role else null,
                    canChangePlan = sub.can_change_plan == true,
                    portalUrl = sub.portal_url,
                    isActionInFlight = state.isActionInFlight,
                    onResume = onResume,
                    onCancelRequest = { showCancelDialog = true },
                    onOpenPortal = { url -> uriHandler.openUri(url) },
                )
            }
        } else if (sub != null) {
            item {
                NoActivePlanCard(
                    subscription = sub,
                    onOpenPortal = sub.portal_url?.let { url -> { uriHandler.openUri(url) } },
                )
            }
        }

        if (state.errorMessage != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = state.errorMessage,
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
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = state.actionMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        val actionUrl = extractUrl(state.actionMessage)
                        if (actionUrl != null) {
                            Button(
                                onClick = { uriHandler.openUri(actionUrl) },
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                Text(stringResource(R.string.billing_open_portal))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.padding(start = 4.dp).size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (sub != null && sub.tiers.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.billing_available_plans)) }
            val currentTier = sub.tiers.find { it.is_current == true || it.tier_id == sub.current?.tier_id }
            val currentTierOrder = currentTier?.tier_order ?: 0.0
            val pendingDowngradeName = sub.current?.pending_downgrade_tier_name

            items(sub.tiers, key = { it.tier_id ?: it.name ?: "" }) { tier ->
                TierCard(
                    tier = tier,
                    isCurrent = tier.is_current == true || tier.tier_id == sub.current?.tier_id,
                    isPendingTarget = tier.name != null && tier.name == pendingDowngradeName,
                    currentTierOrder = currentTierOrder,
                    canChangePlan = sub.can_change_plan == true,
                    isActionInFlight = state.isActionInFlight,
                    onSelect = {
                        tier.tier_id?.let { onPreview(it) }
                    },
                    onOpenPortal = sub.portal_url?.let { url -> { uriHandler.openUri(url) } },
                )
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
private fun PlanCard(
    subscription: SubscriptionCurrent,
    allTiers: List<SubscriptionTier>,
    orgName: String?,
    role: String?,
    canChangePlan: Boolean,
    portalUrl: String?,
    isActionInFlight: Boolean,
    onResume: () -> Unit,
    onCancelRequest: () -> Unit,
    onOpenPortal: (String) -> Unit,
) {
    val tierPrice = allTiers.find { it.tier_id == subscription.tier_id }?.dollars_per_month_display
    val hasPendingChange = subscription.cancel_at_period_end == true || subscription.pending_downgrade_tier_name != null

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = subscription.tier_name ?: stringResource(R.string.billing_free_plan),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (tierPrice != null) {
                        Text(
                            text = stringResource(R.string.billing_price_per_month, tierPrice),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                StatusBadge(
                    text = stringResource(R.string.billing_current_plan_badge),
                    status = StatusBadgeType.SUCCESS,
                )
            }

            if (orgName != null) {
                Text(
                    text = if (role != null) "Org: $orgName ($role)" else "Org: $orgName",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (subscription.credits_remaining != null) {
                Text(
                    text =
                        stringResource(
                            R.string.billing_credits_remaining,
                            subscription.credits_remaining,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (subscription.cycle_ends_at != null && !hasPendingChange) {
                Text(
                    text = stringResource(R.string.billing_renews, subscription.cycle_ends_at),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (subscription.pending_downgrade_tier_name != null) {
                val effectiveDate = subscription.pending_downgrade_display ?: subscription.pending_downgrade_at ?: ""
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text =
                                    stringResource(
                                        R.string.billing_scheduled_downgrade_banner,
                                        subscription.pending_downgrade_tier_name,
                                        effectiveDate,
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                        if (canChangePlan) {
                            Button(
                                onClick = onResume,
                                enabled = !isActionInFlight,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.billing_undo_change))
                            }
                        }
                    }
                }
            } else if (subscription.cancel_at_period_end == true) {
                val effectiveDate =
                    subscription.cancellation_effective_display
                        ?: subscription.cancellation_effective_at
                        ?: ""
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text =
                                    stringResource(
                                        R.string.billing_scheduled_cancellation_banner,
                                        effectiveDate,
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        if (canChangePlan) {
                            Button(
                                onClick = onResume,
                                enabled = !isActionInFlight,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.billing_resume))
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (canChangePlan && !hasPendingChange) {
                    OutlinedButton(
                        onClick = onCancelRequest,
                        enabled = !isActionInFlight,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.billing_cancel_subscription))
                    }
                }
                if (portalUrl != null) {
                    OutlinedButton(
                        onClick = { onOpenPortal(portalUrl) },
                        modifier =
                            if (canChangePlan && !hasPendingChange) {
                                Modifier.weight(1f)
                            } else {
                                Modifier.fillMaxWidth()
                            },
                    ) {
                        Text(stringResource(R.string.billing_manage_portal))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.padding(start = 4.dp).size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TierCard(
    tier: SubscriptionTier,
    isCurrent: Boolean,
    isPendingTarget: Boolean,
    currentTierOrder: Double,
    canChangePlan: Boolean,
    isActionInFlight: Boolean,
    onSelect: () -> Unit,
    onOpenPortal: ((String) -> Unit)?,
) {
    val tierOrder = tier.tier_order ?: 0.0
    val isUpgrade = tierOrder > currentTierOrder
    val isDowngrade = tierOrder < currentTierOrder && currentTierOrder > 0.0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isCurrent) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
            ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tier.name ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text =
                            stringResource(
                                R.string.billing_price_per_month,
                                tier.dollars_per_month_display ?: "$0",
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                when {
                    isCurrent -> {
                        StatusBadge(
                            text = stringResource(R.string.billing_current_plan_badge),
                            status = StatusBadgeType.SUCCESS,
                        )
                    }

                    isPendingTarget -> {
                        StatusBadge(
                            text = stringResource(R.string.billing_scheduled_badge),
                            status = StatusBadgeType.WARNING,
                        )
                    }

                    canChangePlan -> {
                        Button(
                            onClick = onSelect,
                            enabled = !isActionInFlight && tier.is_enabled != false,
                        ) {
                            Text(
                                when {
                                    isUpgrade -> stringResource(R.string.billing_upgrade)
                                    isDowngrade -> stringResource(R.string.billing_downgrade)
                                    else -> stringResource(R.string.billing_choose)
                                },
                            )
                        }
                    }

                    onOpenPortal != null -> {
                        OutlinedButton(
                            onClick = { onOpenPortal("https://portal.nousresearch.com") },
                        ) {
                            Text(stringResource(R.string.billing_choose))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.padding(start = 4.dp).size(14.dp),
                            )
                        }
                    }
                }
            }

            if (tier.monthly_credits != null && tier.monthly_credits != "0") {
                Text(
                    text =
                        stringResource(
                            R.string.billing_monthly_credits_suffix,
                            tier.monthly_credits,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NoActivePlanCard(
    subscription: SubscriptionStateResponse,
    onOpenPortal: (() -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
            )
            if (onOpenPortal != null) {
                OutlinedButton(
                    onClick = onOpenPortal,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(stringResource(R.string.billing_open_portal))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 4.dp).size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageBarRow(
    label: String,
    bar: UsageBar,
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
        Icon(
            imageVector = Icons.Filled.AccountBalanceWallet,
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

private fun extractUrl(message: String): String? {
    val prefix = "open: "
    val idx = message.indexOf(prefix)
    return if (idx != -1) {
        message.substring(idx + prefix.length).trim()
    } else if (message.startsWith("http://") || message.startsWith("https://")) {
        message.trim()
    } else {
        null
    }
}
