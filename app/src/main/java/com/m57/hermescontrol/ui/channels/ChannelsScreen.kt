package com.m57.hermescontrol.ui.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.EnvVarField
import com.m57.hermescontrol.data.model.MessagingPlatform
import com.m57.hermescontrol.data.model.MessagingPlatformUpdate
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.channels.components.PlatformCard
import com.m57.hermescontrol.ui.channels.components.TelegramOnboardingDialog
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing

// ── Validation helpers ──────────────────────────────────────────────────

private val SLACK_MEMBER_ID_RE = Regex("^[UW][A-Z0-9]{2,}$")
private val SLACK_TOKEN_PREFIXES =
    mapOf(
        "SLACK_BOT_TOKEN" to "xoxb-",
        "SLACK_APP_TOKEN" to "xapp-",
    )

private fun validateEnvField(
    field: EnvVarField,
    value: String,
): String? {
    val trimmed = value.trim()
    if (!trimmed.isNotEmpty()) return null
    val expectedPrefix = SLACK_TOKEN_PREFIXES[field.key]
    if (expectedPrefix != null && !trimmed.startsWith(expectedPrefix)) {
        return "${field.prompt ?: field.key} must start with $expectedPrefix"
    }
    if (field.key == "SLACK_ALLOWED_USERS") {
        val parts = trimmed.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val invalid = parts.firstOrNull { it != "*" && !SLACK_MEMBER_ID_RE.matches(it) }
        if (invalid != null) {
            return "$invalid does not look like a Slack member ID. Use IDs like U01ABC2DEF3."
        }
    }
    return null
}

// ── State → badge colour / label  ──────────────────────────────────────

// ── Main screen  ──────────────────────────────────────────────────────

@Composable
fun ChannelsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: ChannelsViewModel = viewModel { ChannelsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    val filteredPlatforms =
        remember(query, state.platforms) {
            state.platforms.filter { platform ->
                platform.name.contains(query, ignoreCase = true)
            }
        }

    LaunchedEffect(Unit) {
        viewModel.loadPlatforms()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    // ── Telegram onboarding dialog ────────────────────────────────
    if (state.onboardingPhase != OnboardingPhase.IDLE) {
        TelegramOnboardingDialog(
            state = state,
            onDismiss = { viewModel.cancelTelegramOnboarding() },
            onAddAllowedId = { viewModel.addOnboardingAllowedId() },
            onRemoveAllowedId = { viewModel.removeOnboardingAllowedId(it) },
            onNewAllowedIdChange = { viewModel.setOnboardingNewAllowedId(it) },
            onApply = { viewModel.applyTelegramOnboarding() },
        )
    }

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_channels)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadPlatforms() },
    ) { paddingValues ->
        when {
            state.isLoading && state.platforms.isEmpty() -> {
                SkeletonListState(modifier = Modifier.padding(paddingValues))
            }

            state.errorMessage != null && state.platforms.isEmpty() -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadPlatforms() },
                    modifier = Modifier.padding(paddingValues),
                )
            }

            state.platforms.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.channels_empty_title),
                    subtitle = stringResource(R.string.channels_empty_desc),
                    actionLabel = stringResource(R.string.empty_action_connect_telegram),
                    onAction = { viewModel.startTelegramOnboarding() },
                    modifier = Modifier.padding(paddingValues),
                )
            }

            else -> {
                val gatewayRunning = state.platforms.firstOrNull()?.gatewayRunning ?: false

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listContentPadding,
                    verticalArrangement = listItemSpacing,
                ) {
                    // ── Restart banner ──
                    if (state.restartNeeded) {
                        item(key = "restart_banner") {
                            RestartBanner(
                                isRestarting = state.isRestarting,
                                onRestart = viewModel::restartGateway,
                            )
                        }
                    }

                    // ── Gateway-not-running hint ──
                    if (!gatewayRunning && !state.restartNeeded) {
                        item(key = "gateway_offline") {
                            GatewayOfflineBanner(gatewayCommand = state.gatewayStartCommand)
                        }
                    }

                    // ── Search bar ──
                    item(key = "search") {
                        SearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = "Search channels…",
                        )
                    }

                    // ── Channel cards ──
                    items(filteredPlatforms, key = { it.id }) { platform ->
                        PlatformCard(
                            platform = platform,
                            isToggling = state.togglingId == platform.id,
                            isTesting = state.testingId == platform.id,
                            isRemoving = state.removingId == platform.id,
                            onToggle = { enabled -> viewModel.togglePlatform(platform.id, enabled) },
                            onTest = { viewModel.testPlatform(platform.id) },
                            onSave = { env -> viewModel.configurePlatform(platform.id, env) },
                            onRemove = { viewModel.removePlatform(platform.id) },
                            onStartOnboarding = { viewModel.startTelegramOnboarding() },
                        )
                    }

                    // ── Admin section ──
                    item(key = "admin_section") {
                        AdminSection(
                            envPath = state.envPath,
                        )
                    }
                }
            }
        }
    }
}

