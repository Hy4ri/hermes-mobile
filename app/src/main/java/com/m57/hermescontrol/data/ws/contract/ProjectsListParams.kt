package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.Serializable

/** `projects.list` takes no explicit params; the active profile is injected by WsProfileParams. */
@Serializable
data object ProjectsListParams
