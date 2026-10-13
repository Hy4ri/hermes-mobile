package com.m57.hermescontrol.ui.files

import android.content.ContentResolver
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.local.SwrCache
import com.m57.hermescontrol.data.model.ManagedDirectoryCreate
import com.m57.hermescontrol.data.model.ManagedFileActionResponse
import com.m57.hermescontrol.data.model.ManagedFileDelete
import com.m57.hermescontrol.data.model.ManagedFileEntry
import com.m57.hermescontrol.data.model.ManagedFilesListResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.remote.safeApiRead
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchSwrLoad
import com.m57.hermescontrol.util.StreamingUriRequestBody
import com.m57.hermescontrol.util.uploadMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class FilesUiState(
    val currentPath: String = "",
    val parentPath: String? = null,
    /** Breadcrumb parts: pair of (absolute path, display name). */
    val crumbs: List<Pair<String, String>> = emptyList(),
    val entries: List<ManagedFileEntry> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    // ── Transient action state ──
    val busyPaths: Set<String> = emptySet(),
    val isCreatingDir: Boolean = false,
    val newDirName: String = "",
    val isUploading: Boolean = false,
    val uploadProgress: Float? = null,
    val deleteTarget: ManagedFileEntry? = null,
    val openingPath: String? = null,
    val toastMessage: String? = null,
)

/** Decoded file ready to be written to disk and opened on-device. */
data class DownloadedFile(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DownloadedFile) return false
        return name == other.name &&
            mimeType == other.mimeType &&
            (bytes?.contentEquals(other.bytes) == true)
    }

    override fun hashCode(): Int {
        var r = name.hashCode()
        r = 31 * r + mimeType.hashCode()
        r = 31 * r + (bytes?.contentHashCode() ?: 0)
        return r
    }
}

/** Map a successful [NetworkResult] to a new value; pass failures through. */
inline fun <T, R> NetworkResult<T>.map(transform: (T) -> R): NetworkResult<R> =
    when (this) {
        is NetworkResult.Success -> NetworkResult.Success(transform(this.data))
        is NetworkResult.Failure -> this
    }

class FilesViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val getCurrentScope: () -> DataScope? = { runCatching { AuthManager.currentDataScope() }.getOrNull() },
    private val getApiService: () -> HermesApiService = { ApiClient.hermesApi },
) : ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    private val filesCache = SwrCache<String, ManagedFilesListResponse>()

    @Volatile
    private var uploadGeneration: Int = 0
    private var uploadJob: Job? = null

    /**
     * Reset state owned by the previous server/profile context (issue: PR #1192 cache scoping).
     * The current directory path belongs to the old server and must not leak into the new one.
     */
    fun clearScopeOwnedState() {
        uploadGeneration++
        uploadJob?.cancel()
        uploadJob = null
        _uiState.update {
            it.copy(
                currentPath = "",
                parentPath = null,
                crumbs = emptyList(),
                entries = emptyList(),
                isLoading = false,
                errorMessage = null,
                busyPaths = emptySet(),
                isCreatingDir = false,
                newDirName = "",
                isUploading = false,
                uploadProgress = null,
                deleteTarget = null,
                openingPath = null,
            )
        }
    }

    fun load(
        path: String? = null,
        forceRefresh: Boolean = false,
    ) {
        val cacheKey = path ?: ""
        safeLaunchSwrLoad(
            cache = filesCache,
            cacheKey = cacheKey,
            forceRefresh = forceRefresh,
            onCacheHit = { data ->
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        currentPath = data.path,
                        parentPath = data.parent,
                        crumbs = buildCrumbs(data.path),
                        entries = data.entries,
                        errorMessage = null,
                    )
                }
            },
            apiCall = {
                safeApiRead { ApiClient.hermesApi.listManagedFiles(path) }
            },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        currentPath = data.path,
                        parentPath = data.parent,
                        crumbs = buildCrumbs(data.path),
                        entries = data.entries,
                    )
                }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to list files: $errorMsg",
                    )
                }
            },
        )
    }

    fun refresh() = load(_uiState.value.currentPath.ifBlank { null }, forceRefresh = true)

    fun navigateTo(entry: ManagedFileEntry) {
        if (!entry.isDirectory) return
        load(entry.path)
    }

    fun navigateUp() {
        _uiState.value.parentPath?.let { load(it) }
    }

    fun navigateToCrumb(path: String) = load(path)

    // ── Create directory ──────────────────────────────────────────────────
    fun openCreateDir() {
        _uiState.update { it.copy(isCreatingDir = true, newDirName = "") }
    }

    fun dismissCreateDir() {
        _uiState.update { it.copy(isCreatingDir = false, newDirName = "") }
    }

    fun setNewDirName(name: String) {
        _uiState.update { it.copy(newDirName = name) }
    }

    fun createDir() {
        val name = _uiState.value.newDirName.trim()
        val base = _uiState.value.currentPath
        if (name.isBlank()) {
            _uiState.update { it.copy(toastMessage = "Folder name is required") }
            return
        }
        if (name.contains("/")) {
            _uiState.update { it.copy(toastMessage = "Folder name cannot contain /") }
            return
        }
        val target = if (base.isBlank()) name else "$base/$name"
        _uiState.update { it.copy(isCreatingDir = false, newDirName = "", isLoading = true) }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall {
                        ApiClient.hermesApi.createManagedDirectory(ManagedDirectoryCreate(target))
                    }
                }
            handleActionResult(result, "Folder created", "Failed to create folder")
        }
    }

    // ── Upload (streaming multipart) ──────────────────────────────────────
    fun uploadFile(
        uri: Uri,
        contentResolver: ContentResolver,
    ) {
        if (_uiState.value.isUploading) return
        val expectedScope = getCurrentScope()
        val expectedPath = _uiState.value.currentPath
        val service = getApiService()
        val gen = ++uploadGeneration

        _uiState.update {
            it.copy(
                isUploading = true,
                uploadProgress = null,
            )
        }

        uploadJob =
            viewModelScope.launch {
                val ownerJob = coroutineContext[Job]
                try {
                    val metadata =
                        withContext(ioDispatcher) {
                            contentResolver.uploadMetadata(uri, fallbackName = "upload.bin", dispatcher = ioDispatcher)
                        }

                    if (metadata.size > StreamingUriRequestBody.MAX_UPLOAD_BYTES) {
                        throw IOException("Selected file exceeds the upload size limit")
                    }

                    val target = if (expectedPath.isBlank()) metadata.name else "$expectedPath/${metadata.name}"
                    val pathBody = target.toRequestBody("text/plain".toMediaTypeOrNull())
                    val overwriteBody = "true".toRequestBody("text/plain".toMediaTypeOrNull())

                    val requestBody =
                        StreamingUriRequestBody(
                            contentResolver = contentResolver,
                            uri = uri,
                            contentType = metadata.mimeType.toMediaTypeOrNull(),
                            contentLength = metadata.size,
                            maxBytes = StreamingUriRequestBody.MAX_UPLOAD_BYTES,
                            onProgress = { bytesWritten, totalBytes ->
                                if (gen == uploadGeneration &&
                                    getCurrentScope() == expectedScope &&
                                    _uiState.value.currentPath == expectedPath
                                ) {
                                    val progressFraction =
                                        if (totalBytes > 0L) {
                                            (bytesWritten.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                        } else {
                                            null
                                        }
                                    _uiState.update {
                                        if (gen == uploadGeneration && getCurrentScope() == expectedScope &&
                                            it.currentPath == expectedPath
                                        ) {
                                            it.copy(uploadProgress = progressFraction)
                                        } else {
                                            it
                                        }
                                    }
                                }
                            },
                            isCancelled = { ownerJob?.isActive != true || gen != uploadGeneration },
                        )

                    val filePart =
                        MultipartBody.Part.createFormData(
                            name = "file",
                            filename = metadata.name,
                            body = requestBody,
                        )

                    val result =
                        withContext(ioDispatcher) {
                            safeApiCall(retries = 0) {
                                service.uploadManagedFileStream(pathBody, overwriteBody, filePart)
                            }
                        }

                    if (gen != uploadGeneration ||
                        getCurrentScope() != expectedScope ||
                        _uiState.value.currentPath != expectedPath
                    ) {
                        return@launch
                    }

                    when (result) {
                        is NetworkResult.Success -> {
                            if (!result.data.ok) throw IOException("Gateway refused the upload")
                            _uiState.update {
                                it.copy(
                                    toastMessage = "File uploaded",
                                )
                            }
                            refresh()
                        }

                        is NetworkResult.Failure -> {
                            _uiState.update {
                                it.copy(
                                    toastMessage = "Failed to upload file: ${result.error.message}",
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (gen == uploadGeneration &&
                        getCurrentScope() == expectedScope &&
                        _uiState.value.currentPath == expectedPath
                    ) {
                        _uiState.update {
                            it.copy(
                                toastMessage = "Failed to upload file: ${e.message}",
                            )
                        }
                    }
                } finally {
                    if (gen == uploadGeneration) {
                        uploadJob = null
                        _uiState.update {
                            it.copy(
                                isUploading = false,
                                uploadProgress = null,
                            )
                        }
                    }
                }
            }
    }

    // ── Delete ────────────────────────────────────────────────────────────
    fun requestDelete(entry: ManagedFileEntry) {
        _uiState.update { it.copy(deleteTarget = entry) }
    }

    fun dismissDelete() {
        _uiState.update { it.copy(deleteTarget = null) }
    }

    fun confirmDelete() {
        val target = _uiState.value.deleteTarget ?: return
        _uiState.update {
            it.copy(
                deleteTarget = null,
                busyPaths = it.busyPaths + target.path,
            )
        }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall {
                        ApiClient.hermesApi.deleteManagedFile(
                            ManagedFileDelete(target.path, recursive = target.isDirectory),
                        )
                    }
                }
            val cleared =
                _uiState.value.busyPaths - target.path
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            busyPaths = cleared,
                            toastMessage = "Deleted ${target.name}",
                        )
                    }
                    refresh()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            busyPaths = cleared,
                            toastMessage = "Failed to delete: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    // ── Read / open (in-app download + base64 decode) ───────────────────────
    // The backend returns file bytes as a base64 `data_url` (GET /api/files/read).
    // We decode in-app and hand the raw bytes to the Screen, which writes them
    // to cacheDir and opens via FileProvider — reusing the normal Bearer/session
    // auth instead of the browser-only `?token=` escape hatch.
    fun downloadFile(
        entry: ManagedFileEntry,
        onResult: (NetworkResult<DownloadedFile>) -> Unit,
    ) {
        _uiState.update { it.copy(openingPath = entry.path) }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiRead { ApiClient.hermesApi.readManagedFile(entry.path) }
                        .map { read ->
                            val bytes = decodeDataUrl(read.dataUrl)
                            DownloadedFile(
                                name = entry.name,
                                mimeType = read.mimeType.ifBlank { "application/octet-stream" },
                                bytes = bytes,
                            )
                        }
                }
            _uiState.update { it.copy(openingPath = null) }
            onResult(result)
        }
    }

    private fun decodeDataUrl(dataUrl: String): ByteArray? {
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return null
        val payload = dataUrl.substring(comma + 1)
        return runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()
    }

    private fun handleActionResult(
        result: NetworkResult<ManagedFileActionResponse>,
        successMsg: String,
        errorMsg: String,
    ) {
        when (result) {
            is NetworkResult.Success -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        toastMessage = successMsg,
                    )
                }
                refresh()
            }

            is NetworkResult.Failure -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        toastMessage = "$errorMsg: ${result.error.message}",
                    )
                }
            }
        }
    }

    fun showToast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    /** Reset transient state on screen re-entry (issue #619 pattern). */
    fun clearTransientState() {
        // #1554: leaving/re-entering cannot leave an old upload writing or publishing into the new screen.
        uploadGeneration++
        uploadJob?.cancel()
        uploadJob = null
        _uiState.update {
            it.copy(
                errorMessage = null,
                toastMessage = null,
                isCreatingDir = false,
                newDirName = "",
                isUploading = false,
                uploadProgress = null,
                deleteTarget = null,
            )
        }
    }

    private fun buildCrumbs(path: String): List<Pair<String, String>> {
        if (path.isBlank()) return emptyList()
        val parts = path.split("/").filter { it.isNotBlank() }
        val crumbs = mutableListOf<Pair<String, String>>()
        var acc = ""
        for (part in parts) {
            acc += "/$part"
            crumbs.add(acc to part)
        }
        return crumbs
    }
}
