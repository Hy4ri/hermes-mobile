package com.m57.hermescontrol.ui.system

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.chat.MediaImageStore
import com.m57.hermescontrol.ui.common.ActionProgressDialog
import com.m57.hermescontrol.ui.common.ConfirmDialog
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.system.components.HookCreateDialog
import com.m57.hermescontrol.ui.system.components.actionLogSection
import com.m57.hermescontrol.ui.system.components.checkpointsSection
import com.m57.hermescontrol.ui.system.components.credentialsSection
import com.m57.hermescontrol.ui.system.components.curatorSection
import com.m57.hermescontrol.ui.system.components.gatewaySection
import com.m57.hermescontrol.ui.system.components.hostSection
import com.m57.hermescontrol.ui.system.components.operationsSection
import com.m57.hermescontrol.ui.system.components.portalSection
import com.m57.hermescontrol.ui.system.components.shellHooksSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Staged SAF-picked backup archive for the restore flow (issue #786). */
private data class PendingImport(
    val uri: Uri,
    val name: String,
    val mime: String,
)

@Composable
fun SystemScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: SystemViewModel? = null,
) {
    val app = LocalContext.current.applicationContext as Application
    val resolvedViewModel = viewModel ?: viewModel { SystemViewModel(app) }
    val state by resolvedViewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) {
        resolvedViewModel.loadAll()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = resolvedViewModel::clearToast)

    // ── Backup import (issue #786) ────────────────────────────────────────
    val context = LocalContext.current
    val importScope = rememberCoroutineScope()
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    val importPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            importScope.launch {
                val meta =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val name =
                                uri.lastPathSegment?.substringAfterLast("/")?.takeIf { it.isNotBlank() }
                                    ?: "backup.zip"
                            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                            PendingImport(uri, name, mime)
                        }
                    }
                meta.fold(
                    onSuccess = {
                        pendingImport = it
                        resolvedViewModel.setImportFile(it.name)
                    },
                    onFailure = { resolvedViewModel.showToast("Could not read selected file: ${it.message}") },
                )
            }
        }

    // ── Confirmation dialogs ──────────────────────────────────────────────

    if (state.updateConfirmOpen) {
        AlertDialog(
            onDismissRequest = resolvedViewModel::closeUpdateConfirm,
            title = { Text(stringResource(R.string.system_update_confirm_title)) },
            text = { Text(stringResource(R.string.system_update_confirm_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    resolvedViewModel.closeUpdateConfirm()
                    resolvedViewModel.applyUpdate()
                }) {
                    Text(stringResource(R.string.system_confirm_update_now))
                }
            },
            dismissButton = {
                TextButton(onClick = resolvedViewModel::closeUpdateConfirm) {
                    Text(stringResource(R.string.system_confirm_cancel))
                }
            },
        )
    }

    ActionProgressDialog(
        controller = resolvedViewModel.actionProgress,
        title = stringResource(R.string.system_update_progress_title),
    )

    // Credential remove confirmation using ConfirmDialog
    var credToRemove by remember { mutableStateOf<Pair<String, Int>?>(null) }
    credToRemove?.let { (provider, index) ->
        ConfirmDialog(
            title = stringResource(R.string.system_credentials_remove_confirm_title),
            message = stringResource(R.string.system_credentials_remove_confirm_desc),
            onConfirm = {
                resolvedViewModel.removeCredential(provider, index)
                credToRemove = null
            },
            onDismiss = { credToRemove = null },
            confirmText = stringResource(R.string.action_delete),
            isDestructive = true,
            icon = Icons.Filled.Delete,
        )
    }

    // Prune checkpoints confirmation
    var pruneConfirm by remember { mutableStateOf(false) }
    if (pruneConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.system_checkpoints_prune_confirm_title),
            message = stringResource(R.string.system_checkpoints_prune_confirm_desc),
            onConfirm = {
                pruneConfirm = false
                resolvedViewModel.pruneCheckpoints()
            },
            onDismiss = { pruneConfirm = false },
            confirmText = stringResource(R.string.action_ok),
            isDestructive = true,
        )
    }

    // Restore confirmation
    var importConfirm by remember { mutableStateOf(false) }
    if (importConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.system_op_import_confirm_title),
            message = stringResource(R.string.system_op_import_confirm_desc),
            onConfirm = {
                importConfirm = false
                val pending = pendingImport
                pendingImport = null
                if (pending != null) {
                    importScope.launch {
                        val read =
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    context.contentResolver.openInputStream(pending.uri)?.use { it.readBytes() }
                                }
                            }
                        read.fold(
                            onSuccess = { bytes ->
                                if (bytes != null) {
                                    resolvedViewModel.importArchive(pending.name, bytes, pending.mime)
                                } else {
                                    resolvedViewModel.showToast("Could not read selected file")
                                }
                            },
                            onFailure = {
                                resolvedViewModel.showToast("Could not read selected file: ${it.message}")
                            },
                        )
                    }
                }
            },
            onDismiss = { importConfirm = false },
            confirmText = stringResource(R.string.system_confirm_restore),
            isDestructive = true,
        )
    }

    // Hook creation modal (hoisted out of LazyColumn item)
    HookCreateDialog(
        isOpen = state.hookModalOpen,
        event = state.hookEvent,
        command = state.hookCommand,
        matcher = state.hookMatcher,
        timeout = state.hookTimeout,
        approve = state.hookApprove,
        isCreating = state.creatingHook,
        onEventChange = resolvedViewModel::updateHookEvent,
        onCommandChange = resolvedViewModel::updateHookCommand,
        onMatcherChange = resolvedViewModel::updateHookMatcher,
        onTimeoutChange = resolvedViewModel::updateHookTimeout,
        onApproveChange = resolvedViewModel::updateHookApprove,
        onCreate = resolvedViewModel::createHook,
        onDismiss = resolvedViewModel::toggleHookModal,
    )

    HermesScaffold(
        modifier = modifier,
        title = { Text(stringResource(R.string.screen_system)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { resolvedViewModel.loadAll() },
    ) { padding ->
        when {
            state.isLoading && state.stats == null && state.doctorReport == null &&
                state.status == null -> {
                SkeletonListState()
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: stringResource(R.string.error_unknown),
                    onRetry = { resolvedViewModel.loadAll() },
                )
            }

            state.stats == null && state.doctorReport == null && state.status == null -> {
                EmptyState(
                    title = stringResource(R.string.system_empty_title),
                    subtitle = stringResource(R.string.system_empty_desc),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding =
                        PaddingValues(
                            horizontal = spacing.md,
                            vertical = spacing.sm,
                        ),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    // ── 1. Host ──
                    hostSection(state, spacing, resolvedViewModel)

                    // ── 2. Portal ──
                    portalSection(state, spacing, uriHandler)

                    // ── 3. Curator ──
                    curatorSection(state, spacing, resolvedViewModel)

                    // ── 4. Gateway ──
                    gatewaySection(state, spacing, resolvedViewModel)

                    // ── 6. Credentials ──
                    credentialsSection(state, spacing, resolvedViewModel) { credToRemove = it }

                    // ── 7. Operations ──
                    operationsSection(
                        state = state,
                        spacing = spacing,
                        viewModel = resolvedViewModel,
                        onImportConfirm = { importConfirm = it },
                        onImportPick = {
                            importPickerLauncher.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/octet-stream",
                                    "*/*",
                                ),
                            )
                        },
                        downloadBackup = {
                            resolvedViewModel.downloadBackup { body, fileName ->
                                importScope.launch {
                                    val uri =
                                        withContext(Dispatchers.IO) {
                                            runCatching {
                                                MediaImageStore.saveToDownloads(
                                                    context,
                                                    body.byteStream(),
                                                    body.contentLength().takeIf { it >= 0 },
                                                    fileName,
                                                    "application/zip",
                                                )
                                            }.getOrNull()
                                        }
                                    if (uri != null) {
                                        resolvedViewModel.showToast("Backup saved to Downloads")
                                    } else {
                                        resolvedViewModel.showToast("Could not save backup to Downloads")
                                    }
                                }
                            }
                        },
                    )

                    // ── 8. Checkpoints ──
                    checkpointsSection(state, spacing) { pruneConfirm = it }

                    // ── 9. Shell Hooks ──
                    shellHooksSection(state, spacing, resolvedViewModel)

                    // ── 10. Action Log ──
                    if (state.activeAction != null) {
                        actionLogSection(state, spacing, resolvedViewModel)
                    }
                }
            }
        }
    }
}
