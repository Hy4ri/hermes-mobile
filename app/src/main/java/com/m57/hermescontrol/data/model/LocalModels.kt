package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

/** `GET /api/local-models/status` — managed llama.cpp runtime + staged GGUF models. */
@Serializable
data class LocalModelsStatus(
    val enabled: Boolean = false,
    val tag: String? = null,
    val updateAvailable: Boolean = false,
    val runtimeInstalled: Boolean = false,
    val runtimeBackend: String? = null,
    val serverRunning: Boolean = false,
    val serverBaseUrl: String? = null,
    val activeModelId: String? = null,
    /** model id -> router state (`loaded` / `ready` / `loading`). */
    val loadedModels: Map<String, String> = emptyMap(),
    val models: List<LocalStagedModel> = emptyList(),
    val modelsDir: String? = null,
)

@Serializable
data class LocalStagedModel(
    val id: String,
    val sizeBytes: Long = 0,
    val sizeLabel: String? = null,
)

/** `GET /api/local-models/hardware` — what this host can run. GPU fields are NVIDIA-only. */
@Serializable
data class LocalModelsHardware(
    val uma: Boolean = false,
    val vramTotalBytes: Long = 0,
    val vramUsableBytes: Long = 0,
    val ramTotalBytes: Long = 0,
    val ramAvailableBytes: Long = 0,
    val vramLabel: String? = null,
    val gpuName: String? = null,
    val gpuUtilPercent: Int? = null,
    val vramUsedBytes: Long? = null,
)

/** One background job (`model-download`, `runtime-install`, `model-activate`, ...). */
@Serializable
data class LocalModelJob(
    val jobId: String,
    val kind: String = "",
    val target: String = "",
    val modelId: String? = null,
    /** running | paused | done | error */
    val status: String = "running",
    val phase: String = "",
    val detail: String = "",
    val totalBytes: Long? = null,
    val doneBytes: Long = 0,
    val percent: Int? = null,
    val bytesPerSec: Double? = null,
    val etaSeconds: Double? = null,
    val error: String? = null,
    val pauseRequested: Boolean = false,
    val canPause: Boolean = false,
    val canResume: Boolean = false,
) {
    val isActive: Boolean get() = status == "running" || status == "paused"
}

@Serializable
data class LocalModelJobsResponse(
    val jobs: List<LocalModelJob> = emptyList(),
)

@Serializable
data class LocalModelJobIdBody(
    val jobId: String,
)

@Serializable
data class LocalModelJobActionResponse(
    val ok: Boolean? = null,
    val paused: Boolean? = null,
    val resumed: Boolean? = null,
)
