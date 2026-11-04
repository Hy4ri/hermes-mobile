package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.Serializable

/** `mcp.servers.status` takes no explicit params; the active profile is injected by WsProfileParams (#419). */
@Serializable
data object McpServersStatusParams
