package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.Serializable

/** `profiles.list` takes no explicit params here; the active profile is injected by WsProfileParams. */
@Serializable
data object ProfilesListParams
