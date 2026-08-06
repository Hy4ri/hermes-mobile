package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

/** A memory provider row as reported by `GET /api/memory` (issue #783). */
@Serializable
data class MemoryProviderStatusRow(
    val name: String = "",
    val description: String = "",
    val available: Boolean = false,
    val configured: Boolean = false,
    val status: String = "",
    val setup: MemoryProviderSetupInfo? = null,
)

/** Setup manifest for a memory provider (pip deps, external deps, env). */
@Serializable
data class MemoryProviderSetupInfo(
    val pip_dependencies: List<String> = emptyList(),
    val external_dependencies: List<MemoryProviderExternalDependency> = emptyList(),
    val required_env: List<String> = emptyList(),
    val dependencies_installed: Boolean? = null,
)

@Serializable
data class MemoryProviderExternalDependency(
    val name: String = "",
    val install: String = "",
    val check: String = "",
)

/** Per-provider config surface from `GET /api/memory/providers/{name}/config`. */
@Serializable
data class MemoryProviderConfigResponse(
    val name: String = "",
    val label: String = "",
    val docs_url: String = "",
    val fields: List<MemoryProviderField> = emptyList(),
    val setup: MemoryProviderSetupInfo? = null,
)

@Serializable
data class MemoryProviderField(
    val description: String = "",
    val group: String = "",
    val info: String? = null,
    val inline: Boolean = false,
    val is_set: Boolean = false,
    val key: String = "",
    val kind: String = "text",
    val label: String = "",
    val options: List<MemoryProviderFieldOption> = emptyList(),
    val placeholder: String = "",
    val value: String = "",
)

@Serializable
data class MemoryProviderFieldOption(
    val description: String = "",
    val label: String = "",
    val value: String = "",
)

@Serializable
data class MemoryProviderConfigUpdateRequest(
    val values: Map<String, String> = emptyMap(),
)

@Serializable
data class MemoryProviderConfigUpdateResponse(
    val ok: Boolean = false,
    val active: String? = null,
)

@Serializable
data class MemoryProviderSetupRequest(
    val values: Map<String, String> = emptyMap(),
)

/** Result of `POST /api/memory/providers/{name}/setup` — synchronous, no polling. */
@Serializable
data class MemoryProviderSetupResponse(
    val ok: Boolean = false,
    val provider: String = "",
    val results: List<MemoryProviderSetupResult> = emptyList(),
    val status: MemoryProviderStatusRow? = null,
)

@Serializable
data class MemoryProviderSetupResult(
    val kind: String = "",
    val name: String = "",
    val status: String = "",
    val command: String = "",
    val returncode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
) {
    val succeeded: Boolean
        get() = status != "failed"
}
