package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.R

sealed class NavIcon {
    data class Menu(val onOpenDrawer: () -> Unit) : NavIcon()
    data class Back(val onBack: () -> Unit) : NavIcon()
    data object None : NavIcon()
}

/**
 * Shared Scaffold wrapper that standardizes the TopAppBar.
 *
 * Improvements (v2):
 *  - Scroll-aware top bar (pinned collapse on scroll)
 *  - Uses Spacing tokens internally
 *  - Simplified API: navIcon parameter instead of flags
 *  - Safe-area aware via Scaffold insets
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesScaffold(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    navIcon: NavIcon = NavIcon.None,
    onRefresh: (() -> Unit)? = null,
    isRefreshing: Boolean = false,
    pinTopBar: Boolean = false,
    snackbarHost: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior =
        if (pinTopBar) {
            TopAppBarDefaults.pinnedScrollBehavior()
        } else {
            TopAppBarDefaults.enterAlwaysScrollBehavior()
        }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { snackbarHost() },
        topBar = {
            TopAppBar(
                title = title,
                navigationIcon = {
                    when (navIcon) {
                        is NavIcon.Back -> {
                            IconButton(onClick = navIcon.onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.content_desc_back),
                                    modifier = Modifier.testTag("back_button"),
                                )
                            }
                        }

                        is NavIcon.Menu -> {
                            IconButton(onClick = navIcon.onOpenDrawer) {
                                Icon(
                                    imageVector = Icons.Filled.Menu,
                                    contentDescription = stringResource(R.string.content_desc_open_drawer),
                                    modifier = Modifier.testTag("menu_button"),
                                )
                            }
                        }

                        is NavIcon.None -> {}
                    }
                },
                actions = {
                    if (onRefresh != null) {
                        IconButton(onClick = onRefresh) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.content_desc_refresh),
                                modifier = Modifier.testTag("refresh_button"),
                            )
                        }
                    }
                    actions()
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        val refreshContent: @Composable () -> Unit = {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
            ) {
                content(paddingValues)
            }
        }

        if (onRefresh != null) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                refreshContent()
            }
        } else {
            refreshContent()
        }
    }
}
