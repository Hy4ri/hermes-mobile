package com.m57.hermescontrol.profilelock

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.applock.AlertDialog
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.ui.settings.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the app inside the lock while it is on (#1643): drops protected back-stack entries and
 * re-homes the connection and active profile to the lock target if they ever differ.
 */
@Composable
internal fun ProfileLockEnforcer(hasToken: Boolean) {
    val lockState by ProfileLock.state.collectAsState()
    val selected by AuthManager.selectedProfileFlow.collectAsState()
    val active by AuthManager.activeProfileId.collectAsState()
    val top = NavigationController.backStack?.lastOrNull()
    val depth = NavigationController.backStack?.size
    LaunchedEffect(lockState.locked, top, depth) {
        if (lockState.locked) NavigationController.dropLockedDestinations()
    }
    LaunchedEffect(lockState, selected, active, hasToken) {
        val target = lockState.target ?: return@LaunchedEffect
        if (!lockState.locked || !hasToken) return@LaunchedEffect
        if (selected != target.connectionProfileId) {
            ProfileSwitchCoordinator.switchConnectionProfile(target.connectionProfileId)
        } else if (active != target.profileName) {
            ProfileSwitchCoordinator.switchProfile(target.profileName)
        }
    }
}

/**
 * Sets a new PIN (entered twice) and locks the app to [profileName] on the current connection.
 * [onDone] receives true once the lock is stored.
 */
@Composable
internal fun ProfileLockSetDialog(
    profileName: String,
    onDismiss: () -> Unit,
    onDone: (Boolean) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.profile_lock_set_title, profileName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.profile_lock_set_body))
                PinField(pin, R.string.profile_lock_pin_label, "profile_lock_pin") {
                    pin = it
                    error = null
                }
                PinField(confirm, R.string.profile_lock_pin_confirm_label, "profile_lock_pin_confirm") {
                    confirm = it
                    error = null
                }
                Text(
                    stringResource(R.string.profile_lock_forgot_pin_note),
                    style = MaterialTheme.typography.bodySmall,
                )
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && pin.isNotEmpty() && confirm.isNotEmpty(),
                modifier = Modifier.testTag("profile_lock_set_confirm"),
                onClick = {
                    when {
                        !ProfileLock.isValidPin(pin) -> {
                            error = R.string.profile_lock_pin_invalid
                        }

                        pin != confirm -> {
                            error = R.string.profile_lock_pin_mismatch
                        }

                        else -> {
                            busy = true
                            scope.launch {
                                val target = ProfileLockTarget(AuthManager.getSelectedProfileId(), profileName)
                                // Pin the scope explicitly before locking so REST and WS stop
                                // depending on the server's sticky profile.
                                val switched =
                                    AuthManager.activeProfileId.value == profileName ||
                                        ProfileSwitchCoordinator.switchProfile(profileName) is
                                            com.m57.hermescontrol.data.remote.NetworkResult.Success
                                val stored =
                                    switched && withContext(Dispatchers.Default) { ProfileLock.enable(target, pin) }
                                busy = false
                                if (stored) {
                                    onDone(true)
                                } else {
                                    error = R.string.profile_lock_save_failed
                                }
                            }
                        }
                    }
                },
            ) { Text(stringResource(R.string.profile_lock_action_lock)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Asks for the PIN and turns the lock off when it is correct. */
@Composable
internal fun ProfileLockUnlockDialog(
    onDismiss: () -> Unit,
    onUnlocked: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var attemptsLeft by remember { mutableStateOf<Int?>(null) }
    var coolingDown by remember { mutableStateOf(false) }
    val failedText = stringResource(R.string.profile_lock_save_failed)
    val coolingText = stringResource(R.string.profile_lock_cooling_down, (ProfileLock.LOCKOUT_MS / 1000).toInt())
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.profile_lock_unlock_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PinField(pin, R.string.profile_lock_pin_label, "profile_lock_unlock_pin") {
                    pin = it
                    message = null
                    attemptsLeft = null
                    coolingDown = false
                }
                val left = attemptsLeft
                val error =
                    when {
                        coolingDown -> coolingText
                        left != null -> pluralStringResource(R.plurals.profile_lock_wrong_pin, left, left)
                        else -> message
                    }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && pin.isNotEmpty(),
                modifier = Modifier.testTag("profile_lock_unlock_confirm"),
                onClick = {
                    busy = true
                    val entered = pin
                    scope.launch {
                        val result = withContext(Dispatchers.Default) { ProfileLock.disable(entered) }
                        busy = false
                        pin = ""
                        when (result) {
                            ProfileUnlockResult.Unlocked -> onUnlocked()
                            is ProfileUnlockResult.WrongPin -> attemptsLeft = result.attemptsLeft
                            is ProfileUnlockResult.CoolingDown -> coolingDown = true
                            ProfileUnlockResult.Failed -> message = failedText
                        }
                    }
                },
            ) { Text(stringResource(R.string.profile_lock_action_turn_off)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Lock status plus the owner's way out; shown in Settings and on Landing while locked. */
@Composable
internal fun ProfileLockCard(modifier: Modifier = Modifier) {
    val lockState by ProfileLock.state.collectAsState()
    if (!lockState.locked) return
    var unlocking by remember { mutableStateOf(false) }
    Column(modifier = modifier.testTag("profile_lock_card")) {
        SectionCard {
            Text(stringResource(R.string.profile_lock_title), style = MaterialTheme.typography.titleMedium)
            Text(
                lockState.target?.let { stringResource(R.string.profile_lock_locked_to, it.profileName) }
                    ?: stringResource(R.string.profile_lock_unreadable),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(stringResource(R.string.profile_lock_card_body), style = MaterialTheme.typography.bodySmall)
            if (lockState.target != null) {
                OutlinedButton(
                    onClick = { unlocking = true },
                    modifier = Modifier.fillMaxWidth().testTag("profile_lock_turn_off"),
                ) { Text(stringResource(R.string.profile_lock_action_turn_off)) }
            }
        }
    }
    val context = LocalContext.current
    val disabledToast = stringResource(R.string.profile_lock_disabled_toast)
    if (unlocking) {
        ProfileLockUnlockDialog(
            onDismiss = { unlocking = false },
            onUnlocked = {
                unlocking = false
                Toast
                    .makeText(
                        context,
                        disabledToast,
                        Toast.LENGTH_SHORT,
                    ).show()
            },
        )
    }
}

/** Shown instead of sign-in while locked: explains why and gives the owner the way out. */
@Composable
internal fun LockedLandingContent(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            stringResource(R.string.profile_lock_landing_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ProfileLockCard(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun PinField(
    value: String,
    labelRes: Int,
    tag: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { next -> onChange(next.filter { it in '0'..'9' }.take(ProfileLock.MAX_PIN_LENGTH)) },
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
