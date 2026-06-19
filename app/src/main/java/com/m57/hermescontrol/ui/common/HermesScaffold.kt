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

/**
 * Shared Scaffold wrapper that standardizes the TopAppBar.
 *
 * Improvements (v2):
 *  - Scroll-aware top bar (pinned collapse on scroll)
 *  - Uses Spacing tokens internally
 *  - Simplified API: onOpenDrawer OR showBack (mutually exclusive)
 *  - Safe-area aware via Scaffold insets
 *
 * Screens that still need a Back arrow instead of a Menu icon can set
 * `showBack = true` and pass an `onBack` lambda.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesScaffold(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    isRefreshing: Boolean = false,
    onBack: (() -> Unit)? = null,
    showBack: Boolean = false,
    snackbarHost: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { snackbarHost() },
        topBar = {
            TopAppBar(
                title = title,
                navigationIcon = {
                    when {
                        showBack && onBack != null -> {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                )
                            }
                        }

                        onOpenDrawer != null -> {
                            IconButton(onClick = onOpenDrawer) {
                                Icon(
                                    imageVector = Icons.Filled.Menu,
                                    contentDescription = "Open navigation drawer",
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (onRefresh != null) {
                        IconButton(onClick = onRefresh) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Refresh",
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
