package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
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
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.m57.hermescontrol.R
import kotlin.math.roundToInt

/** Sealed interface for navigation icon types — eliminates boolean-flag anti-pattern. */
sealed interface NavIcon {
    /** Hamburger menu icon that opens the drawer. */
    data class Menu(
        val onOpen: () -> Unit,
    ) : NavIcon

    /** Back arrow icon. */
    data class Back(
        val onBack: () -> Unit,
    ) : NavIcon
}

/**
 * Shared Scaffold wrapper that standardizes the TopAppBar.
 *
 * Improvements (v2):
 *  - Scroll-aware top bar (pinned collapse on scroll)
 *  - Uses Spacing tokens internally
 *  - Simplified API: [navigationIcon] replaces the old showBack/onBack/onOpenDrawer trio
 *  - Safe-area aware via Scaffold insets
 *
 * Screens that still need a Back arrow instead of a Menu icon can set
 * `navigationIcon = NavIcon.Back { … }` instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesScaffold(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    navigationIcon: NavIcon? = null,
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
                    when (val icon = navigationIcon) {
                        is NavIcon.Back -> {
                            IconButton(onClick = icon.onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.content_desc_back),
                                    modifier = Modifier.testTag("back_button"),
                                )
                            }
                        }

                        is NavIcon.Menu -> {
                            IconButton(onClick = icon.onOpen) {
                                Icon(
                                    imageVector = Icons.Filled.Menu,
                                    contentDescription = stringResource(R.string.content_desc_open_drawer),
                                    modifier = Modifier.testTag("menu_button"),
                                )
                            }
                        }

                        null -> { /* no navigation icon */ }
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
        val layoutDirection = LocalLayoutDirection.current
        val refreshContent: @Composable () -> Unit = {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(
                            start = paddingValues.calculateStartPadding(layoutDirection),
                            end = paddingValues.calculateEndPadding(layoutDirection),
                            bottom = paddingValues.calculateBottomPadding(),
                        ).dynamicTopBarPadding(scrollBehavior, paddingValues.calculateTopPadding()),
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

@OptIn(ExperimentalMaterial3Api::class)
private fun Modifier.dynamicTopBarPadding(
    scrollBehavior: TopAppBarScrollBehavior,
    baseTopPadding: Dp,
): Modifier =
    this.layout { measurable, constraints ->
        val baseTopPaddingPx = baseTopPadding.roundToPx()
        val heightOffset = scrollBehavior.state.heightOffset.roundToInt()
        val activeTopPadding = (baseTopPaddingPx + heightOffset).coerceAtLeast(0)

        val placeable =
            measurable.measure(
                constraints.copy(
                    maxHeight = (constraints.maxHeight - activeTopPadding).coerceAtLeast(0),
                    minHeight = (constraints.minHeight - activeTopPadding).coerceAtLeast(0),
                ),
            )

        layout(placeable.width, placeable.height + activeTopPadding) {
            placeable.placeRelative(0, activeTopPadding)
        }
    }
