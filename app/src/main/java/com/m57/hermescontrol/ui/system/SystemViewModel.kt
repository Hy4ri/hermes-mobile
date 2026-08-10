package com.m57.hermescontrol.ui.system

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.data.model.ActionResponse
import com.m57.hermescontrol.data.model.ActionStatusResponse
import com.m57.hermescontrol.data.model.BackupTriggerRequest
import com.m57.hermescontrol.data.model.CheckpointsResponse
import com.m57.hermescontrol.data.model.CredentialPoolProvider
import com.m57.hermescontrol.data.model.CuratorResponse
import com.m57.hermescontrol.data.model.DebugShareResponse
import com.m57.hermescontrol.data.model.DoctorResponse
import com.m57.hermescontrol.data.model.HookResponse
import com.m57.hermescontrol.data.model.PortalResponse
import com.m57.hermescontrol.data.model.StatusResponse
import com.m57.hermescontrol.data.model.SystemStatsResponse
import com.m57.hermescontrol.data.model.UpdateCheckResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ActionProgressController
import com.m57.hermescontrol.ui.common.ToastHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

data class SystemUiState(
    val isLoading: Boolean = false,
    val stats: SystemStatsResponse? = null,
    val portal: PortalResponse? = null,
    val curator: CuratorResponse? = null,
    val credentials: List<CredentialPoolProvider> = emptyList(),
    val checkpoints: CheckpointsResponse? = null,
    val hooks: HookResponse? = null,
    val updateInfo: UpdateCheckResponse? = null,
    val status: StatusResponse? = null,
    val doctorReport: DoctorResponse? = null,
    val activeAction: String? = null,
    val actionLog: ActionStatusResponse? = null,
    val backupArchive: String? = null,
    val downloadableBackup: String? = null,
    val debugShare: DebugShareResponse? = null,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    // Form states
    val credProvider: String = "openrouter",
    val credKey: String = "",
    val credLabel: String = "",
    val addingCred: Boolean = false,
    // Hook modal
    val hookModalOpen: Boolean = false,
    val hookEvent: String = "pre_tool_call",
    val hookCommand: String = "",
    val hookMatcher: String = "",
    val hookTimeout: String = "",
    val hookApprove: Boolean = true,
    val creatingHook: Boolean = false,
    // Import (issue #786): SAF-picked backup archive staged by the screen
    val importFileName: String? = null,
    val isImporting: Boolean = false,
    // True while the async backup-archive retry loop is running
    val isDownloading: Boolean = false,
    // Debug share
    val shareRedact: Boolean = true,
    val sharing: Boolean = false,
    // Update
    val checkingUpdate: Boolean = false,
    val updateConfirmOpen: Boolean = false,
)

