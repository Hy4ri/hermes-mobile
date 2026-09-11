package com.m57.hermescontrol.ui.mcp

import com.m57.hermescontrol.data.model.AddMcpServerRequest

object AddServerRequestBuilder {
    /**
     * Constructs a validated AddMcpServerRequest from the add-server form parameters.
     */
    fun build(
        name: String,
        mode: AddServerMode,
        url: String,
        command: String,
        args: String,
        auth: String,
        bearerToken: String,
    ): AddMcpServerRequest {
        val isHttp = mode == AddServerMode.HTTP
        val isStdio = mode == AddServerMode.Stdio

        val resolvedUrl = if (isHttp) url.trim().ifBlank { null } else null
        val resolvedCommand = if (isStdio) command.trim().ifBlank { null } else null
        val resolvedArgs =
            if (isStdio && args.isNotBlank()) {
                args.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            } else {
                null
            }
        val resolvedAuth = if (isHttp && auth != "none") auth else null
        val resolvedBearerToken =
            if (isHttp && auth == "header") bearerToken.trim().ifBlank { null } else null

        return AddMcpServerRequest(
            name = name.trim(),
            url = resolvedUrl,
            command = resolvedCommand,
            args = resolvedArgs,
            auth = resolvedAuth,
            bearerToken = resolvedBearerToken,
        )
    }

    /**
     * Overload reading form parameters directly from McpServersUiState.
     */
    fun build(state: McpServersUiState): AddMcpServerRequest =
        build(
            name = state.addServerName,
            mode = state.addMode,
            url = state.addServerUrl,
            command = state.addServerCommand,
            args = state.addServerArgs,
            auth = state.addServerAuth,
            bearerToken = state.addServerBearerToken,
        )
}
