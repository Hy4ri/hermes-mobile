package com.m57.hermescontrol.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.m57.hermescontrol.ui.common.ExpressiveButton as Button
import com.m57.hermescontrol.ui.common.ExpressiveIconButton as IconButton
import com.m57.hermescontrol.ui.common.ExpressiveTextButton as TextButton

/** A modal composition: chat, its draft, and pending attachments remain mounted. */
@Composable
fun RealtimeTalkDialog(
    launch: TalkLaunch,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val controller = remember(launch) { TalkCallController(context.applicationContext, launch) }
    val state by controller.state.collectAsStateWithLifecycle()
    var permissionDenied by remember(launch) { mutableStateOf(false) }
    var backgroundEnded by remember(launch) { mutableStateOf(false) }
    var pendingStop by remember(launch) { mutableStateOf<JsonObject?>(null) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionDenied = !granted
            if (granted && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                backgroundEnded = false
                controller.start()
            }
        }

    fun connect(reconnect: Boolean) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            permissionDenied = false
            backgroundEnded = false
            if (reconnect) controller.reconnect() else controller.start()
        }
    }
    LaunchedEffect(controller) { connect(false) }
    DisposableEffect(controller, owner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    controller.end()
                    backgroundEnded = true
                }
            }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            controller.dispose()
        }
    }
    val close = {
        controller.end()
        onDismiss()
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        RealtimeTalkContent(
            state = state,
            permissionDenied = permissionDenied,
            backgroundEnded = backgroundEnded,
            onClose = close,
            onPermission = { connect(false) },
            onReconnect = { connect(true) },
            onMute = controller::mute,
            onInterrupt = controller::interrupt,
            onStop = { pendingStop = it },
            onResult = controller::showResult,
        )
        pendingStop?.let { job ->
            AlertDialog(
                onDismissRequest = { pendingStop = null },
                title = { Text(stringResource(R.string.realtime_stop_work)) },
                text = { Text(stringResource(R.string.realtime_stop_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingStop = null
                            controller.stopWork(job)
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.realtime_stop_work))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingStop = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.realtime_keep_work))
                    }
                },
            )
        }
    }
}

@Composable
internal fun RealtimeTalkContent(
    state: TalkCallState,
    permissionDenied: Boolean,
    backgroundEnded: Boolean,
    onClose: () -> Unit,
    onPermission: () -> Unit,
    onReconnect: () -> Unit,
    onMute: () -> Unit,
    onInterrupt: () -> Unit,
    onStop: (JsonObject) -> Unit,
    onResult: (JsonObject) -> Unit,
) {
    var activityVisible by remember { mutableStateOf(false) }
    val canControl =
        !permissionDenied && !backgroundEnded &&
            when (state.phase) {
                "listening", "speaking", "executing", "connected" -> true
                else -> false
            }
    val canInterrupt = canControl && (state.phase == "speaking" || state.phase == "executing")
    BackHandler(enabled = activityVisible) { activityVisible = false }
    HermesScaffold(
        modifier = Modifier.fillMaxSize(),
        title = { Text(stringResource(R.string.realtime_title)) },
        navigationIcon = NavIcon.Back { if (activityVisible) activityVisible = false else onClose() },
        drawerGesturesEnabled = false,
        showConnectionBanner = false,
        pinTopBar = true,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (activityVisible) {
                    TalkActivity(
                        userText = state.userText,
                        assistantText = state.assistantText,
                        jobs = state.jobs,
                        receipt = state.receipt,
                        phase = state.phase,
                        error = state.error,
                        permissionDenied = permissionDenied,
                        backgroundEnded = backgroundEnded,
                        canInterrupt = canInterrupt,
                        onPermission = onPermission,
                        onReconnect = onReconnect,
                        onInterrupt = onInterrupt,
                        onStop = onStop,
                        onResult = onResult,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    TalkVoiceStage(
                        state = state,
                        permissionDenied = permissionDenied,
                        backgroundEnded = backgroundEnded,
                        canInterrupt = canInterrupt,
                        onPermission = onPermission,
                        onReconnect = onReconnect,
                        onInterrupt = onInterrupt,
                        onActivity = { activityVisible = true },
                        modifier = Modifier.weight(1f),
                    )
                }
                TalkControls(
                    muted = state.muted,
                    canControl = canControl,
                    activityVisible = activityVisible,
                    onMute = onMute,
                    onClose = onClose,
                    onActivity = { activityVisible = !activityVisible },
                )
            }
        }
    }
}

