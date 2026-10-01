package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.Serializable

/**
 * `process.stop` kills every background process in the gateway registry (the desktop `/stop` second half). Desktop
 * sends `{}`: the backend call is global, so no `session_id` or `profile` override is sent.
 */
@Serializable
data object ProcessStopParams

@Serializable
data class ProcessStopResult(
    val killed: Int? = null,
)
