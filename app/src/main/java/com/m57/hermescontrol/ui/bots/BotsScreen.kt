package com.m57.hermescontrol.ui.bots

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.ui.common.BotAvatar
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.ToastEffect
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: BotsViewModel = viewModel { BotsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var isSearchActive by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (showCreateDialog) {
        CreateBotDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, title, description, shape, color ->
                viewModel.createBot(name, title, description, shape, color) {
                    showCreateDialog = false
                }
            },
        )
    }

    ToastEffect(
        toastMessage = state.toastMessage,
        onClearToast = { viewModel.clearToast() },
    )

    HermesScaffold(
        modifier = modifier,
        title = {
            if (isSearchActive) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text(stringResource(R.string.bots_search_placeholder)) },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (state.searchQuery.isNotEmpty()) {
                                    viewModel.setSearchQuery("")
                                } else {
                                    isSearchActive = false
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_cancel),
                            )
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("bots_search_field"),
                )
            } else {
                Text(
                    text = stringResource(R.string.screen_bots),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        actions = {
            if (!isSearchActive) {
                IconButton(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.testTag("bots_action_create"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.bots_action_create),
                    )
                }
                IconButton(
                    onClick = { isSearchActive = true },
                    modifier = Modifier.testTag("bots_action_search"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.bots_search_placeholder),
                    )
                }
            }
            if (state.hasHiddenBots) {
                IconButton(
                    onClick = { viewModel.toggleShowHidden() },
                    modifier = Modifier.testTag("bots_action_toggle_hidden"),
                ) {
                    Icon(
                        imageVector =
                            if (state.showHidden) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                        contentDescription =
                            if (state.showHidden) {
                                stringResource(R.string.bots_hide_hidden)
                            } else {
                                stringResource(R.string.bots_show_hidden)
                            },
                    )
                }
            }
        },
        isRefreshing = state.isRefreshing,
        onRefresh = { viewModel.loadBots(isRefresh = true) },
    ) {
        when {
            state.isLoading && state.profiles.isEmpty() -> {
                SkeletonListState()
            }

            state.errorMessage != null && state.profiles.isEmpty() -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadBots() },
                )
            }

            state.displayProfiles.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.bots_empty_title),
                    subtitle = stringResource(R.string.bots_empty_description),
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.activeNowBots.isNotEmpty() && !isSearchActive) {
                        item(key = "active_now_strip") {
                            ActiveNowStrip(
                                activeBots = state.activeNowBots,
                                onSelectBot = { bot ->
                                    scope.launch {
                                        viewModel.selectBot(bot)
                                        val canonicalId =
                                            bot.canonical_session?.resolved_id
                                                ?: bot.canonical_session?.id
                                        if (!canonicalId.isNullOrBlank()) {
                                            NavigationController.openChatSession(canonicalId)
                                        } else {
                                            NavigationController.navigateTo(com.m57.hermescontrol.ChatScreen)
                                        }
                                    }
                                },
                            )
                        }
                    }

                    items(
                        items = state.displayProfiles,
                        key = { it.name },
                    ) { profile ->
                        BotCard(
                            profile = profile,
                            isActiveProfile = profile.name == state.activeProfileName,
                            onClick = {
                                scope.launch {
                                    viewModel.selectBot(profile)
                                    val canonicalId =
                                        profile.canonical_session?.resolved_id
                                            ?: profile.canonical_session?.id
                                    if (!canonicalId.isNullOrBlank()) {
                                        NavigationController.openChatSession(canonicalId)
                                    } else {
                                        NavigationController.navigateTo(com.m57.hermescontrol.ChatScreen)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveNowStrip(
    activeBots: List<ProfileInfo>,
    onSelectBot: (ProfileInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.bots_active_now),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            activeBots.forEach { bot ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onSelectBot(bot) }
                            .testTag("bot_active_chip_${bot.name}"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BotAvatar(
                            name = bot.name,
                            avatar = bot.botMeta()?.avatar,
                            size = 24.dp,
                            isActive = true,
                            showPresence = true,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = bot.effectiveTitle,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun BotCard(
    profile: ProfileInfo,
    isActiveProfile: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val botMeta = profile.botMeta()
    val isHidden = profile.isHidden
    val hasWorker = profile.worker_session != null

    Card(
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isActiveProfile) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .testTag("bot_card_${profile.name}"),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BotAvatar(
                name = profile.name,
                avatar = botMeta?.avatar,
                size = 44.dp,
                isActive = isActiveProfile || hasWorker,
                showPresence = true,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = profile.effectiveTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isHidden) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = stringResource(R.string.bots_hidden_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                if (profile.effectiveTitle != profile.name) {
                    Text(
                        text = "@${profile.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (profile.effectiveDescription.isNotBlank()) {
                    Text(
                        text = profile.effectiveDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val preview = profile.canonical_session?.preview ?: profile.last_session?.preview
                if (!preview.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