@Composable
private fun TalkVoiceStage(
    state: TalkCallState,
    permissionDenied: Boolean,
    backgroundEnded: Boolean,
    canInterrupt: Boolean,
    onPermission: () -> Unit,
    onReconnect: () -> Unit,
    onInterrupt: () -> Unit,
    onActivity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val notice = permissionDenied || backgroundEnded || state.error != null
    val running = remember(state.jobs) { state.jobs.count { it.text("status") == "running" } }
    val latest = remember(state.assistantText, state.userText) { state.assistantText.ifBlank { state.userText } }
    val heading =
        when {
            permissionDenied -> R.string.realtime_state_permission
            backgroundEnded -> R.string.realtime_state_ended
            state.phase == "error" -> R.string.realtime_state_error
            state.phase == "speaking" -> R.string.realtime_state_speaking
            state.phase == "executing" -> R.string.realtime_state_working
            state.muted -> R.string.realtime_state_muted
            state.phase == "listening" || state.phase == "connected" -> R.string.realtime_state_listening
            state.phase == "ended" -> R.string.realtime_state_ended
            state.phase == "idle" -> R.string.realtime_state_ready
            else -> R.string.realtime_state_connecting
        }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(
                    rememberScrollState(),
                ).heightIn(min = maxHeight)
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(R.string.realtime_global_short),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(heading),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            TalkNotice(
                phase = state.phase,
                error = state.error,
                permissionDenied = permissionDenied,
                backgroundEnded = backgroundEnded,
                onPermission = onPermission,
                onReconnect = onReconnect,
            )
            TalkVoiceVisual(
                phase = if (permissionDenied || backgroundEnded) "ended" else state.phase,
                muted = state.muted || permissionDenied || backgroundEnded,
                level = state.level,
                interactive = canInterrupt,
                interruptLabel = stringResource(R.string.realtime_interrupt),
                onInterrupt = onInterrupt,
                modifier = Modifier.fillMaxWidth().height(if (notice) 104.dp else 208.dp).testTag("voice_interrupt"),
            )
            if (!notice) {
                Text(
                    stringResource(
                        when {
                            state.phase == "executing" -> {
                                R.string.realtime_working_hint
                            }

                            canInterrupt -> {
                                R.string.realtime_interrupt_hint
                            }

                            state.muted -> {
                                R.string.realtime_muted_hint
                            }

                            state.phase == "listening" || state.phase == "connected" -> {
                                R.string.realtime_listening_hint
                            }

                            state.phase == "idle" || state.phase == "ended" || state.phase == "error" -> {
                                R.string.realtime_resume_hint
                            }

                            else -> {
                                R.string.realtime_connecting_hint
                            }
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (latest.isNotBlank()) {
                Spacer(Modifier.height(18.dp))
                Text(latest, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                TextButton(onClick = onActivity, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.realtime_view_transcript))
                }
            }
            if (running > 0) {
                TextButton(onClick = onActivity, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Work, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        pluralStringResource(R.plurals.realtime_running_work, running, running),
                        Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TalkNotice(
    phase: String,
    error: String?,
    permissionDenied: Boolean,
    backgroundEnded: Boolean,
    onPermission: () -> Unit,
    onReconnect: () -> Unit,
) {
    val statusColors = LocalHermesStatusColors.current
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (permissionDenied) {
            Text(
                stringResource(R.string.realtime_permission),
                color = statusColors.warning,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onPermission, modifier = Modifier.heightIn(min = 48.dp).testTag("voice_permission")) {
                Text(stringResource(R.string.realtime_retry_permission))
            }
        }
        if (backgroundEnded) Text(stringResource(R.string.realtime_background), textAlign = TextAlign.Center)
        error?.let { Text(it, color = statusColors.error, textAlign = TextAlign.Center) }
        if (!permissionDenied && (backgroundEnded || phase == "idle" || phase == "error" || phase == "ended")) {
            Button(onClick = onReconnect, modifier = Modifier.heightIn(min = 48.dp).testTag("voice_reconnect")) {
                Text(stringResource(R.string.realtime_reconnect))
            }
        }
    }
}

@Composable
private fun TalkControls(
    muted: Boolean,
    canControl: Boolean,
    activityVisible: Boolean,
    onMute: () -> Unit,
    onClose: () -> Unit,
    onActivity: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val muteLabel = stringResource(if (muted) R.string.realtime_unmute else R.string.realtime_mute)
    val micState = stringResource(if (muted) R.string.realtime_state_muted else R.string.realtime_mic_on)
    val activityLabel =
        stringResource(if (activityVisible) R.string.realtime_voice_view else R.string.realtime_activity)
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 18.dp).testTag("voice_controls"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            IconToggleButton(
                checked = muted,
                onCheckedChange = { onMute() },
                enabled = canControl,
                modifier = Modifier.size(64.dp).testTag("voice_mute").semantics { stateDescription = micState },
                colors =
                    IconButtonDefaults.iconToggleButtonColors(
                        containerColor = colors.surfaceContainerHigh,
                        contentColor = colors.onSurface,
                        checkedContainerColor = colors.secondaryContainer,
                        checkedContentColor = colors.onSecondaryContainer,
                    ),
            ) {
                Icon(if (muted) Icons.Filled.MicOff else Icons.Filled.Mic, contentDescription = muteLabel)
            }
            ControlLabel(muteLabel)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(64.dp).testTag("voice_end"),
                shape = CircleShape,
                colors =
                    IconButtonDefaults.iconButtonColors(
                        containerColor = colors.errorContainer,
                        contentColor = colors.onErrorContainer,
                    ),
            ) {
                Icon(Icons.Filled.CallEnd, contentDescription = stringResource(R.string.realtime_end))
            }
            ControlLabel(stringResource(R.string.realtime_end))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(
                onClick = onActivity,
                modifier = Modifier.size(64.dp).testTag("voice_activity"),
                shape = CircleShape,
                colors =
                    IconButtonDefaults.iconButtonColors(
                        containerColor = if (activityVisible) colors.primaryContainer else colors.surfaceContainerHigh,
                        contentColor = if (activityVisible) colors.onPrimaryContainer else colors.onSurface,
                    ),
            ) {
                Icon(
                    if (activityVisible) Icons.Filled.GraphicEq else Icons.AutoMirrored.Filled.Notes,
                    contentDescription = activityLabel,
                )
            }
            ControlLabel(activityLabel)
        }
    }
}

@Composable
private fun ControlLabel(label: String) {
    Text(
        label,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clearAndSetSemantics {},
        style = MaterialTheme.typography.labelLarge,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun TalkActivity(
    userText: String,
    assistantText: String,
    jobs: List<JsonObject>,
    receipt: String,
    phase: String,
    error: String?,
    permissionDenied: Boolean,
    backgroundEnded: Boolean,
    canInterrupt: Boolean,
    onPermission: () -> Unit,
    onReconnect: () -> Unit,
    onInterrupt: () -> Unit,
    onStop: (JsonObject) -> Unit,
    onResult: (JsonObject) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    LaunchedEffect(receipt) { if (receipt.isNotBlank()) list.animateScrollToItem(0) }
    LazyColumn(
        modifier.fillMaxWidth().testTag("voice_activity_content"),
        state = list,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            TalkNotice(phase, error, permissionDenied, backgroundEnded, onPermission, onReconnect)
            if (canInterrupt) {
                TextButton(onClick = onInterrupt, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.realtime_interrupt))
                }
            }
        }
        if (receipt.isNotBlank()) {
            item {
                Text(stringResource(R.string.realtime_receipt), style = MaterialTheme.typography.titleMedium)
                SelectionContainer { Text(receipt) }
                HorizontalDivider(Modifier.padding(top = 18.dp))
            }
        }
        item {
            Text(stringResource(R.string.realtime_latest_exchange), style = MaterialTheme.typography.titleLarge)
            if (userText.isBlank() && assistantText.isBlank()) {
                Text(
                    stringResource(R.string.realtime_no_transcript),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Transcript(R.string.realtime_you, userText)
            Transcript(R.string.realtime_assistant, assistantText)
        }
        item {
            HorizontalDivider()
            Text(
                stringResource(R.string.realtime_jobs),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 18.dp),
            )
            Text(stringResource(R.string.realtime_work_source), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.realtime_work_note), style = MaterialTheme.typography.bodySmall)
            if (jobs.isEmpty()) {
                Text(stringResource(R.string.realtime_no_work), modifier = Modifier.padding(top = 12.dp))
            }
        }
        items(jobs) { job ->
            TalkJob(job, onStop = { onStop(job) }, onResult = { onResult(job) })
        }
        item {
            HorizontalDivider()
            Text(
                stringResource(R.string.realtime_global_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 18.dp),
            )
        }
    }
}

@Composable
private fun Transcript(
    label: Int,
    text: String,
) {
    if (text.isNotBlank()) {
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer { Text(text) }
        }
    }
}

