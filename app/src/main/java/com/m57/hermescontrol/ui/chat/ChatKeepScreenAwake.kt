package com.m57.hermescontrol.ui.chat

import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.m57.hermescontrol.R

/** Keep the display awake only while this chat owns a resumed screen (issue #1625). */
@Composable
internal fun ChatKeepScreenAwakeEffect(enabled: Boolean) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    if (enabled) {
        DisposableEffect(view, lifecycle) {
            val previous = view.keepScreenOn

            fun update() {
                view.keepScreenOn = previous || lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
            val observer = LifecycleEventObserver { _, _ -> update() }
            lifecycle.addObserver(observer)
            update()
            onDispose {
                lifecycle.removeObserver(observer)
                view.keepScreenOn = previous
            }
        }
    }
}

@Composable
internal fun ChatKeepScreenAwakeMenuItem(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.settings_keep_screen_awake_title)) },
        trailingIcon = {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.testTag("chat_menu_keep_awake_checkbox"),
            )
        },
        onClick = { onToggle(!checked) },
        modifier =
            Modifier
                .testTag("chat_menu_keep_awake")
                .semantics {
                    role = Role.Checkbox
                    toggleableState = ToggleableState(checked)
                },
    )
}
