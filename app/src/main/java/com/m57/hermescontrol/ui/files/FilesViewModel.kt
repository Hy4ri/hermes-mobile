package com.m57.hermescontrol.ui.files

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.ManagedDirectoryCreate
import com.m57.hermescontrol.data.model.ManagedFileActionResponse
import com.m57.hermescontrol.data.model.ManagedFileDelete
import com.m57.hermescontrol.data.model.ManagedFileEntry
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

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

class FilesViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    fun load(path: String? = null) {
        safeLaunchLoad(
            apiCall = {
                safeApiCall { ApiClient.hermesApi.listManagedFiles(path) }
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

    fun refresh() = load(_uiState.value.currentPath.ifBlank { null })

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
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.createManagedDirectory(ManagedDirectoryCreate(target))
                    }
                }
            handleActionResult(result, "Folder created", "Failed to create folder")
        }
    }

    // ── Upload (streaming multipart) ──────────────────────────────────────
    fun uploadFile(
        fileName: String,
        bytes: ByteArray,
        mimeType: String,
    ) {
        val base = _uiState.value.currentPath
        val target = if (base.isBlank()) fileName else "$base/$fileName"
        val pathBody = target.toRequestBody("text/plain".toMediaTypeOrNull())
        val overwriteBody = "true".toRequestBody("text/plain".toMediaTypeOrNull())
        val part =
            MultipartBody.Part.createFormData(
                "file",
                fileName,
                bytes.toRequestBody(mimeType.toMediaTypeOrNull()),
            )
        _uiState.update { it.copy(isUploading = true) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.uploadManagedFileStream(pathBody, overwriteBody, part)
                    }
                }
            handleActionResult(result, "File uploaded", "Failed to upload file")
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
                withContext(Dispatchers.IO) {
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
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.readManagedFile(entry.path) }
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
                        isUploading = false,
                        toastMessage = successMsg,
                    )
                }
                refresh()
            }

            is NetworkResult.Failure -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isUploading = false,
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
        _uiState.update {
            it.copy(
                errorMessage = null,
                toastMessage = null,
                isCreatingDir = false,
                newDirName = "",
                isUploading = false,
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
