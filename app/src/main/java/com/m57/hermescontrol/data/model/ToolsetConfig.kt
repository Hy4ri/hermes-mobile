package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

/**
 * Per-toolset management payloads — issue #782.
 *
 * Contracts verified against hermes-agent hermes_cli/web_routers/tools.py:
 * - GET  /api/tools/toolsets/{name}/config → provider matrix + key status
 *   (web toolset additionally resolves active_search_backend /
 *   active_extract_backend the way the runtime dispatchers do).
 * - PUT  /api/tools/toolsets/{name}/provider {provider} → {ok, name, provider,
 *   needs_nous_auth?, feature?} (managed Nous rows without Portal entitlement
 *   write the keys but won't activate until sign-in).
 * - PUT  /api/tools/toolsets/{name}/env {env: {KEY: value}} → {ok, name,
 *   saved[], skipped[], is_set{}}; keys validated against the toolset
 *   category's env-var allowlist, blank values skipped ("leave unchanged").
 * - POST /api/tools/toolsets/{name}/post-setup {key} → {ok, pid, name,
 *   key}; spawns `hermes tools post-setup <key>` and the client tails
 *   GET /api/actions/{name}/status (see ActionStatusResponse).
 */
@Serializable
data class ToolsetConfigResponse(
    val name: String,
    val hasCategory: Boolean = false,
    val providers: List<ToolsetProvider> = emptyList(),
    val activeProvider: String? = null,
    val activeSearchBackend: String? = null,
    val activeExtractBackend: String? = null,
)

@Serializable
data class ToolsetProvider(
    val name: String,
    val badge: String? = null,
    val tag: String? = null,
    val envVars: List<ToolsetEnvVar> = emptyList(),
    val postSetup: String? = null,
    val requiresNousAuth: Boolean = false,
    val isActive: Boolean = false,
    val status: String? = null,
)

@Serializable
data class ToolsetEnvVar(
    val key: String,
    val prompt: String? = null,
    val url: String? = null,
    val default: String? = null,
    val isSet: Boolean = false,
)

@Serializable
data class ToolsetProviderSelectRequest(
    val provider: String,
    val profile: String? = null,
)

@Serializable
data class ToolsetProviderSelectResponse(
    val ok: Boolean = false,
    val name: String? = null,
    val provider: String? = null,
    val needsNousAuth: Boolean = false,
    val feature: String? = null,
)

@Serializable
data class ToolsetEnvUpdateRequest(
    val env: Map<String, String>,
    val profile: String? = null,
)

@Serializable
data class ToolsetEnvUpdateResponse(
    val ok: Boolean = false,
    val name: String? = null,
    val saved: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val isSet: Map<String, Boolean> = emptyMap(),
)

@Serializable
data class ToolsetPostSetupRequest(
    val key: String,
    val profile: String? = null,
)

@Serializable
data class ToolsetPostSetupResponse(
    val ok: Boolean = false,
    val pid: Int? = null,
    val name: String? = null,
    val key: String? = null,
)
