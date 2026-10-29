package com.m57.hermescontrol.ui.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.applock.AlertDialog
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.ManagedFileEntry
import com.m57.hermescontrol.data.model.ManagedFilesListResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiRead
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.LocalSpacing
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Immutable UI state for the remote folder picker dialog.
 */
data class RemoteFolderPickerState(
    val currentPath: String = "",
    val parentPath: String? = null,
    val directories: List<ManagedFileEntry> = emptyList(),
    val root: String? = null,
    val lockedRoot: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isConfirmed: Boolean = false,
    val listingScope: DataScope? = null,
) {
    /**
     * Resolves the current directory as an absolute host path.
     * Rejects relative paths containing segment '..' or missing an absolute root anchor.
     */
    fun resolveCurrentAbsolutePath(): String? {
        val path = currentPath
        if (path.startsWith("/")) return path
        val segments = path.split('/', '\\')
        if (segments.any { it == ".." }) return null
        val anchor = root?.takeIf { it.startsWith("/") } ?: return null
        if (path.isEmpty()) return anchor
        return if (anchor.endsWith("/")) "$anchor$path" else "$anchor/$path"
    }

    /**
     * Whether the current directory can be selected as a project host path.
     * Blocked on initial unconfirmed state, active loading, error, or unresolvable path.
     */
    val canSelectCurrent: Boolean
        get() = isConfirmed && !isLoading && errorMessage == null && resolveCurrentAbsolutePath() != null
}

/**
 * Controller managing remote folder navigation and selection.
 * Injected with an API loader, StateFlow<DataScope?> for scope lifecycle invalidation,
 * coroutine scope, and IO dispatcher.
 */
class RemoteFolderPickerController(
    private val loader: suspend (path: String?) -> NetworkResult<ManagedFilesListResponse>,
    private val scopes: StateFlow<DataScope?>,
    private val coroutineScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(RemoteFolderPickerState())
    val state: StateFlow<RemoteFolderPickerState> = _state.asStateFlow()

    private var activeJob: Job? = null
    private var scopeCollectionJob: Job? = null
    private var generation: Long = 0L
    private var lastRequestedPath: String? = null
    private var isCancelled = false

    init {
        var observedScope = scopes.value
        scopeCollectionJob =
            coroutineScope.launch {
                scopes.collect { newScope ->
                    if (newScope != observedScope) {
                        observedScope = newScope
                        generation++
                        activeJob?.cancel()
                        activeJob = null
                        lastRequestedPath = null
                        _state.value = RemoteFolderPickerState()
                    }
                }
            }
    }

    fun load(path: String? = null) {
        if (isCancelled) return
        generation++
        val currentGen = generation
        val currentScope = scopes.value
        lastRequestedPath = path
        activeJob?.cancel()

        _state.update { it.copy(isLoading = true, errorMessage = null) }

        activeJob =
            coroutineScope.launch {
                val result = withContext(ioDispatcher) { loader(path) }
                if (isCancelled || generation != currentGen || scopes.value != currentScope) {
                    return@launch
                }

                when (result) {
                    is NetworkResult.Success -> {
                        val response = result.data
                        val dirs = response.entries.filter { it.isDirectory }
                        _state.update {
                            it.copy(
                                currentPath = response.path,
                                parentPath = response.parent,
                                directories = dirs,
                                root = response.root,
                                lockedRoot = response.lockedRoot,
                                isLoading = false,
                                errorMessage = null,
                                isConfirmed = true,
                                listingScope = currentScope,
                            )
                        }
                    }

                    is NetworkResult.Failure -> {
                        _state.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = result.error.message,
                                isConfirmed = false,
                            )
                        }
                    }
                }
            }
    }

    fun openDirectory(entry: ManagedFileEntry) {
        if (!entry.isDirectory || isCancelled || _state.value.isLoading) return
        load(entry.path)
    }

    fun navigateUp() {
        val parent = _state.value.parentPath ?: return
        if (isCancelled || _state.value.isLoading) return
        load(parent)
    }

    fun retry() {
        if (isCancelled || _state.value.isLoading) return
        load(lastRequestedPath)
    }

    fun selectCurrent(): String? {
        val currentState = _state.value
        if (isCancelled || !currentState.canSelectCurrent) return null
        if (currentState.listingScope == null || currentState.listingScope != scopes.value) return null
        return currentState.resolveCurrentAbsolutePath()
    }

    fun cancel() {
        isCancelled = true
        activeJob?.cancel()
        activeJob = null
        scopeCollectionJob?.cancel()
        scopeCollectionJob = null
        _state.value = RemoteFolderPickerState()
    }
}