class SystemViewModel :
    ViewModel(),
    ToastHost {
    companion object {
        private const val TAG = "SystemViewModel"

        // The backup action is async — retry a 404 download until the zip
        // exists (up to ~3 minutes, 328MB backups took ~70s in testing).
        private const val DOWNLOAD_RETRY_DELAY_MS = 5_000L
        private const val MAX_DOWNLOAD_ATTEMPTS = 36
    }

    private val _uiState = MutableStateFlow(SystemUiState())
    val uiState: StateFlow<SystemUiState> = _uiState.asStateFlow()

    /**
     * Progress popup for the update flow (issue #863): after the trigger POST
     * returns, [ActionProgressController] polls the action status log until
     * the update exits, then refreshes the whole screen so the version row
     * and update badge reflect the new build.
     */
    val actionProgress =
        ActionProgressController(
            scope = viewModelScope,
            onFinished = { loadAll() },
        )

    private var actionPollingJob: Job? = null
    private var downloadJob: Job? = null

    // ── Full parallel data load ────────────────────────────────────────

    fun loadAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            coroutineScope {
                val statsDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getSystemStats() } }
                val statusDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getStatus() } }
                val portalDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getPortal() } }
                val curatorDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getCurator() } }

                val credDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getCredentialPool() } }
                val checkpointsDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getCheckpoints() } }
                val hooksDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getHooks() } }
                val updateDeferred =
                    async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.checkHermesUpdate(false) } }
                val doctorDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.runDoctor() } }

                val statsResult = statsDeferred.await()
                val statusResult = statusDeferred.await()
                val portalResult = portalDeferred.await()
                val curatorResult = curatorDeferred.await()

                val credResult = credDeferred.await()
                val checkpointsResult = checkpointsDeferred.await()
                val hooksResult = hooksDeferred.await()
                val updateResult = updateDeferred.await()
                val doctorResult = doctorDeferred.await()

                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        stats = (statsResult as? NetworkResult.Success)?.data,
                        status = (statusResult as? NetworkResult.Success)?.data,
                        portal = (portalResult as? NetworkResult.Success)?.data,
                        curator = (curatorResult as? NetworkResult.Success)?.data,
                        credentials =
                            ((credResult as? NetworkResult.Success)?.data)?.providers ?: emptyList(),
                        checkpoints = (checkpointsResult as? NetworkResult.Success)?.data,
                        hooks = (hooksResult as? NetworkResult.Success)?.data,
                        updateInfo = (updateResult as? NetworkResult.Success)?.data,
                        doctorReport = (doctorResult as? NetworkResult.Success)?.data,
                        errorMessage = null,
                    )
                }

                // Log failures in debug builds
                if (BuildConfig.DEBUG) {
                    listOf(
                        "stats" to statsResult,
                        "status" to statusResult,
                        "portal" to portalResult,
                        "curator" to curatorResult,
                        "credentials" to credResult,
                        "checkpoints" to checkpointsResult,
                        "hooks" to hooksResult,
                        "update" to updateResult,
                        "doctor" to doctorResult,
                    ).forEach { (name, result) ->
                        if (result is NetworkResult.Failure) {
                            Log.w(TAG, "$name endpoint: ${result.error.message}")
                        }
                    }
                }
            }
        }
    }

    // ── Targeted Data Reloads ──────────────────────────────────────────

    fun loadCredentials() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getCredentialPool() } }
            if (result is NetworkResult.Success) {
                _uiState.update { it.copy(credentials = result.data.providers ?: emptyList()) }
            }
        }
    }

    fun loadHooks() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getHooks() } }
            if (result is NetworkResult.Success) {
                _uiState.update { it.copy(hooks = result.data) }
            }
        }
    }

    fun loadCurator() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getCurator() } }
            if (result is NetworkResult.Success) {
                _uiState.update { it.copy(curator = result.data) }
            }
        }
    }

    fun loadStatus() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getStatus() } }
            if (result is NetworkResult.Success) {
                _uiState.update { it.copy(status = result.data) }
            }
        }
    }

    // ── Gateway actions ────────────────────────────────────────────────

    fun startGateway() {
        runGatewayAction("start") { safeApiCall { ApiClient.hermesApi.startGateway() } }
    }

    fun stopGateway() {
        runGatewayAction("stop") { safeApiCall { ApiClient.hermesApi.stopGateway() } }
    }

    fun restartGateway() {
        runGatewayAction("restart") { safeApiCall { ApiClient.hermesApi.restartGateway() } }
    }

    private fun runGatewayAction(
        name: String,
        apiCall: suspend () -> NetworkResult<Unit>,
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { apiCall() }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Gateway ${name}ed successfully") }
                    loadStatus()
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to $name gateway: ${result.error.message}") }
                }
            }
        }
    }

    // ── Update actions ─────────────────────────────────────────────────

    fun checkForUpdate(force: Boolean) {
        _uiState.update { it.copy(checkingUpdate = true) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.checkHermesUpdate(force) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(checkingUpdate = false, updateInfo = result.data) }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            checkingUpdate = false,
                            toastMessage = "Update check failed: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    /**
     * Run the update and surface its progress in [ActionProgressController]'s
     * popup: the trigger POST returns immediately (`{ok, name}`) while the
     * actual update runs in the background, so the dialog polls the action
     * status log until it exits (success/failure + tail shown to the user).
     */
    fun applyUpdate() {
        actionProgress.open()
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.updateHermes() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    val name = result.data.name
                    if (name != null) {
                        actionProgress.markStarted(name)
                    } else {
                        actionProgress.fail(
                            "Update started but the backend did not report an action name",
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    actionProgress.fail("Failed to start update: ${result.error.message}")
                }
            }
        }
    }

    fun openUpdateConfirm() {
        _uiState.update { it.copy(updateConfirmOpen = true) }
    }

    fun closeUpdateConfirm() {
        _uiState.update { it.copy(updateConfirmOpen = false) }
    }

    // ── Curator actions ────────────────────────────────────────────────

    fun toggleCuratorPaused() {
        val currentlyPaused = _uiState.value.curator?.paused ?: return
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.setCuratorPaused(mapOf("paused" to !currentlyPaused)) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Curator ${if (!currentlyPaused) "paused" else "resumed"}",
                        )
                    }
                    loadCurator()
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to toggle curator: ${result.error.message}") }
                }
            }
        }
    }

    fun runCuratorNow() {
        runOperation(
            apiCall = { safeApiCall { ApiClient.hermesApi.runCurator() } },
            label = "Curator run",
        )
    }

    // ── Credential pool actions ────────────────────────────────────────

    fun updateCredProvider(v: String) {
        _uiState.update { it.copy(credProvider = v) }
    }

    fun updateCredKey(v: String) {
        _uiState.update { it.copy(credKey = v) }
    }

    fun updateCredLabel(v: String) {
        _uiState.update { it.copy(credLabel = v) }
    }

    fun addCredential() {
        val state = _uiState.value
        if (state.credKey.isBlank()) {
            _uiState.update { it.copy(toastMessage = "Key/token is required") }
            return
        }
        _uiState.update { it.copy(addingCred = true) }
        viewModelScope.launch {
            val body =
                buildMap {
                    put("provider", state.credProvider)
                    put("token", state.credKey)
                    if (state.credLabel.isNotBlank()) put("label", state.credLabel)
                }
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.addCredentialPoolEntry(body) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            addingCred = false,
                            credKey = "",
                            credLabel = "",
                            toastMessage = "Credential added",
                        )
                    }
                    loadCredentials()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(addingCred = false, toastMessage = "Failed to add credential: ${result.error.message}")
                    }
                }
            }
        }
    }

    fun removeCredential(
        provider: String,
        index: Int,
    ) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.removeCredentialPoolEntry(provider, index) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Credential removed") }
                    loadCredentials()
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to remove credential: ${result.error.message}") }
                }
            }
        }
    }

    // ── Operation actions ──────────────────────────────────────────────

    fun runSecurityAudit() {
        runOperation(
            apiCall = { safeApiCall { ApiClient.hermesApi.runSecurityAudit() } },
            label = "Security audit",
        )
    }

    fun runPromptSize() {
        runOperation(
            apiCall = { safeApiCall { ApiClient.hermesApi.runPromptSize() } },
            label = "Prompt size check",
        )
    }

    fun runDump() {
        runOperation(
            apiCall = { safeApiCall { ApiClient.hermesApi.runDump() } },
            label = "Dump",
        )
    }

    fun runConfigMigrate() {
        runOperation(
            apiCall = { safeApiCall { ApiClient.hermesApi.runConfigMigrate() } },
            label = "Config migrate",
        )
    }

    fun runUpdateSkills() {
        runOperation(
            apiCall = { safeApiCall { ApiClient.hermesApi.updateSkillsFromHub() } },
            label = "Skills update",
        )
    }

    // ── Backup actions ─────────────────────────────────────────────────

    fun triggerBackup() {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.triggerBackup(BackupTriggerRequest())
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Backup triggered",
                            backupArchive = result.data.archive,
                        )
                    }
                    loadAll()
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to trigger backup: ${result.error.message}") }
                }
            }
        }
    }

    /**
     * Download the triggered backup archive. The backup action is ASYNC — the
     * trigger returns the path instantly, but the zip (can be hundreds of MB)
     * takes a while to write. The backend 404s ("Backup not found") until the
     * file exists, so a 404 is retried with a delay instead of failing the tap.
     * The @Streaming body is handed to [onBody] — the screen streams it to
     * Downloads via MediaImageStore (never buffered in memory).
     */
    fun downloadBackup(onBody: (body: okhttp3.ResponseBody, fileName: String) -> Unit) {
        val archive =
            _uiState.value.backupArchive ?: run {
                _uiState.update { it.copy(toastMessage = "No backup archive available") }
                return
            }
        // Single-flight: a second tap while a retry loop is running must not
        // spawn a parallel loop (logcat showed interleaved duplicate requests).
        downloadJob?.cancel()
        downloadJob =
            viewModelScope.launch {
                _uiState.update { it.copy(isDownloading = true) }
                try {
                    var attempt = 0
                    while (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                        val result =
                            withContext(Dispatchers.IO) {
                                safeApiCall { ApiClient.hermesApi.downloadBackup(archive) }
                            }
                        when (result) {
                            is NetworkResult.Success -> {
                                // @Streaming body — hand it off as-is; the screen
                                // streams it to Downloads (never bytes() — OOM).
                                onBody(result.data, archive.substringAfterLast('/'))
                                return@launch
                            }

                            is NetworkResult.Failure -> {
                                val code = (result.error as? NetworkError.Http)?.code
                                if (code == 404) {
                                    // Live countdown so the wait is visible —
                                    // 328MB backups took ~60s in testing.
                                    val elapsed = attempt * DOWNLOAD_RETRY_DELAY_MS / 1000
                                    _uiState.update {
                                        it.copy(toastMessage = "Waiting for backup… ${elapsed}s")
                                    }
                                    attempt++
                                    delay(DOWNLOAD_RETRY_DELAY_MS)
                                } else {
                                    _uiState.update {
                                        it.copy(toastMessage = "Failed to download backup: ${result.error.message}")
                                    }
                                    return@launch
                                }
                            }
                        }
                    }
                    _uiState.update { it.copy(toastMessage = "Backup is still being created — try again in a moment") }
                } finally {
                    _uiState.update { it.copy(isDownloading = false) }
                }
            }
    }

    fun setImportFile(name: String) {
        _uiState.update { it.copy(importFileName = name) }
    }

    fun clearImportFile() {
        _uiState.update { it.copy(importFileName = null) }
    }

    fun importArchive(
        fileName: String,
        bytes: ByteArray,
        mimeType: String,
    ) {
        // Mirrors FilesViewModel.uploadFile: multipart file + force (issue #786)
        val forceBody = "false".toRequestBody("text/plain".toMediaTypeOrNull())
        val part =
            MultipartBody.Part.createFormData(
                "file",
                fileName,
                bytes.toRequestBody(mimeType.toMediaTypeOrNull()),
            )
        _uiState.update { it.copy(isImporting = true) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.importUpload(forceBody, part)
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    result.data.name?.let { pollActionStatus(it) }
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            importFileName = null,
                            toastMessage = "Import started",
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            toastMessage = "Import failed: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun showToast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    // ── Debug share actions ────────────────────────────────────────────

    fun toggleShareRedact() {
        _uiState.update { it.copy(shareRedact = !it.shareRedact) }
    }

    fun runDebugShare() {
        val shareRedact = _uiState.value.shareRedact
        _uiState.update { it.copy(sharing = true) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.runDebugShare(mapOf("redact" to shareRedact)) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            sharing = false,
                            debugShare = result.data,
                            toastMessage = "Debug share created",
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            sharing = false,
                            toastMessage = "Debug share failed: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    // ── Checkpoints actions ────────────────────────────────────────────

    fun pruneCheckpoints() {
        runOperation(
            apiCall = { safeApiCall { ApiClient.hermesApi.pruneCheckpoints() } },
            label = "Checkpoint prune",
        )
    }

    // ── Hook actions ───────────────────────────────────────────────────

    fun toggleHookModal() {
        _uiState.update { it.copy(hookModalOpen = !it.hookModalOpen) }
    }

    fun updateHookEvent(v: String) {
        _uiState.update { it.copy(hookEvent = v) }
    }

    fun updateHookCommand(v: String) {
        _uiState.update { it.copy(hookCommand = v) }
    }

    fun updateHookMatcher(v: String) {
        _uiState.update { it.copy(hookMatcher = v) }
    }

    fun updateHookTimeout(v: String) {
        _uiState.update { it.copy(hookTimeout = v) }
    }

    fun updateHookApprove(v: Boolean) {
        _uiState.update { it.copy(hookApprove = v) }
    }

    fun createHook() {
        val state = _uiState.value
        if (state.hookCommand.isBlank()) {
            _uiState.update { it.copy(toastMessage = "Command is required") }
            return
        }
        _uiState.update { it.copy(creatingHook = true) }
        viewModelScope.launch {
            val body =
                buildMap<String, Any> {
                    put("event", state.hookEvent)
                    put("command", state.hookCommand)
                    if (state.hookMatcher.isNotBlank()) put("matcher", state.hookMatcher)
                    if (state.hookTimeout.isNotBlank()) put("timeout", state.hookTimeout.toIntOrNull() ?: 30)
                    put("allowed", state.hookApprove)
                }
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.createHook(body) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            creatingHook = false,
                            hookModalOpen = false,
                            hookCommand = "",
                            hookMatcher = "",
                            hookTimeout = "",
                            toastMessage = "Hook created",
                        )
                    }
                    loadHooks()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            creatingHook = false,
                            toastMessage = "Failed to create hook: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun deleteHook(
        event: String,
        command: String,
    ) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.deleteHook(mapOf("event" to event, "command" to command)) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Hook deleted") }
                    loadHooks()
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to delete hook: ${result.error.message}") }
                }
            }
        }
    }

    // ── Action log polling ─────────────────────────────────────────────

    private fun pollActionStatus(name: String) {
        actionPollingJob?.cancel()
        _uiState.update { it.copy(activeAction = name, actionLog = null) }
        actionPollingJob =
            viewModelScope.launch {
                while (isActive) {
                    delay(1200)
                    val result =
                        withContext(Dispatchers.IO) {
                            safeApiCall { ApiClient.hermesApi.getActionStatus(name) }
                        }
                    if (result is NetworkResult.Success) {
                        _uiState.update { it.copy(actionLog = result.data) }
                        if (result.data.running != true) {
                            loadAll()
                            break
                        }
                    }
                }
            }
    }

    fun closeActionLog() {
        actionPollingJob?.cancel()
        _uiState.update { it.copy(activeAction = null, actionLog = null) }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun runOperation(
        apiCall: suspend () -> NetworkResult<ActionResponse>,
        label: String,
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { apiCall() }
            when (result) {
                is NetworkResult.Success -> {
                    result.data.name?.let { pollActionStatus(it) }
                    _uiState.update { it.copy(toastMessage = "$label started") }
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "$label failed: ${result.error.message}") }
                }
            }
        }
    }

    // ── Transient state ────────────────────────────────────────────────

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
