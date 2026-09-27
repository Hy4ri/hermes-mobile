package com.m57.hermescontrol.data.model

import com.m57.hermescontrol.util.ConnectorUrlValidator

/** Backend-owned connector setup operation snapshot. Secrets are intentionally absent. */
data class ConnectionOperationSnapshot(
    val sessionId: String?,
    val opId: String,
    val seq: Long,
    val deadlineAt: Double,
    val timeoutSeconds: Double?,
    val toolCallId: String?,
    val settled: Boolean,
    val settledBy: String?,
    val targets: List<ConnectionOperationTarget>,
    val accountOwned: Boolean = false,
)

data class ConnectionOperationTarget(
    val name: String,
    val kind: ConnectionTargetKind,
    val action: ConnectionTargetAction,
    val state: ConnectionTargetState,
    val detail: String?,
    val instructions: String?,
    val discoveryError: String?,
    val connectUrl: String?,
    val connectionId: String?,
    val attempt: String?,
    val requiredEnv: List<ConnectionEnvField>,
    val tools: List<String>,
    val hint: String?,
    /** Catalog facts for [ConnectionTargetKind.PLUGIN] / [ConnectionTargetKind.SKILL] rows only. */
    val catalog: ConnectionCatalogInfo? = null,
) {
    /** Safe validated browser URL. OAuth query parameters stay redacted from [toString]. */
    val safeConnectUrl: String?
        get() = connectUrl?.takeIf(ConnectorUrlValidator::isValidHttpsUrl)

    override fun toString(): String =
        "ConnectionOperationTarget(" +
            "name=$name, kind=$kind, action=$action, state=$state, detail=$detail, " +
            "instructions=$instructions, discoveryError=$discoveryError, " +
            "connectUrl=${if (connectUrl == null) "null" else "[REDACTED]"}, " +
            "connectionId=$connectionId, attempt=$attempt, requiredEnv=$requiredEnv, " +
            "tools=$tools, hint=$hint, catalog=$catalog)"
}

/** Host-resolved catalog row facts (hermes-agent `manage_catalog`); the model supplies only the id. */
data class ConnectionCatalogInfo(
    val display: String,
    val description: String?,
    val tier: String?,
    val platforms: List<String>,
    val repo: String?,
    val sha: String?,
    val subdir: String?,
    val scan: ConnectionCatalogScan?,
    val requirements: List<String>,
    val targetProfile: String,
    /** On an installed row: the skill the model can now load. */
    val skill: String?,
)

data class ConnectionCatalogScan(
    val status: CatalogScanStatus,
    val summary: String?,
)

enum class CatalogScanStatus { PASSED, WARNINGS, FAILED, UNKNOWN }

data class ConnectionEnvField(
    val name: String,
    val required: Boolean,
    val secret: Boolean,
    val defaultValue: String?,
    val prompt: String?,
)

enum class ConnectionTargetKind {
    CONNECTOR,
    MCP,
    PLUGIN,
    SKILL,
    UNKNOWN,
    ;

    val isCatalog: Boolean get() = this == PLUGIN || this == SKILL
}

enum class ConnectionTargetAction {
    AUTHORIZE,
    CONNECT,
    ENABLE,
    INSTALL,
    RECONNECT,
    UNKNOWN,
}

enum class ConnectionTargetState {
    PENDING,
    INITIATED,
    CONNECTED,
    SKIPPED,
    FAILED,
    EXPIRED,
    UNAVAILABLE,
    NOT_CONNECTED,
    UNKNOWN,
}