/**
 * Reusable dialog for choosing a folder on the remote gateway host.
 */
@Composable
fun RemoteFolderPickerDialog(
    onSelectFolder: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    scopes: StateFlow<DataScope?> = AuthManager.dataScopeFlow,
    loader: suspend (String?) -> NetworkResult<ManagedFilesListResponse> = { path ->
        safeApiRead { ApiClient.hermesApi.listManagedFiles(path) }
    },
) {
    val coroutineScope = rememberCoroutineScope()
    val controller =
        remember(scopes) {
            RemoteFolderPickerController(
                loader = loader,
                scopes = scopes,
                coroutineScope = coroutineScope,
            )
        }

    DisposableEffect(controller) {
        onDispose {
            controller.cancel()
        }
    }

    LaunchedEffect(controller) {
        controller.load(null)
    }

    val state by controller.state.collectAsState()
    val currentScope by scopes.collectAsState()

    RemoteFolderPickerDialogView(
        state = state,
        canConfirm = state.canSelectCurrent && state.listingScope == currentScope,
        onNavigateUp = controller::navigateUp,
        onSelectEntry = controller::openDirectory,
        onRetry = controller::retry,
        onConfirm = {
            val selected = controller.selectCurrent()
            if (selected != null) {
                onSelectFolder(selected)
                onDismiss()
            }
        },
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

/**
 * Stateless view wrapper for the entire AlertDialog to allow full dialog preview and testing.
 */
@Composable
fun RemoteFolderPickerDialogView(
    state: RemoteFolderPickerState,
    canConfirm: Boolean,
    onNavigateUp: () -> Unit,
    onSelectEntry: (ManagedFileEntry) -> Unit,
    onRetry: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag("remote_folder_picker_dialog"),
        title = {
            Text(stringResource(R.string.projects_picker_title), style = MaterialTheme.typography.titleMedium)
        },
        text = {
            RemoteFolderPickerContent(
                state = state,
                onNavigateUp = onNavigateUp,
                onSelectEntry = onSelectEntry,
                onRetry = onRetry,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = canConfirm,
                modifier = Modifier.testTag("remote_folder_picker_confirm"),
            ) {
                Text(stringResource(R.string.projects_picker_select_folder))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("remote_folder_picker_cancel"),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * Stateless content of the remote folder picker for testability and preview rendering.
 */
@Composable
fun RemoteFolderPickerContent(
    state: RemoteFolderPickerState,
    onNavigateUp: () -> Unit,
    onSelectEntry: (ManagedFileEntry) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current

    Column(modifier = modifier) {
        // Path breadcrumb / header row
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onNavigateUp,
                enabled = state.parentPath != null && !state.isLoading,
                modifier = Modifier.size(48.dp).testTag("remote_folder_picker_up"),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.projects_picker_up_desc),
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.width(spacing.xs))
            val displayPath =
                when {
                    state.isLoading && !state.isConfirmed -> stringResource(R.string.projects_picker_loading_path)
                    state.isConfirmed -> state.resolveCurrentAbsolutePath() ?: state.currentPath.ifEmpty { "/" }
                    state.currentPath.isNotEmpty() -> state.currentPath
                    else -> stringResource(R.string.projects_picker_path_unavailable)
                }
            Text(
                text = displayPath,
                style =
                    MaterialTheme.typography.bodySmall.copy(
                        textDirection = TextDirection.Ltr,
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = spacing.xs))

        // Content area: loading / error / empty / directory list
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 280.dp),
            contentAlignment = Alignment.Center,
        ) {
            val listInteractionEnabled = !state.isLoading && state.errorMessage == null

            when {
                state.isLoading && state.directories.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 2.dp)
                }

                state.errorMessage != null && state.directories.isEmpty() -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(spacing.xs),
                        modifier = Modifier.padding(spacing.md),
                    ) {
                        Text(
                            text = state.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        IconButton(
                            onClick = onRetry,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.action_retry),
                            )
                        }
                    }
                }

                state.directories.isEmpty() && state.isConfirmed -> {
                    Text(
                        text = stringResource(R.string.projects_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (state.errorMessage != null) {
                            Row(
                                modifier =
                                    Modifier.fillMaxWidth().padding(
                                        horizontal = spacing.xs,
                                        vertical = spacing.xs,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = state.errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = onRetry,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = stringResource(R.string.action_retry),
                                    )
                                }
                            }
                        }

                        if (state.isLoading) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }

                        LazyColumn(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .let { if (!listInteractionEnabled) it.alpha(0.5f) else it },
                        ) {
                            items(state.directories, key = { it.path }) { dir ->
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .clickable(enabled = listInteractionEnabled) { onSelectEntry(dir) }
                                            .padding(vertical = spacing.xs, horizontal = spacing.xs),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(modifier = Modifier.width(spacing.sm))
                                    Text(
                                        text = dir.name,
                                        style =
                                            MaterialTheme.typography.bodyMedium.copy(
                                                textDirection = TextDirection.Ltr,
                                            ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun RemoteFolderPickerDialogPreview() {
    val spacing = LocalSpacing.current
    HermesControlTheme {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.padding(16.dp),
        ) {
            Column(modifier = Modifier.padding(spacing.lg)) {
                Text(
                    stringResource(R.string.projects_picker_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = spacing.md),
                )
                RemoteFolderPickerContent(
                    state =
                        RemoteFolderPickerState(
                            currentPath = "/opt/hermes/projects",
                            parentPath = "/opt/hermes",
                            root = "/opt/hermes",
                            isConfirmed = true,
                            directories =
                                listOf(
                                    ManagedFileEntry(
                                        name = "client",
                                        path = "/opt/hermes/projects/client",
                                        isDirectory = true,
                                    ),
                                    ManagedFileEntry(
                                        name = "server",
                                        path = "/opt/hermes/projects/server",
                                        isDirectory = true,
                                    ),
                                    ManagedFileEntry(
                                        name = "docs",
                                        path = "/opt/hermes/projects/docs",
                                        isDirectory = true,
                                    ),
                                ),
                        ),
                    onNavigateUp = {},
                    onSelectEntry = {},
                    onRetry = {},
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = {}) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    TextButton(onClick = {}) {
                        Text(stringResource(R.string.projects_picker_select_folder))
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RemoteFolderPickerContentPreview() {
    HermesControlTheme {
        Surface {
            RemoteFolderPickerContent(
                state =
                    RemoteFolderPickerState(
                        currentPath = "/opt/hermes/projects",
                        parentPath = "/opt/hermes",
                        root = "/opt/hermes",
                        isConfirmed = true,
                        directories =
                            listOf(
                                ManagedFileEntry(
                                    name = "client",
                                    path = "/opt/hermes/projects/client",
                                    isDirectory = true,
                                ),
                                ManagedFileEntry(
                                    name = "server",
                                    path = "/opt/hermes/projects/server",
                                    isDirectory = true,
                                ),
                                ManagedFileEntry(name = "docs", path = "/opt/hermes/projects/docs", isDirectory = true),
                            ),
                    ),
                onNavigateUp = {},
                onSelectEntry = {},
                onRetry = {},
                modifier = Modifier.width(320.dp).padding(16.dp),
            )
        }
    }
}
