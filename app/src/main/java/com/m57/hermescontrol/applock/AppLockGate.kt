package com.m57.hermescontrol.applock

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R

internal val LocalAppLockController = staticCompositionLocalOf<AppLockController?> { null }

@Composable
internal fun AppLockHost(
    controller: AppLockController,
    onExit: () -> Unit,
    content: @Composable () -> Unit,
) {
    val session = controller.session
    SideEffect { controller.updateSecureWindow() }
    CompositionLocalProvider(LocalAppLockController provides controller) {
        AppLockGate(
            blocked = session.policy.blocked,
            retainContent = session.hasOpened,
            content = content,
        ) {
            BackHandler(onBack = onExit)
            val context = LocalContext.current
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Text(stringResource(R.string.app_lock_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.app_lock_locked))
                session.error?.let { Text(stringResource(it)) }
                if (!session.deviceSecure) {
                    Text(stringResource(R.string.app_lock_unavailable))
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                    }) { Text(stringResource(R.string.app_lock_security_settings)) }
                }
                Button(
                    enabled = session.deviceSecure && session.pendingAuth == null && session.credentialRequest == null,
                    onClick = { controller.authenticate(AppLockAuthAction.UNLOCK) },
                ) { Text(stringResource(R.string.app_lock_unlock)) }
            }
        }
    }
}

/**
 * Cold starts never compose private content. Later locks retain its state but do not place it:
 * unplaced descendants cannot draw, receive input, or expose accessibility nodes (#1604).
 */
@Composable
internal fun AppLockGate(
    blocked: Boolean,
    retainContent: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
    lockContent: @Composable () -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(blocked) {
        if (blocked) {
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (retainContent || !blocked) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (blocked) Modifier.clearAndSetSemantics {} else Modifier)
                    .onPreviewKeyEvent { blocked }
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            if (!blocked) placeable.placeRelative(0, 0)
                        }
                    },
            ) {
                CompositionLocalProvider(LocalPrivateWindowsBlocked provides blocked) { content() }
            }
        }
        if (blocked) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .testTag("app_lock_gate"),
            ) { lockContent() }
        }
    }
}
