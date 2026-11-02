package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.Serializable

/** `subagent.roster` takes no explicit params; the active profile is injected by WsProfileParams (#387). */
@Serializable
data object SubagentRosterParams
