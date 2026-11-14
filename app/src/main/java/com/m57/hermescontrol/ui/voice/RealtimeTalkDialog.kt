package com.m57.hermescontrol.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.m57.hermescontrol.ui.common.ExpressiveButton as Button
import com.m57.hermescontrol.ui.common.ExpressiveLinearProgressIndicator as LinearProgressIndicator
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
private fun Transcript(
    label: Int,
    text: String,
) {
    if (text.isNotBlank()) {
        Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
        Text(text)
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
            TextButton(
                onClick = onStop,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.realtime_stop_work)) }
        }
        if (job.text("output").isNotBlank()) {
            TextButton(
                onClick = onResult,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.realtime_result)) }
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
    HermesScaffold(
        modifier = Modifier.fillMaxSize(),
        title = { Text(stringResource(R.string.realtime_title)) },
        navigationIcon = NavIcon.Back(onClose),
        drawerGesturesEnabled = false,
        showConnectionBanner = false,
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.realtime_global_note), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.realtime_status, state.phase))
            Text(stringResource(R.string.realtime_level), style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(
                progress = { state.level.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            if (permissionDenied) {
                Text(stringResource(R.string.realtime_permission), color = MaterialTheme.colorScheme.error)
                Button(onClick = onPermission, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.realtime_retry_permission))
                }
            }
            if (backgroundEnded) Text(stringResource(R.string.realtime_background))
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = onReconnect,
                enabled = state.phase in setOf("idle", "error", "ended"),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.realtime_reconnect))
            }
            Button(
                onClick = onMute,
                enabled =
                    !permissionDenied && !backgroundEnded &&
                        state.phase in setOf("listening", "speaking", "executing", "connected"),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(if (state.muted) R.string.realtime_unmute else R.string.realtime_mute))
            }
            TextButton(onClick = onInterrupt, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.realtime_interrupt))
            }
            Button(
                onClick = onClose,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.realtime_end)) }
            Transcript(R.string.realtime_you, state.userText)
            Transcript(R.string.realtime_assistant, state.assistantText)
            if (state.jobs.isNotEmpty()) {
                Text(stringResource(R.string.realtime_jobs), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.realtime_work_note), style = MaterialTheme.typography.bodySmall)
            }
            state.jobs.forEach { job ->
                TalkJob(
                    job,
                    onStop = { onStop(job) },
                    onResult = { onResult(job) },
                )
            }
            if (state.receipt.isNotBlank()) {
                Text(stringResource(R.string.realtime_receipt), style = MaterialTheme.typography.titleMedium)
                Text(state.receipt)
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
internal fun RealtimeTalkPreview() {
    val job =
        Json
            .parseToJsonElement(
                """{"runId":42,"kind":"hermes","status":"running","output":""}""",
            ).jsonObject
    MaterialTheme {
        RealtimeTalkContent(
            state =
                TalkCallState(
                    phase = "listening",
                    level = 0.35f,
                    userText = "Review my changes.",
                    assistantText = "The work is running.",
                    jobs = listOf(job),
                ),
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