@Composable
private fun TalkJob(
    job: JsonObject,
    onStop: () -> Unit,
    onResult: () -> Unit,
) {
    val status = job.text("status")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.realtime_run, job.text("runId"), job.text("kind")),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(stringResource(R.string.realtime_job_status, status))
        if (status == "running") {
            TextButton(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.realtime_stop_work))
            }
        }
        if (job.text("output").isNotBlank()) {
            TextButton(onClick = onResult, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.realtime_result))
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
internal fun RealtimeTalkPreview() {
    TalkScenePreview(TalkCallState(phase = "listening", level = 0.35f), ThemePreference.DARK)
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
internal fun RealtimeTalkMutedPreview() {
    TalkScenePreview(TalkCallState(phase = "listening", muted = true), ThemePreference.LIGHT)
}

@Preview(showBackground = true, widthDp = 320, heightDp = 640, fontScale = 1.5f)
@Composable
internal fun RealtimeTalkErrorPreview() {
    TalkScenePreview(
        TalkCallState(phase = "error", error = "Voice access denied (403); check Access and subscription"),
        ThemePreference.DARK,
    )
}

@Composable
private fun TalkScenePreview(
    state: TalkCallState,
    preference: ThemePreference,
) {
    HermesControlTheme(themePreference = preference) {
        RealtimeTalkContent(
            state = state,
            permissionDenied = false,
            backgroundEnded = false,
            onClose = {},
            onPermission = {},
            onReconnect = {},
            onMute = {},
            onInterrupt = {},
            onStop = {},
            onResult = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
internal fun RealtimeTalkActivityPreview() {
    val job =
        remember {
            Json.parseToJsonElement("""{"runId":42,"kind":"hermes","status":"running","output":""}""").jsonObject
        }
    HermesControlTheme(themePreference = ThemePreference.DARK) {
        HermesScaffold(title = { Text(stringResource(R.string.realtime_title)) }, showConnectionBanner = false) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                TalkActivity(
                    userText = "Review my changes.",
                    assistantText = "The work is running.",
                    jobs = listOf(job),
                    receipt = "",
                    phase = "speaking",
                    error = null,
                    permissionDenied = false,
                    backgroundEnded = false,
                    canInterrupt = true,
                    onPermission = {},
                    onReconnect = {},
                    onInterrupt = {},
                    onStop = {},
                    onResult = {},
                    modifier = Modifier.weight(1f),
                )
                TalkControls(false, true, true, {}, {}, {})
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 840, heightDp = 640, locale = "zh-rTW")
@Composable
internal fun RealtimeTalkWidePreview() {
    TalkScenePreview(TalkCallState(phase = "speaking", muted = true), ThemePreference.DARK)
}