// ── Restart banner  ───────────────────────────────────────────────────

@Composable
private fun RestartBanner(
    isRestarting: Boolean,
    onRestart: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.channels_restart_banner),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = onRestart,
                enabled = !isRestarting,
            ) {
                if (isRestarting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = stringResource(R.string.channels_action_restart_now),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

// ── Gateway offline banner  ───────────────────────────────────────────

@Composable
private fun GatewayOfflineBanner(gatewayCommand: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "The gateway is not running. Configure channels here, then start the gateway with:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = LocalHermesStatusColors.current.warningContainer,
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    text = "  $gatewayCommand",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// ── Platform card  ────────────────────────────────────────────────────

// ── Configure form  ───────────────────────────────────────────────────

@Composable
internal fun ConfigureForm(
    platform: MessagingPlatform,
    onSave: (MessagingPlatformUpdate) -> Unit,
    onClose: () -> Unit,
) {
    val envFields = platform.envVars.orEmpty()
    val inputValues = remember { mutableStateMapOf<String, String>() }
    val fieldErrors = remember { mutableStateMapOf<String, String>() }

    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.channels_sec_settings),
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(modifier = Modifier.height(8.dp))

    envFields.forEach { field ->
        OutlinedTextField(
            value = inputValues[field.key].orEmpty(),
            onValueChange = {
                inputValues[field.key] = it
                fieldErrors.remove(field.key)
            },
            label = { Text(field.prompt ?: field.key) },
            placeholder = {
                if (field.isSet) {
                    Text(field.redactedValue ?: "******** (Already Configured)")
                }
            },
            supportingText = {
                fieldErrors[field.key]?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (field.help != null && !fieldErrors.containsKey(field.key)) {
                    Text(
                        text = field.help,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                field.expiresAt?.let { expiry ->
                    Text(
                        text = "Expires: ${formatTimestamp(expiry)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
            singleLine = true,
            visualTransformation =
                if (field.isPassword) {
                    PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
            modifier = Modifier.fillMaxWidth(),
            isError = fieldErrors.containsKey(field.key),
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onClose,
        ) {
            Text(
                text = stringResource(R.string.action_cancel),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = {
                val errors = mutableMapOf<String, String>()
                envFields.forEach { field ->
                    val value = inputValues[field.key].orEmpty()
                    val error = validateEnvField(field, value)
                    if (error != null) errors[field.key] = error
                }
                if (errors.isNotEmpty()) {
                    fieldErrors.putAll(errors)
                    return@Button
                }
                val filledEnv = inputValues.filterValues { it.isNotBlank() }
                val update =
                    MessagingPlatformUpdate(
                        enabled = platform.enabled,
                        env = filledEnv,
                    )
                onSave(update)
                onClose()
            },
        ) {
            Text(
                text = stringResource(R.string.channels_action_save_settings),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

// ── Admin section  ────────────────────────────────────────────────────

@Composable
private fun AdminSection(envPath: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = "Admin",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Credentials are written to $envPath",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Telegram Onboarding Dialog ────────────────────────────────────────

private val isoTimestampParser =
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

private val displayTimestampFormatter =
    java.text.SimpleDateFormat("MMM d, yyyy HH:mm", java.util.Locale.US)

private fun formatTimestamp(isoDate: String): String {
    return try {
        val parsed = isoTimestampParser.parse(isoDate) ?: return ""
        displayTimestampFormatter.format(parsed)
    } catch (_: Exception) {
        ""
    }
}
