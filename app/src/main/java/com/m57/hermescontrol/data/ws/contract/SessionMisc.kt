package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data object SessionListParams

@Serializable
data class SessionIdParams(
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class SessionBranchParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("name") val name: String? = null,
)

@Serializable
data class SessionBranchWholeParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("name") val name: String? = null,
)

@Serializable
data class SessionCompressParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("focus_topic") val focusTopic: String? = null,
)

@Serializable
data class PromptBtwParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("text") val text: String,
)

@Serializable
data class PromptBtwResult(
    @SerialName("task_id") val taskId: String? = null,
)

/** `session.workspace.move`: [sessionKey] is the STORED session id; [cwd] the target folder. */
@Serializable
data class SessionWorkspaceMoveParams(
    @SerialName("session_key") val sessionKey: String,
    @SerialName("cwd") val cwd: String,
)

@Serializable
data class SessionWorkspaceMoveResult(
    @SerialName("cwd") val cwd: String,
    @SerialName("branch") val branch: String? = null,
    @SerialName("git_repo_root") val gitRepoRoot: String? = null,
)
