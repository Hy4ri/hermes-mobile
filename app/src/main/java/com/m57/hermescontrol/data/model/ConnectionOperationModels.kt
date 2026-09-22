package com.m57.hermescontrol.data.model

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
    val requiredEnv: List<ConnectionEnvField>,
    val tools: List<String>,
)

data class ConnectionEnvField(
    val name: String,
    val required: Boolean,
    val secret: Boolean,
    val defaultValue: String?,
    val prompt: String?,
)

enum class ConnectionTargetKind { CONNECTOR, MCP, UNKNOWN }
enum class ConnectionTargetAction { AUTHORIZE, CONNECT, ENABLE, INSTALL, RECONNECT, UNKNOWN }
enum class ConnectionTargetState { PENDING, INITIATED, CONNECTED, SKIPPED, FAILED, EXPIRED, UNAVAILABLE, NOT_CONNECTED, UNKNOWN }
